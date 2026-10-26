# Phone <-> watch protocol

`nextbus.proto` (proto3) is the single source of truth. The phone does all JSON
handling; the watch only ever sees these messages.

## Wire encoding
Standard protobuf wire format of one `Envelope` (a `oneof` over `Hello`,
`SelectionsSync`, `ArrivalsUpdate`, `RefreshRequest`). The watch codec
(`watch/source/Proto.mc`) implements only: varint, zigzag `sint32`,
length-delimited (strings, sub-messages, packed `repeated uint32`), and skipping
of unknown fields (so newer phones can add fields). Limits: varints decode to a
32-bit signed Number, so `uint32` values >= 2^31 (epoch seconds after 2038) wrap.

## Transport (deviation from "send bytes")
Target devices (fr165/fr255 families) are Connect IQ 5.2.0. `Communications.transmit`
accepts a `ByteArray` only from API 6.0.0, and the Android SDK has no `byte[]`
type. So the encoded bytes travel as a flat list of integers:

    [byteLength, w0, w1, ...]   w = 3 bytes, big-endian, zero padded (0..0xFFFFFF)

Implemented by `Transport` (Kotlin) and `Proto.pack/unpack` (Monkey C). When all
targets reach API 6.0.0 this can be replaced by sending the raw bytes.

Sizes (bytes, see `ProtocolTest`): 10 selections 747 (249 ints), arrivals for 10
selections 241 (81 ints). The per-message limit of Connect IQ is undocumented
and must be measured on a device; messages are small by design (max 10
selections, max 3 arrivals each).

## Versioning
Every top-level message carries `protocol_version` (`Transport.PROTOCOL_VERSION`,
`Config.PROTOCOL_VERSION`). Receivers show "update the phone app / watch app" on
mismatch (`Hello` is exchanged at start). Bump the version only for incompatible
changes; adding fields is compatible.

## Golden vectors
`vectors/vectors.json` is produced by the Kotlin encoder: for each message the
protobuf hex, the transport list, and the expected decode in the watch's compact
positional form (documented at the top of `Proto.mc`).

- Regenerate: `cd android && ./gradlew :protocol:generateVectors && python3 ../protocol/gen_mc_tests.py`
- Check for drift (CI): `protocol/check.sh` (fails if `.proto` changed without
  regenerating, if vectors differ from the encoder, or if the generated
  Monkey C tests are stale).
- Kotlin test: `./gradlew :protocol:test`. Monkey Unit: build with `monkeyc -t`,
  run with `monkeydo <prg> <device> -t` (tests in `watch/source/tests/`).

## Extending
1. Edit `nextbus.proto` (new fields: new numbers, never reuse).
2. Update `Proto.mc` decode (and the positional layout comment).
3. Add/adjust vectors in `android/protocol/src/test/.../Vectors.kt`.
4. Regenerate, run `protocol/check.sh`, run the Monkey Unit tests.
