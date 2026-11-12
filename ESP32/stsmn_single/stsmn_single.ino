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
#include <esp_system.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include <BLEDevice.h>
#include <BLEServer.h>
#include <BLEUtils.h>
#include <BLE2902.h>
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
// A fitted sensor that has NEVER given a valid reading since boot is treated as 'not connected' (ignored, shown as null) instead of a
// fault. Once it has worked, losing it IS a fault (WARNING + supply cut after SENSOR_FAULT_CUTOFF_MS). false = strict: every HAS_* sensor must work.
constexpr bool AUTO_IGNORE_UNPLUGGED = true;
constexpr bool HAS_VOLTAGE_SENSOR = true;
constexpr bool HAS_LEVEL_SENSOR   = true;
// Flame strength (weak / strong) needs the module's AO pin wired to GPIO36. Without it only the digital
// output (DO) is used: flame = on/off (always "strong"). Set true ONLY if AO is really connected.
constexpr bool HAS_FLAME_ANALOG   = true;   // AO wired to GPIO36 (ADC1), DO wired to GPIO25

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

// ---------- Bluetooth Low Energy (phone <-> ESP32 without Wi-Fi) -------------------------------------------
// 1 = BLE on (ESP32 BLE library of the core). It adds ~0.6-0.7 MB: the sketch no longer fits the default partition (1.3 MB), so
// you MUST pick Partition Scheme "Huge APP (3MB No OTA)" in the board options, otherwise the build stops with "Sketch too big".
// Protection never depends on BLE.
#define ENABLE_BLE 0
#define BLE_DEVICE_NAME "STSMN-TR001"
// Nordic UART service UUIDs (generic BLE terminal apps can read the stream too).
#define BLE_SERVICE_UUID "6e400001-b5a3-f393-e0a9-e50e24dcca9e"
#define BLE_RX_UUID      "6e400002-b5a3-f393-e0a9-e50e24dcca9e"   // phone -> ESP32 (write): "PHONE <password> <number>"
#define BLE_TX_UUID      "6e400003-b5a3-f393-e0a9-e50e24dcca9e"   // ESP32 -> phone (notify): "ST {status json}\n", "HE {health json}\n"
constexpr uint32_t BLE_STATUS_EVERY_MS = 1000;
constexpr uint32_t BLE_HEALTH_EVERY_MS = 5000;

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
constexpr uint32_t COMM_TASK_STACK     = 8192;     // GPS + SMS task (low priority, never touches the relay)

// ---------- Relay polarity (VERIFY ON YOUR MODULE!) --------------------------
// Logical meaning is fixed: relay=true -> supply allowed, relay=false -> cutoff.
// Wire the load through COM + NO so that a dead/unpowered ESP32 = cutoff.
constexpr bool RELAY_ACTIVE_LOW = true;    // most 1-channel modules are active-low
// 5V relay modules driven from the ESP32's 3.3V: a plain HIGH is NOT high enough for many active-low modules, the relay
// then stays ON even when the firmware says "cut". With this set (and ACTIVE_LOW) the cut state releases the pin
// (high-impedance, the module's own pull-up switches it OFF) and the ON state drives LOW. Works with 3.3V and 5V modules.
constexpr bool RELAY_OPEN_DRAIN_OFF = true;
constexpr bool FLAME_ACTIVE_LOW = true;    // most flame modules pull LOW on flame
constexpr bool BUZZER_ACTIVE_HIGH = true;

// ---------- Timing -----------------------------------------------------------
constexpr uint32_t SENSOR_CYCLE_MS     = 250;   // one protection cycle (seq++ each cycle)
constexpr uint32_t TEMP_REQUEST_EVERY_MS = 1000;
constexpr uint32_t TEMP_CONVERSION_MS  = 200;   // DS18B20 at 10-bit resolution
constexpr uint32_t BOOT_SELF_CHECK_MS  = 3000;  // relay stays in cutoff until done
constexpr uint32_t MQ2_WARMUP_MS       = 60000; // smoke reported false while warming
constexpr uint32_t RECOVERY_MS         = 5000;  // stable time before leaving EMERGENCY

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

// ---------- Flame module analog output (HAS_FLAME_ANALOG) - see flame_detect.h and docs/FLAME_AO_CALIBRATION.md ----------
// The AO value is judged against a measured AMBIENT BASELINE, never against a fixed ADC level. Values marked
// CALIBRATE are PLACEHOLDERS, not measurements: until you have measured your own module (guide above) the
// firmware prints "FLAME: AO NOT CALIBRATED" at boot. Nothing here claims to be correct for your module.
constexpr bool  FLAME_AO_CALIBRATED   = false;  // set true ONLY after doing the calibration guide
constexpr bool  FLAME_AO_DROPS_WITH_FLAME = true; // most IR modules: AO voltage FALLS as flame IR rises. The firmware checks this
                                                  // against DO at run time and falls back to DO-only if AO moves the other way.
