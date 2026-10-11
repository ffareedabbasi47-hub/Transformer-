# STSMN security audit (v1.2.0)

Method: read every source file (ESP32 firmware, Android app, realtime server, CI workflow, docs), grep for dangerous APIs, ran the tests
that can run in the sandbox. **Not** a penetration test; no real hardware, no phone, no Android SDK was available (see "Not verified").
Secret values are never written in this file.

## Findings

| # | Severity | Status | Finding (evidence) | Fix / action |
|---|---|---|---|---|
| 1 | High | Fixed in docs, **you must act** | The old Wi-Fi password was committed in an earlier version and this document used to print it. It still exists in Git history | Removed from this file. **Treat that password as burned: never reuse it.** Rewriting history is your decision (`git filter-repo`), it needs a force-push |
| 2 | High | Fixed (docs + delivery) | README "Option B" tells you to upload `STSMN.zip`. A zip made from a working folder contains the git-ignored `ESP32/stsmn_firmware/secrets.h` (it was inside the zip you sent) | README now says: delete `secrets.h` before zipping/uploading. The zip delivered with this release does not contain it |
| 3 | Medium | Open (needs your OK) | `res/xml/network_security_config.xml`: `base-config cleartextTrafficPermitted="true"` allows plain HTTP to ANY host (needed for the ESP32, which has no TLS) | Compensating controls in code: ESP32 host must be a private IPv4 (`SettingsStore.isValidHost`, unit-tested), cloud URL must be `wss://` (plain `ws://` only for private LAN, `CloudRules`, unit-tested). Tightening to `192.168.4.1` only would break users who change the address, so I did not do it without asking |
| 4 | Medium | Fixed (compile/run unverified) | Cloud viewer key and device token were stored in plaintext SharedPreferences | `KeystoreCodec` (AES-256-GCM, Android Keystore key). Plain copy is erased; old plain values are migrated once; if encryption fails the secret is **not** stored (no plaintext fallback). `allowBackup=false` already prevents backup extraction |
| 5 | Medium | Fixed + tested | Server read `devices.json` only at start: a revoked token kept working until restart, new devices needed a restart | `Registry.reload()` on every sweep (2 s), revoked publishers are disconnected (test: "device added or revoked ... without restart"). Added `scripts/revoke-device.js` (revoke / `--rotate`) |
| 6 | Medium | Design limit | One shared viewer key: everyone who has it sees every transformer. No per-user accounts or expiry | Rotate by changing `STSMN_VIEWER_KEY` and restarting. Next step: per-user keys/roles |
| 7 | High | **Fixed** (found by the CI `npm audit` on first run) | `server/package.json` pinned `ws` 8.18.0, which is in the affected range 8.0.0-8.20.1 of two advisories (GHSA-58qx-3vcg-4xpx uninitialized memory disclosure, GHSA-96hv-2xvq-fx4p memory-exhaustion DoS). Evidence: failed GitHub Actions job "Realtime server tests" | Pinned to `ws` 8.21.3 (outside the affected range; the 8 server tests were run on exactly this version). `npm audit --omit=dev --audit-level=high` stays a blocking CI step. Commit `package-lock.json` once generated; other dependencies (Compose, AGP, OkHttp, osmdroid) were not scanned |
| 8 | Low | Fixed | ESP32 `buildStatusJson` could truncate silently if fields grew | Overflow now serves an explicit error JSON; the app rejects it and goes STALE instead of showing a half value |
| 9 | Low | Accepted | ESP32 `/api/status` and `/api/health` are unauthenticated, plain HTTP. Read-only, no control endpoint, protected by the WPA2 access point (max 3 clients) | Keep it read-only. If a control endpoint is ever added it MUST have a token |
| 10 | Low | Accepted | GitHub Actions pinned by major tag, not commit SHA; the CI artifact is a debug (debuggable) APK | Pin SHAs for a public repo; use the signed release APK (secrets in README) for the exhibition |
| 11 | Info | Checked | No SQL, no shell/process execution, no `eval`, no WebView, no HTML rendering, no file upload, no cookies/sessions (so no CSRF/XSS/CORS surface). grep for `exec/eval/innerHTML/WebView/rawQuery` = 0 hits | – |

