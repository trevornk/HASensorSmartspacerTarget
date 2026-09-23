package com.noirdraco.hasensorsmartspacertarget

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Gemeinsamer Ablauf beider Worker: Einstellungen laden, Heartbeat sicherstellen, abrufen,
 * speichern, loggen. Konvention: `Success` → speichern; `HttpError` → `failure` (dauerhaft,
 * falscher Token/Entity); `NetworkError` → `retry` bis [MAX_ATTEMPTS].
 *
 * Die Unterklassen unterscheiden sich nur darin, wann sie Smartspacer benachrichtigen — siehe
 * [onValueStored] und [onFinished]. Ihre Klassennamen bleiben, weil WorkManager eingeplante Jobs
 * über den Klassennamen wiederfindet.
 */
abstract class HaRefreshWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    companion object {
        const val KEY_SMARTSPACER_ID = "smartspacer_id"
        const val KEY_TRIGGER = "trigger"

        // Nach so vielen vergeblichen Versuchen aufgeben (bis zum nächsten Heartbeat)
        private const val MAX_ATTEMPTS = 4

        // HA-Zustände, die "kein echter Wert" bedeuten
        private val UNAVAILABLE_STATES = setOf("unavailable", "unknown", "none", "")
    }

    /** Kennung im [RefreshLog], z. B. "target". */
    protected abstract val kind: String

    /** Nach dem Speichern eines neuen Werts; [changed] = Zustand anders als vorher. */
    protected open fun onValueStored(smartspacerId: String, changed: Boolean) {}

    /** Immer am Ende eines Laufs, auch bei Fehlern. */
    protected open fun onFinished(smartspacerId: String) {}

    override suspend fun doWork(): Result {
        val smartspacerId = inputData.getString(KEY_SMARTSPACER_ID) ?: return Result.failure()
        // Jobs aus älteren Versionen (periodischer Fallback) tragen keinen Auslöser
        val trigger = inputData.getString(KEY_TRIGGER) ?: "alt"
        try {
            val settings = HomeAssistantPrefs.loadSettings(applicationContext, smartspacerId)
                ?: return Result.failure()

            // Selbstheilung: sicherstellen, dass der Doze-Heartbeat gesetzt ist (u. a. falls er
            // verloren gegangen ist). Verschiebt einen ausstehenden Alarm nicht.
            HomeAssistantPrefs.scheduleHeartbeat(applicationContext)

            val result = withContext(Dispatchers.IO) { HomeAssistantApi.fetch(settings) }
            val attempt = runAttemptCount + 1
            val outcome = when (result) {
                is FetchResult.Success -> {
                    val stored = handleSuccess(smartspacerId, result.value)
                    "ok ${result.value.state}" + if (stored) "" else " (alter Wert bleibt)"
                }
                is FetchResult.HttpError -> "http ${result.code}"
                is FetchResult.NetworkError -> "netz: ${result.message}"
            }
            RefreshLog.log(
                applicationContext,
                "$kind ${RefreshLog.shortId(smartspacerId)} $trigger #$attempt $outcome"
            )

            return when (result) {
                is FetchResult.Success -> Result.success()
                // Dauerhafter Fehler — letzten Wert stehen lassen, nicht wiederholen
                is FetchResult.HttpError -> Result.failure()
                // Vorübergehender Fehler — mit Backoff wiederholen, bis MAX_ATTEMPTS erreicht ist
                is FetchResult.NetworkError ->
                    if (attempt < MAX_ATTEMPTS) Result.retry() else Result.failure()
            }
        } finally {
            onFinished(smartspacerId)
        }
    }

    /** Speichert [value]; `false`, wenn er als kurzer Aussetzer verworfen wurde. */
    private fun handleSuccess(smartspacerId: String, value: SensorValue): Boolean {
        val old = HomeAssistantPrefs.loadLastValue(applicationContext, smartspacerId)
        val isUnavailable = value.state.trim().lowercase() in UNAVAILABLE_STATES
        // Kurzzeitig "unavailable"/"unknown" nicht übernehmen, wenn schon ein guter Wert vorliegt —
        // sonst zeigt das Target einen Aussetzer bzw. schlägt die Bedingung bei jedem Aussetzer um.
        if (isUnavailable && old != null) return false

        val changed = old == null || !old.state.trim().equals(value.state.trim(), ignoreCase = true)
        HomeAssistantPrefs.saveLastValue(applicationContext, smartspacerId, value)
        onValueStored(smartspacerId, changed)
        return true
    }
}
