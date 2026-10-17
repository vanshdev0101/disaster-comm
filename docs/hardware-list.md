# Hardware Status and Build Guide

**Disaster Communication and Resource Management System** — what you own, what is missing, and how to wire it

Updated 12 Oct 2026 from your kit list. Scope: **one sensor node and one gateway**. Prices are rough Indian hobby-store ranges, not quotes. The wiring comes from `firmware/include/config.h` and has **not** been tested on real hardware yet.

## 1. Your kit against the build

| Qty | Part you have | Role | Status |
|---|---|---|---|
| 2 | ESP32 DevKit (WROOM-32, 30/38-pin) | Node + gateway | OK |
| 2 | **Ra-02 LoRa module (SX1278, 433 MHz)** on blue breakout, **header strips loose in the bag** | Node + gateway radio | Works at 433 MHz. **Headers still need soldering** (16 joints each). Not the 868 MHz RFM95W originally planned |
| 2 | SMA whip antenna + u.FL pigtail | Radio antenna | OK. Check it is a 433 MHz antenna. Fit before power |
| 2 | USB data cable | Power + programming | OK |
| 1 | HC-SR04 ultrasonic sensor | Water level | Works, but **not waterproof**: indoor demo only |
| 1 | FC-37 / YL-83 rain plate + board | Rain | OK (wet/dry level only) |
| 1 | KY-026 IR flame sensor | Flame | OK (about 1 m range) |
| 1 | DS18B20 waterproof probe | Temperature | OK |
| 2 | 830-point breadboard | Node + gateway | OK |
| 1 pack each | Male-female and male-male jumper wires (40) | Wiring | OK |
| 2 each | 1 kΩ, 2 kΩ, 4.7 kΩ resistors | Dividers, DS18B20 pull-up | OK. Exactly enough |
| 2 | 100–470 µF electrolytic capacitor | 3.3 V rail for the radios | OK |

## 2. What is still missing

| Item | Needed? | Approx. price | Note |
|---|---|---|---|
| **MQ-2 smoke sensor module** | **Yes, for smoke detection** | ₹150–250 | Without it, run with GPIO 34 tied to GND (see section 10) |
| 0.1 µF ceramic capacitors (5+) | Optional | ₹10–20 | Decoupling near the radio |
| Digital multimeter | Optional, strongly helpful | ₹400–800 | You can build without it by following the safety steps below |
| **Soldering of the Ra-02 headers** | **Yes** | free–₹50 at a shop | 2 modules × 16 pins. Ask a shop or borrow an iron |
| Waterproof ultrasonic (JSN-SR04T) | Only for outdoor use | ₹350–600 | HC-SR04 is fine on a desk |
| 5 V USB wall adapter | Optional | ₹200–300 | To run the node without the laptop |

**Not needed:** extra resistors (1k + 2k pairs replace 10k + 20k), female-female jumpers, pin headers.

## 3. Cost to complete

| Scenario | Cost |
|---|---|
| Minimum to get every sensor working (MQ-2 only) | ₹150–250 |
| Plus ceramic capacitors and a multimeter | ₹560–1,070 |

## 4. Safe to build without a multimeter

- The one fatal mistake is 5 V on a 3.3 V GPIO. Only two signals are 5 V: the HC-SR04 ECHO and, later, the MQ-2 analog output. Both go through a 1 kΩ + 2 kΩ divider.
- Wire everything with USB **unplugged**. Plug in last. Recheck each wire against the table in section 10.
- Power the rain plate, flame sensor, DS18B20 and radio from **3V3**. Only the HC-SR04 (and the MQ-2) go on **VIN**.
- Tell resistors apart by colour bands: 1 kΩ is brown-black-red, 2 kΩ is red-black-red, 4.7 kΩ is yellow-violet-red.
- Fit the antenna before the radio is powered.
- After powering, open the Serial Monitor at 115200 baud. A JSON line prints every 10 s. A plausible `level_cm` shows the ECHO path works, and `rain` rises when the plate is wet.

