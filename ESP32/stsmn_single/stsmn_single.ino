// =============================================================================
// STSMN - Smart Transformer Safety & Monitoring Network  |  ESP32 firmware
//
// The ESP32 is the protection controller. Sensing, risk calculation, relay
// cutoff, LEDs and buzzer all run here and NEVER depend on the phone.
// The phone only reads GET /api/status (and /api/health).
//
// Libraries (Arduino Library Manager): "OneWire", "DallasTemperature".
// Board: "ESP32 Dev Module" (ESP32 Arduino core 2.x or 3.x).
// LOW-VOLTAGE DEMO ONLY - never connect to 230V mains or a real transformer.
// =============================================================================
#include <WiFi.h>
#include <WebServer.h>

// ===== config.h =====
// =============================================================================
// STSMN firmware configuration  -  the ONE place for pins, thresholds, timing.
// Everything marked "ASSUMPTION" is NOT specified in the architecture PDF and
// must be verified / calibrated on your own hardware before the exhibition.
// This file has no Arduino dependency so risk_engine.h can be tested on a PC.
// =============================================================================
#include <stdint.h>

// ---------- Identity ---------------------------------------------------------
#define STSMN_DEVICE_ID "TR-001"
#define STSMN_FW_VERSION "1.0.0"

// ---------- Wi-Fi (PDF: ESP32 as local SoftAP, GET /api/status) -------------
// SSID and password live in secrets.h (NOT committed to Git, see secrets.h.example).
constexpr int WIFI_AP_CHANNEL = 6;
constexpr int WIFI_AP_MAX_CLIENTS = 3;
// Fixed AP address: the firmware sets it explicitly (WiFi.softAPConfig), so this is the
// address to enter in the app. Serial Monitor also prints the real address at boot.
constexpr uint8_t WIFI_AP_IP[4] = {192, 168, 4, 1};
constexpr int HTTP_PORT = 80;

// ---------- Pins (ASSUMPTION: PDF gives no pins) -----------------------------
// Analog inputs are all on ADC1 (GPIO32-39) because ADC2 cannot be used while
// Wi-Fi is on. Strapping pins (0,2,5,12,15) are avoided.
constexpr int PIN_DS18B20   = 4;   // 4.7k pull-up to 3.3V
constexpr int PIN_ACS712    = 34;  // THROUGH 10k/20k divider (sensor is 5V)
constexpr int PIN_VOLTAGE   = 35;  // module output; keep input <= 15V (see docs)
constexpr int PIN_MQ2_AO    = 32;  // THROUGH 10k/20k divider (module is 5V)
constexpr int PIN_LEVEL     = 33;  // water-level sensor powered from 3.3V
constexpr int PIN_FLAME_DO  = 25;  // digital output of flame module
constexpr int PIN_RELAY     = 26;
constexpr int PIN_LED_GREEN = 18;
constexpr int PIN_LED_YELLOW= 19;
constexpr int PIN_LED_RED   = 23;
constexpr int PIN_BUZZER    = 27;  // ASSUMPTION: ACTIVE buzzer (on/off)

// ---------- Relay polarity (VERIFY ON YOUR MODULE!) --------------------------
// Logical meaning is fixed: relay=true -> supply allowed, relay=false -> cutoff.
// Wire the load through COM + NO so that a dead/unpowered ESP32 = cutoff.
constexpr bool RELAY_ACTIVE_LOW = true;    // most 1-channel modules are active-low
constexpr bool FLAME_ACTIVE_LOW = true;    // most flame modules pull LOW on flame
constexpr bool BUZZER_ACTIVE_HIGH = true;

// ---------- Timing -----------------------------------------------------------
constexpr uint32_t SENSOR_CYCLE_MS     = 250;   // one protection cycle (seq++ each cycle)
constexpr uint32_t TEMP_REQUEST_EVERY_MS = 1000;
constexpr uint32_t TEMP_CONVERSION_MS  = 200;   // DS18B20 at 10-bit resolution
constexpr uint32_t BOOT_SELF_CHECK_MS  = 3000;  // relay stays in cutoff until done
constexpr uint32_t MQ2_WARMUP_MS       = 60000; // smoke reported false while warming
constexpr uint32_t RECOVERY_MS         = 10000; // stable time before leaving EMERGENCY

