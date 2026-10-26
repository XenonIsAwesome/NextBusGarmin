package app.nextbus.core

/** One predicted arrival of a line at a stop, provider-neutral. */
data class Visit(
    val stopCode: String,
    val lineName: String,
    val destinationId: String?,
    val etaEpoch: Long,
)

/**
 * What the phone persists per selection. [destinationId] disambiguates a line
 * that serves one stop in several directions (null = match any destination).
 */
data class SelectionSpec(
    val id: Int,
    val line: String,
    val stopCode: String,
    val destinationId: String? = null,
)

enum class FailureKind { NETWORK, RATE_LIMITED, SERVER, INVALID_STOP, BAD_RESPONSE }

sealed interface FetchResult {
    data class Success(val visits: List<Visit>) : FetchResult

    data class Failure(
        val kind: FailureKind,
        val message: String,
        val retryAfterSeconds: Long? = null,
        val invalidStops: Set<String> = emptySet(),
    ) : FetchResult
}
