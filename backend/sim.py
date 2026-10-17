"""Fake sensor node for testing without hardware: python sim.py [url] [flood]"""
import json, random, sys, time, urllib.request

url = (sys.argv[1] if len(sys.argv) > 1 else "http://localhost:8000") + "/api/readings"
flood = "flood" in sys.argv
boot, seq = random.randrange(1 << 30), 0
while True:
    seq += 1
    r = {"node": "sim1", "boot": boot, "seq": seq,
         "level_cm": random.randint(60, 80) if flood else random.randint(5, 20),
         "rain": random.randint(0, 100), "temp_c": round(random.uniform(24, 34), 1),
         "smoke": random.randint(200, 800), "flame": 0}
    req = urllib.request.Request(url, json.dumps(r).encode(), {"Content-Type": "application/json"})
    print(seq, urllib.request.urlopen(req, timeout=3).read().decode())
    time.sleep(2)
