package app.nextbus.data

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.nextbus.BuildConfig
import app.nextbus.NextBusApp
import app.nextbus.core.IndexCheck
import app.nextbus.core.IndexUpdater
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

sealed interface IndexUpdateResult {
    data object NotConfigured : IndexUpdateResult
    data object UpToDate : IndexUpdateResult
    data class Updated(val builtAt: String) : IndexUpdateResult
    data class Failed(val reason: String) : IndexUpdateResult
}

/** Checks the published manifest and installs a newer index (checksum verified). */
object IndexMaintenance {
    private const val WORK_NAME = "index-update"

    private suspend fun get(url: String): ByteArray = withContext(Dispatchers.IO) {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 15_000
            c.readTimeout = 60_000
            c.instanceFollowRedirects = true // GitHub release assets redirect to a CDN
            check(c.responseCode == 200) { "HTTP ${c.responseCode} for $url" }
            c.inputStream.use { it.readBytes() }
        } finally {
            c.disconnect()
        }
    }

    suspend fun checkAndInstall(index: IndexRepository): IndexUpdateResult {
        val url = BuildConfig.INDEX_MANIFEST_URL
        if (url.isBlank()) return IndexUpdateResult.NotConfigured
        val updater = IndexUpdater(url, { String(get(it)) }, { get(it) }, IndexRepository.SUPPORTED_SCHEMA)
        return when (val check = updater.check(runCatching { index.info().builtAt }.getOrNull())) {
            IndexCheck.UpToDate -> IndexUpdateResult.UpToDate
            is IndexCheck.NeedsNewerApp -> IndexUpdateResult.Failed("the published index needs a newer app (schema ${check.schemaVersion})")
            is IndexCheck.Failed -> IndexUpdateResult.Failed(check.reason)
            is IndexCheck.Available -> {
                val bytes = updater.download(check.manifest).getOrElse { return IndexUpdateResult.Failed(it.message ?: "download failed") }
                index.install(bytes, IndexRepository.SUPPORTED_SCHEMA)?.let { return IndexUpdateResult.Failed(it) }
                IndexUpdateResult.Updated(check.manifest.builtAt)
            }
        }
    }

    /** Weekly, on an unmetered network, not on a low battery. */
    fun schedule(context: Context) {
        if (BuildConfig.INDEX_MANIFEST_URL.isBlank()) return
        val request = PeriodicWorkRequestBuilder<IndexUpdateWorker>(7, TimeUnit.DAYS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.UNMETERED).setRequiresBatteryNotLow(true).build())
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
    }
}

class IndexUpdateWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result =
        when (IndexMaintenance.checkAndInstall((applicationContext as NextBusApp).graph.index)) {
            is IndexUpdateResult.Failed -> Result.retry()
            else -> Result.success()
        }
}
