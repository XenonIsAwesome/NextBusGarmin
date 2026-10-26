package app.nextbus.core

/**
 * Build-time configuration for the phone side, in one place (see docs/README.md).
 * Changing a value here is a rebuild; there are no runtime settings.
 */
object NextBusConfig {
    /**
     * Minimum seconds between curlbus requests. Shared by every trigger (watch
     * requests, app UI, background). curlbus caches for ~30s and its maintainer
     * protects the upstream Ministry quota, so do not go below ~30.
     */
    const val REFRESH_SECONDS = 40L

    /** Upper bound of the exponential back-off after errors / HTTP 429 / 5xx. */
    const val MAX_BACKOFF_SECONDS = 300L

    /** Swap this to point at a self-hosted curlbus instance. No trailing slash needed. */
    const val CURLBUS_BASE_URL = "https://curlbus.app"

    /** Selections the watch is sent (matches Config.MAX_SELECTIONS on the watch). */
    const val MAX_SELECTIONS = 10
}
