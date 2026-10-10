package com.infraxcoders.bmpcc.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import com.infraxcoders.bmpcc.core.Bmd
import java.util.UUID

/**
 * Bluetooth link to a Blackmagic camera: scan, connect, pair (the camera shows a 6-digit PIN that Android asks
 * for), then send commands and receive the camera's settings, record state and timecode.
 * All state is Compose state, so screens update by themselves. Callbacks are moved to the main thread.
 */
@SuppressLint("MissingPermission")
object CameraLink {
    enum class Phase(val label: String) {
        IDLE("Not connected"), SCANNING("Searching for cameras…"), CONNECTING("Connecting…"),
        PAIRING("Pairing: enter the PIN shown on the camera"), SETTING_UP("Setting up…"), CONNECTED("Connected"),
    }

    data class Found(val device: BluetoothDevice, val name: String, val rssi: Int)

    var phase by mutableStateOf(Phase.IDLE); private set
    var error by mutableStateOf<String?>(null); private set
    var cameraName by mutableStateOf<String?>(null); private set
    val found = mutableStateListOf<Found>()

    // What the camera reports.
    var recording by mutableStateOf(false); private set
    var timecode by mutableStateOf<String?>(null); private set
    var iso by mutableStateOf<Int?>(null); private set
    var shutterAngle by mutableStateOf<Double?>(null); private set
    var shutterSpeed by mutableStateOf<Int?>(null); private set
    var whiteBalance by mutableStateOf<Int?>(null); private set
    var tint by mutableStateOf<Int?>(null); private set
    var fNumber by mutableStateOf<Double?>(null); private set
    var focus by mutableStateOf<Double?>(null); private set
    var ndStops by mutableStateOf<Double?>(null); private set
    var fps by mutableStateOf<Int?>(null); private set
    var resolution by mutableStateOf<String?>(null); private set
    var battery by mutableStateOf<Int?>(null); private set
    var lut by mutableStateOf<Bmd.Lut?>(null); private set
    /** Last recording format the camera reported, so a new frame rate can be sent with the same size and flags. */
    private var format: Bmd.Update.Format? = null
    var status by mutableStateOf<Bmd.Status?>(null); private set

    val isConnected: Boolean get() = phase == Phase.CONNECTED

    private lateinit var app: Context
    private val main = Handler(Looper.getMainLooper())
    private var gatt: BluetoothGatt? = null
    private var device: BluetoothDevice? = null
    private val queue = ArrayDeque<() -> Boolean>()
    private var busy = false
    private var bondReceiverRegistered = false

    private val uuidService = UUID.fromString(Bmd.SERVICE)
    private val uuidOut = UUID.fromString(Bmd.OUTGOING_CONTROL)
    private val uuidIn = UUID.fromString(Bmd.INCOMING_CONTROL)
    private val uuidTc = UUID.fromString(Bmd.TIMECODE)
    private val uuidStatus = UUID.fromString(Bmd.CAMERA_STATUS)
    private val uuidCccd = UUID.fromString(Bmd.CCCD)

    fun init(context: Context) { if (!::app.isInitialized) app = context.applicationContext }

