#include <WiFi.h>
#include <WebServer.h>
#include <esp_task_wdt.h>
#include <freertos/FreeRTOS.h>
#include <freertos/task.h>
#include <freertos/semphr.h>
#include <esp_timer.h>
#include <esp_system.h>
#include <Preferences.h>

enum class Status : uint8_t;
struct RiskInput;
struct RiskBreakdown;
struct RiskResult;
struct RiskPct;
struct SensorReadings;

#include <stdint.h>

#define STSMN_DEVICE_ID "TR-001"
#define STSMN_FW_VERSION "1.0.0"

constexpr int WIFI_AP_CHANNEL = 6;
constexpr int WIFI_AP_MAX_CLIENTS = 3;

constexpr uint8_t WIFI_AP_IP[4] = {192, 168, 4, 1};
constexpr int HTTP_PORT = 80;

constexpr bool HAS_TEMP_SENSOR    = true;
constexpr bool HAS_CURRENT_SENSOR = true;

constexpr bool AUTO_IGNORE_UNPLUGGED = true;
constexpr bool HAS_VOLTAGE_SENSOR = true;
constexpr bool HAS_LEVEL_SENSOR   = true;

constexpr bool HAS_FLAME_ANALOG   = true;

constexpr int PIN_DS18B20   = 4;
constexpr int PIN_ACS712    = 34;
constexpr int PIN_VOLTAGE   = 35;
constexpr int PIN_MQ2_AO    = 32;
constexpr int PIN_LEVEL     = 33;
constexpr int PIN_FLAME_DO  = 25;
constexpr int PIN_FLAME_AO  = 36;
constexpr int PIN_RELAY     = 26;
constexpr int PIN_LED_GREEN = 18;
constexpr int PIN_LED_YELLOW= 19;
constexpr int PIN_LED_RED   = 23;
constexpr int PIN_BUZZER    = 27;

#define ENABLE_BLE 0
#define BLE_DEVICE_NAME "STSMN-TR001"

#define BLE_SERVICE_UUID "6e400001-b5a3-f393-e0a9-e50e24dcca9e"
#define BLE_RX_UUID      "6e400002-b5a3-f393-e0a9-e50e24dcca9e"
#define BLE_TX_UUID      "6e400003-b5a3-f393-e0a9-e50e24dcca9e"
constexpr uint32_t BLE_STATUS_EVERY_MS = 1000;
constexpr uint32_t BLE_HEALTH_EVERY_MS = 5000;

constexpr int PIN_GPS_RX = 16;
constexpr int PIN_GPS_TX = 17;
constexpr uint32_t GPS_BAUD = 9600;
constexpr uint32_t GPS_FIX_TIMEOUT_MS = 10000;

constexpr int PIN_SIM_RX = 13;
constexpr int PIN_SIM_TX = 14;
constexpr uint32_t SIM_BAUD = 9600;

constexpr bool     SMS_ON_WARNING      = false;
constexpr uint32_t SMS_MIN_INTERVAL_MS = 120000;
constexpr uint32_t SMS_REPEAT_MS       = 1800000;
constexpr uint32_t GSM_STATUS_EVERY_MS = 30000;
constexpr uint32_t COMM_TASK_STACK     = 8192;

constexpr bool RELAY_ACTIVE_LOW = true;

constexpr bool RELAY_OPEN_DRAIN_OFF = true;
constexpr bool FLAME_ACTIVE_LOW = true;
constexpr bool BUZZER_ACTIVE_HIGH = true;

constexpr uint32_t SENSOR_CYCLE_MS     = 250;
constexpr uint32_t TEMP_REQUEST_EVERY_MS = 1000;
constexpr uint32_t TEMP_CONVERSION_MS  = 200;
constexpr uint32_t BOOT_SELF_CHECK_MS  = 3000;
constexpr uint32_t MQ2_WARMUP_MS       = 60000;
constexpr uint32_t RECOVERY_MS         = 5000;

constexpr int ADC_SAMPLES_PER_CYCLE = 16;
constexpr int AVG_WINDOW_CYCLES     = 8;
constexpr uint8_t INVALID_AFTER_BAD_CYCLES = 8;
constexpr uint8_t VALID_AFTER_GOOD_CYCLES  = 4;
constexpr int FLAME_DEBOUNCE_CYCLES = 2;

constexpr float DS_MIN_C = -55.0f;
constexpr float DS_MAX_C = 125.0f;

constexpr float ACS_MV_PER_A        = 185.0f;
constexpr float ACS_DIVIDER_FACTOR  = 20.0f/30.0f;
constexpr int   ACS_ZERO_MIN_MV     = 1200;
constexpr int   ACS_ZERO_MAX_MV     = 2100;
constexpr int   ACS_ADC_MIN_MV      = 200;
constexpr int   ACS_ADC_MAX_MV      = 3000;
constexpr float ACS_DEADBAND_A      = 0.05f;

constexpr float VOLT_MODULE_RATIO = 5.0f;
constexpr float VOLT_CAL_FACTOR   = 1.00f;
constexpr int   VOLT_ADC_MAX_MV   = 3100;

constexpr float NOMINAL_VOLTAGE_V = 3.7f;

constexpr int MQ2_DELTA_ENTER_MV = 120;
constexpr int MQ2_DELTA_EXIT_MV  = 60;

constexpr float MQ2_BASELINE_ALPHA = 0.002f;
constexpr int MQ2_NOISE_MV      = 30;
constexpr int MQ2_FULL_DELTA_MV = 450;

constexpr bool  FLAME_AO_CALIBRATED   = false;
constexpr bool  FLAME_AO_DROPS_WITH_FLAME = true;

constexpr int   FLAME_MAINS_HZ        = 50;

constexpr int FLAME_OVERSAMPLE = 4;
constexpr float FLAME_FULL_FRACTION   = 0.5f;
constexpr float FLAME_MIN_DELTA_MV    = 40.0f;
constexpr float FLAME_DO_ENTER_RATIO  = 0.35f;
constexpr float FLAME_NOISE_FLOOR_MV  = 5.0f;
constexpr float FLAME_ENTER_SIGMAS    = 6.0f;
constexpr float FLAME_EXIT_RATIO      = 0.5f;
constexpr float FLAME_FLICKER_SIGMAS  = 5.0f;
constexpr float FLAME_FLICKER_FRAC_PER_SIGMA = 0.03f;
constexpr uint32_t FLAME_RELEASE_MS = 6000;
constexpr int   FLAME_DO_MIN_TRANSITIONS = 2;
constexpr int   FLAME_DO_MAX_TRANSITIONS = 20;
constexpr int   FLAME_MIN_REVERSALS   = 4;
constexpr int   FLAME_MAX_REVERSALS   = 28;
constexpr int   FLAME_FLICKER_CYCLES  = 3;
constexpr float FLAME_RISE_FRAC       = 0.5f;
constexpr uint32_t FLAME_RISE_WINDOW_MS = 1000;
constexpr uint32_t FLAME_CORROB_HOLD_MS = 3000;
constexpr uint32_t FLAME_CONFIRM_MS   = 500;
constexpr uint32_t FLAME_INIT_MS      = 3000;
constexpr uint32_t FLAME_INIT_GIVEUP_MS = 15000;
constexpr float FLAME_INIT_MAX_SIGMA_MV = 40.0f;
constexpr float FLAME_BASELINE_ALPHA  = 0.004f;
constexpr uint32_t FLAME_REBASE_MS    = 20000;
constexpr uint32_t FLAME_FALLBACK_HOLD_MS = 10000;
constexpr bool FLAME_DO_CALIBRATED = false;
constexpr bool LEVEL_CALIBRATED = false;
constexpr bool MQ2_CALIBRATED = false;
constexpr uint32_t TRIP_MIN_OPEN_MS = 10000;
constexpr bool OIL_LOW_RAISES_WARNING = false;
constexpr uint32_t OIL_LOW_HOLD_MS = 10000;
constexpr int AUTH_FAIL_LIMIT = 5;
constexpr uint32_t AUTH_LOCKOUT_MS = 30000;
constexpr uint32_t SMS_NEW_EPISODE_MIN_MS = 30000;
constexpr uint32_t GPS_SENTENCE_TTL_MS = 3000;
constexpr int SIM_FAILS_BEFORE_REINIT = 3;
constexpr int EVENT_LOG_SIZE = 16;
constexpr uint32_t BOOT_HOLD_MS = 15000;

constexpr bool  FLAME_CALIBRATION_LOG = false;
constexpr int FLAME_CRIT_PCT    = 40;
constexpr int SIGNAL_MIN_PCT    = 15;

constexpr int LEVEL_DRY_MV  = 0;
constexpr int LEVEL_FULL_MV = 1800;
constexpr int OIL_LOW_PERCENT = 30;
constexpr int OIL_OK_PERCENT  = 50;

constexpr int PTS_SMOKE = 50;
constexpr int PTS_FLAME = 35, PTS_TEMP = 15;
constexpr int PTS_CURRENT = 25, PTS_VOLTAGE = 15, PTS_OIL = 18;
static_assert(PTS_FLAME + PTS_TEMP == PTS_SMOKE, "fire half (flame + temperature) must equal the smoke half");

constexpr float TEMP_WARN_C = 40.0f;
constexpr float TEMP_CRIT_C = 70.0f;
constexpr float CURRENT_WARN_A = 2.0f;
constexpr float CURRENT_CRIT_A = 4.5f;
constexpr float VOLT_OK_BAND_PCT   = 15.0f;
constexpr float VOLT_FULL_BAND_PCT = 40.0f;

constexpr int WARNING_ENTER   = 55;
constexpr int WARNING_EXIT    = 45;
constexpr int EMERGENCY_ENTER = 80;
constexpr int EMERGENCY_EXIT  = 60;
constexpr int FLOOR_SENSOR_FAULT_RISK = 55;
constexpr int SENSOR_WARN_PCT         = 50;
constexpr int FLOOR_STRESS_RISK       = 55;
constexpr int FLOOR_CRITICAL_RISK     = 90;
constexpr int FLOOR_SMOKE_RISK        = 55;

constexpr uint32_t RELAY_ARM_STABLE_MS     = 2000;
constexpr uint32_t SENSOR_FAULT_CUTOFF_MS  = 10000;

constexpr uint32_t WDT_TIMEOUT_S            = 8;
constexpr uint32_t PROTECTION_TASK_STACK    = 10240;
constexpr int      PROTECTION_TASK_PRIORITY = 3;
constexpr uint32_t PROTECTION_LOOP_MS       = 10;

constexpr uint32_t CRIT_TEMP_HOLD_MS    = 3000;
constexpr uint32_t CRIT_CURRENT_HOLD_MS = 1500;
constexpr uint32_t CRIT_SMOKE_HEAT_HOLD_MS = 2000;
constexpr uint32_t CRIT_SMOKE_HOLD_MS      = 3000;

#define WIFI_AP_SSID "STSMN-TR001"
#define WIFI_AP_PASSWORD "CHANGE_ME_min8chars"

#define ALERT_PHONE_NUMBER "+91XXXXXXXXXX"
#define STSMN_API_TOKEN WIFI_AP_PASSWORD

#include <stdint.h>
#include <stddef.h>

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
  int smokePct;      int flamePct;
  bool oilLow;
};

struct RiskBreakdown { int temp, current, voltage, smoke, flame, oil, base; };

inline int effSmokePct(const RiskInput& in) { return in.smokePct > 0 ? (in.smokePct > 100 ? 100 : in.smokePct) : (in.smoke ? 100 : 0); }
inline int effFlamePct(const RiskInput& in) { return in.flamePct > 0 ? (in.flamePct > 100 ? 100 : in.flamePct) : (in.flame ? 100 : 0); }

