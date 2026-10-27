package app.nextbus.garmin

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.garmin.android.connectiq.exception.InvalidStateException
import app.nextbus.R
import app.nextbus.core.SendResult
import app.nextbus.core.WatchLink
import com.garmin.android.connectiq.ConnectIQ
import com.garmin.android.connectiq.ConnectIQ.ConnectIQListener
import com.garmin.android.connectiq.ConnectIQ.IQApplicationEventListener
import com.garmin.android.connectiq.ConnectIQ.IQApplicationInfoListener
import com.garmin.android.connectiq.ConnectIQ.IQConnectType
import com.garmin.android.connectiq.ConnectIQ.IQDeviceEventListener
import com.garmin.android.connectiq.ConnectIQ.IQMessageStatus
import com.garmin.android.connectiq.ConnectIQ.IQSdkErrorStatus
import com.garmin.android.connectiq.IQApp
import com.garmin.android.connectiq.IQDevice
import com.garmin.android.connectiq.IQDevice.IQDeviceStatus
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

/**
 * The Garmin Connect IQ Mobile SDK as a [WatchLink]. Messages travel through Garmin Connect
 * (WIRELESS) or, for development, through the Connect IQ simulator over adb (TETHERED).
 * Incoming messages are only delivered while this object is registered, i.e. while the relay
 * service is alive: that is why the service is a foreground service.
 */
