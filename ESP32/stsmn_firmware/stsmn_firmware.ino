// =============================================================================
// STSMN - Smart Transformer Safety & Monitoring Network  |  ESP32 firmware v1.1
//
// The ESP32 is the protection controller. Sensing, risk calculation, relay
// cutoff, LEDs and buzzer run in their OWN FreeRTOS task and never depend on
// the phone or on the HTTP server (a stalled client cannot delay protection).
// The phone only reads GET /api/status and GET /api/health.
//
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

// ---- Compile-time check of the Wi-Fi settings (a known default password must never ship) ------------------
constexpr size_t cstrLen(const char* s, size_t i = 0) { return s[i] ? cstrLen(s, i + 1) : i; }
constexpr bool cstrEq(const char* a, const char* b, size_t i = 0) {
  return a[i] == b[i] && (a[i] == 0 || cstrEq(a, b, i + 1));
}
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

// State shared between the protection task (writer) and the HTTP handlers (readers).
// Guarded by stateMutex; the mutex is NEVER held while sending data over the network.
static SemaphoreHandle_t stateMutex = nullptr;
static char statusJson[384] = "";
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
static void buildStatusJson(char* out, size_t n, const SensorReadings& s, const RiskResult& r,
                            bool relay, bool alarm, uint32_t seqNo) {
  char t[16], v[16], c[16], oil[16];
  fmtFloat(t, sizeof(t), s.tempValid, s.tempC, 1);
  fmtFloat(v, sizeof(v), s.voltageValid, s.voltageV, 2);
  fmtFloat(c, sizeof(c), s.currentValid, s.currentA, 2);
  if (s.oilValid) snprintf(oil, sizeof(oil), "%d", s.oilPct); else snprintf(oil, sizeof(oil), "null");
  const int written = snprintf(out, n,
    "{\"deviceId\":\"%s\",\"fw\":\"%s\",\"uptime\":%llu,\"temperature\":%s,\"voltage\":%s,"
    "\"current\":%s,\"smoke\":%s,\"flame\":%s,\"oilLevel\":%s,\"risk\":%d,"
    "\"status\":\"%s\",\"relay\":%s,\"alarm\":%s,\"seq\":%lu}",
    STSMN_DEVICE_ID, STSMN_FW_VERSION, (unsigned long long)uptimeMs(), t, v, c,
    s.smoke ? "true" : "false", s.flame ? "true" : "false", oil, r.risk,
    statusName(r.status), relay ? "true" : "false", alarm ? "true" : "false",
    (unsigned long)seqNo);
  // Never serve a half-written JSON: on overflow the app gets an explicit invalid packet (-> STALE), not a wrong value.
  if (written < 0 || written >= (int)n) snprintf(out, n, "{\"error\":\"status buffer overflow\"}");
}

// ---- HTTP handlers (read-only; no control endpoints) -------------------------
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
  SensorReadings rd; bool armed; uint32_t sq;
  xSemaphoreTake(stateMutex, portMAX_DELAY);
  rd = lastReadings; armed = relayArmed; sq = seq;
  xSemaphoreGive(stateMutex);
  char buf[384];
  snprintf(buf, sizeof(buf),
    "{\"deviceId\":\"%s\",\"fw\":\"%s\",\"uptime\":%llu,\"seq\":%lu,\"clients\":%d,"
    "\"mq2Ready\":%s,\"tempValid\":%s,\"currentValid\":%s,\"voltageValid\":%s,\"relayMismatch\":%s,"
    "\"armed\":%s,\"heapFree\":%lu,\"stackFreeBytes\":%lu}",
    STSMN_DEVICE_ID, STSMN_FW_VERSION, (unsigned long long)uptimeMs(), (unsigned long)sq,
    WiFi.softAPgetStationNum(),
    rd.mq2Ready ? "true" : "false", rd.tempValid ? "true" : "false",
    rd.currentValid ? "true" : "false", rd.voltageValid ? "true" : "false",
    actuators.relayMismatch() ? "true" : "false", armed ? "true" : "false",
    (unsigned long)ESP.getFreeHeap(),
    (unsigned long)(protectionHandle ? uxTaskGetStackHighWaterMark(protectionHandle) * sizeof(StackType_t) : 0));
  sendJson(200, buf);
}

static void handleNotFound() {
  const String uri = server.uri();
  if (uri == "/api/status" || uri == "/api/health") {     // known path, wrong method
    server.sendHeader("Allow", "GET");
    sendJson(405, "{\"error\":\"method not allowed\"}");
  } else {
    sendJson(404, "{\"error\":\"not found\"}");
  }
}

// ---- One protection cycle (protection task only) -----------------------------
static void protectionCycle(uint32_t now) {
  SensorReadings rd = sensors.sample(now);

  RiskInput in{rd.tempValid, rd.tempC, rd.currentValid, rd.currentA, rd.voltageValid, rd.voltageV,
               rd.smoke, rd.flame, rd.oilValid, rd.oilPct};
  lastRisk = riskEngine.update(in, now);

  // Supply allowed only when armed (boot check + stable valid sensors) and never in EMERGENCY.
  const bool allow = relayGate.update(now, lastRisk.status, lastRisk.sensorFault);
  actuators.setRelayAllowed(allow);
  alarmOn = (lastRisk.status == Status::EMERGENCY);

  char json[sizeof(statusJson)];
  const uint32_t nextSeq = seq + 1;
  buildStatusJson(json, sizeof(json), rd, lastRisk, actuators.relayAllowed(), alarmOn, nextSeq);

  xSemaphoreTake(stateMutex, portMAX_DELAY);
  seq = nextSeq;
  lastReadings = rd;
  relayArmed = relayGate.armed();
  strlcpy(statusJson, json, sizeof(statusJson));
  xSemaphoreGive(stateMutex);

  // Serial log makes the score explainable to judges (115200 baud).
  Serial.printf("seq=%lu %s risk=%d [T%d I%d V%d S%d F%d O%d] relay=%d armed=%d crit=%d fault=%d\n",
    (unsigned long)nextSeq, statusName(lastRisk.status), lastRisk.risk,
    lastRisk.parts.temp, lastRisk.parts.current, lastRisk.parts.voltage,
    lastRisk.parts.smoke, lastRisk.parts.flame, lastRisk.parts.oil,
    actuators.relayAllowed(), relayGate.armed(), lastRisk.critical, lastRisk.sensorFault);
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
  server.onNotFound(handleNotFound);
  server.begin();

  startWatchdog();
  xTaskCreatePinnedToCore(protectionTask, "stsmn_protect", PROTECTION_TASK_STACK, nullptr,
                          PROTECTION_TASK_PRIORITY, &protectionHandle, 1);
}

void loop() {
  server.handleClient();        // phone is optional; protection runs in its own task and never waits for this
  delay(2);
}