// ---------- Filtering / validity --------------------------------------------
constexpr int ADC_SAMPLES_PER_CYCLE = 16;  // oversampling inside one cycle
constexpr int AVG_WINDOW_CYCLES     = 8;   // moving average (8 x 250ms = 2s)
constexpr uint8_t INVALID_AFTER_BAD_CYCLES = 8; // 2 s of bad samples -> invalid
constexpr uint8_t VALID_AFTER_GOOD_CYCLES  = 4; // 1 s of good samples -> valid again
constexpr int FLAME_DEBOUNCE_CYCLES = 2;   // 500 ms

// ---------- DS18B20 ----------------------------------------------------------
constexpr float DS_MIN_C = -55.0f;
constexpr float DS_MAX_C = 125.0f;

// ---------- ACS712 5A --------------------------------------------------------
constexpr float ACS_MV_PER_A        = 185.0f;      // 5A variant @5V (datasheet)
constexpr float ACS_DIVIDER_FACTOR  = 20.0f/30.0f; // 10k top / 20k bottom
constexpr int   ACS_ZERO_MIN_MV     = 1200;        // idle level at ADC pin sanity range
constexpr int   ACS_ZERO_MAX_MV     = 2100;
constexpr int   ACS_ADC_MIN_MV      = 200;         // runtime: below = disconnected
constexpr int   ACS_ADC_MAX_MV      = 3000;        // runtime: above = saturated
constexpr float ACS_DEADBAND_A      = 0.05f;

// ---------- Voltage module (0-25V) ------------------------------------------
constexpr float VOLT_MODULE_RATIO = 5.0f;  // module divides by 5 (calibrate!)
constexpr float VOLT_CAL_FACTOR   = 1.00f; // multiply after comparing with a multimeter
constexpr int   VOLT_ADC_MAX_MV   = 3100;  // above this the ESP32 ADC saturates
// ASSUMPTION: demo supply is the 3.7V battery. Change when your supply changes.
constexpr float NOMINAL_VOLTAGE_V = 3.7f;

// ---------- MQ-2 (relative threshold, demo indicator only) -------------------
constexpr int MQ2_DELTA_ENTER_MV = 300;  // above clean-air baseline -> smoke
constexpr int MQ2_DELTA_EXIT_MV  = 180;  // hysteresis

// ---------- Water level (DEMO: water only, never transformer oil) ------------
constexpr int LEVEL_DRY_MV  = 0;     // CALIBRATE: reading with dry sensor
constexpr int LEVEL_FULL_MV = 1800;  // CALIBRATE: reading when fully wet
constexpr int OIL_LOW_PERCENT = 30;  // same value as OIL_LOW_PERCENT in the app
constexpr int OIL_OK_PERCENT  = 50;

// ---------- Risk scoring (PDF: 0-25,0-25,0-15,0-15,0-20,0-10) ----------------
// ASSUMPTION: all numeric limits are DEMO values and must be calibrated.
constexpr int PTS_TEMP = 25, PTS_CURRENT = 25, PTS_VOLTAGE = 15;
constexpr int PTS_SMOKE = 15, PTS_FLAME = 20, PTS_OIL = 10;

constexpr float TEMP_WARN_C = 40.0f;   // 0 points below, ramps up
constexpr float TEMP_CRIT_C = 70.0f;   // full points at/above
constexpr float CURRENT_WARN_A = 2.0f;
constexpr float CURRENT_CRIT_A = 4.5f;
constexpr float VOLT_OK_BAND_PCT   = 15.0f;  // +/- % of nominal = no points
constexpr float VOLT_FULL_BAND_PCT = 40.0f;  // +/- % of nominal = full points

// ---------- Status state machine --------------------------------------------
constexpr int WARNING_ENTER   = 40;   // PDF example: 55 = warning
constexpr int WARNING_EXIT    = 30;   // hysteresis
constexpr int EMERGENCY_ENTER = 80;   // PDF example: 90+ = emergency
constexpr int EMERGENCY_EXIT  = 60;   // hysteresis
constexpr int FLOOR_SENSOR_FAULT_RISK = 45;  // a dead protection sensor = WARNING
constexpr int FLOOR_CRITICAL_RISK     = 90;  // any critical condition = >= 90

// Critical conditions (each must hold for the given time -> EMERGENCY)
constexpr uint32_t CRIT_TEMP_HOLD_MS    = 3000;
constexpr uint32_t CRIT_CURRENT_HOLD_MS = 1500;
constexpr uint32_t CRIT_SMOKE_HEAT_HOLD_MS = 2000; // smoke while temp >= TEMP_WARN_C
// Flame (debounced) is critical immediately.

