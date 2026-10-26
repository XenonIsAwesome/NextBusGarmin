package app.nextbus.core

import java.io.ByteArrayInputStream
import java.security.MessageDigest
import java.time.Instant
import java.util.zip.GZIPInputStream
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * What the index publisher (.github/workflows/index.yml) uploads next to index.db.gz:
 * `manifest.json`. [sha256] is of the gzip file; [builtAt] is ISO-8601 UTC.
 */
@Serializable
data class IndexManifest(
    val builtAt: String,
    val schemaVersion: Int,
    val sha256: String,
    val size: Long,
    val url: String,
)

sealed interface IndexCheck {
    data object UpToDate : IndexCheck
    data class Available(val manifest: IndexManifest) : IndexCheck
    /** The published index needs a newer app (schema we do not understand). */
    data class NeedsNewerApp(val schemaVersion: Int) : IndexCheck
    data class Failed(val reason: String) : IndexCheck
}

/**
 * Checks for and downloads a newer prebuilt index. Transport and installing are injected so
 * this is testable; the app opens the result as SQLite and checks `meta.schema_version` itself
 * before swapping it in.
 */
class IndexUpdater(
    private val manifestUrl: String,
    private val fetchText: suspend (String) -> String,
    private val fetchBytes: suspend (String) -> ByteArray,
    private val supportedSchema: Int,
) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun check(localBuiltAt: String?): IndexCheck {
        if (manifestUrl.isBlank()) return IndexCheck.Failed("index updates are not configured")
        val manifest = try {
            json.decodeFromString(IndexManifest.serializer(), fetchText(manifestUrl))
        } catch (e: Exception) {
            return IndexCheck.Failed("could not read the index manifest: ${e.message}")
        }
        if (manifest.schemaVersion != supportedSchema) return IndexCheck.NeedsNewerApp(manifest.schemaVersion)
        return if (isNewer(manifest.builtAt, localBuiltAt)) IndexCheck.Available(manifest) else IndexCheck.UpToDate
    }

    /** Downloads, verifies the checksum, and returns the decompressed SQLite file. */
    suspend fun download(manifest: IndexManifest): Result<ByteArray> = runCatching {
        val gz = fetchBytes(manifest.url)
        check(gz.size.toLong() == manifest.size) { "downloaded ${gz.size} bytes, expected ${manifest.size}" }
        val sha = MessageDigest.getInstance("SHA-256").digest(gz).joinToString("") { "%02x".format(it) }
        check(sha.equals(manifest.sha256, ignoreCase = true)) { "checksum mismatch" }
        GZIPInputStream(ByteArrayInputStream(gz)).use { it.readBytes() }
    }

    companion object {
        fun isNewer(remoteBuiltAt: String, localBuiltAt: String?): Boolean {
            val remote = runCatching { Instant.parse(remoteBuiltAt) }.getOrNull() ?: return false
            val local = localBuiltAt?.let { runCatching { Instant.parse(it) }.getOrNull() } ?: return true
            return remote.isAfter(local)
        }
    }
}
