#pragma once
#include "FreeRTOS.h"
typedef void* TaskHandle_t;
typedef void (*TaskFunction_t)(void*);
inline int xTaskCreatePinnedToCore(TaskFunction_t, const char*, uint32_t, void*, int, TaskHandle_t*, int) { return 1; }
inline void vTaskDelay(TickType_t) {}
inline UBaseType_t uxTaskGetStackHighWaterMark(TaskHandle_t) { return 1000; }
