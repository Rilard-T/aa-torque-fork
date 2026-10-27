package com.aatorque.obd

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.SocketTimeoutException
import java.util.UUID

/** Bluetooth Classic (SPP) канал к ELM327. Команды строго по очереди. */
class Elm327Link(private val device: BluetoothDevice) : ObdLink {
    private var socket: BluetoothSocket? = null
    private var input: InputStream? = null
    private var output: OutputStream? = null

    @SuppressLint("MissingPermission")
    override fun open() {
        close()
        runCatching { BluetoothAdapter.getDefaultAdapter()?.cancelDiscovery() }   // нужен BLUETOOTH_SCAN; поиск мы не запускаем, отказ не страшен
        // Клонам ELM327 подходят разные способы: обычный SPP, незащищённый SPP, канал 1 напрямую
        val ways = listOf<() -> BluetoothSocket>(
            { device.createRfcommSocketToServiceRecord(SPP_UUID) },
            { device.createInsecureRfcommSocketToServiceRecord(SPP_UUID) },
            {
                device.javaClass.getMethod("createRfcommSocket", Int::class.javaPrimitiveType)
                    .invoke(device, 1) as BluetoothSocket
            },
        )
        var last: Exception? = null
        for ((i, make) in ways.withIndex()) {
            var s: BluetoothSocket? = null
            try {
                s = make()
                s.connect()
                socket = s
                input = s.inputStream
                output = s.outputStream
                return
            } catch (e: Exception) {
                last = e
                runCatching { s?.close() }
                if (i < ways.size - 1) Thread.sleep(700)
            }
        }
        throw IOException("Канал Bluetooth не открылся: ${last?.message}")
    }

    override fun close() {
        runCatching { socket?.close() }
        socket = null; input = null; output = null
    }

    /** Отправляет команду и читает ответ до приглашения '>' . Возвращает строки ответа без эха. */
    @Synchronized
    override fun command(cmd: String, timeoutMs: Long): String {
        val out = output ?: throw IOException("Нет соединения")
        val inp = input ?: throw IOException("Нет соединения")
        while (inp.available() > 0) inp.read()          // выбрасываем хвост прошлого ответа
        out.write((cmd + "\r").toByteArray(Charsets.US_ASCII))
        out.flush()
        val sb = StringBuilder()
        val deadline = System.currentTimeMillis() + timeoutMs
        val buf = ByteArray(256)
        done@ while (true) {
            val n = inp.available()
            if (n > 0) {
                val r = inp.read(buf, 0, minOf(n, buf.size))
                if (r < 0) throw IOException("Соединение закрыто")
                for (i in 0 until r) {
                    val ch = (buf[i].toInt() and 0xFF).toChar()
                    if (ch == '>') break@done
                    sb.append(ch)
                }
            } else {
                if (System.currentTimeMillis() > deadline) throw SocketTimeoutException("Таймаут: $cmd")
                Thread.sleep(1)
            }
        }
        return sb.toString().replace('\r', '\n').lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.equals(cmd, ignoreCase = true) }
            .joinToString("\n")
    }

    companion object {
        val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    }
}
