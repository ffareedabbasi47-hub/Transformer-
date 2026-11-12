// =============================================================================
// STSMN firmware configuration  -  the ONE place for pins, thresholds, timing.
// Everything marked "ASSUMPTION" is NOT specified in the architecture PDF and
// must be verified / calibrated on your own hardware before the exhibition.
// This file has no Arduino dependency so risk_engine.h can be tested on a PC.
// =============================================================================
#pragma once
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
