// Host test of flame_detect.h with SYNTHETIC signals. These are model signals I made up to exercise the logic;
// they are NOT measurements of a real sensor. Real behaviour must be verified on hardware (docs/FLAME_AO_CALIBRATION.md).
// g++ -std=c++17 -I../../ESP32/stsmn_firmware flame_detect_test.cpp
#include <cstdio>
#include <cstdlib>
#include <functional>
#include "flame_detect.h"

static int failures = 0;
#define CHECK(c, m) do { if (!(c)) { printf("FAIL: %s\n", m); failures++; } else printf("ok:   %s\n", m); } while (0)

static float noise() { return ((rand() % 2001) - 1000) / 1000.0f * 6.0f; }   // +-6 mV ADC noise

struct Run { bool everCritical = false, everWarn = false; int maxPct = 0; int critAtMs = -1; bool lastCritical = false; int lastPct = 0; bool usable = true; };

// ao(t_ms) -> mV, doFn(t_ms) -> comparator
static Run simulate(int totalMs, std::function<float(int)> ao, std::function<bool(int)> doFn, FlameDetector& d) {
  Run r; int doCount = 0;
  for (int t = 0; t < totalMs; t += 10) {
    d.feed(ao(t) + noise());
    if ((t % SENSOR_CYCLE_MS) == 0) {
      bool raw = doFn(t); doCount = raw ? doCount + 1 : 0;
      FlameOut o = d.evaluate((uint32_t)t + 1000, doCount >= FLAME_DEBOUNCE_CYCLES);
      r.usable = o.aoUsable; r.lastCritical = o.critical; r.lastPct = o.pct;
      if (o.aoUsable) {
        if (o.pct > r.maxPct) r.maxPct = o.pct;
        if (o.pct >= SIGNAL_MIN_PCT) r.everWarn = true;
        if (o.critical && !r.everCritical) { r.everCritical = true; r.critAtMs = t; }
      }
    }
  }
  return r;
}
static float flameWave(int t, int t0, float level, float depth, float hz) {   // drops from ambient with flicker
  if (t < t0) return 0;
  return level + depth * sinf(6.2831853f * hz * (t - t0) / 1000.0f) + (rand() % 100 - 50) / 50.0f * depth * 0.4f;
}
static const float AMB = 2800.0f;

