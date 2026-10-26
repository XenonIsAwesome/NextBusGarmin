package app.nextbus.core

import kotlin.math.cos
import kotlin.math.sqrt

object Geo {
    /** Metres between two points in microdegrees (equirectangular; same formula as the watch's Relevance.mc). */
    fun distanceM(latE6a: Int, lonE6a: Int, latE6b: Int, lonE6b: Int): Double {
        val dLat = (latE6b - latE6a) * 1e-6 * 111_320.0
        val midLat = Math.toRadians((latE6a + latE6b) * 5e-7)
        val dLon = (lonE6b - lonE6a) * 1e-6 * 111_320.0 * cos(midLat)
        return sqrt(dLat * dLat + dLon * dLon)
    }
}
