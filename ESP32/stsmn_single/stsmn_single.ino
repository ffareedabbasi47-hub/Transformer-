// =============================================================================
// STSMN - Smart Transformer Safety & Monitoring Network  |  ESP32 firmware v1.1
//
// The ESP32 is the protection controller. Sensing, risk calculation, relay
// cutoff, LEDs and buzzer run in their OWN FreeRTOS task and never depend on
// the phone or on the HTTP server (a stalled client cannot delay protection).
// The phone only reads GET /api/status and GET /api/health.
//
// Modules: DS18B20 temperature, ACS712 current, voltage sensor, MQ-2 smoke, flame sensor, water level,
//          NEO-6M GPS (UART2), SIM800L SMS (UART1), relay, buzzer, 3 LEDs.
// Libraries (Arduino Library Manager): "OneWire", "DallasTemperature".
// Board: "ESP32 Dev Module" (ESP32 Arduino core 2.x or 3.x).
// LOW-VOLTAGE DEMO ONLY - never connect to 230V mains or a real transformer.
// =============================================================================
#include <WiFi.h>
#include <WebServer.h>
#include <esp_task_wdt.h>
#include <freertos/FreeRTOS.h>
#include <freertos/task.h>
#include <freertos/semphr.h>
#include <esp_timer.h>
#include <Preferences.h>

// ===== forward declarations (needed by the Arduino IDE's automatic prototypes) =====
enum class Status : uint8_t;
struct RiskInput;
struct RiskBreakdown;
struct RiskResult;
struct RiskPct;
struct SensorReadings;


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

// ---------- Which sensors are actually FITTED on your board ------------------------
// A sensor that is not connected cannot be read: its pin just floats and gives random numbers. Set it to
// false and the firmware treats it as "not fitted": it is reported as null (the app shows "-"), adds no
// risk points and is NEVER counted as a fault (a missing sensor must not hold the status at WARNING).
// Leave true for everything that IS wired. (Smoke, flame, GPS and SIM have their own checks.)
constexpr bool HAS_TEMP_SENSOR    = true;
constexpr bool HAS_CURRENT_SENSOR = true;
constexpr bool HAS_VOLTAGE_SENSOR = true;
constexpr bool HAS_LEVEL_SENSOR   = true;
// Flame strength (weak / strong) needs the module's AO pin wired to GPIO36. Without it only the digital
// output (DO) is used: flame = on/off (always "strong"). Set true ONLY if AO is really connected.
constexpr bool HAS_FLAME_ANALOG   = false;

// ---------- Pins (ASSUMPTION: PDF gives no pins) -----------------------------
// Analog inputs are all on ADC1 (GPIO32-39) because ADC2 cannot be used while
// Wi-Fi is on. Strapping pins (0,2,5,12,15) are avoided.
constexpr int PIN_DS18B20   = 4;   // 4.7k pull-up to 3.3V
constexpr int PIN_ACS712    = 34;  // THROUGH 10k/20k divider (sensor is 5V)
constexpr int PIN_VOLTAGE   = 35;  // module output; keep input <= 15V (see docs)
constexpr int PIN_MQ2_AO    = 32;  // THROUGH 10k/20k divider (module is 5V)
constexpr int PIN_LEVEL     = 33;  // water-level sensor powered from 3.3V
constexpr int PIN_FLAME_DO  = 25;  // digital output of flame module
constexpr int PIN_FLAME_AO  = 36;  // OPTIONAL analog output of the flame module (power the module from 3.3V)
constexpr int PIN_RELAY     = 26;
constexpr int PIN_LED_GREEN = 18;
constexpr int PIN_LED_YELLOW= 19;
constexpr int PIN_LED_RED   = 23;
constexpr int PIN_BUZZER    = 27;  // ASSUMPTION: ACTIVE buzzer (on/off)

// ---------- GPS module (NEO-6M) on UART2 ----------------------------------------
constexpr int PIN_GPS_RX = 16;   // ESP32 RX  <-  GPS TX
constexpr int PIN_GPS_TX = 17;   // ESP32 TX  ->  GPS RX (the NEO-6M only talks; this wire is optional)
constexpr uint32_t GPS_BAUD = 9600;
constexpr uint32_t GPS_FIX_TIMEOUT_MS = 10000;   // no valid sentence for 10 s = "no fix" (never an old position)

