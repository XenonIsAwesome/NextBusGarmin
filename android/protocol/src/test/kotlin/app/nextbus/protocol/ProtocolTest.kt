package app.nextbus.protocol

import java.io.File
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class ProtocolTest {
    private val protocolDir = File("../../protocol")

    @Test
    fun goldenVectorsMatchEncoder() {
        val golden = File(protocolDir, "vectors/vectors.json").readText()
        val fresh = renderVectors(File(protocolDir, "nextbus.proto").readText())
        assertEquals(
            fresh, golden,
            "protocol/vectors/vectors.json is out of date; run: ./gradlew :protocol:generateVectors",
        )
    }

    @Test
    fun transportRoundTripsAndEveryVectorDecodes() {
        for (v in VECTORS) {
            val bytes = v.envelope.toByteArray()
            assertContentEquals(bytes, Transport.unpack(Transport.pack(bytes)), v.name)
            assertEquals(v.envelope, Transport.decode(Transport.encode(v.envelope)), v.name)
        }
    }

    @Test
    fun packLayout() {
        assertEquals(listOf(0), Transport.pack(ByteArray(0)))
        assertEquals(listOf(4, 0x010203, 0x040000), Transport.pack(byteArrayOf(1, 2, 3, 4)))
        assertEquals(listOf(5, 0xFFFFFF, 0xFF8000), Transport.pack(byteArrayOf(-1, -1, -1, -1, -128)))
        assertContentEquals(byteArrayOf(-1, -1, -1, -1, -128), Transport.unpack(listOf(5, 0xFFFFFF, 0xFF8000)))
    }

    @Test
    fun sizesFitComfortably() {
        val sizes = VECTORS.associate { it.name to it.envelope.toByteArray().size }
        println("encoded sizes (bytes): $sizes")
        assert(sizes.getValue("selections_10") < 1200)
        assert(sizes.getValue("arrivals_10") < 400)
    }
}
