// Over-current latch (decision D1): a trip stays open until an authorised reset; no auto re-closing into a fault.
// g++ -std=c++17 -I../ESP32/stsmn_firmware latch_test.cpp
#include <cstdio>
#include "risk_engine.h"
#include "protection_logic.h"
static int failures = 0;
#define CHECK(c, m) do { if (!(c)) { printf("FAIL: %s\n", m); failures++; } else printf("ok:   %s\n", m); } while (0)
static RiskInput normal(float amps = 0.5f) { return RiskInput{true, 30.0f, true, amps, true, 3.7f, false, false, true, 80, 0, 0}; }

int main() {
  { // permanent short for 20 min: exactly one closure, then locked open
    RiskEngine r; RelayGate g; uint32_t now = 20000; bool relay = false, prev = false; int closes = 0;
    for (int i = 0; i < 4800; i++) {
      auto res = r.update(normal(relay ? 6.0f : 0.0f), now); relay = g.update(now, res.status, res.sensorFault);
      if (relay && !prev) closes++; prev = relay; now += 250;
    }
    CHECK(closes == 1, "permanent short: the supply is connected once, then stays open (was 134 re-closes)");
    CHECK(r.latched() && r.status() == Status::EMERGENCY, "latched trip keeps EMERGENCY");
  }
  { // reset: refused during the minimum open time, accepted afterwards, then normal operation resumes
    RiskEngine r; RelayGate g; uint32_t now = 20000; bool relay = false;
    for (int i = 0; i < 80; i++) { auto res = r.update(normal(), now); relay = g.update(now, res.status, res.sensorFault); now += 250; }
    for (int i = 0; i < 12; i++) { auto res = r.update(normal(6.0f), now); relay = g.update(now, res.status, res.sensorFault); now += 250; }
    CHECK(r.latched() && !relay, "overcurrent trips and latches");
    auto res = r.update(normal(0.0f), now);
    CHECK(res.latched && res.resetWaitMs > 0 && res.resetWaitMs <= TRIP_MIN_OPEN_MS, "reset wait is reported");
    CHECK(!r.reset(now), "reset refused before the minimum open time");
    now += TRIP_MIN_OPEN_MS + 250;
    CHECK(r.reset(now), "reset accepted after the minimum open time");
    for (int i = 0; i < 100; i++) { auto s2 = r.update(normal(0.5f), now); relay = g.update(now, s2.status, s2.sensorFault); now += 250; }
    CHECK(!r.latched() && r.status() == Status::SAFE && relay, "after reset and healthy readings the supply returns");
  }
  { // trip memory: restored latch after a reset of the ESP32
    RiskEngine r; r.restoreLatched(1000); RelayGate g; bool relay = false; uint32_t now = 1000;
    for (int i = 0; i < 200; i++) { auto res = r.update(normal(0.0f), now); relay = g.update(now, res.status, res.sensorFault); now += 250; }
    CHECK(r.latched() && !relay, "restored trip memory keeps the supply cut after a reboot");
  }
  { // boot hold after an abnormal reset
    RelayGate g; RiskEngine r; uint32_t now = 20000; bool relay = false;
    for (int i = 0; i < 100; i++) { auto res = r.update(normal(), now); relay = g.update(now, res.status, res.sensorFault, true); now += 250; }
    CHECK(!relay, "boot hold keeps the supply cut");
    for (int i = 0; i < 40; i++) { auto res = r.update(normal(), now); relay = g.update(now, res.status, res.sensorFault, false); now += 250; }
    CHECK(relay, "supply returns after the hold is released and sensors are healthy");
  }
  { // short momentary spike shorter than the hold time does not latch
    RiskEngine r; uint32_t now = 20000;
    for (int i = 0; i < 40; i++) { r.update(normal(), now); now += 250; }
    r.update(normal(6.0f), now); now += 250; r.update(normal(0.5f), now);
    CHECK(!r.latched(), "spike shorter than CRIT_CURRENT_HOLD_MS does not latch");
  }
  printf(failures ? "FAILED: %d\n" : "ALL PASSED\n", failures);
  return failures ? 1 : 0;
}
