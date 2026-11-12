// =============================================================================
// Transparent, deterministic risk engine (NO machine learning).
//
//   1. Each sensor adds a bounded number of points (caps from the PDF).
//   2. Points are summed and clamped to 0..100  ->  "base" score.
//   3. Floors: a dead protection sensor => at least 45; a critical condition => at least 90.
//   4. Status comes from a hysteresis state machine (no flapping), and EMERGENCY
//      is only left after RECOVERY_MS of stable, safe readings.
// =============================================================================
#pragma once
#include <stdint.h>
#include <math.h>
#include "config.h"
#include "util.h"

enum class Status : uint8_t { SAFE = 0, WARNING = 1, EMERGENCY = 2 };

inline const char* statusName(Status s) {
  switch (s) {
    case Status::SAFE: return "SAFE";
    case Status::WARNING: return "WARNING";
    default: return "EMERGENCY";
  }
}

struct RiskInput {
  bool tempValid;    float tempC;
  bool currentValid; float currentA;
  bool voltageValid; float voltageV;
  bool smoke;
  bool flame;
  bool oilValid;     int oilPct;
  int smokePct;      int flamePct;     // signal strength 0..100 (0 = boolean-only caller: smoke/flame then count as 100)
  bool oilLow;                         // level below OIL_LOW_PERCENT for OIL_LOW_HOLD_MS (optional WARNING floor)
};

struct RiskBreakdown { int temp, current, voltage, smoke, flame, oil, base; };

// Strength of the smoke / flame signal, 0..100. Callers that only know on/off (smokePct/flamePct left 0) count as 100.
inline int effSmokePct(const RiskInput& in) { return in.smokePct > 0 ? (in.smokePct > 100 ? 100 : in.smokePct) : (in.smoke ? 100 : 0); }
inline int effFlamePct(const RiskInput& in) { return in.flamePct > 0 ? (in.flamePct > 100 ? 100 : in.flamePct) : (in.flame ? 100 : 0); }

struct RiskResult {
  int risk;            // final 0..100 (after floors)
  Status status;
  bool critical;       // a critical sensor condition is active
  bool sensorFault;    // at least one protection sensor is invalid
  RiskBreakdown parts; // for Serial debug / judge explanation
  bool latched;        // over-current trip: stays EMERGENCY / supply cut until an authorised reset
  int trips;           // over-current trips since boot
  uint32_t resetWaitMs;// minimum open time still to wait before a reset is accepted
};

// 0 points at/below lo, maxPts at/above hi, linear in between.
inline float rampPoints(float x, float lo, float hi, float maxPts) {
  if (isnan(x) || x <= lo) return 0.0f;
  if (x >= hi) return maxPts;
  return maxPts * (x - lo) / (hi - lo);
}

inline RiskBreakdown scoreSensors(const RiskInput& in) {
  RiskBreakdown p = {0, 0, 0, 0, 0, 0, 0};
  if (in.tempValid)    p.temp    = (int)lroundf(rampPoints(in.tempC, TEMP_WARN_C, TEMP_CRIT_C, PTS_TEMP));
  if (in.currentValid) p.current = (int)lroundf(rampPoints(fabsf(in.currentA), CURRENT_WARN_A, CURRENT_CRIT_A, PTS_CURRENT));
  if (in.voltageValid) {
    float devPct = fabsf(in.voltageV - NOMINAL_VOLTAGE_V) / NOMINAL_VOLTAGE_V * 100.0f;
    p.voltage = (int)lroundf(rampPoints(devPct, VOLT_OK_BAND_PCT, VOLT_FULL_BAND_PCT, PTS_VOLTAGE));
  }
  p.smoke = (int)lroundf(PTS_SMOKE * effSmokePct(in) / 100.0f);
  p.flame = (int)lroundf(PTS_FLAME * effFlamePct(in) / 100.0f);
  if (in.oilValid) {
    // Low level: full points at/below OIL_LOW_PERCENT, none at/above OIL_OK_PERCENT.
    p.oil = (int)lroundf(PTS_OIL - rampPoints((float)in.oilPct, (float)OIL_LOW_PERCENT, (float)OIL_OK_PERCENT, (float)PTS_OIL));
  }
  int sum = p.temp + p.current + p.voltage + p.smoke + p.flame + p.oil;
  p.base = sum < 0 ? 0 : (sum > 100 ? 100 : sum);
  return p;
}

// "How much of the danger range is used", 0..100 per sensor, -1 = no valid reading / not fitted. For the app.
struct RiskPct { int temp, current, voltage, smoke, flame; };
inline int rampPct(float x, float lo, float hi) { return (int)lroundf(rampPoints(x, lo, hi, 100.0f)); }
inline RiskPct scorePercent(const RiskInput& in) {
  RiskPct p;
  p.temp    = in.tempValid    ? rampPct(in.tempC, TEMP_WARN_C, TEMP_CRIT_C) : -1;
  p.current = in.currentValid ? rampPct(fabsf(in.currentA), CURRENT_WARN_A, CURRENT_CRIT_A) : -1;
  p.voltage = in.voltageValid ? rampPct(fabsf(in.voltageV - NOMINAL_VOLTAGE_V) / NOMINAL_VOLTAGE_V * 100.0f, VOLT_OK_BAND_PCT, VOLT_FULL_BAND_PCT) : -1;
  p.smoke   = effSmokePct(in);
  p.flame   = effFlamePct(in);
  return p;
}