struct RiskResult {
  int risk;
  Status status;
  bool critical;
  bool sensorFault;
  RiskBreakdown parts;
  bool latched;
  int trips;
  uint32_t resetWaitMs;
};

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
  p.smoke = (int)lroundf(PTS_SMOKE * effSmokePct(in) / 100.0f);
  p.flame = (int)lroundf(PTS_FLAME * effFlamePct(in) / 100.0f);
  if (in.oilValid) {

    p.oil = (int)lroundf(PTS_OIL - rampPoints((float)in.oilPct, (float)OIL_LOW_PERCENT, (float)OIL_OK_PERCENT, (float)PTS_OIL));
  }
  int sum = p.temp + p.current + p.voltage + p.smoke + p.flame + p.oil;
  p.base = sum < 0 ? 0 : (sum > 100 ? 100 : sum);
  return p;
}

struct RiskPct { int temp, current, voltage, smoke, flame; };
inline int rampPct(float x, float lo, float hi) { return (int)lroundf(rampPoints(x, lo, hi, 100.0f)); }
inline RiskPct scorePercent(const RiskInput& in) {
  RiskPct p;
  p.temp    = in.tempValid    ? rampPct(in.tempC, TEMP_WARN_C, TEMP_CRIT_C) : -1;
  p.current = in.currentValid ? rampPct(fabsf(in.currentA), CURRENT_WARN_A, CURRENT_CRIT_A) : -1;
  p.voltage = in.voltageValid ? rampPct(fabsf(in.voltageV - NOMINAL_VOLTAGE_V) / NOMINAL_VOLTAGE_V * 100.0f, VOLT_OK_BAND_PCT, VOLT_FULL_BAND_PCT) : -1;
  p.smoke   = effSmokePct(in);
  p.flame   = effFlamePct(in);
  return p;
}

class RiskEngine {
 public:

  void setFitted(bool temp, bool current, bool voltage) { fitT_ = temp; fitC_ = current; fitV_ = voltage; }
  RiskResult update(const RiskInput& in, uint32_t now) {
    RiskResult r;
    r.parts = scoreSensors(in);
    r.latched = false; r.trips = 0; r.resetWaitMs = 0;

    r.sensorFault = (fitT_ && HAS_TEMP_SENSOR && !in.tempValid) || (fitC_ && HAS_CURRENT_SENSOR && !in.currentValid) ||
                    (fitV_ && HAS_VOLTAGE_SENSOR && !in.voltageValid);

    bool tempCrit = tempCritTimer_.update(in.tempValid && in.tempC >= TEMP_CRIT_C, now, CRIT_TEMP_HOLD_MS);
    bool curCrit  = currentCritTimer_.update(in.currentValid && fabsf(in.currentA) >= CURRENT_CRIT_A, now, CRIT_CURRENT_HOLD_MS);

    bool smokeHeat = smokeHeatTimer_.update(in.smoke && (!in.tempValid || in.tempC >= TEMP_WARN_C), now, CRIT_SMOKE_HEAT_HOLD_MS);
    bool smokeAlone = smokeTimer_.update(in.smoke, now, CRIT_SMOKE_HOLD_MS);

    const bool smokeSignal = in.smoke || effSmokePct(in) >= SIGNAL_MIN_PCT;
    const bool flameSignal = in.flame || effFlamePct(in) >= SIGNAL_MIN_PCT;
    const bool smokeAndFire = smokeSignal && flameSignal;
    if (curCrit && !latched_) { latched_ = true; latchSince_ = now; trips_++; newLatch_ = true; }
    r.critical = in.flame || tempCrit || curCrit || latched_ || smokeHeat || smokeAlone || smokeAndFire;

    int risk = r.parts.base;
    if (r.sensorFault && risk < FLOOR_SENSOR_FAULT_RISK) risk = FLOOR_SENSOR_FAULT_RISK;
    const RiskPct pc = scorePercent(in);
    const bool stressed = pc.temp >= SENSOR_WARN_PCT || pc.current >= SENSOR_WARN_PCT || pc.voltage >= SENSOR_WARN_PCT;
    if (stressed && risk < FLOOR_STRESS_RISK) risk = FLOOR_STRESS_RISK;
    if ((smokeSignal || flameSignal) && risk < FLOOR_SMOKE_RISK) risk = FLOOR_SMOKE_RISK;
    if (OIL_LOW_RAISES_WARNING && in.oilLow && risk < FLOOR_STRESS_RISK) risk = FLOOR_STRESS_RISK;
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
        if (latched_ || r.critical || risk >= EMERGENCY_EXIT) {
          recovering_ = false;
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
    r.latched = latched_; r.trips = trips_;
    if (latched_) { const uint32_t el = (uint32_t)(now - latchSince_); r.resetWaitMs = el >= TRIP_MIN_OPEN_MS ? 0 : TRIP_MIN_OPEN_MS - el; }
    return r;
  }
  Status status() const { return status_; }
  bool latched() const { return latched_; }
  bool justLatched() { const bool v = newLatch_; newLatch_ = false; return v; }
  void restoreLatched(uint32_t now) { latched_ = true; latchSince_ = now; status_ = Status::EMERGENCY; recovering_ = false; }
  bool reset(uint32_t now) {
    if (!latched_) return true;
    if ((uint32_t)(now - latchSince_) < TRIP_MIN_OPEN_MS) return false;
    latched_ = false; status_ = Status::SAFE; recovering_ = false; currentCritTimer_.reset();
    return true;
  }

 private:
  bool fitT_ = true, fitC_ = true, fitV_ = true;
  bool latched_ = false, newLatch_ = false; int trips_ = 0; uint32_t latchSince_ = 0;
  void enterEmergency() { status_ = Status::EMERGENCY; recovering_ = false; }
  Status status_ = Status::SAFE;
  bool recovering_ = false;
  uint32_t recoveryStart_ = 0;
  HoldTimer tempCritTimer_, currentCritTimer_, smokeHeatTimer_, smokeTimer_;
};

#include <stdint.h>

class RelayGate {
 public:

  bool update(uint32_t now, Status status, bool sensorFault) { return update(now, status, sensorFault, false); }

  bool update(uint32_t now, Status status, bool sensorFault, bool hold) {
    if (hold) { armed_ = false; healthy_ = false; faultActive_ = false; return false; }
    if (status == Status::EMERGENCY) {
      armed_ = false; healthy_ = false; faultActive_ = false;
      return false;
    }
    if (sensorFault) {
      healthy_ = false;
      if (!faultActive_) { faultActive_ = true; faultSince_ = now; }
      if (SENSOR_FAULT_CUTOFF_MS != 0 && (uint32_t)(now - faultSince_) >= SENSOR_FAULT_CUTOFF_MS) armed_ = false;
    } else {
      faultActive_ = false;
      if (!healthy_) { healthy_ = true; healthySince_ = now; }
      if (!armed_ && now >= BOOT_SELF_CHECK_MS && (uint32_t)(now - healthySince_) >= RELAY_ARM_STABLE_MS) armed_ = true;
    }
    return armed_;
  }
  bool armed() const { return armed_; }

 private:
  bool armed_ = false, healthy_ = false, faultActive_ = false;
  uint32_t healthySince_ = 0, faultSince_ = 0;
};

#include <stdint.h>
#include <math.h>

enum FlameWhy : uint8_t { FW_OK = 0, FW_LEARNING = 1, FW_NOISY = 2, FW_POLARITY = 3 };
enum FlameEvent : uint8_t { FE_NONE = 0, FE_BASELINE_READY = 1, FE_AO_UNUSABLE_NOISY = 2, FE_POLARITY_BAD = 4, FE_REBASED = 8,
                            FE_CRITICAL_ON = 16, FE_NEAR_ADC_CEILING = 32 };

struct FlameOut {
  int pct;
  bool critical;
  bool aoUsable;
  uint8_t events;
  float mean, base, dev, noise, enter;
  int reversals; float p2p;
  bool corroborated, flicker, rise;
  uint8_t why;
};

struct FlameTuning {
  float minDeltaMv = FLAME_MIN_DELTA_MV;
  float flickerSigmas = FLAME_FLICKER_SIGMAS;
  int minReversals = FLAME_MIN_REVERSALS;
  uint32_t confirmMs = FLAME_CONFIRM_MS;
  uint32_t rebaseMs = FLAME_REBASE_MS;
  bool requireDo = true;
  bool invert = !FLAME_AO_DROPS_WITH_FLAME;
  static constexpr float DELTA_MIN = 10, DELTA_MAX = 300, FLICKER_MIN = 2, FLICKER_MAX = 10;
  static constexpr int REV_MIN = 2, REV_MAX = 12;
  static constexpr uint32_t CONFIRM_MIN = 250, CONFIRM_MAX = 2000, REBASE_MIN = 3000, REBASE_MAX = 60000;
  bool valid() const {
    return isfinite(minDeltaMv) && minDeltaMv >= DELTA_MIN && minDeltaMv <= DELTA_MAX &&
           isfinite(flickerSigmas) && flickerSigmas >= FLICKER_MIN && flickerSigmas <= FLICKER_MAX &&
           minReversals >= REV_MIN && minReversals <= REV_MAX &&
           confirmMs >= CONFIRM_MIN && confirmMs <= CONFIRM_MAX && rebaseMs >= REBASE_MIN && rebaseMs <= REBASE_MAX;
  }
  void clampAll() {
    if (!isfinite(minDeltaMv)) minDeltaMv = FLAME_MIN_DELTA_MV;
    if (!isfinite(flickerSigmas)) flickerSigmas = FLAME_FLICKER_SIGMAS;
    if (minDeltaMv < DELTA_MIN) minDeltaMv = DELTA_MIN;
    if (minDeltaMv > DELTA_MAX) minDeltaMv = DELTA_MAX;
    if (flickerSigmas < FLICKER_MIN) flickerSigmas = FLICKER_MIN;
    if (flickerSigmas > FLICKER_MAX) flickerSigmas = FLICKER_MAX;
    if (minReversals < REV_MIN) minReversals = REV_MIN;
    if (minReversals > REV_MAX) minReversals = REV_MAX;
    if (confirmMs < CONFIRM_MIN) confirmMs = CONFIRM_MIN;
    if (confirmMs > CONFIRM_MAX) confirmMs = CONFIRM_MAX;
    if (rebaseMs < REBASE_MIN) rebaseMs = REBASE_MIN;
    if (rebaseMs > REBASE_MAX) rebaseMs = REBASE_MAX;
  }
};

class FlameDetector {
 public:
  FlameTuning tune;
  void relearn() {
    baseReady_ = false; dead_ = false; polarityBad_ = false; active_ = false; sticky_ = false;
    initStart_ = 0; initQuietSince_ = 0; initSum_ = 0; initSd_ = 0; initN_ = 0;
    confirmMs_ = 0; belowMs_ = 0; uncorrMs_ = 0; flickerHits_ = 0; histN_ = 0;
    doMs_ = 0; riseActive_ = false; polEvents_ = 0; polEpisode_ = false; polBadActive_ = false;
  }
  bool learned() const { return baseReady_; }
  bool confirmed() const { return sticky_; }

  static constexpr int N = 64;

  void feed(float mv) {
    buf_[idx_] = mv;
    idx_ = (idx_ + 1) % N;
    if (count_ < N) count_++;
  }

