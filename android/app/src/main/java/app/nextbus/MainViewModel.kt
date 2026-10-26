package app.nextbus

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.nextbus.core.SavedSelection
import app.nextbus.data.AddResult
import app.nextbus.data.IndexInfo
import app.nextbus.data.IndexMaintenance
import app.nextbus.data.IndexUpdateResult
import app.nextbus.data.LineOption
import app.nextbus.data.StopHit
import app.nextbus.service.RelayService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainViewModel(private val app: Application) : AndroidViewModel(app) {
    private val graph = (app as NextBusApp).graph

    val selections: StateFlow<List<SavedSelection>> =
        graph.selections.selections.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val relayEnabled: StateFlow<Boolean> = graph.settings.relayEnabled.stateIn(viewModelScope, SharingStarted.Eagerly, true)
    val tethered: StateFlow<Boolean> = graph.settings.tethered.stateIn(viewModelScope, SharingStarted.Eagerly, false)
    val mockWatch: StateFlow<Boolean> = graph.settings.mockWatch.stateIn(viewModelScope, SharingStarted.Eagerly, false)
    val status: StateFlow<RelayStatus> = RelayStatusStore.status

    val indexInfo = MutableStateFlow<IndexInfo?>(null)
    val indexMessage = MutableStateFlow<IndexUpdateResult?>(null)
    val indexChecking = MutableStateFlow(false)

    init {
        loadIndexInfo()
    }

    private fun loadIndexInfo() = viewModelScope.launch {
        indexInfo.value = withContext(Dispatchers.IO) { runCatching { graph.index.info() }.getOrNull() }
    }

    fun setRelay(on: Boolean) = viewModelScope.launch {
        graph.settings.setRelayEnabled(on)
        if (on) RelayService.start(app) else RelayService.stop(app)
    }

    fun setTethered(on: Boolean) = viewModelScope.launch {
        graph.settings.setTethered(on)
        if (relayEnabled.value) {
            RelayService.stop(app)
            RelayService.start(app)
        }
    }

    fun setMockWatch(on: Boolean) = viewModelScope.launch {
        graph.settings.setMockWatch(on)
        if (relayEnabled.value) {
            RelayService.stop(app)
            RelayService.start(app)
        }
    }

    suspend fun searchStops(query: String): List<StopHit> = withContext(Dispatchers.IO) { graph.index.searchStops(query) }

    suspend fun nearbyStops(latE6: Int, lonE6: Int): List<StopHit> = withContext(Dispatchers.IO) { graph.index.nearbyStops(latE6, lonE6) }

    suspend fun linesAt(stop: StopHit): List<LineOption> = withContext(Dispatchers.IO) { graph.index.linesAt(stop.code) }

    suspend fun add(stop: StopHit, line: LineOption): AddResult = graph.selections.add(stop, line)

    fun remove(id: Int) = viewModelScope.launch { graph.selections.remove(id) }
    fun move(id: Int, delta: Int) = viewModelScope.launch { graph.selections.move(id, delta) }
    fun renameStop(id: Int, name: String) = viewModelScope.launch { graph.selections.renameStop(id, name) }

    fun checkIndexUpdate() = viewModelScope.launch {
        indexChecking.value = true
        indexMessage.value = withContext(Dispatchers.IO) { IndexMaintenance.checkAndInstall(graph.index) }
        indexChecking.value = false
        loadIndexInfo()
    }
}
