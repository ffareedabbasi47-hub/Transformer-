"""Generates docs/STSMN_Sensor_Plan.{html,pdf}: how every sensor must work (Hinglish, beginner friendly).
All numbers come from ESP32/stsmn_firmware/config.h (DEMO values, calibrate on your hardware)."""
import pathlib

def table(head, rows):
    h = "".join(f"<th>{c}</th>" for c in head)
    r = "".join("<tr>" + "".join(f"<td>{c}</td>" for c in row) + "</tr>" for row in rows)
    return f"<table><tr>{h}</tr>{r}</table>"

def sensor(title, what, how, rule, test, cal):
    return f"""<h2>{title}</h2>
<p><b>Kya naapta hai:</b> {what}</p><p><b>Kaise kaam karta hai:</b> {how}</p>
<p><b>ESP32 ka rule (config.h):</b> {rule}</p>
<p><b>Safe test:</b> {test}</p><p><b>Calibration:</b> {cal}</p>"""

b = []
b.append("""<h1>STSMN - Sensors ka kaam aur plan</h1>
<p>Har sensor kya naapta hai, ESP32 usse kaise padhta hai, kab kya hoga (percent, points, alarm, relay, SMS, WhatsApp), aur kaise test karna hai.
Saari limits <b>DEMO values</b> hain, apne hardware pe calibrate karo. Sirf low-voltage demo.</p>
<div class="box"><b>Ek line mein:</b> sensor padho -> signal ko 0-100 % mein badlo -> points jodo (dhua 50 + aag 50 + baaki) -> SAFE / WARNING / EMERGENCY.
EMERGENCY = buzzer + laal LED + <b>relay apne aap band</b> + SMS + app mein WhatsApp.</div>""")

b.append("<h2>1. Poora flow: jab aag lagti hai to kya-kya hota hai</h2>" + table(
 ["Kab", "Kya hota hai"],
 [["0 s", "Flame sensor aag dekhta hai (0.5 s debounce). Flame signal 40 % se upar = asli aag."],
  ["turant", "Risk 90+ -> <b>EMERGENCY</b>. Laal LED, buzzer, <b>relay band (supply kat gayi)</b>. App mein laal screen + notification."],
  ["~1-3 s", "ESP32 ka SIM800L aapke saved number pe <b>SMS</b> bhejta hai (ek baar; phir har 30 minute mein yaad dilata hai)."],
  ["app khula ho", "App WhatsApp apne aap bhejta hai (agar key saved hai). Internet na ho to har 30 s mein 6 baar koshish."],
  ["dhua + aag dono", "Kamzor signal par bhi turant EMERGENCY (fire in progress)."],
  ["sirf dhua", "Pehle WARNING (peela). Dhua 3 s tak raha to EMERGENCY + relay band. Dhua + temperature 40 C+ ho to 2 s mein."],
  ["theek hone par", "Signal hatne ke baad 10 s tak sab theek raha tabhi EMERGENCY se bahar aata hai. Relay phir khud 2 s stable dekh ke chalu hota hai."]]))

b.append("<h2>2. Score ka hisaab (0-100)</h2>" + table(
 ["Cheez", "Max points", "0 % kab", "100 % kab"],
 [["Dhua (MQ-2)", "50", "clean-air se 30 mV tak badhna", "450 mV badhna"],
  ["Aag: Flame sensor", "35", "aag nahi", "tez aag (AO) / DO on"],
  ["Aag: Temperature", "15", "40 C tak", "70 C"],
  ["Current", "25", "2.0 A tak", "4.5 A"],
  ["Voltage", "15", "nominal +-15 % ke andar", "+-40 %"],
  ["Level kam", "18", "50 % se upar", "30 % se neeche"]]) +
 "<p>Kamzor signal = kam points, tez signal = poore points. SAFE: 0-54, WARNING: 55+ (45 se neeche aake SAFE), EMERGENCY: 80+ ya koi critical shart.</p>"
 "<p><b>Floor (score chhupa nahi sakta):</b> dhua/aag ka koi signal (15 %+) = kam se kam 55 (WARNING). Temperature/current/voltage apni range ke aadhe se upar = kam se kam 55 (WARNING). Koi critical = 90+.</p>")

