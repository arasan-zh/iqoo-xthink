package `in`.arasan.xthink.camera

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHidDevice
import android.bluetooth.BluetoothHidDeviceAppSdpSettings
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.content.ContextCompat
import `in`.arasan.xthink.guidance.HidKey
import `in`.arasan.xthink.guidance.HidKeymap
import java.util.concurrent.Executor
import java.util.concurrent.Executors

/**
 * The phone as a Bluetooth keyboard.
 *
 * Android's public HID-device profile lets an app register as a keyboard;
 * the Mac then sees "xThink" in its Bluetooth list, pairs, and every key
 * report lands in whatever window has focus - a terminal, an editor, a
 * Claude Code prompt. USB would have been nicer but needs the kernel's
 * gadget config, which is root-only; Bluetooth is what the hardware
 * gives an app.
 *
 * The key table and the report descriptor live in :guidance (HidKeymap),
 * under test. This class is the profile plumbing and the typing thread.
 */
class MacKeyboard(private val context: Context) {

    enum class State { NO_BLUETOOTH, NEEDS_PERMISSION, BLUETOOTH_OFF, REGISTERING, READY, CONNECTING, CONNECTED }

    @Volatile
    var state: State = State.NO_BLUETOOTH
        private set

    /** The host we are connected to, when [state] is CONNECTED. */
    @Volatile
    var hostName: String? = null
        private set

    private val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    private val adapter: BluetoothAdapter? = manager?.adapter
    private var hid: BluetoothHidDevice? = null
    private var host: BluetoothDevice? = null
    private val typist: Executor = Executors.newSingleThreadExecutor()
    private var onState: ((State) -> Unit)? = null
    private var callbackExecutor: Executor = Executor { it.run() }

