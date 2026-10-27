package com.aatorque.obd

import java.io.IOException

/** Канал связи с ELM327: Bluetooth Classic, BLE или Wi-Fi. Команды выполняются строго по очереди. */
interface ObdLink {
    @Throws(IOException::class)
    fun open()

    fun close()

    /** Отправляет команду и возвращает строки ответа без эха и без приглашения «>». */
    @Throws(IOException::class)
    fun command(cmd: String, timeoutMs: Long): String
}
