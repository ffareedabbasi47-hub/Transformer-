#include <cstdio>
#include <initializer_list>
#include "risk_engine.h"
#include "protection_logic.h"
static int fails = 0;
#define CHECK(c, m) do { if (c) printf("ok:   %s\n", m); else { printf("FAIL: %s\n", m); fails++; } } while (0)
static RiskInput base(float v) { return RiskInput{true, 30.0f, true, 0.5f, true, v, false, false, true, 85, 0, 0, false}; }
int main() {
  for (float v : {0.0f, 3.7f, 8.0f}) { auto b = scoreSensors(base(v)); CHECK(b.voltage == 0, "0..8 V: no voltage points"); }
  CHECK(scoreSensors(base(9.0f)).voltage == 20, "9 V = 20 points");
  CHECK(scoreSensors(base(10.0f)).voltage == 40, "10 V = 40 points");
  CHECK(scoreSensors(base(11.0f)).voltage == 60, "11 V = 60 points");
  CHECK(scoreSensors(base(12.0f)).voltage == 80, "12 V = 80 points");
  { RiskEngine r; uint32_t now = 20000; Status s = Status::SAFE;
    for (int i = 0; i < 40; i++) { s = r.update(base(7.9f), now).status; now += 250; }
    CHECK(s == Status::SAFE, "7.9 V stays SAFE");
    for (int i = 0; i < 40; i++) { s = r.update(base(12.5f), now).status; now += 250; }
    CHECK(s == Status::EMERGENCY, "12.5 V = EMERGENCY"); }
  { RiskEngine r; RelayGate g; uint32_t now = 20000; bool relay = false; Status s = Status::SAFE;
    for (int i = 0; i < 80; i++) { auto res = r.update(base(5.0f), now); relay = g.update(now, res.status, res.sensorFault); now += 250; }
    CHECK(relay, "normal: supply on");
    int closesWhileBad = 0; bool prev = relay;
    for (int cyc = 0; cyc < 12; cyc++) {
      for (int i = 0; i < 24; i++) { auto res = r.update(base(12.6f), now); s = res.status; relay = g.update(now, res.status, res.sensorFault); if (relay && !prev) closesWhileBad++; prev = relay; now += 250; }
      for (int i = 0; i < 16; i++) { auto res = r.update(base(5.0f), now); s = res.status; relay = g.update(now, res.status, res.sensorFault); if (relay && !prev) closesWhileBad++; prev = relay; now += 250; }
    }
    CHECK(closesWhileBad == 0, "fault that flickers every 4 s: supply never closes meanwhile"); 
    for (int i = 0; i < 200; i++) { auto res = r.update(base(5.0f), now); s = res.status; relay = g.update(now, res.status, res.sensorFault); now += 250; }
    CHECK(relay && s == Status::SAFE, "condition really gone: supply returns by itself"); }
  printf(fails ? "\n%d FAILED\n" : "\nALL PASSED\n", fails);
  return fails != 0;
}
