package com.aatorque.obd

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.content.Context
import android.os.Build
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * ELM327 по Bluetooth LE (GATT). Подходят типовые адаптеры: службы FFF0, FFE0, 18F0 и Nordic UART;
 * для остальных каналы обмена определяются автоматически (одна характеристика с уведомлениями, одна для записи).
 */
@SuppressLint("MissingPermission")
class BleLink(context: Context, private val device: BluetoothDevice) : ObdLink {
    private val appContext = context.applicationContext
    private var gatt: BluetoothGatt? = null
    private var notifyChar: BluetoothGattCharacteristic? = null
    private var writeChar: BluetoothGattCharacteristic? = null
    private var writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
    @Volatile private var connected = false
    @Volatile private var servicesOk = false
    @Volatile private var mtu = 23
    @Volatile private var connLatch = CountDownLatch(1)
    @Volatile private var svcLatch = CountDownLatch(1)
    @Volatile private var mtuLatch = CountDownLatch(1)
    @Volatile private var descLatch = CountDownLatch(1)
    @Volatile private var writeLatch: CountDownLatch? = null
    @Volatile private var writeOk = true
    private val rx = LinkedBlockingQueue<ByteArray>()

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            connected = newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS
            connLatch.countDown()
            if (!connected) {                       // разрыв: разбудим все ожидающие шаги
                svcLatch.countDown(); mtuLatch.countDown(); descLatch.countDown()
                writeLatch?.countDown()
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            servicesOk = status == BluetoothGatt.GATT_SUCCESS
            svcLatch.countDown()
        }