  void feedDo(bool raw) {
    dbuf_[didx_] = raw ? 1 : 0;
    didx_ = (didx_ + 1) % N;
    if (dcount_ < N) dcount_++;
  }

  FlameOut evaluate(uint32_t now, bool doOn) {
    FlameOut o = {};
    o.noise = noise_; o.base = base_;
    const uint32_t dt = SENSOR_CYCLE_MS;
    if (count_ < N / 2) { o.aoUsable = false; o.why = FW_LEARNING; return o; }

    float mean = 0, mn = buf_[0], mx = buf_[0];
    for (int i = 0; i < count_; i++) { float v = buf_[i]; mean += v; if (v < mn) mn = v; if (v > mx) mx = v; }
    mean /= count_;
    float var = 0;
    for (int i = 0; i < count_; i++) { float d = buf_[i] - mean; var += d * d; }
    const float sd = sqrtf(var / count_);
    o.mean = mean; o.p2p = mx - mn;

    if (!baseReady_) {
      if (initStart_ == 0) initStart_ = now ? now : 1;
      if (doOn) { initQuietSince_ = 0; initSum_ = initSd_ = 0; initN_ = 0; }
      else if (sd > FLAME_INIT_MAX_SIGMA_MV) { initQuietSince_ = 0; }
      else { if (initQuietSince_ == 0) initQuietSince_ = now ? now : 1; initSum_ += mean; initSd_ += sd; initN_++; }
      if (initQuietSince_ != 0 && (uint32_t)(now - initQuietSince_) >= FLAME_INIT_MS && initN_ > 0) {
        base_ = initSum_ / initN_;
        noise_ = fmaxf(FLAME_NOISE_FLOOR_MV, initSd_ / initN_);
        baseReady_ = true; o.events |= FE_BASELINE_READY;
        if (base_ > 3050.0f) o.events |= FE_NEAR_ADC_CEILING;
      } else {
        if (initQuietSince_ == 0) { initSum_ = initSd_ = 0; initN_ = 0; }
        if ((uint32_t)(now - initStart_) >= FLAME_INIT_GIVEUP_MS && sd > FLAME_INIT_MAX_SIGMA_MV) {
          if (!dead_) o.events |= FE_AO_UNUSABLE_NOISY;
          if (!dead_) deadSince_ = now;
          dead_ = true;
        }
      }
      o.aoUsable = false; o.why = dead_ ? FW_NOISY : FW_LEARNING; o.mean = mean; o.base = base_; o.noise = noise_;
      return o;
    }
    if (dead_ && (uint32_t)(now - deadSince_) >= 20000) { relearn(); o.aoUsable = false; o.why = FW_LEARNING; return o; }
    if (polBadActive_ && (int32_t)(now - polBadUntil_) >= 0) { polBadActive_ = false; polEvents_ = 0; }
    if (dead_ || polBadActive_) { o.aoUsable = false; o.why = dead_ ? FW_NOISY : FW_POLARITY; o.mean = mean; o.base = base_; return o; }

    const float sgn = tune.invert ? -1.0f : 1.0f;
    const float dev = sgn * (base_ - mean);
    const float enterBase = fmaxf(tune.minDeltaMv, FLAME_ENTER_SIGMAS * noise_);
    const float enter = doOn ? fmaxf(4.0f * noise_, FLAME_DO_ENTER_RATIO * tune.minDeltaMv) : enterBase;
    const float exitT = enter * FLAME_EXIT_RATIO;
    const float holdT = sticky_ ? 0.3f * enterBase : exitT;
    if (!active_ && dev >= enter) active_ = true;
    else if (active_ && dev < holdT && !(sticky_ && doOn)) active_ = false;
    if (!sticky_ && ((!active_ && fabsf(dev) < enterBase) || uncorrMs_ >= 3000)) noise_ += 0.1f * (fmaxf(FLAME_NOISE_FLOOR_MV, sd) - noise_);

    if (doOn) { doMs_ += dt; } else { doMs_ = 0; }
    if (!doOn) polEpisode_ = false;
    if (doOn && doMs_ >= 1500 && !polEpisode_ && dev <= -fmaxf(4.0f * noise_, 0.5f * tune.minDeltaMv)) {
      polEpisode_ = true;
      if (++polEvents_ >= 2) {
        polBadActive_ = true; polBadUntil_ = now + 120000; o.events |= FE_POLARITY_BAD;
        active_ = false; sticky_ = false; confirmMs_ = 0; histN_ = 0;
        o.aoUsable = false; o.why = FW_POLARITY; return o;
      }
    }

    constexpr int H = FLAME_RISE_WINDOW_MS / SENSOR_CYCLE_MS;
    float oldest = dev;
    for (int i = 0; i < H; i++) if (devHist_[i] < oldest) oldest = devHist_[i];
    const bool rapid = histN_ >= H && dev >= enter && (dev - oldest) >= FLAME_RISE_FRAC * enter;
    for (int i = H - 1; i > 0; i--) devHist_[i] = devHist_[i - 1];
    devHist_[0] = dev; if (histN_ < H) histN_++;
    if (rapid) { riseActive_ = true; riseUntil_ = now + FLAME_CORROB_HOLD_MS; }
    if (riseActive_ && (int32_t)(now - riseUntil_) >= 0) riseActive_ = false;
    const bool riseRecent = riseActive_;

    const float h = fmaxf(tune.flickerSigmas * noise_, FLAME_FLICKER_FRAC_PER_SIGMA * tune.flickerSigmas * (dev > 0 ? dev : 0));
    int rev = 0; int state = 0;
    for (int k = 0; k < count_; k++) {
      const float v = buf_[(idx_ + (N - count_) + k) % N] - mean;
      if (v > h * 0.5f) { if (state == -1) rev++; state = 1; }
      else if (v < -h * 0.5f) { if (state == 1) rev++; state = -1; }
    }
    int doTrans = 0;
    for (int k = 1; k < dcount_; k++) {
      const int a = dbuf_[(didx_ + (N - dcount_) + k - 1) % N], b = dbuf_[(didx_ + (N - dcount_) + k) % N];
      if (a != b) doTrans++;
    }
    const bool doChatter = doTrans >= FLAME_DO_MIN_TRANSITIONS && doTrans <= FLAME_DO_MAX_TRANSITIONS;
    const bool flickerNow = active_ && rev >= tune.minReversals && rev <= FLAME_MAX_REVERSALS;
    if (flickerNow) { if (flickerHits_ < 100) flickerHits_++; } else flickerHits_ = 0;
    const bool flickerSustained = flickerHits_ >= FLAME_FLICKER_CYCLES;
    o.reversals = rev;

    const bool corroborated = sticky_ || (flickerSustained && (!tune.requireDo || doOn || doChatter));
    const bool weakEvidence = flickerSustained;

    const float fullDelta = fmaxf(1.0f, fabsf(base_) * FLAME_FULL_FRACTION);
    int pctRaw = (int)lroundf(dev / fullDelta * 100.0f);
    if (pctRaw < 0) pctRaw = 0;
    if (pctRaw > 100) pctRaw = 100;

    const bool strong = active_;
    if (strong && corroborated) confirmMs_ += dt; else if (!sticky_) confirmMs_ = 0;
    bool critical = sticky_ || confirmMs_ >= tune.confirmMs;
    if (critical && !sticky_) { sticky_ = true; o.events |= FE_CRITICAL_ON; }
    if (sticky_) {
      if (active_) belowMs_ = 0; else { belowMs_ += dt; if (belowMs_ >= FLAME_RELEASE_MS) { sticky_ = false; confirmMs_ = 0; critical = false; belowMs_ = 0; } }
    }

    if (!active_ && !riseRecent && !sticky_ && !flickerSustained && sd < 3.0f * noise_ + 1.0f) base_ += FLAME_BASELINE_ALPHA * (mean - base_);
    if (active_ && !corroborated && !flickerSustained) {
      uncorrMs_ += dt;
      if (uncorrMs_ >= tune.rebaseMs) { base_ = mean; uncorrMs_ = 0; o.events |= FE_REBASED; active_ = false; }
    } else uncorrMs_ = 0;

    int pct;
    if (!active_) pct = 0;
    else if (!corroborated && weakEvidence) pct = pctRaw < SIGNAL_MIN_PCT ? SIGNAL_MIN_PCT : (pctRaw < FLAME_CRIT_PCT ? pctRaw : FLAME_CRIT_PCT - 1);
    else if (!corroborated) pct = pctRaw < SIGNAL_MIN_PCT ? pctRaw : SIGNAL_MIN_PCT - 1;
    else if (!critical) pct = pctRaw < SIGNAL_MIN_PCT ? SIGNAL_MIN_PCT : (pctRaw < FLAME_CRIT_PCT ? pctRaw : FLAME_CRIT_PCT - 1);
    else pct = pctRaw < FLAME_CRIT_PCT ? FLAME_CRIT_PCT : pctRaw;

    o.pct = pct; o.critical = critical; o.aoUsable = true;
    o.base = base_; o.dev = dev; o.noise = noise_; o.enter = enter;
    o.corroborated = corroborated; o.flicker = flickerSustained; o.rise = riseRecent; o.why = FW_OK;
    return o;
  }

  bool baselineReady() const { return baseReady_; }
  bool doChatterNow() const {
    int tr = 0;
    for (int k = 1; k < dcount_; k++) {
      const int a = dbuf_[(didx_ + (N - dcount_) + k - 1) % N], b = dbuf_[(didx_ + (N - dcount_) + k) % N];
      if (a != b) tr++;
    }
    return tr >= FLAME_DO_MIN_TRANSITIONS && tr <= FLAME_DO_MAX_TRANSITIONS;
  }

 private:
  float buf_[N] = {};
  int idx_ = 0, count_ = 0;
  int flickerHits_ = 0;
  uint8_t dbuf_[N] = {}; int didx_ = 0, dcount_ = 0;
  bool baseReady_ = false, dead_ = false, polarityBad_ = false, active_ = false, sticky_ = false;
  bool riseActive_ = false, polEpisode_ = false, polBadActive_ = false; int polEvents_ = 0; uint32_t polBadUntil_ = 0;
  float base_ = 0, noise_ = FLAME_NOISE_FLOOR_MV;
  uint32_t deadSince_ = 0, initStart_ = 0, initQuietSince_ = 0; float initSum_ = 0, initSd_ = 0; int initN_ = 0;
  float devHist_[FLAME_RISE_WINDOW_MS / SENSOR_CYCLE_MS] = {}; int histN_ = 0;
  uint32_t riseUntil_ = 0, doMs_ = 0, confirmMs_ = 0, belowMs_ = 0, uncorrMs_ = 0;
};

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
  float mq2Mv;       float mq2BaseMv;
  int smokePct;      int flamePct;
  bool tempSeen, currentSeen, voltageSeen;
};

class SensorHub {
 public:
  SensorHub()
      : ds_(&oneWire_), tempFilter_(INVALID_AFTER_BAD_CYCLES / 2, VALID_AFTER_GOOD_CYCLES),
        currentFilter_(INVALID_AFTER_BAD_CYCLES, VALID_AFTER_GOOD_CYCLES),
        voltageFilter_(INVALID_AFTER_BAD_CYCLES, VALID_AFTER_GOOD_CYCLES) {}

