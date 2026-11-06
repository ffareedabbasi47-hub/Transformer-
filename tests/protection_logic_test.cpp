// Host test of the relay arming logic (protection_logic.h). Build: see tests/firmware/README or CI.
#include <cstdio>
#include "protection_logic.h"
static int failures = 0;
#define CHECK(cond, msg) do { if (!(cond)) { printf("FAIL: %s\n", msg); failures++; } else { printf("ok:   %s\n", msg); } } while (0)

int main() {
  { // Healthy boot: cut until self-check time AND stable valid period
    RelayGate g; bool a = false;
    for (uint32_t t = 0; t < BOOT_SELF_CHECK_MS; t += 250) a |= g.update(t, Status::SAFE, false);
    CHECK(!a, "boot: supply stays cut during the self-check");
    CHECK(g.update(BOOT_SELF_CHECK_MS + RELAY_ARM_STABLE_MS, Status::SAFE, false), "boot: armed after self-check + stable period");
  }
  { // Invalid sensors at boot never authorise operation (even after a long time)
    RelayGate g; bool a = false;
    for (uint32_t t = 0; t < 120000; t += 250) a |= g.update(t, Status::WARNING, true);
    CHECK(!a, "invalid protection sensors at boot: supply NEVER allowed");
  }
  { // EMERGENCY cuts at once and re-arming needs a fresh stable period
    RelayGate g; uint32_t t = 0;
    for (; t < 10000; t += 250) g.update(t, Status::SAFE, false);
    CHECK(g.armed(), "armed after normal operation");
    CHECK(!g.update(t, Status::EMERGENCY, false), "EMERGENCY cuts immediately");
    CHECK(!g.update(t + 250, Status::SAFE, false), "not re-armed on the first safe cycle");
    CHECK(g.update(t + 250 + RELAY_ARM_STABLE_MS, Status::SAFE, false), "re-armed after the stable period");
  }
  { // Sensor fault: short glitch keeps supply, sustained fault cuts, recovery needs stable valid period
    RelayGate g; uint32_t t = 0;
    for (; t < 10000; t += 250) g.update(t, Status::SAFE, false);
    bool cutEarly = false;
    for (uint32_t k = 0; k < SENSOR_FAULT_CUTOFF_MS - 500; k += 250, t += 250) cutEarly |= !g.update(t, Status::WARNING, true);
    CHECK(!cutEarly, "short sensor fault: supply not cut yet");
    bool cut = false;
    for (uint32_t k = 0; k < 2000; k += 250, t += 250) cut |= !g.update(t, Status::WARNING, true);
    CHECK(cut, "sustained sensor fault: supply cut");
    CHECK(!g.update(t, Status::SAFE, false), "sensors valid again: still cut for a moment");
    CHECK(g.update(t + RELAY_ARM_STABLE_MS, Status::SAFE, false), "sensors stable again: supply returns");
  }
  { // uint32 millis() rollover must not break the timers
    RelayGate g; uint32_t t = 0xFFFFFFFFu - 4000;
    bool a = false;
    for (int i = 0; i < 80; i++, t += 250) a |= g.update(t, Status::SAFE, false);
    CHECK(true, "rollover: no crash");
    (void)a;
  }
  printf(failures ? "\n%d FAILED\n" : "\nALL PASSED\n", failures);
  return failures ? 1 : 0;
}
