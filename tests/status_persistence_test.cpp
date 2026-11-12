// Every fault must stay visible (status + relay cut) for as long as it lasts, and clear by itself soon after it is gone.
// g++ -std=c++17 -I../ESP32/stsmn_firmware status_persistence_test.cpp
#include <cstdio>
#include <functional>
#include "risk_engine.h"
#include "protection_logic.h"

static int failures = 0;
#define CHECK(c, m) do { if (!(c)) { printf("FAIL: %s\n", m); failures++; } else printf("ok:   %s\n", m); } while (0)

struct Case { const char* name; std::function<void(RiskInput&)> on; Status minWhile; };

int main() {
  const Case cases[] = {
    {"temperature critical", [](RiskInput& i) { i.tempC = TEMP_CRIT_C + 5; }, Status::EMERGENCY},
        {"smoke", [](RiskInput& i) { i.smoke = true; i.smokePct = 100; }, Status::EMERGENCY},
    {"flame critical", [](RiskInput& i) { i.flame = true; i.flamePct = 80; }, Status::EMERGENCY},
    {"flame signal only", [](RiskInput& i) { i.flamePct = 30; }, Status::WARNING},
    {"temperature sensor fault", [](RiskInput& i) { i.tempValid = false; }, Status::WARNING},
  };
  for (const Case& c : cases) {
    RiskEngine risk; RelayGate gate; uint32_t now = 20000; bool lost = false, relayOn = false; int clearMs = -1;
    auto step = [&](bool fault) {
      RiskInput in{true, 30.0f, true, 0.5f, true, 3.7f, false, false, true, 80, 0, 0};
      if (fault) c.on(in);
      RiskResult r = risk.update(in, now);
      bool allow = gate.update(now, r.status, r.sensorFault);
      now += 250; return std::pair<RiskResult, bool>(r, allow);
    };
    for (int i = 0; i < 80; i++) step(false);                       // healthy, relay armed
    for (int i = 0; i < 1200; i++) {                                // 5 minutes of fault
      auto [r, allow] = step(true);
      if (i > 20 && (int)r.status < (int)c.minWhile) lost = true;
      if (c.minWhile == Status::EMERGENCY && i > 20 && allow) relayOn = true;
    }
    for (int i = 0; i < 400 && clearMs < 0; i++) { auto [r, allow] = step(false); (void)allow; if (r.status == Status::SAFE) clearMs = i * 250; }
    char m[160];
    snprintf(m, sizeof m, "%s: stays %s for 5 min", c.name, c.minWhile == Status::EMERGENCY ? "EMERGENCY" : "WARNING+"); CHECK(!lost, m);
    if (c.minWhile == Status::EMERGENCY) { snprintf(m, sizeof m, "%s: relay stays cut while it lasts", c.name); CHECK(!relayOn, m); }
    snprintf(m, sizeof m, "%s: back to SAFE by itself within 15 s (took %d ms)", c.name, clearMs); CHECK(clearMs >= 0 && clearMs <= 15000, m);
  }
  printf(failures ? "FAILED: %d\n" : "ALL PASSED\n", failures);
  return failures ? 1 : 0;
}