  void begin() {
    analogReadResolution(12);
    analogSetAttenuation(ADC_11db);

    pinMode(PIN_FLAME_DO, FLAME_ACTIVE_LOW ? INPUT_PULLUP : INPUT_PULLDOWN);

    ds_.begin();
    ds_.setResolution(10);
    ds_.setWaitForConversion(true);
    ds_.requestTemperatures();
    float t = ds_.getTempCByIndex(0);
    bool ok = tempPlausible(t,  true);
    tempFilter_.prime(ok);
    if (ok) { tempAvg_.push(t); }
    ds_.setWaitForConversion(false);

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

  void fastFlameSample() {
    if (!HAS_FLAME_ANALOG) return;
    const uint32_t us = micros();
    float sum = 0;
    for (int i = 0; i < FLAME_OVERSAMPLE; i++) sum += (float)analogReadMilliVolts(PIN_FLAME_AO);
    flameDet_.feed(sum / FLAME_OVERSAMPLE);
    flameDet_.feedDo(digitalRead(PIN_FLAME_DO) == (FLAME_ACTIVE_LOW ? LOW : HIGH));
    if (lastFeedUs_ != 0) {
      const float dt = (float)(uint32_t)(us - lastFeedUs_);
      if (dt > 0 && dt < 200000.0f) feedPeriodUs_ += 0.05f * (dt - feedPeriodUs_);
    }
    lastFeedUs_ = us ? us : 1;
  }
  int flameRateHz() const { return feedPeriodUs_ > 1.0f ? (int)(1000000.0f / feedPeriodUs_ + 0.5f) : 0; }
  bool flameDo() const { return flameDiagDo_; }
  bool flameLearned() const { return flameDet_.learned(); }
  bool flameConfirmed() const { return flameDet_.confirmed(); }

  FlameTuning& flameTuning() { return flameDet_.tune; }
  void flameRelearn() { flameDet_.relearn(); }
  FlameOut flameDiag() const { return flameDiag_; }
  bool flameDiagDo() const { return flameDiagDo_; }

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
      if (!tempFilter_.valid()) tempAvg_.reset();
    }
  }

  const SensorReadings& sample(uint32_t now) {

    r_.tempValid = HAS_TEMP_SENSOR && tempFilter_.valid() && tempAvg_.hasData();
    r_.tempC = tempAvg_.value();
    if (r_.tempValid) seenT_ = true;
    r_.tempSeen = seenT_ || !AUTO_IGNORE_UNPLUGGED;

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
    if (r_.currentValid) seenC_ = true;
    r_.currentSeen = seenC_ || !AUTO_IGNORE_UNPLUGGED;

    float vMv = readAvgMv(PIN_VOLTAGE, ADC_SAMPLES_PER_CYCLE);
    bool vOk = vMv <= VOLT_ADC_MAX_MV;
    if (vOk) voltAvg_.push(vMv / 1000.0f * VOLT_MODULE_RATIO * VOLT_CAL_FACTOR);
    voltageFilter_.update(vOk);
    if (!voltageFilter_.valid()) voltAvg_.reset();
    r_.voltageV = voltAvg_.value();
    r_.voltageValid = HAS_VOLTAGE_SENSOR && voltageFilter_.valid() && voltAvg_.hasData();
    if (r_.voltageValid) seenV_ = true;
    r_.voltageSeen = seenV_ || !AUTO_IGNORE_UNPLUGGED;

    float mq = readAvgMv(PIN_MQ2_AO, ADC_SAMPLES_PER_CYCLE);
    mq2Avg_.push(mq);
    const float mqAvg = mq2Avg_.value();
    if (!mq2Ready_) {
      mq2Baseline_ = mqAvg;
      if ((uint32_t)(now - mq2Start_) >= MQ2_WARMUP_MS) mq2Ready_ = true;
      r_.smoke = false;
    } else {
      float delta = mqAvg - mq2Baseline_;
      if (!smokeLatched_ && delta >= MQ2_DELTA_ENTER_MV) smokeLatched_ = true;
      else if (smokeLatched_ && delta <= MQ2_DELTA_EXIT_MV) smokeLatched_ = false;

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
        if (smokeLatched_ && sp < 1) sp = 1;
      }
      r_.smokePct = sp;
    }
    r_.mq2Ready = mq2Ready_;

    bool raw = digitalRead(PIN_FLAME_DO) == (FLAME_ACTIVE_LOW ? LOW : HIGH);
    if (raw) { if (flameCount_ < 100) flameCount_++; } else flameCount_ = 0;
    const bool flameDo = flameCount_ >= FLAME_DEBOUNCE_CYCLES;
    int fp = flameDo ? 100 : 0;
    bool flameCrit = flameDo;
    if (HAS_FLAME_ANALOG) {
      const FlameOut fo = flameDet_.evaluate(now, flameDo);
      if (fo.aoUsable) { fp = fo.pct; flameCrit = fo.critical; }
      else {
        if (flameDo && flameDet_.doChatterNow()) { if (fbChatter_ < 100) fbChatter_++; } else fbChatter_ = 0;
        if (fbChatter_ >= 2) { fbActive_ = true; fbHoldUntil_ = now + FLAME_FALLBACK_HOLD_MS; }
        if (fbActive_ && (int32_t)(now - fbHoldUntil_) >= 0) fbActive_ = false;
        flameCrit = fbActive_;
        fp = flameCrit ? 100 : 0;
      }
      flameLog(fo, flameDo);
      flameDiag_ = fo; flameDiagDo_ = flameDo;
    }
    r_.flamePct = fp;
    r_.flame = flameCrit;

    float lv = readAvgMv(PIN_LEVEL, ADC_SAMPLES_PER_CYCLE);
    levelAvg_.push(lv);
    float span = (float)(LEVEL_FULL_MV - LEVEL_DRY_MV);
    float pct = (levelAvg_.value() - LEVEL_DRY_MV) / span * 100.0f;
    if (pct < 0) pct = 0;
    if (pct > 100) pct = 100;
    r_.oilPct = (int)lroundf(pct);
    r_.oilValid = HAS_LEVEL_SENSOR && lv <= VOLT_ADC_MAX_MV;

    return r_;
  }

 private:
  void flameLog(const FlameOut& fo, bool doOn) {
    if (!FLAME_AO_CALIBRATED && !flameWarned_) {
      flameWarned_ = true;
      Serial.println("FLAME: AO NOT CALIBRATED - thresholds in config.h are placeholders (docs/FLAME_AO_CALIBRATION.md)");
    }
    if (fo.events & FE_BASELINE_READY) Serial.printf("FLAME: ambient baseline learned: %.0f mV (noise %.1f mV)\n", fo.base, fo.noise);
    if (fo.events & FE_NEAR_ADC_CEILING) Serial.println("FLAME: baseline near the ADC ceiling (~3.1 V): power the module from 3.3 V and lower its gain if AO sits at the top");
    if (fo.events & FE_AO_UNUSABLE_NOISY) Serial.println("FLAME: AO is noisy/floating at boot -> AO NOT USED, falling back to DO-only (check the AO wire)");
    if (fo.events & FE_POLARITY_BAD) Serial.println("FLAME: DO fired but AO moved the other way -> AO NOT USED, DO-only. Try FLAME_AO_DROPS_WITH_FLAME = false");
    if (fo.events & FE_REBASED) Serial.println("FLAME: steady non-flickering change accepted as new ambient (sun/lamp)");
    if (fo.events & FE_CRITICAL_ON) Serial.println("FLAME: CONFIRMED (strong + corroborated)");
    if (++flameStatN_ % 40 == 0)
      Serial.printf("FLAME mode=%s ao=%.0f base=%.0f dev=%.0f DO=%d rev=%d pct=%d\n",
                    fo.aoUsable ? "AO+DO" : (fo.why == FW_NOISY ? "DO-only (AO noisy/not wired)" : fo.why == FW_POLARITY ? "DO-only (AO polarity)" : "learning baseline"),
                    fo.mean, fo.base, fo.dev, doOn, fo.reversals, fo.pct);
    if (FLAME_CALIBRATION_LOG && ++flameLogN_ % 4 == 0)
      Serial.printf("FLAMEcal ao=%.0f base=%.0f dev=%.0f enter=%.0f noise=%.1f p2p=%.0f rev=%d DO=%d rise=%d flick=%d corr=%d pct=%d crit=%d usable=%d\n",
                    fo.mean, fo.base, fo.dev, fo.enter, fo.noise, fo.p2p, fo.reversals, doOn, fo.rise, fo.flicker, fo.corroborated, fo.pct, fo.critical, fo.aoUsable);
  }
  static float readAvgMv(int pin, int samples) {
    uint32_t sum = 0;
    for (int i = 0; i < samples; i++) sum += analogReadMilliVolts(pin);
    return (float)sum / (float)samples;
  }
  static bool tempPlausible(float t, bool firstReading) {
    if (t == DEVICE_DISCONNECTED_C) return false;
    if (isnan(t) || t < DS_MIN_C || t > DS_MAX_C) return false;
    if (firstReading && t == 85.0f) return false;
    return true;
  }

  OneWire oneWire_{PIN_DS18B20};
  DallasTemperature ds_;
  ValidityFilter tempFilter_, currentFilter_, voltageFilter_;
  MovingAverage<4> tempAvg_;
  MovingAverage<AVG_WINDOW_CYCLES> currentAvg_, voltAvg_, mq2Avg_, levelAvg_;
  FlameDetector flameDet_;
  int fbChatter_ = 0; uint32_t fbHoldUntil_ = 0; bool fbActive_ = false;
  uint32_t lastFeedUs_ = 0; float feedPeriodUs_ = 10000.0f;
  FlameOut flameDiag_ = {}; bool flameDiagDo_ = false;
  bool flameWarned_ = false; uint32_t flameLogN_ = 0, flameStatN_ = 0;
  bool tempPending_ = false;
  uint32_t tempRequestedAt_ = 0, tempLastRequest_ = 0;
  float zeroAdcMv_ = 0;
  bool zeroOk_ = false;
  uint32_t mq2Start_ = 0;
  bool mq2Ready_ = false, smokeLatched_ = false;
  float mq2Baseline_ = 0;
  int flameCount_ = 0;
  bool seenT_ = false, seenC_ = false, seenV_ = false;
  SensorReadings r_{};
};

#include <Arduino.h>

class Actuators {
 public:

  void begin() {
    if (RELAY_USES_RELEASE) {
      pinMode(PIN_RELAY, INPUT);
    } else {

      digitalWrite(PIN_RELAY, RELAY_ACTIVE_LOW ? HIGH : LOW);
      pinMode(PIN_RELAY, OUTPUT);
    }
    relayAllowed_ = false;
    pinMode(PIN_LED_GREEN, OUTPUT);
    pinMode(PIN_LED_YELLOW, OUTPUT);
    pinMode(PIN_LED_RED, OUTPUT);
    pinMode(PIN_BUZZER, OUTPUT);
    setBuzzer(false);
    allLeds(false);
  }

  void setRelayAllowed(bool allow) {
    if (RELAY_USES_RELEASE) {
      if (allow && !relayAllowed_) { pinMode(PIN_RELAY, OUTPUT); digitalWrite(PIN_RELAY, LOW); }
      else if (!allow) pinMode(PIN_RELAY, INPUT);
    } else {
      const int onLevel  = RELAY_ACTIVE_LOW ? LOW : HIGH;
      const int offLevel = RELAY_ACTIVE_LOW ? HIGH : LOW;
      digitalWrite(PIN_RELAY, allow ? onLevel : offLevel);
    }
    relayAllowed_ = allow;
  }
  bool relayAllowed() const { return relayAllowed_; }

  bool relayMismatch() const {
    if (RELAY_USES_RELEASE) return relayAllowed_ && digitalRead(PIN_RELAY) != LOW;
    const int onLevel  = RELAY_ACTIVE_LOW ? LOW : HIGH;
    const int offLevel = RELAY_ACTIVE_LOW ? HIGH : LOW;
    return digitalRead(PIN_RELAY) != (relayAllowed_ ? onLevel : offLevel);
  }