constexpr int   FLAME_MAINS_HZ        = 50;      // 50 (India/EU) or 60. AO is read continuously (~1 ms) and AVERAGED over one mains ripple
                                                 // period (half a mains cycle = 10 ms at 50 Hz), so lamp ripple (2 x mains) does not alias into fake flicker.
constexpr int FLAME_OVERSAMPLE = 4;   // AO reads averaged per detector sample (one sample per protection-loop iteration)
constexpr float FLAME_FULL_FRACTION   = 0.5f; // CALIBRATE: AO change from ambient to a strong controlled flame = 100 %
constexpr float FLAME_MIN_DELTA_MV    = 40.0f;
constexpr float FLAME_DO_ENTER_RATIO  = 0.35f;
constexpr float FLAME_NOISE_FLOOR_MV  = 5.0f;    // lowest noise (sigma) the detector assumes
constexpr float FLAME_ENTER_SIGMAS    = 6.0f;    // enter threshold = max(MIN_DELTA, this x measured noise)
constexpr float FLAME_EXIT_RATIO      = 0.5f;    // hysteresis: leave when deviation < enter x this
constexpr float FLAME_FLICKER_SIGMAS  = 5.0f;    // flicker swing must exceed this x noise ...
constexpr float FLAME_FLICKER_FRAC_PER_SIGMA = 0.03f;   // flicker swing must be >= this x (strictness) x the deviation
constexpr uint32_t FLAME_RELEASE_MS = 6000;
constexpr int   FLAME_DO_MIN_TRANSITIONS = 2;
constexpr int   FLAME_DO_MAX_TRANSITIONS = 20;
constexpr int   FLAME_MIN_REVERSALS   = 4;       // direction changes inside the ~0.64 s window (= flicker of roughly 3 Hz and up)
constexpr int   FLAME_MAX_REVERSALS   = 28;      // more than this = sample noise / electrical interference, not a flame
constexpr int   FLAME_FLICKER_CYCLES  = 3;       // flicker must be seen in this many consecutive 250 ms cycles (not one shadow/blink)
constexpr float FLAME_RISE_FRAC       = 0.5f;    // "sudden rise" = deviation gains this x enter within FLAME_RISE_WINDOW_MS
constexpr uint32_t FLAME_RISE_WINDOW_MS = 1000;
constexpr uint32_t FLAME_CORROB_HOLD_MS = 3000;  // a sudden rise stays "recent" for this long
constexpr uint32_t FLAME_CONFIRM_MS   = 500;     // strong + corroborated for this long = EMERGENCY (the only added delay)
constexpr uint32_t FLAME_INIT_MS      = 3000;    // quiet time needed to learn the ambient baseline at boot
constexpr uint32_t FLAME_INIT_GIVEUP_MS = 15000; // AO never became quiet (floating wire?) -> AO unusable, DO-only
constexpr float FLAME_INIT_MAX_SIGMA_MV = 40.0f; // noisier than this at boot = AO not usable
constexpr float FLAME_BASELINE_ALPHA  = 0.004f;  // baseline drift per cycle (~60 s time constant), only while quiet
constexpr uint32_t FLAME_REBASE_MS    = 20000;   // a steady, non-flickering, DO-less change this long = new ambient (sun/lamp)
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
                                                 // the relay); true = DO alone is an EMERGENCY again (flame can no longer be missed, sunlight WILL trigger)
constexpr bool  FLAME_CALIBRATION_LOG = false;   // true: print AO / baseline / deviation / flicker once per second (calibration)
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
constexpr int PTS_CURRENT = 25, PTS_VOLTAGE = 15, PTS_OIL = 18;
static_assert(PTS_FLAME + PTS_TEMP == PTS_SMOKE, "fire half (flame + temperature) must equal the smoke half");

constexpr float TEMP_WARN_C = 40.0f;   // 0 points below, ramps up
constexpr float TEMP_CRIT_C = 70.0f;   // full points at/above
constexpr float CURRENT_WARN_A = 2.0f;
constexpr float CURRENT_CRIT_A = 4.5f;
constexpr float VOLT_OK_BAND_PCT   = 15.0f;  // +/- % of nominal = no points
constexpr float VOLT_FULL_BAND_PCT = 40.0f;  // +/- % of nominal = full points

