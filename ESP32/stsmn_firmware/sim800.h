// SIM800L driver: sends SMS over AT commands. Blocking calls (up to ~40 s) -> only used from the comm task,
// NEVER from the protection task. Arduino-ESP32 only (uses HardwareSerial).
#pragma once
#include "sms_logic.h"

class SimModule {
 public:
  // Starts the UART and checks that the module answers. Returns true when it is ready to send SMS.
  bool begin(HardwareSerial& ser, int rxPin, int txPin, uint32_t baud) {
    s_ = &ser;
    ser.begin(baud, SERIAL_8N1, rxPin, txPin);
    delay(300);
    ok_ = false;
    for (int i = 0; i < 4 && !ok_; i++) ok_ = cmd("AT", "OK", 1000);
    if (!ok_) return false;
    cmd("ATE0", "OK", 1000);                // no echo
    ok_ = cmd("AT+CMGF=1", "OK", 2000);     // text mode
    if (ok_) cmd("AT+CSCS=\"GSM\"", "OK", 2000);
    return ok_;
  }
  bool ready() const { return ok_; }

  int signal() {                            // 0..31, 99 unknown, -1 no answer
    if (!ok_ || !cmd("AT+CSQ", "OK", 2000)) return -1;
    return sms::parseCsq(rx_);
  }
  bool registered() {
    if (!ok_ || !cmd("AT+CREG?", "OK", 2000)) return false;
    return sms::parseCregRegistered(rx_);
  }

  bool sendSms(const char* number, const char* text) {
    if (!ok_ || !s_) return false;
    flushInput();
    s_->print("AT+CMGS=\"");
    s_->print(number);
    s_->print("\"\r");
    if (!waitFor(">", nullptr, 8000)) { s_->write((uint8_t)0x1B); return false; }   // ESC cancels a half-open SMS
    s_->print(text);
    s_->write((uint8_t)0x1A);                                                      // Ctrl-Z = send
    return waitFor("+CMGS:", "ERROR", 40000);
  }

 private:
  HardwareSerial* s_ = nullptr;
  bool ok_ = false;
  char rx_[200] = "";

  void flushInput() { while (s_ && s_->available()) s_->read(); }

  // Collects the reply in rx_ until `want` appears (true), `bad` appears or the time runs out (false).
  bool waitFor(const char* want, const char* bad, uint32_t timeoutMs) {
    size_t len = 0;
    rx_[0] = 0;
    const uint32_t t0 = millis();
    while ((uint32_t)(millis() - t0) < timeoutMs) {
      while (s_->available()) {
        const int c = s_->read();
        if (c < 0) break;
        if (len >= sizeof(rx_) - 1) { memmove(rx_, rx_ + 100, len - 100); len -= 100; }
        rx_[len++] = (char)c;
        rx_[len] = 0;
      }
      if (strstr(rx_, want)) return true;
      if (bad && strstr(rx_, bad)) return false;
      delay(5);
    }
    return false;
  }

  bool cmd(const char* c, const char* want, uint32_t timeoutMs) {
    flushInput();
    s_->print(c);
    s_->print("\r");
    return waitFor(want, "ERROR", timeoutMs);
  }
};
