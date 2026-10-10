// Small, dependency-free helpers (filters, debounce). Safe to unit-test on a PC.
#pragma once
#include <stdint.h>
#include <stddef.h>

// Fixed-window moving average (smooths noisy analog readings).
template <size_t N>
class MovingAverage {
 public:
  void push(float v) {
    if (count_ == N) sum_ -= buf_[idx_]; else count_++;
    buf_[idx_] = v;
    sum_ += v;
    idx_ = (idx_ + 1) % N;
  }
  float value() const { return count_ ? sum_ / (float)count_ : 0.0f; }
  bool hasData() const { return count_ > 0; }
  void reset() { idx_ = 0; count_ = 0; sum_ = 0; }
 private:
  float buf_[N] = {0};
  size_t idx_ = 0, count_ = 0;
  float sum_ = 0;
};

// Sensor validity with hysteresis: a single bad sample never invalidates a
// sensor, and a single good sample never re-validates it.
class ValidityFilter {
 public:
  ValidityFilter(uint8_t failLimit, uint8_t recoverLimit)
      : fail_(failLimit), recover_(recoverLimit) {}
  void prime(bool ok) { valid_ = ok; good_ = 0; bad_ = 0; }
  bool update(bool sampleOk) {
    if (sampleOk) {
      bad_ = 0;
      if (good_ < 255) good_++;
      if (!valid_ && good_ >= recover_) valid_ = true;
    } else {
      good_ = 0;
      if (bad_ < 255) bad_++;
      if (valid_ && bad_ >= fail_) valid_ = false;
    }
    return valid_;
  }
  bool valid() const { return valid_; }
 private:
  uint8_t fail_, recover_, good_ = 0, bad_ = 0;
  bool valid_ = false;
};

// "Condition must hold continuously for holdMs" timer.
class HoldTimer {
 public:
  bool update(bool cond, uint32_t now, uint32_t holdMs) {
    if (!cond) { active_ = false; return false; }
    if (!active_) { active_ = true; since_ = now; }
    return (uint32_t)(now - since_) >= holdMs;
  }
  void reset() { active_ = false; }
 private:
  bool active_ = false;
  uint32_t since_ = 0;
};
