package app.nextbus.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GeoTest {
    @Test
    fun matchesTheWatchTestCases() {
        // The same numbers as watch/source/tests/RelevanceTest.mc.
        assertEquals(650.0, Geo.distanceM(32184760, 34855867, 32184760 + 5839, 34855867), 5.0)
        assertEquals(943.0, Geo.distanceM(32184760, 34855867, 32184760, 34855867 + 10000), 8.0)
        assertEquals(0.0, Geo.distanceM(1, 2, 1, 2))
    }

    @Test
    fun realStopsInRaanana() {
        // 36601 Beit Loewenstein/Ahuza to 36602 Weizman/Rivka Gover: ~1 km apart.
        val d = Geo.distanceM(32184760, 34855867, 32192499, 34850301)
        assertTrue(d in 900.0..1100.0, "got $d")
    }
}
