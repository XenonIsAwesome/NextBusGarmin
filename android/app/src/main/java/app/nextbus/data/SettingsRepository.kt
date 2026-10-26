package app.nextbus.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.settingsStore by preferencesDataStore("settings")

class SettingsRepository(private val context: Context) {
    private val relayKey = booleanPreferencesKey("relay_enabled")
    private val tetheredKey = booleanPreferencesKey("tethered")
    private val mockKey = booleanPreferencesKey("mock_watch")

    /** Keep the relay (foreground service) running so the watch can reach the phone app. */
    val relayEnabled: Flow<Boolean> = context.settingsStore.data.map { it[relayKey] ?: true }

    /** Debug: talk to the Connect IQ simulator over `adb forward tcp:7381 tcp:7381` instead of Garmin Connect. */
    val tethered: Flow<Boolean> = context.settingsStore.data.map { it[tetheredKey] ?: false }

    /** Debug: a software model of the watch app stands in for the real link (see MockWatch). */
    val mockWatch: Flow<Boolean> = context.settingsStore.data.map { it[mockKey] ?: false }

    suspend fun setMockWatch(v: Boolean) { context.settingsStore.edit { it[mockKey] = v } }
    suspend fun setRelayEnabled(v: Boolean) { context.settingsStore.edit { it[relayKey] = v } }
    suspend fun setTethered(v: Boolean) { context.settingsStore.edit { it[tetheredKey] = v } }
}
