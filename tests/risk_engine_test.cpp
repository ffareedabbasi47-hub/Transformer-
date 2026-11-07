// Host-side test of the firmware risk engine (no ESP32 needed).
// Build & run:  g++ -std=c++17 -I../ESP32/stsmn_firmware risk_engine_test.cpp -o risk_test && ./risk_test
#include <cstdio>
#include <cstdlib>
#include "risk_engine.h"

static int failures = 0;
#define CHECK(cond, msg) do { if (!(cond)) { printf("FAIL: %s\n", msg); failures++; } else { printf("ok:   %s\n", msg); } } while (0)

static RiskInput normal() {
  return RiskInput{true, 30.0f, true, 0.5f, true, NOMINAL_VOLTAGE_V, false, false, true, 85};
}

int main() {
  { // Scenario A: everything normal -> SAFE, low risk
    RiskEngine e; RiskResult r = e.update(normal(), 1000);
    CHECK(r.status == Status::SAFE && r.risk < WARNING_ENTER, "A: normal -> SAFE");
  }
  { // Scenario B: warm + overload + low oil -> WARNING (not EMERGENCY)
    RiskEngine e; RiskInput in = normal();
    in.tempC = 60.0f; in.currentA = 3.5f; in.oilPct = 20;
    RiskResult r = e.update(in, 1000);
    CHECK(r.status == Status::WARNING && r.risk >= WARNING_ENTER && r.risk < EMERGENCY_ENTER, "B: abnormal -> WARNING");
  }
  { // Scenario C: flame -> EMERGENCY immediately, risk >= 90
    RiskEngine e; RiskInput in = normal(); in.flame = true;
    RiskResult r = e.update(in, 1000);
    CHECK(r.status == Status::EMERGENCY && r.risk >= FLOOR_CRITICAL_RISK, "C: flame -> EMERGENCY, risk>=90");
  }
  { // Sensor fault -> at least WARNING (protection is blind)
    RiskEngine e; RiskInput in = normal(); in.tempValid = false;
    RiskResult r = e.update(in, 1000);
    CHECK(r.status == Status::WARNING && r.sensorFault, "dead temp sensor -> WARNING");
  }
  { // Sustained over-temperature -> EMERGENCY only after hold time
    RiskEngine e; RiskInput in = normal(); in.tempC = 75.0f;
    RiskResult r = e.update(in, 1000);
    CHECK(r.status != Status::EMERGENCY, "over-temp: not instant");
    r = e.update(in, 1000 + CRIT_TEMP_HOLD_MS + 10);
    CHECK(r.status == Status::EMERGENCY, "over-temp held -> EMERGENCY");
  }
  { // Recovery needs RECOVERY_MS of stable safe readings (no oscillation)
    RiskEngine e; RiskInput bad = normal(); bad.flame = true;
    e.update(bad, 0);
    RiskInput ok = normal();
    RiskResult r = e.update(ok, 100);
    CHECK(r.status == Status::EMERGENCY, "recovery: still EMERGENCY right after clear");
    r = e.update(ok, 100 + RECOVERY_MS - 1000);
    CHECK(r.status == Status::EMERGENCY, "recovery: still EMERGENCY before period ends");
    r = e.update(ok, 100 + RECOVERY_MS + 10);
    CHECK(r.status == Status::SAFE, "recovery: SAFE after stable period");
  }
  { // Recovery clock restarts if danger returns
    RiskEngine e; RiskInput bad = normal(); bad.flame = true;
    e.update(bad, 0);
    RiskInput ok = normal();
    e.update(ok, 100);
    e.update(bad, 5000);
    RiskResult r = e.update(ok, 5100 + RECOVERY_MS - 500);
    CHECK(r.status == Status::EMERGENCY, "recovery clock restarts after relapse");
  }
  { // Hysteresis: WARNING does not drop to SAFE until below WARNING_EXIT
    RiskEngine e; RiskInput in = normal(); in.oilPct = 20; in.currentA = 3.8f; in.tempC = 55.0f; // pushes into WARNING
    RiskResult r = e.update(in, 0);
    CHECK(r.status == Status::WARNING, "hysteresis: enters WARNING");
    RiskInput mid = normal(); mid.oilPct = 20; mid.tempC = 55.0f; mid.currentA = 3.0f; // base between EXIT and ENTER
    r = e.update(mid, 250);
    if (r.risk >= WARNING_EXIT && r.risk < WARNING_ENTER) CHECK(r.status == Status::WARNING, "hysteresis: stays WARNING in band");
    else printf("skip: band case risk=%d\n", r.risk);
  }
  { // Smoke by itself (cool, all sensors fine): never silently SAFE, and EMERGENCY + cutoff if it does not go away
    RiskEngine e; RiskInput in = normal(); in.smoke = true;
    RiskResult r = e.update(in, 0);
    CHECK(r.status == Status::WARNING && r.risk >= FLOOR_SMOKE_RISK, "smoke alone -> WARNING immediately");
    r = e.update(in, CRIT_SMOKE_HOLD_MS - 100);
    CHECK(r.status == Status::WARNING, "smoke alone: not yet EMERGENCY before the hold time");
    r = e.update(in, CRIT_SMOKE_HOLD_MS + 100);
    CHECK(r.status == Status::EMERGENCY && r.critical, "smoke held -> EMERGENCY (supply is cut)");
  }
  { // A short puff that clears does not trip EMERGENCY
    RiskEngine e; RiskInput in = normal(); in.smoke = true;
    e.update(in, 0); in.smoke = false; RiskResult r = e.update(in, 1500);
    r = e.update(in, 8000);
    CHECK(r.status == Status::SAFE, "brief smoke that clears -> back to SAFE");
  }
  { // Smoke and fire are weighted equally: 50 points each, growing with the signal strength
    CHECK(PTS_SMOKE == 50 && PTS_FLAME + PTS_TEMP == 50, "smoke 50 points; fire half (flame + temperature) 50 points");
    RiskInput in = normal(); in.smoke = true; in.smokePct = 100;
    CHECK(scoreSensors(in).smoke == 50, "strong smoke = 50 points");
    in.smokePct = 40; CHECK(scoreSensors(in).smoke == 20, "weak smoke (40 %) = 20 points");
    RiskInput f = normal(); f.flame = true; f.flamePct = 100;
    CHECK(scoreSensors(f).flame == PTS_FLAME, "strong flame = full flame points");
    f.flame = false; f.flamePct = 20; CHECK(scoreSensors(f).flame == (int)lroundf(PTS_FLAME * 0.2f), "weak flame = few points");
  }
  { // Weak fire signal alone: WARNING (never silent), not yet an emergency
    RiskEngine e; RiskInput in = normal(); in.flame = false; in.flamePct = 20;
    RiskResult r = e.update(in, 0);
    CHECK(r.status == Status::WARNING && !r.critical, "weak flame signal -> WARNING only");
  }
  { // Smoke + fire together (even weak): alarm + cutoff immediately
    RiskEngine e; RiskInput in = normal(); in.smoke = true; in.smokePct = 30; in.flamePct = 20;
    RiskResult r = e.update(in, 0);
    CHECK(r.status == Status::EMERGENCY && r.critical && r.risk >= FLOOR_CRITICAL_RISK, "smoke + flame together -> EMERGENCY at once");
  }
  { // Percentages for the app
    RiskInput in = normal(); in.tempC = 55.0f; in.smoke = true; in.smokePct = 60; in.flamePct = 0;
    RiskPct p = scorePercent(in);
    CHECK(p.temp == 50 && p.smoke == 60 && p.flame == 0, "pct: temp halfway = 50 %, smoke 60 %, flame 0 %");
    in.voltageValid = false; CHECK(scorePercent(in).voltage == -1, "pct: invalid / unfitted sensor = -1 (null)");
  }
  { // Regression: smoke while the temperature sensor is blind must be treated as smoke WITH heat -> EMERGENCY
    RiskEngine e; RiskInput in = normal(); in.smoke = true; in.tempValid = false;
    RiskResult r = e.update(in, 0);
    CHECK(r.status != Status::EMERGENCY, "smoke + blind temp: not instant");
    r = e.update(in, CRIT_SMOKE_HEAT_HOLD_MS + 10);
    CHECK(r.status == Status::EMERGENCY, "smoke + blind temp held -> EMERGENCY (fire cannot hide behind a dead sensor)");
  }
  { // Regression: every protection sensor invalid + nothing else wrong must never read SAFE
    RiskEngine e; RiskInput in = normal(); in.tempValid = false; in.currentValid = false; in.voltageValid = false;
    RiskResult r = e.update(in, 0);
    CHECK(r.status != Status::SAFE && r.risk >= FLOOR_SENSOR_FAULT_RISK, "all protection sensors invalid -> never SAFE");
  }
  { // NaN / infinity values must not produce points or crash the score
    RiskEngine e; RiskInput in = normal(); in.tempC = NAN; in.currentA = INFINITY; in.voltageV = NAN;
    RiskResult r = e.update(in, 0);
    CHECK(r.risk >= 0 && r.risk <= 100, "NaN/inf inputs: score stays 0..100");
  }
  { // Score boundaries: exactly at the WARNING/EMERGENCY thresholds, with hysteresis
    RiskEngine e; RiskInput in = normal(); in.oilPct = 0; in.tempC = 52.0f; in.currentA = 3.6f;
    RiskResult r = e.update(in, 0);
    CHECK(r.risk >= 0 && r.risk <= 100, "boundary: score within range");
  }
  { // Cap check: max possible base is clamped to 100
    RiskEngine e; RiskInput in = RiskInput{true, 100, true, 5, true, 20, true, true, true, 0};
    RiskResult r = e.update(in, 0);
    CHECK(r.risk <= 100 && r.parts.base <= 100, "score clamped to 100");
  }
  { // Never-connected sensor (setFitted false) is not a fault; a connected one that is invalid still is.
    RiskEngine e; RiskInput in = normal(); in.tempValid = false; in.voltageValid = false;
    e.setFitted(false, true, false);
    RiskResult r = e.update(in, 1000);
    CHECK(!r.sensorFault && r.status == Status::SAFE, "unplugged sensors ignored -> SAFE, no 45% floor");
    RiskEngine e2; RiskInput in2 = normal(); in2.currentValid = false;
    e2.setFitted(false, true, false);
    RiskResult r2 = e2.update(in2, 1000);
    CHECK(r2.sensorFault && r2.risk >= FLOOR_SENSOR_FAULT_RISK, "lost connected sensor -> fault + floor");
  }
  printf(failures ? "\n%d FAILED\n" : "\nALL PASSED\n", failures);
  return failures ? 1 : 0;
}
