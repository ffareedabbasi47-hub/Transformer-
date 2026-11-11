// Flame detector for an IR flame module with analog (AO) + digital (DO) outputs.
// Pure C++ (no Arduino): tested on a PC with synthetic signals in tests/firmware/flame_detect_test.cpp.
//
// What it does (and does not):
//   * AO is the PRIMARY input. It is judged against a measured ambient BASELINE (learned at boot, then followed
//     only slowly and only while the signal is quiet), never against a fixed ADC level.
//   * A candidate must be above an enter threshold (>= noise based, hysteresis on exit) and must show FLICKER (the one
//     property steady sunlight/lamps lack): several direction changes of AO inside a ~0.64 s window, in 2 consecutive
//     cycles. For an EMERGENCY the flicker must also come with a sudden rise OR DO asserted (DO supports, it never replaces flicker).
//     Once confirmed it stays (sticky) so a flame that saturates the sensor and stops flickering is not dropped.
//     An uncorroborated change (slow sun, lamp, reflection) is capped below the "signal present" level, and
//     after FLAME_REBASE_MS it becomes the new baseline. It can never cause an EMERGENCY.
//   * DO alone, or a strong STEADY AO change (sun, lamp, reflection) is never a warning nor an emergency while AO is usable.
//   * If AO is unusable (noisy/floating at boot, or it moves against DO = polarity wrong) the caller uses DO only:
//     only DO chatter (the comparator flickering with the flame) counts as an EMERGENCY; a steady DO is ignored (sunlight).
//   * Limit: a sensor cannot tell sunlight from flame by physics alone; this uses behaviour (suddenness, flicker,
//     DO) and can be fooled (see docs/FLAME_AO_CALIBRATION.md).
#pragma once
#include <stdint.h>
#include <math.h>
#include "config.h"

enum FlameWhy : uint8_t { FW_OK = 0, FW_LEARNING = 1, FW_NOISY = 2, FW_POLARITY = 3 };
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
  uint8_t why;        // FlameWhy: why aoUsable is false
};

struct FlameTuning {
  float minDeltaMv = FLAME_MIN_DELTA_MV;
  float flickerSigmas = FLAME_FLICKER_SIGMAS;
  int minReversals = FLAME_MIN_REVERSALS;
  uint32_t confirmMs = FLAME_CONFIRM_MS;
  uint32_t rebaseMs = FLAME_REBASE_MS;
  bool requireDo = true;
  void clampAll() {
    if (minDeltaMv < 10) minDeltaMv = 10; if (minDeltaMv > 400) minDeltaMv = 400;
    if (flickerSigmas < 2) flickerSigmas = 2; if (flickerSigmas > 12) flickerSigmas = 12;
    if (minReversals < 2) minReversals = 2; if (minReversals > 16) minReversals = 16;
    if (confirmMs < 250) confirmMs = 250; if (confirmMs > 3000) confirmMs = 3000;
    if (rebaseMs < 3000) rebaseMs = 3000; if (rebaseMs > 60000) rebaseMs = 60000;
  }
};

class FlameDetector {
 public:
  FlameTuning tune;
  void relearn() {
    baseReady_ = false; dead_ = false; polarityBad_ = false; active_ = false; sticky_ = false;
    initStart_ = 0; initQuietSince_ = 0; initSum_ = 0; initSd_ = 0; initN_ = 0;
    confirmMs_ = 0; belowMs_ = 0; uncorrMs_ = 0; flickerHits_ = 0; histN_ = 0;
  }

  static constexpr int N = 64;

  // Fast path: call about every FLAME_FAST_PERIOD_MS with the AO reading in mV.
  void feed(float mv) {
    buf_[idx_] = mv;                 // already an average over one mains-ripple period (see sensors.h)
    idx_ = (idx_ + 1) % N;
    if (count_ < N) count_++;
  }

  void feedDo(bool raw) {
    dbuf_[didx_] = raw ? 1 : 0;
    didx_ = (didx_ + 1) % N;
    if (dcount_ < N) dcount_++;
  }

