#pragma once
// Host mock of the ESP32 Preferences (NVS) library: in-memory only.
#include <string>
#include <map>
#include <cstddef>
#include <cstring>
class Preferences {
 public:
  bool begin(const char*, bool = false) { return true; }
  void end() {}
  size_t getString(const char* k, char* out, size_t n) {
    auto it = store().find(k); if (it == store().end() || n == 0) { if (n) out[0] = 0; return 0; }
    strncpy(out, it->second.c_str(), n - 1); out[n - 1] = 0; return strlen(out);
  }
  size_t putString(const char* k, const char* v) { store()[k] = v; return strlen(v); }
  bool isKey(const char* k) { return store().count(k) > 0; }
 private:
  static std::map<std::string, std::string>& store() { static std::map<std::string, std::string> s; return s; }
};