// ---------- SIM module (SIM800L) on UART1 ----------------------------------------
constexpr int PIN_SIM_RX = 13;   // ESP32 RX  <-  SIM800L TXD
constexpr int PIN_SIM_TX = 14;   // ESP32 TX  ->  SIM800L RXD, through a 1k + 2k voltage divider
constexpr uint32_t SIM_BAUD = 9600;
// SMS alert: sent when the status ENTERS EMERGENCY (and WARNING if SMS_ON_WARNING). The phone number is in secrets.h.
constexpr bool     SMS_ON_WARNING      = false;
constexpr uint32_t SMS_MIN_INTERVAL_MS = 120000;   // never more than one SMS per 2 minutes
constexpr uint32_t SMS_REPEAT_MS       = 1800000;  // reminder every 30 min while the alarm persists
constexpr uint32_t GSM_STATUS_EVERY_MS = 30000;    // signal/registration check
constexpr uint32_t COMM_TASK_STACK     = 6144;     // GPS + SMS task (low priority, never touches the relay)

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
constexpr int MQ2_DELTA_ENTER_MV = 120;  // above clean-air baseline -> smoke (at the ADC pin, after the 10k/20k divider)
constexpr int MQ2_DELTA_EXIT_MV  = 60;   // hysteresis
// The clean-air baseline follows slow drift (heater warm-up, temperature, humidity) so it never has to be
// perfect: ~2 minute time constant, and it never follows a rise bigger than half the enter threshold (= smoke).
constexpr float MQ2_BASELINE_ALPHA = 0.002f;
constexpr int MQ2_NOISE_MV      = 30;    // rise below this = noise (0 % smoke)
constexpr int MQ2_FULL_DELTA_MV = 450;   // rise at/above this = 100 % smoke (strong signal)

// ---------- Flame module analog output (only if HAS_FLAME_ANALOG) --------------------------
// Most modules: AO voltage DROPS as the flame gets stronger. CALIBRATE both values on your module.
constexpr int FLAME_AO_CLEAR_MV = 2900;  // reading with no flame (module powered from 3.3V)
constexpr int FLAME_AO_FULL_MV  = 600;   // reading with a strong flame close by
constexpr int FLAME_CRIT_PCT    = 40;    // flame signal >= this = real fire = EMERGENCY at once
constexpr int SIGNAL_MIN_PCT    = 15;    // smoke or flame signal >= this counts as "present"

// ---------- Water level (DEMO: water only, never transformer oil) ------------
constexpr int LEVEL_DRY_MV  = 0;     // CALIBRATE: reading with dry sensor
constexpr int LEVEL_FULL_MV = 1800;  // CALIBRATE: reading when fully wet
constexpr int OIL_LOW_PERCENT = 30;  // same value as OIL_LOW_PERCENT in the app
constexpr int OIL_OK_PERCENT  = 50;

// ---------- Risk scoring (PDF: 0-25,0-25,0-15,0-15,0-20,0-10) ----------------
// ASSUMPTION: all numeric limits are DEMO values and must be calibrated.
// FIRE and SMOKE are weighted equally: 50 points each. The fire side (50) is shared between the flame sensor
// and the heat it makes (temperature), so a real fire = flame + heat, smoke = the other 50.
// The points grow with the SIGNAL STRENGTH (weak signal = few points, strong signal = full points).
constexpr int PTS_SMOKE = 50;
constexpr int PTS_FLAME = 35, PTS_TEMP = 15;           // PTS_FLAME + PTS_TEMP = 50 (the "fire" half)
constexpr int PTS_CURRENT = 25, PTS_VOLTAGE = 15, PTS_OIL = 10;
static_assert(PTS_FLAME + PTS_TEMP == PTS_SMOKE, "fire half (flame + temperature) must equal the smoke half");

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
constexpr int SENSOR_WARN_PCT         = 50;  // temperature / current / voltage in the upper half of its danger range ...
constexpr int FLOOR_STRESS_RISK       = 45;  // ... = at least WARNING, even if the points do not add up
constexpr int FLOOR_CRITICAL_RISK     = 90;  // any critical condition = >= 90
constexpr int FLOOR_SMOKE_RISK        = 55;  // any smoke OR flame signal = at least WARNING (never silently "SAFE")

// ---------- Relay arming / fail-safe (see protection_logic.h) -----------------
// The supply is NEVER allowed until every protection sensor (temperature, current, voltage) has been valid
// for RELAY_ARM_STABLE_MS after the boot self-check. If any of them stays invalid for SENSOR_FAULT_CUTOFF_MS
// the supply is cut and needs a fresh stable-valid period to come back. 0 disables the cut-off on sensor
// fault (NOT recommended: protection would be blind while the load stays connected).
constexpr uint32_t RELAY_ARM_STABLE_MS     = 2000;
constexpr uint32_t SENSOR_FAULT_CUTOFF_MS  = 10000;