// ===== Wi-Fi settings: CHANGE THESE TWO LINES =====
#define WIFI_AP_SSID "STSMN-TR001"
#define WIFI_AP_PASSWORD "CHANGE_ME_min8chars"  // WPA2 needs 8 to 63 characters

// ===== util.h =====
// Small, dependency-free helpers (filters, debounce). Safe to unit-test on a PC.
#include <stdint.h>
#include <stddef.h>

// Fixed-window moving average (smooths noisy analog readings).
template <size_t N>
class MovingAverage {
 public:
  void push(float v) {
    if (count_ == N) sum_ -= buf_[idx_]; else count_++;
    buf_[idx_] = v;
    sum_ += v;
    idx_ = (idx_ + 1) % N;
  }
  float value() const { return count_ ? sum_ / (float)count_ : 0.0f; }
  bool hasData() const { return count_ > 0; }
  void reset() { idx_ = 0; count_ = 0; sum_ = 0; }
 private:
  float buf_[N] = {0};
  size_t idx_ = 0, count_ = 0;
  float sum_ = 0;
};

// Sensor validity with hysteresis: a single bad sample never invalidates a
// sensor, and a single good sample never re-validates it.
class ValidityFilter {
 public:
  ValidityFilter(uint8_t failLimit, uint8_t recoverLimit)
      : fail_(failLimit), recover_(recoverLimit) {}
  void prime(bool ok) { valid_ = ok; good_ = 0; bad_ = 0; }
  bool update(bool sampleOk) {
    if (sampleOk) {
      bad_ = 0;
      if (good_ < 255) good_++;
      if (!valid_ && good_ >= recover_) valid_ = true;
    } else {
      good_ = 0;
      if (bad_ < 255) bad_++;
      if (valid_ && bad_ >= fail_) valid_ = false;
    }
    return valid_;
  }
  bool valid() const { return valid_; }
 private:
  uint8_t fail_, recover_, good_ = 0, bad_ = 0;
  bool valid_ = false;
};

// "Condition must hold continuously for holdMs" timer.
class HoldTimer {
 public:
  bool update(bool cond, uint32_t now, uint32_t holdMs) {
    if (!cond) { active_ = false; return false; }
    if (!active_) { active_ = true; since_ = now; }
    return (uint32_t)(now - since_) >= holdMs;
  }
  void reset() { active_ = false; }
 private:
  bool active_ = false;
  uint32_t since_ = 0;
};

// ===== risk_engine.h =====
// =============================================================================
// Transparent, deterministic risk engine (NO machine learning).
//
//   1. Each sensor adds a bounded number of points (caps from the PDF).
//   2. Points are summed and clamped to 0..100  ->  "base" score.
//   3. Floors: a dead protection sensor => at least 45; a critical condition => at least 90.
//   4. Status comes from a hysteresis state machine (no flapping), and EMERGENCY
//      is only left after RECOVERY_MS of stable, safe readings.
// =============================================================================
#include <stdint.h>
#include <math.h>

enum class Status : uint8_t { SAFE = 0, WARNING = 1, EMERGENCY = 2 };

inline const char* statusName(Status s) {
  switch (s) {
    case Status::SAFE: return "SAFE";
    case Status::WARNING: return "WARNING";
    default: return "EMERGENCY";
  }
}

struct RiskInput {
  bool tempValid;    float tempC;
  bool currentValid; float currentA;
  bool voltageValid; float voltageV;
  bool smoke;
  bool flame;
  bool oilValid;     int oilPct;
};

struct RiskBreakdown { int temp, current, voltage, smoke, flame, oil, base; };

struct RiskResult {
  int risk;            // final 0..100 (after floors)
  Status status;
  bool critical;       // a critical sensor condition is active
  bool sensorFault;    // at least one protection sensor is invalid
  RiskBreakdown parts; // for Serial debug / judge explanation
};

// 0 points at/below lo, maxPts at/above hi, linear in between.
inline float rampPoints(float x, float lo, float hi, float maxPts) {
  if (isnan(x) || x <= lo) return 0.0f;
  if (x >= hi) return maxPts;
  return maxPts * (x - lo) / (hi - lo);
}

