package com.stsmn.monitor.data

import android.Manifest
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
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import com.stsmn.monitor.domain.BleLineAssembler
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

enum class BleState { OFF, NO_PERMISSION, UNSUPPORTED, IDLE, SCANNING, CONNECTING, CONNECTED }

/** Required runtime permissions (differ before/after Android 12). */
object BlePermissions {
    fun required(): Array<String> =
        if (Build.VERSION.SDK_INT >= 31) arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)

    fun granted(context: Context): Boolean =
        required().all { context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }
}

/**
 * Bluetooth Low Energy link to the ESP32 (Nordic UART service). Viewer only: it receives the same JSON the Wi-Fi API serves
 * and can write one command (the SMS number, protected by the device password). Reconnects by itself while started.
 * All Android callbacks arrive on binder threads; everything is handed over through thread-safe flows.
 */
@SuppressLint("MissingPermission")
class BleClient(context: Context) {
    private val app = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())
    private val adapter: BluetoothAdapter? = app.getSystemService(BluetoothManager::class.java)?.adapter

    private val _state = MutableStateFlow(BleState.IDLE)
    val state: StateFlow<BleState> = _state.asStateFlow()
    private val _lines = MutableSharedFlow<String>(extraBufferCapacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val lines: SharedFlow<String> = _lines.asSharedFlow()
    @Volatile var deviceName: String? = null; private set

    private var gatt: BluetoothGatt? = null
    private var rx: BluetoothGattCharacteristic? = null
    private var wanted = false
    private var scanning = false
    private val assembler = BleLineAssembler()

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            handler.post {
                if (!wanted || gatt != null) return@post
                stopScan()
                connect(result.device)
            }
        }
        override fun onScanFailed(errorCode: Int) {
            handler.post { scanning = false; if (wanted) { _state.value = BleState.IDLE; scheduleRetry() } }
        }
    }

    fun start() { handler.post { wanted = true; attempt() } }

    fun stop() {
        handler.post {
            wanted = false
            handler.removeCallbacksAndMessages(null)
            stopScan()
            closeGatt()
            _state.value = BleState.IDLE
        }
    }

    private fun attempt() {
        if (!wanted || gatt != null || scanning) return
        val a = adapter
        if (a == null || !app.packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)) { _state.value = BleState.UNSUPPORTED; return }
        if (!BlePermissions.granted(app)) { _state.value = BleState.NO_PERMISSION; scheduleRetry(); return }
        if (!a.isEnabled) { _state.value = BleState.OFF; scheduleRetry(); return }
        val scanner = a.bluetoothLeScanner ?: run { _state.value = BleState.OFF; scheduleRetry(); return }
        val filter = ScanFilter.Builder().setServiceUuid(ParcelUuid(SERVICE_UUID)).build()
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        try {
            scanner.startScan(listOf(filter), settings, scanCallback)
            scanning = true; _state.value = BleState.SCANNING
            handler.postDelayed({ if (scanning && gatt == null) { stopScan(); _state.value = BleState.IDLE; scheduleRetry() } }, SCAN_TIMEOUT_MS)
        } catch (e: Exception) { _state.value = BleState.IDLE; scheduleRetry() }
    }

    private fun scheduleRetry() {
        if (!wanted) return
        handler.postDelayed({ attempt() }, RETRY_MS)
    }

    private fun stopScan() {
        if (!scanning) return
        scanning = false
        try { adapter?.bluetoothLeScanner?.stopScan(scanCallback) } catch (_: Exception) {}
    }

    private fun connect(device: BluetoothDevice) {
        _state.value = BleState.CONNECTING
        deviceName = try { device.name } catch (_: Exception) { null }
        assembler.reset()
        gatt = device.connectGatt(app, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
    }

    private fun closeGatt() {
        rx = null
        gatt?.let { try { it.disconnect(); it.close() } catch (_: Exception) {} }
        gatt = null
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                g.requestMtu(185)          // a bigger MTU only speeds things up; the stream also works with 20 bytes
                handler.post { g.discoverServices() }
            } else {
                handler.post {
                    if (gatt === g) closeGatt() else try { g.close() } catch (_: Exception) {}
                    if (wanted) { _state.value = BleState.IDLE; scheduleRetry() }
                }
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            val svc = g.getService(SERVICE_UUID)
            val tx = svc?.getCharacteristic(TX_UUID)
            if (status != BluetoothGatt.GATT_SUCCESS || svc == null || tx == null) {
                handler.post { closeGatt(); if (wanted) { _state.value = BleState.IDLE; scheduleRetry() } }
                return
            }
            rx = svc.getCharacteristic(RX_UUID)
            g.setCharacteristicNotification(tx, true)
            val cccd = tx.getDescriptor(CCCD_UUID)
            if (cccd != null) {
                @Suppress("DEPRECATION")
                cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                @Suppress("DEPRECATION")
                g.writeDescriptor(cccd)
            }
            _state.value = BleState.CONNECTED
        }

        // Android 13+ calls this one; older versions call the deprecated 2-argument one below.
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) {
            handleChunk(value)
        }

        @Deprecated("Used before Android 13")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            @Suppress("DEPRECATION")
            c.value?.let { handleChunk(it) }
        }
    }

    private fun handleChunk(bytes: ByteArray) {
        for (line in assembler.feed(String(bytes, Charsets.UTF_8))) _lines.tryEmit(line)
    }

    /** Writes a command to the ESP32 (20-byte pieces, works with the default MTU). False when not connected. Blocking: call off the main thread. */
    fun write(text: String): Boolean {
        val g = gatt ?: return false
        val c = rx ?: return false
        val data = text.toByteArray(Charsets.UTF_8)
        var ok = true
        var off = 0
        while (off < data.size && ok) {
            val part = data.copyOfRange(off, minOf(off + 20, data.size))
            @Suppress("DEPRECATION")
            run {
                c.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                c.value = part
                ok = g.writeCharacteristic(c)
            }
            off += 20
            if (ok && off < data.size) Thread.sleep(80)   // let the previous write finish
        }
        return ok
    }

    companion object {
        val SERVICE_UUID: UUID = UUID.fromString("6e400001-b5a3-f393-e0a9-e50e24dcca9e")
        val RX_UUID: UUID = UUID.fromString("6e400002-b5a3-f393-e0a9-e50e24dcca9e")   // phone -> ESP32
        val TX_UUID: UUID = UUID.fromString("6e400003-b5a3-f393-e0a9-e50e24dcca9e")   // ESP32 -> phone
        val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        const val SCAN_TIMEOUT_MS = 15_000L
        const val RETRY_MS = 3_000L
    }
}
