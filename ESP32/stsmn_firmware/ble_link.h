// BLE link (read-only monitoring + saving the alert number). Needs the BLE library of the ESP32 core.
// The phone is a VIEWER: the only writable thing is the SMS number, and only with the device password (same as the Wi-Fi one).
// Relay, thresholds and protection are never reachable from BLE. Everything runs in the low-priority comm task.
#pragma once
#include <Arduino.h>
#include "config.h"

#if ENABLE_BLE
class BleLink : public BLEServerCallbacks, public BLECharacteristicCallbacks {
 public:
  void begin() {
    BLEDevice::init(BLE_DEVICE_NAME);
    server_ = BLEDevice::createServer();
    server_->setCallbacks(this);
    BLEService* svc = server_->createService(BLE_SERVICE_UUID);
    tx_ = svc->createCharacteristic(BLE_TX_UUID, BLECharacteristic::PROPERTY_NOTIFY);
    tx_->addDescriptor(new BLE2902());
    BLECharacteristic* rx = svc->createCharacteristic(BLE_RX_UUID, BLECharacteristic::PROPERTY_WRITE | BLECharacteristic::PROPERTY_WRITE_NR);
    rx->setCallbacks(this);
    svc->start();
    BLEAdvertising* adv = BLEDevice::getAdvertising();
    adv->addServiceUUID(BLE_SERVICE_UUID);
    adv->setScanResponse(true);
    BLEDevice::startAdvertising();
    started_ = true;
  }
  bool connected() const { return started_ && connected_; }

  // Sends "<prefix><text>\n" in 20-byte notifications (works with the default ATT MTU).
  void sendLine(const char* prefix, const char* text) {
    if (!connected()) return;
    char line[1100];
    const int n = snprintf(line, sizeof(line), "%s%s\n", prefix, text);
    if (n <= 0 || n >= (int)sizeof(line)) return;
    for (int off = 0; off < n && connected_; off += 20) {
      const int len = (n - off) < 20 ? (n - off) : 20;
      tx_->setValue((uint8_t*)(line + off), len);
      tx_->notify();
      delay(8);
    }
  }

  // Returns true once per received line (copied out, zero-terminated).
  bool takeCommand(char* out, size_t n) {
    if (!lineReady_) return false;
    strlcpy(out, rxLine_, n);
    lineReady_ = false;
    return true;
  }

  void onConnect(BLEServer*) override { connected_ = true; }
  void onDisconnect(BLEServer*) override { connected_ = false; BLEDevice::startAdvertising(); }
  void onWrite(BLECharacteristic* c) override {
    auto v = c->getValue();                       // std::string (core 2.x) or String (core 3.x)
    const size_t len = v.length();
    const char* d = v.c_str();
    for (size_t i = 0; i < len; i++) {
      const char ch = d[i];
      if (ch == '\n' || ch == '\r') {
        if (rxLen_ > 0 && !lineReady_) { rxLine_[rxLen_] = 0; lineReady_ = true; }
        rxLen_ = 0;
      } else if (rxLen_ < sizeof(rxLine_) - 1) {
        rxLine_[rxLen_++] = ch;
      } else {
        rxLen_ = 0;                               // too long: drop the line
      }
    }
  }

 private:
  BLEServer* server_ = nullptr;
  BLECharacteristic* tx_ = nullptr;
  volatile bool connected_ = false, started_ = false, lineReady_ = false;
  char rxLine_[96] = {};
  size_t rxLen_ = 0;
};
#endif
