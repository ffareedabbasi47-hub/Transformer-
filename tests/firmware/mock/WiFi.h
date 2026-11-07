#pragma once
#include "Arduino.h"
#define WIFI_AP 2
struct WiFiClass {
  void mode(int) {}
  bool softAPConfig(IPAddress, IPAddress, IPAddress) { return true; }
  bool softAP(const char*, const char*, int = 1, int = 0, int = 4) { return true; }
  IPAddress softAPIP() { return IPAddress(192, 168, 4, 1); }
  void setTxPower(int) {}
  int softAPgetStationNum() { return 0; }
};
static WiFiClass WiFi;
#define WIFI_POWER_11dBm 44
