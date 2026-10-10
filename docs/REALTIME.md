# Realtime multi-transformer setup

```
ESP32 (SoftAP, Wi-Fi) --HTTP--> phone "gateway" --wss--> STSMN server --wss--> every viewer phone (map, fleet, alerts)
```
Each transformer has its own ID (e.g. `TR-001`) and its own secret token. The phone that is on that ESP32's Wi-Fi relays every new
packet (about every 3 s) to the server over mobile data. Everyone else only watches.

## 1. Run the server
```
cd server
npm install
export STSMN_VIEWER_KEY=$(node -e "console.log(require('crypto').randomBytes(24).toString('hex'))")   # keep this value, share it only with your team
node scripts/add-device.js TR-001 "Ajrara school" 26.85 80.94      # prints the device TOKEN once
node scripts/add-device.js TR-002 "Second site" 26.90 80.88
npm start                                                          # listens on PORT (default 8080)
```
Put it behind HTTPS (Render / Fly.io / Railway, or Caddy/nginx on a VPS). Public address becomes `wss://your-host/ws`.
Revoke or rotate: `node scripts/revoke-device.js TR-002` or `... TR-002 --rotate`. No restart needed.
Keep `server/data/` private (it holds token hashes) and never commit `.env`.

## 2. Phones
Settings -> Realtime cloud: turn on, server address `wss://your-host/ws`, **viewer key** (everyone), and on the gateway phone also **device ID + device token**.
Map and Fleet then show every transformer live: SAFE / WARNING / EMERGENCY, or STALE / OFFLINE when data stops. Emergency also raises a phone notification.

## 3. Without internet
The app still works exactly as before with one ESP32 over its own Wi-Fi; the cloud is optional.
