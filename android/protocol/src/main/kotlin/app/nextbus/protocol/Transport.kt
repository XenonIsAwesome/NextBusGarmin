package app.nextbus.protocol

/**
 * Connect IQ message transport. The watch devices we target (API 5.2.0) cannot
 * receive a ByteArray (supported from API 6.0.0) and the Android SDK has no
 * byte[] type, so the protobuf bytes of an [Envelope] travel as a flat list of
 * Integers: element 0 is the byte length, then 3 bytes per element, big-endian,
 * zero padded. 24 bits keeps every element a positive Monkey C Number.
 */
object Transport {
    const val PROTOCOL_VERSION = 1

    fun pack(bytes: ByteArray): List<Int> {
        val out = ArrayList<Int>(1 + (bytes.size + 2) / 3)
        out.add(bytes.size)
        var i = 0
        while (i < bytes.size) {
            var w = 0
            for (k in 0 until 3) {
                w = (w shl 8) or (if (i + k < bytes.size) bytes[i + k].toInt() and 0xFF else 0)
            }
            out.add(w)
            i += 3
        }
        return out
    }

    fun unpack(ints: List<Int>): ByteArray {
        require(ints.isNotEmpty()) { "empty transport message" }
        val len = ints[0]
        require(len >= 0 && ints.size - 1 >= (len + 2) / 3) { "transport message too short" }
        val out = ByteArray(len)
        for (i in 0 until len) {
            val w = ints[1 + i / 3]
            out[i] = (w shr (16 - 8 * (i % 3))).toByte()
        }
        return out
    }

    fun encode(envelope: Envelope): List<Int> = pack(envelope.toByteArray())

    fun decode(ints: List<Int>): Envelope = Envelope.parseFrom(unpack(ints))
}
