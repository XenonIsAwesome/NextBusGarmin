package app.nextbus.garmin

import android.content.Context
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
    private var sdk: ConnectIQ? = null
    private val devices = LinkedHashMap<Long, IQDevice>()
    private val connected = HashSet<Long>()

    fun start(tethered: Boolean) {
        val s = ConnectIQ.getInstance(context, if (tethered) IQConnectType.TETHERED else IQConnectType.WIRELESS)
        sdk = s
        s.initialize(context, false, object : ConnectIQListener {
            override fun onSdkReady() = discover()
            override fun onInitializeError(status: IQSdkErrorStatus) {
                _state.update {
                    it.copy(
                        ready = false,
                        problem = when (status) {
                            IQSdkErrorStatus.GCM_NOT_INSTALLED -> "Garmin Connect is not installed on this phone"
                            IQSdkErrorStatus.GCM_UPGRADE_NEEDED -> "Garmin Connect needs to be updated"
                            else -> "Could not start the Garmin Connect IQ service ($status)"
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
        devices.clear()
        connected.clear()
        _state.value = State()
    }

    private fun discover() {
        val s = sdk ?: return
        try {
            val known = s.knownDevices.orEmpty() + s.connectedDevices.orEmpty()
            known.forEach { devices[it.deviceIdentifier] = it }
            if (devices.isEmpty()) {
                _state.update { it.copy(ready = true, problem = "No Garmin watch is paired in Garmin Connect") }
                return
            }
            for (d in devices.values) {
                s.registerForDeviceEvents(d, IQDeviceEventListener { device, status -> onStatus(device, status) })
                onStatus(d, s.getDeviceStatus(d))
                s.registerForAppEvents(d, app, IQApplicationEventListener { _, _, data, status ->
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
        } catch (e: Exception) {
            _state.update { it.copy(problem = "Garmin Connect IQ error: ${e.message}") }
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

    override suspend fun send(message: List<Int>): SendResult = withContext(Dispatchers.Main) {
        val s = sdk
        val d = activeDevice()
        if (s == null || d == null || d.deviceIdentifier !in connected) return@withContext SendResult.FAILED
        suspendCancellableCoroutine { cont ->
            try {
                s.sendMessage(d, app, message) { _, _, status ->
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
                if (cont.isActive) cont.resume(SendResult.FAILED)
            }
        }
    }

    companion object {
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
