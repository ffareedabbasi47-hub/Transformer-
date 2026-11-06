#!/usr/bin/env python3
"""Builds docs/wiring/*.svg|png and docs/STSMN_Wiring_Guide.pdf (+ .html) - beginner wiring guide in simple Hinglish.
Run from the repo root: python3 tools/make_wiring_doc.py   (needs Playwright/Chromium for the PDF)."""
import pathlib, html
OUT = pathlib.Path("docs/wiring"); OUT.mkdir(parents=True, exist_ok=True)

# ---------------- diagram ----------------
W, H = 1500, 980
BLUE, GREEN, ORANGE, PURPLE, TEAL, GREY = "#1d6fe0", "#1f9d55", "#e07b00", "#8a3ffc", "#0f9fb0", "#555"
svg = [f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 {W} {H}" width="{W}" height="{H}" font-family="Arial, Helvetica, sans-serif">',
       f'<rect width="{W}" height="{H}" fill="#ffffff"/>',
       '<text x="750" y="44" text-anchor="middle" font-size="30" font-weight="bold" fill="#0b1b33">STSMN - ESP32 wiring (sirf signal wires)</text>',
       '<text x="750" y="74" text-anchor="middle" font-size="17" fill="#444">Har box ke andar likha hai kaunsa power (3.3V / 5V) aur GND lagana hai. Line = signal wire jo ESP32 ke GPIO pin tak jati hai.</text>']
ex, ey, ew, eh = 590, 110, 320, 820
svg.append(f'<rect x="{ex}" y="{ey}" width="{ew}" height="{eh}" rx="18" fill="#10233f" stroke="#0b1b33" stroke-width="4"/>')
svg.append(f'<text x="{ex+ew/2}" y="{ey+44}" text-anchor="middle" font-size="26" font-weight="bold" fill="#fff">ESP32</text>')
svg.append(f'<text x="{ex+ew/2}" y="{ey+70}" text-anchor="middle" font-size="15" fill="#9fb6d9">DevKit V1 (30 pin)</text>')

def box(x, y, w, h, title, lines, color):
    svg.append(f'<rect x="{x}" y="{y}" width="{w}" height="{h}" rx="12" fill="#f6f9ff" stroke="{color}" stroke-width="3"/>')
    svg.append(f'<text x="{x+12}" y="{y+26}" font-size="18" font-weight="bold" fill="{color}">{html.escape(title)}</text>')
    for i, l in enumerate(lines):
        svg.append(f'<text x="{x+12}" y="{y+48+i*19}" font-size="14" fill="#222">{html.escape(l)}</text>')

def pin(side, y, label, color):
    x = ex if side == "L" else ex + ew
    tx = x + 14 if side == "L" else x - 14
    anchor = "start" if side == "L" else "end"
    svg.append(f'<circle cx="{x}" cy="{y}" r="7" fill="{color}" stroke="#fff" stroke-width="2"/>')
    svg.append(f'<text x="{tx}" y="{y+5}" text-anchor="{anchor}" font-size="16" font-weight="bold" fill="#fff">{label}</text>')

def wire(x1, y1, x2, y2, color, label=None):
    mx = (x1 + x2) / 2
    svg.append(f'<path d="M{x1},{y1} C{mx},{y1} {mx},{y2} {x2},{y2}" fill="none" stroke="{color}" stroke-width="4" stroke-linecap="round"/>')
    if label:
        svg.append(f'<text x="{mx}" y="{(y1+y2)/2-8}" text-anchor="middle" font-size="13" fill="{color}" font-weight="bold">{html.escape(label)}</text>')

