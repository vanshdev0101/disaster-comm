"""Self-check: python test_backend.py"""
import os, tempfile
os.environ["DB"] = os.path.join(tempfile.mkdtemp(), "t.db")
import server as s

s.init()
assert s.ingest({"node": "n1", "boot": 1, "seq": 1, "level_cm": 70, "flame": 1})
assert not s.ingest({"node": "n1", "boot": 1, "seq": 1, "level_cm": 70}), "dup must be ignored"
assert s.ingest({"node": "n1", "boot": 2, "seq": 1, "level_cm": 10}), "reboot (new boot id) must be accepted"
st = s.state()
assert st["nodes"][0]["data"]["level_cm"] == 10 and st["nodes"][0]["alerts"] == []
s.ingest({"node": "n2", "boot": 1, "seq": 1, "level_cm": 70, "flame": 1, "via": "lora", "rssi": -90})
n2 = [n for n in s.state()["nodes"] if n["node"] == "n2"][0]
assert n2["alerts"] == ["FLOOD", "FIRE"] and n2["via"] == "lora"
s.set_resource(1, "deployed")
assert s.state()["resources"][0]["status"] == "deployed"
print("ok")
