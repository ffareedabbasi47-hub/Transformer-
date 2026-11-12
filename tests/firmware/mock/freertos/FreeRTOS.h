#pragma once
#include <stdint.h>
typedef uint8_t StackType_t; typedef uint32_t TickType_t; typedef unsigned UBaseType_t;
#define pdMS_TO_TICKS(x) (x)
#ifndef pdPASS
#define pdPASS 1
#endif
#define portMAX_DELAY 0xffffffffu
