// Sensor layer: reads, validates and filters every sensor. No sensor value is
// ever invented: a sensor that cannot be trusted is reported as INVALID.
#pragma once
#include <Arduino.h>
#include <OneWire.h>
#include <DallasTemperature.h>
#include "config.h"
#include "util.h"

struct SensorReadings {
  bool tempValid;    float tempC;
  bool currentValid; float currentA;
  bool voltageValid; float voltageV;
  bool smoke;        bool mq2Ready;
  bool flame;
  bool oilValid;     int oilPct;
};

class SensorHub {
 public:
  SensorHub()
      : ds_(&oneWire_), tempFilter_(INVALID_AFTER_BAD_CYCLES / 2, VALID_AFTER_GOOD_CYCLES),
        currentFilter_(INVALID_AFTER_BAD_CYCLES, VALID_AFTER_GOOD_CYCLES),
        voltageFilter_(INVALID_AFTER_BAD_CYCLES, VALID_AFTER_GOOD_CYCLES) {}

  // Blocking, called once in setup(). Relay MUST already be in cutoff so the
  // ACS712 zero-current offset can be measured with no load current.
  void begin() {
    analogReadResolution(12);
    analogSetAttenuation(ADC_11db);  // ~0-3.1V usable range
    pinMode(PIN_FLAME_DO, INPUT);

    ds_.begin();
    ds_.setResolution(10);
    ds_.setWaitForConversion(true);
    ds_.requestTemperatures();
    float t = ds_.getTempCByIndex(0);
    bool ok = tempPlausible(t, /*firstReading=*/true);
    tempFilter_.prime(ok);
    if (ok) { tempAvg_.push(t); }
    ds_.setWaitForConversion(false);  // non-blocking from now on

    // ACS712 zero-current calibration (average of 200 samples).
    zeroAdcMv_ = 0;
    for (int i = 0; i < 200; i++) { zeroAdcMv_ += readAvgMv(PIN_ACS712, 4); delay(2); }
    zeroAdcMv_ /= 200.0f;
    zeroOk_ = (zeroAdcMv_ >= ACS_ZERO_MIN_MV && zeroAdcMv_ <= ACS_ZERO_MAX_MV);
    currentFilter_.prime(zeroOk_);

    float vMv = readAvgMv(PIN_VOLTAGE, ADC_SAMPLES_PER_CYCLE);
    voltageFilter_.prime(vMv <= VOLT_ADC_MAX_MV);

    mq2Start_ = millis();
    r_ = SensorReadings{};
  }

  // Non-blocking DS18B20 state machine; call every loop().
  void pollTemperature(uint32_t now) {
    if (!tempPending_) {
      if ((uint32_t)(now - tempLastRequest_) >= TEMP_REQUEST_EVERY_MS) {
        ds_.requestTemperatures();
        tempPending_ = true;
        tempRequestedAt_ = now;
      }
    } else if ((uint32_t)(now - tempRequestedAt_) >= TEMP_CONVERSION_MS) {
      float t = ds_.getTempCByIndex(0);
      tempPending_ = false;
      tempLastRequest_ = now;
      bool ok = tempPlausible(t, !tempAvg_.hasData());
      if (ok) tempAvg_.push(t);
      tempFilter_.update(ok);
    }
  }

