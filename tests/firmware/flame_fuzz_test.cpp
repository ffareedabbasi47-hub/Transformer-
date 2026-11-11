// Randomised end-to-end check (synthetic signals): FlameDetector -> RiskEngine -> RelayGate.
// g++ -std=c++17 -I../../ESP32/stsmn_firmware flame_fuzz_test.cpp
#include <cstdio>
#include <cstdlib>
#include <random>
#include <functional>
#include "flame_detect.h"
#include "risk_engine.h"
#include "protection_logic.h"

static std::mt19937 rng(12345);
static void seed(unsigned s) { rng.seed(s); srand(s); }
static float U(float a, float b) { return std::uniform_real_distribution<float>(a, b)(rng); }
static float G(float s) { return std::normal_distribution<float>(0, s)(rng); }

struct Result { bool everEmergency = false; int firstEmergencyMs = -1; int emergencyDrops = 0; bool relayOnDuringBurn = false; int relayOnAfterMs = 0; };

static Result run(int totalMs, int burnStart, int burnEnd, std::function<float(int)> ao, std::function<bool(int)> doFn, float noiseSd) {
  FlameDetector d; RiskEngine risk; RelayGate gate; Result r; int doCount = 0; bool wasEmergency = false;
  const uint32_t T0 = 20000;                      // boot self-check done
  for (int t = 0; t < totalMs; t += 10) {
    d.feed(ao(t) + G(noiseSd)); d.feedDo(doFn(t));
    if (t % SENSOR_CYCLE_MS) continue;
    doCount = doFn(t) ? doCount + 1 : 0;
    const uint32_t now = T0 + t;
    FlameOut o = d.evaluate(now, doCount >= FLAME_DEBOUNCE_CYCLES);
    RiskInput in{true, 40.0f, true, 1.0f, true, 230.0f, false, o.aoUsable ? o.critical : false, false, 10, 0, o.aoUsable ? o.pct : 0};
    RiskResult rr = risk.update(in, now);
    const bool allow = gate.update(now, rr.status, rr.sensorFault);
    const bool em = rr.status == Status::EMERGENCY;
    if (em && !r.everEmergency) { r.everEmergency = true; r.firstEmergencyMs = t; }
    if (burnEnd > 0 && t > burnStart + 3000 && t < burnEnd) { if (!em && wasEmergency) r.emergencyDrops++; if (allow && r.everEmergency) r.relayOnDuringBurn = true; }
    wasEmergency = em;
  }
  return r;
}

int main(int argc, char** argv) {
  if (argc > 1) seed((unsigned)atoi(argv[1]));
  int fails = 0, N = 300;
  int flameMiss = 0, flameDrop = 0, flameRelay = 0; double worstDelay = 0;
  for (int i = 0; i < N; i++) {                   // real-like flames
    const float amb = U(1500, 3100), D = U(200, 2000), depth = U(0.12f, 0.45f), noiseSd = U(2, 12);
    const float hz = U(4, 14), doDrop = U(0, 0.15f); const bool chatter = U(0, 1) < 0.4f; const int gapAt = (int)U(15000, 40000), gapLen = (int)U(2000, 6000);
    const bool decay = U(0, 1) < 0.5f;
    auto ao = [&](int t) { if (t < 10000 || t >= 70000) return amb;
      float k = decay ? 1.0f - 0.55f * (t - 10000) / 60000.0f : 1.0f; bool gap = t >= gapAt && t < gapAt + gapLen;
      float fl = gap ? 0.0f : depth * D * k * sinf(6.2831853f * hz * (t - 10000) / 1000.0f) + (gap ? 0 : G(depth * D * 0.15f));
      return amb - D * k - fl; };
    auto dof = [&](int t) { if (t < 10000 || t >= 70000) return false; if (chatter) return ((t / 30) % 3) != 0; return U(0, 1) > doDrop; };
    Result r = run(80000, 10000, 70000, ao, dof, noiseSd);
    if (!r.everEmergency || r.firstEmergencyMs - 10000 > 5000) { flameMiss++; if (flameMiss <= 4) printf("  miss: amb=%.0f D=%.0f depth=%.2f noise=%.1f hz=%.1f chatter=%d doDrop=%.2f first=%d\n", amb, D, depth, noiseSd, hz, chatter, doDrop, r.firstEmergencyMs); }
    else if (r.firstEmergencyMs - 10000 > worstDelay) worstDelay = r.firstEmergencyMs - 10000;
    if (r.emergencyDrops) { flameDrop++; if (flameDrop <= 4) printf("  drop: amb=%.0f D=%.0f depth=%.2f noise=%.1f hz=%.1f chatter=%d doDrop=%.2f decay=%d gap=%d/%d\n", amb, D, depth, noiseSd, hz, chatter, doDrop, decay, gapAt, gapLen); }
    if (r.relayOnDuringBurn) flameRelay++;
  }
  printf("flames: %d/%d missed(>3.5s), %d dropped while burning, %d relay-on while burning, slowest %.0f ms\n", flameMiss, N, flameDrop, flameRelay, worstDelay);
  fails += flameMiss + flameDrop + flameRelay;

  int lightFalse = 0;
  for (int i = 0; i < N; i++) {                   // sun / lamp / room light: steady or slowly changing, noisy, DO often on
    const float amb = U(1500, 3100), D = U(0, 2800), noiseSd = U(2, 40);
    const float beatHz = U(0.5f, 8), beatAmp = U(0, 0.04f) * D; const int mode = (int)U(0, 4);
    const bool slow = U(0, 1) < 0.3f, shadow = U(0, 1) < 0.3f;
    auto ao = [&](int t) { float k = t < 10000 ? 0.0f : (slow ? fminf(1.0f, (t - 10000) / 40000.0f) : 1.0f);
      float sh = (shadow && t > 50000 && t < 52000) ? 0.3f * D * k : 0.0f;
      return amb - D * k - sh - beatAmp * k * sinf(6.2831853f * beatHz * t / 1000.0f); };
    auto dof = [&](int t) { if (t < 10000) return false; switch (mode) { case 0: return false; case 1: return true; case 2: return ((t / 40) % 2) == 0; default: return U(0, 1) < 0.7f; } };
    Result r = run(130000, 0, 0, ao, dof, noiseSd);
    if (r.everEmergency) { lightFalse++; if (lightFalse <= 6) printf("  FALSE: amb=%.0f D=%.0f noise=%.1f beat=%.1fHz/%.0fmV mode=%d slow=%d shadow=%d at=%d\n", amb, D, noiseSd, beatHz, beatAmp, mode, slow, shadow, r.firstEmergencyMs); }
  }
  printf("light: %d/%d false emergencies\n", lightFalse, N);
  fails += lightFalse;
  printf(fails ? "FUZZ FAILURES: %d\n" : "FUZZ OK\n", fails);
  return fails ? 1 : 0;
}
