#pragma once
#include "OneWire.h"
#define DEVICE_DISCONNECTED_C -127
struct MockDs { float temp = 25.0f; };
inline MockDs& mockDs() { static MockDs d; return d; }
class DallasTemperature {
 public:
  explicit DallasTemperature(OneWire*) {}
  void begin() {}
  void setResolution(uint8_t) {}
  void setWaitForConversion(bool) {}
  void requestTemperatures() {}
  float getTempCByIndex(uint8_t) { return mockDs().temp; }
};
