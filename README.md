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
Windows: server and simulator only need JDK 21 / Python; see the Windows section when added.