  void applyStatus(Status s, bool alarm, uint32_t now) {
    bool blink = ((now / 250) % 2) == 0;
    digitalWrite(PIN_LED_GREEN,  s == Status::SAFE ? HIGH : LOW);
    digitalWrite(PIN_LED_YELLOW, s == Status::WARNING ? HIGH : LOW);
    digitalWrite(PIN_LED_RED,    s == Status::EMERGENCY ? (blink ? HIGH : LOW) : LOW);
    setBuzzer(alarm && blink);
  }

 private:
  static constexpr bool RELAY_USES_RELEASE = RELAY_ACTIVE_LOW && RELAY_OPEN_DRAIN_OFF;
  void setBuzzer(bool on) { digitalWrite(PIN_BUZZER, (on == BUZZER_ACTIVE_HIGH) ? HIGH : LOW); }
  void allLeds(bool on) {
    digitalWrite(PIN_LED_GREEN, on); digitalWrite(PIN_LED_YELLOW, on); digitalWrite(PIN_LED_RED, on);
  }
  bool relayAllowed_ = false;
};

#include <stdint.h>
#include <stddef.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>

struct GpsFix {
  bool valid = false;
  double lat = 0, lng = 0;
  int sats = 0;
};

class NmeaParser {
 public:

  bool feed(char c) {
    if (c == '$') { len_ = 0; in_ = true; return false; }
    if (!in_) return false;
    if (c == '\r' || c == '\n') {
      in_ = false;
      if (len_ == 0) return false;
      buf_[len_] = 0;
      return handle();
    }
    if (len_ >= sizeof(buf_) - 1) { in_ = false; return false; }
    buf_[len_++] = c;
    return false;
  }
  const GpsFix& fix() const { return fix_; }

  static uint8_t checksum(const char* body, size_t n) {
    uint8_t x = 0;
    for (size_t i = 0; i < n; i++) x ^= (uint8_t)body[i];
    return x;
  }

 private:
  char buf_[110];
  size_t len_ = 0;
  bool in_ = false;
  GpsFix fix_;
  bool rmcOk_ = false, ggaOk_ = false;

  static int hexv(char c) {
    if (c >= '0' && c <= '9') return c - '0';
    if (c >= 'A' && c <= 'F') return c - 'A' + 10;
    if (c >= 'a' && c <= 'f') return c - 'a' + 10;
    return -1;
  }

  static bool coord(const char* v, char hemi, bool isLat, double& out) {
    if (!v || !*v) return false;

    double raw = 0.0;
    int nd = 0;
    const char* q = v;
    while (*q >= '0' && *q <= '9') { raw = raw * 10.0 + (*q - '0'); ++q; ++nd; }
    if (*q == '.') {
      ++q;
      double scale = 0.1;
      while (*q >= '0' && *q <= '9') { raw += (*q - '0') * scale; scale *= 0.1; ++q; ++nd; }
    }
    if (nd == 0) return false;
    const double deg = floor(raw / 100.0);
    const double min = raw - deg * 100.0;
    if (min >= 60.0) return false;
    double d = deg + min / 60.0;
    if (hemi == 'S' || hemi == 'W') d = -d;
    else if (hemi != 'N' && hemi != 'E') return false;
    if (isLat ? (fabs(d) > 90.0) : (fabs(d) > 180.0)) return false;
    out = d;
    return true;
  }

  bool handle() {
    char* star = strrchr(buf_, '*');
    if (!star || star == buf_ || strlen(star) < 3) return false;
    const int h1 = hexv(star[1]), h2 = hexv(star[2]);
    if (h1 < 0 || h2 < 0) return false;
    if (checksum(buf_, (size_t)(star - buf_)) != (uint8_t)(h1 * 16 + h2)) return false;
    *star = 0;

    char* f[16];
    int n = 0;
    char* p = buf_;
    while (n < 16) {
      f[n++] = p;
      char* comma = strchr(p, ',');
      if (!comma) break;
      *comma = 0;
      p = comma + 1;
    }
    const size_t tl = strlen(f[0]);
    if (tl < 5) return false;
    const char* type = f[0] + tl - 3;

    if (!strcmp(type, "RMC") && n >= 7) {
      double la, lo;
      const bool ok = f[2][0] == 'A' && coord(f[3], f[4][0], true, la) && coord(f[5], f[6][0], false, lo) &&
                      !(la == 0 && lo == 0);
      rmcOk_ = ok;
      if (ok) { fix_.lat = la; fix_.lng = lo; }
    } else if (!strcmp(type, "GGA") && n >= 8) {
      double la, lo;
      const int quality = atoi(f[6]);
      const bool ok = quality > 0 && coord(f[2], f[3][0], true, la) && coord(f[4], f[5][0], false, lo) &&
                      !(la == 0 && lo == 0);
      ggaOk_ = ok;
      if (ok) { fix_.lat = la; fix_.lng = lo; }
      fix_.sats = atoi(f[7]);
    } else {
      return false;
    }
    fix_.valid = rmcOk_ || ggaOk_;
    return true;
  }
};

#include <stdint.h>
#include <stddef.h>
#include <stdio.h>
#include <string.h>

namespace sms {

inline bool isValidPhone(const char* p) {
  if (!p) return false;
  size_t i = 0;
  if (p[0] == '+') i = 1;
  int digits = 0;
  for (; p[i]; i++) {
    if (p[i] < '0' || p[i] > '9') return false;
    digits++;
  }
  return digits >= 8 && digits <= 15;
}

inline bool normalizePhone(const char* in, char* out, size_t n) {
  char d[24]; size_t k = 0; bool plus = false;
  if (!in || n < 6) return false;
  for (const char* c = in; *c; c++) {
    if (*c == '+' && k == 0 && !plus) { plus = true; continue; }
    if (*c >= '0' && *c <= '9') { if (k >= 18) return false; d[k++] = *c; continue; }
    if (*c == ' ' || *c == '-' || *c == '(' || *c == ')') continue;
    return false;
  }
  d[k] = 0;
  if (!plus && k == 10 && d[0] >= '6' && d[0] <= '9') { char t[32]; snprintf(t, sizeof(t), "91%s", d); strcpy(d, t); k += 2; }
  else if (!plus && k == 12 && d[0] == '9' && d[1] == '1') {   }
  else if (!plus && k == 11 && d[0] == '0') { char t[32]; snprintf(t, sizeof(t), "91%s", d + 1); strcpy(d, t); k = strlen(d); }
  if (!plus && !(k == 12 && d[0] == '9' && d[1] == '1')) return false;
  if (k < 8 || k > 15) return false;
  if ((size_t)snprintf(out, n, "+%s", d) >= n) return false;
  return isValidPhone(out);
}

inline void maskPhone(const char* p, char* out, size_t n) {
  size_t len = strlen(p);
  if (n == 0) return;
  if (len < 6 || len + 1 > n) { out[0] = 0; return; }
  for (size_t i = 0; i < len; i++) out[i] = (i < 3 || i >= len - 2) ? p[i] : '*';
  out[len] = 0;
}

inline bool buildAlertText(char* out, size_t n, const char* id, const char* status, int risk,
                           bool tempValid, float tempC, bool gpsValid, double lat, double lng) {
  int w = snprintf(out, n, "STSMN %s %s risk %d/100", id, status, risk);
  if (w < 0 || (size_t)w >= n) return false;
  size_t used = (size_t)w;
  if (tempValid) {
    w = snprintf(out + used, n - used, " temp %.1fC", (double)tempC);
    if (w < 0 || (size_t)w >= n - used) return false;
    used += (size_t)w;
  }
  if (gpsValid) w = snprintf(out + used, n - used, " https://maps.google.com/?q=%.6f,%.6f", lat, lng);
  else w = snprintf(out + used, n - used, " GPS: no fix");
  if (w < 0 || (size_t)w >= n - used) return false;
  return (used + (size_t)w) <= 160;
}

class SmsGate {
 public:
  SmsGate(bool onWarning, uint32_t minIntervalMs, uint32_t repeatMs)
      : thr_(onWarning ? 1 : 2), minMs_(minIntervalMs), repeatMs_(repeatMs) {}
  bool update(int level, uint32_t now) {
    if (level < thr_) { wasActive_ = false; pending_ = false; return false; }
    if (!wasActive_) { wasActive_ = true; pending_ = true; }
    if (!pending_ && sentOnce_ && (uint32_t)(now - lastSent_) >= repeatMs_) pending_ = true;
    if (pending_ && (!sentOnce_ || (uint32_t)(now - lastSent_) >= minMs_)) {
      pending_ = false; sentOnce_ = true; lastSent_ = now;
      return true;
    }
    return false;
  }
  void retry() { if (wasActive_) pending_ = true; }
 private:
  int thr_;
  uint32_t minMs_, repeatMs_;
  bool wasActive_ = false, pending_ = false, sentOnce_ = false;
  uint32_t lastSent_ = 0;
};

inline int parseCsq(const char* resp) {
  const char* p = resp ? strstr(resp, "+CSQ:") : nullptr;
  if (!p) return -1;
  p += 5;
  while (*p == ' ') p++;
  if (*p < '0' || *p > '9') return -1;
  int v = 0;
  while (*p >= '0' && *p <= '9') { v = v * 10 + (*p - '0'); p++; }
  return v;
}

inline bool parseCregRegistered(const char* resp) {
  const char* p = resp ? strstr(resp, "+CREG:") : nullptr;
  if (!p) return false;
  p += 6;
  while (*p == ' ') p++;
  const char* comma = strchr(p, ',');
  const char* q = comma ? comma + 1 : p;
  while (*q == ' ') q++;
  return *q == '1' || *q == '5';
}

}

class SimModule {
 public:

  bool begin(HardwareSerial& ser, int rxPin, int txPin, uint32_t baud) {
    s_ = &ser;
    ser.begin(baud, SERIAL_8N1, rxPin, txPin);
    delay(300);
    ok_ = false;
    for (int i = 0; i < 4 && !ok_; i++) ok_ = cmd("AT", "OK", 1000);
    if (!ok_) return false;
    cmd("ATE0", "OK", 1000);
    ok_ = cmd("AT+CMGF=1", "OK", 2000);
    if (ok_) cmd("AT+CSCS=\"GSM\"", "OK", 2000);
    return ok_;
  }
  bool ready() const { return ok_; }

  int signal() {
    if (!ok_ || !cmd("AT+CSQ", "OK", 2000)) return -1;
    return sms::parseCsq(rx_);
  }
  bool registered() {
    if (!ok_ || !cmd("AT+CREG?", "OK", 2000)) return false;
    return sms::parseCregRegistered(rx_);
  }

  bool sendSms(const char* number, const char* text) {
    if (!ok_ || !s_) return false;
    flushInput();
    s_->print("AT+CMGS=\"");
    s_->print(number);
    s_->print("\"\r");
    if (!waitFor(">", nullptr, 8000)) { s_->write((uint8_t)0x1B); return false; }
    s_->print(text);
    s_->write((uint8_t)0x1A);
    return waitFor("+CMGS:", "ERROR", 40000);
  }

 private:
  HardwareSerial* s_ = nullptr;
  bool ok_ = false;
  char rx_[200] = "";

  void flushInput() { while (s_ && s_->available()) s_->read(); }

