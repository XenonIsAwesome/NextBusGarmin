# GTFS index builder

Builds `android/app/src/main/assets/index.db`, the stop/line index bundled in the Android app
(search a stop, list the lines serving it, build a selection). No stop_times are shipped.

    python3 tools/gtfs-index/build_index.py          # one command, ~25 s plus the download

- **Input:** Israel Ministry of Transport GTFS, `https://gtfs.mot.gov.il/gtfsfiles/israel-public-transportation.zip`
  (open, no key, rebuilt nightly, ~190 MB; `stop_times.txt` is ~1 GB unzipped and is streamed).
  Cached in `tools/gtfs-index/cache/` (git-ignored); `--refresh` re-downloads, `--zip f.zip` uses a local file.
  Note: a `HEAD` request to that URL returns an HTML page, but `GET` returns the zip.
  If your HTTP client cannot verify the server's certificate chain, pass `--cafile bundle.pem`.
- **Dependencies:** none (Python >= 3.9 standard library), so there is nothing to pin. For a given zip the
  output is deterministic apart from the `built_at` timestamp in `meta`.
- **Output:** SQLite, schema in `build_index.py` (`SCHEMA`): `operators`, `stops` (code, Hebrew and English
  name, lat/lon in microdegrees, city), `routes` (one row per GTFS `route_id` = line + direction +
  alternative: line number, operator, destination), `route_stops` (the stops of one representative trip per
  route: the first trip of the route's most common shape), `meta`. Bus only (`route_type` 3).
- **Size:** 11.8 MB on disk, **~4.2 MB** compressed in the APK (29,858 stops, 6,591 routes, 223,459
  route-stop rows). The file itself is git-ignored; run the tool before building the app.

## English names
`translations.txt` maps the Hebrew text to English for ~92% of stop names (`name_en_src = 0`). The rest
(~2,300 stops, `name_en_src = 1`) are machine-transliterated, with a small glossary for common words
(school, hospital, blvd...). **Quality is low**: Hebrew has no vowels, so the output is a phonetic guess
("Hospital Belineson/Z'Botineseki"). The watch shows English only, so the Android app flags these for the
user to review/edit; curlbus's own English names (`stop_info.name.EN`) are another source. Operator
English names are a fixed table in the script (the GTFS has none).

## Identifiers (verified against live curlbus)
| index | curlbus |
|---|---|
| `stops.code` | `stop_code` (GTFS `stop_code`, not `stop_id`) |
| `routes.route_id` | `route_id` / `line_id` |
| `routes.line` | `line_name` |
| `routes.operator_id` | `operator_id` |
| `routes.dest_code` | `destination_id` (stop code of the trip's last stop) |
| `routes.direction` | `direction_id` (1/2, from `route_desc`, not the trip's 0/1) |

`verify_against_curlbus.py [index.db] [stop ...]` fetches live arrivals and checks every bus against the index
(one request per stop). Run on stops 36601 and 36602 (Ra'anana) and 1 (Jerusalem): 13 distinct buses,
0 mismatches.

## Cadence
Routes and stops change slowly but do change (new lines, moved stops). Rebuild **before each app release and
at least monthly**. A selection saved against an old `route_id` keeps working in curlbus as long as the
line and stop codes still exist; a new line simply will not be offered until the index is rebuilt.

## Known limits
- One representative trip per route: a route whose trips visit different stop sets (short-turns, branches)
  lists the most common variant only.
- Duplicate `stop_code`s (a station plus its platforms) share name and coordinates and are merged to one row.
