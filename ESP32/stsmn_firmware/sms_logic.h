// Pure SMS / GSM logic (no Arduino dependency) so it can be tested on a PC.
#pragma once
#include <stdint.h>
#include <stddef.h>
#include <stdio.h>
#include <string.h>

namespace sms {

// "+919876543210" or "9876543210": optional '+', then 8..15 digits. The placeholder "+91XXXXXXXXXX" is NOT valid.
inline bool isValidPhone(const char* p) {
  if (!p) return false;
  size_t i = 0;
  if (p[0] == '+') i = 1;
  int digits = 0;
  for (; p[i]; i++) {
    if (p[i] < '0' || p[i] > '9') return false;
    digits++;
  }
  return digits >= 8 && digits <= 15;
}

// Plain-ASCII alert (one 160-character SMS). The position is included ONLY when the GPS has a real fix.
// Returns false if it did not fit.
inline bool buildAlertText(char* out, size_t n, const char* id, const char* status, int risk,
                           bool tempValid, float tempC, bool gpsValid, double lat, double lng) {
  int w = snprintf(out, n, "STSMN %s %s risk %d/100", id, status, risk);
  if (w < 0 || (size_t)w >= n) return false;
  size_t used = (size_t)w;
  if (tempValid) {
    w = snprintf(out + used, n - used, " temp %.1fC", (double)tempC);
    if (w < 0 || (size_t)w >= n - used) return false;
    used += (size_t)w;
  }
  if (gpsValid) w = snprintf(out + used, n - used, " https://maps.google.com/?q=%.6f,%.6f", lat, lng);
  else w = snprintf(out + used, n - used, " GPS: no fix");
  if (w < 0 || (size_t)w >= n - used) return false;
  return (used + (size_t)w) <= 160;
}

// Decides WHEN to send an alert SMS. level: 0 SAFE, 1 WARNING, 2 EMERGENCY.
// - a new alert episode (level rises to the threshold) sends once,
// - never more often than minIntervalMs (an episode that starts too soon waits),
// - while the alarm persists, a reminder every repeatMs,
// - a failed send is retried after minIntervalMs via retry().
class SmsGate {
 public:
  SmsGate(bool onWarning, uint32_t minIntervalMs, uint32_t repeatMs)
      : thr_(onWarning ? 1 : 2), minMs_(minIntervalMs), repeatMs_(repeatMs) {}
  bool update(int level, uint32_t now) {
    if (level < thr_) { wasActive_ = false; pending_ = false; return false; }
    if (!wasActive_) { wasActive_ = true; pending_ = true; }
    if (!pending_ && sentOnce_ && (uint32_t)(now - lastSent_) >= repeatMs_) pending_ = true;
    if (pending_ && (!sentOnce_ || (uint32_t)(now - lastSent_) >= minMs_)) {
      pending_ = false; sentOnce_ = true; lastSent_ = now;
      return true;
    }
    return false;
  }
  void retry() { if (wasActive_) pending_ = true; }
 private:
  int thr_;
  uint32_t minMs_, repeatMs_;
  bool wasActive_ = false, pending_ = false, sentOnce_ = false;
  uint32_t lastSent_ = 0;
};

// "+CSQ: 18,0" -> 18 (0..31, 99 = unknown). -1 if the reply has no +CSQ line.
inline int parseCsq(const char* resp) {
  const char* p = resp ? strstr(resp, "+CSQ:") : nullptr;
  if (!p) return -1;
  p += 5;
  while (*p == ' ') p++;
  if (*p < '0' || *p > '9') return -1;
  int v = 0;
  while (*p >= '0' && *p <= '9') { v = v * 10 + (*p - '0'); p++; }
  return v;
}

// "+CREG: 0,1" (home) or "+CREG: 0,5" (roaming) -> true. Anything else (searching, denied, no reply) -> false.
inline bool parseCregRegistered(const char* resp) {
  const char* p = resp ? strstr(resp, "+CREG:") : nullptr;
  if (!p) return false;
  p += 6;
  while (*p == ' ') p++;
  const char* comma = strchr(p, ',');
  const char* q = comma ? comma + 1 : p;      // "+CREG: n,stat" or the unsolicited form "+CREG: stat"
  while (*q == ' ') q++;
  return *q == '1' || *q == '5';
}

}  // namespace sms