## Realtime server controls (new in v1.2.0, `server/`)
Verified by `server/test/server.test.js` (8 tests, all pass in this sandbox):
- Auth is the first message (5 s timeout). Device tokens are random 192-bit, stored only as SHA-256 hashes, compared in constant time.
- A device can publish **only its own ID** (token is bound to the ID). Viewers can never write.
- Strict validation of every field and range, unknown fields dropped, 4 KB message limit, text frames only.
- Rate limits: 1 telemetry / 400 ms per device, 20 connections per IP, IP blocked 60 s after 5 failed logins, same generic error for every auth failure.
- No debug or test routes. Only `GET /healthz`. Errors are generic, no stack traces. Server refuses to start without a 16+ char `STSMN_VIEWER_KEY` (environment variable, never in code).
- Stale logic runs on the **server**: no data for 15 s = STALE, 60 s = OFFLINE, so a dead phone can never leave a green marker.
- TLS is not done in Node: run it behind a TLS proxy (Render, Fly, Caddy, nginx). The app refuses plain `ws://` to public hosts.

## Tests and results (actual)
| What | Command | Result |
|---|---|---|
| Realtime server | `cd server && npm test` | 8/8 pass on `ws` 8.21.3 (same version now pinned) |
| Kotlin domain + settings validation | kotlinc 2.0.21 + JUnit 4.13.2, test-only stand-ins for `org.json`/Android `Context` | 41/41 pass |
| ESP32 risk engine (host) | `g++ -std=c++17 tests/risk_engine_test.cpp` | ALL PASSED |
| CI workflow YAML | parsed with PyYAML | valid, 4 jobs |

## Not verified (be honest with the teacher)
- **Android app was never compiled in this sandbox** (no Android SDK / Gradle network). Compose screens, `KeystoreCodec`, `RealtimeLink`, notifications, map: reviewed by reading + import scan only. The first GitHub Actions run is the real compile check; if it fails, send me the log.
- No ESP32 compile, no physical hardware test. A GPIO write/readback does not prove the relay contacts opened; use an independent hardware cutoff for any real load.
- No load/penetration test of the server; no TLS test.

## Remaining risks / next steps (priority)
1. Rotate the Wi-Fi password (#1) and decide about history rewrite.
2. Run the GitHub build, install the APK, run `tests/TEST_PLAN.md` with the ESP32 on low voltage.
3. Deploy the server behind TLS (`docs/REALTIME.md`), commit `package-lock.json`.
4. Decide on #3 (restrict cleartext to the ESP32 address).
5. Per-user viewer accounts, token expiry, audit log.
6. Independent hardware protection; this is a demo, not a certified transformer protection system.

## Addendum: 3D globe and Street View (WebView)

| Item | Decision |
|---|---|
| WebView loads only the bundled page via `WebViewAssetLoader` (`https://appassets.androidplatform.net/assets/globe/`) | `file://` and content access disabled; every navigation away from the page is blocked |
| Page CSP | `script-src 'self'` (no inline script, no eval), tiles over https only |
| Data into the page | Built with `org.json` from validated numbers and IDs matching `^[A-Z0-9][A-Z0-9-]{1,23}$`; markers are created with `textContent`, never `innerHTML` |
| JS to app bridge | Two methods only (`onSelect`, `onReady`); the ID is re-validated in Kotlin |
| Street View | Intent to the Google Maps app / https link with validated coordinates; no API key stored |
| Third-party tiles | Esri World Imagery, OpenStreetMap, AWS terrain: device positions are NOT sent to them, only tile requests |

Verified: the page was run in headless Chromium (markers drawn, hostile ID rejected, no JS errors). Not verified: the Kotlin side (compiled only by CI) and a real phone WebView with real tiles. Check Esri's and OSM's tile usage terms before heavy or public use.

### Addendum 2: 3D globe hardening (2026-10-11)

| Item | Result |
|---|---|
| JS bridge | Exactly 4 methods (`onSelect`, `onReady`, `onFatal`, `onNotice`); every string is length-limited and validated; `alive` flag drops callbacks after the screen is disposed |
| Error classification | Fatal (WebGL missing, map cannot start, style load error, 20 s start timeout, renderer crash) is separated from notices (tiles, terrain, stray script error). A started globe is never destroyed by a notice |
| Renderer crash | `onRenderProcessGone` shows a fallback card (Retry / Flat map) instead of Android killing the app |
| Retry | A new WebView is built (`key(attempt)`); callbacks of old pages are ignored; no automatic retry loops |
| Network | The app does NOT bind its process to the ESP32 Wi-Fi (`WifiNetworkBinder` only opens ESP32 connections through that network), so WebView tiles use Android's default internet route. Not verified on a physical phone |
| Diagnostics | Logcat tag `StsmnGlobe`; tap the credit line on the globe to show the developer line (tiles ok/failed, JS errors, network). Hidden by default |
| Still open | HTTP cleartext is not yet restricted to 192.168.4.1 (needs your decision: breaking if the ESP32 IP changes). Satellite tiles need internet; no offline satellite cache exists |
