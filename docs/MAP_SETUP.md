# Live Map – setup, limits and test status (v1.1.0)

ONE transformer, ONE marker, Uttar Pradesh. No multi-transformer list.

## What was added
- **Map tab** (bottom bar: Home, Sensors, **Map**, History, User, Settings). osmdroid (OpenStreetMap), **no API key**.
- One marker labelled `STSMN-TR-001` (= "STSMN-" + `deviceId` from `/api/status`). Green SAFE, yellow WARNING, red EMERGENCY,
  grey STALE/OFFLINE – the status word is also written on the marker and in the legend.
- Tap the marker -> detail card: transformer ID, location source, coordinates, connection state, last update (clock + age),
  risk score, status, temperature, voltage, current, smoke, flame, oil level (demo), relay, alarm.
- **Center on Transformer** button, legend, "(c) OpenStreetMap contributors" attribution.
- States: loading map, map error (card still works), no coordinates, Wi-Fi not connected, ESP32 not responding / stale, no internet for tiles.
- **Settings -> Site location**: latitude / longitude / source (DEMO or Real site). Default is a DEMO point in Lucknow and is labelled
  "DEMO LOCATION" on the marker and banner. Entering the demo numbers as "Real site" is rejected. The phone's GPS is never used.
- Polling is now 1/2/**3**/5 s (default 3 s) and runs **only while the app is visible**. Stale timeout 5/10/**15** s choices (default 10 s,
  never less than 3x the poll interval).

## Needs your input (not in the PDF, not invented)
| Missing | What I did |
|---|---|
| Real installation coordinates | DEMO marker until you enter them in Settings -> Site location |
| ESP32 address | Firmware now fixes the access-point address with `WiFi.softAPConfig` to `192.168.4.1` (`WIFI_AP_IP` in `config.h`); the Serial Monitor prints the real one at boot. Change both if you want another address |
| GPS module | PDF lists NEO-6M as optional phase 2; the firmware has no GPS code and the JSON contract is unchanged, so GPS is not used |
| Internet for map tiles | **Conflicts with the PDF line "Internet dependency: none for exhibition core".** See below |

## Internet and the map (important)
Map *tiles* are downloaded from OpenStreetMap, so the map background needs internet. Everything else (marker, data, alerts, protection)
does not. Your phone can use **mobile data** for tiles while its Wi-Fi is connected to the ESP32 (the app sends the ESP32 requests over
Wi-Fi explicitly). For a no-internet exhibition: open the Map tab once at home on internet and zoom/pan over Uttar Pradesh and the
site; tiles are cached in app storage (up to 200 MB) and reused offline. Without tiles the map area is blank, but the marker, label and
detail card still work and a banner says so. OSM's tile policy allows light use like a school demo, not bulk downloading.

## Build
```
cd Android
gradle assembleDebug          # APK: app/build/outputs/apk/debug/app-debug.apk
gradle testDebugUnitTest      # unit tests
```
GitHub: the workflow `.github/workflows/android.yml` does this automatically (see README).

## Changed / new files
Changed: `Android/app/build.gradle.kts`, `AppConfig.kt`, `Model.kt`, `ConnectionTracker.kt`, `SettingsStore.kt`, `StsmnRepository.kt`,
`MainViewModel.kt`, `MainActivity.kt`, `StsmnApp.kt`, `SettingsScreen.kt`, `Components.kt`, `ESP32/stsmn_firmware/config.h`,
`ESP32/stsmn_firmware/stsmn_firmware.ino`, `README.md`, `docs/DECISIONS.md`.
New: `domain/SiteLocation.kt`, `domain/MarkerState.kt`, `ui/MapScreen.kt`, `ui/MarkerIcons.kt`, `src/test/.../MapLogicTest.kt`, `docs/MAP_SETUP.md`.

## Test status (honest)
| Test | Result |
|---|---|
| ESP32 risk engine on PC (`tests/risk_engine_test.cpp`) | **Ran: ALL PASSED** (re-run after this change) |
| ESP32 firmware syntax check against stub Arduino headers | **Ran: no errors** (not run on a real ESP32) |
| Kotlin unit tests (parser, tracker, events, `MapLogicTest`: marker states, invalid coordinates, API failure / Wi-Fi loss -> STALE/OFFLINE) | **Written, NOT run** – no Kotlin compiler in my sandbox. They run in the GitHub "Unit tests" job |
| Android compile, map rendering, tapping the marker, tile loading, real Wi-Fi loss | **NOT verified** – needs the first GitHub build and a phone test |
