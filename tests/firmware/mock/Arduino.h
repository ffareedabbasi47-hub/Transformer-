// Minimal HOST MOCK of the Arduino-ESP32 API: lets the sketch be syntax/type-checked and logic-tested on a PC.
// It is NOT the real ESP32 core: a pass here does NOT prove the firmware builds for the ESP32.
#pragma once
#include <stdint.h>
#include <stddef.h>
#include <stdio.h>
#include <stdarg.h>
#include <string.h>
#include <math.h>
#include <string>
#ifndef ESP_ARDUINO_VERSION_MAJOR
#define ESP_ARDUINO_VERSION_MAJOR 2
#endif
#define HIGH 1
#define LOW 0
#define INPUT 0x01
#define OUTPUT 0x03
#define INPUT_PULLUP 0x05
#define OPEN_DRAIN 0x10   /* real ESP32 core defines this macro: sketch must not use the name */
#define PULLUP 0x04
#define PULLDOWN 0x08
#define INPUT_PULLDOWN 0x09
typedef enum { ADC_0db, ADC_2_5db, ADC_6db, ADC_11db } adc_attenuation_t;
struct MockHw {
  int pinMode_[64] = {0}; int level[64] = {0}; int writes[64] = {0}; int firstWriteBeforeMode[64] = {0};
  uint32_t mv[64] = {0}; uint32_t now = 0;
};
inline MockHw& hw() { static MockHw h; return h; }
inline void pinMode(int p, int m) { hw().pinMode_[p] = m; }
inline void digitalWrite(int p, int v) { if (!hw().pinMode_[p]) hw().firstWriteBeforeMode[p] = 1; hw().level[p] = v; hw().writes[p]++; }
inline int digitalRead(int p) { return hw().level[p]; }
inline void analogReadResolution(int) {}
inline void analogSetAttenuation(adc_attenuation_t) {}
inline uint32_t analogReadMilliVolts(int p) { return hw().mv[p]; }
inline uint32_t millis() { return hw().now; }
inline uint32_t micros() { return hw().now * 1000u; }
inline void delay(uint32_t) {}
class String {
 public:
  String(const char* s = "") : s_(s) {}
  bool operator==(const char* o) const { return s_ == o; }
  size_t length() const { return s_.size(); }
  const char* c_str() const { return s_.c_str(); }
  long toInt() const { return atol(s_.c_str()); }
  float toFloat() const { return (float)atof(s_.c_str()); }
 private: std::string s_;
};
class IPAddress {
 public:
  IPAddress(uint8_t a = 0, uint8_t b = 0, uint8_t c = 0, uint8_t d = 0) { v[0]=a; v[1]=b; v[2]=c; v[3]=d; }
  uint8_t v[4];
};
#define SERIAL_8N1 0x800001c
struct HardwareSerial {
  void begin(unsigned long) {}
  void begin(unsigned long, uint32_t, int, int) {}
  int available() { return 0; }
  int read() { return -1; }
  size_t write(uint8_t) { return 1; }
  void print(const char* s, int = 0) { fputs(s, stdout); }
  void print(const IPAddress&) {}
  void println(const char* s = "") { puts(s); }
  void println(const IPAddress&) { puts("ip"); }
  int printf(const char* f, ...) __attribute__((format(printf, 2, 3))) { va_list a; va_start(a, f); int n = vprintf(f, a); va_end(a); return n; }
};
static HardwareSerial Serial;
static HardwareSerial Serial1;
static HardwareSerial Serial2;
struct EspClass { uint32_t getFreeHeap() { return 123456; } void restart() {} };
static EspClass ESP;
inline size_t strlcpy(char* d, const char* s, size_t n) { size_t l = strlen(s); if (n) { size_t c = l >= n ? n - 1 : l; memcpy(d, s, c); d[c] = 0; } return l; }