// ---------- Status state machine --------------------------------------------
constexpr int WARNING_ENTER   = 55;   // yellow LED / WARNING from 55 (a dry demo level sensor alone = 18 points stays green)
constexpr int WARNING_EXIT    = 45;   // hysteresis
constexpr int EMERGENCY_ENTER = 80;   // PDF example: 90+ = emergency
constexpr int EMERGENCY_EXIT  = 60;   // hysteresis
constexpr int FLOOR_SENSOR_FAULT_RISK = 55;  // a dead protection sensor = WARNING
constexpr int SENSOR_WARN_PCT         = 50;  // temperature / current / voltage in the upper half of its danger range ...
constexpr int FLOOR_STRESS_RISK       = 55;  // ... = at least WARNING, even if the points do not add up
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
constexpr uint32_t PROTECTION_TASK_STACK    = 10240;
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
#define STSMN_API_TOKEN WIFI_AP_PASSWORD


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
  bool oilLow;                         // level below OIL_LOW_PERCENT for OIL_LOW_HOLD_MS (optional WARNING floor)
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
  bool latched;        // over-current trip: stays EMERGENCY / supply cut until an authorised reset
  int trips;           // over-current trips since boot
  uint32_t resetWaitMs;// minimum open time still to wait before a reset is accepted
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
  // Runtime 'is this sensor connected at all' (a sensor never seen valid since boot is treated as not fitted). Default: all fitted.
  void setFitted(bool temp, bool current, bool voltage) { fitT_ = temp; fitC_ = current; fitV_ = voltage; }
  RiskResult update(const RiskInput& in, uint32_t now) {
    RiskResult r;
    r.parts = scoreSensors(in);
    r.latched = false; r.trips = 0; r.resetWaitMs = 0;
    // Only sensors that are FITTED (config.h HAS_*) can be a fault; an unplugged-by-design sensor is not one.
    r.sensorFault = (fitT_ && HAS_TEMP_SENSOR && !in.tempValid) || (fitC_ && HAS_CURRENT_SENSOR && !in.currentValid) ||
                    (fitV_ && HAS_VOLTAGE_SENSOR && !in.voltageValid);

    bool tempCrit = tempCritTimer_.update(in.tempValid && in.tempC >= TEMP_CRIT_C, now, CRIT_TEMP_HOLD_MS);
    bool curCrit  = currentCritTimer_.update(in.currentValid && fabsf(in.currentA) >= CURRENT_CRIT_A, now, CRIT_CURRENT_HOLD_MS);
    // Smoke with an unknown temperature is treated as smoke WITH heat (conservative: a blind sensor must not hide a fire).
    bool smokeHeat = smokeHeatTimer_.update(in.smoke && (!in.tempValid || in.tempC >= TEMP_WARN_C), now, CRIT_SMOKE_HEAT_HOLD_MS);
    bool smokeAlone = smokeTimer_.update(in.smoke, now, CRIT_SMOKE_HOLD_MS);   // real smoke that does not go away
    // Smoke AND fire together (even weak signals) = fire in progress: alarm + supply cut at once.
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
  bool update(uint32_t now, Status status, bool sensorFault) { return update(now, status, sensorFault, false); }
  // hold = boot hold after an abnormal reset: the supply stays cut until the caller releases it.
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

// ===== flame_detect.h =====
// Flame detector for an IR flame module with analog (AO) + digital (DO) outputs.
// Pure C++ (no Arduino): tested on a PC with synthetic signals in tests/firmware/flame_detect_test.cpp.
//
// What it does (and does not):
//   * AO is the PRIMARY input. It is judged against a measured ambient BASELINE (learned at boot, then followed
//     only slowly and only while the signal is quiet), never against a fixed ADC level.
//   * A candidate must be above an enter threshold (>= noise based, hysteresis on exit) and must show FLICKER (the one
//     property steady sunlight/lamps lack): several direction changes of AO inside a ~0.64 s window, in 2 consecutive
//     cycles. For an EMERGENCY the flicker must also come with a sudden rise OR DO asserted (DO supports, it never replaces flicker).
//     Once confirmed it stays (sticky) so a flame that saturates the sensor and stops flickering is not dropped.
//     An uncorroborated change (slow sun, lamp, reflection) is capped below the "signal present" level, and
//     after FLAME_REBASE_MS it becomes the new baseline. It can never cause an EMERGENCY.
//   * DO alone, or a strong STEADY AO change (sun, lamp, reflection) is never a warning nor an emergency while AO is usable.
//   * If AO is unusable (noisy/floating at boot, or it moves against DO = polarity wrong) the caller uses DO only:
//     only DO chatter (the comparator flickering with the flame) counts as an EMERGENCY; a steady DO is ignored (sunlight).
//   * Limit: a sensor cannot tell sunlight from flame by physics alone; this uses behaviour (suddenness, flicker,
//     DO) and can be fooled (see docs/FLAME_AO_CALIBRATION.md).
#include <stdint.h>
#include <math.h>

enum FlameWhy : uint8_t { FW_OK = 0, FW_LEARNING = 1, FW_NOISY = 2, FW_POLARITY = 3 };
enum FlameEvent : uint8_t { FE_NONE = 0, FE_BASELINE_READY = 1, FE_AO_UNUSABLE_NOISY = 2, FE_POLARITY_BAD = 4, FE_REBASED = 8,
                            FE_CRITICAL_ON = 16, FE_NEAR_ADC_CEILING = 32 };

struct FlameOut {
  int pct;            // 0..100 signal strength (only meaningful when aoUsable)
  bool critical;      // confirmed flame -> EMERGENCY (only meaningful when aoUsable)
  bool aoUsable;      // false: caller must use the legacy DO-only behaviour
  uint8_t events;     // FlameEvent bits, set only in the cycle they happen
  float mean, base, dev, noise, enter;   // diagnostics (mV)
  int reversals; float p2p;
  bool corroborated, flicker, rise;
  uint8_t why;        // FlameWhy: why aoUsable is false
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

  // Fast path: call about every FLAME_FAST_PERIOD_MS with the AO reading in mV.
  void feed(float mv) {
    buf_[idx_] = mv;                 // already an average over one mains-ripple period (see sensors.h)
    idx_ = (idx_ + 1) % N;
    if (count_ < N) count_++;
  }

  void feedDo(bool raw) {
    dbuf_[didx_] = raw ? 1 : 0;
    didx_ = (didx_ + 1) % N;
    if (dcount_ < N) dcount_++;
  }

  // Cycle path: call every SENSOR_CYCLE_MS. doOn = debounced DO asserted (comparator says flame).
  FlameOut evaluate(uint32_t now, bool doOn) {
    FlameOut o = {};
    o.noise = noise_; o.base = base_;
    const uint32_t dt = SENSOR_CYCLE_MS;
    if (count_ < N / 2) { o.aoUsable = false; o.why = FW_LEARNING; return o; }

    // ---- window statistics ----
    float mean = 0, mn = buf_[0], mx = buf_[0];
    for (int i = 0; i < count_; i++) { float v = buf_[i]; mean += v; if (v < mn) mn = v; if (v > mx) mx = v; }
    mean /= count_;
    float var = 0;
    for (int i = 0; i < count_; i++) { float d = buf_[i] - mean; var += d * d; }
    const float sd = sqrtf(var / count_);
    o.mean = mean; o.p2p = mx - mn;

    // ---- baseline learning at boot (needs a quiet AO and no DO) ----
    if (!baseReady_) {
      if (initStart_ == 0) initStart_ = now ? now : 1;
      if (doOn) { initQuietSince_ = 0; initSum_ = initSd_ = 0; initN_ = 0; }   // never learn a flame/bright source as "normal"
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

    // ---- deviation toward "flame" ----
    const float sgn = tune.invert ? -1.0f : 1.0f;
    const float dev = sgn * (base_ - mean);
    const float enterBase = fmaxf(tune.minDeltaMv, FLAME_ENTER_SIGMAS * noise_);
    const float enter = doOn ? fmaxf(4.0f * noise_, FLAME_DO_ENTER_RATIO * tune.minDeltaMv) : enterBase;
    const float exitT = enter * FLAME_EXIT_RATIO;
    const float holdT = sticky_ ? 0.3f * enterBase : exitT;
    if (!active_ && dev >= enter) active_ = true;
    else if (active_ && dev < holdT && !(sticky_ && doOn)) active_ = false;
    if (!sticky_ && ((!active_ && fabsf(dev) < enterBase) || uncorrMs_ >= 3000)) noise_ += 0.1f * (fmaxf(FLAME_NOISE_FLOOR_MV, sd) - noise_);

    // ---- polarity check against DO ----
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

    // ---- sudden rise (signal-change analysis) ----
    constexpr int H = FLAME_RISE_WINDOW_MS / SENSOR_CYCLE_MS;
    float oldest = dev;
    for (int i = 0; i < H; i++) if (devHist_[i] < oldest) oldest = devHist_[i];
    const bool rapid = histN_ >= H && dev >= enter && (dev - oldest) >= FLAME_RISE_FRAC * enter;
    for (int i = H - 1; i > 0; i--) devHist_[i] = devHist_[i - 1];
    devHist_[0] = dev; if (histN_ < H) histN_++;
    if (rapid) { riseActive_ = true; riseUntil_ = now + FLAME_CORROB_HOLD_MS; }
    if (riseActive_ && (int32_t)(now - riseUntil_) >= 0) riseActive_ = false;
    const bool riseRecent = riseActive_;

    // ---- flicker: direction reversals of AO around its mean, swing >= h ----
    const float h = fmaxf(tune.flickerSigmas * noise_, FLAME_FLICKER_FRAC_PER_SIGMA * tune.flickerSigmas * (dev > 0 ? dev : 0));
    int rev = 0; int state = 0;
    for (int k = 0; k < count_; k++) {
      const float v = buf_[(idx_ + (N - count_) + k) % N] - mean;     // oldest -> newest
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

    // ---- corroboration ----
    const bool corroborated = sticky_ || (flickerSustained && (!tune.requireDo || doOn || doChatter));
    const bool weakEvidence = flickerSustained;     // flicker without rise/DO: visible (warning level) but never an emergency

    // ---- strength ----
    const float fullDelta = fmaxf(1.0f, fabsf(base_) * FLAME_FULL_FRACTION);
    int pctRaw = (int)lroundf(dev / fullDelta * 100.0f);
    if (pctRaw < 0) pctRaw = 0;
    if (pctRaw > 100) pctRaw = 100;

    // ---- confirmation (only added delay: FLAME_CONFIRM_MS) ----
    const bool strong = active_;
    if (strong && corroborated) confirmMs_ += dt; else if (!sticky_) confirmMs_ = 0;
    bool critical = sticky_ || confirmMs_ >= tune.confirmMs;
    if (critical && !sticky_) { sticky_ = true; o.events |= FE_CRITICAL_ON; }
    if (sticky_) {                         // stays critical while the flame signal is still there
      if (active_) belowMs_ = 0; else { belowMs_ += dt; if (belowMs_ >= FLAME_RELEASE_MS) { sticky_ = false; confirmMs_ = 0; critical = false; belowMs_ = 0; } }
    }

    // ---- baseline: follow slowly while quiet; absorb steady, uncorroborated changes; never absorb fire-like ones ----
    if (!active_ && !riseRecent && !sticky_ && !flickerSustained && sd < 3.0f * noise_ + 1.0f) base_ += FLAME_BASELINE_ALPHA * (mean - base_);
    if (active_ && !corroborated && !flickerSustained) {
      uncorrMs_ += dt;
      if (uncorrMs_ >= tune.rebaseMs) { base_ = mean; uncorrMs_ = 0; o.events |= FE_REBASED; active_ = false; }
    } else uncorrMs_ = 0;

    // ---- DO alone (AO quiet): WARNING at most ----
    int pct;
    if (!active_) pct = 0;                                  // DO alone / nothing: ignored while AO is usable
    else if (!corroborated && weakEvidence) pct = pctRaw < SIGNAL_MIN_PCT ? SIGNAL_MIN_PCT : (pctRaw < FLAME_CRIT_PCT ? pctRaw : FLAME_CRIT_PCT - 1);
    else if (!corroborated) pct = pctRaw < SIGNAL_MIN_PCT ? pctRaw : SIGNAL_MIN_PCT - 1;   // steady change: silent
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
  bool tempSeen, currentSeen, voltageSeen;   // was this sensor ever valid since boot (false = not connected)
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

  // One detector sample per protection-loop iteration: FLAME_OVERSAMPLE quick AO reads are averaged. The measured feed rate is kept
  // (flameRateHz) because the detector's windows are in samples, not seconds.
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
    if (r_.tempValid) seenT_ = true;
    r_.tempSeen = seenT_ || !AUTO_IGNORE_UNPLUGGED;

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
    if (r_.currentValid) seenC_ = true;
    r_.currentSeen = seenC_ || !AUTO_IGNORE_UNPLUGGED;

    // --- Voltage: ADC mV -> volts using module ratio and calibration factor ---
    float vMv = readAvgMv(PIN_VOLTAGE, ADC_SAMPLES_PER_CYCLE);
    bool vOk = vMv <= VOLT_ADC_MAX_MV;  // above = ADC saturated: input too high
    if (vOk) voltAvg_.push(vMv / 1000.0f * VOLT_MODULE_RATIO * VOLT_CAL_FACTOR);
    voltageFilter_.update(vOk);
    if (!voltageFilter_.valid()) voltAvg_.reset();
    r_.voltageV = voltAvg_.value();
    r_.voltageValid = HAS_VOLTAGE_SENSOR && voltageFilter_.valid() && voltAvg_.hasData();
    if (r_.voltageValid) seenV_ = true;
    r_.voltageSeen = seenV_ || !AUTO_IGNORE_UNPLUGGED;

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
    int fp = flameDo ? 100 : 0;                        // digital output only (used while AO is unusable)
    bool flameCrit = flameDo;
    if (HAS_FLAME_ANALOG) {                            // AO is primary, DO supports it (flame_detect.h)
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

    // --- Water level (DEMO ONLY, water, never oil) ---
    float lv = readAvgMv(PIN_LEVEL, ADC_SAMPLES_PER_CYCLE);
    levelAvg_.push(lv);
    float span = (float)(LEVEL_FULL_MV - LEVEL_DRY_MV);
    float pct = (levelAvg_.value() - LEVEL_DRY_MV) / span * 100.0f;
    if (pct < 0) pct = 0;
    if (pct > 100) pct = 100;
    r_.oilPct = (int)lroundf(pct);
    r_.oilValid = HAS_LEVEL_SENSOR && lv <= VOLT_ADC_MAX_MV;   // a pin pinned at the ADC rail is a wiring fault, not a level

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
    if (++flameStatN_ % 40 == 0)   // every 10 s: which mode is the flame sensor in?
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
    if (firstReading && t == 85.0f) return false;  // DS18B20 power-on reset value
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
    if (RELAY_USES_RELEASE) {
      pinMode(PIN_RELAY, INPUT);   // released = cutoff (the module's pull-up keeps the relay OFF; same as a dead ESP32)
    } else {
      // Latch the CUT level into the output register BEFORE the pin becomes an output, so the relay never
      // sees a pulse of the "energised" level during boot (relevant for active-low modules).
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
      if (allow && !relayAllowed_) { pinMode(PIN_RELAY, OUTPUT); digitalWrite(PIN_RELAY, LOW); }   // coil energised
      else if (!allow) pinMode(PIN_RELAY, INPUT);                                                  // released = cutoff
    } else {
      const int onLevel  = RELAY_ACTIVE_LOW ? LOW : HIGH;   // coil energized = supply allowed
      const int offLevel = RELAY_ACTIVE_LOW ? HIGH : LOW;
      digitalWrite(PIN_RELAY, allow ? onLevel : offLevel);
    }
    relayAllowed_ = allow;
  }
  bool relayAllowed() const { return relayAllowed_; }

  // True if the relay pin no longer reads back the level we commanded.
  bool relayMismatch() const {
    if (RELAY_USES_RELEASE) return relayAllowed_ && digitalRead(PIN_RELAY) != LOW;   // a released pin has no level to compare
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
  static constexpr bool RELAY_USES_RELEASE = RELAY_ACTIVE_LOW && RELAY_OPEN_DRAIN_OFF;
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

// ===== ble_link.h =====
// BLE link (read-only monitoring + saving the alert number). Needs the BLE library of the ESP32 core.
// The phone is a VIEWER: the only writable thing is the SMS number, and only with the device password (same as the Wi-Fi one).
// Relay, thresholds and protection are never reachable from BLE. Everything runs in the low-priority comm task.
#include <Arduino.h>

#if ENABLE_BLE
class BleLink : public BLEServerCallbacks, public BLECharacteristicCallbacks {
 public:
  void begin() {
    BLEDevice::init(BLE_DEVICE_NAME);
    server_ = BLEDevice::createServer();
    server_->setCallbacks(this);
    BLEService* svc = server_->createService(BLE_SERVICE_UUID);
    tx_ = svc->createCharacteristic(BLE_TX_UUID, BLECharacteristic::PROPERTY_NOTIFY);
    tx_->addDescriptor(new BLE2902());
    BLECharacteristic* rx = svc->createCharacteristic(BLE_RX_UUID, BLECharacteristic::PROPERTY_WRITE | BLECharacteristic::PROPERTY_WRITE_NR);
    rx->setCallbacks(this);
    svc->start();
    BLEAdvertising* adv = BLEDevice::getAdvertising();
    adv->addServiceUUID(BLE_SERVICE_UUID);
    adv->setScanResponse(true);
    BLEDevice::startAdvertising();
    started_ = true;
  }
  bool connected() const { return started_ && connected_; }

  // Sends "<prefix><text>\n" in 20-byte notifications (works with the default ATT MTU).
  void sendLine(const char* prefix, const char* text) {
    if (!connected()) return;
    char line[1100];
    const int n = snprintf(line, sizeof(line), "%s%s\n", prefix, text);
    if (n <= 0 || n >= (int)sizeof(line)) return;
    for (int off = 0; off < n && connected_; off += 20) {
      const int len = (n - off) < 20 ? (n - off) : 20;
      tx_->setValue((uint8_t*)(line + off), len);
      tx_->notify();
      delay(8);
    }
  }

  // Returns true once per received line (copied out, zero-terminated).
  bool takeCommand(char* out, size_t n) {
    if (!lineReady_) return false;
    strlcpy(out, rxLine_, n);
    lineReady_ = false;
    return true;
  }

  void onConnect(BLEServer*) override { connected_ = true; }
  void onDisconnect(BLEServer*) override { connected_ = false; BLEDevice::startAdvertising(); }
  void onWrite(BLECharacteristic* c) override {
    auto v = c->getValue();                       // std::string (core 2.x) or String (core 3.x)
    const size_t len = v.length();
    const char* d = v.c_str();
    for (size_t i = 0; i < len; i++) {
      const char ch = d[i];
      if (ch == '\n' || ch == '\r') {
        if (rxLen_ > 0 && !lineReady_) { rxLine_[rxLen_] = 0; lineReady_ = true; }
        rxLen_ = 0;
      } else if (rxLen_ < sizeof(rxLine_) - 1) {
        rxLine_[rxLen_++] = ch;
      } else {
        rxLen_ = 0;                               // too long: drop the line
      }
    }
  }

 private:
  BLEServer* server_ = nullptr;
  BLECharacteristic* tx_ = nullptr;
  volatile bool connected_ = false, started_ = false, lineReady_ = false;
  char rxLine_[96] = {};
  size_t rxLen_ = 0;
};
#endif

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

// Only the protection task touches these.
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

// 64-bit uptime: does not wrap after 49.7 days like millis().
static uint64_t uptimeMs() { return (uint64_t)(esp_timer_get_time() / 1000); }

// ---- JSON helpers: invalid sensors are sent as null, never as a fake number --
static void fmtFloat(char* out, size_t n, bool valid, float v, int decimals) {
  if (!valid || isnan(v) || isinf(v)) snprintf(out, n, "null");
  else snprintf(out, n, "%.*f", decimals, v);
}

// Builds into `out` (size n). Field names/types are the contract with the Android app: do not rename.
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
static void flameTuningLoad();
static void loadAlertPhone() {
  char stored[24] = "", norm[24] = "";
  prefs.begin("stsmn", false);
  flameTuningLoad();
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

// Shared by HTTP and BLE. Returns 1 = number saved, 0 = SMS switched off (empty), -1 = invalid. `masked` gets the masked number.
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

// POST /api/contact   phone=<number>   (form field).  Empty phone = switch SMS off. Cannot touch the relay or any threshold.
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
  char b[20];
  v.toCharArray(b, sizeof(b));
  int i = 0, digits = 0;
  bool neg = false;
  if (b[i] == '-' || b[i] == '+') { neg = (b[i] == '-'); i++; }
  float ip = 0, fp = 0, sc = 0.1f;
  while (b[i] >= '0' && b[i] <= '9') { ip = ip * 10.0f + (float)(b[i] - '0'); i++; digits++; }
  if (b[i] == '.') {
    i++;
    while (b[i] >= '0' && b[i] <= '9') { fp += (float)(b[i] - '0') * sc; sc *= 0.1f; i++; digits++; }
  }
  if (digits == 0 || b[i] != 0) return false;
  out = neg ? -(ip + fp) : (ip + fp);
  return isfinite(out);
}
static bool parseI(const String& v, long& out) {
  if (v.length() == 0 || v.length() > 9) return false;
  char b[12];
  v.toCharArray(b, sizeof(b));
  int i = 0, digits = 0;
  bool neg = false;
  if (b[i] == '-' || b[i] == '+') { neg = (b[i] == '-'); i++; }
  long n = 0;
  while (b[i] >= '0' && b[i] <= '9') { n = n * 10 + (b[i] - '0'); i++; digits++; }
  if (digits == 0 || b[i] != 0) return false;
  out = neg ? -n : n;
  return true;
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
  if (uri == "/api/status" || uri == "/api/health" || uri == "/api/contact" || uri == "/api/flame" || uri == "/api/reset" || uri == "/api/events") {     // known path, wrong method
    server.sendHeader("Allow", (uri == "/api/contact" || uri == "/api/reset") ? "POST" : (uri == "/api/flame" ? "GET, POST" : "GET"));
    sendJson(405, "{\"error\":\"method not allowed\"}");
  } else {
    sendJson(404, "{\"error\":\"not found\"}");
  }
}

// ---- One protection cycle (protection task only) -----------------------------
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

  // Supply allowed only when armed (boot check + stable valid sensors) and never in EMERGENCY.
  const bool allow = relayGate.update(now, lastRisk.status, lastRisk.sensorFault, bootHold);
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

  // Serial log makes the score explainable to judges (115200 baud).
  if ((logTick++ & 3) == 0 || lastRisk.status != Status::SAFE)
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
    sensors.fastFlameSample();                      // AO sampling for flicker analysis (non-blocking, averaged per mains period)
    if ((uint32_t)(now - lastCycle) >= SENSOR_CYCLE_MS) {
      lastCycle = now;
      protectionCycle(now);
    }
    actuators.applyStatus(lastRisk.status, alarmOn, now);
    esp_task_wdt_reset();
    vTaskDelay(pdMS_TO_TICKS(PROTECTION_LOOP_MS));
  }
}

#if ENABLE_BLE
static BleLink ble;
// Command from the phone over BLE: "PHONE <device password> <number or empty>". Replies "OK ..." / "ERR ..." on the notify stream.
static void handleBleCommand(const char* cmd) {
  char reply[64];
  if (strncmp(cmd, "PHONE ", 6) != 0) { ble.sendLine("ER ", "unknown command"); return; }
  const char* p = cmd + 6;
  const char* sp = strchr(p, ' ');
  const size_t pwLen = sp ? (size_t)(sp - p) : strlen(p);
  if (pwLen != strlen(WIFI_AP_PASSWORD) || strncmp(p, WIFI_AP_PASSWORD, pwLen) != 0) { ble.sendLine("ER ", "wrong password"); return; }
  const char* num = sp ? sp + 1 : "";
  char masked[24];
  const int r = applyContact(num, masked, sizeof(masked));
  if (r < 0) snprintf(reply, sizeof(reply), "invalid number");
  else if (r == 0) snprintf(reply, sizeof(reply), "sms off");
  else snprintf(reply, sizeof(reply), "saved %s", masked);
  ble.sendLine(r < 0 ? "ER " : "OK ", reply);
}

static void bleService(uint32_t now) {
  static uint32_t lastSt = 0, lastHe = 0;
  char cmd[96];
  if (ble.takeCommand(cmd, sizeof(cmd))) handleBleCommand(cmd);
  if (!ble.connected()) return;
  if ((uint32_t)(now - lastSt) >= BLE_STATUS_EVERY_MS) {
    lastSt = now;
    char snap[sizeof(statusJson)];
    xSemaphoreTake(stateMutex, portMAX_DELAY);
    strlcpy(snap, statusJson, sizeof(snap));
    xSemaphoreGive(stateMutex);
    if (snap[0]) ble.sendLine("ST ", snap);
  }
  if ((uint32_t)(now - lastHe) >= BLE_HEALTH_EVERY_MS) {
    lastHe = now;
    char hb[960];
    buildHealthJson(hb, sizeof(hb));
    ble.sendLine("HE ", hb);
  }
}
#endif

// GPS reader + SIM/SMS sender. Low priority; never touches sensors, relay or LEDs.
static void commTask(void*) {
  uint32_t lastGsmCheck = 0, lastSimTry = 0;
  bool simTried = false;
  for (;;) {
    const uint32_t now = millis();

#if ENABLE_BLE
    bleService(now);
#endif
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
  Serial.printf("Reset reason: %s (BROWNOUT = power dip: use a power bank / separate 5V supply)\n", resetReasonName());

  stateMutex = xSemaphoreCreateMutex();
  if (!stateMutex) { Serial.println("FATAL: mutex"); delay(500); ESP.restart(); }
  loadAlertPhone();             // SMS number saved earlier (from the app) or the one in secrets.h
  {
    const esp_reset_reason_t rr = esp_reset_reason();
    if (rr == ESP_RST_PANIC || rr == ESP_RST_INT_WDT || rr == ESP_RST_TASK_WDT || rr == ESP_RST_WDT || rr == ESP_RST_BROWNOUT) {
      bootHold = true; bootHoldUntil = millis() + BOOT_HOLD_MS;
    }
    if (prefs.getBool("trip", false)) { riskEngine.restoreLatched(millis()); prevStatus = Status::EMERGENCY; }
  }
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
  server.on("/api/flame", HTTP_ANY, handleFlame);
  server.on("/api/reset", HTTP_POST, handleReset);
  server.on("/api/events", HTTP_GET, handleEvents);
  server.onNotFound(handleNotFound);
  server.begin();

  Serial2.begin(GPS_BAUD, SERIAL_8N1, PIN_GPS_RX, PIN_GPS_TX);     // GPS module
  Serial.println(smsEnabled ? "SMS alerts: ON" : "SMS alerts: OFF (save a number in the app: Settings > Alert number)");
  Serial.printf("Sensors fitted: temp=%d current=%d voltage=%d level=%d (config.h HAS_*; unfitted = ignored, shown as null)\n",
    HAS_TEMP_SENSOR, HAS_CURRENT_SENSOR, HAS_VOLTAGE_SENSOR, HAS_LEVEL_SENSOR);
  Serial.printf("Relay: ACTIVE_LOW=%d. At boot (cutoff) the relay module's LED must be OFF; it clicks ON ~5 s later when all fitted sensors are valid. If it is the other way round, flip RELAY_ACTIVE_LOW in config.h.\n", (int)RELAY_ACTIVE_LOW);
#if ENABLE_BLE
  ble.begin();
  Serial.println("BLE: advertising as " BLE_DEVICE_NAME " (monitor over Bluetooth)");
#endif
  if (xTaskCreatePinnedToCore(commTask, "stsmn_comm", COMM_TASK_STACK, nullptr, 1, nullptr, 0) != pdPASS) { Serial.println("FATAL: comm task"); delay(500); ESP.restart(); }

  startWatchdog();
  if (xTaskCreatePinnedToCore(protectionTask, "stsmn_protect", PROTECTION_TASK_STACK, nullptr,
                              PROTECTION_TASK_PRIORITY, &protectionHandle, 1) != pdPASS) { Serial.println("FATAL: protection task"); delay(500); ESP.restart(); }
}

void loop() {
  if (tripNv >= 0) { const int v = tripNv; tripNv = -1; prefs.putBool("trip", v == 1); }
  server.handleClient();        // phone is optional; protection runs in its own task and never waits for this
  delay(2);
}