b.append(sensor("3. Temperature - DS18B20 (GPIO 4)",
 "Transformer / body ka temperature.",
 "Digital sensor, ek wire (OneWire). 4.7k resistor data aur 3.3V ke beech.",
 "40 C se points shuru, 70 C pe 100 %. 70 C+ lagatar 3 s = EMERGENCY. Sensor khula / galat reading = 'not valid' (kabhi 0 C nahi dikhata).",
 "Sensor ko haath mein pakdo (~33 C) ya gunguna paani (50 C). App mein temperature aur % badhna chahiye.",
 "Zaroorat nahi (factory calibrated). Multimeter thermometer se check kar lo."))
b.append(sensor("4. Current - ACS712 5A (GPIO 34)",
 "Load ka current (Ampere).",
 "Current badhne par output voltage badhta hai. Sensor 5V ka hai, isliye ESP32 ko 10k+20k divider se do. Idle par ~2.5 V (divider ke baad ~1.67 V).",
 "2.0 A se points, 4.5 A pe 100 %. 4.5 A+ lagatar 1.5 s = EMERGENCY. Idle level galat (200-3000 mV ke bahar) = sensor not valid.",
 "Chhota low-voltage load (bulb/LED strip) lagao. Pehle bina load ke 0.00 A aana chahiye.",
 "Bina load ke boot karo (zero point khud set hota hai). Multimeter se ek known load check karo."))
b.append(sensor("5. Voltage - 0-25 V module (GPIO 35)",
 "Supply voltage.",
 "Module voltage ko 5 se divide karta hai. ESP32 reading ko wapas multiply karta hai.",
 "Nominal 3.7 V (demo battery). +-15 % tak koi points nahi, +-40 % pe 100 %. <b>Agar module laga nahi to config.h mein HAS_VOLTAGE_SENSOR = false karo</b> (warna fault maana jata hai).",
 "Battery ko multimeter se naapo aur app se milao.",
 "VOLT_CAL_FACTOR ko multimeter ki reading / app ki reading se multiply karo."))
b.append(sensor("6. Dhua - MQ-2 (GPIO 32)",
 "Dhuaan / gas ka signal. (Ye 'kitna dhua' ka rough percent hai, ppm nahi.)",
 "Heater garam hota hai (60 s warm-up, tab tak 'smoke' nahi bolta). Dhua aane par analog voltage badhta hai. Module 5V ka hai, isliye 10k+20k divider.",
 "Clean-air baseline khud dheere-dheere set hota rehta hai. Baseline se 120 mV upar = dhua pakda gaya (60 mV neeche aane par band). 30 mV se 450 mV tak = 0 se 100 %. Dhua akela 3 s = EMERGENCY. Serial Monitor mein <code>mq=asli/baseline</code> dikhta hai.",
 "Agarbatti ya bujhi hui maachis ka dhua sensor ke paas (aag mat lagao). App mein smoke % badhta dikhna chahiye. Dhua hatao, % wapas 0 hona chahiye.",
 "Sensor ko 5 minute garam hone do. Agar bahut zyada/kam sensitive ho to MQ2_DELTA_ENTER_MV aur MQ2_FULL_DELTA_MV badlo."))
b.append(sensor("7. Aag - Flame sensor (DO: GPIO 25, AO: GPIO 36)",
 "Aag ki infrared roshni.",
 "AO pin = signal kitna tez (mukhya). DO pin = comparator ka haan/na (sirf madad). AO ko ambient baseline se compare kiya jaata hai; suraj/lamp ki dheere badalti roshni se bachne ke liye flicker, achanak badlaav aur DO bhi dekhte hain.",
 "Sirf FLICKER wali aag (AO jhilmilata hai) + (achanak badlaav ya DO) 0.5 s tak = EMERGENCY. Steady dhoop/lamp/reflection (flicker nahi) = kuch nahi. Sirf DO = ignore. AO kharab ho to DO-only = sirf WARNING. Details: docs/FLAME_AO_CALIBRATION.md.",
 "Door se ek chhoti jwala (safe jagah, paani paas, adult ke saath). Suraj/lamp se EMERGENCY nahi aana chahiye.",
 "FLAME_AO_CALIBRATION.md ke steps se apni readings lo; config.h ke FLAME_* values placeholders hain. Suraj aur aag ko sensor akela alag nahi kar sakta."))
b.append(sensor("8. Oil level (demo: sirf paani) - Water level sensor (GPIO 33)",
 "Paani ka level (asli transformer oil ke liye ye sensor nahi chalega).",
 "Jitna zyada doobta hai, voltage utna badhta hai.",
 "Level 30 % se neeche = low (10 points). 50 % se upar = theek.",
 "Sensor ko dheere paani mein dubao, % badhna chahiye.",
 "LEVEL_DRY_MV (sukha) aur LEVEL_FULL_MV (poora gila) mV note karke daalo. Laga nahi to HAS_LEVEL_SENSOR = false."))