class RiskEngine {
 public:
  // Runtime 'is this sensor connected at all' (a sensor never seen valid since boot is treated as not fitted). Default: all fitted.
  void setFitted(bool temp, bool current, bool voltage) { fitT_ = temp; fitC_ = current; fitV_ = voltage; }
  RiskResult update(const RiskInput& in, uint32_t now) {
    RiskResult r;
    r.parts = scoreSensors(in);
    r.latched = false; r.trips = 0; r.resetWaitMs = 0;
    // Only sensors that are FITTED (config.h HAS_*) can be a fault; an unplugged-by-design sensor is not one.
    r.sensorFault = (fitT_ && HAS_TEMP_SENSOR && !in.tempValid) || (fitC_ && HAS_CURRENT_SENSOR && !in.currentValid) ||
                    (fitV_ && HAS_VOLTAGE_SENSOR && !in.voltageValid);

    bool tempCrit = tempCritTimer_.update(in.tempValid && in.tempC >= TEMP_CRIT_C, now, CRIT_TEMP_HOLD_MS);
    bool curCrit  = currentCritTimer_.update(in.currentValid && fabsf(in.currentA) >= CURRENT_CRIT_A, now, CRIT_CURRENT_HOLD_MS);
    // Smoke with an unknown temperature is treated as smoke WITH heat (conservative: a blind sensor must not hide a fire).
    bool smokeHeat = smokeHeatTimer_.update(in.smoke && (!in.tempValid || in.tempC >= TEMP_WARN_C), now, CRIT_SMOKE_HEAT_HOLD_MS);
    bool smokeAlone = smokeTimer_.update(in.smoke, now, CRIT_SMOKE_HOLD_MS);   // real smoke that does not go away
    // Smoke AND fire together (even weak signals) = fire in progress: alarm + supply cut at once.
    const bool smokeSignal = in.smoke || effSmokePct(in) >= SIGNAL_MIN_PCT;
    const bool flameSignal = in.flame || effFlamePct(in) >= SIGNAL_MIN_PCT;
    const bool smokeAndFire = smokeSignal && flameSignal;
    if (curCrit && !latched_) { latched_ = true; latchSince_ = now; trips_++; newLatch_ = true; }
    r.critical = in.flame || tempCrit || curCrit || latched_ || smokeHeat || smokeAlone || smokeAndFire;

    int risk = r.parts.base;
    if (r.sensorFault && risk < FLOOR_SENSOR_FAULT_RISK) risk = FLOOR_SENSOR_FAULT_RISK;
    const RiskPct pc = scorePercent(in);
    const bool stressed = pc.temp >= SENSOR_WARN_PCT || pc.current >= SENSOR_WARN_PCT || pc.voltage >= SENSOR_WARN_PCT;
    if (stressed && risk < FLOOR_STRESS_RISK) risk = FLOOR_STRESS_RISK;
    if ((smokeSignal || flameSignal) && risk < FLOOR_SMOKE_RISK) risk = FLOOR_SMOKE_RISK;
    if (OIL_LOW_RAISES_WARNING && in.oilLow && risk < FLOOR_STRESS_RISK) risk = FLOOR_STRESS_RISK;
    if (r.critical && risk < FLOOR_CRITICAL_RISK) risk = FLOOR_CRITICAL_RISK;
    r.risk = risk;

    switch (status_) {
      case Status::SAFE:
        if (r.critical || risk >= EMERGENCY_ENTER) enterEmergency();
        else if (risk >= WARNING_ENTER) status_ = Status::WARNING;
        break;
      case Status::WARNING:
        if (r.critical || risk >= EMERGENCY_ENTER) enterEmergency();
        else if (risk < WARNING_EXIT) status_ = Status::SAFE;
        break;
      case Status::EMERGENCY:
        if (latched_ || r.critical || risk >= EMERGENCY_EXIT) {
          recovering_ = false;            // still unsafe: restart the recovery clock
        } else {
          if (!recovering_) { recovering_ = true; recoveryStart_ = now; }
          if ((uint32_t)(now - recoveryStart_) >= RECOVERY_MS) {
            status_ = (risk >= WARNING_ENTER) ? Status::WARNING : Status::SAFE;
            recovering_ = false;
          }
        }
        break;
    }
    r.status = status_;
    r.latched = latched_; r.trips = trips_;
    if (latched_) { const uint32_t el = (uint32_t)(now - latchSince_); r.resetWaitMs = el >= TRIP_MIN_OPEN_MS ? 0 : TRIP_MIN_OPEN_MS - el; }
    return r;
  }
  Status status() const { return status_; }
  bool latched() const { return latched_; }
  bool justLatched() { const bool v = newLatch_; newLatch_ = false; return v; }
  void restoreLatched(uint32_t now) { latched_ = true; latchSince_ = now; status_ = Status::EMERGENCY; recovering_ = false; }
  bool reset(uint32_t now) {
    if (!latched_) return true;
    if ((uint32_t)(now - latchSince_) < TRIP_MIN_OPEN_MS) return false;
    latched_ = false; status_ = Status::SAFE; recovering_ = false; currentCritTimer_.reset();
    return true;
  }

 private:
  bool fitT_ = true, fitC_ = true, fitV_ = true;
  bool latched_ = false, newLatch_ = false; int trips_ = 0; uint32_t latchSince_ = 0;
  void enterEmergency() { status_ = Status::EMERGENCY; recovering_ = false; }
  Status status_ = Status::SAFE;
  bool recovering_ = false;
  uint32_t recoveryStart_ = 0;
  HoldTimer tempCritTimer_, currentCritTimer_, smokeHeatTimer_, smokeTimer_;
};
