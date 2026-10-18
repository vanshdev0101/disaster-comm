#!/usr/bin/env python3
"""USB bridge: gateway -> server, for when Wi-Fi is down.

The gateway prints every LoRa packet it receives on its USB serial port. This reads those lines and posts them to the
local server, so alerts still reach the dashboard when there is no Wi-Fi at all. Leave it running next to the server.
The server ignores a reading it already has (same node/boot/seq), so running this alongside the gateway's own Wi-Fi forwarding is safe.

  pip install pyserial
  python gateway-bridge.py                      # uses the only USB serial port, if there is exactly one
  python gateway-bridge.py /dev/ttyUSB0         # Windows: python gateway-bridge.py COM5
  python gateway-bridge.py /dev/ttyUSB0 http://localhost:8000/api/readings
"""
import sys, time, urllib.request
import serial, serial.tools.list_ports

URL = sys.argv[2] if len(sys.argv) > 2 else "http://localhost:8000/api/readings"

def pick_port():
    if len(sys.argv) > 1:
        return sys.argv[1]
    ports = [p.device for p in serial.tools.list_ports.comports() if "USB" in p.device.upper() or p.device.upper().startswith("COM")]
    if len(ports) != 1:
        sys.exit(f"found {len(ports)} serial ports {ports}: pass the gateway's port as the first argument")
    return ports[0]

def post(line):
    req = urllib.request.Request(URL, line.encode(), {"Content-Type": "application/json"})
    try:
        with urllib.request.urlopen(req, timeout=3) as r:
            return r.read().decode()
    except Exception as e:
        return f"FAILED ({e})"

port = pick_port()
print(f"bridging {port} -> {URL}", flush=True)
while True:
    try:
        s = serial.Serial()
        s.port, s.baudrate, s.timeout = port, 115200, 1
        s.dtr = s.rts = False  # don't reset the board when opening
        s.open()
        while True:
            line = s.readline().decode(errors="replace").strip()
            # only LoRa packets: the node's own serial output has no "via":"lora" and must not be bridged
            if line.startswith("{") and line.endswith("}") and '"via":"lora"' in line:
                print(time.strftime("%H:%M:%S"), post(line), line[:90], flush=True)
    except (serial.SerialException, OSError) as e:
        print("port lost, retrying:", e, flush=True)
        time.sleep(2)