class GarminLink(private val context: Context, appId: String) : WatchLink {
    data class State(
        val ready: Boolean = false,
        val problem: String? = null,
        val deviceName: String? = null,
        val connected: Boolean = false,
        val appInstalled: Boolean? = null,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    /** The watch sent a message (already flattened to the integer-list transport form). */
    var onMessage: ((List<Int>) -> Unit)? = null

    /** The watch (re)connected; the phone should resend its selections. */
    var onWatchConnected: (() -> Unit)? = null

    private val app = IQApp(appId)
    private val main = Handler(Looper.getMainLooper())
    private var sdk: ConnectIQ? = null
    private val devices = LinkedHashMap<Long, IQDevice>()
    private val connected = HashSet<Long>()

    private var tethered = false

    fun start(tethered: Boolean) {
        this.tethered = tethered
        val s = ConnectIQ.getInstance(context, if (tethered) IQConnectType.TETHERED else IQConnectType.WIRELESS)
        sdk = s
        s.initialize(context, false, object : ConnectIQListener {
            // In simulator (TETHERED) mode the SDK reports ready slightly before it accepts calls.
            override fun onSdkReady() { main.postDelayed({ discover(0) }, 200) }
            override fun onInitializeError(status: IQSdkErrorStatus) {
                _state.update {
                    it.copy(
                        ready = false,
                        problem = when (status) {
                            IQSdkErrorStatus.GCM_NOT_INSTALLED -> context.getString(R.string.gcm_not_installed)
                            IQSdkErrorStatus.GCM_UPGRADE_NEEDED -> context.getString(R.string.gcm_upgrade_needed)
                            else -> context.getString(R.string.gcm_start_failed, status.toString())
                        },
                    )
                }
            }

            override fun onSdkShutDown() {
                _state.update { it.copy(ready = false) }
            }
        })
    }

    fun stop() {
        runCatching { sdk?.unregisterAllForEvents() }
        runCatching { sdk?.shutdown(context) }
        sdk = null
        main.removeCallbacksAndMessages(null)
        devices.clear()
        connected.clear()
        _state.value = State()
    }

    private fun discover(attempt: Int) {
        val s = sdk ?: return
        try {
            val known = s.knownDevices.orEmpty() + s.connectedDevices.orEmpty()
            known.forEach { devices[it.deviceIdentifier] = it }
            if (devices.isEmpty()) {
                _state.update { it.copy(ready = true, problem = context.getString(R.string.gcm_no_watch)) }
                return
            }
            for (d in devices.values) {
                s.registerForDeviceEvents(d, IQDeviceEventListener { device, status -> onStatus(device, status) })
                onStatus(d, s.getDeviceStatus(d))
                s.registerForAppEvents(d, app, IQApplicationEventListener { _, _, data, status ->
                    Log.d(TAG, "watch message: status=$status size=${data?.size}")
                    if (status == IQMessageStatus.SUCCESS) toInts(data)?.let { onMessage?.invoke(it) }
                })
                s.getApplicationInfo(app.applicationId, d, object : IQApplicationInfoListener {
                    override fun onApplicationInfoReceived(info: IQApp?) {
                        if (isActive(d)) _state.update { it.copy(appInstalled = true) }
                    }

                    override fun onApplicationNotInstalled(applicationId: String?) {
                        if (isActive(d)) _state.update { it.copy(appInstalled = false) }
                    }
                })
            }
            _state.update { it.copy(ready = true, problem = null) }
        } catch (e: InvalidStateException) {
            // Tethered: the simulator may be started after the app, so keep trying quietly.
            if (tethered) main.postDelayed({ discover(attempt + 1) }, 2000)
            else if (attempt < 5) main.postDelayed({ discover(attempt + 1) }, 500)
            else _state.update { it.copy(problem = context.getString(R.string.gcm_error, e.message ?: "")) }
        } catch (e: Exception) {
            _state.update { it.copy(problem = context.getString(R.string.gcm_error, e.message ?: "")) }
        }
    }

    private fun isActive(d: IQDevice) = activeDevice()?.deviceIdentifier == d.deviceIdentifier

    private fun onStatus(device: IQDevice, status: IQDeviceStatus) {
        val wasConnected = connected.isNotEmpty()
        if (status == IQDeviceStatus.CONNECTED) connected += device.deviceIdentifier else connected -= device.deviceIdentifier
        val active = activeDevice()
        _state.update { it.copy(deviceName = active?.friendlyName, connected = connected.isNotEmpty()) }
        if (!wasConnected && connected.isNotEmpty()) onWatchConnected?.invoke()
    }

    /** The connected watch if any, otherwise the first paired one. */
    private fun activeDevice(): IQDevice? =
        devices.values.firstOrNull { it.deviceIdentifier in connected } ?: devices.values.firstOrNull()

    // The tethered (simulator) strategy writes to a socket on the calling thread, which Android
    // forbids on the main thread; the Garmin Connect path is a binder call and does not mind.
    override suspend fun send(message: List<Int>): SendResult = withContext(if (tethered) Dispatchers.IO else Dispatchers.Main) {
        val s = sdk
        val d = activeDevice()
        if (s == null || d == null || d.deviceIdentifier !in connected) {
            Log.w(TAG, "send: no connected watch (sdk=${s != null}, device=${d?.friendlyName}, connected=$connected)")
            return@withContext SendResult.FAILED
        }
        suspendCancellableCoroutine { cont ->
            try {
                s.sendMessage(d, app, message) { _, _, status ->
                    if (status != IQMessageStatus.SUCCESS) Log.w(TAG, "send: status $status")
                    if (cont.isActive) {
                        cont.resume(
                            when (status) {
                                IQMessageStatus.SUCCESS -> SendResult.OK
                                IQMessageStatus.FAILURE_MESSAGE_TOO_LARGE -> SendResult.TOO_LARGE
                                else -> SendResult.FAILED
                            },
                        )
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "send failed", e)
                if (cont.isActive) cont.resume(SendResult.FAILED)
            }
        }
    }

    companion object {
        private const val TAG = "GarminLink"

        /**
         * The SDK hands a Monkey C Array over as a List. Some versions wrap the payload in one more
         * list; unwrap that. Returns null if any element is not a number.
         */
        fun toInts(data: List<Any?>?): List<Int>? {
            var list: List<Any?> = data ?: return null
            if (list.size == 1 && list[0] is List<*>) {
                @Suppress("UNCHECKED_CAST")
                list = list[0] as List<Any?>
            }
            return list.map { (it as? Number)?.toInt() ?: return null }
        }
    }
}
