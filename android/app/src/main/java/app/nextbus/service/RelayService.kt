package app.nextbus.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import app.nextbus.BuildConfig
import app.nextbus.MainActivity
import app.nextbus.NextBusApp
import app.nextbus.R
import app.nextbus.RelayStatusStore
import app.nextbus.core.ArrivalCoordinator
import app.nextbus.core.CurlbusProvider
import app.nextbus.MockWatchHub
import app.nextbus.core.MockWatch
import app.nextbus.core.WatchLink
import app.nextbus.core.RelayEngine
import app.nextbus.core.RelayEvent
import app.nextbus.core.SavedSelection
import app.nextbus.garmin.GarminLink
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Keeps the phone app alive and registered with the Connect IQ SDK so watch requests are
 * answered while the watch app is open. It owns the one [ArrivalCoordinator], so every trigger
 * shares the same curlbus rate limit. Visible as a low-priority notification.
 */
class RelayService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var link: GarminLink? = null
    private var started = false

    @Volatile private var cachedSelections: List<SavedSelection> = emptyList()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            scope.launch { (application as NextBusApp).graph.settings.setRelayEnabled(false) }
            stopSelf()
            return START_NOT_STICKY
        }
        ServiceCompat.startForeground(
            this, NOTIFICATION_ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
        )
        if (!started) {
            started = true
            startRelay()
        }
        return START_STICKY
    }

    @OptIn(FlowPreview::class)
    private fun startRelay() {
        val graph = (application as NextBusApp).graph
        val coordinator = ArrivalCoordinator(CurlbusProvider(), { System.currentTimeMillis() / 1000 })
        val garmin = GarminLink(this, BuildConfig.WATCH_APP_ID).also { link = it }
        var mock: MockWatch? = null
        val engine = RelayEngine(
            coordinator = coordinator,
            selections = { cachedSelections },
            link = object : WatchLink {
                override suspend fun send(message: List<Int>) =
                    mock?.send(message) ?: garmin.send(message)
            },
            now = { System.currentTimeMillis() / 1000 },
            appVersion = BuildConfig.VERSION_NAME,
            onEvent = ::onEvent,
        )
        RelayStatusStore.update { it.copy(running = true) }

        garmin.onMessage = { message -> scope.launch { engine.onWatchMessage(message) } }
        garmin.onWatchConnected = { scope.launch { engine.sendHello(); engine.pushSelections() } }

        scope.launch {
            garmin.state.collect { s ->
                if (mock != null) return@collect
                RelayStatusStore.update {
                    it.copy(
                        linkProblem = s.problem, watchName = s.deviceName,
                        watchConnected = s.connected, watchAppInstalled = s.appInstalled,
                    )
                }
            }
        }
        // Selections edited in the app go to the watch (and refresh its arrivals).
        scope.launch {
            cachedSelections = graph.selections.current()
            graph.selections.selections.distinctUntilChanged().drop(1).debounce(400).collect {
                cachedSelections = it
                engine.onSelectionsChanged()
            }
        }
        scope.launch {
            if (BuildConfig.DEBUG && graph.settings.mockWatch.first()) {
                val w = MockWatch({ System.currentTimeMillis() / 1000 }, { engine.onWatchMessage(it) }).also { mock = it }
                runMockWatch(w)
                return@launch
            }
            val tethered = graph.settings.tethered.first()
            kotlinx.coroutines.withContext(Dispatchers.Main) { garmin.start(tethered) }
        }
    }

    /** Debug: drive the modelled watch (open/close/refresh buttons, 40 s timer) and publish what it shows. */
    private suspend fun runMockWatch(w: MockWatch) = coroutineScope {
        RelayStatusStore.update { it.copy(watchName = "Mock watch", watchConnected = true, watchAppInstalled = true) }
        fun publish() = MockWatchHub.publish(MockWatchHub.State(w.open, w.view(), w.generatedAt))
        launch {
            MockWatchHub.commands.collect {
                when (it) {
                    MockWatchHub.Command.OPEN -> { w.openApp(); w.tick() }
                    MockWatchHub.Command.CLOSE -> w.closeApp()
                    MockWatchHub.Command.REFRESH -> w.refresh()
                }
                publish()
            }
        }
        while (true) {
            w.tick()
            publish()
            delay(1000)
        }
    }

    private fun onEvent(event: RelayEvent) {
        val nowMs = System.currentTimeMillis()
        RelayStatusStore.update { s ->
            when (event) {
                is RelayEvent.WatchMessage -> s.copy(lastWatchMessageAt = nowMs)
                is RelayEvent.SelectionsSent -> s.copy(lastSyncAt = nowMs)
                is RelayEvent.ArrivalsSent -> s.copy(
                    lastFetchAt = event.result.lastGoodAt?.let { it * 1000 } ?: s.lastFetchAt,
                    lastError = event.result.lastError?.let { "${it.kind.name.lowercase().replace('_', ' ')}: ${it.message}" },
                    versionMismatch = null,
                )
                is RelayEvent.VersionMismatch -> s.copy(versionMismatch = event.watchVersion)
                is RelayEvent.Problem -> s.copy(lastError = event.message)
            }
        }
    }

    private fun notification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, getString(R.string.relay_channel), NotificationManager.IMPORTANCE_LOW),
        )
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(
            this, 1, Intent(this, RelayService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle(getString(R.string.relay_notification_title))
            .setContentText(getString(R.string.relay_notification_text))
            .setContentIntent(open)
            .addAction(0, getString(R.string.relay_stop), stop)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    override fun onDestroy() {
        scope.cancel()
        link?.stop()
        MockWatchHub.publish(MockWatchHub.State())
        RelayStatusStore.reset()
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "relay"
        private const val NOTIFICATION_ID = 1
        private const val ACTION_STOP = "app.nextbus.STOP_RELAY"

        fun start(context: Context) =
            ContextCompat.startForegroundService(context, Intent(context, RelayService::class.java))

        fun stop(context: Context) {
            context.stopService(Intent(context, RelayService::class.java))
        }
    }
}
