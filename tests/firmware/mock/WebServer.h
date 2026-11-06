#pragma once
#include "Arduino.h"
#include <functional>
enum HTTPMethod { HTTP_ANY, HTTP_GET, HTTP_POST };
class WebServer {
 public:
  explicit WebServer(int) {}
  typedef std::function<void(void)> THandlerFunction;
  void on(const char*, HTTPMethod, THandlerFunction) {}
  void onNotFound(THandlerFunction) {}
  void begin() {}
  void handleClient() {}
  void sendHeader(const char*, const char*) {}
  void send(int, const char*, const char*) {}
  String uri() { return String("/"); }
};
