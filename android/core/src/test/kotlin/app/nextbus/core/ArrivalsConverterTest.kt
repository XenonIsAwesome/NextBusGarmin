package app.nextbus.core

import app.nextbus.protocol.Status
import kotlin.test.Test
import kotlin.test.assertEquals

class ArrivalsConverterTest {
    private val visits = (CurlbusParser.parse(Fixtures.text("stop_36601.json")) as FetchResult.Success).visits
    private val at = Fixtures.capturedAt("stop_36601.json")

    @Test
    fun picksUpToThreeSortedFutureArrivalsOfTheLine() {
        val u = ArrivalsConverter.convert(listOf(SelectionSpec(1, "29", "36601")), visits, at, at)
        val item = u.getItems(0)
        assertEquals(Status.OK, item.status)
        assertEquals(3, item.arrivalTimesCount)
        assertEquals(item.arrivalTimesList.sorted(), item.arrivalTimesList)
        assertEquals(at.toInt(), u.generatedAt)
    }

    @Test
    fun pastArrivalsAreDroppedRelativeToNow() {
        val first = visits.first { it.lineName == "29" }.etaEpoch
        val u = ArrivalsConverter.convert(listOf(SelectionSpec(1, "29", "36601")), visits, at, first + 1)
        assertEquals(3, u.getItems(0).arrivalTimesCount) // 4 buses, the first has passed
        assertEquals(true, u.getItems(0).arrivalTimesList.all { it > first })
    }

    @Test
    fun destinationDisambiguatesDirections() {
        val other = SelectionSpec(1, "29", "36601", destinationId = "does-not-exist")
        val u = ArrivalsConverter.convert(listOf(other), visits, at, at)
        assertEquals(Status.NO_SERVICE, u.getItems(0).status)
    }

    @Test
    fun noMatchingVisitsIsNoService() {
        val u = ArrivalsConverter.convert(listOf(SelectionSpec(1, "999", "36601")), visits, at, at)
        assertEquals(Status.NO_SERVICE, u.getItems(0).status)
        assertEquals(0, u.getItems(0).arrivalTimesCount)
        val empty = ArrivalsConverter.convert(listOf(SelectionSpec(1, "29", "36601")), emptyList(), at, at)
        assertEquals(Status.NO_SERVICE, empty.getItems(0).status)
    }

    @Test
    fun staleKeepsLastGoodTimesMarkedStale() {
        val u = ArrivalsConverter.convert(listOf(SelectionSpec(1, "29", "36601")), visits, at, at + 20, stale = true)
        assertEquals(Status.STALE, u.getItems(0).status)
        assertEquals(3, u.getItems(0).arrivalTimesCount)
        assertEquals(at.toInt(), u.generatedAt) // age is measured from the data, not from now
    }

    @Test
    fun neverFetchedIsError_invalidStopIsError_pendingIsStale() {
        val sels = listOf(SelectionSpec(1, "29", "36601"), SelectionSpec(2, "1", "99"), SelectionSpec(3, "13", "36601"))
        val u = ArrivalsConverter.convert(sels, visits, at, at, failedStops = setOf("99"), pending = setOf(3))
        assertEquals(listOf(Status.OK, Status.ERROR, Status.STALE), u.itemsList.map { it.status })
        val none = ArrivalsConverter.convert(sels, null, 0, at)
        assertEquals(true, none.itemsList.all { it.status == Status.ERROR })
    }

    @Test
    fun multiStopFixtureRoundTripsThroughTransport() {
        val v = (CurlbusParser.parse(Fixtures.text("multi_36601_36602.json")) as FetchResult.Success).visits
        val u = ArrivalsConverter.convert(
            listOf(SelectionSpec(1, "29", "36601"), SelectionSpec(2, "531", "36602")), v,
            Fixtures.capturedAt("multi_36601_36602.json"), Fixtures.capturedAt("multi_36601_36602.json"),
        )
        val env = app.nextbus.protocol.Envelope.newBuilder().setArrivalsUpdate(u).build()
        assertEquals(env, app.nextbus.protocol.Transport.decode(app.nextbus.protocol.Transport.encode(env)))
    }
}
