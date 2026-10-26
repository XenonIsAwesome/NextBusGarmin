#!/usr/bin/env python3
"""Builds the stop/line index shipped inside the Android app.

    python3 tools/gtfs-index/build_index.py            # download GTFS (cached) and build
    python3 tools/gtfs-index/build_index.py --zip f.zip --out index.db

Source: Israel Ministry of Transport GTFS (open, no key, updated nightly):
https://gtfs.mot.gov.il/gtfsfiles/israel-public-transportation.zip  (~190 MB; stop_times is ~1 GB
unzipped, streamed, never written to disk). Standard library only (Python >= 3.9), so there is
nothing to pin; the output is deterministic for a given zip.

Output: SQLite (see SCHEMA below): bus stops, bus routes (one row per GTFS route_id, i.e. per
line + direction + alternative), and the stops of one representative trip per route. stop_times
is not shipped. Identifiers match curlbus (verified by verify_against_curlbus.py):
  stops.code      == curlbus stop_code            (GTFS stop_code, NOT stop_id)
  routes.route_id == curlbus route_id / line_id   (GTFS route_id)
  routes.line     == curlbus line_name            (GTFS route_short_name)
  routes.operator_id == curlbus operator_id       (GTFS agency_id)
  routes.dest_code   == curlbus destination_id    (stop_code of the trip's last stop)
"""
import argparse, collections, csv, datetime, io, os, re, sqlite3, ssl, sys, urllib.request, zipfile

URL = "https://gtfs.mot.gov.il/gtfsfiles/israel-public-transportation.zip"
HERE = os.path.dirname(os.path.abspath(__file__))
DEFAULT_OUT = os.path.join(HERE, "..", "..", "android", "app", "src", "main", "assets", "index.db")
SCHEMA_VERSION = 1
BUS_ROUTE_TYPE = "3"

# English operator names (Ministry operator ids, as listed by https://curlbus.app/operators);
# agency names have no English translation in the GTFS translations.txt.
OPERATORS_EN = {
    2: "Israel Railways", 3: "Egged", 4: "Egged Taavura", 5: "Dan", 6: "Nazareth UNBS",
    7: "Nazareth Transport & Tourism", 8: "GB Tours", 10: "Eilot Regional Council",
    14: "Nativ Express", 15: "Metropoline", 16: "Superbus", 18: "Kavim", 20: "Carmelit",
    21: "Citypass", 23: "Galim", 24: "Golan Regional Council",
}

SCHEMA = """
CREATE TABLE meta(key TEXT PRIMARY KEY, value TEXT) WITHOUT ROWID;
CREATE TABLE operators(operator_id INTEGER PRIMARY KEY, name_he TEXT NOT NULL, name_en TEXT NOT NULL);
-- lat_e6/lon_e6 are microdegrees, the unit of the watch protocol. name_en_src: 0 = official
-- GTFS translation, 1 = machine transliteration (low quality, show it to the user for review).
CREATE TABLE stops(
  code TEXT PRIMARY KEY, name_he TEXT NOT NULL, name_en TEXT NOT NULL, name_en_src INTEGER NOT NULL,
  lat_e6 INTEGER NOT NULL, lon_e6 INTEGER NOT NULL, city_he TEXT NOT NULL, city_en TEXT NOT NULL
) WITHOUT ROWID;
-- direction/alt come from route_desc ("<line id>-<dir>-<alt>"); direction is curlbus's direction_id (1/2).
-- dest_* is the last stop of the representative trip, as in curlbus's destination.
CREATE TABLE routes(
  route_id INTEGER PRIMARY KEY, operator_id INTEGER NOT NULL, line TEXT NOT NULL,
  direction INTEGER NOT NULL, alt TEXT NOT NULL, from_he TEXT NOT NULL, to_he TEXT NOT NULL,
  dest_code TEXT NOT NULL, dest_he TEXT NOT NULL, dest_en TEXT NOT NULL, headsign_he TEXT NOT NULL
);
CREATE TABLE route_stops(route_id INTEGER NOT NULL, seq INTEGER NOT NULL, stop_code TEXT NOT NULL,
  PRIMARY KEY(route_id, seq)) WITHOUT ROWID;
CREATE INDEX route_stops_by_stop ON route_stops(stop_code, route_id);
CREATE INDEX routes_by_line ON routes(line);
CREATE INDEX stops_by_city ON stops(city_en);
"""

