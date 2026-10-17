# Gateway Wiring Guide

**ESP32 + Ra-02 LoRa radio (433 MHz)** — the control-station receiver. About 20 minutes plus soldering the header pins. No multimeter needed.

## What you need

| Qty | Part |
|---|---|
| 1 | ESP32 DevKit taped **GATEWAY** (MAC ending `b5:cc`) |
| 1 | Ra-02 LoRa module (SX1278, 410–525 MHz) on its blue breakout, with its **two 8-pin header strips soldered on** |
| 1 | Antenna: SMA whip with its u.FL pigtail cable (check it is marked 433 MHz) |
| 1 | 830-point breadboard |
| 9 | Male-to-male jumper wires (8 used, 1 spare) |
| 1 | 100–470 µF electrolytic capacitor |
| 1 | USB data cable |

## Rules for this build

1. **USB unplugged** while you place parts and wires.
2. **3.3 V only** for the radio. Use the pin labelled **3V3**, never **VIN** or **5V**. 5 V destroys the module instantly.
3. **Antenna on before power**, even though the gateway mostly listens.
4. **Read your breakout's printed labels.** Pin order differs between breakout boards, so match by **name**, not position.

## Pin map

Only these radio pins are used. Leave DIO1–DIO5 unconnected. Your breakout prints: left side GND, GND, 3.3V, RST, DIO0, DIO1, DIO2, DIO3; right side GND, NSS, MOSI, MISO, SCK, DIO5, DIO4, GND.

| Ra-02 pin (printed name) | Wire to ESP32 pin | Purpose |
|---|---|---|
| **3.3V** (or VCC / 3V3) | **3V3** | Power |
| **GND** (any one) | **GND** | Ground |
| **NSS** (or CS / SS) | **GPIO 5** (D5) | Chip select |
| **SCK** (or CLK) | **GPIO 18** (D18) | SPI clock |
| **MISO** | **GPIO 19** (D19) | Data from radio |
| **MOSI** | **GPIO 23** (D23) | Data to radio |
| **RST** (or RESET) | **GPIO 14** (D14) | Radio reset |
| **DIO0** (or G0) | **GPIO 26** (D26) | Packet-received signal |
| **u.FL socket** (top-left of the module) | Antenna pigtail | See step 2 |

```
 ESP32                        Ra-02 breakout
 ┌─────────┐                  ┌──────────────┐
 │ 3V3  ───┼──────────────────┼─ 3.3V        │
 │ GND  ───┼──────────────────┼─ GND         │
 │ GPIO 5  ┼──────────────────┼─ NSS         │
 │ GPIO 18 ┼──────────────────┼─ SCK         │
 │ GPIO 19 ┼──────────────────┼─ MISO        │
 │ GPIO 23 ┼──────────────────┼─ MOSI        │
 │ GPIO 14 ┼──────────────────┼─ RST         │
 │ GPIO 26 ┼──────────────────┼─ DIO0        │
 │         │                  │  u.FL ── antenna
 └─────────┘                  └──────────────┘
```

**Pitfalls**
- **MISO and MOSI swapped** is the most common mistake. The radio then reports "init failed".
- Pin labels on DevKits vary (`D5`, `IO5`, `GPIO5`). They all mean the same GPIO.

## Steps

### 0. Solder the header pins first
The two 8-pin strips are loose in the bag. They must be soldered into the breakout's holes, **long pins pointing down**, so the module can plug into the breadboard.

- Ask any electronics shop. It takes about 5 minutes, 16 joints.
- Or borrow a soldering iron from a lab or friend.
- Do **not** rely on pushing loose pins into the holes. That contact is unreliable and causes confusing failures.

### 1. Place the parts (USB unplugged)
1. Push the **ESP32** into the breadboard so it straddles the centre channel, with the USB port at the end of the board. Keep its pin rows free on both sides.
2. Push the **Ra-02 breakout** into a clear area of the same breadboard, away from the ESP32's pins, with its pin labels readable.

### 2. Antenna
- Press the **u.FL plug** of the pigtail straight down onto the small u.FL socket on the module (top-left, near the metal can) until it clicks. It is delicate: press, don't twist.
- Screw the **whip antenna** onto the SMA end of the pigtail.
- The module must never be powered without this antenna fitted.

### 3. Wire it, in this order
1. **GND** radio (any GND pin) → ESP32 **GND**
2. **NSS** → GPIO 5 (right column, second from top)
3. **SCK** → GPIO 18
4. **MISO** → GPIO 19
5. **MOSI** → GPIO 23
6. **RST** → GPIO 14
7. **DIO0** → GPIO 26
8. **3.3V** radio → ESP32 **3V3** (last)

### 4. Capacitor
Place the electrolytic capacitor across the radio's power: **long leg (+) in the 3.3 V row, short leg / striped side (−) in the GND row**, close to the radio. It smooths the power spikes when the radio transmits.

### 5. Check before power (2 minutes)
- [ ] The only wire on **3V3** goes to the radio's 3.3 V pin. Nothing on **VIN** or **5V**.
- [ ] Each of the 6 signal wires goes to the pin named in the table, with MISO and MOSI not swapped.
- [ ] No two wires share a breadboard column by accident.
- [ ] The capacitor's striped leg goes to GND.
- [ ] The antenna is attached (u.FL clicked on, whip screwed on).

### 6. Flash and test
Plug in USB, then in a terminal:
```
source ~/.cache/pio-venv/bin/activate.fish
cd ~/disaster-comm/firmware
pio run -e gateway -t upload
pio device monitor -b 115200
```
Press the **EN** button on the ESP32 if you don't see boot text.

| Output | Meaning |
|---|---|
| `LoRa ready` | **Pass.** The radio answered over SPI, so power, NSS, SCK, MISO, MOSI and RST are all correct. |
| `LoRa init failed (check wiring/frequency)` | Wiring fault. See below. |

`LoRa ready` does **not** prove DIO0 or the antenna. Those are proved in the node-to-gateway test later.

## If it fails

1. **Unplug USB first.**
2. Recheck MISO/MOSI, then NSS and SCK, against the table.
3. Recheck that the radio's 3.3 V is on **3V3** and that GND is common.
4. Press each wire firmly. Breadboard contacts are often loose.
5. Try a different breadboard row for the radio.
6. **If the radio feels hot or smells,** unplug it. It may have been powered from 5 V.

If it still fails, send me the exact monitor text and the printed labels on your breakout.

## Frequency note
Both radios must use the same band. This kit is **433 MHz**, so `LORA_FREQ` in `firmware/include/config.h` is set to `433E6` and both boards share it. 433 MHz low-power devices are commonly used in India; I could not verify the exact rule here, so keep transmit power at default and use it for a desk or classroom demo.

## Next
Once the gateway shows `LoRa ready`, do the same radio wiring on the node (the pin map is identical), then add one sensor at a time.
