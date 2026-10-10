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
  { // Cap check: max possible base is clamped to 100
    RiskEngine e; RiskInput in = RiskInput{true, 100, true, 5, true, 20, true, true, true, 0};
    RiskResult r = e.update(in, 0);
    CHECK(r.risk <= 100 && r.parts.base <= 100, "score clamped to 100");
  }
  printf(failures ? "\n%d FAILED\n" : "\nALL PASSED\n", failures);
  return failures ? 1 : 0;
}
