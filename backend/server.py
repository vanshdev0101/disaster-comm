"""Control-station backend: stdlib only, so it runs the same on Windows and Linux.
Run: python server.py   (PORT and DB env vars override defaults)"""
import json, os, sqlite3, time
from contextlib import closing
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

HERE = os.path.dirname(os.path.abspath(__file__))
DB = os.environ.get("DB", os.path.join(HERE, "data.db"))
PORT = int(os.environ.get("PORT", 8000))

# ponytail: fixed thresholds, per-site calibration/config file when real sensors arrive
def alerts_for(r):
    a = []
    if r.get("level_cm", 0) >= 50: a.append("FLOOD")
    if r.get("flame"): a.append("FIRE")
    if r.get("smoke", 0) >= 2000: a.append("SMOKE")
    if r.get("temp_c", 0) >= 50: a.append("HEAT")
    if r.get("rain", 0) >= 80: a.append("HEAVY_RAIN")
    return a

SEED = [("Rescue boat", "available"), ("Ambulance", "available"),
        ("Fire engine", "available"), ("Relief food kits", "available")]

def db():
    c = sqlite3.connect(DB)
    c.row_factory = sqlite3.Row
    return c

def init():
    with closing(db()) as c:
        c.executescript("""
        CREATE TABLE IF NOT EXISTS readings(
          id INTEGER PRIMARY KEY, node TEXT, boot INTEGER, seq INTEGER, recv REAL,
          via TEXT, rssi INTEGER, data TEXT, alerts TEXT, UNIQUE(node, boot, seq));
        CREATE TABLE IF NOT EXISTS resources(
          id INTEGER PRIMARY KEY, name TEXT, status TEXT);""")
        if not c.execute("SELECT 1 FROM resources").fetchone():
            c.executemany("INSERT INTO resources(name,status) VALUES(?,?)", SEED)
        c.commit()

def ingest(r):
    """Insert one reading. Duplicates (same node/boot/seq, e.g. LoRa copy + later Wi-Fi replay) are ignored."""
    al = alerts_for(r)
    with closing(db()) as c:
        cur = c.execute(
            "INSERT OR IGNORE INTO readings(node,boot,seq,recv,via,rssi,data,alerts) VALUES(?,?,?,?,?,?,?,?)",
            (r["node"], r.get("boot", 0), r["seq"], time.time(), r.get("via", "wifi"),
             r.get("rssi"), json.dumps(r), ",".join(al)))
        c.commit()
        return cur.rowcount == 1

def state():
    with closing(db()) as c:
        nodes = [dict(row) for row in c.execute(
            "SELECT node,recv,via,rssi,data,alerts FROM readings WHERE id IN "
            "(SELECT MAX(id) FROM readings GROUP BY node) ORDER BY node")]
        for n in nodes:
            n["data"] = json.loads(n["data"])
            n["alerts"] = [x for x in n["alerts"].split(",") if x]
        res = [dict(row) for row in c.execute("SELECT * FROM resources ORDER BY id")]
    return {"now": time.time(), "nodes": nodes, "resources": res}

def set_resource(rid, status):
    with closing(db()) as c:
        c.execute("UPDATE resources SET status=? WHERE id=?", (status, rid))
        c.commit()

class H(BaseHTTPRequestHandler):
    def _send(self, code, body, ctype="application/json"):
        b = body if isinstance(body, bytes) else json.dumps(body).encode()
        self.send_response(code)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(b)))
        self.end_headers()
        self.wfile.write(b)

    def _json(self):
        return json.loads(self.rfile.read(int(self.headers.get("Content-Length", 0))) or b"{}")

    def do_GET(self):
        if self.path == "/api/state": return self._send(200, state())
        if self.path in ("/", "/index.html"):
            with open(os.path.join(HERE, "dashboard.html"), "rb") as f:
                return self._send(200, f.read(), "text/html; charset=utf-8")
        self._send(404, {"error": "not found"})

    def do_POST(self):
        try:
            body = self._json()
            if self.path == "/api/readings":
                if "node" not in body or "seq" not in body:
                    return self._send(400, {"error": "node and seq required"})
                return self._send(200, {"stored": ingest(body)})
            if self.path == "/api/resources":
                if body.get("status") not in ("available", "deployed", "unavailable"):
                    return self._send(400, {"error": "bad status"})
                set_resource(int(body["id"]), body["status"])
                return self._send(200, {"ok": True})
        except (ValueError, KeyError, TypeError):
            return self._send(400, {"error": "bad json"})
        self._send(404, {"error": "not found"})

    def log_message(self, *a): pass

if __name__ == "__main__":
    init()
    print(f"http://localhost:{PORT}  (db: {DB})")
    ThreadingHTTPServer(("0.0.0.0", PORT), H).serve_forever()
