import Toybox.Lang;
import Toybox.StringUtil;

// Hand-written decoder/encoder for the subset of protobuf used by
// protocol/nextbus.proto: varint, zigzag (sint32), length-delimited, and
// skipping of unknown fields. Decoded messages are positional Arrays so they
// can be stored in Application.Storage as-is:
//
//   Hello          [T_HELLO, version, role, appVersion]
//   SelectionsSync [T_SELECTIONS, version, [[id, line, stopCode, stopName, destination, latE6, lonE6, operatorId], ...]]
//   ArrivalsUpdate [T_ARRIVALS, version, generatedAt, [[selectionId, status, [epoch, ...]], ...]]
//   RefreshRequest [T_REFRESH, version, [selectionId, ...], reason]
//
// Limit: varints are decoded into a 32-bit signed Number, so uint32 values
// >= 2^31 (epoch seconds past the year 2038) wrap negative.
module Proto {
    // Envelope oneof field numbers double as message type tags.
    const T_HELLO = 1;
    const T_SELECTIONS = 2;
    const T_ARRIVALS = 3;
    const T_REFRESH = 4;

    const ROLE_PHONE = 1;
    const ROLE_WATCH = 2;

    const STATUS_OK = 1;
    const STATUS_NO_SERVICE = 2;
    const STATUS_ERROR = 3;
    const STATUS_STALE = 4;

    const REASON_APP_OPENED = 1;
    const REASON_TIMER = 2;
    const REASON_MANUAL = 3;

    class Reader {
        var buf as ByteArray;
        var pos as Number = 0;
        var bad as Boolean = false;

        function initialize(b as ByteArray) {
            buf = b;
        }

        function varint() as Number {
            var result = 0;
            var shift = 0;
            while (true) {
                if (pos >= buf.size() || shift > 28) {
                    bad = true;
                    return 0;
                }
                var b = buf[pos];
                pos++;
                result |= (b & 0x7F) << shift;
                if ((b & 0x80) == 0) {
                    return result;
                }
                shift += 7;
            }
            return 0;
        }

        // Reads a length prefix and returns the end offset of the payload.
        function lengthEnd() as Number {
            var len = varint();
            var e = pos + len;
            if (len < 0 || e > buf.size()) {
                bad = true;
                return pos;
            }
            return e;
        }

        function string(e as Number) as String {
            if (e <= pos) {
                return "";
            }
            var s = StringUtil.convertEncodedString(buf.slice(pos, e), {
                :fromRepresentation => StringUtil.REPRESENTATION_BYTE_ARRAY,
                :toRepresentation => StringUtil.REPRESENTATION_STRING_PLAIN_TEXT,
                :encoding => StringUtil.CHAR_ENCODING_UTF8
            }) as String;
            pos = e;
            return s;
        }

        function skip(wireType as Number) as Void {
            if (wireType == 0) {
                varint();
            } else if (wireType == 2) {
                pos = lengthEnd();
            } else if (wireType == 1) {
                pos += 8;
            } else if (wireType == 5) {
                pos += 4;
            } else {
                bad = true;
            }
            if (pos > buf.size()) {
                bad = true;
            }
        }
    }

    function zigzag(n as Number) as Number {
        return (n >> 1) ^ (-(n & 1));
    }

    // Appends the values of a repeated uint32 field (packed or not) to out.
    function readUints(r as Reader, wireType as Number, out as Array<Number>) as Void {
        if (wireType == 2) {
            var e = r.lengthEnd();
            while (r.pos < e && !r.bad) {
                out.add(r.varint());
            }
        } else if (wireType == 0) {
            out.add(r.varint());
        } else {
            r.bad = true;
        }
    }

    function decodeHello(r as Reader, e as Number) as Array {
        var out = [T_HELLO, 0, 0, ""] as Array;
        while (r.pos < e && !r.bad) {
            var tag = r.varint();
            var f = tag >> 3;
            var w = tag & 7;
            if (f == 1 && w == 0) {
                out[1] = r.varint();
            } else if (f == 2 && w == 0) {
                out[2] = r.varint();
            } else if (f == 3 && w == 2) {
                out[3] = r.string(r.lengthEnd());
            } else {
                r.skip(w);
            }
        }
        return out;
    }

    function decodeSelection(r as Reader, e as Number) as Array {
        var out = [0, "", "", "", "", 0, 0, 0] as Array;
        while (r.pos < e && !r.bad) {
            var tag = r.varint();
            var f = tag >> 3;
            var w = tag & 7;
            if (f == 1 && w == 0) {
                out[0] = r.varint();
            } else if (f >= 2 && f <= 5 && w == 2) {
                out[f - 1] = r.string(r.lengthEnd());
            } else if ((f == 6 || f == 7) && w == 0) {
                out[f - 1] = zigzag(r.varint());
            } else if (f == 8 && w == 0) {
                out[7] = r.varint();
            } else {
                r.skip(w);
            }
        }
        return out;
    }

    function decodeSelections(r as Reader, e as Number) as Array {
        var out = [T_SELECTIONS, 0, []] as Array;
        var list = out[2] as Array;
        while (r.pos < e && !r.bad) {
            var tag = r.varint();
            var f = tag >> 3;
            var w = tag & 7;
            if (f == 1 && w == 0) {
                out[1] = r.varint();
            } else if (f == 2 && w == 2) {
                var se = r.lengthEnd();
                list.add(decodeSelection(r, se));
                r.pos = se;
            } else {
                r.skip(w);
            }
        }
        return out;
    }