inline RiskBreakdown scoreSensors(const RiskInput& in) {
  RiskBreakdown p = {0, 0, 0, 0, 0, 0, 0};
  if (in.tempValid)    p.temp    = (int)lroundf(rampPoints(in.tempC, TEMP_WARN_C, TEMP_CRIT_C, PTS_TEMP));
  if (in.currentValid) p.current = (int)lroundf(rampPoints(fabsf(in.currentA), CURRENT_WARN_A, CURRENT_CRIT_A, PTS_CURRENT));
  if (in.voltageValid) {
    float devPct = fabsf(in.voltageV - NOMINAL_VOLTAGE_V) / NOMINAL_VOLTAGE_V * 100.0f;
    p.voltage = (int)lroundf(rampPoints(devPct, VOLT_OK_BAND_PCT, VOLT_FULL_BAND_PCT, PTS_VOLTAGE));
  }
  if (in.smoke) p.smoke = PTS_SMOKE;
  if (in.flame) p.flame = PTS_FLAME;
  if (in.oilValid) {
    // Low level: full points at/below OIL_LOW_PERCENT, none at/above OIL_OK_PERCENT.
    p.oil = (int)lroundf(PTS_OIL - rampPoints((float)in.oilPct, (float)OIL_LOW_PERCENT, (float)OIL_OK_PERCENT, (float)PTS_OIL));
  }
  int sum = p.temp + p.current + p.voltage + p.smoke + p.flame + p.oil;
  p.base = sum < 0 ? 0 : (sum > 100 ? 100 : sum);
  return p;
}

class RiskEngine {
 public:
  RiskResult update(const RiskInput& in, uint32_t now) {
    RiskResult r;
    r.parts = scoreSensors(in);
    r.sensorFault = !(in.tempValid && in.currentValid && in.voltageValid);

    bool tempCrit = tempCritTimer_.update(in.tempValid && in.tempC >= TEMP_CRIT_C, now, CRIT_TEMP_HOLD_MS);
    bool curCrit  = currentCritTimer_.update(in.currentValid && fabsf(in.currentA) >= CURRENT_CRIT_A, now, CRIT_CURRENT_HOLD_MS);
    bool smokeHeat = smokeHeatTimer_.update(in.smoke && in.tempValid && in.tempC >= TEMP_WARN_C, now, CRIT_SMOKE_HEAT_HOLD_MS);
    r.critical = in.flame || tempCrit || curCrit || smokeHeat;

    int risk = r.parts.base;
    if (r.sensorFault && risk < FLOOR_SENSOR_FAULT_RISK) risk = FLOOR_SENSOR_FAULT_RISK;
    if (r.critical && risk < FLOOR_CRITICAL_RISK) risk = FLOOR_CRITICAL_RISK;
    r.risk = risk;

    switch (status_) {
      case Status::SAFE:
        if (r.critical || risk >= EMERGENCY_ENTER) enterEmergency();
        else if (risk >= WARNING_ENTER) status_ = Status::WARNING;
        break;
      case Status::WARNING:
        if (r.critical || risk >= EMERGENCY_ENTER) enterEmergency();
        else if (risk < WARNING_EXIT) status_ = Status::SAFE;
        break;
      case Status::EMERGENCY:
        if (r.critical || risk >= EMERGENCY_EXIT) {
          recovering_ = false;            // still unsafe: restart the recovery clock
        } else {
          if (!recovering_) { recovering_ = true; recoveryStart_ = now; }
          if ((uint32_t)(now - recoveryStart_) >= RECOVERY_MS) {
            status_ = (risk >= WARNING_ENTER) ? Status::WARNING : Status::SAFE;
            recovering_ = false;
          }
        }
        break;
    }
    r.status = status_;
    return r;
  }
  Status status() const { return status_; }

 private:
  void enterEmergency() { status_ = Status::EMERGENCY; recovering_ = false; }
  Status status_ = Status::SAFE;
  bool recovering_ = false;
  uint32_t recoveryStart_ = 0;
  HoldTimer tempCritTimer_, currentCritTimer_, smokeHeatTimer_;
};

// ===== sensors.h =====
// Sensor layer: reads, validates and filters every sensor. No sensor value is
// ever invented: a sensor that cannot be trusted is reported as INVALID.
#include <Arduino.h>
#include <OneWire.h>
#include <DallasTemperature.h>

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

// ===== actuators.h =====
// Relay, LEDs and buzzer. The relay takes a LOGICAL value:
//   allow = true  -> supply allowed
//   allow = false -> cutoff
// and maps it to the real GPIO level using RELAY_ACTIVE_LOW from config.h.
#include <Arduino.h>