// ---------- Tasks / watchdog -------------------------------------------------
// Protection (sensors, risk, relay, LEDs) runs in its own FreeRTOS task so a slow or stalled HTTP client can
// never delay it. The task feeds the task watchdog; if it ever hangs the ESP32 resets and the relay returns to cutoff.
constexpr uint32_t WDT_TIMEOUT_S            = 8;
constexpr uint32_t PROTECTION_TASK_STACK    = 6144;
constexpr int      PROTECTION_TASK_PRIORITY = 3;   // above the Arduino loop task (1)
constexpr uint32_t PROTECTION_LOOP_MS       = 10;  // sleep between protection-task iterations

// Critical conditions (each must hold for the given time -> EMERGENCY)
constexpr uint32_t CRIT_TEMP_HOLD_MS    = 3000;
constexpr uint32_t CRIT_CURRENT_HOLD_MS = 1500;
constexpr uint32_t CRIT_SMOKE_HEAT_HOLD_MS = 2000; // smoke while temp >= TEMP_WARN_C
constexpr uint32_t CRIT_SMOKE_HOLD_MS      = 3000; // smoke by itself, held this long -> EMERGENCY + supply cut
// Flame (debounced) is critical immediately.

// ===== Wi-Fi settings: CHANGE THE PASSWORD BELOW (8 to 63 characters). The sketch will not compile with the example one. =====
#define WIFI_AP_SSID "STSMN-TR001"
#define WIFI_AP_PASSWORD "CHANGE_ME_min8chars"
// Phone that receives the SMS alert, with country code (e.g. "+919876543210"). Leave the X placeholder to switch SMS off.
#define ALERT_PHONE_NUMBER "+91XXXXXXXXXX"


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
  int smokePct;      int flamePct;     // signal strength 0..100 (0 = boolean-only caller: smoke/flame then count as 100)
};

struct RiskBreakdown { int temp, current, voltage, smoke, flame, oil, base; };

// Strength of the smoke / flame signal, 0..100. Callers that only know on/off (smokePct/flamePct left 0) count as 100.
inline int effSmokePct(const RiskInput& in) { return in.smokePct > 0 ? (in.smokePct > 100 ? 100 : in.smokePct) : (in.smoke ? 100 : 0); }
inline int effFlamePct(const RiskInput& in) { return in.flamePct > 0 ? (in.flamePct > 100 ? 100 : in.flamePct) : (in.flame ? 100 : 0); }

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
  p.smoke = (int)lroundf(PTS_SMOKE * effSmokePct(in) / 100.0f);
  p.flame = (int)lroundf(PTS_FLAME * effFlamePct(in) / 100.0f);
  if (in.oilValid) {
    // Low level: full points at/below OIL_LOW_PERCENT, none at/above OIL_OK_PERCENT.
    p.oil = (int)lroundf(PTS_OIL - rampPoints((float)in.oilPct, (float)OIL_LOW_PERCENT, (float)OIL_OK_PERCENT, (float)PTS_OIL));
  }
  int sum = p.temp + p.current + p.voltage + p.smoke + p.flame + p.oil;
  p.base = sum < 0 ? 0 : (sum > 100 ? 100 : sum);
  return p;
}