    function decodeSelectionArrivals(r as Reader, e as Number) as Array {
        var out = [0, 0, []] as Array;
        while (r.pos < e && !r.bad) {
            var tag = r.varint();
            var f = tag >> 3;
            var w = tag & 7;
            if (f == 1 && w == 0) {
                out[0] = r.varint();
            } else if (f == 2 && w == 0) {
                out[1] = r.varint();
            } else if (f == 3) {
                readUints(r, w, out[2] as Array<Number>);
            } else {
                r.skip(w);
            }
        }
        return out;
    }

    function decodeArrivals(r as Reader, e as Number) as Array {
        var out = [T_ARRIVALS, 0, 0, []] as Array;
        var list = out[3] as Array;
        while (r.pos < e && !r.bad) {
            var tag = r.varint();
            var f = tag >> 3;
            var w = tag & 7;
            if (f == 1 && w == 0) {
                out[1] = r.varint();
            } else if (f == 2 && w == 0) {
                out[2] = r.varint();
            } else if (f == 3 && w == 2) {
                var ie = r.lengthEnd();
                list.add(decodeSelectionArrivals(r, ie));
                r.pos = ie;
            } else {
                r.skip(w);
            }
        }
        return out;
    }

    function decodeRefresh(r as Reader, e as Number) as Array {
        var out = [T_REFRESH, 0, [], 0] as Array;
        while (r.pos < e && !r.bad) {
            var tag = r.varint();
            var f = tag >> 3;
            var w = tag & 7;
            if (f == 1 && w == 0) {
                out[1] = r.varint();
            } else if (f == 2) {
                readUints(r, w, out[2] as Array<Number>);
            } else if (f == 3 && w == 0) {
                out[3] = r.varint();
            } else {
                r.skip(w);
            }
        }
        return out;
    }

    // Decodes an Envelope. Returns null if the bytes are malformed or empty.
    function decodeEnvelope(bytes as ByteArray) as Array? {
        var r = new Reader(bytes);
        var result = null as Array?;
        while (r.pos < bytes.size() && !r.bad) {
            var tag = r.varint();
            var f = tag >> 3;
            var w = tag & 7;
            if (w == 2 && f >= 1 && f <= 4) {
                var e = r.lengthEnd();
                if (r.bad) {
                    break;
                }
                if (f == T_HELLO) {
                    result = decodeHello(r, e);
                } else if (f == T_SELECTIONS) {
                    result = decodeSelections(r, e);
                } else if (f == T_ARRIVALS) {
                    result = decodeArrivals(r, e);
                } else {
                    result = decodeRefresh(r, e);
                }
                r.pos = e;
            } else {
                r.skip(w);
            }
        }
        return r.bad ? null : result;
    }

    // ---- Transport: flat Number list <-> bytes (see protocol/PROTOCOL.md) ----

    function unpack(ints as Array) as ByteArray? {
        if (ints.size() < 1 || !(ints[0] instanceof Number)) {
            return null;
        }
        var len = ints[0] as Number;
        if (len < 0 || ints.size() < 1 + (len + 2) / 3) {
            return null;
        }
        var out = new [len]b;
        for (var i = 0; i < len; i++) {
            var w = ints[1 + i / 3];
            if (!(w instanceof Number)) {
                return null;
            }
            out[i] = ((w as Number) >> (16 - 8 * (i % 3))) & 0xFF;
        }
        return out;
    }

    function pack(bytes as ByteArray) as Array<Number> {
        var n = bytes.size();
        var out = [n] as Array<Number>;
        for (var i = 0; i < n; i += 3) {
            var w = 0;
            for (var k = 0; k < 3; k++) {
                w = (w << 8) | (i + k < n ? bytes[i + k] : 0);
            }
            out.add(w);
        }
        return out;
    }

    function decodeMessage(ints as Array) as Array? {
        var bytes = unpack(ints);
        return bytes == null ? null : decodeEnvelope(bytes);
    }

    // ---- Encoder (watch -> phone: Hello and RefreshRequest only) ----

    function putVarint(out as ByteArray, n as Number) as Void {
        while (n >= 0x80 || n < 0) {
            out.add((n & 0x7F) | 0x80);
            n = (n >> 7) & 0x1FFFFFF;
        }
        out.add(n);
    }

    // Wraps a message body as Envelope field `field` (length-delimited).
    function wrap(field as Number, body as ByteArray) as ByteArray {
        var out = new [0]b;
        putVarint(out, (field << 3) | 2);
        putVarint(out, body.size());
        out.addAll(body);
        return out;
    }

    function encodeRefresh(ids as Array<Number>, reason as Number) as ByteArray {
        var body = new [0]b;
        putVarint(body, 0x08);
        putVarint(body, Config.PROTOCOL_VERSION);
        if (ids.size() > 0) {
            var packed = new [0]b;
            for (var i = 0; i < ids.size(); i++) {
                putVarint(packed, ids[i]);
            }
            putVarint(body, (2 << 3) | 2);
            putVarint(body, packed.size());
            body.addAll(packed);
        }
        if (reason != 0) {
            putVarint(body, 0x18);
            putVarint(body, reason);
        }
        return wrap(T_REFRESH, body);
    }

    function encodeHello(appVersion as String) as ByteArray {
        var body = new [0]b;
        putVarint(body, 0x08);
        putVarint(body, Config.PROTOCOL_VERSION);
        putVarint(body, 0x10);
        putVarint(body, ROLE_WATCH);
        var s = appVersion.toUtf8Array();
        putVarint(body, (3 << 3) | 2);
        putVarint(body, s.size());
        body.addAll(s as Array<Number>);
        return wrap(T_HELLO, body);
    }
}
