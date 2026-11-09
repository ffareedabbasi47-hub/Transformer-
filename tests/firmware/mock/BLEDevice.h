#pragma once
// Host mock of the ESP32 BLE library: only what ble_link.h touches (type/signature check, no radio).
#include <string>
#include <stdint.h>
class BLECharacteristic;
class BLEServer;
class BLEServerCallbacks { public: virtual ~BLEServerCallbacks() {} virtual void onConnect(BLEServer*) {} virtual void onDisconnect(BLEServer*) {} };
class BLECharacteristicCallbacks { public: virtual ~BLECharacteristicCallbacks() {} virtual void onWrite(BLECharacteristic*) {} };
class BLE2902 {};
class BLECharacteristic {
 public:
  static const int PROPERTY_NOTIFY = 1, PROPERTY_WRITE = 2, PROPERTY_WRITE_NR = 4;
  void addDescriptor(BLE2902*) {}
  void setCallbacks(BLECharacteristicCallbacks*) {}
  void setValue(uint8_t*, size_t) {}
  void notify() {}
  std::string getValue() { return std::string(); }
};
class BLEService { public: BLECharacteristic* createCharacteristic(const char*, int) { static BLECharacteristic c; return &c; } void start() {} };
class BLEServer { public: void setCallbacks(BLEServerCallbacks*) {} BLEService* createService(const char*) { static BLEService s; return &s; } };
class BLEAdvertising { public: void addServiceUUID(const char*) {} void setScanResponse(bool) {} };
class BLEDevice {
 public:
  static void init(const char*) {}
  static BLEServer* createServer() { static BLEServer s; return &s; }
  static BLEAdvertising* getAdvertising() { static BLEAdvertising a; return &a; }
  static void startAdvertising() {}
};
