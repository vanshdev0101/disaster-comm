# Disaster Communication & Resource Management

ESP32 sensor node -> Wi-Fi to LAN backend, or LoRa -> gateway -> backend when Wi-Fi/Internet fails.
Readings that couldn't be posted are queued in flash and replayed; the server dedups on (node, boot, seq).

```
server/     Spring Boot 4 / Java 21 backend + dashboard (the real one)
backend/    original Python prototype, same API; sim.py still works against either
firmware/   PlatformIO: env `node` (sensors) and env `gateway` (LoRa receiver)
```

## Server (Spring)
```
cd server
# JDK 21 required (Linux fish: set -x JAVA_HOME /usr/lib/jvm/java-21-openjdk)
./mvnw spring-boot:run    # Windows: mvnw.cmd spring-boot:run -> http://localhost:8000
./mvnw verify             # tests
```

### Dashboard features
- **Login:** viewing is open; acknowledging, allocating, resolving, renaming and placing nodes need an officer login.
  First start creates `admin` / `admin123` (a warning is printed). Change it with `disaster.admin.password=...` in
  `server/src/main/resources/application.properties` before the first run, then add officers from the dashboard (admin only).
- **Audit log:** every officer action is recorded with the officer's name.
- **Map + node status:** click "Place on map" on a node card, then click the map. Nodes show online/offline (no data for 60 s),
  Wi-Fi vs LoRa link and signal strength. The map needs Internet for tiles; everything else works offline.
- **History charts:** water level, temperature and rain for the last 30 minutes.
- **Alerts:** press "Alerts: on" for a beep, banner and browser notification on new incidents or a node going offline.

## Officer notification (Telegram)
When an incident opens, the responsible officer is messaged on Telegram: severity, hazard, node, the reading against its
threshold, observed time, incident ID and a dashboard link. Who is told: the node's **responsible officer** if one is set,
otherwise every linked officer who is **on duty**. The officer who acknowledges an incident becomes responsible for it, or
an officer can **assign** it to someone else, who is messaged too. Messages wait in a queue and are retried while Telegram
is unreachable. It is off unless a bot token is set.

Setup (one time): in Telegram message **@BotFather**, send `/newbot`, copy the token. Start the server with it
(keep the token out of git):
```
DISASTER_TELEGRAM_TOKEN=123456:ABC... DISASTER_PUBLIC_URL=http://192.168.1.10:8000 java -jar target/server-0.0.1-SNAPSHOT.jar
```
Each officer: sign in, press **Notifications**, **Link Telegram**, and send the bot `/link CODE`. In the Instrument readings
panel, pick a node's **Owner**. Note Telegram needs Internet, so during a total outage the dashboard sound and the on-screen
alert are the channel; queued messages go out when the Internet returns.

## Cloud storage (store-and-forward)
Readings always go to the control station's local database first. When a cloud server is configured, the station uploads
everything the cloud has not yet confirmed (every 10 s, plus a snapshot of incidents/resources), keeps it queued while the
Internet is down, and catches up when it is back. The cloud ignores repeats, so retries are safe. The dashboard shows
"Cloud sync: Synced / N queued, offline / Off".

The cloud is the same app run on a hosted machine with a receiver key; its read-only page is `/cloud.html`.
```
# cloud (demo on this PC, port 8100):
SERVER_PORT=8100 DISASTER_CLOUD_RECEIVER_KEY=demo-key SPRING_DATASOURCE_URL=jdbc:h2:file:./data/cloud java -jar target/server-0.0.1-SNAPSHOT.jar
# control station, pointed at it:
DISASTER_CLOUD_URL=http://localhost:8100 DISASTER_CLOUD_KEY=demo-key DISASTER_STATION=chennai-control java -jar target/server-0.0.1-SNAPSHOT.jar
```
Windows PowerShell: set the variables first, e.g. `$env:DISASTER_CLOUD_URL="http://localhost:8100"`. For a real cloud, deploy the jar to a
host and use its https URL; use a long random key. With no `DISASTER_CLOUD_URL` the feature is off. One station per cloud instance for now.

## Windows
Needs JDK 21 (Temurin), Git and, for the simulator, Python 3. In PowerShell:
```
git clone https://github.com/vanshdev0101/disaster-comm.git
cd disaster-comm\server
.\mvnw.cmd spring-boot:run          # then open http://localhost:8000
# second window: fake node, no hardware needed
cd disaster-comm\backend; py sim.py flood
```
With boards: install the CP210x driver, copy `firmware\include\secrets.example.h` to `secrets.h`, set Wi-Fi and the PC's LAN IP
(`ipconfig`), allow TCP 8000 in Windows Firewall, then `pio run -e node -t upload` / `-e gateway`.
Not yet run on Windows by the author.

## Python prototype
```
cd backend
python server.py          # Windows: py server.py   -> http://localhost:8000
python sim.py             # fake node (add `flood` for alerts); no hardware needed
python test_backend.py
```

## Firmware
```
pip install platformio    # or the PlatformIO VS Code extension
cd firmware
copy include\secrets.example.h include\secrets.h     # Linux: cp
# edit secrets.h: Wi-Fi + SERVER_URL = this PC's LAN IP, port 8000
pio run -e node -t upload
pio run -e gateway -t upload
```
Pins, frequency and node ID are in `firmware/include/config.h`.
Linux serial access: add your user to the `uucp` group (Arch/CachyOS). Windows: install the CP210x/CH340 driver.
Open TCP 8000 in the PC firewall so the ESP32s can reach the backend.

## Status
Working end to end on real hardware (Linux): node (temperature, flame, rain, water level) -> Wi-Fi -> server -> dashboard,
and node -> LoRa (433 MHz, Ra-02) -> gateway -> server when the server/Wi-Fi is unreachable.
Smoke (MQ-2) is not fitted: `HAVE_SMOKE 0` in `firmware/include/config.h` reports "no sensor".