// "How much of the danger range is used", 0..100 per sensor, -1 = no valid reading / not fitted. For the app.
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
  RiskResult update(const RiskInput& in, uint32_t now) {
    RiskResult r;
    r.parts = scoreSensors(in);
    // Only sensors that are FITTED (config.h HAS_*) can be a fault; an unplugged-by-design sensor is not one.
    r.sensorFault = (HAS_TEMP_SENSOR && !in.tempValid) || (HAS_CURRENT_SENSOR && !in.currentValid) ||
                    (HAS_VOLTAGE_SENSOR && !in.voltageValid);

    bool tempCrit = tempCritTimer_.update(in.tempValid && in.tempC >= TEMP_CRIT_C, now, CRIT_TEMP_HOLD_MS);
    bool curCrit  = currentCritTimer_.update(in.currentValid && fabsf(in.currentA) >= CURRENT_CRIT_A, now, CRIT_CURRENT_HOLD_MS);
    // Smoke with an unknown temperature is treated as smoke WITH heat (conservative: a blind sensor must not hide a fire).
    bool smokeHeat = smokeHeatTimer_.update(in.smoke && (!in.tempValid || in.tempC >= TEMP_WARN_C), now, CRIT_SMOKE_HEAT_HOLD_MS);
    bool smokeAlone = smokeTimer_.update(in.smoke, now, CRIT_SMOKE_HOLD_MS);   // real smoke that does not go away
    // Smoke AND fire together (even weak signals) = fire in progress: alarm + supply cut at once.
    const bool smokeSignal = in.smoke || effSmokePct(in) >= SIGNAL_MIN_PCT;
    const bool flameSignal = in.flame || effFlamePct(in) >= SIGNAL_MIN_PCT;
    const bool smokeAndFire = smokeSignal && flameSignal;
    r.critical = in.flame || tempCrit || curCrit || smokeHeat || smokeAlone || smokeAndFire;

    int risk = r.parts.base;
    if (r.sensorFault && risk < FLOOR_SENSOR_FAULT_RISK) risk = FLOOR_SENSOR_FAULT_RISK;
    const RiskPct pc = scorePercent(in);
    const bool stressed = pc.temp >= SENSOR_WARN_PCT || pc.current >= SENSOR_WARN_PCT || pc.voltage >= SENSOR_WARN_PCT;
    if (stressed && risk < FLOOR_STRESS_RISK) risk = FLOOR_STRESS_RISK;
    if ((smokeSignal || flameSignal) && risk < FLOOR_SMOKE_RISK) risk = FLOOR_SMOKE_RISK;
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
  HoldTimer tempCritTimer_, currentCritTimer_, smokeHeatTimer_, smokeTimer_;
};

// ===== protection_logic.h =====
// Relay arming logic. Pure C++ (no Arduino): tested on a PC in tests/protection_logic_test.cpp.
//
//   * Boot: the supply stays CUT until the boot self-check time has passed AND all protection sensors
//     were valid continuously for RELAY_ARM_STABLE_MS. Invalid sensor data can never authorise operation.
//   * EMERGENCY always cuts the supply and disarms; coming back needs a fresh stable-valid period.
//   * A protection-sensor fault that lasts SENSOR_FAULT_CUTOFF_MS cuts the supply (protection is blind).
// The phone never appears here: connectivity cannot arm, disarm or override anything.
#include <stdint.h>

