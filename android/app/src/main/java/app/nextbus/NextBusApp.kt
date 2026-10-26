package app.nextbus

import android.app.Application
import app.nextbus.data.IndexMaintenance
import app.nextbus.data.IndexRepository
import app.nextbus.data.SelectionRepository
import app.nextbus.data.SettingsRepository

/** Process-wide singletons (the relay service and the UI share them). */
class AppGraph(app: Application) {
    val selections = SelectionRepository(app)
    val settings = SettingsRepository(app)
    val index = IndexRepository(app)
}

class NextBusApp : Application() {
    lateinit var graph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        graph = AppGraph(this)
        IndexMaintenance.schedule(this)
    }
}
