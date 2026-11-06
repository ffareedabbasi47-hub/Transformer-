// NMEA parser for the GPS module (NEO-6M and compatible). No Arduino dependency: testable on a PC.
// Only sentences with a correct checksum are used. A position is reported only while the module says "fix".
#pragma once
#include <stdint.h>
#include <stddef.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>

struct GpsFix {
  bool valid = false;
  double lat = 0, lng = 0;   // decimal degrees, + = N/E, - = S/W
  int sats = 0;
};

class NmeaParser {
 public:
  // Feed one received character. Returns true when a complete, checksum-valid RMC/GGA sentence was processed.
  bool feed(char c) {
    if (c == '$') { len_ = 0; in_ = true; return false; }
    if (!in_) return false;
    if (c == '\r' || c == '\n') {
      in_ = false;
      if (len_ == 0) return false;
      buf_[len_] = 0;
      return handle();
    }
    if (len_ >= sizeof(buf_) - 1) { in_ = false; return false; }   // too long: junk
    buf_[len_++] = c;
    return false;
  }
  const GpsFix& fix() const { return fix_; }

  // XOR of the characters between '$' and '*'.
  static uint8_t checksum(const char* body, size_t n) {
    uint8_t x = 0;
    for (size_t i = 0; i < n; i++) x ^= (uint8_t)body[i];
    return x;
  }

 private:
  char buf_[110];
  size_t len_ = 0;
  bool in_ = false;
  GpsFix fix_;
  bool rmcOk_ = false, ggaOk_ = false;

  static int hexv(char c) {
    if (c >= '0' && c <= '9') return c - '0';
    if (c >= 'A' && c <= 'F') return c - 'A' + 10;
    if (c >= 'a' && c <= 'f') return c - 'a' + 10;
    return -1;
  }

  // "4807.038" + 'N' -> 48.1173
  static bool coord(const char* v, char hemi, bool isLat, double& out) {
    if (!v || !*v) return false;
    char* end = nullptr;
    const double raw = strtod(v, &end);
    if (end == v || raw < 0) return false;
    const double deg = floor(raw / 100.0);
    const double min = raw - deg * 100.0;
    if (min >= 60.0) return false;
    double d = deg + min / 60.0;
    if (hemi == 'S' || hemi == 'W') d = -d;
    else if (hemi != 'N' && hemi != 'E') return false;
    if (isLat ? (fabs(d) > 90.0) : (fabs(d) > 180.0)) return false;
    out = d;
    return true;
  }

  bool handle() {
    char* star = strrchr(buf_, '*');
    if (!star || star == buf_ || strlen(star) < 3) return false;
    const int h1 = hexv(star[1]), h2 = hexv(star[2]);
    if (h1 < 0 || h2 < 0) return false;
    if (checksum(buf_, (size_t)(star - buf_)) != (uint8_t)(h1 * 16 + h2)) return false;   // corrupted: ignore
    *star = 0;

    char* f[16];
    int n = 0;
    char* p = buf_;
    while (n < 16) {                      // split on commas in place (empty fields stay empty strings)
      f[n++] = p;
      char* comma = strchr(p, ',');
      if (!comma) break;
      *comma = 0;
      p = comma + 1;
    }
    const size_t tl = strlen(f[0]);
    if (tl < 5) return false;
    const char* type = f[0] + tl - 3;     // GPRMC / GNRMC -> "RMC"

    if (!strcmp(type, "RMC") && n >= 7) {
      double la, lo;
      const bool ok = f[2][0] == 'A' && coord(f[3], f[4][0], true, la) && coord(f[5], f[6][0], false, lo) &&
                      !(la == 0 && lo == 0);
      rmcOk_ = ok;
      if (ok) { fix_.lat = la; fix_.lng = lo; }
    } else if (!strcmp(type, "GGA") && n >= 8) {
      double la, lo;
      const int quality = atoi(f[6]);
      const bool ok = quality > 0 && coord(f[2], f[3][0], true, la) && coord(f[4], f[5][0], false, lo) &&
                      !(la == 0 && lo == 0);
      ggaOk_ = ok;
      if (ok) { fix_.lat = la; fix_.lng = lo; }
      fix_.sats = atoi(f[7]);
    } else {
      return false;
    }
    fix_.valid = rmcOk_ || ggaOk_;
    return true;
  }
};