## Sensor choices and alternatives

Sections 5–9 cover each sensor: the part selected, then the alternatives with the trade-off.

## 5. Water level (flood)

**You have: HC-SR04** (about 2 cm to 400 cm). It uses the same code and pins as the JSN-SR04T and suits a desk demo. It is not waterproof, so keep it above the water and dry. **Field upgrade: JSN-SR04T**, a sealed probe with about 25 cm to 450 cm range.

| Alternative | Advantage | Drawback |
|---|---|---|
| JSN-SR04T | Waterproof probe, same code and pins | ₹350–600; dead zone below about 25 cm |
| A02YYUW (UART waterproof ultrasonic) | Waterproof, 3.3 V-friendly, short dead zone (about 3 cm) | UART instead of pulse; slightly different code |
| Float switch | Very cheap, no calibration, works as a hard "level reached" trigger | One threshold per switch; no continuous reading |
| Submersible pressure transducer (4–20 mA or 0–5 V) | Accurate depth in the water, good for real deployments | ₹2,000+, needs an ADC/amplifier stage and calibration |
| Exposed-trace "water level strip" | Almost free | Corrodes in days. **Not recommended.** |

**Notes:** HC-SR04 echo is 5 V, so use the 1 kΩ + 2 kΩ divider. FLOOD triggers at `level_cm >= 50`, and level is `MOUNT_CM - distance`. For a desk demo set `MOUNT_CM` to about 60 and fix the sensor 60 cm above the base, or lower the threshold in the server.

## 6. Rainfall

**Selected: FC-37 / YL-83 rain plate.** Reads wetness only (how wet the plate is), which is enough to show rain on the prototype.

| Alternative | Advantage | Drawback |
|---|---|---|
| Tipping-bucket rain gauge (reed switch) | Real rainfall in mm per tip, standard in weather stations | ₹1,200–2,500; needs an interrupt/pulse counter and mounting |
| Capacitive rain sensor | No exposed traces, so no corrosion | Less common, usually a bit pricier |
| Optical (infrared) rain sensor | No moving parts, detects drops | Costly; mostly used on vehicles and industrial setups |

**Notes:** the rain plate corrodes and is wet/dry only, so it cannot give rainfall rate. For a credible "heavy rain" alert, upgrade to the tipping bucket and send mm per interval.

## 7. Flame (fire)

**Selected: KY-026 IR flame sensor.** A phototransistor tuned to the infrared band (about 760–1100 nm) emitted by flames. It has a digital alarm output with an adjustable threshold, plus an analog output.

| Alternative | Advantage | Drawback |
|---|---|---|
| Multi-channel IR flame sensor (5-way) | Wider field of view, can estimate direction | Slightly more cost and wiring |
| UV flame sensor | Ignores sunlight, fewer false positives outdoors | Expensive; narrow supplier choices |
| MLX90640 thermal camera | Sees heat patterns over a whole area | ₹4,000+; heavy processing for an ESP32 |
| Temperature + smoke fusion (no flame sensor) | No extra part; confirms fire from two signals | Slower to react; no direct flame detection |

**Notes:** range is only about 1 m, and direct sunlight causes false positives. Treat it as a demo-grade sensor; for real use, combine it with smoke and temperature before raising a FIRE alert.

## 8. Smoke and gas

**To buy: MQ-2.** A heated metal-oxide sensor that responds to smoke, LPG, methane, hydrogen and alcohol. Gives an analog level you threshold in firmware.

| Alternative | Advantage | Drawback |
|---|---|---|
| PMS5003 / PMSA003 (laser particle sensor) | Measures real smoke particles (PM2.5/PM10) | ₹1,500–2,500; UART, 5 V supply |
| MQ-7 / MQ-9 | Detects carbon monoxide, which matters in fires | Same heater, drift and burn-in issues as MQ-2 |
| MQ-135 | Air-quality trend | Not a smoke detector; poor for fire |
| SGP30 / CCS811 / ENS160 | Digital I2C, low power, TVOC/air-quality | Not designed as smoke detectors |
| Commercial photoelectric smoke detector with relay output | Certified detection | Extra wiring, bulky, needs its own 9 V supply |

