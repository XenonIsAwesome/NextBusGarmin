package app.nextbus.core

import java.time.OffsetDateTime
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject

/**
 * Pure mapping of a curlbus JSON body to [Visit]s. Fields used (verified
 * against real responses, see test fixtures): `errors`, `visits.<stop>[]` with
 * `stop_code`, `line_name`, `destination_id`, `eta` ("2026-10-26 21:16:00+02:00").
 */
object CurlbusParser {
    private val invalidStop = Regex("""Invalid stop code (\S+)""")

    fun parse(body: String): FetchResult {
        val root = try {
            Json.parseToJsonElement(body)
        } catch (e: Exception) {
            return bad("response is not JSON")
        } as? JsonObject ?: return bad("response is not a JSON object")

        val visitsEl = root["visits"] as? JsonObject
        val errors = (root["errors"] as? JsonArray).orEmpty().mapNotNull { it.str() }
        if (visitsEl == null) {
            val invalid = errors.mapNotNull { invalidStop.find(it)?.groupValues?.get(1) }.toSet()
            if (invalid.isNotEmpty()) {
                return FetchResult.Failure(FailureKind.INVALID_STOP, errors.joinToString().trim(), invalidStops = invalid)
            }
            return bad(errors.firstOrNull()?.trim() ?: "no visits in response")
        }

        val visits = ArrayList<Visit>()
        for ((key, list) in visitsEl) {
            for (v in (list as? JsonArray).orEmpty()) {
                val o = v as? JsonObject ?: continue
                val line = o["line_name"].str() ?: continue
                val eta = parseTime(o["eta"].str()) ?: continue
                visits += Visit(
                    stopCode = o["stop_code"].str() ?: key,
                    lineName = line,
                    destinationId = o["destination_id"].str(),
                    etaEpoch = eta,
                )
            }
        }
        return FetchResult.Success(visits)
    }

    /** Accepts "2026-10-26 21:16:00+02:00" and the ISO "T" form. Null if unparseable. */
    fun parseTime(s: String?): Long? {
        if (s.isNullOrBlank()) return null
        return try {
            OffsetDateTime.parse(s.trim().replace(' ', 'T')).toEpochSecond()
        } catch (e: Exception) {
            null
        }
    }

    private fun bad(msg: String) = FetchResult.Failure(FailureKind.BAD_RESPONSE, msg)

    private fun JsonElement?.str(): String? = (this as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
}