class RelayGate {
 public:
  // Returns true when the supply may be connected. Call once per protection cycle.
  bool update(uint32_t now, Status status, bool sensorFault) {
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
    // Latch the CUT level into the output register BEFORE the pin becomes an output, so the relay never
    // sees a pulse of the "energised" level during boot (relevant for active-low modules).
    digitalWrite(PIN_RELAY, RELAY_ACTIVE_LOW ? HIGH : LOW);
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

// ===== gps_nmea.h =====
// NMEA parser for the GPS module (NEO-6M and compatible). No Arduino dependency: testable on a PC.
// Only sentences with a correct checksum are used. A position is reported only while the module says "fix".
#include <stdint.h>
#include <stddef.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>

struct GpsFix {
  bool valid = false;
  double lat = 0, lng = 0;   // decimal degrees, + = N/E, - = S/W
  int sats = 0;
};

class NmeaParser {
 public:
  // Feed one received character. Returns true when a complete, checksum-valid RMC/GGA sentence was processed.
  bool feed(char c) {
    if (c == '$') { len_ = 0; in_ = true; return false; }
    if (!in_) return false;
    if (c == '\r' || c == '\n') {
      in_ = false;
      if (len_ == 0) return false;
      buf_[len_] = 0;
      return handle();
    }
    if (len_ >= sizeof(buf_) - 1) { in_ = false; return false; }   // too long: junk
    buf_[len_++] = c;
    return false;
  }
  const GpsFix& fix() const { return fix_; }

  // XOR of the characters between '$' and '*'.
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

  // "4807.038" + 'N' -> 48.1173
  static bool coord(const char* v, char hemi, bool isLat, double& out) {
    if (!v || !*v) return false;
    // Own tiny decimal parser (no libc strtod: some editors' analyzers reject it). NMEA values are plain "ddmm.mmmm".
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
    if (checksum(buf_, (size_t)(star - buf_)) != (uint8_t)(h1 * 16 + h2)) return false;   // corrupted: ignore
    *star = 0;

    char* f[16];
    int n = 0;
    char* p = buf_;
    while (n < 16) {                      // split on commas in place (empty fields stay empty strings)
      f[n++] = p;
      char* comma = strchr(p, ',');
      if (!comma) break;
      *comma = 0;
      p = comma + 1;
    }
    const size_t tl = strlen(f[0]);
    if (tl < 5) return false;
    const char* type = f[0] + tl - 3;     // GPRMC / GNRMC -> "RMC"

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

// ===== sms_logic.h =====
// Pure SMS / GSM logic (no Arduino dependency) so it can be tested on a PC.
#include <stdint.h>
#include <stddef.h>
#include <stdio.h>
#include <string.h>

namespace sms {

// "+919876543210" or "9876543210": optional '+', then 8..15 digits. The placeholder "+91XXXXXXXXXX" is NOT valid.
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

// Turns what a person types ("75050 45948", "+91-75050-45948", "917505045948") into "+<digits>".
// 10 digits starting 6-9 are taken as an Indian mobile (+91). Returns false if the result is not a plausible number.
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
  else if (!plus && k == 12 && d[0] == '9' && d[1] == '1') { /* already has the country code */ }
  else if (!plus && k == 11 && d[0] == '0') { char t[32]; snprintf(t, sizeof(t), "91%s", d + 1); strcpy(d, t); k = strlen(d); }
  if (!plus && !(k == 12 && d[0] == '9' && d[1] == '1')) return false;   // without "+" only the forms above are understood
  if (k < 8 || k > 15) return false;
  if ((size_t)snprintf(out, n, "+%s", d) >= n) return false;
  return isValidPhone(out);
}

// "+917505045948" -> "+91*******48" (never the whole number over Wi-Fi; just enough for the person to recognise it).
inline void maskPhone(const char* p, char* out, size_t n) {
  size_t len = strlen(p);
  if (n == 0) return;
  if (len < 6 || len + 1 > n) { out[0] = 0; return; }
  for (size_t i = 0; i < len; i++) out[i] = (i < 3 || i >= len - 2) ? p[i] : '*';
  out[len] = 0;
}

// Plain-ASCII alert (one 160-character SMS). The position is included ONLY when the GPS has a real fix.
// Returns false if it did not fit.
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

// Decides WHEN to send an alert SMS. level: 0 SAFE, 1 WARNING, 2 EMERGENCY.
// - a new alert episode (level rises to the threshold) sends once,
// - never more often than minIntervalMs (an episode that starts too soon waits),
// - while the alarm persists, a reminder every repeatMs,
// - a failed send is retried after minIntervalMs via retry().
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

// "+CSQ: 18,0" -> 18 (0..31, 99 = unknown). -1 if the reply has no +CSQ line.
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

// "+CREG: 0,1" (home) or "+CREG: 0,5" (roaming) -> true. Anything else (searching, denied, no reply) -> false.
inline bool parseCregRegistered(const char* resp) {
  const char* p = resp ? strstr(resp, "+CREG:") : nullptr;
  if (!p) return false;
  p += 6;
  while (*p == ' ') p++;
  const char* comma = strchr(p, ',');
  const char* q = comma ? comma + 1 : p;      // "+CREG: n,stat" or the unsolicited form "+CREG: stat"
  while (*q == ' ') q++;
  return *q == '1' || *q == '5';
}

}  // namespace sms

// ===== sim800.h =====
// SIM800L driver: sends SMS over AT commands. Blocking calls (up to ~40 s) -> only used from the comm task,
// NEVER from the protection task. Arduino-ESP32 only (uses HardwareSerial).

class SimModule {
 public:
  // Starts the UART and checks that the module answers. Returns true when it is ready to send SMS.
  bool begin(HardwareSerial& ser, int rxPin, int txPin, uint32_t baud) {
    s_ = &ser;
    ser.begin(baud, SERIAL_8N1, rxPin, txPin);
    delay(300);
    ok_ = false;
    for (int i = 0; i < 4 && !ok_; i++) ok_ = cmd("AT", "OK", 1000);
    if (!ok_) return false;
    cmd("ATE0", "OK", 1000);                // no echo
    ok_ = cmd("AT+CMGF=1", "OK", 2000);     // text mode
    if (ok_) cmd("AT+CSCS=\"GSM\"", "OK", 2000);
    return ok_;
  }
  bool ready() const { return ok_; }

  int signal() {                            // 0..31, 99 unknown, -1 no answer
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
    if (!waitFor(">", nullptr, 8000)) { s_->write((uint8_t)0x1B); return false; }   // ESC cancels a half-open SMS
    s_->print(text);
    s_->write((uint8_t)0x1A);                                                      // Ctrl-Z = send
    return waitFor("+CMGS:", "ERROR", 40000);
  }

 private:
  HardwareSerial* s_ = nullptr;
  bool ok_ = false;
  char rx_[200] = "";

  void flushInput() { while (s_ && s_->available()) s_->read(); }

  // Collects the reply in rx_ until `want` appears (true), `bad` appears or the time runs out (false).
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

// ===== main sketch =====
// ---- Compile-time check of the Wi-Fi settings (a known default password must never ship) ------------------
// (No default arguments: the Arduino IDE / ArduinoDroid copy them into auto-generated prototypes -> "redefinition of default argument".)
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

// GPS + SIM (SMS) run in a separate low-priority task: a slow modem can never delay protection.
static NmeaParser gps;
static SimModule sim;
static sms::SmsGate smsGate(SMS_ON_WARNING, SMS_MIN_INTERVAL_MS, SMS_REPEAT_MS);   // protection task only
static Preferences prefs;                 // alert phone number survives power-off (NVS flash)
static char alertPhone[24] = "";          // guarded by stateMutex
static volatile bool smsEnabled = false;  // true while alertPhone is a valid number
struct CommState {                       // written by the comm task, read by /api/health (under stateMutex)
  bool gpsValid = false; double lat = 0, lng = 0; int sats = 0; uint32_t lastFixMs = 0;
  bool gsmReady = false, gsmRegistered = false; int gsmSignal = -1;
  uint32_t smsSent = 0, smsFailed = 0;
};
struct AlertRequest { bool pending = false; int status = 0; int risk = 0; bool tempValid = false; float tempC = 0; };
static CommState comm;
static AlertRequest alertReq;
static volatile bool smsRetryFlag = false;

// State shared between the protection task (writer) and the HTTP handlers (readers).
// Guarded by stateMutex; the mutex is NEVER held while sending data over the network.
static SemaphoreHandle_t stateMutex = nullptr;
static char statusJson[640] = "";
static SensorReadings lastReadings{};
static bool relayArmed = false;
static uint32_t seq = 0;

// Only the protection task touches these.
static uint32_t lastCycle = 0;
static RiskResult lastRisk{};
static bool alarmOn = false;

// 64-bit uptime: does not wrap after 49.7 days like millis().
static uint64_t uptimeMs() { return (uint64_t)(esp_timer_get_time() / 1000); }

// ---- JSON helpers: invalid sensors are sent as null, never as a fake number --
static void fmtFloat(char* out, size_t n, bool valid, float v, int decimals) {
  if (!valid || isnan(v) || isinf(v)) snprintf(out, n, "null");
  else snprintf(out, n, "%.*f", decimals, v);
}

// Builds into `out` (size n). Field names/types are the contract with the Android app: do not rename.
static void buildStatusJson(char* out, size_t n, const SensorReadings& s, const RiskResult& r, const RiskPct& pc,
                            bool relay, bool alarm, uint32_t seqNo) {
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
    "\"pct\":{\"temp\":%s,\"current\":%s,\"voltage\":%s,\"smoke\":%d,\"flame\":%d}}",
    STSMN_DEVICE_ID, STSMN_FW_VERSION, (unsigned long long)uptimeMs(), t, v, c,
    s.smoke ? "true" : "false", s.flame ? "true" : "false", oil, r.risk,
    statusName(r.status), relay ? "true" : "false", alarm ? "true" : "false",
    (unsigned long)seqNo, pT, pI, pV, pc.smoke, pc.flame);
  // Never serve a half-written JSON: on overflow the app gets an explicit invalid packet (-> STALE), not a wrong value.
  if (written < 0 || written >= (int)n) snprintf(out, n, "{\"error\":\"status buffer overflow\"}");
}

// ---- Alert phone number: set from the app, stored on the ESP32, SMS goes to this number --------------------
static void setAlertPhone(const char* normalized) {
  xSemaphoreTake(stateMutex, portMAX_DELAY);
  strlcpy(alertPhone, normalized, sizeof(alertPhone));
  smsEnabled = sms::isValidPhone(alertPhone);
  xSemaphoreGive(stateMutex);
}
static void loadAlertPhone() {
  char stored[24] = "", norm[24] = "";
  prefs.begin("stsmn", false);
  if (prefs.isKey("phone")) prefs.getString("phone", stored, sizeof(stored));
  else strlcpy(stored, ALERT_PHONE_NUMBER, sizeof(stored));        // first boot: the number compiled into the sketch (if any)
  if (sms::normalizePhone(stored, norm, sizeof(norm))) setAlertPhone(norm);
}

// ---- HTTP handlers (read-only for sensors/relay; the ONLY thing that can be written is the SMS number) --- -------------------------
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
  if (!snap[0]) { sendJson(503, "{\"error\":\"not ready\"}"); return; }   // no state computed yet: never an empty 200
  sendJson(200, snap);
}

static void handleHealth() {
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
  char buf[860];
  snprintf(buf, sizeof(buf),
    "{\"deviceId\":\"%s\",\"fw\":\"%s\",\"uptime\":%llu,\"seq\":%lu,\"clients\":%d,"
    "\"mq2Ready\":%s,\"tempValid\":%s,\"currentValid\":%s,\"voltageValid\":%s,\"relayMismatch\":%s,"
    "\"armed\":%s,\"heapFree\":%lu,\"stackFreeBytes\":%lu,"
    "\"gpsFix\":%s,\"gpsSats\":%d,\"lat\":%s,\"lng\":%s,"
    "\"gsmReady\":%s,\"gsmRegistered\":%s,\"gsmSignal\":%d,\"smsEnabled\":%s,\"smsSent\":%lu,\"smsFailed\":%lu,"
    "\"mq2Mv\":%d,\"mq2BaseMv\":%d,\"alertPhone\":\"%s\"}",
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
    (int)rd.mq2Mv, (int)rd.mq2BaseMv, phoneMasked);
  sendJson(200, buf);
}

// POST /api/contact   phone=<number>   (form field).  Empty phone = switch SMS off. Cannot touch the relay or any threshold.
static void handleContactSet() {
  if (!server.hasArg("phone")) { sendJson(400, "{\"error\":\"phone missing\"}"); return; }
  const String raw = server.arg("phone");
  if (raw.length() > 30) { sendJson(400, "{\"error\":\"phone too long\"}"); return; }
  char norm[24] = "";
  if (raw.length() == 0) {
    setAlertPhone("");
    prefs.putString("phone", "");
    sendJson(200, "{\"ok\":true,\"smsEnabled\":false}");
    return;
  }
  if (!sms::normalizePhone(raw.c_str(), norm, sizeof(norm))) { sendJson(400, "{\"error\":\"invalid phone number\"}"); return; }
  setAlertPhone(norm);
  prefs.putString("phone", norm);
  char masked[24];
  sms::maskPhone(norm, masked, sizeof(masked));
  char buf[96];
  snprintf(buf, sizeof(buf), "{\"ok\":true,\"smsEnabled\":true,\"alertPhone\":\"%s\"}", masked);
  Serial.printf("Alert number saved: %s\n", masked);
  sendJson(200, buf);
}

static void handleNotFound() {
  const String uri = server.uri();
  if (uri == "/api/status" || uri == "/api/health" || uri == "/api/contact") {     // known path, wrong method
    server.sendHeader("Allow", uri == "/api/contact" ? "POST" : "GET");
    sendJson(405, "{\"error\":\"method not allowed\"}");
  } else {
    sendJson(404, "{\"error\":\"not found\"}");
  }
}

// ---- One protection cycle (protection task only) -----------------------------
static void protectionCycle(uint32_t now) {
  SensorReadings rd = sensors.sample(now);

  RiskInput in{rd.tempValid, rd.tempC, rd.currentValid, rd.currentA, rd.voltageValid, rd.voltageV,
               rd.smoke, rd.flame, rd.oilValid, rd.oilPct, rd.smokePct, rd.flamePct};
  lastRisk = riskEngine.update(in, now);

  // Supply allowed only when armed (boot check + stable valid sensors) and never in EMERGENCY.
  const bool allow = relayGate.update(now, lastRisk.status, lastRisk.sensorFault);
  actuators.setRelayAllowed(allow);
  alarmOn = (lastRisk.status == Status::EMERGENCY);

  // SMS alert request (the comm task sends it; nothing here waits for the modem).
  if (smsRetryFlag) { smsRetryFlag = false; smsGate.retry(); }
  if (smsEnabled && smsGate.update((int)lastRisk.status, now)) {
    xSemaphoreTake(stateMutex, portMAX_DELAY);
    alertReq.pending = true; alertReq.status = (int)lastRisk.status; alertReq.risk = lastRisk.risk;
    alertReq.tempValid = rd.tempValid; alertReq.tempC = rd.tempC;
    xSemaphoreGive(stateMutex);
  }

  char json[sizeof(statusJson)];
  const uint32_t nextSeq = seq + 1;
  buildStatusJson(json, sizeof(json), rd, lastRisk, scorePercent(in), actuators.relayAllowed(), alarmOn, nextSeq);

  xSemaphoreTake(stateMutex, portMAX_DELAY);
  seq = nextSeq;
  lastReadings = rd;
  relayArmed = relayGate.armed();
  strlcpy(statusJson, json, sizeof(statusJson));
  xSemaphoreGive(stateMutex);

  // Serial log makes the score explainable to judges (115200 baud).
  Serial.printf("seq=%lu %s risk=%d [T%d I%d V%d S%d F%d O%d] relay=%d armed=%d crit=%d fault=%d ok(T%d I%d V%d) smoke=%d%% flame=%d%% mq=%d/%d\n",
    (unsigned long)nextSeq, statusName(lastRisk.status), lastRisk.risk,
    lastRisk.parts.temp, lastRisk.parts.current, lastRisk.parts.voltage,
    lastRisk.parts.smoke, lastRisk.parts.flame, lastRisk.parts.oil,
    actuators.relayAllowed(), relayGate.armed(), lastRisk.critical, lastRisk.sensorFault,
    rd.tempValid, rd.currentValid, rd.voltageValid, rd.smokePct, rd.flamePct, (int)rd.mq2Mv, (int)rd.mq2BaseMv);
}

// Protection task: sensors + risk + relay + LEDs, independent of Wi-Fi/HTTP.
static void protectionTask(void*) {
  esp_task_wdt_add(nullptr);                        // reset the ESP32 (relay -> cutoff) if this task ever hangs
  for (;;) {
    const uint32_t now = millis();
    sensors.pollTemperature(now);                   // non-blocking
    if ((uint32_t)(now - lastCycle) >= SENSOR_CYCLE_MS) {
      lastCycle = now;
      protectionCycle(now);
    }
    actuators.applyStatus(lastRisk.status, alarmOn, now);
    esp_task_wdt_reset();
    vTaskDelay(pdMS_TO_TICKS(PROTECTION_LOOP_MS));
  }
}

// GPS reader + SIM/SMS sender. Low priority; never touches sensors, relay or LEDs.
static void commTask(void*) {
  uint32_t lastGsmCheck = 0, lastSimTry = 0;
  bool simTried = false;
  for (;;) {
    const uint32_t now = millis();

    // 1) GPS: feed the parser with whatever arrived on UART2.
    for (int guard = 0; guard < 256 && Serial2.available(); guard++) {
      if (gps.feed((char)Serial2.read())) {
        const GpsFix f = gps.fix();
        xSemaphoreTake(stateMutex, portMAX_DELAY);
        comm.gpsValid = f.valid; comm.sats = f.sats;
        if (f.valid) { comm.lat = f.lat; comm.lng = f.lng; comm.lastFixMs = now; }
        xSemaphoreGive(stateMutex);
      }
    }

    // 2) SIM: (re)start the modem, then keep signal/registration up to date.
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

    // 3) Alert SMS requested by the protection task.
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
  esp_task_wdt_init(WDT_TIMEOUT_S, true);           // true = panic/reset on timeout
#endif
}

void setup() {
  actuators.begin();            // relay -> cutoff before anything else
  Serial.begin(115200);
  Serial.println("\nSTSMN firmware " STSMN_FW_VERSION);

  stateMutex = xSemaphoreCreateMutex();
  loadAlertPhone();             // SMS number saved earlier (from the app) or the one in secrets.h
  sensors.begin();              // includes ACS712 zero calibration (relay is in cutoff = no load current)

  // First state is computed from real readings before anyone can request it.
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
  server.onNotFound(handleNotFound);
  server.begin();

  Serial2.begin(GPS_BAUD, SERIAL_8N1, PIN_GPS_RX, PIN_GPS_TX);     // GPS module
  Serial.println(smsEnabled ? "SMS alerts: ON" : "SMS alerts: OFF (save a number in the app: Settings > Alert number)");
  Serial.printf("Sensors fitted: temp=%d current=%d voltage=%d level=%d (config.h HAS_*; unfitted = ignored, shown as null)\n",
    HAS_TEMP_SENSOR, HAS_CURRENT_SENSOR, HAS_VOLTAGE_SENSOR, HAS_LEVEL_SENSOR);
  Serial.printf("Relay: ACTIVE_LOW=%d. At boot (cutoff) the relay module's LED must be OFF; it clicks ON ~5 s later when all fitted sensors are valid. If it is the other way round, flip RELAY_ACTIVE_LOW in config.h.\n", (int)RELAY_ACTIVE_LOW);
  xTaskCreatePinnedToCore(commTask, "stsmn_comm", COMM_TASK_STACK, nullptr, 1, nullptr, 0);

  startWatchdog();
  xTaskCreatePinnedToCore(protectionTask, "stsmn_protect", PROTECTION_TASK_STACK, nullptr,
                          PROTECTION_TASK_PRIORITY, &protectionHandle, 1);
}

void loop() {
  server.handleClient();        // phone is optional; protection runs in its own task and never waits for this
  delay(2);
}
