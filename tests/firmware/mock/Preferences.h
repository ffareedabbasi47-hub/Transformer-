#pragma once
// Host mock of the ESP32 Preferences (NVS) library: in-memory only.
#include <string>
#include <map>
#include <cstddef>
#include <cstring>
#include <cstdlib>
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
 size_t putFloat(const char* k, float v) { store()[k] = std::to_string(v); return 4; }
  size_t putInt(const char* k, int v) { store()[k] = std::to_string(v); return 4; }
  size_t putUInt(const char* k, unsigned v) { store()[k] = std::to_string(v); return 4; }
  size_t putBool(const char* k, bool v) { store()[k] = v ? "1" : "0"; return 1; }
  float getFloat(const char* k, float d = 0) { auto it = store().find(k); return it == store().end() ? d : (float)atof(it->second.c_str()); }
  int getInt(const char* k, int d = 0) { auto it = store().find(k); return it == store().end() ? d : atoi(it->second.c_str()); }
  unsigned getUInt(const char* k, unsigned d = 0) { auto it = store().find(k); return it == store().end() ? d : (unsigned)atol(it->second.c_str()); }
  bool getBool(const char* k, bool d = false) { auto it = store().find(k); return it == store().end() ? d : it->second == "1"; }
 private:
  static std::map<std::string, std::string>& store() { static std::map<std::string, std::string> s; return s; }
};
