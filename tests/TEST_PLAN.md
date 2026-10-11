# Exhibition test plan (PDF section 23) – run each test at least 3 times
Host-side automated tests: `g++ -std=c++17 -I../ESP32/stsmn_firmware risk_engine_test.cpp -o risk_test && ./risk_test`
Android unit tests (GitHub Actions job "Unit tests"): parser, connection state machine, event detector.

| # | Test | How | Pass condition | 1 | 2 | 3 |
|---|---|---|---|---|---|---|
| 1 | Boot | Power ESP32 | No reboot loop; Serial shows seq increasing | | | |
| 2 | Wi-Fi | Join STSMN-TR001 from phone | Connects every time | | | |
| 3 | JSON | Open http://192.168.4.1/api/status in browser for 2 min | Always valid JSON | | | |
| 4 | Temperature | Warm sensor with warm water | App value tracks; unplug → "UNAVAILABLE", status ≥ WARNING | | | |
| 5 | Current | No load / small load | Zero-load ≈ 0.00 A; stable reading | | | |
| 6 | Voltage | Compare with multimeter | Within ~5 % (adjust VOLT_CAL_FACTOR) | | | |
| 7 | Smoke | After 60 s warm-up, safe gas source (e.g. lighter gas without flame) | Smoke DETECTED then clears | | | |
| 8 | Relay | Observe LED/lamp on COM+NO | ON in SAFE, OFF in EMERGENCY (check RELAY_ACTIVE_LOW) | | | |
| 9 | Emergency | Trigger flame input safely (no real flame) | Relay OFF, red LED + buzzer, app EMERGENCY + event | | | |
| 10 | Phone-off | Switch phone Wi-Fi off, trigger emergency | Relay still cuts | | | |
| 11 | Reconnect | Turn phone Wi-Fi off/on | App returns to LIVE automatically | | | |
| 12 | Stale | Unplug ESP32 | App shows STALE then ERROR, never "SAFE" live | | | |
| 13 | Restart | Reset ESP32 | App logs DEVICE_RESTART and recovers | | | |
| 14 | Recovery | Clear emergency | Relay returns only after ~10 s stable | | | |

## Supervised low-voltage bench checklist (v1.2.0 additions)
Only with a teacher present, USB/power-bank power, 3.7 V battery as the "line", **never mains**.
1. Server running, two devices created, app shows server "CLOUD LIVE".
2. Power ESP32: relay stays OFF during calibration, then ON when SAFE.
3. Unplug the phone's Wi-Fi from the ESP32: map marker turns STALE within ~15 s and OFFLINE within 60 s (never stays green).
4. Heat the DS18B20 gently / cover the flame sensor: WARNING then EMERGENCY, relay cuts off, notification appears on the second phone.
5. Stop the server: viewer phones show "Reconnecting…", markers STALE; restart server: they recover by themselves.
6. Wrong viewer key in Settings: banner "key rejected", no data shown.

## 3D globe tests
- `Android/app/src/test/.../GlobeTest.kt` (JVM, runs in CI): globe JSON validation, ID/coordinate rules, lifecycle state machine (start, timeout, crash, retry, notices, duplicate suppression).
- `tests/globe/globe_smoke.js` (headless Chromium, run manually): 23 checks on the real page (markers add/update/remove, hostile IDs, selection, Show all incl. identical/dateline, repeated style switching, stray error vs fatal, offline tiles, no WebGL). Command: `NODE_PATH=<playwright dir> CHROMIUM=<path> node tests/globe/globe_smoke.js`.
- NOT automated: a real phone's WebView/GPU, real tile servers, renderer-crash behaviour. Use the manual checklist in README.
