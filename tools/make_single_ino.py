#!/usr/bin/env python3
"""Builds ESP32/stsmn_single/stsmn_single.ino: the whole firmware in ONE file (copy-paste into the Arduino IDE).
Run from the repo root:  python3 tools/make_single_ino.py
The multi-file sketch in ESP32/stsmn_firmware/ stays the canonical source."""
import re, pathlib
fw = pathlib.Path("ESP32/stsmn_firmware")
def body(name):
    out = []
    for line in (fw / name).read_text().splitlines():
        if re.match(r'\s*#pragma once', line) or re.match(r'\s*#include "', line):
            continue
        out.append(line)
    return "\n".join(out).strip("\n")
ino = (fw / "stsmn_firmware.ino").read_text().splitlines()
first_local = next(i for i, l in enumerate(ino) if l.startswith('#include "config.h"'))
after_includes = next(i for i, l in enumerate(ino) if l.startswith("// ---- Compile-time check"))
head = "\n".join(ino[:first_local])
tail = "\n".join(ino[after_includes:])
wifi = '''// ===== Wi-Fi settings: CHANGE THE PASSWORD BELOW (8 to 63 characters). The sketch will not compile with the example one. =====
#define WIFI_AP_SSID "STSMN-TR001"
#define WIFI_AP_PASSWORD "CHANGE_ME_min8chars"
'''
parts = [head, "// ===== config.h =====\n" + body("config.h"), wifi]
for f in ["util.h", "risk_engine.h", "protection_logic.h", "sensors.h", "actuators.h"]:
    parts.append("// ===== %s =====\n%s" % (f, body(f)))
parts.append("// ===== main sketch =====\n" + tail)
out = pathlib.Path("ESP32/stsmn_single/stsmn_single.ino")
out.parent.mkdir(parents=True, exist_ok=True)
out.write_text("\n\n".join(parts) + "\n")
print("wrote", out, len(out.read_text().splitlines()), "lines")
