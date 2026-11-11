# Flame sensor (IR, AO + DO) - how it works, calibration, honest limits

Wiring used: DO -> GPIO25, AO -> GPIO36 (ADC1, safe with Wi-Fi). Power the module from **3.3 V** (so AO never exceeds what the ESP32 ADC can take).
`HAS_FLAME_ANALOG = true`. Code: `flame_detect.h` (logic), `sensors.h` (wiring into the sensor cycle), `config.h` (FLAME_* values).

## What the firmware does (v12: sunlight / lamps ignored by design)
1. **Sampling**: the protection loop runs every ~1 ms. AO is read each time and **averaged over one mains-ripple period** (10 ms at 50 Hz, `FLAME_MAINS_HZ`; set 60 for 60 Hz grids), giving a clean 100 Hz AO signal. This removes 100 Hz lamp ripple that would otherwise alias into fake "flicker". Window = 64 values (~0.64 s), evaluated every 250 ms.
2. **Baseline**: learned at boot from 3 s of quiet AO (**start the device with no flame in front of it**; DO is ignored for this, so a bright room is fine). Afterwards it follows slow drift only while the signal is quiet, never a sudden rise / flicker / confirmed flame.
3. **Polarity**: `FLAME_AO_DROPS_WITH_FLAME = true` assumes AO falls with flame IR (typical, not guaranteed). If DO fires for 1.5 s while AO moves the other way, AO is declared unusable (message printed). Then set it to `false`.
4. **The rule that ignores sun and lamps**: a candidate (deviation above `max(FLAME_MIN_DELTA_MV, 8 x noise)`) is only a flame if AO **flickers** (4..28 direction changes in the window, swing above noise, seen in 2 consecutive cycles). Steady sunlight, steady lamps, reflections and a comparator stuck on do NOT flicker, so they are **never a warning or an emergency**, however strong. After 20 s such a steady change becomes the new ambient.
5. **EMERGENCY** = strong (>= `FLAME_CRIT_PCT` = 40 % of `FLAME_FULL_DELTA_MV`) + flicker + (a sudden rise OR DO asserted), for 500 ms. DO supports; it never replaces flicker. Once confirmed it stays while the flame signal is present (a flame that saturates the sensor is not dropped). Flicker without rise/DO = WARNING level only.
6. **DO alone** while AO is usable = ignored. If AO is unusable (noisy at boot, retried every 20 s) the sketch uses DO only: a steady DO is ignored (sunlight cannot sound the alarm); DO that flickers (on/off several times in 0.6 s, as a flame does) is an EMERGENCY and is held 10 s. If AO moves against DO the sketch flips the AO direction once by itself. Smoke + flame signal (>= 15 %) still escalates through the existing smoke-and-fire rule.
7. Serial Monitor prints `FLAME mode=...` every 10 s: `AO+DO` means the analog logic is active; `DO-only (AO noisy/not wired)` means the AO wire is missing/floating - fix the wire.
8. MQ-2 smoke, temperature, relay protection and the state machine are untouched: smoke/temperature emergencies do not depend on this code.

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
- Sunlight contains far more IR than a flame. A photodiode-based module **cannot physically separate** sunlight from flame. This firmware uses *behaviour* (suddenness, flicker, DO, persistence), not spectrum. It reduces false alarms from slow daylight changes, steady lamps and static reflections; it can still be fooled by: sunlight that flickers (leaves, a fan blade, passing people) while it suddenly appears or DO is on, flashing/PWM lamps with a slow beat, welding, a heater coil right in front of the sensor.
- A real flame can be missed if: it is far away and weak (below the enter threshold), the module is mis-gained or clipped, or the flame never flickers and DO is off. Do not rely on this sensor alone: MQ-2 smoke and temperature protections remain independent.
- **Known blind spot**: a flame that is perfectly steady and saturates the sensor from the very first moment (no flicker ever seen) looks exactly like sunlight and is NOT an emergency by the flame logic (tested as a documented limitation). Real flames flicker while they grow; MQ-2 smoke and temperature remain independent protections.
- Hardware not tested by me: all behaviour above was checked only with **synthetic signals on a PC** (tests/firmware/flame_detect_test.cpp) and a compile check. Real module behaviour, thresholds and delays must be verified by you with the steps above.
