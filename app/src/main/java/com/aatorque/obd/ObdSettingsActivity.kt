package com.aatorque.obd

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.InputType
import androidx.appcompat.app.AppCompatActivity
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.SwitchPreferenceCompat
import com.aatorque.stats.R
import java.util.Locale
import java.util.concurrent.Executors

/** Экран «Подключение к OBD» в стиле остальных настроек приложения (тема AppTheme, список настроек). */
class ObdSettingsActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(R.style.AppTheme)              // та же тема, что у главного экрана
        super.onCreate(savedInstanceState)
        title = "Подключение к OBD"
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(android.R.id.content, ObdPrefsFragment())
                .commit()
        }
        askPermissionsThenStart()
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    fun askPermissionsThenStart() {
        val need = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= 31 &&
            checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED)
            need += Manifest.permission.BLUETOOTH_CONNECT
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            need += Manifest.permission.POST_NOTIFICATIONS
        if (need.isEmpty()) ObdBridgeService.start(this)
        else requestPermissions(need.toTypedArray(), 77)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 77) {
            ObdBridgeService.start(this)
            (supportFragmentManager.findFragmentById(android.R.id.content) as? ObdPrefsFragment)?.refreshAdapters()
        }
        if (requestCode == 78 && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            (supportFragmentManager.findFragmentById(android.R.id.content) as? ObdPrefsFragment)?.startBleScan()
        }
    }

    /** Для поиска BLE: BLUETOOTH_SCAN (Android 12+) или геолокация (Android 11 и ниже). */
    fun hasScanPermission(): Boolean =
        if (Build.VERSION.SDK_INT >= 31)
            checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED
        else
            checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    fun requestScanPermission() {
        val p = if (Build.VERSION.SDK_INT >= 31) Manifest.permission.BLUETOOTH_SCAN
        else Manifest.permission.ACCESS_FINE_LOCATION
        requestPermissions(arrayOf(p), 78)
    }
}

class ObdPrefsFragment : PreferenceFragmentCompat() {
    private val ui = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private var statusPref: Preference? = null
    private var valuesPref: Preference? = null
    private var adapterPref: ListPreference? = null
    private val classicPrefs = mutableListOf<Preference>()
    private val blePrefs = mutableListOf<Preference>()
    private val wifiPrefs = mutableListOf<Preference>()
    private var bleScanPref: Preference? = null
    private var blePref: ListPreference? = null
    private val scanner = BleScanner()
    @Volatile private var busy = false

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        preferenceManager.sharedPreferencesName = "obd_bridge"
        val ctx = requireContext()
        val prefs = ctx.getSharedPreferences("obd_bridge", Context.MODE_PRIVATE)
        val screen = preferenceManager.createPreferenceScreen(ctx)
        preferenceScreen = screen

        fun category(title: String): PreferenceCategory {
            val c = PreferenceCategory(ctx)
            c.title = title
            screen.addPreference(c)
            return c
        }
        fun action(cat: PreferenceCategory, title: String, summary: String?, onClick: () -> Unit) {
            val p = Preference(ctx)
            p.title = title
            if (summary != null) p.summary = summary
            p.setOnPreferenceClickListener { onClick(); true }
            cat.addPreference(p)
        }

        // --- состояние ---
        val st = category("Состояние")
        val status = Preference(ctx)
        status.title = "Связь с адаптером"
        status.summary = ObdBridgeService.status
        status.isSelectable = false
        st.addPreference(status)
        statusPref = status
        val values = Preference(ctx)
        values.title = "Текущие значения"
        values.summary = "—"
        values.isSelectable = false
        st.addPreference(values)
        valuesPref = values

        // --- подключение ---
        val con = category("Подключение")
        action(con, "Запустить / подключиться", "Запускает фоновую службу и переподключается к адаптеру") {
            (activity as? ObdSettingsActivity)?.askPermissionsThenStart()
            ObdBridgeService.reconnect(ctx)
        }
        action(con, "Остановить", "Закрывает соединение и убирает уведомление") {
            ObdBridgeService.stop(ctx)
        }

