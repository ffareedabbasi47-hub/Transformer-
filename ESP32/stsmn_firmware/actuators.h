// Relay, LEDs and buzzer. The relay takes a LOGICAL value:
//   allow = true  -> supply allowed
//   allow = false -> cutoff
// and maps it to the real GPIO level using RELAY_ACTIVE_LOW from config.h.
#pragma once
#include <Arduino.h>
#include "config.h"
#include "risk_engine.h"

class Actuators {
 public:
  // Call FIRST in setup(): put the relay into cutoff before anything else.
  void begin() {
    if (RELAY_USES_RELEASE) {
      pinMode(PIN_RELAY, INPUT_PULLUP);   // released = cutoff (weak pull-up holds the pin high, the module's own pull-up keeps the relay OFF)
    } else {
      // Latch the CUT level into the output register BEFORE the pin becomes an output, so the relay never
      // sees a pulse of the "energised" level during boot (relevant for active-low modules).
      digitalWrite(PIN_RELAY, RELAY_ACTIVE_LOW ? HIGH : LOW);
      pinMode(PIN_RELAY, OUTPUT);
    }
    relayAllowed_ = false;
    pinMode(PIN_LED_GREEN, OUTPUT);
    pinMode(PIN_LED_YELLOW, OUTPUT);
    pinMode(PIN_LED_RED, OUTPUT);
    pinMode(PIN_BUZZER, OUTPUT);
    setBuzzer(false);
    allLeds(false);
  }

  void setRelayAllowed(bool allow) {
    if (RELAY_USES_RELEASE) {
      if (allow && !relayAllowed_) { pinMode(PIN_RELAY, OUTPUT); digitalWrite(PIN_RELAY, LOW); }   // coil energised
      else if (!allow) { digitalWrite(PIN_RELAY, HIGH); pinMode(PIN_RELAY, INPUT_PULLUP); }          // released = cutoff, pin held high
    } else {
      const int onLevel  = RELAY_ACTIVE_LOW ? LOW : HIGH;   // coil energized = supply allowed
      const int offLevel = RELAY_ACTIVE_LOW ? HIGH : LOW;
      digitalWrite(PIN_RELAY, allow ? onLevel : offLevel);
    }
    relayAllowed_ = allow;
  }
  bool relayAllowed() const { return relayAllowed_; }

  // True if the relay pin no longer reads back the level we commanded.
  bool relayMismatch() const {
    if (RELAY_USES_RELEASE) return relayAllowed_ && digitalRead(PIN_RELAY) != LOW;   // a released pin has no level to compare
    const int onLevel  = RELAY_ACTIVE_LOW ? LOW : HIGH;
    const int offLevel = RELAY_ACTIVE_LOW ? HIGH : LOW;
    return digitalRead(PIN_RELAY) != (relayAllowed_ ? onLevel : offLevel);
  }

  // Green = SAFE, Yellow = WARNING, Red (blinking) + buzzer = EMERGENCY.
  void applyStatus(Status s, bool alarm, uint32_t now) {
    bool blink = ((now / 250) % 2) == 0;
    digitalWrite(PIN_LED_GREEN,  s == Status::SAFE ? HIGH : LOW);
    digitalWrite(PIN_LED_YELLOW, s == Status::WARNING ? HIGH : LOW);
    digitalWrite(PIN_LED_RED,    s == Status::EMERGENCY ? (blink ? HIGH : LOW) : LOW);
    setBuzzer(alarm && blink);  // pulsing alarm tone
  }

 private:
  static constexpr bool RELAY_USES_RELEASE = RELAY_ACTIVE_LOW && RELAY_OPEN_DRAIN_OFF;
  void setBuzzer(bool on) { digitalWrite(PIN_BUZZER, (on == BUZZER_ACTIVE_HIGH) ? HIGH : LOW); }
  void allLeds(bool on) {
    digitalWrite(PIN_LED_GREEN, on); digitalWrite(PIN_LED_YELLOW, on); digitalWrite(PIN_LED_RED, on);
  }
  bool relayAllowed_ = false;
};
