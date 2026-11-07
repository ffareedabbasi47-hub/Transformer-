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
  float mq2Mv;       float mq2BaseMv;   // diagnostics only (shown in /api/health + Serial)
  int smokePct;      int flamePct;      // signal strength 0..100
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
    // Pull towards the 'no flame' level so an unplugged flame module cannot raise a false EMERGENCY
    // (a disconnected flame module CANNOT be detected: digital output only, see README limitations).
    pinMode(PIN_FLAME_DO, FLAME_ACTIVE_LOW ? INPUT_PULLUP : INPUT_PULLDOWN);

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
      if (!tempFilter_.valid()) tempAvg_.reset();   // never mix old values into a recovered reading
    }
  }

  // One protection cycle (every SENSOR_CYCLE_MS).
  const SensorReadings& sample(uint32_t now) {
    // --- Temperature (value comes from the averaged valid readings) ---
    r_.tempValid = HAS_TEMP_SENSOR && tempFilter_.valid() && tempAvg_.hasData();
    r_.tempC = tempAvg_.value();

    // --- Current: ADC mV -> amps using the calibrated zero offset ---
    float adcMv = readAvgMv(PIN_ACS712, ADC_SAMPLES_PER_CYCLE);
    bool curOk = zeroOk_ && adcMv >= ACS_ADC_MIN_MV && adcMv <= ACS_ADC_MAX_MV;
    if (curOk) {
      float amps = fabsf((adcMv - zeroAdcMv_) / (ACS_MV_PER_A * ACS_DIVIDER_FACTOR));
      currentAvg_.push(amps);
    }
    currentFilter_.update(curOk);
    if (!currentFilter_.valid()) currentAvg_.reset();
    float a = currentAvg_.value();
    r_.currentA = (a < ACS_DEADBAND_A) ? 0.0f : a;
    r_.currentValid = HAS_CURRENT_SENSOR && currentFilter_.valid() && currentAvg_.hasData();

    // --- Voltage: ADC mV -> volts using module ratio and calibration factor ---
    float vMv = readAvgMv(PIN_VOLTAGE, ADC_SAMPLES_PER_CYCLE);
    bool vOk = vMv <= VOLT_ADC_MAX_MV;  // above = ADC saturated: input too high
    if (vOk) voltAvg_.push(vMv / 1000.0f * VOLT_MODULE_RATIO * VOLT_CAL_FACTOR);
    voltageFilter_.update(vOk);
    if (!voltageFilter_.valid()) voltAvg_.reset();
    r_.voltageV = voltAvg_.value();
    r_.voltageValid = HAS_VOLTAGE_SENSOR && voltageFilter_.valid() && voltAvg_.hasData();

    // --- MQ-2: demo indicator, relative to a clean-air baseline ---
    float mq = readAvgMv(PIN_MQ2_AO, ADC_SAMPLES_PER_CYCLE);
    mq2Avg_.push(mq);
    const float mqAvg = mq2Avg_.value();
    if (!mq2Ready_) {
      mq2Baseline_ = mqAvg;                              // while the heater warms up the baseline simply follows it
      if ((uint32_t)(now - mq2Start_) >= MQ2_WARMUP_MS) mq2Ready_ = true;
      r_.smoke = false;  // never report smoke from a cold sensor
    } else {
      float delta = mqAvg - mq2Baseline_;
      if (!smokeLatched_ && delta >= MQ2_DELTA_ENTER_MV) smokeLatched_ = true;
      else if (smokeLatched_ && delta <= MQ2_DELTA_EXIT_MV) smokeLatched_ = false;
      // Clean air: let the baseline follow slow drift. Never while smoke is present or rising.
      if (!smokeLatched_ && delta < MQ2_DELTA_ENTER_MV * 0.5f) mq2Baseline_ += (mqAvg - mq2Baseline_) * MQ2_BASELINE_ALPHA;
      r_.smoke = smokeLatched_;
    }
    r_.mq2Mv = mqAvg; r_.mq2BaseMv = mq2Baseline_;
    {
      int sp = 0;
      if (mq2Ready_) {
        const float d = mqAvg - mq2Baseline_;
        sp = (int)lroundf((d - MQ2_NOISE_MV) / (float)(MQ2_FULL_DELTA_MV - MQ2_NOISE_MV) * 100.0f);
        if (sp < 0) sp = 0;
        if (sp > 100) sp = 100;
        if (smokeLatched_ && sp < 1) sp = 1;   // latched smoke always shows as present
      }
      r_.smokePct = sp;
    }
    r_.mq2Ready = mq2Ready_;

    // --- Flame: digital input, debounced ---
    bool raw = digitalRead(PIN_FLAME_DO) == (FLAME_ACTIVE_LOW ? LOW : HIGH);
    if (raw) { if (flameCount_ < 100) flameCount_++; } else flameCount_ = 0;
    const bool flameDo = flameCount_ >= FLAME_DEBOUNCE_CYCLES;
    int fp = flameDo ? 100 : 0;                        // digital output: on/off
    if (HAS_FLAME_ANALOG) {                            // analog output: how strong (weak ... strong)
      flameAoAvg_.push(readAvgMv(PIN_FLAME_AO, ADC_SAMPLES_PER_CYCLE));
      int ap = (int)lroundf((FLAME_AO_CLEAR_MV - flameAoAvg_.value()) / (float)(FLAME_AO_CLEAR_MV - FLAME_AO_FULL_MV) * 100.0f);
      if (ap < 0) ap = 0;
      if (ap > 100) ap = 100;
      if (ap > fp) fp = ap;
    }
    r_.flamePct = fp;
    r_.flame = flameDo || fp >= FLAME_CRIT_PCT;

    // --- Water level (DEMO ONLY, water, never oil) ---
    float lv = readAvgMv(PIN_LEVEL, ADC_SAMPLES_PER_CYCLE);
    levelAvg_.push(lv);
    float span = (float)(LEVEL_FULL_MV - LEVEL_DRY_MV);
    float pct = (levelAvg_.value() - LEVEL_DRY_MV) / span * 100.0f;
    if (pct < 0) pct = 0;
    if (pct > 100) pct = 100;
    r_.oilPct = (int)lroundf(pct);
    r_.oilValid = HAS_LEVEL_SENSOR;

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
  MovingAverage<4> flameAoAvg_;
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
