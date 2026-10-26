package app.nextbus.core

/**
 * Source of live arrivals. Implementations own transport and parsing only;
 * rate limiting lives in [ArrivalCoordinator] and protocol mapping in
 * [ArrivalsConverter], so a provider can be swapped (own curlbus instance,
 * Ministry SIRI API) without touching either.
 */
interface ArrivalProvider {
    /** One batched fetch for [stopCodes]; [lines] is a hint to shrink the payload. */
    suspend fun fetch(stopCodes: Collection<String>, lines: Set<String>): FetchResult
}
