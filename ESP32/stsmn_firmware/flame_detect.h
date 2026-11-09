// Flame detector for an IR flame module with analog (AO) + digital (DO) outputs.
// Pure C++ (no Arduino): tested on a PC with synthetic signals in tests/firmware/flame_detect_test.cpp.
//
// What it does (and does not):
//   * AO is the PRIMARY input. It is judged against a measured ambient BASELINE (learned at boot, then followed
//     only slowly and only while the signal is quiet), never against a fixed ADC level.
//   * A candidate must be above an enter threshold (>= noise based, hysteresis on exit) and must be CORROBORATED:
//       - DO asserted (comparator, supporting evidence), or
//       - flicker (fast direction changes of AO in a ~0.64 s window) that arrived with a sudden rise, or
//       - already confirmed (sticky, so a flame that saturates the sensor and stops flickering is not dropped).
//     An uncorroborated change (slow sun, lamp, reflection) is capped below the "signal present" level, and
//     after FLAME_REBASE_MS it becomes the new baseline. It can never cause an EMERGENCY.
//   * DO alone is NEVER an emergency while AO is usable (WARNING at most, after FLAME_DO_ONLY_HOLD_MS).
//   * Fail-safe: if AO is unusable (noisy/floating at boot, or it moves against DO = polarity wrong) the caller
//     falls back to the old DO-only behaviour, so a real flame is still detected.
//   * Limit: a sensor cannot tell sunlight from flame by physics alone; this uses behaviour (suddenness, flicker,
//     DO) and can be fooled (see docs/FLAME_AO_CALIBRATION.md).
#pragma once
#include <stdint.h>
#include <math.h>
#include "config.h"

enum FlameEvent : uint8_t { FE_NONE = 0, FE_BASELINE_READY = 1, FE_AO_UNUSABLE_NOISY = 2, FE_POLARITY_BAD = 4, FE_REBASED = 8,
                            FE_CRITICAL_ON = 16, FE_NEAR_ADC_CEILING = 32 };

struct FlameOut {
  int pct;            // 0..100 signal strength (only meaningful when aoUsable)
  bool critical;      // confirmed flame -> EMERGENCY (only meaningful when aoUsable)
  bool aoUsable;      // false: caller must use the legacy DO-only behaviour
  uint8_t events;     // FlameEvent bits, set only in the cycle they happen
  float mean, base, dev, noise, enter;   // diagnostics (mV)
  int reversals; float p2p;
  bool corroborated, flicker, rise;
};

class FlameDetector {
 public:
  static constexpr int N = 64;

  // Fast path: call about every FLAME_FAST_PERIOD_MS with the AO reading in mV.
  void feed(float mv) {
    if (!hasFast_) { xf_ = mv; hasFast_ = true; } else xf_ += 0.5f * (mv - xf_);
    buf_[idx_] = xf_;
    idx_ = (idx_ + 1) % N;
    if (count_ < N) count_++;
  }

