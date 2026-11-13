// Host test of the REAL sensors.h / actuators.h against the Arduino MOCK (tests/firmware/mock).
// Simulates disconnected / saturated / NaN-like inputs. Not a hardware test.
#include <cstdio>
#include "Arduino.h"
#include "sensors.h"
#include "actuators.h"
static int failures = 0;
#define CHECK(cond, msg) do { if (!(cond)) { printf("FAIL: %s\n", msg); failures++; } else { printf("ok:   %s\n", msg); } } while (0)

static void reset() {
  hw() = MockHw();
  hw().mv[PIN_ACS712] = 1650; hw().mv[PIN_VOLTAGE] = 740;  // 3.7 V through the /5 module
  hw().mv[PIN_MQ2_AO] = 400;  hw().mv[PIN_LEVEL] = 1500;
  hw().level[PIN_FLAME_DO] = HIGH;                          // active-low module: HIGH = no flame
  mockDs().temp = 30.0f;
}
// Advance `ms` of simulated time: temperature polled every 50 ms, protection cycle every 250 ms.
static SensorReadings run(SensorHub& h, uint32_t ms) {
  SensorReadings r{};
  for (uint32_t t = 0; t < ms; t += 50) {
    hw().now += 50; h.pollTemperature(hw().now);
    if (hw().now % 250 == 0) r = h.sample(hw().now);
  }
  return r;
}

