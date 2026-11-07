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

// Turns what a person types ("75050 45948", "+91-75050-45948", "917505045948") into "+<digits>".
// 10 digits starting 6-9 are taken as an Indian mobile (+91). Returns false if the result is not a plausible number.
inline bool normalizePhone(const char* in, char* out, size_t n) {
  char d[24]; size_t k = 0; bool plus = false;
  if (!in || n < 6) return false;
  for (const char* c = in; *c; c++) {
    if (*c == '+' && k == 0 && !plus) { plus = true; continue; }
    if (*c >= '0' && *c <= '9') { if (k >= 18) return false; d[k++] = *c; continue; }
    if (*c == ' ' || *c == '-' || *c == '(' || *c == ')') continue;
    return false;
  }
  d[k] = 0;
  if (!plus && k == 10 && d[0] >= '6' && d[0] <= '9') { char t[32]; snprintf(t, sizeof(t), "91%s", d); strcpy(d, t); k += 2; }
  else if (!plus && k == 12 && d[0] == '9' && d[1] == '1') { /* already has the country code */ }
  else if (!plus && k == 11 && d[0] == '0') { char t[32]; snprintf(t, sizeof(t), "91%s", d + 1); strcpy(d, t); k = strlen(d); }
  if (!plus && !(k == 12 && d[0] == '9' && d[1] == '1')) return false;   // without "+" only the forms above are understood
  if (k < 8 || k > 15) return false;
  if ((size_t)snprintf(out, n, "+%s", d) >= n) return false;
  return isValidPhone(out);
}

// "+917505045948" -> "+91*******48" (never the whole number over Wi-Fi; just enough for the person to recognise it).
inline void maskPhone(const char* p, char* out, size_t n) {
  size_t len = strlen(p);
  if (n == 0) return;
  if (len < 6 || len + 1 > n) { out[0] = 0; return; }
  for (size_t i = 0; i < len; i++) out[i] = (i < 3 || i >= len - 2) ? p[i] : '*';
  out[len] = 0;
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
