#!/usr/bin/env python3
"""Simulates the Arduino IDE's automatic prototype insertion on the single-file sketch and syntax-checks the result
against the host mocks. Run from the repo root. (Real IDE behaviour is approximated; it is NOT the ESP32 toolchain.)"""
import re, sys, subprocess, pathlib, tempfile
src = pathlib.Path("ESP32/stsmn_single/stsmn_single.ino").read_text()
src = re.sub(r'(#define WIFI_AP_PASSWORD ")[^"]*(")', r'\1host-test-password-1\2', src, count=1)   # the sketch refuses the public example password
lines = src.split("\n")
fn = re.compile(r'^(static |inline )*[A-Za-z_][A-Za-z_0-9:<>\*& ]* [A-Za-z_0-9]+\([^;{]*\)\s*(const)?\s*\{?$')
protos, first, inns = [], None, False
for i, l in enumerate(lines):
    if l.startswith("namespace "): inns = True
    if l.startswith("}  // namespace"): inns = False
    if inns or l.startswith((" ", "\t", "constexpr", "template")) or "if (" in l: continue
    if fn.match(l):
        if first is None: first = i
        protos.append(re.sub(r'\s*\{$', ';', l.rstrip()))
last_inc = max(i for i, l in enumerate(lines) if l.startswith("#include <"))
bad = 0
for name, at in (("before first function", first), ("after last #include", last_inc + 1)):
    out = lines[:at] + protos + lines[at:]
    with tempfile.NamedTemporaryFile("w", suffix=".cpp", delete=False) as f:
        f.write("\n".join(out)); path = f.name
    for std in ("gnu++11", "gnu++17"):
        r = subprocess.run(["g++", "-std=" + std, "-fsyntax-only", "-Wall", "-Wno-unused-parameter", "-Itests/firmware/mock", path], capture_output=True, text=True)
        print(("OK  " if r.returncode == 0 else "FAIL"), name, std, len(protos), "prototypes")
        if r.returncode: bad += 1; print(r.stderr[:800])
sys.exit(1 if bad else 0)
