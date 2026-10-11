# STSMN – Smart Transformer Safety & Monitoring Network

ESP32 (protection controller) → local Wi-Fi → Android app (dashboard).
**LOW-VOLTAGE DEMO ONLY – never connect to 230 V mains or a real transformer.**

```
STSMN/
├── Android/            Kotlin + Jetpack Compose app (tabs: Home, Fleet, Map, History, Settings; Sensor Data, Diagnostics, Profile inside)
├── server/             Node realtime server: many transformers, live status + location (docs/REALTIME.md)
├── ESP32/stsmn_firmware/   Arduino sketch (config.h = pins + thresholds, risk_engine.h = score)
├── docs/               DECISIONS.md (assumptions + PDF notes), MAP_SETUP.md (Live Map)
├── tests/              risk_engine_test.cpp (PC), TEST_PLAN.md (exhibition checklist)
└── .github/workflows/android.yml   builds the APK on GitHub
```

## 1. Build the APK on GitHub (phone-friendly)
**Option A – normal:** create a GitHub repo and upload the whole `STSMN` folder contents (Android/, ESP32/, docs/, tests/, .github/).
**Option B – only 2 files (easiest from a phone):**
> ⚠ Before making `STSMN.zip`, **delete `ESP32/stsmn_firmware/secrets.h`** from the folder. It is git-ignored for normal uploads, but a zip carries it into the repo.
1. Create a new repo. Upload `STSMN.zip` to the repo root (Add file → Upload files).
2. Add file → Create new file → name it `.github/workflows/android.yml` and paste the workflow file contents.
3. Open the **Actions** tab → "Build STSMN APK" → wait ~5–8 min → open the run → **Artifacts → STSMN-debug-apk** → download, extract, install the APK.
(Android asks to allow "install unknown apps" for your browser/file manager.)

Optional signed release APK: add repo secrets `STSMN_KEYSTORE_BASE64`, `STSMN_KEYSTORE_PASSWORD`, `STSMN_KEY_ALIAS`, `STSMN_KEY_PASSWORD`. Never commit a keystore.

## 2. Flash the ESP32
Arduino IDE → install board "esp32" and libraries **OneWire** + **DallasTemperature** → open `ESP32/stsmn_firmware/stsmn_firmware.ino` → board "ESP32 Dev Module" → upload. Serial Monitor at 115200 shows every score with its per-sensor points.
Before flashing, make sure `ESP32/stsmn_firmware/secrets.h` exists (copy `secrets.h.example` if not) and set your own Wi-Fi password there. It is git-ignored and never pushed.
Wi-Fi name: `STSMN-TR001`, address `192.168.4.1` (fixed in firmware via `softAPConfig`).

## 3. Wiring notes (verify on your modules!)
| Part | ESP32 pin | Important |
|---|---|---|
| DS18B20 | GPIO4 | 4.7 kΩ pull-up to 3.3 V |
| ACS712 5A OUT | GPIO34 | **10k/20k divider** (sensor outputs up to 5 V); sensor VCC = 5 V |
| Voltage module S | GPIO35 | Module ÷5. Keep demo input ≤ 15 V (3.7 V battery is fine) |
| MQ-2 AO | GPIO32 | **10k/20k divider**; warm-up 60 s |
| Water level | GPIO33 | 3.3 V, **water only** |
| Flame DO | GPIO25 | |
| Relay IN | GPIO26 | Load via **COM + NO** so power loss = cutoff. Check `RELAY_ACTIVE_LOW` |
| LEDs G/Y/R | 18 / 19 / 23 | 220 Ω each |
| Buzzer | GPIO27 | Active buzzer assumed |
Power the ESP32 from USB / power bank. At boot the relay stays OFF while the ACS712 zero offset is calibrated – keep the load disconnected at that moment.

## 4. Connect the phone
Join the `STSMN-TR001` Wi-Fi. If Android asks "keep connection? (no internet)", choose **Yes/Keep**. The app binds to that Wi-Fi network even when mobile data is on.

Not verified on real hardware yet – follow `tests/TEST_PLAN.md` and calibrate thresholds in `config.h` before the exhibition.

## Realtime fleet (v1.2.0)
See `docs/REALTIME.md`. Many transformers, each with its own ID/token, live on one map, emergency notifications.

## Live Map (v1.1.0)
One transformer on an Uttar Pradesh map. Setup, internet/tile note and test status: `docs/MAP_SETUP.md`.

## Security
See `docs/SECURITY_AUDIT.md` (checklist, what was fixed, what is still open).

## Map tab: 3 views
`3D Earth` (Option A: standalone offline-capable dark 3D Earth with transformer markers and live telemetry; remote satellite/street imagery as optional enhancement), `Flat` (OpenStreetMap with local tile cache), and `All` (list of every transformer). Tap a transformer and use **Open Street View** to see the real street in the Google Maps app (no API key). MapLibre GL JS 5.7.1 (BSD-3) is bundled in `Android/app/src/main/assets/globe/`.
