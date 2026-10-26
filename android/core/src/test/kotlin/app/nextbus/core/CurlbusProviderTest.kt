package app.nextbus.core

import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlinx.coroutines.test.runTest

class CurlbusProviderTest {
    private class FakeHttp(val respond: (String) -> HttpResponse) : HttpFetcher {
        val urls = mutableListOf<String>()
        val headers = mutableListOf<Map<String, String>>()
        override suspend fun get(url: String, headers: Map<String, String>): HttpResponse {
            urls += url
            this.headers += headers
            return respond(url)
        }
    }

    private fun ok(name: String) = HttpResponse(200, Fixtures.text(name))

    @Test
    fun batchesStopsAndFiltersLines() = runTest {
        val http = FakeHttp { ok("multi_36601_36602.json") }
        val r = CurlbusProvider("https://example.test/", http).fetch(listOf("36602", "36601", "36601"), setOf("531", "29"))
        assertIs<FetchResult.Success>(r)
        assertEquals(listOf("https://example.test/36601+36602?filter=29,531"), http.urls)
        assertEquals("application/json", http.headers.single()["Accept"])
    }

    @Test
    fun noFilterWhenNoLines() = runTest {
        val http = FakeHttp { ok("stop_36601.json") }
        CurlbusProvider("https://example.test", http).fetch(listOf("36601"), emptySet())
        assertEquals(listOf("https://example.test/36601"), http.urls)
    }

    @Test
    fun splitsAboveThirtyStops() = runTest {
        val http = FakeHttp { HttpResponse(200, """{"errors":null,"visits":{}}""") }
        CurlbusProvider("https://x", http).fetch((1..31).map { it.toString() }, emptySet())
        assertEquals(2, http.urls.size)
        assertEquals(30, http.urls[0].substringAfter("https://x/").split("+").size)
    }

    @Test
    fun mapsHttpFailures() = runTest {
        suspend fun kind(code: Int, retry: Long? = null) = (CurlbusProvider("https://x", FakeHttp { HttpResponse(code, "", retry) })
            .fetch(listOf("1"), emptySet()) as FetchResult.Failure)
        assertEquals(FailureKind.RATE_LIMITED, kind(429, 90).kind)
        assertEquals(90L, kind(429, 90).retryAfterSeconds)
        assertEquals(FailureKind.SERVER, kind(503).kind)
        assertEquals(FailureKind.BAD_RESPONSE, kind(400).kind)
        assertEquals(FailureKind.BAD_RESPONSE, kind(404).kind)
        val net = CurlbusProvider("https://x", FakeHttp { throw IOException("offline") }).fetch(listOf("1"), emptySet())
        assertEquals(FailureKind.NETWORK, (net as FetchResult.Failure).kind)
    }
}