int main() {
  { reset(); SensorHub h; h.begin(); SensorReadings r = run(h, 3000);
    CHECK(r.tempValid && r.tempC > 29.5f && r.tempC < 30.5f, "normal temperature valid");
    CHECK(r.currentValid && r.currentA == 0.0f, "no load current -> 0 A and valid");
    CHECK(r.voltageValid && r.voltageV > 3.6f * VOLT_CAL_FACTOR && r.voltageV < 3.8f * VOLT_CAL_FACTOR, "voltage ~3.7 V valid");
    CHECK(!r.smoke && !r.mq2Ready, "MQ-2 warm-up: ready=false, smoke=false");
    CHECK(!r.flame, "no flame"); CHECK(r.oilValid, "level reading present");
  }
  { reset(); SensorHub h; h.begin(); run(h, 3000);
    mockDs().temp = DEVICE_DISCONNECTED_C;                  // sensor unplugged
    SensorReadings r = run(h, 8000);
    CHECK(!r.tempValid, "DS18B20 disconnected -> temperature INVALID (not 0, not -127)");
    mockDs().temp = 31.0f; r = run(h, 8000);
    CHECK(r.tempValid && r.tempC > 30.5f && r.tempC < 31.5f, "DS18B20 recovers and shows only fresh values");
  }
  { reset(); mockDs().temp = 85.0f; SensorHub h; h.begin(); SensorReadings r = run(h, 500);
    CHECK(!r.tempValid, "DS18B20 power-on value 85.0 C at start is rejected");
  }
  { reset(); mockDs().temp = 130.0f; SensorHub h; h.begin(); SensorReadings r = run(h, 6000);
    CHECK(!r.tempValid, "out-of-range temperature (130 C) invalid");
  }
  { reset(); hw().mv[PIN_ACS712] = 0;                       // ACS712 unplugged at boot: zero level implausible
    SensorHub h; h.begin(); SensorReadings r = run(h, 4000);
    CHECK(!r.currentValid, "ACS712 absent at boot -> current INVALID (never a valid 0 A)");
  }
  { reset(); SensorHub h; h.begin(); run(h, 3000);
    hw().mv[PIN_ACS712] = 3200;                             // saturated / shorted
    SensorReadings r = run(h, 3000);
    CHECK(!r.currentValid, "ACS712 saturated -> invalid after 2 s");
    hw().mv[PIN_ACS712] = 1650; r = run(h, 3000);
    CHECK(r.currentValid && r.currentA == 0.0f, "ACS712 recovers");
  }
  { reset(); SensorHub h; h.begin(); run(h, 3000);
    hw().mv[PIN_VOLTAGE] = 3300;                            // ADC saturated: input too high
    SensorReadings r = run(h, 3000);
    CHECK(!r.voltageValid, "voltage ADC saturated -> invalid (not clipped to a fake number)");
    hw().mv[PIN_VOLTAGE] = 700; r = run(h, 1250);
    CHECK(r.voltageValid && r.voltageV > 3.45f * VOLT_CAL_FACTOR && r.voltageV < 3.55f * VOLT_CAL_FACTOR, "voltage recovers without old values mixed in");
  }
  { reset(); SensorHub h; h.begin(); run(h, 3000);
    SensorReadings r{};
    for (int i = 0; i < 60; i++) { hw().mv[PIN_VOLTAGE] = (i % 2) ? 150 : 1100; hw().mv[PIN_LEVEL] = (i % 2) ? 300 : 1500; r = run(h, 250); }
    CHECK(!r.voltageValid, "floating/noisy voltage pin -> no fake number (invalid)");
    CHECK(!r.oilValid, "floating/noisy level pin -> no fake percentage (invalid)");
    hw().mv[PIN_VOLTAGE] = 700; hw().mv[PIN_LEVEL] = 900; r = run(h, 6000);
    CHECK(r.voltageValid && r.voltageV > 3.4f * VOLT_CAL_FACTOR && r.voltageV < 3.6f * VOLT_CAL_FACTOR, "steady voltage valid again");
    CHECK(r.oilValid, "steady level valid again");
    hw().mv[PIN_VOLTAGE] = 40; r = run(h, 8000);
    CHECK(r.voltageValid && fabsf(r.voltageV) < 0.001f, "below ADC dead-band reads 0 V, not a fake value");
  }
  { reset(); SensorHub h; h.begin(); run(h, 3000);
    hw().level[PIN_FLAME_DO] = LOW;                         // flame
    SensorReadings r = run(h, 250);
    CHECK(!r.flame, "flame needs debounce (not instant)");
    r = run(h, 750);
    CHECK(!r.flame && r.flamePct == 0, "steady DO alone (AO not usable, no chatter) = ignored: sunlight cannot sound the alarm");
  }
  { reset(); SensorHub h; h.begin(); SensorReadings r = run(h, MQ2_WARMUP_MS + 1000);
    CHECK(r.mq2Ready && !r.smoke, "MQ-2 ready after warm-up, clean air = no smoke");
    hw().mv[PIN_MQ2_AO] = 400 + MQ2_DELTA_ENTER_MV + 100; r = run(h, 3000);
    CHECK(r.smoke, "smoke above baseline detected");
    hw().mv[PIN_MQ2_AO] = 400; r = run(h, 4000);
    CHECK(!r.smoke, "smoke clears with hysteresis");
  }
  { reset(); hw().level[PIN_FLAME_DO] = HIGH; SensorHub h; h.begin();
    CHECK(hw().pinMode_[PIN_FLAME_DO] == INPUT_PULLUP, "flame input pulled to 'no flame' (unplugged module = no false alarm)");
  }
  { reset(); Actuators a; a.begin();
    if (RELAY_ACTIVE_LOW && RELAY_OPEN_DRAIN_OFF) {
      CHECK(hw().pinMode_[PIN_RELAY] == INPUT_PULLUP && !a.relayAllowed(), "relay: cut after begin() = pin released (weak pull-up high)");
      a.setRelayAllowed(true);
      CHECK(hw().pinMode_[PIN_RELAY] == OUTPUT && hw().level[PIN_RELAY] == LOW && !a.relayMismatch(), "relay: allowed = pin driven LOW");
      a.setRelayAllowed(true);
      CHECK(hw().pinMode_[PIN_RELAY] == OUTPUT && hw().level[PIN_RELAY] == LOW, "relay: repeated 'allowed' calls keep it on");
      a.setRelayAllowed(false);
      CHECK(hw().pinMode_[PIN_RELAY] == INPUT_PULLUP && !a.relayAllowed() && !a.relayMismatch(), "relay: cut again = released");
    } else {
      CHECK(hw().firstWriteBeforeMode[PIN_RELAY] == 1, "relay: cut level latched BEFORE the pin becomes an output (no boot pulse)");
      CHECK(hw().level[PIN_RELAY] == (RELAY_ACTIVE_LOW ? HIGH : LOW) && !a.relayAllowed(), "relay: cut after begin()");
      a.setRelayAllowed(true);
      CHECK(hw().level[PIN_RELAY] == (RELAY_ACTIVE_LOW ? LOW : HIGH) && !a.relayMismatch(), "relay: allowed maps to the configured polarity");
      a.setRelayAllowed(false);
      CHECK(hw().level[PIN_RELAY] == (RELAY_ACTIVE_LOW ? HIGH : LOW), "relay: cut again");
    }
  }
  printf(failures ? "\n%d FAILED\n" : "\nALL PASSED\n", failures);
  return failures ? 1 : 0;
}