# --- English names ---------------------------------------------------------------------------
# GTFS translations.txt maps the Hebrew text itself (trans_id) to EN. Where it has no entry we
# transliterate. Hebrew is written without vowels, so this is a rough phonetic guess ("bt lwynshtyyn"
# style output with inserted vowels): good enough to recognise a stop, not to publish. Such names
# are flagged (name_en_src = 1) so the app can ask the user to review them. A small glossary
# translates the most common words (school, hospital, blvd, ...).
_HEB = {"א": "", "ב": "b", "ג": "g", "ד": "d", "ה": "h", "ו": "o", "ז": "z", "ח": "ch", "ט": "t",
        "י": "i", "כ": "k", "ך": "k", "ל": "l", "מ": "m", "ם": "m", "נ": "n", "ן": "n", "ס": "s",
        "ע": "", "פ": "p", "ף": "f", "צ": "tz", "ץ": "tz", "ק": "k", "ר": "r", "ש": "sh", "ת": "t",
        "'": "'", '"': ""}
_VOWELS = set("aeiou")
# Very common words and abbreviations in stop names, translated rather than transliterated.
# Keys are whole words after quote marks are removed (the abbreviation for "school" is written
# with a doubled apostrophe in the data).
_GLOSSARY = {
    "ביס": "School", "ביהס": "School", "ביח": "Hospital", "שד": "Blvd", "שדרות": "Blvd",
    "רח": "St", "רחוב": "St", "כיכר": "Square", "קניון": "Mall", "מסוף": "Terminal", "ת": "Station",
    "תחנה": "Station", "תחנת": "Station", "רכבת": "Train", "צהל": "IDF", "מרכז": "Center",
    "מסחרי": "Commercial", "אוניברסיטת": "University", "אוניברסיטה": "University", "בנק": "Bank",
    "דואר": "Post Office", "פארק": "Park", "גן": "Garden", "צומת": "Junction", "כניסה": "Entrance",
    "יציאה": "Exit", "הורדה": "Drop-off", "איסוף": "Pickup", "מזרח": "East", "מערב": "West",
    "צפון": "North", "דרום": "South", "מרכזית": "Central", "אזור": "Area", "איזור": "Area",
    "תעשיה": "Industrial", "יסודי": "Elementary", "קופח": "Clinic",
}




def transliterate(text):
    out = []
    for word in re.split(r"(\W+)", text.replace("''", "").replace('"', "")):
        if not re.search("[א-ת]", word):
            out.append(word)
            continue
        if word in _GLOSSARY:
            out.append(_GLOSSARY[word])
            continue
        letters = [c for c in word if c in _HEB or "א" <= c <= "ת"]
        s = ""
        for i, c in enumerate(letters):
            piece = _HEB.get(c, "")
            if c == "ו" and i == 0:
                piece = "v"
            if c == "י" and i == 0:
                piece = "y"
            # insert an 'e' between two consonant sounds so the word is pronounceable
            if s and piece and s[-1] not in _VOWELS and piece[0] not in _VOWELS:
                s += "e"
            s += piece
        out.append(s.capitalize() if s else "")
    return "".join(out)


def english(text, en_map):
    """Returns (english, source) with source 0 = translation, 1 = transliteration."""
    if text in en_map:
        return en_map[text], 0
    return transliterate(text), 1