  bool waitFor(const char* want, const char* bad, uint32_t timeoutMs) {
    size_t len = 0;
    rx_[0] = 0;
    const uint32_t t0 = millis();
    while ((uint32_t)(millis() - t0) < timeoutMs) {
      while (s_->available()) {
        const int c = s_->read();
        if (c < 0) break;
        if (len >= sizeof(rx_) - 1) { memmove(rx_, rx_ + 100, len - 100); len -= 100; }
        rx_[len++] = (char)c;
        rx_[len] = 0;
      }
      if (strstr(rx_, want)) return true;
      if (bad && strstr(rx_, bad)) return false;
      delay(5);
    }
    return false;
  }

  bool cmd(const char* c, const char* want, uint32_t timeoutMs) {
    flushInput();
    s_->print(c);
    s_->print("\r");
    return waitFor(want, "ERROR", timeoutMs);
  }
};

#include <Arduino.h>

constexpr size_t cstrLenAt(const char* s, size_t i) { return s[i] ? cstrLenAt(s, i + 1) : i; }
constexpr size_t cstrLen(const char* s) { return cstrLenAt(s, 0); }
constexpr bool cstrEqAt(const char* a, const char* b, size_t i) {
  return a[i] == b[i] && (a[i] == 0 || cstrEqAt(a, b, i + 1));
}
constexpr bool cstrEq(const char* a, const char* b) { return cstrEqAt(a, b, 0); }
static_assert(cstrLen(WIFI_AP_PASSWORD) >= 8 && cstrLen(WIFI_AP_PASSWORD) <= 63,
              "WIFI_AP_PASSWORD must be 8..63 characters (WPA2)");
static_assert(!cstrEq(WIFI_AP_PASSWORD, "CHANGE_ME_min8chars"),
              "Change WIFI_AP_PASSWORD in secrets.h - the example password is public");

static WebServer server(HTTP_PORT);
static SensorHub sensors;
static Actuators actuators;
static RiskEngine riskEngine;
static RelayGate relayGate;
static TaskHandle_t protectionHandle = nullptr;

static NmeaParser gps;
static SimModule sim;
static sms::SmsGate smsGate(SMS_ON_WARNING, SMS_MIN_INTERVAL_MS, SMS_REPEAT_MS);
static Preferences prefs;
static char alertPhone[24] = "";
static volatile bool smsEnabled = false;
struct CommState {
  bool gpsValid = false; double lat = 0, lng = 0; int sats = 0; uint32_t lastFixMs = 0;
  bool gsmReady = false, gsmRegistered = false; int gsmSignal = -1;
  uint32_t smsSent = 0, smsFailed = 0;
};
struct AlertRequest { bool pending = false; int status = 0; int risk = 0; bool tempValid = false; float tempC = 0; };
static CommState comm;
static AlertRequest alertReq;
static volatile bool smsRetryFlag = false;

static SemaphoreHandle_t stateMutex = nullptr;
static char statusJson[800] = "";
struct EventEntry { uint32_t t; char msg[24]; };
static EventEntry events[EVENT_LOG_SIZE];
static int eventHead = 0, eventCount = 0;
static FlameOut flameSnap{};
static bool flameSnapDo = false;
static bool snapLatched = false;
static int snapResetWaitS = 0;
static Status snapStatus = Status::SAFE;
static int snapTrips = 0;
static volatile int resetReq = 0;
static volatile int tripNv = -1;
static volatile bool tuneReady = false;
static FlameTuning pendingTune;
static volatile bool relearnReq = false;
static FlameTuning lastTune;
static FlameTuning savingTune;
static bool bootHold = false;
static uint32_t bootHoldUntil = 0;
static HoldTimer oilLowTimer;
static uint32_t authFails = 0, authLockUntil = 0;
static bool authLocked = false;
static Status prevStatus = Status::SAFE;
static uint32_t logTick = 0;
static SensorReadings lastReadings{};
static bool relayArmed = false;
static uint32_t seq = 0;

static uint32_t lastCycle = 0;
static RiskResult lastRisk{};
static bool alarmOn = false;

static void addEvent(const char* m) {
  xSemaphoreTake(stateMutex, portMAX_DELAY);
  EventEntry& e = events[eventHead];
  e.t = millis() / 1000; strlcpy(e.msg, m, sizeof(e.msg));
  eventHead = (eventHead + 1) % EVENT_LOG_SIZE;
  if (eventCount < EVENT_LOG_SIZE) eventCount++;
  xSemaphoreGive(stateMutex);
}

static uint64_t uptimeMs() { return (uint64_t)(esp_timer_get_time() / 1000); }

static void fmtFloat(char* out, size_t n, bool valid, float v, int decimals) {
  if (!valid || isnan(v) || isinf(v)) snprintf(out, n, "null");
  else snprintf(out, n, "%.*f", decimals, v);
}

static void buildStatusJson(char* out, size_t n, const SensorReadings& s, const RiskResult& r, const RiskPct& pc,
                            bool relay, bool alarm, uint32_t seqNo, bool oilLowNow) {
  char t[16], v[16], c[16], oil[16], pT[8], pI[8], pV[8];
  auto pctS = [](char* o, size_t sz, int p) { if (p < 0) snprintf(o, sz, "null"); else snprintf(o, sz, "%d", p); };
  pctS(pT, sizeof(pT), pc.temp); pctS(pI, sizeof(pI), pc.current); pctS(pV, sizeof(pV), pc.voltage);
  fmtFloat(t, sizeof(t), s.tempValid, s.tempC, 1);
  fmtFloat(v, sizeof(v), s.voltageValid, s.voltageV, 2);
  fmtFloat(c, sizeof(c), s.currentValid, s.currentA, 2);
  if (s.oilValid) snprintf(oil, sizeof(oil), "%d", s.oilPct); else snprintf(oil, sizeof(oil), "null");
  const int written = snprintf(out, n,
    "{\"deviceId\":\"%s\",\"fw\":\"%s\",\"uptime\":%llu,\"temperature\":%s,\"voltage\":%s,"
    "\"current\":%s,\"smoke\":%s,\"flame\":%s,\"oilLevel\":%s,\"risk\":%d,"
    "\"status\":\"%s\",\"relay\":%s,\"alarm\":%s,\"seq\":%lu,"
    "\"pct\":{\"temp\":%s,\"current\":%s,\"voltage\":%s,\"smoke\":%d,\"flame\":%d},"
    "\"latched\":%s,\"trips\":%d,\"resetWaitS\":%d,\"oilLow\":%s,\"uncal\":%s,\"relayVerified\":\"UNKNOWN\"}",
    STSMN_DEVICE_ID, STSMN_FW_VERSION, (unsigned long long)uptimeMs(), t, v, c,
    s.smoke ? "true" : "false", s.flame ? "true" : "false", oil, r.risk,
    statusName(r.status), relay ? "true" : "false", alarm ? "true" : "false",
    (unsigned long)seqNo, pT, pI, pV, pc.smoke, pc.flame,
    r.latched ? "true" : "false", r.trips, (int)((r.resetWaitMs + 999) / 1000), oilLowNow ? "true" : "false",
    (!FLAME_DO_CALIBRATED || !LEVEL_CALIBRATED || !MQ2_CALIBRATED) ? "true" : "false");

  if (written < 0 || written >= (int)n) snprintf(out, n, "{\"error\":\"status buffer overflow\"}");
}

static void setAlertPhone(const char* normalized) {
  xSemaphoreTake(stateMutex, portMAX_DELAY);
  strlcpy(alertPhone, normalized, sizeof(alertPhone));
  smsEnabled = sms::isValidPhone(alertPhone);
  xSemaphoreGive(stateMutex);
}
static void flameTuningLoad();
static void loadAlertPhone() {
  char stored[24] = "", norm[24] = "";
  prefs.begin("stsmn", false);
  flameTuningLoad();
  if (prefs.isKey("phone")) prefs.getString("phone", stored, sizeof(stored));
  else strlcpy(stored, ALERT_PHONE_NUMBER, sizeof(stored));
  if (sms::normalizePhone(stored, norm, sizeof(norm))) setAlertPhone(norm);
}

static void sendJson(int code, const char* body) {
  server.sendHeader("Cache-Control", "no-store");
  server.sendHeader("X-Content-Type-Options", "nosniff");
  server.send(code, "application/json", body);
}

static void handleStatus() {
  char snap[sizeof(statusJson)];
  xSemaphoreTake(stateMutex, portMAX_DELAY);
  strlcpy(snap, statusJson, sizeof(snap));
  xSemaphoreGive(stateMutex);
  if (!snap[0]) { sendJson(503, "{\"error\":\"not ready\"}"); return; }
  sendJson(200, snap);
}

static const char* resetReasonName() {
  switch (esp_reset_reason()) {
    case ESP_RST_POWERON: return "POWERON";
    case ESP_RST_SW: return "SOFTWARE";
    case ESP_RST_PANIC: return "PANIC";
    case ESP_RST_INT_WDT: case ESP_RST_TASK_WDT: case ESP_RST_WDT: return "WATCHDOG";
    case ESP_RST_BROWNOUT: return "BROWNOUT";
    default: return "OTHER";
  }
}

static void buildHealthJson(char* buf, size_t bufSize) {
  SensorReadings rd; bool armed; uint32_t sq; CommState cs;
  xSemaphoreTake(stateMutex, portMAX_DELAY);
  rd = lastReadings; armed = relayArmed; sq = seq; cs = comm;
  xSemaphoreGive(stateMutex);
  const bool gpsFix = cs.gpsValid && (uint32_t)(millis() - cs.lastFixMs) < GPS_FIX_TIMEOUT_MS;
  char latS[16], lngS[16];
  if (gpsFix) { snprintf(latS, sizeof(latS), "%.6f", cs.lat); snprintf(lngS, sizeof(lngS), "%.6f", cs.lng); }
  else { snprintf(latS, sizeof(latS), "null"); snprintf(lngS, sizeof(lngS), "null"); }
  char phoneMasked[24] = "";
  xSemaphoreTake(stateMutex, portMAX_DELAY); sms::maskPhone(alertPhone, phoneMasked, sizeof(phoneMasked)); xSemaphoreGive(stateMutex);
  snprintf(buf, bufSize,
    "{\"deviceId\":\"%s\",\"fw\":\"%s\",\"uptime\":%llu,\"seq\":%lu,\"clients\":%d,"
    "\"mq2Ready\":%s,\"tempValid\":%s,\"currentValid\":%s,\"voltageValid\":%s,\"relayMismatch\":%s,"
    "\"armed\":%s,\"heapFree\":%lu,\"stackFreeBytes\":%lu,"
    "\"gpsFix\":%s,\"gpsSats\":%d,\"lat\":%s,\"lng\":%s,"
    "\"gsmReady\":%s,\"gsmRegistered\":%s,\"gsmSignal\":%d,\"smsEnabled\":%s,\"smsSent\":%lu,\"smsFailed\":%lu,"
    "\"mq2Mv\":%d,\"mq2BaseMv\":%d,\"alertPhone\":\"%s\",\"resetReason\":\"%s\",\"bootHold\":%s,\"flameRateHz\":%d,\"flameLearned\":%s}",
    STSMN_DEVICE_ID, STSMN_FW_VERSION, (unsigned long long)uptimeMs(), (unsigned long)sq,
    WiFi.softAPgetStationNum(),
    rd.mq2Ready ? "true" : "false", rd.tempValid ? "true" : "false",
    rd.currentValid ? "true" : "false", rd.voltageValid ? "true" : "false",
    actuators.relayMismatch() ? "true" : "false", armed ? "true" : "false",
    (unsigned long)ESP.getFreeHeap(),
    (unsigned long)(protectionHandle ? uxTaskGetStackHighWaterMark(protectionHandle) * sizeof(StackType_t) : 0),
    gpsFix ? "true" : "false", cs.sats, latS, lngS,
    cs.gsmReady ? "true" : "false", cs.gsmRegistered ? "true" : "false", cs.gsmSignal,
    smsEnabled ? "true" : "false", (unsigned long)cs.smsSent, (unsigned long)cs.smsFailed,
    (int)rd.mq2Mv, (int)rd.mq2BaseMv, phoneMasked, resetReasonName(), bootHold ? "true" : "false", sensors.flameRateHz(), sensors.flameLearned() ? "true" : "false");
}

