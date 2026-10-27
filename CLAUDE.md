# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

Next Bus: live Israeli bus arrivals on a Garmin watch (Forerunner 165/255 families) plus an Android companion app. The watch never touches the network; the phone fetches from curlbus, rate-limits, converts, and sends compact protobuf messages over the Connect IQ Mobile SDK. [docs/README.md](docs/README.md) has the full overview, CI/release setup and a manual test checklist.

## Components
- `protocol/`: `nextbus.proto` is the single source of truth for the phone<->watch contract. Golden vectors (`vectors/vectors.json`) are produced by the Kotlin encoder; Monkey C tests are generated from them.
- `android/`: Gradle multi-module. `protocol` (protobuf codegen + `Transport`), `core` (pure JVM: curlbus parser/provider, converter, rate-limiting coordinator, relay engine, index updater, `MockWatch`), `app` (Compose UI, DataStore, `GarminLink`, foreground `RelayService`).
- `watch/`: Monkey C app + glance with a hand-written protobuf codec (`Proto.mc`), relevance logic (`Relevance.mc`), all tunables in `Config.mc`.
- `tools/`: GTFS stop-index builder, operator logo and app icon generators, Android SDK setup script.

## Commands
```
# Android (JDK 21, ANDROID_HOME set; tools/android/setup-sdk.sh installs the SDK)
python3 tools/gtfs-index/build_index.py            # REQUIRED first: writes git-ignored android/app/src/main/assets/index.db
cd android && ./gradlew test                       # all unit tests
./gradlew :core:test --tests 'app.nextbus.core.RelayEngineTest'   # a single test class
./gradlew :app:installDebug
./gradlew assembleRelease bundleRelease            # signing via NEXTBUS_KEYSTORE* env vars, else debug key

# Protocol (after ANY change to nextbus.proto / Vectors.kt / Proto.mc)
cd android && ./gradlew :protocol:generateVectors && python3 -I ../protocol/gen_mc_tests.py
protocol/check.sh                                  # drift check, also run in CI

# Watch (Connect IQ SDK 9.x + developer key; devices: fr165 fr165m fr255 fr255m fr255s fr255sm)
cd watch
monkeyc -d fr255 -f monkey.jungle -o bin/NextBus.prg -y <key>
monkeydo bin/NextBus.prg fr255
monkeyc -d fr255 -f monkey.jungle -o bin/test.prg -y <key> --unit-test && monkeydo bin/test.prg fr255 -t
```

## Architecture notes that span files
- **Protocol changes touch four places**: `nextbus.proto`, Kotlin encoder/`Vectors.kt`, `watch/source/Proto.mc` (decode + positional-layout comment at its top), and the generated `watch/source/tests/GoldenVectors.mc` (do not hand-edit). Never reuse field numbers. Bump `Transport.PROTOCOL_VERSION` and `Config.PROTOCOL_VERSION` together, and only for incompatible changes.
- **Transport**: the target devices are Connect IQ API 5.2.0 and cannot send `ByteArray`, so the encoded Envelope is packed as `[byteLength, w0, w1, ...]` (3 bytes per Number, big-endian). Implemented by `Transport` (Kotlin) and `Proto.pack/unpack` (Monkey C). On `FAILURE_MESSAGE_TOO_LARGE` the phone retries with shorter names.
- **Rate limiting lives on the phone only**: `REFRESH_SECONDS` (40) in `NextBusConfig.kt`; the watch just sends `RefreshRequest` and the phone decides. Watch page switches must never cause fetches.
- **Relay service** must stay a foreground service, since watch messages only arrive while the app process is alive.
- **`FAKE_PHONE`** in `watch/source/Config.mc` is committed as `true` (demo scenarios, no phone link). `watch.yml` forces it to `false` with `sed`, so keep the exact `const FAKE_PHONE = true;` / `const FAKE_SCENARIO_TAG = false;` lines intact. Set it to `false` yourself for any real device build, and don't commit that change.
- **Cross-file constants**: the app UUID must match in `watch/manifest.xml` and `android/app/build.gradle.kts` (`WATCH_APP_ID`); `INDEX_MANIFEST_URL` is also set in `build.gradle.kts`.
- **Stop index**: `index.db` is built from Ministry of Transport GTFS (ids verified against live curlbus, see `tools/gtfs-index/README.md`); CI bundles the nightly `index-latest` release asset. `tools/gtfs-index/verify_against_curlbus.py` checks it against live data.
- **No watch needed for development**: debug builds have a Home -> "Mock watch" switch backed by `MockWatch` in `android/core`, which also drives unit tests.
- Logos and icons are generated assets (`tools/logos/build.py`, `tools/app-icon/build.py`) written into `watch/resources*` and `android/app/src/main/res`; edit the SVG sources, not the PNGs.
