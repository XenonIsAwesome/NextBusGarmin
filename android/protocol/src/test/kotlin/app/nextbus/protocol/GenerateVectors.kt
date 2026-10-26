package app.nextbus.protocol

import java.io.File
import java.security.MessageDigest

fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }

/** Renders protocol/vectors/vectors.json. Keep output byte-stable so drift checks can diff it. */
fun renderVectors(protoText: String): String {
    val sha = hex(MessageDigest.getInstance("SHA-256").digest(protoText.toByteArray()))
    val sb = StringBuilder("{\n  \"proto_sha256\": \"$sha\",\n  \"protocol_version\": ${Transport.PROTOCOL_VERSION},\n  \"vectors\": [\n")
    VECTORS.forEachIndexed { i, v ->
        val bytes = v.envelope.toByteArray()
        sb.append("    {\n")
        sb.append("      \"name\": \"${v.name}\",\n")
        sb.append("      \"hex\": \"${hex(bytes)}\",\n")
        sb.append("      \"transport\": ${Transport.pack(bytes)},\n".replace(", ", ","))
        sb.append("      \"expected\": ${v.expected}\n")
        sb.append("    }").append(if (i < VECTORS.size - 1) ",\n" else "\n")
    }
    return sb.append("  ]\n}\n").toString()
}

fun main(args: Array<String>) {
    val dir = File(args[0])
    val proto = File(dir, "nextbus.proto").readText()
    File(dir, "vectors").mkdirs()
    File(dir, "vectors/vectors.json").writeText(renderVectors(proto))
}
