package app.nextbus.core

import app.nextbus.protocol.SelectionArrivals
import app.nextbus.protocol.Status
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Real curlbus captures (2026-10-26), see src/test/resources/fixtures. */
object Fixtures {
    fun text(name: String): String =
        Fixtures::class.java.getResource("/fixtures/$name")!!.readText()

    /** The response's own top-level timestamp, as epoch seconds. */
    fun capturedAt(name: String): Long =
        CurlbusParser.parseTime(Json.parseToJsonElement(text(name)).jsonObject["timestamp"]!!.jsonPrimitive.content)!!
}

fun SelectionArrivals.summary() = "$selectionId:${status.name}:${arrivalTimesList}"
fun statusOf(item: SelectionArrivals): Status = item.status
