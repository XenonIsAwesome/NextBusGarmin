package app.nextbus.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import app.nextbus.BuildConfig
import app.nextbus.core.Geo
import java.io.File

data class StopHit(
    val code: String,
    val nameHe: String,
    val nameEn: String,
    /** nameEn is a machine transliteration (low quality). */
    val nameEnIsGuess: Boolean,
    val cityHe: String,
    val cityEn: String,
    val latE6: Int,
    val lonE6: Int,
    val distanceM: Int? = null,
)

data class LineOption(
    val line: String,
    val operatorId: Int,
    val operatorEn: String,
    val destCode: String,
    val destHe: String,
    val destEn: String,
)

data class IndexInfo(val builtAt: String, val stops: Int, val routes: Int, val schemaVersion: Int)

/**
 * The stop/line index (tools/gtfs-index): a read-only SQLite file in filesDir. A copy ships in the
 * APK assets; a newer one downloaded by [app.nextbus.data.IndexMaintenance] replaces it.
 */
class IndexRepository(private val context: Context) {
    private val file = File(context.filesDir, "index.db")
    private var db: SQLiteDatabase? = null

    @Synchronized
    private fun open(): SQLiteDatabase {
        db?.let { return it }
        ensureInstalled()
        return SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY).also { db = it }
    }

    /** Copies the bundled index on first run, and again after an app update if the bundle is newer. */
    private fun ensureInstalled() {
        val prefs = context.getSharedPreferences("index", Context.MODE_PRIVATE)
        val seenVersion = prefs.getInt("asset_version", -1)
        if (file.exists() && seenVersion == BuildConfig.VERSION_CODE) return
        val temp = File(context.cacheDir, "bundled-index.db")
        context.assets.open(ASSET).use { input -> temp.outputStream().use { input.copyTo(it) } }
        val bundled = readBuiltAt(temp)
        val installed = if (file.exists()) readBuiltAt(file) else null
        if (installed == null || (bundled != null && bundled > installed)) {
            temp.copyTo(file, overwrite = true)
        }
        temp.delete()
        prefs.edit().putInt("asset_version", BuildConfig.VERSION_CODE).apply()
    }

    private fun readBuiltAt(f: File): String? = runCatching {
        SQLiteDatabase.openDatabase(f.path, null, SQLiteDatabase.OPEN_READONLY).use { d ->
            d.rawQuery("SELECT value FROM meta WHERE key='built_at'", null).use { if (it.moveToFirst()) it.getString(0) else null }
        }
    }.getOrNull()

    fun info(): IndexInfo = open().rawQuery("SELECT key, value FROM meta", null).use { c ->
        val m = HashMap<String, String>()
        while (c.moveToNext()) m[c.getString(0)] = c.getString(1)
        IndexInfo(m["built_at"] ?: "", m["stops"]?.toIntOrNull() ?: 0, m["routes"]?.toIntOrNull() ?: 0, m["schema_version"]?.toIntOrNull() ?: 0)
    }

    /** Stops whose code starts with, or whose Hebrew/English name or city contains, [query]. */
    fun searchStops(query: String, limit: Int = 40): List<StopHit> {
        val q = query.trim()
        if (q.length < 2) return emptyList()
        val like = "%" + q.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%"
        val sql = """
            SELECT code, name_he, name_en, name_en_src, city_he, city_en, lat_e6, lon_e6 FROM stops
            WHERE code LIKE ? ESCAPE '\' OR name_he LIKE ? ESCAPE '\' OR name_en LIKE ? ESCAPE '\'
               OR city_he LIKE ? ESCAPE '\' OR city_en LIKE ? ESCAPE '\'
            ORDER BY (code = ?) DESC, (code LIKE ? ESCAPE '\') DESC, length(name_en), code
            LIMIT ?"""
        val prefix = like.removePrefix("%")
        return open().rawQuery(sql, arrayOf(prefix, like, like, like, like, q, prefix, limit.toString())).use { it.toStops() }
    }

    /** Stops closest to a point, widening the search box until [limit] are found (or ~5 km). */
    fun nearbyStops(latE6: Int, lonE6: Int, limit: Int = 30): List<StopHit> {
        for (boxE6 in listOf(5_000, 10_000, 20_000, 50_000)) {
            val sql = """
                SELECT code, name_he, name_en, name_en_src, city_he, city_en, lat_e6, lon_e6 FROM stops
                WHERE lat_e6 BETWEEN ? AND ? AND lon_e6 BETWEEN ? AND ?"""
            val args = arrayOf(latE6 - boxE6, latE6 + boxE6, lonE6 - boxE6, lonE6 + boxE6).map { it.toString() }.toTypedArray()
            val hits = open().rawQuery(sql, args).use { it.toStops() }
                .map { it.copy(distanceM = Geo.distanceM(latE6, lonE6, it.latE6, it.lonE6).toInt()) }
                .sortedBy { it.distanceM }
            if (hits.size >= limit || boxE6 == 50_000) return hits.take(limit)
        }
        return emptyList()
    }

    fun stop(code: String): StopHit? =
        open().rawQuery(
            "SELECT code, name_he, name_en, name_en_src, city_he, city_en, lat_e6, lon_e6 FROM stops WHERE code=?",
            arrayOf(code),
        ).use { it.toStops().firstOrNull() }

    /**
     * Lines serving [stopCode], one entry per line + operator + destination (alternatives of the
     * same line collapse). Lines that end at this stop are left out: nothing to catch there.
     */
    fun linesAt(stopCode: String): List<LineOption> {
        val sql = """
            SELECT r.line, r.operator_id, o.name_en, r.dest_code, r.dest_he, r.dest_en
            FROM route_stops rs
            JOIN routes r ON r.route_id = rs.route_id
            JOIN operators o ON o.operator_id = r.operator_id
            WHERE rs.stop_code = ? AND r.dest_code != rs.stop_code
            GROUP BY r.line, r.operator_id, r.dest_code
            ORDER BY CAST(r.line AS INTEGER), r.line, r.dest_en"""
        return open().rawQuery(sql, arrayOf(stopCode)).use { c ->
            buildList { while (c.moveToNext()) add(LineOption(c.getString(0), c.getInt(1), c.getString(2), c.getString(3), c.getString(4), c.getString(5))) }
        }
    }

    /** Validates a downloaded index and swaps it in. Returns an error message, or null on success. */
    @Synchronized
    fun install(bytes: ByteArray, supportedSchema: Int): String? {
        val temp = File(context.filesDir, "index.db.new")
        temp.writeBytes(bytes)
        val schema = runCatching {
            SQLiteDatabase.openDatabase(temp.path, null, SQLiteDatabase.OPEN_READONLY).use { d ->
                d.rawQuery("SELECT value FROM meta WHERE key='schema_version'", null).use { if (it.moveToFirst()) it.getInt(0) else -1 }
            }
        }.getOrElse { temp.delete(); return "downloaded file is not a valid index: ${it.message}" }
        if (schema != supportedSchema) {
            temp.delete()
            return "downloaded index has schema $schema, this app understands $supportedSchema"
        }
        db?.close()
        db = null
        if (!temp.renameTo(file)) {
            temp.copyTo(file, overwrite = true)
            temp.delete()
        }
        return null
    }

    private fun android.database.Cursor.toStops(): List<StopHit> = buildList {
        while (moveToNext()) {
            add(StopHit(getString(0), getString(1), getString(2), getInt(3) == 1, getString(4), getString(5), getInt(6), getInt(7)))
        }
    }

    companion object {
        const val ASSET = "index.db"
        const val SUPPORTED_SCHEMA = 1
    }
}