  // One protection cycle (every SENSOR_CYCLE_MS).
  const SensorReadings& sample(uint32_t now) {
    // --- Temperature (value comes from the averaged valid readings) ---
    r_.tempValid = tempFilter_.valid() && tempAvg_.hasData();
    r_.tempC = tempAvg_.value();

    // --- Current: ADC mV -> amps using the calibrated zero offset ---
    float adcMv = readAvgMv(PIN_ACS712, ADC_SAMPLES_PER_CYCLE);
    bool curOk = zeroOk_ && adcMv >= ACS_ADC_MIN_MV && adcMv <= ACS_ADC_MAX_MV;
    if (curOk) {
      float amps = fabsf((adcMv - zeroAdcMv_) / (ACS_MV_PER_A * ACS_DIVIDER_FACTOR));
      currentAvg_.push(amps);
    }
    currentFilter_.update(curOk);
    float a = currentAvg_.value();
    r_.currentA = (a < ACS_DEADBAND_A) ? 0.0f : a;
    r_.currentValid = currentFilter_.valid() && currentAvg_.hasData();

    // --- Voltage: ADC mV -> volts using module ratio and calibration factor ---
    float vMv = readAvgMv(PIN_VOLTAGE, ADC_SAMPLES_PER_CYCLE);
    bool vOk = vMv <= VOLT_ADC_MAX_MV;  // above = ADC saturated: input too high
    if (vOk) voltAvg_.push(vMv / 1000.0f * VOLT_MODULE_RATIO * VOLT_CAL_FACTOR);
    voltageFilter_.update(vOk);
    r_.voltageV = voltAvg_.value();
    r_.voltageValid = voltageFilter_.valid() && voltAvg_.hasData();

    // --- MQ-2: demo indicator, relative to a clean-air baseline ---
    float mq = readAvgMv(PIN_MQ2_AO, ADC_SAMPLES_PER_CYCLE);
    mq2Avg_.push(mq);
    if (!mq2Ready_) {
      if ((uint32_t)(now - mq2Start_) >= MQ2_WARMUP_MS) {
        mq2Baseline_ = mq2Avg_.value();
        mq2Ready_ = true;
      }
      r_.smoke = false;  // never report smoke from a cold sensor
    } else {
      float delta = mq2Avg_.value() - mq2Baseline_;
      if (!smokeLatched_ && delta >= MQ2_DELTA_ENTER_MV) smokeLatched_ = true;
      else if (smokeLatched_ && delta <= MQ2_DELTA_EXIT_MV) smokeLatched_ = false;
      r_.smoke = smokeLatched_;
    }
    r_.mq2Ready = mq2Ready_;

    // --- Flame: digital input, debounced ---
    bool raw = digitalRead(PIN_FLAME_DO) == (FLAME_ACTIVE_LOW ? LOW : HIGH);
    if (raw) { if (flameCount_ < 100) flameCount_++; } else flameCount_ = 0;
    r_.flame = flameCount_ >= FLAME_DEBOUNCE_CYCLES;

    // --- Water level (DEMO ONLY, water, never oil) ---
    float lv = readAvgMv(PIN_LEVEL, ADC_SAMPLES_PER_CYCLE);
    levelAvg_.push(lv);
    float span = (float)(LEVEL_FULL_MV - LEVEL_DRY_MV);
    float pct = (levelAvg_.value() - LEVEL_DRY_MV) / span * 100.0f;
    if (pct < 0) pct = 0;
    if (pct > 100) pct = 100;
    r_.oilPct = (int)lroundf(pct);
    r_.oilValid = true;

    return r_;
  }

 private:
  static float readAvgMv(int pin, int samples) {
    uint32_t sum = 0;
    for (int i = 0; i < samples; i++) sum += analogReadMilliVolts(pin);
    return (float)sum / (float)samples;
  }
  static bool tempPlausible(float t, bool firstReading) {
    if (t == DEVICE_DISCONNECTED_C) return false;
    if (isnan(t) || t < DS_MIN_C || t > DS_MAX_C) return false;
    if (firstReading && t == 85.0f) return false;  // DS18B20 power-on reset value
    return true;
  }

  OneWire oneWire_{PIN_DS18B20};
  DallasTemperature ds_;
  ValidityFilter tempFilter_, currentFilter_, voltageFilter_;
  MovingAverage<4> tempAvg_;
  MovingAverage<AVG_WINDOW_CYCLES> currentAvg_, voltAvg_, mq2Avg_, levelAvg_;
  bool tempPending_ = false;
  uint32_t tempRequestedAt_ = 0, tempLastRequest_ = 0;
  float zeroAdcMv_ = 0;
  bool zeroOk_ = false;
  uint32_t mq2Start_ = 0;
  bool mq2Ready_ = false, smokeLatched_ = false;
  float mq2Baseline_ = 0;
  int flameCount_ = 0;
  SensorReadings r_{};
};