b.append(sensor("9. GPS - NEO-6M (GPIO 16, 17)",
 "Asli location.",
 "Satellite se fix. Pehli baar 1-5 minute, <b>khule aasman ke neeche</b>. Ghar ke andar fix nahi milta.",
 "Fix na ho to app demo/configured location dikhata hai ('LIVE GPS' nahi). 10 s koi sahi data na aaye to 'no fix'.",
 "Chhat pe ya khidki ke paas rakho. Serial Monitor mein gpsFix / lat / lng.",
 "Zaroorat nahi."))
b.append(sensor("10. SIM800L - SMS (GPIO 13, 14)",
 "SMS alert.",
 "2G modem. <b>Jio SIM nahi chalega</b> (2G chahiye). Alag 4V 2A power + 1000 uF capacitor.",
 "EMERGENCY mein ek SMS, do SMS ke beech kam se kam 2 minute, aur jab tak khatra rahe har 30 minute mein ek.",
 "App > Settings > Alert number daalo. Fir (low-voltage demo mein) dhua dikhao. Ya Serial Monitor mein 'SMS' lines dekho.",
 "Zaroorat nahi."))
b.append(sensor("11. Relay, Buzzer, LED",
 "Supply kaatna, awaaz, status.",
 "Relay: load COM + NO se. ESP32 band ho to bhi supply kat jaati hai. Boot par relay band, ~5 s baad sab sensor theek ho to chalu. Buzzer: EMERGENCY mein beep. LED: hara SAFE, peela WARNING, laal EMERGENCY.",
 "RELAY_ACTIVE_LOW = true aur RELAY_OPEN_DRAIN_OFF = true (3.3V se 5V module ko band karne ke liye pin chhod diya jata hai).",
 "Boot par relay module ki LED band, ~5 s baad ON, EMERGENCY mein OFF. Ulta ho to RELAY_ACTIVE_LOW badlo.",
 "-"))
b.append("<h2>12. App mein kya dikhta hai</h2><p>Sensor Data screen: har sensor ka card, chart aur percent. Dhua aur aag ke chart 0-100 % ke fixed scale mein hain: kamzor signal chhota, tez signal upar tak. Oil level card ke neeche 'Signal strength' card mein sab sensor ka percent (none / weak / medium / strong).</p>")
b.append("<h2>13. Seemayein (sach)</h2><ul><li>Ye sab asli hardware par abhi test nahi hua; limits DEMO hain.</li><li>MQ-2 ppm nahi batata, sirf relative signal.</li><li>WhatsApp tabhi jaata hai jab app khula ho aur phone mein internet ho; SMS app ke bina bhi jaata hai.</li><li>Real transformer par lagane se pehle licensed electrician aur proper protection zaroori hai.</li></ul>")

html = f"""<!doctype html><html lang="hi"><meta charset="utf-8"><title>STSMN Sensor Plan</title><style>
@page {{ size: A4; margin: 14mm; }}
body {{ font-family: Arial, Helvetica, sans-serif; color:#111; font-size:12.5px; line-height:1.5; }}
h1 {{ font-size:26px; margin:0 0 4px; }} h2 {{ font-size:16px; margin:18px 0 6px; border-bottom:2px solid #1d6fe0; padding-bottom:3px; page-break-after:avoid; }}
table {{ border-collapse:collapse; width:100%; margin:6px 0; }} th,td {{ border:1px solid #bbb; padding:4px 7px; text-align:left; vertical-align:top; }}
th {{ background:#e9f1ff; }} tr {{ page-break-inside:avoid; }} code {{ background:#f0f2f5; padding:1px 4px; border-radius:3px; }}
.box {{ background:#fff4f4; border:2px solid #d93025; padding:8px 12px; border-radius:8px; }}
</style><body>{''.join(b)}</body></html>"""
pathlib.Path("docs/STSMN_Sensor_Plan.html").write_text(html)
try:
    from playwright.sync_api import sync_playwright
    with sync_playwright() as p:
        br = p.chromium.launch(); pg = br.new_page()
        pg.goto("file://" + str(pathlib.Path("docs/STSMN_Sensor_Plan.html").resolve()))
        pg.pdf(path="docs/STSMN_Sensor_Plan.pdf", format="A4", print_background=True); br.close()
    print("pdf ok")
except Exception as e:
    print("PDF skipped:", e)
