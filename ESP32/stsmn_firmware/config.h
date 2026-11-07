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
constexpr bool HAS_VOLTAGE_SENSOR = true;
constexpr bool HAS_LEVEL_SENSOR   = true;

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
constexpr int FLOOR_SMOKE_RISK        = 55;  // smoke at all = at least WARNING (never silently "SAFE")

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
