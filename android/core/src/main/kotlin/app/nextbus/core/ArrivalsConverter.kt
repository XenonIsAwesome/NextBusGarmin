package app.nextbus.core

import app.nextbus.protocol.ArrivalsUpdate
import app.nextbus.protocol.SelectionArrivals
import app.nextbus.protocol.Status
import app.nextbus.protocol.Transport

/** Pure Visit -> protocol mapping. All times are absolute epoch seconds. */
object ArrivalsConverter {
    const val MAX_ARRIVALS = 3

    /**
     * @param visits null when no data was ever fetched
     * @param fetchedAt epoch seconds of the fetch that produced [visits] (becomes generated_at)
     * @param now arrivals before this are dropped
     * @param stale the latest fetch failed, so [visits] are old: report STALE
     * @param failedStops selections at these stops are reported ERROR (e.g. invalid stop code)
     * @param pending selection ids not covered by [visits] yet: reported STALE with no times
     */
    fun convert(
        selections: List<SelectionSpec>,
        visits: List<Visit>?,
        fetchedAt: Long,
        now: Long,
        stale: Boolean = false,
        failedStops: Set<String> = emptySet(),
        pending: Set<Int> = emptySet(),
    ): ArrivalsUpdate {
        val items = selections.map { sel ->
            val b = SelectionArrivals.newBuilder().setSelectionId(sel.id)
            when {
                sel.stopCode in failedStops || visits == null -> b.status = Status.ERROR
                sel.id in pending -> b.status = Status.STALE
                else -> {
                    val times = visits.asSequence()
                        .filter { it.stopCode == sel.stopCode && it.lineName == sel.line }
                        .filter { sel.destinationId == null || it.destinationId == sel.destinationId }
                        .map { it.etaEpoch }
                        .filter { it >= now }
                        .distinct().sorted().take(MAX_ARRIVALS).toList()
                    b.addAllArrivalTimes(times.map { it.toInt() })
                    b.status = when {
                        stale -> Status.STALE
                        times.isEmpty() -> Status.NO_SERVICE
                        else -> Status.OK
                    }
                }
            }
            b.build()
        }
        return ArrivalsUpdate.newBuilder()
            .setProtocolVersion(Transport.PROTOCOL_VERSION)
            .setGeneratedAt(if (visits == null) now.toInt() else fetchedAt.toInt())
            .addAllItems(items)
            .build()
    }
}
