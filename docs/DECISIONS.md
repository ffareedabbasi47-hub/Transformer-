# Decisions, assumptions and notes on the PDF blueprint

## Followed exactly
ESP32 ↔ Android over local Wi-Fi (SoftAP), `GET /api/status`, the 14-field JSON contract (no field renamed),
SAFE/WARNING/EMERGENCY, risk caps 25/25/15/15/20/10, ESP32-side cutoff, stale-data rule, bounded local history,
IDLE/CONNECTING/LIVE/STALE/ERROR, no cloud/Firebase/Bluetooth/USB, deterministic risk score (no fake AI).

## Not specified in the PDF → proposed option (please confirm)
| Item | Choice | Where to change |
|---|---|---|
| Invalid sensor in JSON | `null` for temperature/voltage/current/oilLevel (field still present) | firmware `buildStatusJson`, app `StatusParser` |
| `uptime` unit | milliseconds (`millis()`) | – |
| Pins | see `config.h` | `config.h` |
| Wi-Fi SSID/password | `secrets.h` (git-ignored, template `secrets.h.example`) | `secrets.h` |
| Thresholds (40/70 °C, 2/4.5 A, nominal 3.7 V …) | demo values, **must be calibrated** | `config.h` |
| WARNING / EMERGENCY boundaries | 40 / 80 with hysteresis 30 / 60 (PDF examples 18 / 55 / 90+ all map correctly) | `config.h` |
| Critical conditions | flame, temp ≥ 70 °C for 3 s, current ≥ 4.5 A for 1.5 s, smoke + temp ≥ 40 °C for 2 s | `config.h` |
| Recovery from EMERGENCY | 10 s stable, then automatic | `RECOVERY_MS` |
| Dead protection sensor | status at least WARNING (risk floor 45) | `FLOOR_SENSOR_FAULT_RISK` |
| User tab | local profile only (no login) | – |
| Diagnostics | section inside Settings (5 tabs instead of 6 screens) | – |
| Alerts | "Active alerts" at top of History | – |
| ERROR → CONNECTING | app stays in ERROR while retrying with backoff (avoids flicker) | `StsmnRepository` |
| History/settings storage | one JSON file + SharedPreferences (zero extra dependencies) | – |
| Out-of-range sensor value | shown as "unavailable" instead of clamped | `StatusParser` |
| Power from 3.7 V battery | not designed; use USB/power bank | – |
| Live Map tab (v1.1) | 6 tabs; osmdroid map; map tiles need internet (conflicts with PDF "no internet"; core data does not) – see MAP_SETUP.md | `MapScreen.kt` |
| Polling (v1.1) | 3 s default, only while app visible | `AppConfig.kt` |

## Edge cases handled
Duplicate/stuck `seq` is not treated as fresh data; uptime reset = ESP32 restart event; packets > 4 KB rejected;
every request has timeouts; reconnect backoff 1→5 s; missing JSON fields never crash the app.

## Not included in v1 (PDF: optional/future)
GSM/SMS, GPS, cloud, protected manual relay test, `/api/health` parsing in the app (endpoint exists in firmware).
