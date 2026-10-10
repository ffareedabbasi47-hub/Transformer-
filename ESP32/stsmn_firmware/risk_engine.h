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
};

struct RiskBreakdown { int temp, current, voltage, smoke, flame, oil, base; };

struct RiskResult {
  int risk;            // final 0..100 (after floors)
  Status status;
  bool critical;       // a critical sensor condition is active
  bool sensorFault;    // at least one protection sensor is invalid
  RiskBreakdown parts; // for Serial debug / judge explanation
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
  if (in.smoke) p.smoke = PTS_SMOKE;
  if (in.flame) p.flame = PTS_FLAME;
  if (in.oilValid) {
    // Low level: full points at/below OIL_LOW_PERCENT, none at/above OIL_OK_PERCENT.
    p.oil = (int)lroundf(PTS_OIL - rampPoints((float)in.oilPct, (float)OIL_LOW_PERCENT, (float)OIL_OK_PERCENT, (float)PTS_OIL));
  }
  int sum = p.temp + p.current + p.voltage + p.smoke + p.flame + p.oil;
  p.base = sum < 0 ? 0 : (sum > 100 ? 100 : sum);
  return p;
}

class RiskEngine {
 public:
  RiskResult update(const RiskInput& in, uint32_t now) {
    RiskResult r;
    r.parts = scoreSensors(in);
    r.sensorFault = !(in.tempValid && in.currentValid && in.voltageValid);

    bool tempCrit = tempCritTimer_.update(in.tempValid && in.tempC >= TEMP_CRIT_C, now, CRIT_TEMP_HOLD_MS);
    bool curCrit  = currentCritTimer_.update(in.currentValid && fabsf(in.currentA) >= CURRENT_CRIT_A, now, CRIT_CURRENT_HOLD_MS);
    bool smokeHeat = smokeHeatTimer_.update(in.smoke && in.tempValid && in.tempC >= TEMP_WARN_C, now, CRIT_SMOKE_HEAT_HOLD_MS);
    r.critical = in.flame || tempCrit || curCrit || smokeHeat;

    int risk = r.parts.base;
    if (r.sensorFault && risk < FLOOR_SENSOR_FAULT_RISK) risk = FLOOR_SENSOR_FAULT_RISK;
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
        if (r.critical || risk >= EMERGENCY_EXIT) {
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
    return r;
  }
  Status status() const { return status_; }

 private:
  void enterEmergency() { status_ = Status::EMERGENCY; recovering_ = false; }
  Status status_ = Status::SAFE;
  bool recovering_ = false;
  uint32_t recoveryStart_ = 0;
  HoldTimer tempCritTimer_, currentCritTimer_, smokeHeatTimer_;
};