static void handleHealth() {
  char buf[960];
  buildHealthJson(buf, sizeof(buf));
  sendJson(200, buf);
}

static int applyContact(const char* raw, char* masked, size_t maskedSize) {
  masked[0] = 0;
  if (strlen(raw) > 30) return -1;
  if (raw[0] == 0) { setAlertPhone(""); prefs.putString("phone", ""); return 0; }
  char norm[24] = "";
  if (!sms::normalizePhone(raw, norm, sizeof(norm))) return -1;
  setAlertPhone(norm);
  prefs.putString("phone", norm);
  sms::maskPhone(norm, masked, maskedSize);
  Serial.printf("Alert number saved: %s\n", masked);
  return 1;
}

static bool authOk() {
  const uint32_t now = millis();
  if (authLocked && (int32_t)(now - authLockUntil) < 0) { sendJson(429, "{\"error\":\"locked\"}"); return false; }
  authLocked = false;
  const String tk = server.arg("token");
  const char* want = STSMN_API_TOKEN;
  const size_t wl = strlen(want);
  uint8_t diff = (tk.length() != wl) ? 1 : 0;
  for (size_t i = 0; i < wl; i++) diff |= (uint8_t)((i < tk.length() ? tk.c_str()[i] : 0) ^ want[i]);
  if (diff) {
    if (++authFails >= (uint32_t)AUTH_FAIL_LIMIT) { authFails = 0; authLocked = true; authLockUntil = now + AUTH_LOCKOUT_MS; }
    sendJson(401, "{\"error\":\"unauthorized\"}");
    return false;
  }
  authFails = 0;
  return true;
}

static bool parseF(const String& v, float& out) {
  if (v.length() == 0 || v.length() > 16) return false;
  char* end = nullptr;
  const float f = strtof(v.c_str(), &end);
  if (!end || *end != 0 || !isfinite(f)) return false;
  out = f; return true;
}
static bool parseI(const String& v, long& out) {
  if (v.length() == 0 || v.length() > 10) return false;
  char* end = nullptr;
  const long l = strtol(v.c_str(), &end, 10);
  if (!end || *end != 0) return false;
  out = l; return true;
}

static void handleContactSet() {
  if (!authOk()) return;
  if (!server.hasArg("phone")) { sendJson(400, "{\"error\":\"phone missing\"}"); return; }
  const String raw = server.arg("phone");
  if (raw.length() > 30) { sendJson(400, "{\"error\":\"phone too long\"}"); return; }
  char masked[24];
  const int r = applyContact(raw.c_str(), masked, sizeof(masked));
  if (r < 0) { sendJson(400, "{\"error\":\"invalid phone number\"}"); return; }
  if (r == 0) { sendJson(200, "{\"ok\":true,\"smsEnabled\":false}"); return; }
  char buf[96];
  snprintf(buf, sizeof(buf), "{\"ok\":true,\"smsEnabled\":true,\"alertPhone\":\"%s\"}", masked);
  sendJson(200, buf);
}

static void flameTuningSave() {
  const FlameTuning& t = savingTune;
  prefs.putFloat("fl_d", t.minDeltaMv); prefs.putFloat("fl_s", t.flickerSigmas);
  prefs.putInt("fl_r", t.minReversals); prefs.putUInt("fl_c", t.confirmMs);
  prefs.putUInt("fl_b", t.rebaseMs); prefs.putBool("fl_o", t.requireDo); prefs.putBool("fl_i", t.invert);
}

static void flameTuningLoad() {
  FlameTuning& t = sensors.flameTuning();
  if (!prefs.isKey("fl_d")) return;
  t.minDeltaMv = prefs.getFloat("fl_d", t.minDeltaMv); t.flickerSigmas = prefs.getFloat("fl_s", t.flickerSigmas);
  t.minReversals = prefs.getInt("fl_r", t.minReversals); t.confirmMs = prefs.getUInt("fl_c", t.confirmMs);
  t.rebaseMs = prefs.getUInt("fl_b", t.rebaseMs); t.requireDo = prefs.getBool("fl_o", t.requireDo);
  t.invert = prefs.getBool("fl_i", t.invert);
  t.clampAll();
}

static void handleFlame() {
  FlameTuning cur;
  xSemaphoreTake(stateMutex, portMAX_DELAY);
  cur = tuneReady ? pendingTune : lastTune;
  const Status stNow = snapStatus;
  xSemaphoreGive(stateMutex);
  if (server.method() == HTTP_POST) {
    if (!authOk()) return;
    FlameTuning n = cur;
    float f; long l;
    if (server.hasArg("minDelta")) { if (!parseF(server.arg("minDelta"), f) || f < 10 || f > 300) { sendJson(400, "{\"error\":\"minDelta\"}"); return; } n.minDeltaMv = f; }
    if (server.hasArg("flicker")) { if (!parseF(server.arg("flicker"), f) || f < 2 || f > 10) { sendJson(400, "{\"error\":\"flicker\"}"); return; } n.flickerSigmas = f; }
    if (server.hasArg("reversals")) { if (!parseI(server.arg("reversals"), l) || l < 2 || l > 12) { sendJson(400, "{\"error\":\"reversals\"}"); return; } n.minReversals = (int)l; }
    if (server.hasArg("confirmMs")) { if (!parseI(server.arg("confirmMs"), l) || l < 250 || l > 2000) { sendJson(400, "{\"error\":\"confirmMs\"}"); return; } n.confirmMs = (uint32_t)l; }
    if (server.hasArg("rebaseMs")) { if (!parseI(server.arg("rebaseMs"), l) || l < 3000 || l > 60000) { sendJson(400, "{\"error\":\"rebaseMs\"}"); return; } n.rebaseMs = (uint32_t)l; }
    if (server.hasArg("requireDo")) { if (!parseI(server.arg("requireDo"), l) || (l != 0 && l != 1)) { sendJson(400, "{\"error\":\"requireDo\"}"); return; } n.requireDo = l != 0; }
    if (server.hasArg("invert")) { if (!parseI(server.arg("invert"), l) || (l != 0 && l != 1)) { sendJson(400, "{\"error\":\"invert\"}"); return; } n.invert = l != 0; }
    const bool wantRelearn = server.hasArg("relearn") && server.arg("relearn") == "1";
    if (wantRelearn && stNow != Status::SAFE) { sendJson(409, "{\"error\":\"relearn only when SAFE\"}"); return; }
    n.clampAll();
    const bool changed = n.minDeltaMv != cur.minDeltaMv || n.flickerSigmas != cur.flickerSigmas || n.minReversals != cur.minReversals ||
                         n.confirmMs != cur.confirmMs || n.rebaseMs != cur.rebaseMs || n.requireDo != cur.requireDo || n.invert != cur.invert;
    xSemaphoreTake(stateMutex, portMAX_DELAY);
    pendingTune = n; tuneReady = true;
    if (wantRelearn) relearnReq = true;
    xSemaphoreGive(stateMutex);
    if (changed) { savingTune = n; flameTuningSave(); addEvent("FLAME TUNE"); }
    cur = n;
  }
  FlameOut fo; bool dO;
  xSemaphoreTake(stateMutex, portMAX_DELAY);
  fo = flameSnap; dO = flameSnapDo;
  xSemaphoreGive(stateMutex);
  char buf[600];
  snprintf(buf, sizeof(buf),
           "{\"minDelta\":%.0f,\"flicker\":%.1f,\"reversals\":%d,\"confirmMs\":%u,\"rebaseMs\":%u,\"requireDo\":%d,\"invert\":%d,"
           "\"live\":{\"usable\":%d,\"ao\":%.0f,\"base\":%.0f,\"dev\":%.0f,\"noise\":%.1f,\"rev\":%d,\"do\":%d,\"pct\":%d,\"critical\":%d,\"why\":%d,\"flick\":%d,\"corr\":%d}}",
           cur.minDeltaMv, cur.flickerSigmas, cur.minReversals, (unsigned)cur.confirmMs, (unsigned)cur.rebaseMs, cur.requireDo ? 1 : 0, cur.invert ? 1 : 0,
           fo.aoUsable ? 1 : 0, fo.mean, fo.base, fo.dev, fo.noise, fo.reversals, dO ? 1 : 0, fo.pct, fo.critical ? 1 : 0, (int)fo.why, fo.flicker ? 1 : 0, fo.corroborated ? 1 : 0);
  sendJson(200, buf);
}

static void handleReset() {
  if (!authOk()) return;
  bool latched; int wait;
  xSemaphoreTake(stateMutex, portMAX_DELAY);
  latched = snapLatched; wait = snapResetWaitS;
  xSemaphoreGive(stateMutex);
  if (!latched) { sendJson(200, "{\"ok\":true,\"latched\":false}"); return; }
  if (wait > 0) {
    char b[64]; snprintf(b, sizeof(b), "{\"error\":\"wait\",\"resetWaitS\":%d}", wait);
    sendJson(409, b); return;
  }
  resetReq = 1;
  sendJson(200, "{\"ok\":true,\"accepted\":true}");
}

static void handleEvents() {
  char buf[640];
  size_t n = 0;
  n += snprintf(buf + n, sizeof(buf) - n, "{\"events\":[");
  xSemaphoreTake(stateMutex, portMAX_DELAY);
  for (int i = 0; i < eventCount; i++) {
    const int idx = (eventHead - eventCount + i + EVENT_LOG_SIZE * 2) % EVENT_LOG_SIZE;
    if (n + 48 >= sizeof(buf)) break;
    n += snprintf(buf + n, sizeof(buf) - n, "%s{\"t\":%lu,\"e\":\"%s\"}", i ? "," : "", (unsigned long)events[idx].t, events[idx].msg);
  }
  xSemaphoreGive(stateMutex);
  snprintf(buf + n, sizeof(buf) - n, "]}");
  sendJson(200, buf);
}

static void handleNotFound() {
  const String uri = server.uri();
  if (uri == "/api/status" || uri == "/api/health" || uri == "/api/contact" || uri == "/api/flame" || uri == "/api/reset" || uri == "/api/events") {
    server.sendHeader("Allow", (uri == "/api/contact" || uri == "/api/reset") ? "POST" : (uri == "/api/flame" ? "GET, POST" : "GET"));
    sendJson(405, "{\"error\":\"method not allowed\"}");
  } else {
    sendJson(404, "{\"error\":\"not found\"}");
  }
}

