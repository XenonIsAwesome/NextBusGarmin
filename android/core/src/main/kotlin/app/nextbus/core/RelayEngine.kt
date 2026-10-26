package app.nextbus.core

import app.nextbus.protocol.ArrivalsUpdate
import app.nextbus.protocol.Envelope
import app.nextbus.protocol.Hello
import app.nextbus.protocol.RefreshRequest
import app.nextbus.protocol.Role
import app.nextbus.protocol.SelectionsSync
import app.nextbus.protocol.Transport

/** The phone's side of the link to the watch app (the Connect IQ SDK in the real app). */
interface WatchLink {
    /** Sends one message (the integer-list transport form). */
    suspend fun send(message: List<Int>): SendResult
}

enum class SendResult { OK, TOO_LARGE, FAILED }

sealed interface RelayEvent {
    data class WatchMessage(val kind: String, val protocolVersion: Int) : RelayEvent
    data class VersionMismatch(val watchVersion: Int) : RelayEvent
    data class SelectionsSent(val count: Int, val atEpoch: Long) : RelayEvent
    data class ArrivalsSent(val result: RelayResult, val atEpoch: Long) : RelayEvent
    data class Problem(val message: String) : RelayEvent
}

/**
 * Turns watch messages into replies. Stateless apart from what [coordinator] caches: every
 * trigger (watch Hello/Refresh, selections edited, UI refresh) ends up in [pushArrivals], which is
 * rate limited by the coordinator. Subset requests (`selection_ids`) are served like "all": one
 * batched curlbus request per cycle is the rule.
 */
class RelayEngine(
    private val coordinator: ArrivalCoordinator,
    private val selections: () -> List<SavedSelection>,
    private val link: WatchLink,
    private val now: () -> Long,
    private val appVersion: String,
    private val onEvent: (RelayEvent) -> Unit = {},
) {
    /** Entry point for everything the watch sends. */
    suspend fun onWatchMessage(message: List<Int>) {
        val env = try {
            Transport.decode(message)
        } catch (e: Exception) {
            onEvent(RelayEvent.Problem("Unreadable message from watch: ${e.message}"))
            return
        }
        when (env.bodyCase) {
            Envelope.BodyCase.HELLO -> onHello(env.hello)
            Envelope.BodyCase.REFRESH_REQUEST -> onRefresh(env.refreshRequest)
            else -> onEvent(RelayEvent.Problem("Unexpected message from watch: ${env.bodyCase}"))
        }
    }

    private suspend fun onHello(h: Hello) {
        onEvent(RelayEvent.WatchMessage("hello", h.protocolVersion))
        sendHello()
        if (h.protocolVersion != Transport.PROTOCOL_VERSION) {
            onEvent(RelayEvent.VersionMismatch(h.protocolVersion))
            return
        }
        pushSelections()
    }

    private suspend fun onRefresh(r: RefreshRequest) {
        onEvent(RelayEvent.WatchMessage("refresh:${r.reason.name.lowercase()}", r.protocolVersion))
        if (r.protocolVersion != Transport.PROTOCOL_VERSION) {
            // Answer with our Hello so the watch can show "update the phone/watch app".
            sendHello()
            onEvent(RelayEvent.VersionMismatch(r.protocolVersion))
            return
        }
        pushArrivals()
    }

    suspend fun sendHello() {
        val hello = Hello.newBuilder()
            .setProtocolVersion(Transport.PROTOCOL_VERSION)
            .setRole(Role.PHONE)
            .setAppVersion(appVersion)
            .build()
        send(Envelope.newBuilder().setHello(hello).build())
    }

    /** Replaces the watch's selection list. Retries once with shorter names if the message is too large. */
    suspend fun pushSelections() {
        val list = selections()
        var sync: SelectionsSync = SelectionsSyncBuilder.build(list)
        var result = send(Envelope.newBuilder().setSelectionsSync(sync).build())
        if (result == SendResult.TOO_LARGE) {
            sync = SelectionsSyncBuilder.build(list, SelectionsSyncBuilder.SHORT_NAME)
            result = send(Envelope.newBuilder().setSelectionsSync(sync).build())
        }
        if (result == SendResult.OK) onEvent(RelayEvent.SelectionsSent(sync.selectionsCount, now()))
    }

    /** Sends the latest arrivals (fetching from the provider only if the rate limit allows). */
    suspend fun pushArrivals() {
        val list = selections()
        if (list.isEmpty()) {
            send(Envelope.newBuilder().setArrivalsUpdate(emptyUpdate()).build())
            return
        }
        val result = coordinator.update(list.take(NextBusConfig.MAX_SELECTIONS).map { it.spec() })
        if (send(Envelope.newBuilder().setArrivalsUpdate(result.update).build()) == SendResult.OK) {
            onEvent(RelayEvent.ArrivalsSent(result, now()))
        }
    }

    /** Selections edited in the app: resend the list, then arrivals for the new set. */
    suspend fun onSelectionsChanged() {
        pushSelections()
        pushArrivals()
    }

    private fun emptyUpdate(): ArrivalsUpdate = ArrivalsUpdate.newBuilder()
        .setProtocolVersion(Transport.PROTOCOL_VERSION)
        .setGeneratedAt(now().toInt())
        .build()

    private suspend fun send(env: Envelope): SendResult {
        val result = link.send(Transport.encode(env))
        if (result != SendResult.OK) onEvent(RelayEvent.Problem("Could not send ${env.bodyCase.name.lowercase()} to the watch: $result"))
        return result
    }
}
