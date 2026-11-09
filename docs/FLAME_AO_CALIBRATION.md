# Flame sensor (IR, AO + DO) - how it works, calibration, honest limits

Wiring used: DO -> GPIO25, AO -> GPIO36 (ADC1, safe with Wi-Fi). Power the module from **3.3 V** (so AO never exceeds what the ESP32 ADC can take).
`HAS_FLAME_ANALOG = true`. Code: `flame_detect.h` (logic), `sensors.h` (wiring into the sensor cycle), `config.h` (FLAME_* values).

## What the firmware does
1. AO is sampled at ~100 Hz (every 10 ms, 2 reads averaged) in the protection task, evaluated every 250 ms on a 64-sample (~0.64 s) window.
2. **Baseline**: at boot it learns the ambient AO level (needs 3 s of quiet AO and DO not asserted). It then follows slow drift (~60 s time constant) but **only while the signal is quiet**; it never follows a sudden rise, a flickering signal, or a confirmed flame.
3. **Polarity**: `FLAME_AO_DROPS_WITH_FLAME = true` assumes the AO voltage *falls* with flame IR (typical, not guaranteed). The firmware cross-checks it with DO: if DO fires for 1.5 s while AO moved the *other* way, AO is declared unusable, a message is printed, and the sketch falls back to the old DO-only behaviour (so a real flame is still detected). Then set the constant to `false`.
4. **Candidate** = deviation from baseline >= `max(FLAME_MIN_DELTA_MV, 8 x measured noise)`; leaves again below half of that (hysteresis).
5. A candidate counts only if **corroborated**: DO asserted, or flicker (>= 4 direction changes with enough swing in the window) that arrived with a sudden rise, or already confirmed. Uncorroborated (slow sun, lamp, reflection): capped below the "signal present" level, never a warning/emergency, and after 20 s it becomes the new ambient.
6. **EMERGENCY**: strong (>= `FLAME_CRIT_PCT` = 40 % of `FLAME_FULL_DELTA_MV`) and corroborated for 500 ms (the only added delay). It stays critical while the flame signal is present.
7. **DO alone** (AO quiet) is never an emergency; after 3 s it is a WARNING-level signal (15 %). If AO is unusable (noisy/floating at boot, or polarity mismatch) DO alone is an emergency again (old behaviour, fail-safe).
8. Smoke (MQ-2), temperature, relay protection and the state machine are untouched: smoke/temperature emergencies do not depend on this code at all.

## Calibration (do this once; do NOT skip - the numbers in config.h are placeholders)
The firmware prints `FLAME: AO NOT CALIBRATED` at boot until you set `FLAME_AO_CALIBRATED = true`. Set `FLAME_CALIBRATION_LOG = true`, open the Serial Monitor (115200). You get once per second:
`FLAMEcal ao=<mV> base=<mV> dev=<mV> enter=<mV> noise=<mV> p2p=<mV> rev=<n> DO=<0/1> rise=.. flick=.. corr=.. pct=.. crit=.. usable=..`

Write the numbers down yourself - these are *your* readings, nobody else's. Each step: wait ~10 s, note `ao` (and p2p / rev).
1. **Indoor ambient** (room light, no window sun, sensor facing a wall): note `ao`, `noise`. Check `ao` is **below ~3050 mV**; if it sits at the top, the ADC clips - lower the module gain (blue potentiometer) or check it is powered from 3.3 V.
2. **Normal daylight** (window, no direct sun): note `ao`. Expect a small, steady change.
3. **Strong direct sunlight** on the sensor (outside or sun patch; do not stare at it): note `ao`, `rev` (should stay low), `pct`, `crit`. Also move in/out of the sun quickly to see a sudden step. This shows how your module reacts to sunlight (many IR modules saturate in sun).
4. **Controlled flame**: a candle or lighter, **in a safe place, on a non-flammable surface, with water/extinguisher near, never indoors near fuel, adult supervision, remove the relay load / use the low-voltage demo only**. Hold the flame ~20-30 cm away, then ~10 cm, then very close (never touch). For each distance note `ao`, `dev`, `p2p`, `rev`, DO.
5. Set the constants from YOUR notes:
   - `FLAME_FULL_DELTA_MV` = `dev` with a strong flame at your intended working distance (= 100 %). The emergency strength is 40 % of it.
   - `FLAME_MIN_DELTA_MV` = a value clearly above your ambient/daylight `dev` wobble (about 3x the biggest quiet `dev` you saw), but well below the dev of a weak flame at the longest distance you must protect.
   - If `rev` of the real flame is < 4, lower `FLAME_MIN_REVERSALS`; if sunlight shows `rev` >= 4, raise it. Check the same for `FLAME_FLICKER_*`.
   - Adjust the DO potentiometer so DO stays off in daylight and turns on for a near flame (DO is supporting evidence only; AO decides).
6. Set `FLAME_AO_CALIBRATED = true`, `FLAME_CALIBRATION_LOG = false`, upload, and repeat steps 3-4 once as a check: sun must not cause EMERGENCY; the flame must.

## Honest limits (read this)
- Sunlight contains far more IR than a flame. A photodiode-based module **cannot physically separate** sunlight from flame. This firmware uses *behaviour* (suddenness, flicker, DO, persistence), not spectrum. It reduces false alarms from slow daylight changes, steady lamps and static reflections; it can still be fooled by: direct sun that appears suddenly **and** flickers (leaves, a fan blade, passing people) together with DO on, flashing/PWM lamps, welding, a heater coil right in front of the sensor.
- A real flame can be missed if: it is far away and weak (below the enter threshold), the module is mis-gained or clipped, or the flame never flickers and DO is off. Do not rely on this sensor alone: MQ-2 smoke and temperature protections remain independent.
- A saturated sensor (AO pinned at the rail) has no flicker information; such a flame is accepted through the sudden-rise/DO path or the flicker seen while it was building up.
- Hardware not tested by me: all behaviour above was checked only with **synthetic signals on a PC** (tests/firmware/flame_detect_test.cpp) and a compile check. Real module behaviour, thresholds and delays must be verified by you with the steps above.
