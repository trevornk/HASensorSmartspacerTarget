package com.noirdraco.hasensorsmartspacertarget

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Dauerhafter, kleiner Ringpuffer der letzten Abrufe und Heartbeats — damit sich im Alltag ohne
 * eigenes Debug-Gerüst nachvollziehen lässt, wann was aufgefrischt wurde und woran es scheiterte.
 * Logcat taugt dafür nicht (Puffer zu kurz, Mehrstunden-Tests laufen unbeaufsichtigt).
 *
 * Liegt im app-eigenen externen Verzeichnis, andere Apps kommen nicht heran; abholen per
 * `adb pull /sdcard/Android/data/<paket>/files/refresh_log.txt`. Enthält weder URL noch Token,
 * nur gekürzte smartspacerIds und den abgerufenen Zustand.
 */
object RefreshLog {

    const val FILE_NAME = "refresh_log.txt"
    const val MAX_LINES = 300

    // Auslöser eines Abrufs, wie sie im Log stehen
    const val TRIGGER_HEARTBEAT = "heartbeat"
    const val TRIGGER_BROADCAST = "broadcast"
    const val TRIGGER_SETUP = "setup"
    const val TRIGGER_ON_DEMAND = "auswertung"
    const val TRIGGER_BOOT = "boot"

    private val timeFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT)

    fun log(context: Context, message: String) {
        val line = timeFormat.format(Date()) + " " + message
        // Nie wegen des Logs etwas kaputtmachen — schlimmstenfalls fehlt eine Zeile.
        runCatching {
            synchronized(this) {
                val file = logFile(context)
                val old = if (file.exists()) file.readLines() else emptyList()
                file.writeText(appendCapped(old, line, MAX_LINES).joinToString("\n", postfix = "\n"))
            }
        }
    }

    /** Hängt [line] an und behält nur die letzten [max] Zeilen. */
    fun appendCapped(lines: List<String>, line: String, max: Int): List<String> =
        (lines + line).takeLast(max)

    /** Kurzform der smartspacerId (UUID) — reicht zum Unterscheiden im Log. */
    fun shortId(smartspacerId: String): String = smartspacerId.take(8)

    private fun logFile(context: Context): File =
        File(context.getExternalFilesDir(null) ?: context.filesDir, FILE_NAME)
}
