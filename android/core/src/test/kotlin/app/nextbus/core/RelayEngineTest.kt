package app.nextbus.core

import app.nextbus.protocol.Envelope
import app.nextbus.protocol.Hello
import app.nextbus.protocol.Reason
import app.nextbus.protocol.RefreshRequest
import app.nextbus.protocol.Role
import app.nextbus.protocol.Status
import app.nextbus.protocol.Transport
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class RelayEngineTest {
    private class FakeLink(var result: () -> SendResult = { SendResult.OK }) : WatchLink {
        val sent = mutableListOf<Envelope>()
        override suspend fun send(message: List<Int>): SendResult {
            val r = result()
            if (r == SendResult.OK || r == SendResult.TOO_LARGE) sent += Transport.decode(message)
            return r
        }
    }

    private class FakeProvider(var result: FetchResult) : ArrivalProvider {
        var calls = 0
        override suspend fun fetch(stopCodes: Collection<String>, lines: Set<String>): FetchResult {
            calls++
            return result
        }
    }

    private var clock = Fixtures.capturedAt("stop_36601.json")
    private val good = CurlbusParser.parse(Fixtures.text("stop_36601.json"))
    private val sel = SavedSelection(
        id = 1, line = "29", operatorId = 15, stopCode = "36601", stopNameEn = "Beit Loewenstein/Ahuza",
        stopNameHe = "בית לוינשטיין/אחוזה", destCode = "27104", destNameEn = "HaShunit/Yorde Yam",
        destNameHe = "השונית/יורדי ים", latE6 = 32184760, lonE6 = 34855867,
    )
    private var saved = listOf(sel)
    private val events = mutableListOf<RelayEvent>()

    private fun hello(version: Int) = Transport.encode(
        Envelope.newBuilder().setHello(Hello.newBuilder().setProtocolVersion(version).setRole(Role.WATCH).setAppVersion("0.1")).build(),
    )

    private fun refresh(version: Int = Transport.PROTOCOL_VERSION, reason: Reason = Reason.TIMER) = Transport.encode(
        Envelope.newBuilder().setRefreshRequest(RefreshRequest.newBuilder().setProtocolVersion(version).setReason(reason)).build(),
    )

    private fun engine(link: FakeLink, provider: FakeProvider) = RelayEngine(
        ArrivalCoordinator(provider, { clock }, refreshSeconds = 40, maxBackoffSeconds = 300),
        { saved }, link, { clock }, "test", { events += it },
    )

    @Test
    fun helloGetsPhoneHelloThenSelections() = runTest {
        val link = FakeLink()
        engine(link, FakeProvider(good)).onWatchMessage(hello(Transport.PROTOCOL_VERSION))
        assertEquals(listOf(Envelope.BodyCase.HELLO, Envelope.BodyCase.SELECTIONS_SYNC), link.sent.map { it.bodyCase })
        assertEquals(Role.PHONE, link.sent[0].hello.role)
        val s = link.sent[1].selectionsSync.getSelections(0)
        assertEquals(listOf("29", "36601", 15), listOf(s.line, s.stopCode, s.operatorId))
        assertEquals("HaShunit/Yorde Yam", s.destination)
        assertTrue(events.any { it is RelayEvent.SelectionsSent })
    }

    @Test
    fun refreshFetchesAndRepliesWithArrivals() = runTest {
        val link = FakeLink()
        val provider = FakeProvider(good)
        engine(link, provider).onWatchMessage(refresh(reason = Reason.APP_OPENED))
        val update = link.sent.single().arrivalsUpdate
        assertEquals(Status.OK, update.getItems(0).status)
        assertEquals(1, provider.calls)
        val sent = events.filterIsInstance<RelayEvent.ArrivalsSent>().single()
        assertTrue(sent.result.fetched)
    }

    @Test
    fun watchRequestsInsideTheIntervalReplyFromCacheWithoutFetching() = runTest {
        val link = FakeLink()
        val provider = FakeProvider(good)
        val e = engine(link, provider)
        e.onWatchMessage(refresh())
        repeat(10) { clock += 2; e.onWatchMessage(refresh()) }  // rapid page switching, timers, manual presses
        assertEquals(1, provider.calls)
        assertEquals(11, link.sent.size) // every request is answered
        assertTrue(link.sent.all { it.arrivalsUpdate.getItems(0).status == Status.OK })
    }

    @Test
    fun providerFailureStillAnswersWithStaleOrError() = runTest {
        val link = FakeLink()
        val provider = FakeProvider(FetchResult.Failure(FailureKind.NETWORK, "offline"))
        engine(link, provider).onWatchMessage(refresh())
        assertEquals(Status.ERROR, link.sent.single().arrivalsUpdate.getItems(0).status)
        assertIs<RelayEvent.ArrivalsSent>(events.last())
        assertEquals(FailureKind.NETWORK, (events.last() as RelayEvent.ArrivalsSent).result.lastError?.kind)
    }

    @Test
    fun versionMismatchRepliesWithHelloOnly() = runTest {
        val link = FakeLink()
        val provider = FakeProvider(good)
        val e = engine(link, provider)
        e.onWatchMessage(hello(Transport.PROTOCOL_VERSION + 1))
        e.onWatchMessage(refresh(version = Transport.PROTOCOL_VERSION + 1))
        assertEquals(listOf(Envelope.BodyCase.HELLO, Envelope.BodyCase.HELLO), link.sent.map { it.bodyCase })
        assertEquals(0, provider.calls)
        assertEquals(2, events.filterIsInstance<RelayEvent.VersionMismatch>().size)
    }

    @Test
    fun noSelectionsReturnsAnEmptyUpdateWithoutFetching() = runTest {
        saved = emptyList()
        val link = FakeLink()
        val provider = FakeProvider(good)
        engine(link, provider).onWatchMessage(refresh())
        assertEquals(0, link.sent.single().arrivalsUpdate.itemsCount)
        assertEquals(0, provider.calls)
    }

    @Test
    fun tooLargeSelectionsAreRetriedWithShortNames() = runTest {
        var n = 0
        val link = FakeLink({ if (n++ == 0) SendResult.TOO_LARGE else SendResult.OK })
        engine(link, FakeProvider(good)).pushSelections()
        assertEquals(2, link.sent.size)
        assertEquals(SelectionsSyncBuilder.SHORT_NAME, link.sent[1].selectionsSync.getSelections(0).stopName.length)
        assertTrue(events.filterIsInstance<RelayEvent.SelectionsSent>().isNotEmpty())
    }

    @Test
    fun garbageFromWatchIsReportedNotFatal() = runTest {
        val link = FakeLink()
        engine(link, FakeProvider(good)).onWatchMessage(listOf(99, 1))
        assertEquals(0, link.sent.size)
        assertIs<RelayEvent.Problem>(events.single())
    }

    @Test
    fun selectionsChangedSendsListThenArrivals() = runTest {
        val link = FakeLink()
        engine(link, FakeProvider(good)).onSelectionsChanged()
        assertEquals(listOf(Envelope.BodyCase.SELECTIONS_SYNC, Envelope.BodyCase.ARRIVALS_UPDATE), link.sent.map { it.bodyCase })
    }

    @Test
    fun selectionsAreCappedAtTheWatchLimit() {
        val many = (1..15).map { sel.copy(id = it) }
        assertEquals(NextBusConfig.MAX_SELECTIONS, SelectionsSyncBuilder.build(many).selectionsCount)
    }
}
