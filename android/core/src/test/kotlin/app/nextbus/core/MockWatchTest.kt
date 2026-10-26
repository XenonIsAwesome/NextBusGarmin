package app.nextbus.core

import app.nextbus.protocol.Envelope
import app.nextbus.protocol.Status
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/**
 * The whole phone side (real CurlbusProvider + parser, coordinator, converter, relay engine,
 * protocol encoding) against a [MockWatch], with curlbus replaced by captured responses and a
 * simulated clock.
 */
class MockWatchTest {
    private class Curlbus(val respond: (String) -> HttpResponse) : HttpFetcher {
        val urls = mutableListOf<String>()
        override suspend fun get(url: String, headers: Map<String, String>): HttpResponse {
            urls += url
            return respond(url)
        }
    }

    private var clock = Fixtures.capturedAt("stop_36601.json")
    private val sel = SavedSelection(
        id = 1, line = "29", operatorId = 15, stopCode = "36601", stopNameEn = "Beit Loewenstein/Ahuza",
        stopNameHe = "בית לוינשטיין/אחוזה", destCode = "27104", destNameEn = "HaShunit/Yorde Yam",
        destNameHe = "השונית/יורדי ים", latE6 = 32184760, lonE6 = 34855867,
    )
    private var saved = listOf(sel)
    private val events = mutableListOf<RelayEvent>()
    private var curlbus = Curlbus { HttpResponse(200, Fixtures.text("stop_36601.json")) }

    private lateinit var engine: RelayEngine
    private lateinit var watch: MockWatch

    private fun setUp(maxInts: Int = 0) {
        val coordinator = ArrivalCoordinator(CurlbusProvider("https://curlbus.test", curlbus), { clock })
        // The relay engine and the watch point at each other, like the two real apps.
        lateinit var w: MockWatch
        val link = object : WatchLink {
            override suspend fun send(message: List<Int>) = w.send(message)
        }
        engine = RelayEngine(coordinator, { saved }, link, { clock }, "test", { events += it })
        w = MockWatch({ clock }, { engine.onWatchMessage(it) }, maxInts)
        watch = w
    }

    /** Advances time like the real world: the watch's timer fires while its app is open. */
    private suspend fun waitSeconds(n: Int) = repeat(n) { clock++; watch.tick() }

    @Test
    fun openingTheAppFillsTheScreenFromCurlbus() = runTest {
        setUp()
        watch.openApp()
        assertEquals(
            listOf(Envelope.BodyCase.HELLO, Envelope.BodyCase.SELECTIONS_SYNC, Envelope.BodyCase.ARRIVALS_UPDATE),
            watch.received,
        )
        val row = watch.view().single()
        assertEquals("29", row.line)
        assertEquals("Beit Loewenstein/Ahuza", row.stopName)
        assertEquals(15, row.operatorId)
        assertEquals(Status.OK, row.status)
        assertTrue(row.times.isNotEmpty() && row.times.size <= 3, "times: ${row.times}")
        assertEquals(listOf("https://curlbus.test/36601?filter=29"), curlbus.urls)
    }

    @Test
    fun anOpenAppPollsButCurlbusIsHitOncePerWindow() = runTest {
        setUp()
        watch.openApp()
        repeat(5) { watch.refresh() }                // user hammers refresh / flips pages
        waitSeconds(39); assertEquals(1, curlbus.urls.size)
        waitSeconds(3);  assertEquals(2, curlbus.urls.size)   // first poll after the 40 s window
        waitSeconds(300)
        // 342 s after opening: never more often than every 40 s.
        assertTrue(curlbus.urls.size <= 342 / 40 + 1, "curlbus requests: ${curlbus.urls.size}")
        assertEquals(Status.OK, watch.view().single().status)
    }

    @Test
    fun aClosedAppCausesNoTraffic() = runTest {
        setUp()
        watch.openApp(); watch.closeApp()
        waitSeconds(600)
        assertEquals(1, curlbus.urls.size)
    }

    @Test
    fun minutesCountDownBetweenFetches() = runTest {
        setUp()
        watch.openApp()
        val before = watch.view().single().times
        val far = before.mapNotNull { it.toIntOrNull() }.maxOrNull()
        assertNotNull(far, "fixture should have a bus at least a minute out: $before")
        assertTrue(far >= 3, "need a bus 3+ minutes out to count down: $before")
        clock += 120   // no fetch, the watch just recomputes against its clock
        val after = watch.view().single().times.mapNotNull { it.toIntOrNull() }
        assertTrue((far - 2) in after, "expected ${far - 2} in $after")
        assertEquals(1, curlbus.urls.size)
    }

