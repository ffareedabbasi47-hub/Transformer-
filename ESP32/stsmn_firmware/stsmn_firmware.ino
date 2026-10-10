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
#include "config.h"
#if __has_include("secrets.h")
#include "secrets.h"
#else
#error "Copy secrets.h.example to secrets.h and set your Wi-Fi password"
#endif
#include "util.h"
#include "risk_engine.h"
#include "sensors.h"
#include "actuators.h"

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
