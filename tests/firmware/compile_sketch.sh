#!/bin/sh
# Syntax/type check of the REAL sketch sources against HOST MOCKS (not the ESP32 core).
# Usage: tests/firmware/compile_sketch.sh   (run from the repo root). Needs g++.
set -e
HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$HERE/../.."
FW="$ROOT/ESP32/stsmn_firmware"
TMP="$(mktemp -d)"; trap 'rm -rf "$TMP"' EXIT
cp "$FW/secrets.h.example" "$TMP/secrets.h"
sed -i 's/CHANGE_ME_min8chars/host-test-password-1/' "$TMP/secrets.h"   # the sketch refuses the public example password
for STD in gnu++11 gnu++17 gnu++2a; do
  for CORE in 2 3; do
    if [ "$CORE" = 3 ] && [ "$STD" = gnu++11 ]; then continue; fi   # core 3.x builds with a newer standard
    cp "$FW/stsmn_firmware.ino" "$TMP/sketch.cpp"
    g++ -std=$STD -fsyntax-only -Wall -Wextra -Wno-unused-parameter -DESP_ARDUINO_VERSION_MAJOR=$CORE \
        -I"$HERE/mock" -I"$FW" -I"$TMP" "$TMP/sketch.cpp"
    echo "syntax OK: -std=$STD mock core $CORE"
  done
done
