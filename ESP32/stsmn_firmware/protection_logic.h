// Relay arming logic. Pure C++ (no Arduino): tested on a PC in tests/protection_logic_test.cpp.
//
//   * Boot: the supply stays CUT until the boot self-check time has passed AND all protection sensors
//     were valid continuously for RELAY_ARM_STABLE_MS. Invalid sensor data can never authorise operation.
//   * EMERGENCY always cuts the supply and disarms; coming back needs a fresh stable-valid period.
//   * A protection-sensor fault that lasts SENSOR_FAULT_CUTOFF_MS cuts the supply (protection is blind).
// The phone never appears here: connectivity cannot arm, disarm or override anything.
#pragma once
#include <stdint.h>
#include "config.h"
#include "risk_engine.h"

class RelayGate {
 public:
  // Returns true when the supply may be connected. Call once per protection cycle.
  bool update(uint32_t now, Status status, bool sensorFault) {
    if (status == Status::EMERGENCY) {
      armed_ = false; healthy_ = false; faultActive_ = false;
      return false;
    }
    if (sensorFault) {
      healthy_ = false;
      if (!faultActive_) { faultActive_ = true; faultSince_ = now; }
      if (SENSOR_FAULT_CUTOFF_MS != 0 && (uint32_t)(now - faultSince_) >= SENSOR_FAULT_CUTOFF_MS) armed_ = false;
    } else {
      faultActive_ = false;
      if (!healthy_) { healthy_ = true; healthySince_ = now; }
      if (!armed_ && now >= BOOT_SELF_CHECK_MS && (uint32_t)(now - healthySince_) >= RELAY_ARM_STABLE_MS) armed_ = true;
    }
    return armed_;
  }
  bool armed() const { return armed_; }

 private:
  bool armed_ = false, healthy_ = false, faultActive_ = false;
  uint32_t healthySince_ = 0, faultSince_ = 0;
};
