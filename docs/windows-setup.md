# Windows Setup Guide

Run the whole project on a Windows laptop. Do it in order and tick each step. Commands are for **PowerShell**.
Not yet tested on Windows by the author, so the "if it fails" notes matter.

## 0. What you need to bring
- Both ESP32 boards (node and gateway) with the LoRa modules, antennas and sensors wired
- A **known-good USB data cable** for each board, and a power bank for the node
- A **phone hotspot** to use as Wi-Fi (university Wi-Fi usually blocks the ESP32; use the hotspot)
- Your Telegram bot token and cloud receiver key (private, type them in yourself)

## 1. Install the software (one time, about 20 minutes)
1. **JDK 21**: download "Temurin 21 (LTS)" from adoptium.net, tick "Set JAVA_HOME" and "Add to PATH" in the installer.
2. **Git**: git-scm.com, default options.
3. **Python 3**: python.org, tick **Add python.exe to PATH**.
4. **USB driver**: install the **CP210x** driver from Silicon Labs so the boards show as COM ports.
5. Close and reopen PowerShell, then check each one prints a version:
```
java -version        # must say 21
git --version
python --version
```
If `java -version` shows another number, uninstall the older Java or fix PATH.

## 2. Get the project
```
cd $HOME
git clone https://github.com/vanshdev0101/disaster-comm.git
cd disaster-comm
```

## 3. Start the server
```
cd server
.\mvnw.cmd -q -DskipTests package
java -jar target\server-0.0.1-SNAPSHOT.jar
```
Open **http://localhost:8000**. First start creates the officer `admin` / `admin123` (change it with the environment variable `DISASTER_ADMIN_PASSWORD`). A fresh laptop starts with an empty database. That is fine.

- If Windows asks about network access, click **Allow** (private networks).
- To allow the ESP32s in, run once as Administrator:
```
New-NetFirewallRule -DisplayName "Disaster 8000" -Direction Inbound -Protocol TCP -LocalPort 8000 -Action Allow
```
- First build downloads Maven; it needs Internet and takes a few minutes.

## 4. Find the laptop's IP on the hotspot
1. Turn on the phone hotspot. Connect the laptop to it.
2. Run `ipconfig` and note the **IPv4 Address** of the Wi-Fi adapter (for example `192.168.43.20`).

## 5. Flash the boards
```
pip install platformio
cd $HOME\disaster-comm\firmware
copy include\secrets.example.h include\secrets.h
notepad include\secrets.h
```
In `secrets.h` set:
- `WIFI_SSID` and `WIFI_PASS` = the **hotspot** name and password
- `SERVER_URL` = `http://<laptop IP from step 4>:8000/api/readings`

Plug in **one board at a time**, find its port with `pio device list` (for example `COM5`), then:
```
pio run -e node -t upload --upload-port COM5       # node board
pio run -e gateway -t upload --upload-port COM6    # gateway board
```
If upload stalls on "Connecting...", hold the board's **BOOT** button until it starts. If the port is missing, the driver or the cable is the problem (use a data cable, not a charge-only one).

## 6. Test it
1. Power the node from the power bank. After about 10 seconds it should appear **online** on the dashboard with link **Wi-Fi**.
2. Hold a lighter near the flame sensor: an incident opens.
3. Telegram: copy the token into `server\config\application.properties` (create the `config` folder if needed):
```
disaster.telegram.token=<your token>
disaster.public-url=http://<laptop IP>:8000
```
Restart the server, sign in, press **Notifications**, **Link Telegram**, send `/link CODE` to the bot, then **Send test message**.

## 7. Cloud sync (optional, needs Internet from the hotspot)
Stop the server, then:
```
$env:DISASTER_CLOUD_URL = "https://disaster-comm.onrender.com"
$env:DISASTER_CLOUD_KEY = "<receiver key>"
$env:DISASTER_STATION = "chennai-control"
java -jar target\server-0.0.1-SNAPSHOT.jar
```
The dashboard "Cloud sync" box shows Synced once it connects. Open `https://disaster-comm.onrender.com/cloud.html` a minute earlier: the free host sleeps.

## 8. Show the LoRa fallback
**Easiest and safest:** switch the **phone hotspot off**. The node loses Wi-Fi and starts sending over LoRa. The gateway is plugged into the laptop and its readings are forwarded by the USB bridge, so run it in a second PowerShell window:
```
pip install pyserial
cd $HOME\disaster-comm\scripts
python gateway-bridge.py COM6
```
Readings keep arriving with link = **LoRa**. Turn the hotspot back on: the node returns to Wi-Fi and replays what it stored, without duplicates.

**Alternative (keeps the laptop online):** block only the node. In an Administrator PowerShell:
```
cd $HOME\disaster-comm\scripts
.\node-wifi-outage.ps1 on <node IP>      # find the node IP in the hotspot's connected-devices list
.\node-wifi-outage.ps1 off
```

## 9. Troubleshooting
| Symptom | Fix |
|---|---|
| `java -version` is not 21 | Reinstall Temurin 21, tick PATH, reopen PowerShell |
| Node never goes online | Hotspot must be 2.4 GHz; check name and password; `SERVER_URL` IP right; firewall rule added |
| Dashboard opens on the laptop but not the node | Firewall rule for port 8000; laptop IP changed (redo step 4 and reflash) |
| Board not found / upload fails | CP210x driver; use a data cable; hold BOOT; one board plugged at a time |
| Gateway keeps rebooting | Bad cable or weak USB port: try another |
| No LoRa data | Antennas on; both boards flashed; boards close together |
| Telegram silent | Needs Internet; recheck token and the **Link Telegram** step |
| Port 8000 already in use | Close the other server window |
| `mvnw.cmd` is blocked | Run `Set-ExecutionPolicy -Scope Process Bypass` then retry |