    private val adapter: BluetoothAdapter? get() = (app.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
    val bluetoothOn: Boolean get() = adapter?.isEnabled == true
    val hasBluetooth: Boolean get() = adapter != null

    // ── Scanning ──

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            main.post {
                val name = result.scanRecord?.deviceName ?: runCatching { result.device.name }.getOrNull() ?: "Blackmagic camera"
                val i = found.indexOfFirst { it.device.address == result.device.address }
                val f = Found(result.device, name, result.rssi)
                if (i >= 0) found[i] = f else found.add(f)
            }
        }
        override fun onScanFailed(errorCode: Int) { main.post { error = "Bluetooth search failed (code $errorCode)."; phase = Phase.IDLE } }
    }

    private var scanToken = 0

    fun startScan() {
        // Don't interrupt a connection or pairing in progress.
        if (phase != Phase.IDLE && phase != Phase.SCANNING) return
        error = null
        val a = adapter ?: run { error = "This phone has no Bluetooth."; return }
        if (!a.isEnabled) { error = "Turn Bluetooth on."; return }
        val scanner = a.bluetoothLeScanner ?: run { error = "Bluetooth isn't ready."; return }
        found.clear()
        // Paired cameras first, so a known camera can be connected without waiting.
        runCatching {
            a.bondedDevices.filter { d -> val n = d.name ?: ""; listOf("Pocket", "Blackmagic", "URSA", "Cinema", "PYXIS").any { n.contains(it, true) } }
                .forEach { found.add(Found(it, (it.name ?: "Camera") + " (paired)", 0)) }
        }
        val filter = ScanFilter.Builder().setServiceUuid(ParcelUuid(uuidService)).build()
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        runCatching { scanner.startScan(listOf(filter), settings, scanCallback) }
            .onFailure { error = "Couldn't search: ${it.message}"; return }
        phase = Phase.SCANNING
        val token = ++scanToken
        main.postDelayed({ if (phase == Phase.SCANNING && token == scanToken) stopScan() }, 15_000)
    }

    fun stopScan() {
        runCatching { adapter?.bluetoothLeScanner?.stopScan(scanCallback) }
        if (phase == Phase.SCANNING) phase = Phase.IDLE
    }

    // ── Connecting ──

    fun connect(d: BluetoothDevice) {
        stopScan()
        disconnectGatt()
        error = null
        device = d
        cameraName = runCatching { d.name }.getOrNull() ?: "Camera"
        phase = Phase.CONNECTING
        registerBondReceiver()
        gatt = d.connectGatt(app, false, callback, BluetoothDevice.TRANSPORT_LE)
    }

    fun disconnect() {
        disconnectGatt()
        phase = Phase.IDLE
        recording = false
        timecode = null
    }

    private fun disconnectGatt() {
        queue.clear(); busy = false
        gatt?.let { runCatching { it.disconnect() }; runCatching { it.close() } }
        gatt = null
        format = null; battery = null; lut = null
    }

    /** The camera being connected or connected to. */
    val deviceAddress: String? get() = device?.address

    private fun registerBondReceiver() {
        if (bondReceiverRegistered) return
        bondReceiverRegistered = true
        ContextCompat.registerReceiver(app, object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val d: BluetoothDevice? = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                else @Suppress("DEPRECATION") intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                if (d?.address != device?.address) return
                when (intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.BOND_NONE)) {
                    BluetoothDevice.BOND_BONDED -> if (phase == Phase.PAIRING) setUp()
                    BluetoothDevice.BOND_NONE -> if (phase == Phase.PAIRING) {
                        error = "Pairing didn't finish. Check the PIN on the camera and try again."
                        disconnect()
                    }
                }
            }
        }, IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED), ContextCompat.RECEIVER_EXPORTED)
    }

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, statusCode: Int, newState: Int) = main.post {
            if (g != gatt) return@post
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> { phase = Phase.SETTING_UP; if (!g.requestMtu(256)) g.discoverServices() }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    if (phase != Phase.IDLE) error = if (phase == Phase.CONNECTED) "The camera disconnected." else "Couldn't connect (code $statusCode). Is the camera's Bluetooth on?"
                    disconnectGatt(); phase = Phase.IDLE; recording = false
                }
            }
        }.let { }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, statusCode: Int) = main.post { g.discoverServices() }.let { }

        override fun onServicesDiscovered(g: BluetoothGatt, statusCode: Int) = main.post {
            if (g.getService(uuidService) == null) {
                error = "This device isn't a Blackmagic camera (no camera control service)."
                disconnect(); return@post
            }
            val d = device
            if (d != null && d.bondState != BluetoothDevice.BOND_BONDED) {
                phase = Phase.PAIRING
                d.createBond()          // Android shows its pairing dialog: type the PIN the camera displays.
            } else setUp()
        }.let { }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, statusCode: Int) = main.post { next() }.let { }
        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, statusCode: Int) = main.post { next() }.let { }

        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) = main.post { received(c.uuid, value) }.let { }
        @Deprecated("Deprecated in Java")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            if (Build.VERSION.SDK_INT >= 33) return
            @Suppress("DEPRECATION") val v = c.value ?: return
            main.post { received(c.uuid, v) }
        }
    }

    /** Subscribe to the camera's notifications and power it on. */
    private fun setUp() {
        val g = gatt ?: return
        val s = g.getService(uuidService) ?: return
        phase = Phase.SETTING_UP
        listOf(uuidStatus, uuidIn, uuidTc).forEach { u -> s.getCharacteristic(u)?.let { enqueue { subscribe(g, it) } } }
        s.getCharacteristic(uuidStatus)?.let { c -> enqueue { write(g, c, byteArrayOf(0x01)) } }
        enqueue { phase = Phase.CONNECTED; error = null; false }
        next()
    }

    private fun subscribe(g: BluetoothGatt, c: BluetoothGattCharacteristic): Boolean {
        g.setCharacteristicNotification(c, true)
        val d = c.getDescriptor(uuidCccd) ?: return false
        val v = if (c.properties and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0) BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
        else BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        return if (Build.VERSION.SDK_INT >= 33) g.writeDescriptor(d, v) == 0 /* BluetoothStatusCodes.SUCCESS */
        else { @Suppress("DEPRECATION") run { d.value = v; g.writeDescriptor(d) } }
    }

    private fun write(g: BluetoothGatt, c: BluetoothGattCharacteristic, bytes: ByteArray): Boolean =
        if (Build.VERSION.SDK_INT >= 33) g.writeCharacteristic(c, bytes, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) == 0
        else @Suppress("DEPRECATION") run {
            c.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            c.value = bytes
            g.writeCharacteristic(c)
        }

    /** Each operation returns true if it started an async GATT operation (the callback runs the next one). */
    private fun enqueue(op: () -> Boolean) { queue.addLast(op) }

    private fun next() {
        busy = false
        while (queue.isNotEmpty()) {
            val op = queue.removeFirst()
            if (op()) { busy = true; return }
        }
    }

    // ── Commands ──

    fun send(cmd: Bmd.Command) {
        if (!isConnected) return
        val g = gatt ?: return
        val c = g.getService(uuidService)?.getCharacteristic(uuidOut) ?: return
        val bytes = Bmd.encode(cmd)
        enqueue { write(g, c, bytes) }
        if (!busy) next()
    }

    fun record(on: Boolean) = send(Bmd.record(on))
    fun sendIso(v: Int) = send(Bmd.iso(v))
    fun sendShutterAngle(v: Double) = send(Bmd.shutterAngle(v))
    fun sendWhiteBalance(k: Int) = send(Bmd.whiteBalance(k, tint ?: 0))
    fun autoWhiteBalance() = send(Bmd.autoWhiteBalance())
    fun sendAperture(f: Double) = send(Bmd.aperture(f))
    fun autoAperture() = send(Bmd.autoAperture())
    fun sendFocus(v: Double) = send(Bmd.focus(v))
    fun autoFocus() = send(Bmd.autoFocus())
    fun sendNd(stops: Double) = send(Bmd.ndStops(stops))
    fun sendLut(l: Bmd.Lut) { lut = l; send(Bmd.displayLut(l)) }
    /** Changes the project frame rate; needs the camera's current format first. Returns false when not known yet. */
    fun sendFps(newFps: Int): Boolean {
        val f = format ?: return false
        send(Bmd.recordingFormat(newFps, newFps, f.width, f.height, f.flags))
        return true
    }
    val canSetFps: Boolean get() = format != null

    // ── Incoming ──

    private fun received(uuid: UUID, value: ByteArray) {
        when (uuid) {
            uuidTc -> timecode = Bmd.timecode(value)
            uuidStatus -> status = value.firstOrNull()?.let { Bmd.Status(it.toInt() and 0xFF) }
            uuidIn -> Bmd.parse(value).forEach { cmd ->
                when (val u = Bmd.decode(cmd)) {
                    is Bmd.Update.Iso -> iso = u.iso
                    is Bmd.Update.ShutterAngle -> shutterAngle = u.degrees
                    is Bmd.Update.ShutterSpeed -> shutterSpeed = u.denominator
                    is Bmd.Update.WhiteBalance -> { whiteBalance = u.kelvin; tint = u.tint }
                    is Bmd.Update.Aperture -> fNumber = u.fNumber
                    is Bmd.Update.Focus -> focus = u.position
                    is Bmd.Update.Nd -> ndStops = u.stops
                    is Bmd.Update.Recording -> recording = u.recording
                    is Bmd.Update.Format -> { format = u; fps = u.fps; resolution = "${u.width} × ${u.height}" }
                    is Bmd.Update.DisplayLut -> lut = if (u.enabled) u.lut else Bmd.Lut.NONE
                    is Bmd.Update.Battery -> battery = u.percent
                    is Bmd.Update.Zoom, null -> {}
                }
            }
        }
    }
}
