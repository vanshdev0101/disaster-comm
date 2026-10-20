# Project vs. Presentation: What Matches

Disaster Communication and Resource Management System. Compared against the Phase-I presentation (23 July 2026), slides 3, 4, 13 and 16.

## 1. Objectives (slide 4)

| # | Objective in the PPT | Status | What was built |
|---|---|---|---|
| 1 | Monitor disaster conditions using multiple IoT sensors | **Done** | Water level (HC-SR04), rain plate, flame (HW-072), temperature (DS18B20), read every 3 s |
| 2 | Detect flood, fire, smoke, rainfall and temperature in real time | **Done, except smoke** | Flood, fire, heat and heavy-rain rules work. Smoke is coded (MQ-2 input and rule) but the sensor is not fitted |
| 3 | Process sensor readings using the ESP32 | **Partly** | ESP32 reads, filters the first bad temperature reading, timestamps and packages every reading. The threshold decision runs on the server, not on the ESP32 |
| 4 | Send alerts through Wi-Fi when the Internet is available | **Done** | Node posts to the control station over Wi-Fi |
| 5 | Switch to LoRa automatically during outages | **Done** | Tested on hardware: 32 of 32 readings arrived over LoRa, about -52 dBm, no duplicates |
| 6 | Notify the responsible officer | **Done** | Telegram message with severity, node, reading, time and a link; per-node responsible officer; assignment |
| 7 | Help the officer identify and allocate resources | **Done** | Suggested resources per hazard; acknowledge, allocate, assign, resolve; audit log |
| 8 | Store data locally during failures and sync to the cloud after | **Done** | Node: 200 readings in flash, replayed with true time. Station: uploads to a hosted cloud (Render + Neon Postgres) and catches up after outages (4,347 readings synced) |

## 2. Modules (slide 16)

| Module in the PPT | Status | Where |
|---|---|---|
| Disaster Monitoring Module | Done (smoke not fitted) | `firmware/src/node.cpp` |
| Data Processing Module | Partly: thresholds are on the server | `firmware`, `server/.../Api.java`, `Thresholds.java` |
| Hybrid Communication Module | Done | Wi-Fi first, LoRa fallback, gateway, USB bridge |
| Emergency and Resource Management Module | Done | Officer dashboard, incidents, resources, Telegram |

## 3. Points that differ from the PPT

1. **Smoke sensor not fitted.** MQ-2 was not available. The code, the alert rule and the dashboard handle it ("no sensor" is shown), so it works once a sensor is wired in.
2. **Threshold detection is on the server, not the ESP32.** The ESP32 collects and sends everything; the control station decides. Reason: thresholds can then be changed live from the dashboard without reflashing the node. A node-side check is the next step.
3. **Radio frequency is 433 MHz** because that is what the modules support. The licence-free band in India is 865-867 MHz, so a deployment would use 865 MHz modules.
4. **Cloud is a free hosted service** (Render + Neon). It is real and online, but a production system would use a paid tier.

## 4. Built beyond the PPT

- Automatic duplicate rejection (node, boot, sequence) and replay with true measurement time
- USB bridge so the gateway forwards even when there is no Wi-Fi at all
- Officer accounts with hashed passwords, audit log, editable alert thresholds
- Live operations dashboard with map, node status, signal strength, instrument tiles and charts
- Demo scripts that cut the Wi-Fi on purpose, 9 automated tests, public repository with documentation

## 5. How to say it in the viva

"All eight objectives are implemented and tested on hardware. Two differ from the proposal: the smoke sensor is supported in software but not fitted, and the threshold decision runs on the control station so officers can tune it live; moving it onto the ESP32 is future work."
