package app.nextbus.core

import java.io.IOException
import java.net.URLEncoder

/**
 * curlbus.app (unofficial wrapper over the Ministry SIRI-SM feed).
 * `GET <base>/<stop>+<stop>?filter=<line>,<line>` with `Accept: application/json`.
 */
class CurlbusProvider(
    baseUrl: String = NextBusConfig.CURLBUS_BASE_URL,
    private val http: HttpFetcher = JdkHttpFetcher(),
) : ArrivalProvider {
    private val base = baseUrl.trimEnd('/')

    override suspend fun fetch(stopCodes: Collection<String>, lines: Set<String>): FetchResult {
        val stops = stopCodes.distinct().sorted()
        if (stops.isEmpty()) return FetchResult.Success(emptyList())
        val all = ArrayList<Visit>()
        for (chunk in stops.chunked(MAX_STOPS_PER_REQUEST)) {
            when (val r = fetchChunk(chunk, lines)) {
                is FetchResult.Success -> all += r.visits
                is FetchResult.Failure -> return r
            }
        }
        return FetchResult.Success(all)
    }

    internal fun urlFor(stops: List<String>, lines: Set<String>): String {
        val path = stops.joinToString("+") { URLEncoder.encode(it, "UTF-8") }
        val filter = lines.sorted().joinToString(",") { URLEncoder.encode(it, "UTF-8") }
        return if (filter.isEmpty()) "$base/$path" else "$base/$path?filter=$filter"
    }

    private suspend fun fetchChunk(stops: List<String>, lines: Set<String>): FetchResult {
        val resp = try {
            http.get(urlFor(stops, lines), mapOf("Accept" to "application/json"))
        } catch (e: IOException) {
            return FetchResult.Failure(FailureKind.NETWORK, e.message ?: e.javaClass.simpleName)
        }
        return when {
            resp.code == 429 ->
                FetchResult.Failure(FailureKind.RATE_LIMITED, "HTTP 429", resp.retryAfterSeconds)
            resp.code >= 500 ->
                FetchResult.Failure(FailureKind.SERVER, "HTTP ${resp.code}", resp.retryAfterSeconds)
            resp.code != 200 ->
                FetchResult.Failure(FailureKind.BAD_RESPONSE, "HTTP ${resp.code}")
            else -> CurlbusParser.parse(resp.body)
        }
    }

    companion object {
        /** curlbus answers HTTP 400 above this many stops in one request. */
        const val MAX_STOPS_PER_REQUEST = 30
    }
}