  // Cycle path: call every SENSOR_CYCLE_MS. doOn = debounced DO asserted (comparator says flame).
  FlameOut evaluate(uint32_t now, bool doOn) {
    FlameOut o = {};
    o.noise = noise_; o.base = base_;
    const uint32_t dt = SENSOR_CYCLE_MS;
    if (count_ < N / 2) { o.aoUsable = false; o.why = FW_LEARNING; return o; }

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
      (void)doOn;                               // the baseline is learned even if DO is on (bright room): start the device with NO flame in front of it
      if (sd > FLAME_INIT_MAX_SIGMA_MV) { initQuietSince_ = 0; }
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
          if (!dead_) deadSince_ = now;
          dead_ = true;
        }
      }
      o.aoUsable = false; o.why = dead_ ? FW_NOISY : FW_LEARNING; o.mean = mean; o.base = base_; o.noise = noise_;
      return o;
    }
    if (dead_ && (uint32_t)(now - deadSince_) >= 20000) { relearn(); o.aoUsable = false; o.why = FW_LEARNING; return o; }
    if (dead_ || polarityBad_) { o.aoUsable = false; o.why = dead_ ? FW_NOISY : FW_POLARITY; o.mean = mean; o.base = base_; return o; }

    // ---- deviation toward "flame" ----
    const float sgn = (FLAME_AO_DROPS_WITH_FLAME ? 1.0f : -1.0f) * (flipped_ ? -1.0f : 1.0f);
    const float dev = sgn * (base_ - mean);
    const float enterBase = fmaxf(tune.minDeltaMv, FLAME_ENTER_SIGMAS * noise_);
    const float enter = doOn ? fmaxf(4.0f * noise_, FLAME_DO_ENTER_RATIO * tune.minDeltaMv) : enterBase;
    const float exitT = enter * FLAME_EXIT_RATIO;
    const float holdT = sticky_ ? 0.3f * enterBase : exitT;
    if (!active_ && dev >= enter) active_ = true;
    else if (active_ && dev < holdT && !(sticky_ && doOn)) active_ = false;
    if (!sticky_ && ((!active_ && fabsf(dev) < enterBase) || uncorrMs_ >= 3000)) noise_ += 0.1f * (fmaxf(FLAME_NOISE_FLOOR_MV, sd) - noise_);

    // ---- polarity check against DO ----
    if (doOn) { doMs_ += dt; } else { doMs_ = 0; }
    if (doOn && doMs_ >= 1500 && dev <= -fmaxf(4.0f * noise_, 0.5f * tune.minDeltaMv)) {
      o.events |= FE_POLARITY_BAD; o.aoUsable = false;
      if (!flipped_) { flipped_ = true; active_ = false; sticky_ = false; confirmMs_ = 0; doMs_ = 0; histN_ = 0; o.why = FW_LEARNING; return o; }
      polarityBad_ = true; o.why = FW_POLARITY; return o;
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
    const float h = fmaxf(tune.flickerSigmas * noise_, 0.03f * tune.flickerSigmas * (dev > 0 ? dev : 0));
    int rev = 0; int state = 0;
    for (int k = 0; k < count_; k++) {
      const float v = buf_[(idx_ + (N - count_) + k) % N] - mean;     // oldest -> newest
      if (v > h * 0.5f) { if (state == -1) rev++; state = 1; }
      else if (v < -h * 0.5f) { if (state == 1) rev++; state = -1; }
    }
    int doTrans = 0;
    for (int k = 1; k < dcount_; k++) {
      const int a = dbuf_[(didx_ + (N - dcount_) + k - 1) % N], b = dbuf_[(didx_ + (N - dcount_) + k) % N];
      if (a != b) doTrans++;
    }
    const bool doChatter = doTrans >= FLAME_DO_MIN_TRANSITIONS && doTrans <= FLAME_DO_MAX_TRANSITIONS;
    const bool flickerNow = active_ && rev >= tune.minReversals && rev <= FLAME_MAX_REVERSALS;
    if (flickerNow) { if (flickerHits_ < 100) flickerHits_++; } else flickerHits_ = 0;
    const bool flickerSustained = flickerHits_ >= FLAME_FLICKER_CYCLES;
    o.reversals = rev;

    // ---- corroboration ----
    const bool corroborated = sticky_ || (flickerSustained && (!tune.requireDo || doOn || doChatter));
    const bool weakEvidence = flickerSustained;     // flicker without rise/DO: visible (warning level) but never an emergency

    // ---- strength ----
    const float fullDelta = fmaxf(1.0f, fabsf(base_) * FLAME_FULL_FRACTION);
    int pctRaw = (int)lroundf(dev / fullDelta * 100.0f);
    if (pctRaw < 0) pctRaw = 0;
    if (pctRaw > 100) pctRaw = 100;

    // ---- confirmation (only added delay: FLAME_CONFIRM_MS) ----
    const bool strong = active_;
    if (strong && corroborated) confirmMs_ += dt; else if (!sticky_) confirmMs_ = 0;
    bool critical = sticky_ || confirmMs_ >= tune.confirmMs;
    if (critical && !sticky_) { sticky_ = true; o.events |= FE_CRITICAL_ON; }
    if (sticky_) {                         // stays critical while the flame signal is still there
      if (active_) belowMs_ = 0; else { belowMs_ += dt; if (belowMs_ >= FLAME_RELEASE_MS) { sticky_ = false; confirmMs_ = 0; critical = false; belowMs_ = 0; } }
    }

    // ---- baseline: follow slowly while quiet; absorb steady, uncorroborated changes; never absorb fire-like ones ----
    if (!active_ && !riseRecent && !sticky_ && !flickerSustained && sd < 3.0f * noise_ + 1.0f) base_ += FLAME_BASELINE_ALPHA * (mean - base_);
    if (active_ && !corroborated && !flickerSustained) {
      uncorrMs_ += dt;
      if (uncorrMs_ >= tune.rebaseMs) { base_ = mean; uncorrMs_ = 0; o.events |= FE_REBASED; active_ = false; }
    } else uncorrMs_ = 0;

    // ---- DO alone (AO quiet): WARNING at most ----
    int pct;
    if (!active_) pct = 0;                                  // DO alone / nothing: ignored while AO is usable
    else if (!corroborated && weakEvidence) pct = pctRaw < SIGNAL_MIN_PCT ? SIGNAL_MIN_PCT : (pctRaw < FLAME_CRIT_PCT ? pctRaw : FLAME_CRIT_PCT - 1);
    else if (!corroborated) pct = pctRaw < SIGNAL_MIN_PCT ? pctRaw : SIGNAL_MIN_PCT - 1;   // steady change: silent
    else if (!critical) pct = pctRaw < SIGNAL_MIN_PCT ? SIGNAL_MIN_PCT : (pctRaw < FLAME_CRIT_PCT ? pctRaw : FLAME_CRIT_PCT - 1);
    else pct = pctRaw < FLAME_CRIT_PCT ? FLAME_CRIT_PCT : pctRaw;

    o.pct = pct; o.critical = critical; o.aoUsable = true;
    o.base = base_; o.dev = dev; o.noise = noise_; o.enter = enter;
    o.corroborated = corroborated; o.flicker = flickerSustained; o.rise = riseRecent; o.why = FW_OK;
    return o;
  }

  bool baselineReady() const { return baseReady_; }
  bool doChatterNow() const {
    int tr = 0;
    for (int k = 1; k < dcount_; k++) {
      const int a = dbuf_[(didx_ + (N - dcount_) + k - 1) % N], b = dbuf_[(didx_ + (N - dcount_) + k) % N];
      if (a != b) tr++;
    }
    return tr >= FLAME_DO_MIN_TRANSITIONS && tr <= FLAME_DO_MAX_TRANSITIONS;
  }

 private:
  float buf_[N] = {};
  int idx_ = 0, count_ = 0;
  int flickerHits_ = 0;
  uint8_t dbuf_[N] = {}; int didx_ = 0, dcount_ = 0;
  bool flipped_ = false, baseReady_ = false, dead_ = false, polarityBad_ = false, active_ = false, sticky_ = false;
  float base_ = 0, noise_ = FLAME_NOISE_FLOOR_MV;
  uint32_t deadSince_ = 0, initStart_ = 0, initQuietSince_ = 0; float initSum_ = 0, initSd_ = 0; int initN_ = 0;
  float devHist_[FLAME_RISE_WINDOW_MS / SENSOR_CYCLE_MS] = {}; int histN_ = 0;
  uint32_t riseUntil_ = 0, doMs_ = 0, confirmMs_ = 0, belowMs_ = 0, uncorrMs_ = 0;
};
