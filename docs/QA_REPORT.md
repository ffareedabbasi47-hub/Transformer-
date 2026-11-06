# STSMN ESP32 firmware: QA report (v1.1)

Scope: firmware under `ESP32/stsmn_firmware` (+ generated single-file copy). Low-voltage demonstration only; not certified protection equipment.
Original files are in git history (commit before this change) and a backup copy was taken before editing.

## Build verification (read this first)
| Check | Result |
|---|---|
| Real ESP32 build (Arduino IDE / arduino-cli with ESP32 core 2.x or 3.x) | **NOT RUN**: no ESP32 toolchain in the environment. The firmware is **not verified to compile for the ESP32.** |
| Sketch syntax/type check against HOST MOCKS (`tests/firmware/compile_sketch.sh`, g++ gnu++11/17/2a, mock core 2 and 3) | PASS. This checks C++ syntax, declarations and our own API usage only; the mocks are not the real core. |
| Single-file sketch regenerated and syntax-checked the same way | PASS |
| `tests/risk_engine_test.cpp` (risk engine, 18 checks) | PASS |
| `tests/protection_logic_test.cpp` (relay arming gate, 12 checks) | PASS |
| `tests/firmware/sensor_actuator_test.cpp` (real `sensors.h`/`actuators.h` on a mock Arduino, 25 checks) | PASS |
| Regression value: new tests run against the ORIGINAL headers | 3 sensor/actuator checks + 1 risk check FAIL there, i.e. the tests detect the bugs fixed below |
| Real hardware (sensors, relay, Wi-Fi, watchdog, timing, heap/stack) | **HARDWARE NOT VERIFIED** |

## Bugs found and fixed
### Critical
1. **Protection could be stalled by the HTTP server.** `server.handleClient()` ran in the same loop as sensing and relay logic; a client that connects and sends nothing blocks the Arduino `WebServer` for seconds (library data-wait timeout), during which an EMERGENCY would not cut the relay.
   Fix: protection (sensors, risk, relay, LEDs, buzzer) moved to its own FreeRTOS task (priority 3, core 1) with a task watchdog; HTTP stays in `loop()`. Shared state is copied under a mutex and the mutex is never held while sending. *Evidence: syntax check + review. Timing on hardware NOT VERIFIED.*
2. **Relay could be energised with invalid sensors.** Original: relay allowed 3 s after boot regardless of sensor validity (a missing DS18B20 gave only WARNING and the supply stayed on).
   Fix: new `RelayGate` (`protection_logic.h`): supply only after boot check AND temperature+current+voltage valid for 2 s; sustained sensor fault (10 s) cuts; EMERGENCY cuts; re-arming needs a stable valid period. *Evidence: `protection_logic_test.cpp` PASS (boot with invalid sensors never allows for 120 s; rollover safe).*
### Major
3. **Boot pulse on the relay pin.** `pinMode(OUTPUT)` came before the cut level was written, which can pulse an active-low relay on. Fix: latch the cut level first. *Evidence: `sensor_actuator_test` (fails on original).*
4. **Floating flame input** gave random false EMERGENCY / unplugged module undetectable. Fix: internal pull towards "no flame". Limitation: a disconnected flame module is still undetectable (digital output only). *Evidence: test fails on original.*
5. **Smoke hidden by a dead temperature sensor.** The smoke+heat critical rule required a valid temperature. Fix: smoke with unknown temperature counts as smoke with heat. *Evidence: `risk_engine_test` fails on original.*
6. **Stale averages after recovery.** A recovered temperature/current/voltage mixed pre-fault values into the new reading. Fix: averages reset while invalid. *Evidence: `sensor_actuator_test` (fails on original).*
7. **Public default Wi-Fi password could ship.** Fix: `static_assert` refuses the example password and lengths outside 8-63 (checked on host; message shown at compile time).
### Minor
8. `uptime` used 32-bit `millis()` (wraps after 49.7 days; the app treats a decrease as a reboot) -> 64-bit `esp_timer`.
9. Wrong method on `/api/status|health` returned 404 -> now 405 + `Allow: GET`; empty state returns 503 instead of an empty 200.
10. `WiFi.softAP()` result was ignored -> logged; protection continues without Wi-Fi.
11. `/api/health` extended with `armed`, `heapFree`, `stackFreeBytes` (additive; the app ignores unknown keys; `/api/status` keys unchanged).

## Reviewed and left as is (with reason)
* ADC: ADC1 pins only (32-35); 12-bit, 11 dB, `analogReadMilliVolts` (factory-calibrated) with 16-sample oversampling. ADC2 pins (4, 25, 26, 27) are digital only.
* DS18B20: 10-bit resolution (about 188 ms) with 200 ms non-blocking wait; -127 and 85.0 (first read) rejected; range -55..125.
* Risk engine: score clamped 0..100, hysteresis (40/30 and 80/60), critical hold timers, 10 s recovery, floors (45 sensor fault, 90 critical); `millis()` rollover handled with unsigned subtraction (tested).
* JSON: built with `snprintf` into a fixed buffer with overflow detection; invalid values are `null`.

## Remaining hardware assumptions / limitations
* All thresholds, ACS712 sensitivity/divider, voltage ratio, level calibration, polarity flags are **demo assumptions** (see `ESP32/README.md` checklist).
* ACS712 zero is measured once at boot (no auto-recalibration); an unplugged ACS712 is detected only through the zero-level plausibility check and the 0.2-3.0 V window.
* A voltage module with its input unplugged reads about 0 V, which is a valid reading for a dead supply and scores as a large deviation (risk), not as SAFE. A disconnected water-level sensor cannot be told apart from "dry".
* MQ-2 is a relative demo indicator (baseline after 60 s); needs burn-in; not a calibrated smoke detector.
* The relay pin is high-impedance during an ESP32 reset: use COM+NO wiring and a module that idles off.
* The API is read-only plain HTTP on a WPA2 access point; there is no application-level authentication (anyone who knows the Wi-Fi password can read status). Rooted phones / extracted credentials / Wi-Fi deauthentication denial of service are not mitigated.
* GPIO readback (`relayMismatch`) only proves the pin level, not that the relay contacts moved.

## Changed files
| File | Why |
|---|---|
| `ESP32/stsmn_firmware/stsmn_firmware.ino` | task split, watchdog, mutex, arming gate, password asserts, 405/503, 64-bit uptime, softAP check |
| `ESP32/stsmn_firmware/protection_logic.h` (new) | testable relay arming/cut-off logic |
| `ESP32/stsmn_firmware/config.h` | new timing/arming/task constants |
| `ESP32/stsmn_firmware/actuators.h` | no boot pulse on the relay pin |
| `ESP32/stsmn_firmware/sensors.h` | flame pull, reset averages while invalid |
| `ESP32/stsmn_firmware/risk_engine.h` | smoke + unknown temperature = critical |
| `ESP32/stsmn_single/stsmn_single.ino`, `tools/make_single_ino.py` | one-file copy-paste sketch, generated |
| `ESP32/README.md` | build, pin map, calibration checklist, API |
| `tests/risk_engine_test.cpp`, `tests/protection_logic_test.cpp`, `tests/firmware/*` | new regression tests and host mocks |
| `.github/workflows/android.yml` | CI runs the new firmware tests, the mock syntax check and the single-file freshness check |