    val permissions: Array<String> = arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_ADVERTISE)

    fun hasPermission(): Boolean = permissions.all {
        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
    }

    /** Register as a keyboard. [onState] is called on [executor] on every change. */
    fun start(executor: Executor, onState: (State) -> Unit) {
        this.onState = onState
        this.callbackExecutor = executor
        val a = adapter
        if (a == null) return set(State.NO_BLUETOOTH)
        if (!hasPermission()) return set(State.NEEDS_PERMISSION)
        if (!a.isEnabled) return set(State.BLUETOOTH_OFF)
        if (hid != null) return set(if (host != null) State.CONNECTED else State.READY)
        set(State.REGISTERING)
        a.getProfileProxy(
            context,
            object : BluetoothProfile.ServiceListener {
                override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                    hid = proxy as BluetoothHidDevice
                    register()
                }

                override fun onServiceDisconnected(profile: Int) {
                    hid = null
                    host = null
                    set(State.BLUETOOTH_OFF)
                }
            },
            BluetoothProfile.HID_DEVICE,
        )
    }

    private fun register() {
        val h = hid ?: return
        val sdp = BluetoothHidDeviceAppSdpSettings(
            "xThink",
            "xThink camera keyboard",
            "arasan.in",
            BluetoothHidDevice.SUBCLASS1_KEYBOARD,
            HidKeymap.KEYBOARD_DESCRIPTOR,
        )
        val ok = runCatching {
            h.registerApp(sdp, null, null, typist, object : BluetoothHidDevice.Callback() {
                override fun onAppStatusChanged(pluggedDevice: BluetoothDevice?, registered: Boolean) {
                    Log.i(TAG, "keyboard: registered=$registered plugged=${pluggedDevice?.safeName()}")
                    if (registered) {
                        set(State.READY)
                        // A host we already paired with once will take us back.
                        pluggedDevice?.let { connect(it) }
                    } else {
                        host = null
                        set(State.BLUETOOTH_OFF)
                    }
                }

                override fun onConnectionStateChanged(device: BluetoothDevice, state: Int) {
                    Log.i(TAG, "keyboard: ${device.safeName()} state=$state")
                    when (state) {
                        BluetoothProfile.STATE_CONNECTED -> {
                            host = device
                            hostName = device.safeName()
                            set(State.CONNECTED)
                        }
                        BluetoothProfile.STATE_CONNECTING -> set(State.CONNECTING)
                        else -> {
                            if (host == device) host = null
                            hostName = null
                            set(State.READY)
                        }
                    }
                }

                override fun onGetReport(device: BluetoothDevice, type: Byte, id: Byte, bufferSize: Int) {
                    runCatching { h.replyReport(device, type, id, ByteArray(8)) }
                }

                override fun onSetReport(device: BluetoothDevice, type: Byte, id: Byte, data: ByteArray) {
                    runCatching { h.reportError(device, BluetoothHidDevice.ERROR_RSP_SUCCESS) }
                }
            })
        }.getOrElse {
            Log.e(TAG, "keyboard: registerApp failed", it)
            false
        }
        if (!ok) set(State.BLUETOOTH_OFF)
    }

    /** Ask the host to connect. Works once the Mac has paired with us. */
    fun connect(device: BluetoothDevice) {
        val h = hid ?: return
        set(State.CONNECTING)
        runCatching { h.connect(device) }.onFailure { Log.w(TAG, "keyboard: connect failed", it) }
    }

    /** Try every bonded computer; the Mac is usually the only one. */
    fun connectBonded(): Boolean {
        val a = adapter ?: return false
        val bonded = runCatching { a.bondedDevices }.getOrNull() ?: return false
        val candidate = bonded.firstOrNull { d ->
            val major = runCatching { d.bluetoothClass?.majorDeviceClass }.getOrNull()
            major == android.bluetooth.BluetoothClass.Device.Major.COMPUTER
        } ?: bonded.firstOrNull() ?: return false
        Log.i(TAG, "keyboard: connecting to bonded ${candidate.safeName()}")
        connect(candidate)
        return true
    }

    /** Let the Mac find us: the system's discoverable prompt, two minutes. */
    fun discoverableIntent(): Intent =
        Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE)
            .putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 120)

    /**
     * Type [text] on the host, one press and release per character, then
     * Enter if [enter]. Returns at once; typing runs on its own thread at
     * about 60 characters a second. Characters the keyboard has no key for
     * are skipped.
     */
    fun type(text: String, enter: Boolean, onDone: ((typed: Int, dropped: String) -> Unit)? = null) {
        val h = hid
        val d = host
        if (h == null || d == null) {
            onDone?.let { cb -> callbackExecutor.execute { cb(0, text) } }
            return
        }
        typist.execute {
            val keys = HidKeymap.keysFor(text).toMutableList()
            if (enter) keys += HidKey(HidKeymap.ENTER, false)
            var typed = 0
            for (k in keys) {
                val down = runCatching { h.sendReport(d, 0, k.report()) }.getOrDefault(false)
                Thread.sleep(KEY_DOWN_MS)
                runCatching { h.sendReport(d, 0, HidKey.RELEASE) }
                Thread.sleep(KEY_UP_MS)
                if (down) typed++
            }
            val dropped = HidKeymap.unmappable(text)
            Log.i(TAG, "keyboard: typed $typed keys to ${d.safeName()}${if (dropped.isNotEmpty()) " (no key for: $dropped)" else ""}")
            onDone?.let { cb -> callbackExecutor.execute { cb(typed, dropped) } }
        }
    }

    fun stop() {
        val h = hid ?: return
        runCatching { h.unregisterApp() }
        runCatching { adapter?.closeProfileProxy(BluetoothProfile.HID_DEVICE, h) }
        hid = null
        host = null
    }

    private fun set(s: State) {
        state = s
        onState?.let { cb -> callbackExecutor.execute { cb(s) } }
    }

    private fun BluetoothDevice.safeName(): String =
        runCatching { name }.getOrNull() ?: address

    private companion object {
        const val TAG = "xThink"
        const val KEY_DOWN_MS = 8L
        const val KEY_UP_MS = 8L
    }
}
