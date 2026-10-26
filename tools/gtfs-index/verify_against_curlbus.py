#!/usr/bin/env python3
"""Checks the index against live curlbus responses: every arriving bus at the given stops
must exist in the index with the same route/line/operator/destination, and the stop must be on
that route. Usage: verify_against_curlbus.py [index.db] [stop_code ...]   (default: 3 stops)
Makes one curlbus request per stop; run it sparingly."""
import json, os, sqlite3, sys, urllib.request

HERE = os.path.dirname(os.path.abspath(__file__))
args = sys.argv[1:]
db_path = args.pop(0) if args and args[0].endswith(".db") else os.path.join(HERE, "..", "..", "android", "app", "src", "main", "assets", "index.db")
stops = args or ["36601", "36602", "1"]
db = sqlite3.connect(db_path)
bad = checked = 0
for code in stops:
    req = urllib.request.Request("https://curlbus.app/" + code, headers={"Accept": "application/json"})
    data = json.load(urllib.request.urlopen(req, timeout=20))
    visits = (data.get("visits") or {}).get(code, [])
    stop = db.execute("select name_en, lat_e6, lon_e6 from stops where code=?", (code,)).fetchone()
    print("stop %s: index=%s, curlbus visits=%d" % (code, stop and stop[0], len(visits)))
    if stop is None:
        bad += 1
        print("  MISSING stop in index")
    seen = set()
    for v in visits:
        key = (v["route_id"], v["line_name"], v["operator_id"], v["destination_id"], v["direction_id"])
        if key in seen:
            continue
        seen.add(key)
        checked += 1
        r = db.execute("select line, operator_id, dest_code, direction from routes where route_id=?", (int(v["route_id"]),)).fetchone()
        on_route = db.execute("select 1 from route_stops where route_id=? and stop_code=?", (int(v["route_id"]), code)).fetchone()
        problems = []
        if r is None:
            problems.append("route_id not in index")
        else:
            if r[0] != v["line_name"]: problems.append("line %s != %s" % (r[0], v["line_name"]))
            if str(r[1]) != v["operator_id"]: problems.append("operator %s != %s" % (r[1], v["operator_id"]))
            if r[2] != v["destination_id"]: problems.append("dest %s != %s" % (r[2], v["destination_id"]))
            if str(r[3]) != v["direction_id"]: problems.append("direction %s != %s" % (r[3], v["direction_id"]))
        if r and not on_route:
            problems.append("stop not on route")
        if problems:
            bad += 1
        print("  line %-4s route %-6s op %-3s dest %-6s %s" % (v["line_name"], v["route_id"], v["operator_id"], v["destination_id"], "OK" if not problems else "MISMATCH " + "; ".join(problems)))
print("checked %d distinct buses, %d problems" % (checked, bad))
sys.exit(1 if bad else 0)
