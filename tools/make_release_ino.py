import re, sys, pathlib

def strip(src):
    out, i, n = [], 0, len(src)
    while i < n:
        c = src[i]
        if c == '"' or c == "'":
            q = c; j = i + 1
            while j < n and src[j] != q:
                j += 2 if src[j] == '\\' else 1
            out.append(src[i:j + 1]); i = j + 1
        elif src.startswith('//', i):
            while i < n and src[i] != '\n': i += 1
        elif src.startswith('/*', i):
            j = src.index('*/', i) + 2
            out.append('\n' if '\n' in src[i:j] else ' '); i = j
        else:
            out.append(c); i += 1
    lines = [l.rstrip() for l in ''.join(out).split('\n')]
    res, blank = [], 0
    for l in lines:
        if l.strip() == '':
            blank += 1
            if blank > 1: continue
        else:
            blank = 0
        res.append(l)
    return '\n'.join(res).strip() + '\n'

root = pathlib.Path(__file__).resolve().parent.parent
text = (root / 'ESP32/stsmn_single/stsmn_single.ino').read_text()
if re.search(r'^#define ENABLE_BLE 0\b', text, re.M):
    text = re.sub(r'^#include <(BLEDevice|BLEServer|BLEUtils|BLE2902)\.h>\n', '', text, flags=re.M)
    text = re.sub(r'^#if ENABLE_BLE\n.*?^#endif\n', '', text, flags=re.M | re.S)
dst = root / 'ESP32/release/STSMN_final.ino'
dst.parent.mkdir(parents=True, exist_ok=True)
dst.write_text(strip(text))
print(dst, len(dst.read_text().splitlines()), 'lines')