        override fun onMtuChanged(g: BluetoothGatt, newMtu: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) mtu = newMtu
            mtuLatch.countDown()
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
            descLatch.countDown()
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            writeOk = status == BluetoothGatt.GATT_SUCCESS
            writeLatch?.countDown()
        }

        // Android до 13
        @Deprecated("Используется на Android до 13")
        @Suppress("OVERRIDE_DEPRECATION", "DEPRECATION")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            c.value?.let { rx.offer(it.copyOf()) }
        }

        // Android 13 и новее
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) {
            rx.offer(value.copyOf())
        }
    }

    override fun open() {
        close()
        rx.clear()
        connLatch = CountDownLatch(1); svcLatch = CountDownLatch(1)
        mtuLatch = CountDownLatch(1); descLatch = CountDownLatch(1)
        connected = false; servicesOk = false; mtu = 23

        val g = device.connectGatt(appContext, false, callback, BluetoothDevice.TRANSPORT_LE)
            ?: throw IOException("BLE: не удалось начать подключение")
        gatt = g
        if (!connLatch.await(12, TimeUnit.SECONDS) || !connected) {
            close(); throw IOException("BLE: адаптер не отвечает (включите зажигание, подойдите ближе)")
        }
        if (!g.discoverServices()) {
            close(); throw IOException("BLE: не удалось получить список служб адаптера")
        }
        if (!svcLatch.await(10, TimeUnit.SECONDS) || !servicesOk) {
            close(); throw IOException("BLE: службы адаптера не найдены")
        }
        pickCharacteristics(g.services)
        val n = notifyChar
        val w = writeChar
        if (n == null || w == null) {
            close(); throw IOException("BLE: у адаптера не найдены каналы обмена (это точно ELM327 по BLE?)")
        }
        writeType = if ((w.properties and BluetoothGattCharacteristic.PROPERTY_WRITE) != 0)
            BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        else BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        if (g.requestMtu(185)) mtuLatch.await(2, TimeUnit.SECONDS)
        enableNotifications(g, n)
        Thread.sleep(200)
    }

    private fun pickCharacteristics(services: List<BluetoothGattService>) {
        // (служба, уведомления, запись) по первым 8 символам UUID
        val known = listOf(
            Triple("0000fff0", "0000fff1", "0000fff2"),
            Triple("0000ffe0", "0000ffe1", "0000ffe1"),
            Triple("000018f0", "00002af0", "00002af1"),
            Triple("6e400001", "6e400003", "6e400002"),
        )
        for ((s, n, w) in known) {
            val svc = services.firstOrNull { it.uuid.toString().startsWith(s, ignoreCase = true) } ?: continue
            val nc = svc.characteristics.firstOrNull { it.uuid.toString().startsWith(n, ignoreCase = true) }
            val wc = svc.characteristics.firstOrNull { it.uuid.toString().startsWith(w, ignoreCase = true) }
            if (nc != null && wc != null) { notifyChar = nc; writeChar = wc; return }
        }
        // общий случай: первая служба (кроме стандартных), где есть и уведомления, и запись
        val notifyMask = BluetoothGattCharacteristic.PROPERTY_NOTIFY or BluetoothGattCharacteristic.PROPERTY_INDICATE
        val writeMask = BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE
        for (svc in services) {
            val id = svc.uuid.toString().lowercase()
            if (id.startsWith("00001800") || id.startsWith("00001801") || id.startsWith("0000180a")) continue
            val nc = svc.characteristics.firstOrNull { (it.properties and notifyMask) != 0 }
            val wc = svc.characteristics.firstOrNull { (it.properties and writeMask) != 0 }
            if (nc != null && wc != null) { notifyChar = nc; writeChar = wc; return }
        }
    }

    @Suppress("DEPRECATION")
    private fun enableNotifications(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
        g.setCharacteristicNotification(c, true)
        val cccd = c.getDescriptor(UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")) ?: return
        val value = if ((c.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0)
            BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        else BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
        descLatch = CountDownLatch(1)
        val started = if (Build.VERSION.SDK_INT >= 33) {
            g.writeDescriptor(cccd, value) == BluetoothStatusCodes.SUCCESS
        } else {
            cccd.value = value
            g.writeDescriptor(cccd)
        }
        if (started) descLatch.await(3, TimeUnit.SECONDS)
    }

    /** Отправка кусками по размеру MTU; для записи с ответом ждём подтверждения каждого куска. */
    @Suppress("DEPRECATION")
    private fun write(data: ByteArray) {
        val g = gatt ?: throw IOException("BLE: нет соединения")
        val c = writeChar ?: throw IOException("BLE: нет соединения")
        val chunk = maxOf(20, mtu - 3)
        var off = 0
        while (off < data.size) {
            val part = data.copyOfRange(off, minOf(data.size, off + chunk))
            val latch = if (writeType == BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) CountDownLatch(1) else null
            writeLatch = latch
            writeOk = true
            val started = if (Build.VERSION.SDK_INT >= 33) {
                g.writeCharacteristic(c, part, writeType) == BluetoothStatusCodes.SUCCESS
            } else {
                c.writeType = writeType
                c.value = part
                g.writeCharacteristic(c)
            }
            if (!started) throw IOException("BLE: не удалось отправить команду")
            if (latch != null) {
                if (!latch.await(2, TimeUnit.SECONDS) || !writeOk) throw IOException("BLE: нет подтверждения записи")
            } else Thread.sleep(15)
            off += part.size
        }
    }

    @Synchronized
    override fun command(cmd: String, timeoutMs: Long): String {
        if (!connected) throw IOException("BLE: соединение потеряно")
        rx.clear()
        write((cmd + "\r").toByteArray(Charsets.US_ASCII))
        val sb = StringBuilder()
        val deadline = System.currentTimeMillis() + timeoutMs
        while (true) {
            val remaining = deadline - System.currentTimeMillis()
            if (remaining <= 0) throw SocketTimeoutException("Таймаут: $cmd")
            val chunk = rx.poll(minOf(remaining, 100L), TimeUnit.MILLISECONDS)
            if (chunk != null) {
                for (b in chunk) {
                    val ch = (b.toInt() and 0xFF).toChar()
                    if (ch == '>') return clean(sb.toString(), cmd)
                    sb.append(ch)
                }
            } else if (!connected) throw IOException("BLE: соединение потеряно")
        }
    }

    override fun close() {
        connected = false
        runCatching { gatt?.disconnect() }
        runCatching { gatt?.close() }
        gatt = null; notifyChar = null; writeChar = null
        writeLatch?.countDown()
    }

    private fun clean(s: String, cmd: String): String =
        s.replace('\r', '\n').lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.equals(cmd, ignoreCase = true) }
            .joinToString("\n")
}
