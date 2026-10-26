# Next Bus

Live Israeli bus arrivals on a Garmin watch (Forerunner 165/165m/255/255m/255s/255sm) and an Android
companion app. The watch never touches the network: the phone fetches from
[curlbus](https://curlbus.app), rate-limits, converts, and sends the watch a compact message over the
Connect IQ Mobile SDK.

```
 curlbus ──HTTP──▶ Android app ──Connect IQ SDK──▶ Garmin Connect ──BLE──▶ watch app + glance
 (arrivals)        core/: provider, converter,        (or: simulator over adb in dev)
                   rate limiter, relay engine
                   app/: Compose UI, selections,
                   RelayService (foreground)
```

## Repository layout
| path | what |
|---|---|
| `protocol/` | `nextbus.proto` (the contract), `PROTOCOL.md`, golden vectors, drift check `check.sh` |
| `android/protocol`, `android/core` | Kotlin: protobuf codegen, curlbus parser/provider, converter, coordinator (rate limit), relay engine, index updater. Pure JVM, unit-tested |
| `android/app` | UI (Compose), DataStore selections, index repository, `GarminLink`, `RelayService` |
| `watch/` | Monkey C app + glance, hand-written protobuf codec, relevance, operator logos |
| `tools/gtfs-index/` | builds the stop/line SQLite index from the Ministry of Transport GTFS |
| `tools/logos/` | operator logo SVG → per-screen PNG, dominant-colour tool |
| `tools/android/setup-sdk.sh` | installs the Android SDK pieces and the `nextbus` emulator |
| `.github/workflows/index.yml` | nightly index build, published as a release asset |

## Key decisions
- **Phone does all networking.** One curlbus request per refresh for all saved stops; `REFRESH_SECONDS` = 40 is
  the only rate-limit knob (`NextBusConfig.kt`). Watch page switches never cause fetches; the watch only asks
  the phone to refresh (`RefreshRequest`), the phone decides.
- **Transport.** API level 5.2.0 on all six devices cannot send `ByteArray`, so a protobuf `Envelope` is packed
  into a flat Number list: `[byteLength, w0, w1, …]`, 3 bytes per element, big-endian. See `PROTOCOL.md`.
  On `FAILURE_MESSAGE_TOO_LARGE` the phone retries with shorter names (18 chars).
- **Relay service.** Messages from the watch only arrive while the app process is alive, so the relay is a
  foreground service (`connectedDevice`), user-switchable ("Keep relay running").
- **Glance** reads cached Storage only (no network, no radio) and marks data older than 120 s.
- **Relevance** (default page): near + catchable, then far + catchable, then soonest overall. Walking time =
  distance / 1.3 m/s + 30 s, max 2 km. Pure functions in `Relevance.mc`, unit-tested.
- **Index.** The app bundles `index.db` and, if `INDEX_MANIFEST_URL` is set, downloads a newer prebuilt one
  weekly on unmetered networks (sha256 and schema version checked, swapped atomically).
- **No hard-coded location**; location is only used for "stops near me" (phone) and relevance (watch,
  `Positioning` permission). Phone permissions: INTERNET, foreground service, notifications, coarse/fine location.

## Where configuration lives
- Watch: [watch/source/Config.mc](../watch/source/Config.mc) (`FAKE_PHONE`, poll interval, walk constants, limits).
- Android: [NextBusConfig.kt](../android/core/src/main/kotlin/app/nextbus/core/NextBusConfig.kt) (refresh seconds,
  backoff, curlbus URL, max selections) and `android/app/build.gradle.kts` (`WATCH_APP_ID`, `INDEX_MANIFEST_URL`).
- App UUID `fc1842ca-f09c-444c-a9dc-572fdc5d0d2b` must match in `watch/manifest.xml` and `build.gradle.kts`.

## Build and run
**Prerequisites:** Connect IQ SDK 9.x + a developer key; JDK 21; Python 3.9+; Android SDK
(`tools/android/setup-sdk.sh`, then `export ANDROID_HOME=$HOME/Android/Sdk JAVA_HOME=<jdk21>`).

**Watch**
```
cd watch
monkeyc -d fr255 -f monkey.jungle -o bin/NextBus.prg -y <developer_key>      # also fr255s fr255m fr255sm fr165 fr165m
monkeydo bin/NextBus.prg fr255
monkeyc -d fr255 -f monkey.jungle -o bin/test.prg -y <key> --unit-test && monkeydo bin/test.prg fr255 -t   # 26 tests
```
**Set `FAKE_PHONE = false` in `Config.mc` for any real build.** With `true` the watch uses 8 built-in scenarios
and never talks to the phone (used for demos and screenshots).

**Index, then Android**
```
python3 tools/gtfs-index/build_index.py         # writes android/app/src/main/assets/index.db (build fails without it)
cd android && ./gradlew test                    # 47 unit tests
./gradlew :app:installDebug
./gradlew :protocol:generateVectors && ../protocol/check.sh    # after any protocol change
```
**Publishing the index:** push the repo to GitHub, run the "Publish stop index" workflow (or wait for the nightly
run), then set `INDEX_MANIFEST_URL` to
`https://github.com/<owner>/<repo>/releases/download/index-latest/manifest.json`.

**Phone + simulator (development):** start the emulator with `-gpu swiftshader_indirect`; enable
"Simulator mode (adb)" in the app; `adb forward tcp:7381 tcp:7381`; start the Connect IQ simulator
(`connectiq-launch`), load the watch app **first**, then *adb Connection → Start*. See limits below.

## Manual test checklist (real device unless stated)
1. Install the watch app (`.prg` sideload, or Connect IQ Store) and the phone app; pair in Garmin Connect.
2. Phone: add 2-3 stops/lines; they appear in "Watch selections"; relay card says "Connected: <watch>".
3. Watch app: pages show line, operator logo/colour, stop name (scrolls if long), minutes; UP/DOWN pages; default page is the relevant one.
4. Glance: shows next bus for the default selection; open the app from it.
5. Phone disconnected (Bluetooth off): watch shows cached data with "Phone offline"; reconnect recovers within ~1 min.
6. Phone app swiped away / relay switched off: watch shows "Phone app not responding"; reopening recovers.
7. Stale data (airplane mode on phone for >2 min): "Stale …" marker, no wrong minutes.
8. Version mismatch: install an older watch build; phone/watch show an update hint instead of garbage.
9. No service (Shabbat, night, invalid stop): "No service"/no-data states, no crash, no retry storm.
10. Rapid page switching: curlbus request count stays at one per ≥40 s (check the status card or adb logcat).
11. Selection limits: 10 selections accepted, 11th refused; long names still arrive (TOO_LARGE retry).
12. Index update: "Check for update" with a configured manifest; corrupted download is rejected.
13. Hebrew UI (phone locale he): RTL layout correct.

## Verified where (see the final report in `docs/REPORT.md`)