# --- download / read -------------------------------------------------------------------------
def download(dest, cafile=None):
    ctx = ssl.create_default_context(cafile=cafile) if cafile else ssl.create_default_context()
    print("downloading", URL, file=sys.stderr)
    req = urllib.request.Request(URL, headers={"User-Agent": "nextbus-index-builder"})
    tmp = dest + ".part"
    with urllib.request.urlopen(req, context=ctx, timeout=60) as r, open(tmp, "wb") as f:
        total = 0
        while chunk := r.read(1 << 20):
            f.write(chunk)
            total += len(chunk)
        print("  %.1f MB" % (total / 1e6), file=sys.stderr)
        modified = r.headers.get("Last-Modified", "")
    os.replace(tmp, dest)
    open(dest + ".modified", "w").write(modified)


def rows(z, name):
    with z.open(name) as f:
        yield from csv.DictReader(io.TextIOWrapper(f, encoding="utf-8-sig", newline=""))


def micro(v):
    return int(round(float(v) * 1_000_000))


CITY_RE = re.compile(r"עיר:\s*(.*?)\s*רציף:")


def build(zip_path, out_path, modified=""):
    z = zipfile.ZipFile(zip_path)
    en_map = {r["trans_id"]: r["translation"] for r in rows(z, "translations.txt") if r["lang"] == "EN"}

    agencies = {int(a["agency_id"]): a["agency_name"] for a in rows(z, "agency.txt")}
    routes = {r["route_id"]: r for r in rows(z, "routes.txt") if r["route_type"] == BUS_ROUTE_TYPE}
    print("bus routes:", len(routes), file=sys.stderr)

    # Representative trip per route: the first trip (by trip_id) of its most common shape.
    shape_count = collections.Counter()
    first_trip = {}
    for t in rows(z, "trips.txt"):
        rid = t["route_id"]
        if rid not in routes:
            continue
        shape_count[(rid, t["shape_id"])] += 1
        key = (rid, t["shape_id"])
        if key not in first_trip or t["trip_id"] < first_trip[key]["trip_id"]:
            first_trip[key] = t
    rep = {}
    for (rid, shape), n in sorted(shape_count.items(), key=lambda kv: (kv[0][0], -kv[1], kv[0][1])):
        rep.setdefault(rid, first_trip[(rid, shape)])
    trip_route = {t["trip_id"]: rid for rid, t in rep.items()}
    print("representative trips:", len(trip_route), file=sys.stderr)

    stop_by_id = {s["stop_id"]: s for s in rows(z, "stops.txt")}
    print("stops:", len(stop_by_id), file=sys.stderr)

    # Stream stop_times (~1 GB), keeping only rows of representative trips.
    seqs = collections.defaultdict(list)
    with z.open("stop_times.txt") as f:
        header = f.readline().decode("utf-8-sig").strip().split(",")
        i_trip, i_stop, i_seq = header.index("trip_id"), header.index("stop_id"), header.index("stop_sequence")
        wanted = trip_route.keys()
        for line in f:
            p = line.decode("utf-8").split(",")
            if p[i_trip] in wanted:
                seqs[p[i_trip]].append((int(p[i_seq]), p[i_stop]))
    print("trips with stop times:", len(seqs), file=sys.stderr)

    if os.path.exists(out_path):
        os.remove(out_path)
    os.makedirs(os.path.dirname(os.path.abspath(out_path)), exist_ok=True)
    db = sqlite3.connect(out_path)
    db.executescript("PRAGMA page_size=4096;" + SCHEMA)

    used_stop_ids = set()
    route_rows, rs_rows = [], []
    for trip_id, seq in seqs.items():
        rid = trip_route[trip_id]
        r, t = routes[rid], rep[rid]
        seq.sort()
        codes = []
        for _, sid in seq:
            s = stop_by_id.get(sid)
            if s is None:
                continue
            used_stop_ids.add(sid)
            codes.append(s["stop_code"])
        if not codes:
            continue
        last = stop_by_id[seq[-1][1]]
        desc = r["route_desc"].split("-")           # "<line id>-<direction>-<alt>"
        alt = desc[2] if len(desc) > 2 else ""
        long_name = r["route_long_name"].split("<->")
        from_he = long_name[0] if len(long_name) == 2 else ""
        to_he = re.sub(r"-\d+#?[^-]*$", "", long_name[1]) if len(long_name) == 2 else ""
        dest_en, _ = english(last["stop_name"], en_map)
        # curlbus's direction_id is the route_desc direction (1/2), not the trip's 0/1.
        direction = int(desc[1]) if len(desc) > 1 and desc[1].isdigit() else int(t["direction_id"] or 0)
        route_rows.append((int(rid), int(r["agency_id"]), r["route_short_name"], direction, alt,
                           from_he, to_he, last["stop_code"], last["stop_name"], dest_en, t["trip_headsign"]))
        rs_rows += [(int(rid), n, c) for n, c in enumerate(codes)]
    route_rows.sort()
    rs_rows.sort()

    # One row per stop_code. Duplicated codes (a station plus its platforms) share name and
    # coordinates, so any used stop_id of the code will do; take the lowest.
    translit = 0
    stop_rows = []
    emitted = set()
    for sid in sorted(used_stop_ids, key=int):
        s = stop_by_id[sid]
        if s["stop_code"] in emitted:
            continue
        emitted.add(s["stop_code"])
        city_he = (CITY_RE.search(s["stop_desc"]) or [None, ""])[1]
        name_en, src = english(s["stop_name"], en_map)
        city_en, _ = english(city_he, en_map) if city_he else ("", 0)
        translit += src
        stop_rows.append((s["stop_code"], s["stop_name"], name_en, src, micro(s["stop_lat"]), micro(s["stop_lon"]), city_he, city_en))

    ops = sorted({r[1] for r in route_rows})
    db.executemany("INSERT INTO operators VALUES (?,?,?)",
                   [(o, agencies.get(o, str(o)), OPERATORS_EN.get(o) or agencies.get(o, str(o))) for o in ops])
    db.executemany("INSERT INTO stops VALUES (?,?,?,?,?,?,?,?)", stop_rows)
    db.executemany("INSERT INTO routes VALUES (?,?,?,?,?,?,?,?,?,?,?)", route_rows)
    db.executemany("INSERT INTO route_stops VALUES (?,?,?)", rs_rows)
    meta = {"schema_version": SCHEMA_VERSION, "source": URL, "source_last_modified": modified,
            "built_at": datetime.datetime.now(datetime.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
            "stops": len(stop_rows), "routes": len(route_rows), "route_stops": len(rs_rows),
            "stops_transliterated": translit}
    db.executemany("INSERT INTO meta VALUES (?,?)", [(k, str(v)) for k, v in meta.items()])
    db.commit()
    db.execute("VACUUM")
    db.close()
    print("wrote %s (%.1f MB): %s" % (out_path, os.path.getsize(out_path) / 1e6, meta), file=sys.stderr)


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--zip", help="use this GTFS zip instead of downloading")
    ap.add_argument("--out", default=DEFAULT_OUT)
    ap.add_argument("--cache-dir", default=os.path.join(HERE, "cache"))
    ap.add_argument("--cafile", help="CA bundle for the download (some HTTP clients need the chain supplied)")
    ap.add_argument("--refresh", action="store_true", help="re-download even if cached")
    a = ap.parse_args()
    zip_path = a.zip
    modified = ""
    if not zip_path:
        os.makedirs(a.cache_dir, exist_ok=True)
        zip_path = os.path.join(a.cache_dir, "israel-public-transportation.zip")
        if a.refresh or not os.path.exists(zip_path):
            download(zip_path, a.cafile)
    if os.path.exists(zip_path + ".modified"):
        modified = open(zip_path + ".modified").read()
    build(zip_path, a.out, modified)


if __name__ == "__main__":
    main()