static void protectionCycle(uint32_t now) {
  if (tuneReady) {
    xSemaphoreTake(stateMutex, portMAX_DELAY);
    sensors.flameTuning() = pendingTune; tuneReady = false;
    const bool rl = relearnReq; relearnReq = false;
    xSemaphoreGive(stateMutex);
    if (rl) sensors.flameRelearn();
  }
  SensorReadings rd = sensors.sample(now);
  const bool oilLowNow = oilLowTimer.update(rd.oilValid && rd.oilPct < OIL_LOW_PERCENT, now, OIL_LOW_HOLD_MS);

  RiskInput in{rd.tempValid, rd.tempC, rd.currentValid, rd.currentA, rd.voltageValid, rd.voltageV,
               rd.smoke, rd.flame, rd.oilValid, rd.oilPct, rd.smokePct, rd.flamePct, oilLowNow};
  riskEngine.setFitted(rd.tempSeen, rd.currentSeen, rd.voltageSeen);
  if (resetReq) {
    resetReq = 0;
    if (riskEngine.reset(now)) { tripNv = 0; addEvent("RESET OK"); }
  }
  lastRisk = riskEngine.update(in, now);
  if (riskEngine.justLatched()) { tripNv = 1; addEvent("OVERCURRENT LATCH"); }
  if (lastRisk.status != prevStatus) {
    addEvent(lastRisk.status == Status::EMERGENCY ? "-> EMERGENCY" : (lastRisk.status == Status::WARNING ? "-> WARNING" : "-> SAFE"));
    prevStatus = lastRisk.status;
  }
  if (bootHold && (int32_t)(now - bootHoldUntil) >= 0) { bootHold = false; addEvent("BOOT HOLD END"); }

  const bool allow = relayGate.update(now, lastRisk.status, lastRisk.sensorFault, bootHold);
  actuators.setRelayAllowed(allow);
  alarmOn = (lastRisk.status == Status::EMERGENCY);

  if (smsRetryFlag) { smsRetryFlag = false; smsGate.retry(); }
  if (smsEnabled && smsGate.update((int)lastRisk.status, now)) {
    xSemaphoreTake(stateMutex, portMAX_DELAY);
    alertReq.pending = true; alertReq.status = (int)lastRisk.status; alertReq.risk = lastRisk.risk;
    alertReq.tempValid = rd.tempValid; alertReq.tempC = rd.tempC;
    xSemaphoreGive(stateMutex);
  }

  char json[sizeof(statusJson)];
  const uint32_t nextSeq = seq + 1;
  buildStatusJson(json, sizeof(json), rd, lastRisk, scorePercent(in), actuators.relayAllowed(), alarmOn, nextSeq, oilLowNow);

  xSemaphoreTake(stateMutex, portMAX_DELAY);
  seq = nextSeq;
  lastReadings = rd;
  relayArmed = relayGate.armed();
  strlcpy(statusJson, json, sizeof(statusJson));
  flameSnap = sensors.flameDiag(); flameSnapDo = sensors.flameDiagDo();
  lastTune = sensors.flameTuning();
  snapLatched = lastRisk.latched; snapResetWaitS = (int)((lastRisk.resetWaitMs + 999) / 1000);
  snapStatus = lastRisk.status; snapTrips = lastRisk.trips;
  xSemaphoreGive(stateMutex);

  if ((logTick++ & 3) == 0 || lastRisk.status != Status::SAFE)
  Serial.printf("seq=%lu %s risk=%d [T%d I%d V%d S%d F%d O%d] relay=%d armed=%d crit=%d fault=%d ok(T%d I%d V%d) smoke=%d%% flame=%d%% mq=%d/%d\n",
    (unsigned long)nextSeq, statusName(lastRisk.status), lastRisk.risk,
    lastRisk.parts.temp, lastRisk.parts.current, lastRisk.parts.voltage,
    lastRisk.parts.smoke, lastRisk.parts.flame, lastRisk.parts.oil,
    actuators.relayAllowed(), relayGate.armed(), lastRisk.critical, lastRisk.sensorFault,
    rd.tempValid, rd.currentValid, rd.voltageValid, rd.smokePct, rd.flamePct, (int)rd.mq2Mv, (int)rd.mq2BaseMv);
}

static void protectionTask(void*) {
  esp_task_wdt_add(nullptr);
  for (;;) {
    const uint32_t now = millis();
    sensors.pollTemperature(now);
    sensors.fastFlameSample();
    if ((uint32_t)(now - lastCycle) >= SENSOR_CYCLE_MS) {
      lastCycle = now;
      protectionCycle(now);
    }
    actuators.applyStatus(lastRisk.status, alarmOn, now);
    esp_task_wdt_reset();
    vTaskDelay(pdMS_TO_TICKS(PROTECTION_LOOP_MS));
  }
}

static void commTask(void*) {
  uint32_t lastGsmCheck = 0, lastSimTry = 0;
  bool simTried = false;
  for (;;) {
    const uint32_t now = millis();

    for (int guard = 0; guard < 256 && Serial2.available(); guard++) {
      if (gps.feed((char)Serial2.read())) {
        const GpsFix f = gps.fix();
        xSemaphoreTake(stateMutex, portMAX_DELAY);
        comm.gpsValid = f.valid; comm.sats = f.sats;
        if (f.valid) { comm.lat = f.lat; comm.lng = f.lng; comm.lastFixMs = now; }
        xSemaphoreGive(stateMutex);
      }
    }

    if (smsEnabled && !sim.ready() && (!simTried || (uint32_t)(now - lastSimTry) >= 30000)) {
      simTried = true; lastSimTry = now;
      const bool ok = sim.begin(Serial1, PIN_SIM_RX, PIN_SIM_TX, SIM_BAUD);
      xSemaphoreTake(stateMutex, portMAX_DELAY); comm.gsmReady = ok; xSemaphoreGive(stateMutex);
      Serial.println(ok ? "SIM module ready" : "SIM module not answering (check power, wiring, SIM card)");
    }
    if (sim.ready() && (uint32_t)(now - lastGsmCheck) >= GSM_STATUS_EVERY_MS) {
      lastGsmCheck = now;
      const int sig = sim.signal();
      const bool reg = sim.registered();
      xSemaphoreTake(stateMutex, portMAX_DELAY); comm.gsmSignal = sig; comm.gsmRegistered = reg; xSemaphoreGive(stateMutex);
    }

    AlertRequest req;
    xSemaphoreTake(stateMutex, portMAX_DELAY);
    req = alertReq; alertReq.pending = false;
    const bool fixNow = comm.gpsValid && (uint32_t)(now - comm.lastFixMs) < GPS_FIX_TIMEOUT_MS;
    const double la = comm.lat, lo = comm.lng;
    xSemaphoreGive(stateMutex);
    if (req.pending) {
      char text[161];
      const char* st = req.status == 2 ? "EMERGENCY" : (req.status == 1 ? "WARNING" : "SAFE");
      bool ok = false;
      char phone[24];
      xSemaphoreTake(stateMutex, portMAX_DELAY); strlcpy(phone, alertPhone, sizeof(phone)); xSemaphoreGive(stateMutex);
      if (sim.ready() && sms::isValidPhone(phone) && sms::buildAlertText(text, sizeof(text), STSMN_DEVICE_ID, st, req.risk, req.tempValid, req.tempC, fixNow, la, lo))
        ok = sim.sendSms(phone, text);
      xSemaphoreTake(stateMutex, portMAX_DELAY); if (ok) comm.smsSent++; else comm.smsFailed++; xSemaphoreGive(stateMutex);
      if (!ok) smsRetryFlag = true;
      Serial.println(ok ? "SMS alert sent" : "SMS alert FAILED (will retry)");
    }
    vTaskDelay(pdMS_TO_TICKS(20));
  }
}

static void startWatchdog() {
#if defined(ESP_ARDUINO_VERSION_MAJOR) && ESP_ARDUINO_VERSION_MAJOR >= 3
  const esp_task_wdt_config_t cfg = {
    .timeout_ms = WDT_TIMEOUT_S * 1000, .idle_core_mask = 0, .trigger_panic = true};
  if (esp_task_wdt_reconfigure(&cfg) != ESP_OK) esp_task_wdt_init(&cfg);
#else
  esp_task_wdt_init(WDT_TIMEOUT_S, true);
#endif
}

void setup() {
  actuators.begin();
  Serial.begin(115200);
  Serial.println("\nSTSMN firmware " STSMN_FW_VERSION);
  Serial.printf("Reset reason: %s (BROWNOUT = power dip: use a power bank / separate 5V supply)\n", resetReasonName());

  stateMutex = xSemaphoreCreateMutex();
  if (!stateMutex) { Serial.println("FATAL: mutex"); delay(500); ESP.restart(); }
  loadAlertPhone();
  {
    const esp_reset_reason_t rr = esp_reset_reason();
    if (rr == ESP_RST_PANIC || rr == ESP_RST_INT_WDT || rr == ESP_RST_TASK_WDT || rr == ESP_RST_WDT || rr == ESP_RST_BROWNOUT) {
      bootHold = true; bootHoldUntil = millis() + BOOT_HOLD_MS;
    }
    if (prefs.getBool("trip", false)) { riskEngine.restoreLatched(millis()); prevStatus = Status::EMERGENCY; }
  }
  sensors.begin();

  lastCycle = millis();
  protectionCycle(lastCycle);

  WiFi.mode(WIFI_AP);

  IPAddress apIp(WIFI_AP_IP[0], WIFI_AP_IP[1], WIFI_AP_IP[2], WIFI_AP_IP[3]);
  WiFi.softAPConfig(apIp, apIp, IPAddress(255, 255, 255, 0));
  const bool apOk = WiFi.softAP(WIFI_AP_SSID, WIFI_AP_PASSWORD, WIFI_AP_CHANNEL, 0, WIFI_AP_MAX_CLIENTS);
  Serial.print(apOk ? "AP IP: " : "AP FAILED (protection keeps running). IP: ");
  Serial.println(WiFi.softAPIP());

  server.on("/api/status", HTTP_GET, handleStatus);
  server.on("/api/health", HTTP_GET, handleHealth);
  server.on("/api/contact", HTTP_POST, handleContactSet);
  server.on("/api/flame", HTTP_ANY, handleFlame);
  server.on("/api/reset", HTTP_POST, handleReset);
  server.on("/api/events", HTTP_GET, handleEvents);
  server.onNotFound(handleNotFound);
  server.begin();

  Serial2.begin(GPS_BAUD, SERIAL_8N1, PIN_GPS_RX, PIN_GPS_TX);
  Serial.println(smsEnabled ? "SMS alerts: ON" : "SMS alerts: OFF (save a number in the app: Settings > Alert number)");
  Serial.printf("Sensors fitted: temp=%d current=%d voltage=%d level=%d (config.h HAS_*; unfitted = ignored, shown as null)\n",
    HAS_TEMP_SENSOR, HAS_CURRENT_SENSOR, HAS_VOLTAGE_SENSOR, HAS_LEVEL_SENSOR);
  Serial.printf("Relay: ACTIVE_LOW=%d. At boot (cutoff) the relay module's LED must be OFF; it clicks ON ~5 s later when all fitted sensors are valid. If it is the other way round, flip RELAY_ACTIVE_LOW in config.h.\n", (int)RELAY_ACTIVE_LOW);
  if (xTaskCreatePinnedToCore(commTask, "stsmn_comm", COMM_TASK_STACK, nullptr, 1, nullptr, 0) != pdPASS) { Serial.println("FATAL: comm task"); delay(500); ESP.restart(); }

  startWatchdog();
  if (xTaskCreatePinnedToCore(protectionTask, "stsmn_protect", PROTECTION_TASK_STACK, nullptr,
                              PROTECTION_TASK_PRIORITY, &protectionHandle, 1) != pdPASS) { Serial.println("FATAL: protection task"); delay(500); ESP.restart(); }
}

void loop() {
  if (tripNv >= 0) { const int v = tripNv; tripNv = -1; prefs.putBool("trip", v == 1); }
  server.handleClient();
  delay(2);
}
