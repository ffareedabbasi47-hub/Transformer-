# STSMN risk algorithm (what the ESP32 decides, and why)

No machine learning: a transparent rule engine (`ESP32/stsmn_firmware/risk_engine.h`). Every number below is a constant in `config.h`
(DEMO values, calibrate on your hardware). The same code runs on a PC in `tests/risk_engine_test.cpp`.

## 1. Signal strength (0-100 %) per sensor
| Sensor | 0 % | 100 % |
|---|---|---|
| Smoke (MQ-2) | rise <= 30 mV above clean-air baseline | rise >= 450 mV |
| Flame | no flame | strong flame (AO pin: calibrated; DO only: on = 100 %) |
| Temperature | <= 40 C | >= 70 C |
| Current | <= 2.0 A | >= 4.5 A |
| Voltage | within +-15 % of nominal | +-40 % |

A sensor that is not fitted (`HAS_* = false`) or not valid is `null`: no points, never a fault.

## 2. Points (sum, clamped 0-100) - fire and smoke weigh the same
| | max points |
|---|---|
| Smoke | **50** |
| Fire = flame **35** + temperature **15** | **50** |
| Current | 25 |
| Voltage | 15 |
| Low level | 10 |

Points = max x strength %, so a weak signal gives few points, a strong one the full amount.

## 3. Floors (a score alone must never hide a danger)
- any smoke or flame signal (>= 15 %) ........ at least **55** (WARNING)
- temperature / current / voltage >= 50 % of its range ... at least **55** (WARNING)
- a FITTED protection sensor is dead .......... at least **55** (WARNING)
- any critical condition ...................... at least **90** (EMERGENCY)

## 4. Critical conditions = EMERGENCY (alarm + buzzer + supply cut + SMS), no waiting for the score
- flame at >= 40 % (or the flame module's DO is on) - immediately
- **smoke and flame together (even weak, >= 15 % each) - immediately**
- smoke while temperature >= 40 C (or temperature unknown) for 2 s
- smoke alone for 3 s
- temperature >= 70 C for 3 s; current >= 4.5 A for 1.5 s

## 5. Status and relay
- SAFE -> WARNING at score >= 55, back to SAFE below 45 (hysteresis, no flapping).
- WARNING -> EMERGENCY at score >= 80 or any critical condition. EMERGENCY is left only after 10 s of safe readings.
- Relay: supply is allowed only when armed (boot check + all FITTED sensors valid for 2 s). EMERGENCY **always** cuts it and disarms.
  A sensor fault lasting 10 s also cuts it. The phone/app can never switch the relay.
- Alarm (buzzer pulses) = EMERGENCY. Red LED blinks. SMS goes to the saved alert number (see below) once per episode, reminder every 30 min.
