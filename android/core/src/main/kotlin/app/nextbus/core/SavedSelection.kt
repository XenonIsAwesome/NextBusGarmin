package app.nextbus.core

import app.nextbus.protocol.Selection
import app.nextbus.protocol.SelectionsSync
import app.nextbus.protocol.Transport
import kotlinx.serialization.Serializable

/**
 * A "bus line @ stop" the user picked, as persisted on the phone. The watch only ever sees the
 * English subset via [SelectionsSyncBuilder]; the Hebrew names are for the phone UI.
 * [destCode] is the stop code of the line's last stop (curlbus `destination_id`) and
 * separates the two directions of one line at a stop.
 */
@Serializable
data class SavedSelection(
    val id: Int,
    val line: String,
    val operatorId: Int,
    val stopCode: String,
    val stopNameEn: String,
    val stopNameHe: String,
    val destCode: String?,
    val destNameEn: String,
    val destNameHe: String,
    val latE6: Int,
    val lonE6: Int,
    /** The English stop name is a machine transliteration (low quality): ask the user to review it. */
    val nameIsGuess: Boolean = false,
) {
    fun spec() = SelectionSpec(id, line, stopCode, destCode)
}

object SelectionsSyncBuilder {
    /** Longest English name sent by default; a retry after a "too large" failure uses [SHORT_NAME]. */
    const val MAX_NAME = 40
    const val SHORT_NAME = 18

    fun build(selections: List<SavedSelection>, maxNameLength: Int = MAX_NAME): SelectionsSync =
        SelectionsSync.newBuilder()
            .setProtocolVersion(Transport.PROTOCOL_VERSION)
            .addAllSelections(
                selections.take(NextBusConfig.MAX_SELECTIONS).map {
                    Selection.newBuilder()
                        .setId(it.id)
                        .setLine(it.line)
                        .setStopCode(it.stopCode)
                        .setStopName(it.stopNameEn.take(maxNameLength))
                        .setDestination(it.destNameEn.take(maxNameLength))
                        .setLatE6(it.latE6)
                        .setLonE6(it.lonE6)
                        .setOperatorId(it.operatorId)
                        .build()
                },
            )
            .build()
}