class Actuators {
 public:
  // Call FIRST in setup(): put the relay into cutoff before anything else.
  void begin() {
    pinMode(PIN_RELAY, OUTPUT);
    setRelayAllowed(false);
    pinMode(PIN_LED_GREEN, OUTPUT);
    pinMode(PIN_LED_YELLOW, OUTPUT);
    pinMode(PIN_LED_RED, OUTPUT);
    pinMode(PIN_BUZZER, OUTPUT);
    setBuzzer(false);
    allLeds(false);
  }

  void setRelayAllowed(bool allow) {
    relayAllowed_ = allow;
    const int onLevel  = RELAY_ACTIVE_LOW ? LOW : HIGH;   // coil energized = supply allowed
    const int offLevel = RELAY_ACTIVE_LOW ? HIGH : LOW;
    digitalWrite(PIN_RELAY, allow ? onLevel : offLevel);
  }
  bool relayAllowed() const { return relayAllowed_; }

  // True if the relay pin no longer reads back the level we commanded.
  bool relayMismatch() const {
    const int onLevel  = RELAY_ACTIVE_LOW ? LOW : HIGH;
    const int offLevel = RELAY_ACTIVE_LOW ? HIGH : LOW;
    return digitalRead(PIN_RELAY) != (relayAllowed_ ? onLevel : offLevel);
  }

  // Green = SAFE, Yellow = WARNING, Red (blinking) + buzzer = EMERGENCY.
  void applyStatus(Status s, bool alarm, uint32_t now) {
    bool blink = ((now / 250) % 2) == 0;
    digitalWrite(PIN_LED_GREEN,  s == Status::SAFE ? HIGH : LOW);
    digitalWrite(PIN_LED_YELLOW, s == Status::WARNING ? HIGH : LOW);
    digitalWrite(PIN_LED_RED,    s == Status::EMERGENCY ? (blink ? HIGH : LOW) : LOW);
    setBuzzer(alarm && blink);  // pulsing alarm tone
  }

 private:
  void setBuzzer(bool on) { digitalWrite(PIN_BUZZER, (on == BUZZER_ACTIVE_HIGH) ? HIGH : LOW); }
  void allLeds(bool on) {
    digitalWrite(PIN_LED_GREEN, on); digitalWrite(PIN_LED_YELLOW, on); digitalWrite(PIN_LED_RED, on);
  }
  bool relayAllowed_ = false;
};

// ===== main sketch =====
static WebServer server(HTTP_PORT);
static SensorHub sensors;
static Actuators actuators;
static RiskEngine riskEngine;

static char statusJson[384] = "";   // latest complete state, served as-is
static uint32_t seq = 0;
static uint32_t lastCycle = 0;
static bool selfCheckDone = false;
static RiskResult lastRisk{};
static SensorReadings lastReadings{};
static bool alarmOn = false;

// ---- JSON helpers: invalid sensors are sent as null, never as a fake number --
static void fmtFloat(char* out, size_t n, bool valid, float v, int decimals) {
  if (!valid || isnan(v) || isinf(v)) snprintf(out, n, "null");
  else snprintf(out, n, "%.*f", decimals, v);
}

static void buildStatusJson(const SensorReadings& s, const RiskResult& r, bool relay, bool alarm) {
  char t[16], v[16], c[16], oil[16];
  fmtFloat(t, sizeof(t), s.tempValid, s.tempC, 1);
  fmtFloat(v, sizeof(v), s.voltageValid, s.voltageV, 2);
  fmtFloat(c, sizeof(c), s.currentValid, s.currentA, 2);
  if (s.oilValid) snprintf(oil, sizeof(oil), "%d", s.oilPct); else snprintf(oil, sizeof(oil), "null");
  const int written = snprintf(statusJson, sizeof(statusJson),
    "{\"deviceId\":\"%s\",\"fw\":\"%s\",\"uptime\":%lu,\"temperature\":%s,\"voltage\":%s,"
    "\"current\":%s,\"smoke\":%s,\"flame\":%s,\"oilLevel\":%s,\"risk\":%d,"
    "\"status\":\"%s\",\"relay\":%s,\"alarm\":%s,\"seq\":%lu}",
    STSMN_DEVICE_ID, STSMN_FW_VERSION, (unsigned long)millis(), t, v, c,
    s.smoke ? "true" : "false", s.flame ? "true" : "false", oil, r.risk,
    statusName(r.status), relay ? "true" : "false", alarm ? "true" : "false",
    (unsigned long)seq);
  // Never serve a half-written JSON: on overflow the app gets an explicit invalid packet (-> STALE), not a wrong value.
  if (written < 0 || written >= (int)sizeof(statusJson)) {
    snprintf(statusJson, sizeof(statusJson), "{\"error\":\"status buffer overflow\"}");
  }
}

