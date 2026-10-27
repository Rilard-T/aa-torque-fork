package com.aatorque.obd

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.os.Handler
import android.os.Looper

/** Поиск BLE-адаптеров рядом (только устройства с именем). Обратные вызовы приходят в главном потоке. */
class BleScanner {
    data class Found(val name: String, val address: String, val rssi: Int)

    private val handler = Handler(Looper.getMainLooper())
    private val found = LinkedHashMap<String, Found>()
    private var callback: ScanCallback? = null
    private var finish: Runnable? = null

    private fun sorted(): List<Found> = found.values.sortedByDescending { it.rssi }

    @SuppressLint("MissingPermission")
    fun start(durationMs: Long, onUpdate: (List<Found>) -> Unit, onDone: (List<Found>) -> Unit) {
        stop()
        val scanner = BluetoothAdapter.getDefaultAdapter()?.bluetoothLeScanner
        if (scanner == null) { onDone(emptyList()); return }
        found.clear()
        val cb = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val name = result.scanRecord?.deviceName ?: runCatching { result.device.name }.getOrNull()
                if (name.isNullOrBlank()) return
                found[result.device.address] = Found(name, result.device.address, result.rssi)
                onUpdate(sorted())
            }

            override fun onScanFailed(errorCode: Int) {
                stop()
                onDone(sorted())
            }
        }
        callback = cb
        try {
            scanner.startScan(cb)
        } catch (e: Exception) {
            callback = null
            onDone(emptyList())
            return
        }
        val r = Runnable { stop(); onDone(sorted()) }
        finish = r
        handler.postDelayed(r, durationMs)
    }

    @SuppressLint("MissingPermission")
    fun stop() {
        finish?.let { handler.removeCallbacks(it) }
        finish = null
        callback?.let { cb ->
            runCatching { BluetoothAdapter.getDefaultAdapter()?.bluetoothLeScanner?.stopScan(cb) }
        }
        callback = null
    }
}
