// Host test (no hardware): NMEA parser, SMS text, SMS gate, AT-reply parsers.
// g++ -std=c++17 -Wall -I../ESP32/stsmn_firmware gps_sms_test.cpp -o gps_sms_test && ./gps_sms_test
#include <stdio.h>
#include <string>
#include <math.h>
#include "gps_nmea.h"
#include "sms_logic.h"

static int fails = 0, checks = 0;
#define CHECK(c) do { checks++; if (!(c)) { fails++; printf("FAIL line %d: %s\n", __LINE__, #c); } } while (0)

static std::string sentence(const std::string& body) {
  char tail[8];
  snprintf(tail, sizeof(tail), "*%02X\r\n", NmeaParser::checksum(body.data(), body.size()));
  return "$" + body + tail;
}
static bool feedAll(NmeaParser& p, const std::string& s) { bool any = false; for (char c : s) any |= p.feed(c); return any; }

int main() {
  // ---- GPS ----
  { NmeaParser p;                                           // the well-known NMEA example sentences
    CHECK(feedAll(p, "$GPRMC,123519,A,4807.038,N,01131.000,E,022.4,084.4,230394,003.1,W*6A\r\n"));
    CHECK(p.fix().valid); CHECK(fabs(p.fix().lat - 48.1173) < 1e-4); CHECK(fabs(p.fix().lng - 11.516667) < 1e-4); }
  { NmeaParser p;
    CHECK(feedAll(p, "$GPGGA,123519,4807.038,N,01131.000,E,1,08,0.9,545.4,M,46.9,M,,*47\r\n"));
    CHECK(p.fix().valid); CHECK(p.fix().sats == 8); }
  { NmeaParser p;                                           // Lucknow-like position, southern/western hemispheres too
    CHECK(feedAll(p, sentence("GPRMC,101010,A,2650.802,N,08056.772,E,0.0,0.0,061126,,,A")));
    CHECK(fabs(p.fix().lat - 26.84670) < 1e-4); CHECK(fabs(p.fix().lng - 80.94620) < 1e-4);
    NmeaParser q; feedAll(q, sentence("GNRMC,101010,A,3351.000,S,15112.000,W,0,0,061126,,,A"));
    CHECK(q.fix().valid); CHECK(q.fix().lat < 0 && q.fix().lng < 0); }
  { NmeaParser p;                                           // no fix: status V -> never a position
    feedAll(p, sentence("GPRMC,101010,V,,,,,,,061126,,,N"));
    CHECK(!p.fix().valid); }
  { NmeaParser p;                                           // GGA quality 0 = no fix
    feedAll(p, sentence("GPGGA,101010,,,,,0,00,99.9,,,,,,"));
    CHECK(!p.fix().valid); }
  { NmeaParser p;                                           // corrupted checksum is ignored
    CHECK(!feedAll(p, "$GPRMC,123519,A,4807.038,N,01131.000,E,022.4,084.4,230394,003.1,W*00\r\n"));
    CHECK(!p.fix().valid); }
  { NmeaParser p;                                           // 0,0 ("null island") is never accepted as a position
    feedAll(p, sentence("GPRMC,101010,A,0000.000,N,00000.000,E,0,0,061126,,,A"));
    CHECK(!p.fix().valid); }
  { NmeaParser p;                                           // out-of-range / garbage
    feedAll(p, sentence("GPRMC,101010,A,9999.000,N,18100.000,E,0,0,061126,,,A"));
    CHECK(!p.fix().valid);
    for (int i = 0; i < 300; i++) p.feed('x');
    feedAll(p, "$$$***\r\n"); CHECK(!p.fix().valid); }
  { NmeaParser p;                                           // fix is lost when the module reports V after a good fix
    feedAll(p, sentence("GPRMC,1,A,2650.802,N,08056.772,E,0,0,061126,,,A")); CHECK(p.fix().valid);
    feedAll(p, sentence("GPRMC,2,V,,,,,,,061126,,,N")); CHECK(!p.fix().valid); }
  { NmeaParser p;                                           // sentence split across arbitrary chunks still works
    std::string s = sentence("GPRMC,101010,A,2650.802,N,08056.772,E,0,0,061126,,,A");
    bool any = false; for (char c : s) any |= p.feed(c); CHECK(any && p.fix().valid); }

  // ---- phone numbers ----
  CHECK(sms::isValidPhone("+919876543210")); CHECK(sms::isValidPhone("9876543210"));
  CHECK(!sms::isValidPhone("+91XXXXXXXXXX")); CHECK(!sms::isValidPhone("")); CHECK(!sms::isValidPhone(nullptr));
  CHECK(!sms::isValidPhone("+12")); CHECK(!sms::isValidPhone("98765 43210")); CHECK(!sms::isValidPhone("+9198765432101234567"));

  // ---- SMS text ----
  { char t[161];
    CHECK(sms::buildAlertText(t, sizeof(t), "TR-001", "EMERGENCY", 92, true, 71.24f, true, 26.8467, 80.9462));
    CHECK(std::string(t) == "STSMN TR-001 EMERGENCY risk 92/100 temp 71.2C https://maps.google.com/?q=26.846700,80.946200");
    CHECK(strlen(t) <= 160);
    CHECK(sms::buildAlertText(t, sizeof(t), "TR-001", "EMERGENCY", 92, false, 0, false, 0, 0));
    CHECK(std::string(t) == "STSMN TR-001 EMERGENCY risk 92/100 GPS: no fix");     // no fake position, no fake temperature
    char tiny[20]; CHECK(!sms::buildAlertText(tiny, sizeof(tiny), "TR-001", "EMERGENCY", 92, true, 71.2f, true, 26.8, 80.9)); }

  // ---- SMS gate ----
  { sms::SmsGate g(false, 120000, 1800000);
    CHECK(!g.update(0, 0)); CHECK(!g.update(1, 1000));                 // SAFE / WARNING: nothing (warning SMS off)
    CHECK(g.update(2, 2000));                                          // entering EMERGENCY: send once
    CHECK(!g.update(2, 3000)); CHECK(!g.update(2, 60000));             // still emergency: no spam
    CHECK(g.update(2, 2000 + 1800000));                                // reminder after 30 min
    CHECK(!g.update(2, 2000 + 1800000 + 10)); }
  { sms::SmsGate g(false, 120000, 1800000);
    CHECK(g.update(2, 1000));
    g.update(0, 5000);                                                 // recovered
    CHECK(!g.update(2, 10000));                                        // new episode too soon: waits (rate limit)
    CHECK(!g.update(2, 100000));
    CHECK(g.update(2, 1000 + 120000)); }                               // ... and is sent as soon as the interval passed
  { sms::SmsGate g(false, 120000, 1800000);
    CHECK(g.update(2, 1000)); g.retry();                               // failed send -> retried, but throttled
    CHECK(!g.update(2, 2000)); CHECK(g.update(2, 1000 + 120000)); }
  { sms::SmsGate g(true, 120000, 1800000);
    CHECK(g.update(1, 1000)); }                                        // warning SMS enabled
  { sms::SmsGate g(false, 120000, 1800000);                            // millis() wrap-around
    CHECK(g.update(2, 0xFFFFFF00u)); CHECK(!g.update(2, 50)); }

  // ---- AT replies ----
  CHECK(sms::parseCsq("\r\n+CSQ: 18,0\r\n\r\nOK\r\n") == 18); CHECK(sms::parseCsq("+CSQ: 99,99") == 99);
  CHECK(sms::parseCsq("ERROR") == -1); CHECK(sms::parseCsq(nullptr) == -1);
  CHECK(sms::parseCregRegistered("+CREG: 0,1\r\nOK")); CHECK(sms::parseCregRegistered("+CREG: 0,5\r\nOK"));
  CHECK(!sms::parseCregRegistered("+CREG: 0,2\r\nOK")); CHECK(!sms::parseCregRegistered("+CREG: 0,3\r\nOK"));
  CHECK(!sms::parseCregRegistered("")); CHECK(!sms::parseCregRegistered(nullptr));

  printf("%d checks, %d failed\n", checks, fails);
  return fails ? 1 : 0;
}
