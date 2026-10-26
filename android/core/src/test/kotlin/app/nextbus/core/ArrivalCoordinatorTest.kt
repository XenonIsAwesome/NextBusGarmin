package app.nextbus.core

import app.nextbus.protocol.Status
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest

class ArrivalCoordinatorTest {
    private class FakeProvider(var next: () -> FetchResult) : ArrivalProvider {
        val calls = mutableListOf<Pair<Set<String>, Set<String>>>()
        override suspend fun fetch(stopCodes: Collection<String>, lines: Set<String>): FetchResult {
            calls += stopCodes.toSet() to lines
            return next()
        }
    }

    private var clock = Fixtures.capturedAt("stop_36601.json")
    private val good = (CurlbusParser.parse(Fixtures.text("stop_36601.json")) as FetchResult.Success)
    private val sel = listOf(SelectionSpec(1, "29", "36601"))

    private fun coordinator(p: FakeProvider, refresh: Long = 40) =
        ArrivalCoordinator(p, { clock }, refreshSeconds = refresh, maxBackoffSeconds = 300)

    @Test
    fun requestsInsideTheIntervalAreServedFromCache() = runTest {
        val p = FakeProvider { good }
        val c = coordinator(p)
        assertEquals(true, c.update(sel).fetched)
        repeat(20) { clock += 1; assertEquals(false, c.update(sel).fetched) } // rapid page switching / timers
        assertEquals(1, p.calls.size)
        clock += 40
        assertEquals(true, c.update(sel).fetched)
        assertEquals(2, p.calls.size)
    }

    @Test
    fun cachedReplyDropsPassedArrivalsAndStaysOk() = runTest {
        val c = coordinator(FakeProvider { good }, refresh = 3600)
        val first = c.update(sel).update.getItems(0).arrivalTimesList
        clock = first[0].toLong() + 1
        val r = c.update(sel)
        assertEquals(false, r.fetched)
        assertEquals(Status.OK, r.update.getItems(0).status)
        // the 4th bus (not in the first reply) moves up as the passed one drops off
        assertEquals(first.drop(1), r.update.getItems(0).arrivalTimesList.take(2))
        assertEquals(3, r.update.getItems(0).arrivalTimesCount)
    }

    @Test
    fun concurrentTriggersShareOneFetch() = runTest {
        val p = FakeProvider { good }
        val c = coordinator(p)
        (1..8).map { async { c.update(sel) } }.awaitAll()
        assertEquals(1, p.calls.size)
    }

    @Test
    fun oneBatchedRequestForAllSelections() = runTest {
        val p = FakeProvider { good }
        val sels = listOf(SelectionSpec(1, "29", "36601"), SelectionSpec(2, "13", "36601"), SelectionSpec(3, "531", "36602"))
        coordinator(p).update(sels)
        assertEquals(listOf(setOf("36601", "36602") to setOf("29", "13", "531")), p.calls)
    }

    @Test
    fun failureBacksOffExponentiallyAndServesStaleData() = runTest {
        var result: FetchResult = good
        val p = FakeProvider { result }
        val c = coordinator(p)
        c.update(sel)
        val goodAt = clock

        result = FetchResult.Failure(FailureKind.SERVER, "HTTP 503")
        clock += 40
        var r = c.update(sel)
        assertEquals(Status.STALE, r.update.getItems(0).status)
        assertEquals(3, r.update.getItems(0).arrivalTimesCount)
        assertEquals(goodAt.toInt(), r.update.generatedAt)
        assertNotNull(r.lastError)
        assertEquals(goodAt, r.lastGoodAt)

        // 1st failure: wait 80s. Nothing is sent before that.
        clock += 79; assertEquals(false, c.update(sel).fetched)
        clock += 1; assertEquals(true, c.update(sel).fetched)
        // 2nd failure: 160s.
        clock += 159; assertEquals(false, c.update(sel).fetched)
        clock += 1; assertEquals(true, c.update(sel).fetched)
        // 3rd: capped at 300s (not 320).
        clock += 299; assertEquals(false, c.update(sel).fetched)
        clock += 1; assertEquals(true, c.update(sel).fetched)
        assertEquals(5, p.calls.size)

        // Recovery resets everything.
        result = good
        clock += 300
        r = c.update(sel)
        assertNull(r.lastError)
        assertEquals(Status.OK, r.update.getItems(0).status)
        clock += 40; assertEquals(true, c.update(sel).fetched)
    }

    @Test
    fun retryAfterIsHonouredWhenLongerThanBackoff() = runTest {
        val p = FakeProvider { FetchResult.Failure(FailureKind.RATE_LIMITED, "HTTP 429", retryAfterSeconds = 200) }
        val c = coordinator(p)
        c.update(sel)
        clock += 199; assertEquals(false, c.update(sel).fetched)
        clock += 1; assertEquals(true, c.update(sel).fetched)
    }

    @Test
    fun noDataEverAndFailingIsError() = runTest {
        val c = coordinator(FakeProvider { FetchResult.Failure(FailureKind.NETWORK, "offline") })
        assertEquals(Status.ERROR, c.update(sel).update.getItems(0).status)
    }

    @Test
    fun invalidStopIsDroppedAndRetriedOnceWithoutHammering() = runTest {
        val p = FakeProvider {
            FetchResult.Failure(FailureKind.INVALID_STOP, "Invalid stop code 99", invalidStops = setOf("99"))
        }
        val sels = listOf(SelectionSpec(1, "29", "36601"), SelectionSpec(2, "5", "99"))
        var first = true
        p.next = {
            if (first) { first = false; FetchResult.Failure(FailureKind.INVALID_STOP, "Invalid stop code 99", invalidStops = setOf("99")) }
            else good
        }
        val c = coordinator(p)
        val r = c.update(sels)
        assertEquals(2, p.calls.size)
        assertEquals(setOf("36601"), p.calls[1].first)
        assertEquals(listOf(Status.OK, Status.ERROR), r.update.itemsList.map { it.status })
        assertNull(r.lastError)
        clock += 1
        assertEquals(false, c.update(sels).fetched)
    }

    @Test
    fun newlyAddedSelectionWaitsForTheWindowAndShowsPending() = runTest {
        val p = FakeProvider { good }
        val c = coordinator(p)
        c.update(sel)
        clock += 5
        val both = sel + SelectionSpec(2, "13", "36601")
        val r = c.update(both)
        assertEquals(false, r.fetched) // the rate limit is absolute
        assertEquals(listOf(Status.OK, Status.STALE), r.update.itemsList.map { it.status })
        clock += 40
        assertEquals(listOf(Status.OK, Status.OK), c.update(both).update.itemsList.map { it.status })
    }

    @Test
    fun noSelectionsNeverFetches() = runTest {
        val p = FakeProvider { good }
        assertEquals(0, coordinator(p).update(emptyList()).update.itemsCount)
        assertEquals(0, p.calls.size)
    }
}
