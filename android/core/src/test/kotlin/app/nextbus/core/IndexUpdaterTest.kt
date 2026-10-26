package app.nextbus.core

import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.zip.GZIPOutputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class IndexUpdaterTest {
    private val payload = "SQLite format 3\u0000 pretend this is a database".toByteArray()
    private val gz = ByteArrayOutputStream().also { o -> GZIPOutputStream(o).use { it.write(payload) } }.toByteArray()
    private val sha = MessageDigest.getInstance("SHA-256").digest(gz).joinToString("") { "%02x".format(it) }

    private fun manifestJson(builtAt: String = "2026-10-26T20:49:19Z", schema: Int = 1, sha256: String = sha, size: Long = gz.size.toLong()) =
        """{"builtAt":"$builtAt","schemaVersion":$schema,"sha256":"$sha256","size":$size,"url":"https://example.test/index.db.gz","extra":1}"""

    private fun updater(manifest: String, bytes: ByteArray = gz, url: String = "https://example.test/manifest.json") =
        IndexUpdater(url, { manifest }, { bytes }, supportedSchema = 1)

    @Test
    fun newerRemoteIsAvailableOlderOrEqualIsNot() = runTest {
        assertIs<IndexCheck.Available>(updater(manifestJson()).check("2026-10-01T00:00:00Z"))
        assertIs<IndexCheck.Available>(updater(manifestJson()).check(null))
        assertEquals(IndexCheck.UpToDate, updater(manifestJson()).check("2026-10-26T20:49:19Z"))
        assertEquals(IndexCheck.UpToDate, updater(manifestJson()).check("2026-11-01T00:00:00Z"))
    }

    @Test
    fun unknownSchemaNeedsANewerApp() = runTest {
        assertEquals(IndexCheck.NeedsNewerApp(2), updater(manifestJson(schema = 2)).check(null))
    }

    @Test
    fun brokenManifestOrNoUrlFailsCleanly() = runTest {
        assertIs<IndexCheck.Failed>(updater("not json").check(null))
        assertIs<IndexCheck.Failed>(updater(manifestJson(), url = "").check(null))
        assertIs<IndexCheck.Failed>(IndexUpdater("https://x", { throw java.io.IOException("offline") }, { gz }, 1).check(null))
    }

    @Test
    fun downloadVerifiesChecksumAndSizeThenDecompresses() = runTest {
        val m = (updater(manifestJson()).check(null) as IndexCheck.Available).manifest
        assertContentEquals(payload, updater(manifestJson()).download(m).getOrThrow())

        val corrupted = gz.copyOf().also { it[it.size / 2] = (it[it.size / 2] + 1).toByte() }
        assertTrue(updater(manifestJson(), corrupted).download(m).isFailure)
        assertTrue(updater(manifestJson(), gz + 0).download(m).isFailure) // wrong size
        val wrongSha = m.copy(sha256 = "0".repeat(64))
        assertTrue(updater(manifestJson()).download(wrongSha).isFailure)
    }
}