int main() {
  srand(1);
  { FlameDetector d; Run r = simulate(30000, [](int) { return AMB; }, [](int) { return false; }, d);
    CHECK(r.usable && !r.everWarn && !r.everCritical, "quiet ambient: baseline learned, nothing triggers"); }
  { FlameDetector d; // slow sun: ambient falls 900 mV over 120 s
    Run r = simulate(150000, [](int t) { float f = t < 20000 ? 0 : (t > 140000 ? 1 : (t - 20000) / 120000.0f); return AMB - 900.0f * f; }, [](int) { return false; }, d);
    CHECK(r.usable && !r.everCritical && !r.everWarn, "slow sunrise (900 mV in 2 min): baseline follows, no warning, no emergency"); }
  { FlameDetector d; // sudden steady step (lamp switched on / cloud leaves sun), no flicker, no DO, 600 mV
    Run r = simulate(60000, [](int t) { return t < 10000 ? AMB : AMB - 600.0f; }, [](int) { return false; }, d);
    CHECK(!r.everCritical, "sudden steady step without flicker/DO: never EMERGENCY");
    CHECK(!r.everWarn, "... and not even a warning (uncorroborated is capped below 'present')");
    CHECK(!r.lastCritical && r.lastPct == 0, "... and after ~20 s it is accepted as the new ambient"); }
  { FlameDetector d; // real-like flame: appears at 10 s, drops ~700 mV, 8 Hz flicker
    Run r = simulate(30000, [](int t) { return t < 10000 ? AMB : AMB - flameWave(t, 10000, 700, 150, 8); }, [](int) { return false; }, d);
    CHECK(r.everCritical, "flickering flame, DO off (comparator mis-set): EMERGENCY from AO alone");
    CHECK(r.critAtMs >= 10000 && r.critAtMs - 10000 <= 2500, "... within 2.5 s of the flame appearing"); printf("      (detected after %d ms)\n", r.critAtMs - 10000); }
  { FlameDetector d; // flame with flicker AND DO asserted, then it saturates the sensor
    Run r = simulate(30000, [](int t) { return t < 10000 ? AMB : (t < 12000 ? AMB - flameWave(t, 10000, 700, 150, 8) : 80.0f); }, [](int t) { return t >= 10000; }, d);
    CHECK(r.everCritical && r.critAtMs - 10000 <= 2500, "flicker + DO, then saturated: EMERGENCY within 2.5 s"); }
  { FlameDetector d; // DIRECT SUNLIGHT: strong steady change, comparator (DO) on, no flicker
    Run r = simulate(60000, [](int t) { return t < 10000 ? AMB : 150.0f; }, [](int t) { return t >= 10000; }, d);
    CHECK(!r.everCritical, "direct sunlight (strong, steady, DO on): never EMERGENCY");
    CHECK(!r.everWarn, "... and not even a WARNING"); }
  { FlameDetector d; // room light switched on / reflection: moderate steady change with DO on
    Run r = simulate(60000, [](int t) { return t < 10000 ? AMB : AMB - 500.0f; }, [](int t) { return t >= 10000; }, d);
    CHECK(!r.everCritical && !r.everWarn, "bright lamp / reflection (steady, DO on): ignored"); }
  { FlameDetector d; // DO only (AO quiet), comparator set too sensitive
    Run r = simulate(30000, [](int) { return AMB; }, [](int t) { return t >= 10000; }, d);
    CHECK(!r.everCritical && !r.everWarn, "DO alone while AO is usable: ignored"); }
  { FlameDetector d; // LIMITATION (documented): a perfectly steady saturated 'flame' with DO and no flicker looks exactly like sunlight
    Run r = simulate(30000, [](int t) { return t < 10000 ? AMB : 80.0f; }, [](int t) { return t >= 10000; }, d);
    CHECK(!r.everCritical, "known limitation: steady saturation without any flicker is treated as sunlight"); }
  { FlameDetector d; // flicker that is only noise-like fast chatter (too many reversals) is not a flame
    Run r = simulate(40000, [](int t) { return t < 10000 ? AMB : AMB - 600.0f + ((t / 10) % 2 ? 90.0f : -90.0f); }, [](int) { return true; }, d);
    CHECK(!r.everCritical, "sample-rate chatter (every sample flips): not a flame"); }
  { FlameDetector d; // single short blink (shadow / flash) must not trigger
    Run r = simulate(30000, [](int t) { return (t >= 10000 && t < 10400) ? AMB - 700.0f + 150.0f * sinf(t / 30.0f) : AMB; }, [](int t) { return t >= 10000 && t < 10400; }, d);
    CHECK(!r.everCritical, "0.4 s flash: no emergency"); }
  { FlameDetector d; // saturated flame that then keeps burning: must stay critical (not absorbed by the baseline)
    Run r = simulate(90000, [](int t) { return t < 10000 ? AMB : (t < 11500 ? AMB - flameWave(t, 10000, 700, 150, 8) : 80.0f); }, [](int) { return false; }, d);
    CHECK(r.everCritical && r.lastCritical, "burning flame for 80 s stays critical (baseline never absorbs it)"); }
  { FlameDetector d; // flame goes out: returns to normal
    Run r = simulate(40000, [](int t) { return (t >= 10000 && t < 20000) ? AMB - flameWave(t, 10000, 700, 150, 8) : AMB; }, [](int) { return false; }, d);
    CHECK(r.everCritical && !r.lastCritical && r.lastPct == 0, "flame out: critical released"); }
  { FlameDetector d; // inverted polarity: AO RISES with flame while DO fires -> AO unusable, DO-only fallback
    Run r = simulate(30000, [](int t) { return t < 10000 ? 1000.0f : 1000.0f + 700.0f; }, [](int t) { return t >= 10000; }, d);
    CHECK(!r.usable, "AO moving against DO: AO declared unusable (legacy DO-only fallback)"); }
  { FlameDetector d; // floating / very noisy AO at boot
    Run r; int doCount = 0; (void)doCount; bool usable = true;
    for (int t = 0; t < 40000; t += 10) { d.feed(500.0f + (rand() % 2000)); if (t % 250 == 0) { FlameOut o = d.evaluate(t + 1000, false); usable = o.aoUsable; } }
    CHECK(!usable, "floating AO at boot: unusable -> DO-only fallback"); }
  { FlameDetector d; // 100 Hz mains-like ripple (aliased) on a steady ambient must not look like flicker
    Run r = simulate(30000, [](int t) { return AMB + 12.0f * sinf(6.2831853f * 1.0f * t / 1000.0f); }, [](int) { return false; }, d);
    CHECK(!r.everWarn, "tiny 1 Hz alias ripple on steady light: no warning"); }
  printf(failures ? "\n%d FAILED\n" : "\nALL PASSED\n", failures);
  return failures ? 1 : 0;
}
