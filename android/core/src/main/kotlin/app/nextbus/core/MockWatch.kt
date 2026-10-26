package app.nextbus.core

import app.nextbus.protocol.Envelope
import app.nextbus.protocol.Hello
import app.nextbus.protocol.Reason
import app.nextbus.protocol.RefreshRequest
import app.nextbus.protocol.Role
import app.nextbus.protocol.Selection
import app.nextbus.protocol.Status
import app.nextbus.protocol.Transport

/** What one page of the watch app shows (same rules as watch/source/Format.mc). */
data class MockWatchRow(
    val line: String,
    val stopName: String,
    val operatorId: Int,
    val status: Status?,
    /** "now" or whole minutes, at most three. */
    val times: List<String>,
)

/**
 * A model of the watch app's side of the protocol, used in place of a real watch: unit tests and the
 * app's "Mock watch" debug mode. It is a [WatchLink] (receives what the phone sends, decoded exactly as
 * the watch would) and it speaks like the watch does: Hello + refresh when the app opens, a refresh
 * every [pollSeconds] while open, and a new refresh whenever the user "presses" refresh.
 *
 * [toPhone] is where the watch's messages go (the relay engine's onWatchMessage).
 */
class MockWatch(
    private val now: () -> Long,
    private val toPhone: suspend (List<Int>) -> Unit,
    /** Messages longer than this many integers are refused like the real SDK would (0 = no limit). */
    var maxMessageInts: Int = 0,
    val pollSeconds: Long = 40,
) : WatchLink {
    var connected = true
    var open = false
        private set
    var phoneHello: Hello? = null
        private set
    var selections: List<Selection> = emptyList()
        private set
    private val arrivals = HashMap<Int, Pair<Status, List<Long>>>()
    var generatedAt = 0L
        private set
    val received = mutableListOf<Envelope.BodyCase>()

    private var lastPollAt = 0L

    override suspend fun send(message: List<Int>): SendResult {
        if (!connected) return SendResult.FAILED
        if (maxMessageInts > 0 && message.size > maxMessageInts) return SendResult.TOO_LARGE
        val env = try { Transport.decode(message) } catch (e: Exception) { return SendResult.FAILED }
        received += env.bodyCase
        when (env.bodyCase) {
            Envelope.BodyCase.HELLO -> phoneHello = env.hello
            Envelope.BodyCase.SELECTIONS_SYNC -> {
                selections = env.selectionsSync.selectionsList
                arrivals.keys.retainAll(selections.map { it.id }.toSet())
            }
            Envelope.BodyCase.ARRIVALS_UPDATE -> {
                generatedAt = env.arrivalsUpdate.generatedAt.toLong() and 0xFFFFFFFFL
                env.arrivalsUpdate.itemsList.forEach {
                    arrivals[it.selectionId] = it.status to it.arrivalTimesList.map { t -> t.toLong() and 0xFFFFFFFFL }
                }
            }
            else -> return SendResult.FAILED
        }
        return SendResult.OK
    }

    /** The user opens the watch app. */
    suspend fun openApp() {
        open = true
        lastPollAt = now()
        toPhone(Transport.encode(Envelope.newBuilder().setHello(
            Hello.newBuilder().setProtocolVersion(version).setRole(Role.WATCH).setAppVersion("mock"),
        ).build()))
        refresh(Reason.APP_OPENED)
    }

    fun closeApp() { open = false }

    /** Press-to-refresh, page change that asks for fresh data, or the timer. */
    suspend fun refresh(reason: Reason = Reason.MANUAL) {
        lastPollAt = now()
        toPhone(Transport.encode(Envelope.newBuilder().setRefreshRequest(
            RefreshRequest.newBuilder().setProtocolVersion(version).setReason(reason),
        ).build()))
    }

    /** Call as time passes; sends the periodic refresh while the app is open. */
    suspend fun tick() {
        if (open && now() - lastPollAt >= pollSeconds) refresh(Reason.TIMER)
    }

    /** Protocol version the mock claims; tests change it to simulate an old/new watch app. */
    var version = Transport.PROTOCOL_VERSION

    fun view(): List<MockWatchRow> {
        val t = now()
        return selections.map { s ->
            val a = arrivals[s.id]
            MockWatchRow(
                line = s.line, stopName = s.stopName, operatorId = s.operatorId, status = a?.first,
                times = a?.second.orEmpty().filter { it > t - 30 }.take(3).map { minutes(it - t) },
            )
        }
    }

    private fun minutes(d: Long) = if (d < 60) "now" else (d / 60).toString()
}
