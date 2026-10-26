# Status report

## Verified by automated tests (no device)
- Protocol: Kotlin encoder, golden vectors and generated Monkey C tests agree (`protocol/check.sh`).
- Android `core` + `protocol`: 47 unit tests (curlbus parsing on real fixtures, converter, provider, 40 s rate
  limiter and backoff, relay engine incl. TOO_LARGE retry, index updater checksum/schema, geo).
- Watch: 26 Monkey Unit tests on fr255 (formatting, relevance, protocol vectors); all six devices compile.
- GTFS index: built from the live Ministry GTFS (29,858 stops, 6,591 routes) and cross-checked against live curlbus
  identifiers (`verify_against_curlbus.py`).

## Verified in the Connect IQ simulator / Android emulator
- Watch UI on fr255 (and fr165/fr255s builds render): paging, operator logos and colours, stop-name scroll,
  glance, offline/"not responding" states, 8 fake scenarios (`FAKE_PHONE = true`).
- Android UI on the emulator: search (Hebrew/English), stops near me, lines list, add/remove/reorder, relay card,
  Hebrew strings.
- **Phone → watch over the simulator link:** the relay connected to the simulator over adb and the watch app
  displayed the selection sent by the phone (SelectionsSync).

## Not verified / needs a real device
- **Watch → phone and the full loop in the simulator.** The Connect IQ simulator (9.2.0, Linux) segfaulted
  15-30 s after the adb connection was started, in three consecutive runs, before any message from the watch
  reached the phone. The binary is stripped, so no useful stack. The HelloRequest/RefreshRequest direction and
  ArrivalsUpdate display over a live link are therefore untested end to end (each side is unit-tested).
- Real Garmin Connect path (`WIRELESS`): pairing, app discovery, message size limit (unmeasured; the
  TOO_LARGE fallback exists), delivery while the phone is idle/locked, foreground-service behaviour on OEM Androids.
- Battery cost of the relay and of the watch's 40 s polling while the app is open.
- MIP (fr255) colour fidelity on hardware; AMOLED (fr165) true-colour rendering on hardware.
- Index update download (needs `INDEX_MANIFEST_URL`; unit-tested only) and the GitHub workflow (not yet run).

## Limits and known issues
- `FAKE_PHONE` must be `false` for real builds (currently `true` in the repo for demos).
- Watch footer can show a nonsense age ("Updated 498069h ago") when stored `generatedAt` is a tiny value
  (seen after seeded simulator storage); not reproduced from a clean install.
- Phone status text for a failed send is the raw `FAILED`; needs a friendlier message, and "no watch
  connected" should not be reported as a problem.
- English stop names: ~8% are machine transliterations and flagged for user review.
- Phone only receives watch messages while the relay service runs; swiping the app away without the service
  means "Phone app not responding" on the watch.
- API 5.2.0 forces the integer-list transport (~33% overhead); curlbus is a third-party service with no SLA.