**Notes:** you do not own one yet; until it arrives, tie GPIO 34 to GND. MQ-2 needs a 24–48 hour burn-in, draws about 150 mA for its heater, and drifts with humidity. Its output can reach 5 V, so divide it down for the ESP32. It is not a certified smoke detector.

## 9. Temperature

**Selected: DS18B20 waterproof probe.** Digital 1-Wire sensor, −55 to 125 °C, ±0.5 °C, sealed in a steel probe. Needs a 4.7 kΩ pull-up.

| Alternative | Advantage | Drawback |
|---|---|---|
| DHT22 (AM2302) | Temperature and humidity in one part | Slow (about 0.5 Hz), not waterproof |
| BME280 (I2C) | Temperature, humidity and pressure; falling pressure helps storm warning | Not waterproof; about ±1 °C |
| SHT31 / SHT40 | More accurate humidity | Pricier; not waterproof |
| K-type thermocouple + MAX31855 | Measures fire-level heat (hundreds of °C) | Extra amplifier board; about ₹500–800 |
| DHT11 | Cheapest | Poor accuracy and range. **Not recommended.** |

## 10. Node wiring (from config.h, untested on hardware)

| Device pin | ESP32 pin | Note |
|---|---|---|
| RFM95W 3.3V / GND | 3V3 / GND | 3.3 V only. **Never connect to 5 V.** Fit the antenna first |
| RFM95W NSS (CS) | GPIO 5 | |
| RFM95W RST | GPIO 14 | |
| RFM95W DIO0 | GPIO 26 | |
| RFM95W SCK / MISO / MOSI | GPIO 18 / 19 / 23 | Default SPI pins |
| HC-SR04 VCC / GND | VIN (5 V) / GND | |
| HC-SR04 TRIG | GPIO 25 | |
| HC-SR04 ECHO | GPIO 33 | **Through a divider:** ECHO → 1 kΩ → GPIO 33 → 2 kΩ → GND |
| KY-026 VCC / GND / DO | 3V3 / GND / GPIO 27 | Powered at 3.3 V so its output is safe |
| **No MQ-2 yet:** GPIO 34 | GND | Jumper GPIO 34 to GND so it reads 0 and raises no false SMOKE alerts. Remove when the MQ-2 is fitted |
| MQ-2 VCC / GND (when bought) | VIN (5 V) / GND | Heater needs 5 V |
| MQ-2 AO (when bought) | GPIO 34 | **Through a divider:** AO → 1 kΩ → GPIO 34 → 2 kΩ → GND (second pair) |
| Rain plate board VCC / GND / AO | 3V3 / GND / GPIO 35 | |
| DS18B20 red / black | 3V3 / GND | |
| DS18B20 yellow (data) | GPIO 4 | 4.7 kΩ resistor between data and 3V3 |

**Notes:**
- The gateway only needs the RFM95W wired as in the first five rows, plus the ESP32 on USB.
- Total draw at peak is roughly 500 mA (MQ-2 heater, LoRa transmit, ESP32 Wi-Fi). Use a good USB cable; weak ones cause random resets.
- GPIO 34 and 35 are input-only, which is fine for these analog reads.
- Drivers: Windows needs the CP210x or CH340 USB driver. On Linux, add your user to the `uucp` group.

## 11. Next steps and what to avoid

- **Demo build:** your current kit plus an MQ-2, one node and one gateway.
- **More credible field node:** swap the rain plate for a tipping bucket, add a BME280 for pressure, and require flame + smoke + temperature to agree before a FIRE alert.
- **Not recommended:** the exposed water-level strip, the HC-SR04 outdoors, DHT11, the 433 MHz Ra-02 for India, and treating MQ-2 as a certified smoke detector.
