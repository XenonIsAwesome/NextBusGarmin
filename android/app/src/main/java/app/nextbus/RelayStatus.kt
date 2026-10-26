package app.nextbus

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** Everything the home screen shows about the link to the watch. Written by the relay service. */
data class RelayStatus(
    val running: Boolean = false,
    /** Problem with the Garmin side: Garmin Connect missing/outdated, SDK error, no watch. */
    val linkProblem: String? = null,
    val watchName: String? = null,
    val watchConnected: Boolean = false,
    /** null = not known yet. */
    val watchAppInstalled: Boolean? = null,
    val lastWatchMessageAt: Long? = null,
    /** Selections last sent to the watch. */
    val lastSyncAt: Long? = null,
    /** The last successful curlbus fetch. */
    val lastFetchAt: Long? = null,
    val lastError: String? = null,
    /** The watch speaks this protocol version and we do not. */
    val versionMismatch: Int? = null,
)

object RelayStatusStore {
    private val _status = MutableStateFlow(RelayStatus())
    val status: StateFlow<RelayStatus> = _status.asStateFlow()

    fun update(transform: (RelayStatus) -> RelayStatus) = _status.update(transform)
    fun reset() = _status.update { RelayStatus() }
}