  // Cycle path: call every SENSOR_CYCLE_MS. doOn = debounced DO asserted (comparator says flame).
  FlameOut evaluate(uint32_t now, bool doOn) {
    FlameOut o = {};
    o.noise = noise_; o.base = base_;
    const uint32_t dt = SENSOR_CYCLE_MS;
    if (count_ < N / 2) { o.aoUsable = false; return o; }

    // ---- window statistics ----
    float mean = 0, mn = buf_[0], mx = buf_[0];
    for (int i = 0; i < count_; i++) { float v = buf_[i]; mean += v; if (v < mn) mn = v; if (v > mx) mx = v; }
    mean /= count_;
    float var = 0;
    for (int i = 0; i < count_; i++) { float d = buf_[i] - mean; var += d * d; }
    const float sd = sqrtf(var / count_);
    o.mean = mean; o.p2p = mx - mn;

    // ---- baseline learning at boot (needs a quiet AO and no DO) ----
    if (!baseReady_) {
      if (initStart_ == 0) initStart_ = now ? now : 1;
      if (doOn || sd > FLAME_INIT_MAX_SIGMA_MV) { initQuietSince_ = 0; }
      else { if (initQuietSince_ == 0) initQuietSince_ = now ? now : 1; initSum_ += mean; initSd_ += sd; initN_++; }
      if (initQuietSince_ != 0 && (uint32_t)(now - initQuietSince_) >= FLAME_INIT_MS && initN_ > 0) {
        base_ = initSum_ / initN_;
        noise_ = fmaxf(FLAME_NOISE_FLOOR_MV, initSd_ / initN_);
        baseReady_ = true; o.events |= FE_BASELINE_READY;
        if (base_ > 3050.0f) o.events |= FE_NEAR_ADC_CEILING;
      } else {
        if (initQuietSince_ == 0) { initSum_ = initSd_ = 0; initN_ = 0; }
        if ((uint32_t)(now - initStart_) >= FLAME_INIT_GIVEUP_MS && sd > FLAME_INIT_MAX_SIGMA_MV) {
          if (!dead_) o.events |= FE_AO_UNUSABLE_NOISY;
          dead_ = true;
        }
      }
      o.aoUsable = false; o.mean = mean; o.base = base_; o.noise = noise_;
      return o;
    }
    if (dead_ || polarityBad_) { o.aoUsable = false; o.mean = mean; o.base = base_; return o; }

    // ---- deviation toward "flame" ----
    const float sgn = FLAME_AO_DROPS_WITH_FLAME ? 1.0f : -1.0f;
    const float dev = sgn * (base_ - mean);
    const float enter = fmaxf(FLAME_MIN_DELTA_MV, FLAME_ENTER_SIGMAS * noise_);
    const float exitT = enter * FLAME_EXIT_RATIO;
    if (!active_ && dev >= enter) active_ = true;
    else if (active_ && dev < exitT) active_ = false;
    if (!active_ && !doOn) noise_ += 0.1f * (fmaxf(FLAME_NOISE_FLOOR_MV, sd) - noise_);

    // ---- polarity check against DO ----
    if (doOn) { doMs_ += dt; } else { doMs_ = 0; }
    if (doOn && doMs_ >= 1500 && dev <= -fmaxf(4.0f * noise_, 0.5f * FLAME_MIN_DELTA_MV)) {
      polarityBad_ = true; o.events |= FE_POLARITY_BAD; o.aoUsable = false; return o;
    }

    // ---- sudden rise (signal-change analysis) ----
    constexpr int H = FLAME_RISE_WINDOW_MS / SENSOR_CYCLE_MS;
    float oldest = dev;
    for (int i = 0; i < H; i++) if (devHist_[i] < oldest) oldest = devHist_[i];
    const bool rapid = histN_ >= H && dev >= enter && (dev - oldest) >= FLAME_RISE_FRAC * enter;
    for (int i = H - 1; i > 0; i--) devHist_[i] = devHist_[i - 1];
    devHist_[0] = dev; if (histN_ < H) histN_++;
    if (rapid) riseUntil_ = now + FLAME_CORROB_HOLD_MS;
    const bool riseRecent = (int32_t)(riseUntil_ - now) > 0;

    // ---- flicker: direction reversals of AO around its mean, swing >= h ----
    const float h = fmaxf(FLAME_FLICKER_SIGMAS * noise_, FLAME_FLICKER_FRAC * (dev > 0 ? dev : 0));
    int rev = 0; int state = 0;
    for (int k = 0; k < count_; k++) {
      const float v = buf_[(idx_ + (N - count_) + k) % N] - mean;     // oldest -> newest
      if (v > h * 0.5f) { if (state == -1) rev++; state = 1; }
      else if (v < -h * 0.5f) { if (state == 1) rev++; state = -1; }
    }
    const bool flickerNow = active_ && rev >= FLAME_MIN_REVERSALS;
    o.reversals = rev;

    // ---- corroboration ----
    const bool corroborated = doOn || (flickerNow && riseRecent) || sticky_;

    // ---- strength ----
    int pctRaw = (int)lroundf(dev / FLAME_FULL_DELTA_MV * 100.0f);
    if (pctRaw < 0) pctRaw = 0;
    if (pctRaw > 100) pctRaw = 100;

    // ---- confirmation (only added delay: FLAME_CONFIRM_MS) ----
    const bool strong = active_ && pctRaw >= FLAME_CRIT_PCT;
    if (strong && corroborated) confirmMs_ += dt; else if (!sticky_) confirmMs_ = 0;
    bool critical = sticky_ || confirmMs_ >= FLAME_CONFIRM_MS;
    if (critical && !sticky_) { sticky_ = true; o.events |= FE_CRITICAL_ON; }
    if (sticky_) {                         // stays critical while the flame signal is still there
      if (active_) belowMs_ = 0; else { belowMs_ += dt; if (belowMs_ >= 2000) { sticky_ = false; confirmMs_ = 0; critical = false; belowMs_ = 0; } }
    }

    // ---- baseline: follow slowly while quiet; absorb steady, uncorroborated changes; never absorb fire-like ones ----
    if (!active_ && !riseRecent && !sticky_ && !flickerNow && sd < 3.0f * noise_ + 1.0f) base_ += FLAME_BASELINE_ALPHA * (mean - base_);
    if (active_ && !corroborated && !flickerNow) {
      uncorrMs_ += dt;
      if (uncorrMs_ >= FLAME_REBASE_MS) { base_ = mean; uncorrMs_ = 0; o.events |= FE_REBASED; active_ = false; }
    } else uncorrMs_ = 0;

    // ---- DO alone (AO quiet): WARNING at most ----
    int pct;
    if (!active_) pct = (doOn && doMs_ >= FLAME_DO_ONLY_HOLD_MS) ? SIGNAL_MIN_PCT : 0;
    else if (!corroborated) pct = pctRaw < SIGNAL_MIN_PCT ? pctRaw : SIGNAL_MIN_PCT - 1;
    else if (!critical) pct = pctRaw < SIGNAL_MIN_PCT ? SIGNAL_MIN_PCT : (pctRaw < FLAME_CRIT_PCT ? pctRaw : FLAME_CRIT_PCT - 1);
    else pct = pctRaw < FLAME_CRIT_PCT ? FLAME_CRIT_PCT : pctRaw;

    o.pct = pct; o.critical = critical; o.aoUsable = true;
    o.base = base_; o.dev = dev; o.noise = noise_; o.enter = enter;
    o.corroborated = corroborated; o.flicker = flickerNow; o.rise = riseRecent;
    return o;
  }

  bool baselineReady() const { return baseReady_; }

 private:
  float buf_[N] = {};
  int idx_ = 0, count_ = 0;
  float xf_ = 0; bool hasFast_ = false;
  bool baseReady_ = false, dead_ = false, polarityBad_ = false, active_ = false, sticky_ = false;
  float base_ = 0, noise_ = FLAME_NOISE_FLOOR_MV;
  uint32_t initStart_ = 0, initQuietSince_ = 0; float initSum_ = 0, initSd_ = 0; int initN_ = 0;
  float devHist_[FLAME_RISE_WINDOW_MS / SENSOR_CYCLE_MS] = {}; int histN_ = 0;
  uint32_t riseUntil_ = 0, doMs_ = 0, confirmMs_ = 0, belowMs_ = 0, uncorrMs_ = 0;
};
