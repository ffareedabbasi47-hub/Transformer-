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
#include "config.h"
#if __has_include("secrets.h")
#include "secrets.h"
#else
#error "Copy secrets.h.example to secrets.h and set your Wi-Fi password"
#endif
#include "util.h"
#include "risk_engine.h"
#include "protection_logic.h"
#include "sensors.h"
#include "actuators.h"
#include "gps_nmea.h"
#include "sms_logic.h"
#include "sim800.h"
#include <Preferences.h>
#ifndef ALERT_PHONE_NUMBER
#define ALERT_PHONE_NUMBER ""   // old secrets.h without a phone number: SMS stays off
#endif

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
  char buf[960];
  snprintf(buf, sizeof(buf),
    "{\"deviceId\":\"%s\",\"fw\":\"%s\",\"uptime\":%llu,\"seq\":%lu,\"clients\":%d,"
    "\"mq2Ready\":%s,\"tempValid\":%s,\"currentValid\":%s,\"voltageValid\":%s,\"relayMismatch\":%s,"
    "\"armed\":%s,\"heapFree\":%lu,\"stackFreeBytes\":%lu,"
    "\"gpsFix\":%s,\"gpsSats\":%d,\"lat\":%s,\"lng\":%s,"
    "\"gsmReady\":%s,\"gsmRegistered\":%s,\"gsmSignal\":%d,\"smsEnabled\":%s,\"smsSent\":%lu,\"smsFailed\":%lu,"
    "\"mq2Mv\":%d,\"mq2BaseMv\":%d,\"alertPhone\":\"%s\",\"resetReason\":\"%s\"}",
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
    (int)rd.mq2Mv, (int)rd.mq2BaseMv, phoneMasked, resetReasonName());
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
  snprintf(buf, sizeof(buf), "{\"ok\":true,\"smsEnabled\":true,\"alertPhone\":\"%s\",\"resetReason\":\"%s\"}", masked);
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
  riskEngine.setFitted(rd.tempSeen, rd.currentSeen, rd.voltageSeen);
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
  // LED self-test (relay is still cut): green, yellow, red, buzzer-free. If one never lights, check its wiring/resistor/polarity.
  digitalWrite(PIN_LED_GREEN, HIGH); delay(400); digitalWrite(PIN_LED_GREEN, LOW);
  digitalWrite(PIN_LED_YELLOW, HIGH); delay(400); digitalWrite(PIN_LED_YELLOW, LOW);
  digitalWrite(PIN_LED_RED, HIGH); delay(400); digitalWrite(PIN_LED_RED, LOW);
  Serial.println("\nSTSMN firmware " STSMN_FW_VERSION);
  Serial.printf("Reset reason: %s (BROWNOUT = power dip: use a power bank / separate 5V supply)\n", resetReasonName());

  stateMutex = xSemaphoreCreateMutex();
  loadAlertPhone();             // SMS number saved earlier (from the app) or the one in secrets.h
  sensors.begin();              // includes ACS712 zero calibration (relay is in cutoff = no load current)

  // First state is computed from real readings before anyone can request it.
  lastCycle = millis();
  protectionCycle(lastCycle);

  WiFi.mode(WIFI_AP);
  WiFi.setTxPower(WIFI_POWER_11dBm);   // lower radio current peaks: a phone's OTG port cannot supply much

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
