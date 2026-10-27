package com.aatorque.obd

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.IInterface
import android.util.Log
import org.prowl.torque.remote.ITorqueService
import java.lang.reflect.Proxy

/**
 * Локальная замена Torque Pro. Отдаёт тот же интерфейс ITorqueService, но данные берёт
 * напрямую из ELM327. Форк AA Torque привязывается к этому сервису вместо Torque.
 * Работает как foreground-сервис (тип connectedDevice), чтобы система не выгружала его в фоне.
 */
class ObdBridgeService : Service() {
    private var engine: ObdEngine?
        get() = current
        set(v) { current = v }
    @Volatile private var running = false
    @Volatile private var isForeground = false
    private var worker: Thread? = null
    private val prefs by lazy { getSharedPreferences("obd_bridge", MODE_PRIVATE) }

    /**
     * Binder, у которого queryLocalInterface отдаёт наш объект. ITorqueService.Stub.asInterface()
     * в форке вернёт именно его. Прокси реализует интерфейс при любом наборе методов в AIDL:
     * неизвестные методы получают безопасные значения по умолчанию.
     */
    private val binder: IBinder = object : Binder() {
        override fun queryLocalInterface(descriptor: String): IInterface? =
            if (descriptor == DESCRIPTOR) api else null
    }

    @Suppress("UNCHECKED_CAST")
    private val api: ITorqueService by lazy {
        Proxy.newProxyInstance(
            ITorqueService::class.java.classLoader,
            arrayOf(ITorqueService::class.java)
        ) { _, m, args ->
            when (m.name) {
                "asBinder" -> binder
                "listAllPIDs" -> engine?.listIds() ?: MazdaPids.all.map { it.id }.toTypedArray()
                "getPIDInformation" -> {
                    val p = args!![0] as Array<String>
                    Array(p.size) { MazdaPids.byId(p[it])?.info() ?: "" }
                }
                "getPIDValuesAsDouble" -> {
                    val p = args!![0] as Array<String>
                    val e = engine
                    DoubleArray(p.size) { e?.value(p[it]) ?: 0.0 }
                }
                "isConnectedToECU" -> engine != null
                else -> defaultFor(m.returnType)
            }
        } as ITorqueService
    }

    private fun defaultFor(t: Class<*>): Any? = when {
        t == Void.TYPE -> null
        t == Boolean::class.javaPrimitiveType -> false
        t == Int::class.javaPrimitiveType -> 0
        t == Long::class.javaPrimitiveType -> 0L
        t == Float::class.javaPrimitiveType -> 0f
        t == Double::class.javaPrimitiveType -> 0.0
        t.isArray -> java.lang.reflect.Array.newInstance(t.componentType, 0)
        else -> null
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        running = true
        goForeground(status)    // для сервиса, запущенного только привязкой, может быть запрещено — это нормально
        worker = Thread(::connectLoop, "obd-connect").also { it.isDaemon = true; it.start() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            isForeground = false
            stopSelf()
            return START_NOT_STICKY
        }
        if (!isForeground && !goForeground(status)) {
            // startForegroundService() обязывает вызвать startForeground(); если не вышло — не держим сервис
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        worker?.interrupt()
        engine?.close(); engine = null
        status = "Остановлено"
        super.onDestroy()
    }

    // ---------------- foreground и уведомление ----------------

    private fun goForeground(text: String): Boolean {
        val n = buildNotification(text)
        return try {
            if (Build.VERSION.SDK_INT >= 29)
                startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
            else startForeground(NOTIF_ID, n)
            isForeground = true
            true
        } catch (e: Exception) {   // Android 12+: старт из фона запрещён; либо нет разрешения/типа в манифесте
            Log.w(TAG, "foreground не запущен: ${e.message}")
            isForeground = false
            false
        }
    }

    @Suppress("DEPRECATION")
    private fun buildNotification(text: String): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "Связь с адаптером OBD", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, ObdSettingsActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, ObdBridgeService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle("Mazda: диагностика")
            .setContentText(text)
            .setContentIntent(open)
            .addAction(0, "Остановить", stop)
            .setOngoing(true)
            .build()
    }