// ---- HTTP handlers (read-only; no control endpoints in v1) -------------------
static void handleStatus() {
  server.sendHeader("Cache-Control", "no-store");
  server.sendHeader("X-Content-Type-Options", "nosniff");
  server.send(200, "application/json", statusJson);
}

static void handleHealth() {
  char buf[320];
  snprintf(buf, sizeof(buf),
    "{\"deviceId\":\"%s\",\"fw\":\"%s\",\"uptime\":%lu,\"seq\":%lu,\"clients\":%d,"
    "\"mq2Ready\":%s,\"tempValid\":%s,\"currentValid\":%s,\"voltageValid\":%s,\"relayMismatch\":%s}",
    STSMN_DEVICE_ID, STSMN_FW_VERSION, (unsigned long)millis(), (unsigned long)seq,
    WiFi.softAPgetStationNum(),
    lastReadings.mq2Ready ? "true" : "false", lastReadings.tempValid ? "true" : "false",
    lastReadings.currentValid ? "true" : "false", lastReadings.voltageValid ? "true" : "false",
    actuators.relayMismatch() ? "true" : "false");
  server.sendHeader("Cache-Control", "no-store");
  server.sendHeader("X-Content-Type-Options", "nosniff");
  server.send(200, "application/json", buf);
}

static void handleNotFound() { server.send(404, "application/json", "{\"error\":\"not found\"}"); }

// ---- One protection cycle ----------------------------------------------------
static void protectionCycle(uint32_t now) {
  lastReadings = sensors.sample(now);

  RiskInput in{lastReadings.tempValid, lastReadings.tempC,
               lastReadings.currentValid, lastReadings.currentA,
               lastReadings.voltageValid, lastReadings.voltageV,
               lastReadings.smoke, lastReadings.flame,
               lastReadings.oilValid, lastReadings.oilPct};
  lastRisk = riskEngine.update(in, now);

  if (!selfCheckDone && now >= BOOT_SELF_CHECK_MS) selfCheckDone = true;

  // Relay: supply allowed only after the boot self-check and never in EMERGENCY.
  bool allow = selfCheckDone && lastRisk.status != Status::EMERGENCY;
  actuators.setRelayAllowed(allow);

  alarmOn = (lastRisk.status == Status::EMERGENCY);
  seq++;
  buildStatusJson(lastReadings, lastRisk, actuators.relayAllowed(), alarmOn);

  // Serial log makes the score explainable to judges (115200 baud).
  Serial.printf("seq=%lu %s risk=%d [T%d I%d V%d S%d F%d O%d] relay=%d crit=%d\n",
    (unsigned long)seq, statusName(lastRisk.status), lastRisk.risk,
    lastRisk.parts.temp, lastRisk.parts.current, lastRisk.parts.voltage,
    lastRisk.parts.smoke, lastRisk.parts.flame, lastRisk.parts.oil,
    actuators.relayAllowed(), lastRisk.critical);
}

void setup() {
  actuators.begin();            // relay -> cutoff before anything else
  Serial.begin(115200);
  Serial.println("\nSTSMN firmware " STSMN_FW_VERSION);

  sensors.begin();              // includes ACS712 zero calibration (no load)

  WiFi.mode(WIFI_AP);
  IPAddress apIp(WIFI_AP_IP[0], WIFI_AP_IP[1], WIFI_AP_IP[2], WIFI_AP_IP[3]);
  WiFi.softAPConfig(apIp, apIp, IPAddress(255, 255, 255, 0));
  WiFi.softAP(WIFI_AP_SSID, WIFI_AP_PASSWORD, WIFI_AP_CHANNEL, 0, WIFI_AP_MAX_CLIENTS);
  Serial.print("AP IP: "); Serial.println(WiFi.softAPIP());

  server.on("/api/status", HTTP_GET, handleStatus);
  server.on("/api/health", HTTP_GET, handleHealth);
  server.onNotFound(handleNotFound);
  server.begin();

  // First state is computed from real readings before anyone can request it.
  protectionCycle(millis());
  lastCycle = millis();
}

void loop() {
  uint32_t now = millis();
  sensors.pollTemperature(now);                 // non-blocking
  if ((uint32_t)(now - lastCycle) >= SENSOR_CYCLE_MS) {
    lastCycle = now;
    protectionCycle(now);
  }
  actuators.applyStatus(lastRisk.status, alarmOn, now);
  server.handleClient();                        // phone is optional; this never gates protection
}
