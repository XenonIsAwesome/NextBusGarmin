package app.nextbus.core

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class HttpResponse(val code: Int, val body: String, val retryAfterSeconds: Long? = null)

fun interface HttpFetcher {
    /** Throws [IOException] on connectivity problems. */
    suspend fun get(url: String, headers: Map<String, String>): HttpResponse
}

class JdkHttpFetcher(private val timeoutMs: Int = 10_000) : HttpFetcher {
    override suspend fun get(url: String, headers: Map<String, String>): HttpResponse =
        withContext(Dispatchers.IO) {
            val conn = URL(url).openConnection() as HttpURLConnection
            try {
                conn.connectTimeout = timeoutMs
                conn.readTimeout = timeoutMs
                headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
                val code = conn.responseCode
                val stream = if (code >= 400) conn.errorStream else conn.inputStream
                val body = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
                HttpResponse(code, body, conn.getHeaderField("Retry-After")?.trim()?.toLongOrNull())
            } finally {
                conn.disconnect()
            }
        }
}
