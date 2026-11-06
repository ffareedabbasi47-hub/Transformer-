#pragma once
#include <stdint.h>
class OneWire { public: explicit OneWire(uint8_t pin) : pin_(pin) {} private: uint8_t pin_; };
