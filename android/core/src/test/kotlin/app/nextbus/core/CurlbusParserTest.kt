package app.nextbus.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CurlbusParserTest {
    private fun success(name: String) = assertIs<FetchResult.Success>(CurlbusParser.parse(Fixtures.text(name)))

    @Test
    fun realStopResponse() {
        val visits = success("stop_36601.json").visits
        assertEquals(12, visits.size)
        val first = visits.first()
        assertEquals("36601", first.stopCode)
        assertEquals("29", first.lineName)
        assertEquals("27104", first.destinationId)
        // "2026-10-26 21:33:00+02:00" == 19:33:00Z
        assertEquals(1_793_043_180L, first.etaEpoch)
        assertEquals(4, visits.count { it.lineName == "29" })
    }

    @Test
    fun multiStopResponseKeepsStopCodes() {
        val visits = success("multi_36601_36602.json").visits
        assertEquals(setOf("36601", "36602"), visits.map { it.stopCode }.toSet())
    }

    @Test
    fun emptyVisitsIsSuccessWithNoVisits() {
        assertEquals(emptyList(), success("empty_visits.json").visits)
    }

    @Test
    fun invalidStopReportsTheStop() {
        val f = assertIs<FetchResult.Failure>(CurlbusParser.parse(Fixtures.text("invalid_stop.json")))
        assertEquals(FailureKind.INVALID_STOP, f.kind)
        assertEquals(setOf("99999999"), f.invalidStops)
    }

    @Test
    fun batchWithOneInvalidStopFailsWholeRequest() {
        val f = assertIs<FetchResult.Failure>(CurlbusParser.parse(Fixtures.text("mixed_valid_invalid.json")))
        assertEquals(FailureKind.INVALID_STOP, f.kind)
    }

    @Test
    fun garbageIsBadResponse() {
        for (body in listOf(Fixtures.text("garbage.html"), "", "[]", "{}", """{"visits": 3}""")) {
            val f = assertIs<FetchResult.Failure>(CurlbusParser.parse(body), body)
            assertEquals(FailureKind.BAD_RESPONSE, f.kind, body)
        }
    }

    @Test
    fun oddVisitsAreSkippedNotFatal() {
        val body = """{"errors":null,"visits":{"1":[
            {"line_name":"5","eta":null},
            {"line_name":"6","eta":"not a time"},
            {"eta":"2026-10-26 21:16:00+02:00"},
            "junk",
            {"line_name":"7","eta":"2026-10-26T21:16:00+02:00","status":"cancelled"}]}}"""
        val visits = (CurlbusParser.parse(body) as FetchResult.Success).visits
        assertEquals(listOf("7"), visits.map { it.lineName })
        assertEquals("1", visits[0].stopCode) // falls back to the map key
        assertNull(visits[0].destinationId)
    }

    @Test
    fun parseTimeAcceptsBothForms() {
        assertEquals(CurlbusParser.parseTime("2026-10-26T21:15:38+02:00"), CurlbusParser.parseTime("2026-10-26 21:15:38+02:00"))
        assertNull(CurlbusParser.parseTime("2026-10-26 21:15:38")) // no offset: ambiguous, rejected
        assertTrue(CurlbusParser.parseTime(null) == null)
    }
}
