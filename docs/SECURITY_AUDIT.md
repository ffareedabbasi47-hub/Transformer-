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
| 7 | Medium | Not verified | Dependencies: `ws` pinned to an exact version, Compose/AGP/OkHttp/osmdroid pinned, but no lockfile (registry unreachable in my sandbox) and no vulnerability scan was possible | CI now runs `npm audit --omit=dev --audit-level=high`; commit the generated `package-lock.json`; use Dependabot |
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
| Realtime server | `cd server && npm test` | 8/8 pass (used preinstalled `ws` 8.21.3; package pins 8.18.0, will resolve in CI) |
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
