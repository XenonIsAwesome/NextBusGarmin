package app.nextbus.core

import app.nextbus.protocol.ArrivalsUpdate
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class RelayResult(
    val update: ArrivalsUpdate,
    /** true if this call hit the provider. */
    val fetched: Boolean,
    /** The most recent failure, until a fetch succeeds again. */
    val lastError: FetchResult.Failure?,
    val lastGoodAt: Long?,
)

/**
 * The single place that decides whether curlbus is hit. Every trigger (watch
 * request, UI, background) calls [update]; calls are serialised, and a fetch
 * happens at most once per [refreshSeconds] (longer while backing off after
 * errors). Inside the window the last good data is returned, re-evaluated
 * against the current time so passed arrivals drop off.
 */
class ArrivalCoordinator(
    private val provider: ArrivalProvider,
    private val now: () -> Long,
    private val refreshSeconds: Long = NextBusConfig.REFRESH_SECONDS,
    private val maxBackoffSeconds: Long = NextBusConfig.MAX_BACKOFF_SECONDS,
) {
    private val mutex = Mutex()
    private var visits: List<Visit>? = null
    private var coveredIds: Set<Int> = emptySet()
    private var lastGoodAt: Long? = null
    private var nextAllowedAt = 0L
    private var failures = 0
    private var lastError: FetchResult.Failure? = null
    private val invalidStops = mutableSetOf<String>()

    suspend fun update(selections: List<SelectionSpec>): RelayResult = mutex.withLock {
        val t = now()
        invalidStops.retainAll(selections.map { it.stopCode }.toSet())
        var fetched = false
        if (t >= nextAllowedAt && selections.isNotEmpty()) {
            fetched = refresh(selections, t)
        }
        RelayResult(buildUpdate(selections, t), fetched, lastError, lastGoodAt)
    }

    private suspend fun refresh(selections: List<SelectionSpec>, t: Long): Boolean {
        nextAllowedAt = t + refreshSeconds
        var attempted = false
        // At most one retry per cycle, and only to drop stops curlbus rejected:
        // an invalid stop code fails the whole batched request.
        repeat(2) {
            val active = selections.filter { it.stopCode !in invalidStops }
            if (active.isEmpty()) return attempted
            attempted = true
            val result = provider.fetch(active.map { it.stopCode }.toSet(), active.map { it.line }.toSet())
            when (result) {
                is FetchResult.Success -> {
                    visits = result.visits
                    coveredIds = active.map { it.id }.toSet()
                    lastGoodAt = t
                    failures = 0
                    lastError = null
                    return true
                }
                is FetchResult.Failure -> {
                    lastError = result
                    if (result.kind == FailureKind.INVALID_STOP && result.invalidStops.isNotEmpty() &&
                        invalidStops.addAll(result.invalidStops)
                    ) {
                        return@repeat // retry once without them
                    }
                    failures++
                    val backoff = (refreshSeconds shl failures.coerceAtMost(10)).coerceAtMost(maxBackoffSeconds)
                    nextAllowedAt = t + maxOf(backoff, result.retryAfterSeconds ?: 0)
                    return true
                }
            }
        }
        return attempted
    }

    private fun buildUpdate(selections: List<SelectionSpec>, t: Long): ArrivalsUpdate {
        val failed = lastError != null && lastError!!.kind != FailureKind.INVALID_STOP
        return ArrivalsConverter.convert(
            selections = selections,
            visits = visits,
            fetchedAt = lastGoodAt ?: t,
            now = t,
            stale = failed,
            failedStops = invalidStops,
            pending = selections.map { it.id }.toSet() - coveredIds,
        )
    }
}
