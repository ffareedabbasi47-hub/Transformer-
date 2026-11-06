#pragma once
#include "FreeRTOS.h"
typedef void* SemaphoreHandle_t;
inline SemaphoreHandle_t xSemaphoreCreateMutex() { static int x; return &x; }
inline int xSemaphoreTake(SemaphoreHandle_t, TickType_t) { return 1; }
inline int xSemaphoreGive(SemaphoreHandle_t) { return 1; }