left = [  # (pin y, GPIO label, title, lines, colour)
    (190, "GPIO 4", "1. Temperature  DS18B20", ["Laal wire -> 3.3V, Kaala -> GND", "Peela (data) -> GPIO 4", "4.7k resistor: data aur 3.3V ke beech"], BLUE),
    (300, "GPIO 34", "2. Current  ACS712 (5A)", ["VCC -> 5V, GND -> GND", "OUT -> 10k -> GPIO 34, aur", "GPIO 34 -> 20k -> GND (divider)"], BLUE),
    (410, "GPIO 35", "3. Voltage sensor (0-25V)", ["S -> GPIO 35,  '-' -> GND", "'+' khali chhodo", "Screw terminal: battery + / -"], BLUE),
    (520, "GPIO 32", "4. Smoke  MQ-2", ["VCC -> 5V, GND -> GND", "AO -> 10k -> GPIO 32, aur", "GPIO 32 -> 20k -> GND (divider)"], ORANGE),
    (630, "GPIO 33", "5. Water level (oil demo)", ["+ -> 3.3V,  '-' -> GND", "S -> GPIO 33", "Sirf PAANI, transformer oil nahi"], ORANGE),
    (740, "GPIO 25", "6. Flame sensor", ["VCC -> 3.3V, GND -> GND", "DO -> GPIO 25", "(AO khali chhodo)"], ORANGE),
]
for y, g, t, ls, c in left:
    box(30, y - 52, 400, 100, t, ls, c)
    pin("L", y, g, c)
    wire(430, y, ex, y, c)

right = [
    (190, [("GPIO 26", "IN")], "Relay module (1 channel)", ["VCC -> 5V, GND -> GND, IN -> GPIO 26", "Load COM + NO se jodo (NC nahi)", "Sirf low-voltage demo load"], GREEN),
    (300, [("GPIO 27", "+")], "Buzzer (active)", ["+ -> GPIO 27", "- -> GND"], GREEN),
    (410, [("GPIO 18", "G"), ("GPIO 19", "Y"), ("GPIO 23", "R")], "3 LED (Hara, Peela, Laal)", ["Har LED: GPIO -> 220 ohm -> LED lamba pair", "LED chhota pair -> GND"], GREEN),
    (590, [("GPIO 16", "GPS TXD"), ("GPIO 17", "GPS RXD")], "7. GPS  NEO-6M", ["VCC -> 3.3V, GND -> GND", "GPS TXD -> GPIO 16", "GPS RXD -> GPIO 17 (zaroori nahi)", "Antenna khuli jagah / khidki ke paas"], PURPLE),
    (790, [("GPIO 13", "SIM TXD"), ("GPIO 14", "SIM RXD")], "8. SIM  SIM800L (SMS)", ["VCC -> alag 3.7-4.2V battery (ESP32 se NAHI)", "GND -> ESP32 GND se bhi jodo (common)", "SIM TXD -> GPIO 13", "GPIO 14 -> 1k -> SIM RXD, SIM RXD -> 2k -> GND"], TEAL),
]
pin_y = {"GPIO 26": 190, "GPIO 27": 300, "GPIO 18": 380, "GPIO 19": 410, "GPIO 23": 440, "GPIO 16": 570, "GPIO 17": 610, "GPIO 13": 760, "GPIO 14": 820}
for y, pins, t, ls, c in right:
    h = 116 if len(ls) > 3 else 100
    box(1070, y - 52, 400, h, t, ls, c)
    for g, _ in pins:
        py = pin_y[g]
        pin("R", py, g, c)
        wire(ex + ew, py, 1070, y - 52 + h / 2 if len(pins) > 1 else y, c)
# power note
svg.append('<rect x="30" y="852" width="400" height="100" rx="12" fill="#fff4f4" stroke="#d93025" stroke-width="3"/>')
svg.append('<text x="42" y="880" font-size="18" font-weight="bold" fill="#d93025">Sabse zaroori (power)</text>')
for i, l in enumerate(["ESP32 ko USB 5V 2A adapter se chalao.", "Sabhi GND (ESP32, sensors, SIM battery) ek saath jodo.", "SIM800L ko kabhi 3.3V ya ESP32 pin se mat chalao."]):
    svg.append(f'<text x="42" y="{904+i*19}" font-size="14" fill="#222">{html.escape(l)}</text>')
svg.append('</svg>')
(OUT / "STSMN_wiring_diagram.svg").write_text("\n".join(svg))
print("svg ok")

# ---------------- written guide (HTML -> PDF) ----------------
def table(head, rows):
    h = "".join(f"<th>{html.escape(c)}</th>" for c in head)
    b = "".join("<tr>" + "".join(f"<td>{c}</td>" for c in r) + "</tr>" for r in rows)
    return f"<table><tr>{h}</tr>{b}</table>"