        val type = ListPreference(ctx)
        type.key = "link_type"
        type.title = "Тип подключения"
        type.dialogTitle = "Как подключён адаптер"
        type.entries = arrayOf("Bluetooth Classic (обычный)", "Bluetooth LE (BLE)", "Wi-Fi")
        type.entryValues = arrayOf("classic", "ble", "wifi")
        type.setDefaultValue("classic")
        type.summaryProvider = ListPreference.SimpleSummaryProvider.getInstance()
        type.setOnPreferenceChangeListener { _, v ->
            updateLinkPrefs(v.toString())
            ObdBridgeService.reconnect(ctx)
            true
        }
        con.addPreference(type)

        // Bluetooth Classic
        val btSettings = Preference(ctx)
        btSettings.title = "Настройки Bluetooth"
        btSettings.summary = "Сопряжение адаптера (имя обычно OBDII, PIN 1234 или 0000)"
        btSettings.setOnPreferenceClickListener { startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)); true }
        con.addPreference(btSettings)
        classicPrefs += btSettings
        val ad = ListPreference(ctx)
        ad.key = "obd_mac"
        ad.title = "Адаптер"
        ad.dialogTitle = "Адаптер (из сопряжённых устройств)"
        ad.summaryProvider = ListPreference.SimpleSummaryProvider.getInstance()
        ad.setOnPreferenceChangeListener { _, _ -> ObdBridgeService.reconnect(ctx); true }
        con.addPreference(ad)
        adapterPref = ad
        classicPrefs += ad
        refreshAdapters()

        // Bluetooth LE
        val bleScan = Preference(ctx)
        bleScan.title = "Найти BLE-адаптеры"
        bleScan.summary = "Поиск рядом занимает около 10 секунд. Адаптер должен быть включён."
        bleScan.setOnPreferenceClickListener { startBleScan(); true }
        con.addPreference(bleScan)
        blePrefs += bleScan
        bleScanPref = bleScan
        val bleMac = ListPreference(ctx)
        bleMac.key = "ble_mac"
        bleMac.title = "BLE-адаптер"
        bleMac.dialogTitle = "BLE-адаптер (из найденных)"
        bleMac.summaryProvider = Preference.SummaryProvider<ListPreference> { p -> p.entry?.toString() ?: "Не выбран" }
        bleMac.setOnPreferenceChangeListener { _, _ -> ObdBridgeService.reconnect(ctx); true }
        con.addPreference(bleMac)
        blePrefs += bleMac
        blePref = bleMac
        fillBle(emptyList())

        // Wi-Fi
        val wifiSettings = Preference(ctx)
        wifiSettings.title = "Настройки Wi-Fi"
        wifiSettings.summary = "Подключитесь к сети адаптера (имя вроде WiFi_OBDII или V-LINK, интернета в ней нет)"
        wifiSettings.setOnPreferenceClickListener { startActivity(Intent(Settings.ACTION_WIFI_SETTINGS)); true }
        con.addPreference(wifiSettings)
        wifiPrefs += wifiSettings
        val wifiHost = EditTextPreference(ctx)
        wifiHost.key = "wifi_host"
        wifiHost.title = "Адрес адаптера"
        wifiHost.dialogTitle = "IP-адрес адаптера"
        wifiHost.setDefaultValue("192.168.0.10")
        wifiHost.summaryProvider = EditTextPreference.SimpleSummaryProvider.getInstance()
        wifiHost.setOnPreferenceChangeListener { _, _ -> ObdBridgeService.reconnect(ctx); true }
        con.addPreference(wifiHost)
        wifiPrefs += wifiHost
        val wifiPort = EditTextPreference(ctx)
        wifiPort.key = "wifi_port"
        wifiPort.title = "Порт адаптера"
        wifiPort.dialogTitle = "Порт адаптера"
        wifiPort.setDefaultValue("35000")
        wifiPort.setOnBindEditTextListener { it.inputType = InputType.TYPE_CLASS_NUMBER }
        wifiPort.summaryProvider = EditTextPreference.SimpleSummaryProvider.getInstance()
        wifiPort.setOnPreferenceChangeListener { _, _ -> ObdBridgeService.reconnect(ctx); true }
        con.addPreference(wifiPort)
        wifiPrefs += wifiPort

        updateLinkPrefs(prefs.getString("link_type", "classic") ?: "classic")

        // --- опрос датчиков ---
        val poll = category("Опрос датчиков")
        val fast = ListPreference(ctx)
        fast.key = "poll_fast_ms"
        fast.title = "Частота опроса датчиков"
        fast.dialogTitle = "Как часто обновлять основные датчики"
        fast.entries = arrayOf("Максимальная (без паузы)", "Высокая (~10 раз/с)", "Обычная (~5 раз/с)", "Экономная (~2 раза/с)")
        fast.entryValues = arrayOf("0", "100", "200", "500")
        fast.setDefaultValue("100")
        fast.summaryProvider = ListPreference.SimpleSummaryProvider.getInstance()
        poll.addPreference(fast)
        val slow = ListPreference(ctx)
        slow.key = "poll_slow_ms"
        slow.title = "Частота опроса медленных датчиков"
        slow.dialogTitle = "Сажа, температура АКПП, счётчик и пробег прожига"
        slow.entries = arrayOf("Часто (раз в 1 с)", "Обычно (раз в 3 с)", "Редко (раз в 10 с)", "Очень редко (раз в 30 с)")
        slow.entryValues = arrayOf("1000", "3000", "10000", "30000")
        slow.setDefaultValue("3000")
        slow.summaryProvider = ListPreference.SimpleSummaryProvider.getInstance()
        poll.addPreference(slow)
        val turbo = SwitchPreferenceCompat(ctx)
        turbo.key = "fast_adapter"
        turbo.title = "Ускорение ответов адаптера"
        turbo.summary = "Режим ATAT2 и ответ без ожидания лишних кадров. Если значения пропали, выключите."
        turbo.setDefaultValue(true)
        turbo.setOnPreferenceChangeListener { _, _ -> ObdBridgeService.reconnect(ctx); true }
        poll.addPreference(turbo)

        // --- сажевый фильтр ---
        val dpfCat = category("Сажевый фильтр")
        val dpf = EditTextPreference(ctx)
        dpf.key = "dpf_full_gl_ui"
        dpf.isPersistent = false                 // число храним сами как float (его читает ObdEngine)
        dpf.title = "Порог сажи для «DPF %», г/л"
        dpf.dialogTitle = "Порог сажи (г/л), пусто — ${ObdConfig.DPF_FULL_GL} по умолчанию"
        dpf.text = prefs.getFloat("dpf_full_gl", 0f).takeIf { it > 0 }?.toString() ?: ""
        dpf.setOnBindEditTextListener { it.inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL }
        dpf.summaryProvider = Preference.SummaryProvider<EditTextPreference> { p ->
            val v = if (p.text.isNullOrBlank()) "${ObdConfig.DPF_FULL_GL} г/л (по умолчанию)" else "${p.text} г/л"
            "$v. Среднее значение, фактическое может отличаться. Если счётчик врёт, используйте «Экспериментальные функции»."
        }
        dpf.setOnPreferenceChangeListener { _, v ->
            val f = v.toString().replace(',', '.').toFloatOrNull() ?: 0f
            prefs.edit().putFloat("dpf_full_gl", f).apply()
            true
        }
        dpfCat.addPreference(dpf)

        // --- приложение ---
        val app = category("Приложение")
        action(app, "Экспериментальные функции", "Запись сажи перед прожигом и подбор порога для DPF %") {
            startActivity(Intent(ctx, ObdExperimentalActivity::class.java))
        }
        action(app, "Выход из приложения", "Закрыть службу, соединение и приложение целиком") {
            activity?.let { AppExit.confirmAndExit(it) }
        }
        wrapTitles(screen)
    }

    /** Показывает настройки только выбранного типа подключения. */
    private fun updateLinkPrefs(type: String) {
        classicPrefs.forEach { it.isVisible = type == "classic" }
        blePrefs.forEach { it.isVisible = type == "ble" }
        wifiPrefs.forEach { it.isVisible = type == "wifi" }
    }

    fun startBleScan() {
        val act = activity as? ObdSettingsActivity ?: return
        if (!act.hasScanPermission()) {
            act.requestScanPermission()
            return
        }
        bleScanPref?.summary = "Поиск… (около 10 секунд)"
        scanner.start(
            10_000,
            onUpdate = { list ->
                fillBle(list)
                bleScanPref?.summary = "Найдено: ${list.size}"
            },
            onDone = { list ->
                fillBle(list)
                bleScanPref?.summary =
                    if (list.isEmpty()) "Ничего не найдено. Включите зажигание, поднесите телефон ближе и повторите."
                    else "Найдено: ${list.size}. Выберите адаптер в списке ниже."
            }
        )
    }

    private fun fillBle(list: List<BleScanner.Found>) {
        val pref = blePref ?: return
        val ctx = context ?: return
        val saved = ctx.getSharedPreferences("obd_bridge", Context.MODE_PRIVATE)
            .getString("ble_mac", null)?.takeIf { it.isNotBlank() }
        val names = mutableListOf("Не выбран")
        val vals = mutableListOf("")
        list.forEach {
            names += "${it.name}  (${it.address}), ${it.rssi} дБм"
            vals += it.address
        }
        if (saved != null && vals.none { it.equals(saved, ignoreCase = true) }) {
            names += "Сохранённый ($saved)"
            vals += saved
        }
        pref.entries = names.toTypedArray()
        pref.entryValues = vals.toTypedArray()
    }

    @SuppressLint("MissingPermission")
    fun refreshAdapters() {
        val ad = adapterPref ?: return
        val ctx = context ?: return
        val names = mutableListOf("Автоматически (OBD, ELM, V-Link…)")
        val vals = mutableListOf("")
        val allowed = Build.VERSION.SDK_INT < 31 ||
            ctx.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
        if (allowed) {
            BluetoothAdapter.getDefaultAdapter()?.bondedDevices
                ?.sortedBy { it.name ?: "" }
                ?.forEach {
                    names += "${it.name ?: "?"}  (${it.address})"
                    vals += it.address
                }
        }
        ad.entries = names.toTypedArray()
        ad.entryValues = vals.toTypedArray()
        if (ad.value == null) ad.value = ""
    }

    override fun onResume() {
        super.onResume()
        refreshAdapters()
        ui.post(tick)
    }

    override fun onPause() {
        ui.removeCallbacks(tick)
        scanner.stop()
        super.onPause()
    }

    override fun onDestroy() {
        io.shutdown()
        super.onDestroy()
    }

    private val tick = object : Runnable {
        override fun run() {
            statusPref?.summary = ObdBridgeService.status
            if (!busy) {
                busy = true
                io.execute {
                    val text = KEY.joinToString("\n") { (id, name) ->
                        val v = ObdBridgeService.peek(id)
                        name + ": " + (v?.let { String.format(Locale.US, "%.2f", it) } ?: "—")
                    }
                    ui.post { valuesPref?.summary = text; busy = false }
                }
            }
            ui.postDelayed(this, 2000)
        }
    }

    private companion object {
        val KEY = listOf(
            "05" to "Температура ОЖ, °C",
            "0c" to "Обороты, об/мин",
            "0d" to "Скорость, км/ч",
            "ff9005" to "Температура АКПП, °C",
            "ff9006" to "Статус прожига (0/1/2)",
            "ff9003" to "Сажа, г/л",
            "ff9001" to "Счётчик регенераций",
            "ff9002" to "Пробег после прожига, км",
        )
    }
}