    @Test
    fun editingSelectionsInThePhoneUpdatesTheOpenWatch() = runTest {
        setUp()
        watch.openApp()
        saved = emptyList()
        engine.onSelectionsChanged()
        assertTrue(watch.view().isEmpty())
        saved = listOf(sel, sel.copy(id = 2, line = "531"))
        clock += 41
        engine.onSelectionsChanged()
        assertEquals(listOf("29", "531"), watch.view().map { it.line })
    }

    @Test
    fun curlbusDownShowsErrorOnTheWatchThenRecovers() = runTest {
        curlbus = Curlbus { HttpResponse(503, "") }
        setUp()
        watch.openApp()
        assertEquals(Status.ERROR, watch.view().single().status)
        curlbus = Curlbus { HttpResponse(200, Fixtures.text("stop_36601.json")) }
        setUp()
        saved = listOf(sel)
        watch.openApp()
        assertEquals(Status.OK, watch.view().single().status)
    }

    @Test
    fun staleDataIsKeptWhenCurlbusFailsLater() = runTest {
        var up = true
        curlbus = Curlbus { if (up) HttpResponse(200, Fixtures.text("stop_36601.json")) else HttpResponse(500, "") }
        setUp()
        watch.openApp()
        up = false
        clock += 60
        watch.refresh()
        val row = watch.view().single()
        assertEquals(Status.STALE, row.status)
        assertTrue(row.times.isNotEmpty())
    }

    @Test
    fun unknownStopDoesNotBreakOtherSelections() = runTest {
        curlbus = Curlbus {
            if ("99999999" in it) HttpResponse(200, Fixtures.text("invalid_stop.json"))
            else HttpResponse(200, Fixtures.text("stop_36601.json"))
        }
        setUp()
        saved = listOf(sel, sel.copy(id = 2, stopCode = "99999999"))
        watch.openApp()
        val rows = watch.view()
        assertEquals(2, rows.size)
        assertEquals(Status.OK, rows[0].status)
        assertNotNull(rows[1].status)
        assertTrue(rows[1].status != Status.OK)
    }

    @Test
    fun watchWithTinyMessageLimitStillGetsSelections() = runTest {
        saved = (1..10).map { sel.copy(id = it, line = "$it", stopNameEn = "A really quite long stop name number $it") }
        fun size(maxName: Int) = app.nextbus.protocol.Transport.encode(
            Envelope.newBuilder().setSelectionsSync(SelectionsSyncBuilder.build(saved, maxName)).build(),
        ).size
        // Allow the short form but not the default one.
        setUp(maxInts = (size(SelectionsSyncBuilder.SHORT_NAME) + size(SelectionsSyncBuilder.MAX_NAME)) / 2)
        watch.openApp()
        assertEquals(10, watch.selections.size)
        assertTrue(watch.selections.all { it.stopName.length <= SelectionsSyncBuilder.SHORT_NAME })
    }

    @Test
    fun reconnectResendsSelections() = runTest {
        setUp()
        watch.openApp()
        watch.connected = false
        engine.pushSelections()                      // fails, watch unreachable
        watch.connected = true
        engine.sendHello(); engine.pushSelections()  // what the service does when the watch reconnects
        assertEquals(1, watch.selections.size)
        assertTrue(events.any { it is RelayEvent.Problem })
    }

    @Test
    fun oldWatchAppGetsOnlyAHello() = runTest {
        setUp()
        watch.version = 99
        watch.openApp()
        assertEquals(listOf(Envelope.BodyCase.HELLO, Envelope.BodyCase.HELLO), watch.received)
        assertEquals(0, curlbus.urls.size)
        assertTrue(watch.selections.isEmpty())
        assertTrue(events.any { it is RelayEvent.VersionMismatch })
    }

    @Test
    fun emptyServiceWindowShowsNoServiceNotAnError() = runTest {
        curlbus = Curlbus { HttpResponse(200, Fixtures.text("empty_visits.json")) }
        setUp()
        watch.openApp()
        val row = watch.view().single()
        assertEquals(Status.NO_SERVICE, row.status)
        assertTrue(row.times.isEmpty())
    }
}