parts = [
 ("Saaman ki list (asli components)", table(["#", "Component (market mein naam)", "Kaam", "Kitne"], [
   ["1", "ESP32 DevKit V1 (30 pin, ESP32-WROOM-32)", "Dimaag: sab sensors padhta hai, relay chalata hai, Wi-Fi deta hai", "1"],
   ["2", "DS18B20 waterproof temperature probe (3 wire)", "Temperature", "1"],
   ["3", "ACS712 5A current sensor module", "Current (Ampere)", "1"],
   ["4", "Voltage sensor module 0-25V (FZ0430 type)", "Voltage", "1"],
   ["5", "MQ-2 smoke / gas sensor module", "Dhuaan", "1"],
   ["6", "Flame sensor module (IR, 3 ya 4 pin)", "Aag", "1"],
   ["7", "Water level sensor (analog, 3 pin)", "Oil level ka demo (sirf paani)", "1"],
   ["8", "NEO-6M GPS module + antenna", "Asli location (latitude/longitude)", "1"],
   ["9", "SIM800L GSM module + antenna + SIM card", "SMS alert bhejna", "1"],
   ["10", "1 channel relay module 5V", "Supply kaatna (cutoff)", "1"],
   ["11", "Active buzzer", "Alarm awaaz", "1"],
   ["12", "LED hara, peela, laal", "Status light", "3"],
   ["13", "Resistor 220 ohm", "Har LED ke saath", "3"],
   ["14", "Resistor 10k ohm / 20k ohm", "ACS712 aur MQ-2 ke divider (har ek ke liye 10k + 20k)", "2 + 2"],
   ["15", "Resistor 1k ohm / 2k ohm", "SIM800L RX divider", "1 + 1"],
   ["16", "Resistor 4.7k ohm", "DS18B20 pull-up", "1"],
   ["17", "Capacitor 1000 uF 10V (ya zyada)", "SIM800L ke paas power ko steady rakhta hai", "1"],
   ["18", "18650 Li-ion cell + holder (ya 4V 2A buck converter)", "SIM800L ka alag power", "1"],
   ["19", "Breadboard + male-female / male-male jumper wires", "Jodne ke liye", "-"],
   ["20", "USB 5V 2A adapter + micro-USB cable", "ESP32 ka power", "1"],
 ])),
 ("Pehle safety (zaroori)", "<ul><li>Ye <b>sirf low-voltage demo</b> hai (battery / 3.7V - 12V). <b>230V mains ya asli transformer se kabhi mat jodo.</b></li>"
   "<li>Wiring karte waqt ESP32 ka USB nikalo. Pehle sab jodo, phir jaancho, phir power do.</li>"
   "<li>Relay ke load wale taar COM aur <b>NO</b> mein lagao (NC mein nahi): ESP32 band ho to supply kat jaaye.</li>"
   "<li>SIM800L ka power <b>ESP32 ke 3.3V ya 5V pin se mat lo</b>: usse bahut current chahiye, ESP32 reset hoga.</li>"
   "<li>Flame test ke time bade ki nigrani mein raho. Lighter sensor se 20 cm door rakho.</li></ul>"),
 ("Diagram", '<img src="wiring/STSMN_wiring_diagram.svg" style="width:100%">'),
 ("Step by step wiring (pin se pin)", "<p>Rang ka matlab: <b>laal = power</b>, <b>kaala = GND</b>, baaki = signal. Har module ko isi order mein jodo.</p>" + table(["Module", "Module ka pin", "Kahan lagana hai"], [
   ["1. DS18B20", "Laal (VCC)", "ESP32 <b>3V3</b>"], ["", "Kaala (GND)", "ESP32 <b>GND</b>"], ["", "Peela (DATA)", "ESP32 <b>GPIO 4</b>  +  4.7k resistor DATA aur 3V3 ke beech"],
   ["2. ACS712", "VCC", "ESP32 <b>VIN (5V)</b>"], ["", "GND", "ESP32 <b>GND</b>"], ["", "OUT", "10k resistor -> <b>GPIO 34</b>. GPIO 34 se 20k resistor -> GND"], ["", "IP+ / IP-", "Load ke series mein (battery + se load tak, beech mein sensor)"],
   ["3. Voltage sensor", "S", "ESP32 <b>GPIO 35</b>"], ["", "-", "ESP32 <b>GND</b>"], ["", "+ (sensor side)", "Khali chhodo"], ["", "Screw terminal VCC / GND", "Naap-ne wali battery + aur -  (15V se zyada nahi)"],
   ["4. MQ-2", "VCC", "ESP32 <b>VIN (5V)</b>"], ["", "GND", "ESP32 <b>GND</b>"], ["", "AO", "10k resistor -> <b>GPIO 32</b>. GPIO 32 se 20k resistor -> GND"], ["", "DO", "Khali chhodo"],
   ["5. Water level", "+ (VCC)", "ESP32 <b>3V3</b>"], ["", "- (GND)", "ESP32 <b>GND</b>"], ["", "S", "ESP32 <b>GPIO 33</b>"],
   ["6. Flame", "VCC", "ESP32 <b>3V3</b>"], ["", "GND", "ESP32 <b>GND</b>"], ["", "DO", "ESP32 <b>GPIO 25</b>"],
   ["7. GPS NEO-6M", "VCC", "ESP32 <b>3V3</b>"], ["", "GND", "ESP32 <b>GND</b>"], ["", "TXD", "ESP32 <b>GPIO 16</b>"], ["", "RXD", "ESP32 <b>GPIO 17</b> (na jodo to bhi chalega)"],
   ["8. SIM800L", "VCC", "<b>Alag battery +</b> (3.7-4.2V). 1000 uF capacitor VCC-GND ke beech"], ["", "GND", "Battery - <b>aur</b> ESP32 GND (dono GND ek hon)"], ["", "TXD", "ESP32 <b>GPIO 13</b>"], ["", "RXD", "ESP32 <b>GPIO 14</b> se 1k resistor -> SIM800L RXD. SIM800L RXD se 2k resistor -> GND"], ["", "Antenna + SIM card", "Antenna lagao, SIM (SMS wala, PIN lock band) lagao"],
   ["Relay", "VCC / GND / IN", "ESP32 <b>VIN</b> / <b>GND</b> / <b>GPIO 26</b>"], ["", "COM + NO", "Load ka taar (low voltage)"],
   ["Buzzer", "+ / -", "<b>GPIO 27</b> / GND"],
   ["LED hara", "lamba pair -> 220 ohm", "<b>GPIO 18</b>;  chhota pair -> GND"], ["LED peela", "lamba pair -> 220 ohm", "<b>GPIO 19</b>;  chhota pair -> GND"], ["LED laal", "lamba pair -> 220 ohm", "<b>GPIO 23</b>;  chhota pair -> GND"],
 ])),
 ("Code ESP32 mein kaise daalein", "<ol><li>Arduino IDE 2.x kholo. <i>Boards Manager</i> se <b>ESP32 by Espressif Systems</b> install karo.</li>"
   "<li><i>Library Manager</i> se <b>OneWire</b> aur <b>DallasTemperature</b> install karo.</li>"
   "<li>File <code>stsmn_single.ino</code> poori kholo. Upar <code>WIFI_AP_PASSWORD</code> apna naya password (8-63 akshar) karo.</li>"
   "<li><code>ALERT_PHONE_NUMBER</code> mein apna number daalo, country code ke saath, jaise <code>+919876543210</code>. Ye na badlo to SMS band rahega.</li>"
   "<li>Board: <b>ESP32 Dev Module</b>. Sahi COM port chuno. Upload dabao.</li>"
   "<li>Serial Monitor (115200) kholo. Dikhna chahiye: <code>SMS alerts: ON</code> aur kuch second baad <code>SIM module ready</code>.</li></ol>"),
 ("Jaanch (test) karne ka tareeka", table(["Kya jaanchna hai", "Kaise", "Sahi result"], [
   ["Wi-Fi + app", "Phone mein Wi-Fi <b>STSMN-TR001</b>, app mein 192.168.4.1", "App mein live data"],
   ["GPS", "Module khuli jagah / khidki par rakho, 1-10 minute ruko. Browser: 192.168.4.1/api/health", "<code>gpsFix:true</code>, lat/lng aapke shehar ke aas-paas"],
   ["SIM signal", "Wahi /api/health", "<code>gsmReady:true</code>, <code>gsmRegistered:true</code>, <code>gsmSignal</code> 10 se upar"],
   ["SMS alert", "Bade ki nigrani mein lighter ki lau flame sensor ke paas (20 cm) lao", "Relay kat jaata hai, buzzer bajta hai, kuch second mein SMS aata hai"],
   ["Smoke", "Agarbatti ka dhuaan MQ-2 ke paas (pehle 60 second warm-up)", "Smoke true"],
 ])),
 ("Aam dikkatein", table(["Dikkat", "Kaaran / hal"], [
   ["SIM module not answering", "Power kam hai. Alag battery + 1000 uF capacitor lagao. GND common karo. TX/RX ulta to nahi (module TXD -> ESP GPIO 13)."],
   ["SMS nahi aata, gsmRegistered false", "SIM800L sirf 2G chalata hai. Aapke area mein 2G network (Airtel / Vi / BSNL) hona chahiye; Jio SIM nahi chalegi."],
   ["gpsFix false", "Antenna ko khuli jagah rakho. Pehli baar fix mein 10 minute tak lag sakte hain. TXD -> GPIO 16 check karo."],
   ["ESP32 baar baar restart", "USB adapter kamzor hai ya SIM800L ESP32 ke power se juda hai."],
   ["Current / voltage galat", "Multimeter se milaakar <code>VOLT_CAL_FACTOR</code> aur ACS712 ka divider check karo (config.h)."],
 ])),
 ("Sach batana (limits)", "<ul><li>Ye code aur wiring <b>asli hardware par abhi test nahi hue</b>. Sirf computer par logic test aur syntax check hue hain. Pehli baar chalate waqt ek-ek module alag jaancho.</li>"
   "<li>SMS mein location sirf tab aati hai jab GPS ka asli fix ho; warna likha aata hai <i>GPS: no fix</i>. Koi nakli location nahi bheji jaati.</li>"
   "<li>Sensor ki limits (config.h) demo values hain; aapke sensors ke hisaab se calibrate karni hongi.</li>"
   "<li>Ye educational demo hai, certified protection device nahi.</li></ul>"),
]
body = "".join(f"<h2>{html.escape(t)}</h2>{c}" for t, c in parts)
doc = f"""<!doctype html><html lang="hi"><meta charset="utf-8"><title>STSMN Wiring Guide</title><style>
@page {{ size: A4; margin: 14mm; }}
body {{ font-family: Arial, Helvetica, sans-serif; color:#111; font-size: 12.5px; line-height:1.5; }}
h1 {{ font-size: 26px; margin:0 0 4px; }} h2 {{ font-size:17px; margin:22px 0 8px; border-bottom:2px solid #1d6fe0; padding-bottom:3px; page-break-after: avoid; }}
table {{ border-collapse: collapse; width:100%; margin: 6px 0; }} th,td {{ border:1px solid #bbb; padding:4px 7px; text-align:left; vertical-align:top; }}
th {{ background:#e9f1ff; }} tr {{ page-break-inside: avoid; }} code {{ background:#f0f2f5; padding:1px 4px; border-radius:3px; }}
.box {{ background:#fff4f4; border:2px solid #d93025; padding:8px 12px; border-radius:8px; }}
</style><body><h1>STSMN - ESP32 wiring guide</h1>
<p>Smart Transformer Safety &amp; Monitoring Network. Ye guide beginner ke liye hai: pehle saaman, phir diagram, phir pin-se-pin wiring.</p>
<div class="box"><b>Kul 8 modules:</b> 6 sensor modules (1 Temperature, 2 Current, 3 Voltage, 4 Smoke, 5 Flame, 6 Water level) + 7 GPS + 8 SIM (SMS). Saath mein 3 output: Relay, Buzzer, 3 LED.</div>
{body}</body></html>"""
pathlib.Path("docs/STSMN_Wiring_Guide.html").write_text(doc)
pathlib.Path("docs/wiring/STSMN_wiring_diagram.svg")  # referenced relatively below
try:
    from playwright.sync_api import sync_playwright
    with sync_playwright() as p:
        b = p.chromium.launch()
        pg = b.new_page(viewport={"width": 1500, "height": 980}, device_scale_factor=1.5)
        pg.goto("file://" + str(pathlib.Path("docs/wiring/STSMN_wiring_diagram.svg").resolve()))
        pg.screenshot(path="docs/wiring/STSMN_wiring_diagram.png")
        pg2 = b.new_page()
        pg2.goto("file://" + str(pathlib.Path("docs/STSMN_Wiring_Guide.html").resolve()))
        pg2.pdf(path="docs/STSMN_Wiring_Guide.pdf", format="A4", print_background=True)
        b.close()
    print("pdf ok")
except Exception as e:
    print("PDF skipped:", e)
