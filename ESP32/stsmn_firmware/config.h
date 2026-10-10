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
