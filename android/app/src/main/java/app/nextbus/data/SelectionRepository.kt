package app.nextbus.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import app.nextbus.core.NextBusConfig
import app.nextbus.core.SavedSelection
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

private val Context.selectionStore by preferencesDataStore("selections")

enum class AddResult { ADDED, FULL, DUPLICATE }

/** The user's "bus line @ stop" selections, persisted with DataStore. Order = order on the watch. */
class SelectionRepository(private val context: Context) {
    private val json = Json { ignoreUnknownKeys = true }
    private val listKey = stringPreferencesKey("list")
    private val nextIdKey = intPreferencesKey("next_id")
    private val serializer = ListSerializer(SavedSelection.serializer())

    val selections: Flow<List<SavedSelection>> = context.selectionStore.data.map { decode(it[listKey]) }

    suspend fun current(): List<SavedSelection> = selections.first()

    suspend fun add(stop: StopHit, line: LineOption): AddResult {
        var result = AddResult.ADDED
        context.selectionStore.edit { prefs ->
            val list = decode(prefs[listKey])
            when {
                list.any { it.stopCode == stop.code && it.line == line.line && it.operatorId == line.operatorId && it.destCode == line.destCode } ->
                    result = AddResult.DUPLICATE
                list.size >= NextBusConfig.MAX_SELECTIONS -> result = AddResult.FULL
                else -> {
                    val id = prefs[nextIdKey] ?: 1
                    prefs[nextIdKey] = id + 1
                    prefs[listKey] = json.encodeToString(
                        serializer,
                        list + SavedSelection(
                            id = id, line = line.line, operatorId = line.operatorId, stopCode = stop.code,
                            stopNameEn = stop.nameEn, stopNameHe = stop.nameHe, destCode = line.destCode,
                            destNameEn = line.destEn, destNameHe = line.destHe, latE6 = stop.latE6, lonE6 = stop.lonE6,
                            nameIsGuess = stop.nameEnIsGuess,
                        ),
                    )
                }
            }
        }
        return result
    }

    suspend fun remove(id: Int) = update { list -> list.filterNot { it.id == id } }

    /** Moves the selection one place up (-1) or down (+1). */
    suspend fun move(id: Int, delta: Int) = update { list ->
        val i = list.indexOfFirst { it.id == id }
        val j = i + delta
        if (i < 0 || j !in list.indices) list else list.toMutableList().also { it[i] = list[j]; it[j] = list[i] }
    }

    /** Edits the English stop name the watch shows (clears the "machine guess" flag). */
    suspend fun renameStop(id: Int, nameEn: String) = update { list ->
        list.map { if (it.id == id) it.copy(stopNameEn = nameEn.trim().ifEmpty { it.stopNameEn }, nameIsGuess = false) else it }
    }

    private suspend fun update(transform: (List<SavedSelection>) -> List<SavedSelection>) {
        context.selectionStore.edit { prefs -> prefs[listKey] = json.encodeToString(serializer, transform(decode(prefs[listKey]))) }
    }

    private fun decode(s: String?): List<SavedSelection> =
        if (s.isNullOrEmpty()) emptyList() else runCatching { json.decodeFromString(serializer, s) }.getOrDefault(emptyList())
}