    /** Обновляет статус для настроек и текст уведомления. */
    private fun post(text: String) {
        status = text
        if (isForeground) {
            getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification(text))
        }
    }

    // ---------------- подключение ----------------

    /** Канал связи и подсказка на случай, если его не удалось открыть. */
    private class Target(val link: ObdLink, val label: String, val hint: String)

    /** Создаёт канал по выбранному типу подключения; если не получилось, причина остаётся в статусе. */
    @SuppressLint("MissingPermission")
    private fun createTarget(): Target? {
        when (prefs.getString("link_type", "classic")) {
            "wifi" -> {
                val host = prefs.getString("wifi_host", null)?.trim().orEmpty().ifBlank { "192.168.0.10" }
                val port = prefs.getString("wifi_port", null)?.trim()?.toIntOrNull() ?: 35000
                return Target(
                    WifiLink(applicationContext, host, port), "Wi-Fi $host:$port",
                    "Не удалось подключиться по Wi-Fi. Подключите телефон к сети адаптера (WiFi_OBDII, V-LINK…), " +
                        "проверьте адрес и порт, включите зажигание."
                )
            }
            "ble" -> {
                if (Build.VERSION.SDK_INT >= 31 &&
                    checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                    post("Нет разрешения BLUETOOTH_CONNECT")
                    return null
                }
                val mac = prefs.getString("ble_mac", null)?.takeIf { it.isNotBlank() }
                if (mac == null) {
                    post("Выберите BLE-адаптер: «Найти BLE-адаптеры»")
                    return null
                }
                val dev = BluetoothAdapter.getDefaultAdapter()?.getRemoteDevice(mac) ?: return null
                return Target(
                    BleLink(applicationContext, dev), "BLE ${dev.name ?: mac}",
                    "Не удалось подключиться по BLE. Включите зажигание, подойдите ближе к адаптеру, " +
                        "закройте другие программы с OBD."
                )
            }
            else -> {
                val dev = findDevice()
                if (dev == null) {
                    post(status.takeIf { it.startsWith("Нет разрешения") } ?: "Адаптер не найден: сопрягите его в Bluetooth")
                    return null
                }
                return Target(
                    Elm327Link(dev), dev.name ?: dev.address,
                    "Не удалось открыть канал Bluetooth. Включите зажигание, закройте другие программы " +
                        "с OBD (CarScanner, Torque, старую версию приложения)."
                )
            }
        }
    }

    private fun connectLoop() {
        while (running) {
            var link: ObdLink? = null      // закрываем при любом сбое, иначе адаптер остаётся занятым нами же
            try {
                reconnectFlag = false
                val t = createTarget()
                if (t == null) {
                    Thread.sleep(5000); continue
                }
                post("Подключение: ${t.label}…")
                link = t.link
                try {
                    t.link.open()
                } catch (ex: java.io.IOException) {
                    throw java.io.IOException(t.hint + " " + ex.message)
                }
                post("Инициализация адаптера…")
                val e = ObdEngine(t.link, prefs)
                e.init()
                engine = e
                post("Подключено")
                while (running && e.failures < 5 && !reconnectFlag) Thread.sleep(1000)
                post(if (reconnectFlag) "Переподключение…" else "Связь потеряна")
                reconnectFlag = false
                engine = null
                e.close()
            } catch (ie: InterruptedException) {
                link?.close()
                break
            } catch (ex: Exception) {
                engine?.close(); engine = null
                link?.close()
                post(ex.message ?: "Ошибка подключения")
                try { Thread.sleep(3000) } catch (_: InterruptedException) { break }
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun findDevice(): BluetoothDevice? {
        if (Build.VERSION.SDK_INT >= 31 &&
            checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            status = "Нет разрешения BLUETOOTH_CONNECT"
            return null
        }
        val bonded = BluetoothAdapter.getDefaultAdapter()?.bondedDevices ?: return null
        val mac = prefs.getString("obd_mac", null)?.takeIf { it.isNotBlank() } ?: ObdConfig.ADAPTER_MAC.takeIf { it.isNotBlank() }
        if (mac != null) bonded.firstOrNull { it.address.equals(mac, ignoreCase = true) }?.let { return it }
        val re = Regex("obd|elm|v-?link|vgate|konnwei|icar|veepeak|scan|327", RegexOption.IGNORE_CASE)
        return bonded.firstOrNull { re.containsMatchIn(it.name ?: "") }
    }

    companion object {
        private const val TAG = "ObdBridge"
        private const val DESCRIPTOR = "org.prowl.torque.remote.ITorqueService"
        private const val CHANNEL = "obd_bridge"
        private const val NOTIF_ID = 4711
        private const val ACTION_STOP = "com.aatorque.obd.STOP"

        /** Текст состояния для экрана настроек. */
        @Volatile var status: String = "Не запущено"

        @Volatile private var current: ObdEngine? = null
        @Volatile private var reconnectFlag = false

        /** Текущее значение параметра для экрана проверки. Вызывать только не из главного потока. */
        fun peek(id: String): Double? = current?.value(id)

        /** Переподключиться (например, после смены адаптера). */
        fun reconnect(context: Context) {
            reconnectFlag = true
            start(context)
        }

        /** Остановить foreground-режим и сервис. */
        fun stop(context: Context) {
            try {
                context.startService(Intent(context, ObdBridgeService::class.java).setAction(ACTION_STOP))
            } catch (e: Exception) {
                Log.w(TAG, "stop: ${e.message}")
            }
        }

        /**
         * Запускает сервис как foreground. Безопасно вызывать откуда угодно: если система запрещает
         * старт из фона (Android 12+), исключение гасится, и сервис просто работает как привязанный.
         * Гарантированно разрешено, когда приложение на экране (например, из SettingsActivity).
         */
        fun start(context: Context) {
            try {
                context.startForegroundService(Intent(context, ObdBridgeService::class.java))
            } catch (e: Exception) {
                Log.w(TAG, "start: ${e.message}")
            }
        }
    }
}
