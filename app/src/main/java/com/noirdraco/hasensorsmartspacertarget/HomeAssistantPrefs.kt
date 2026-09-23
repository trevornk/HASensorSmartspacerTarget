package com.noirdraco.hasensorsmartspacertarget

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import androidx.core.content.edit
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.WorkRequest
import androidx.work.workDataOf
import org.json.JSONObject
import java.util.concurrent.TimeUnit

data class SensorSettings(
    val baseUrl: String,
    val token: String,
    val entityId: String,
    // Optionaler Anzeige-Filter fürs Target: kommagetrennte Begriffe, von denen einer im Zustand
    // vorkommen muss (leer = immer anzeigen). Siehe [ShowRule]. Für die Anwesenheits-Bedingung
    // ohne Bedeutung.
    val showOnlyIf: String = ""
)

data class SensorValue(
    val state: String,
    val friendlyName: String,
    val unit: String,
    // MDI-Name aus dem HA-Attribut "icon", z. B. "mdi:trash-can" (leer, falls nicht gesetzt)
    val icon: String,
    // HA-Attribut "device_class", z. B. "temperature" — Zweitquelle fürs Icon
    val deviceClass: String,
    val timestamp: Long
)

/**
 * Zentrale Ablage der pro-smartspacerId gespeicherten Einstellungen und des zuletzt
 * abgerufenen Sensorwerts.
 *
 * Der Token liegt in EncryptedSharedPreferences (androidx.security), also verschlüsselt im
 * Android-Keystore. Sollte die Verschlüsselung auf einem Gerät fehlschlagen, wird auf normale
 * SharedPreferences zurückgefallen, damit die App nicht abstürzt — dann liegt der Token
 * unverschlüsselt, was für den privaten Gebrauch vertretbar ist.
 */
object HomeAssistantPrefs {

    private const val PREFS_NAME = "ha_sensor_secure"
    private const val KEY_SETTINGS_PREFIX = "settings_"
    private const val KEY_LAST_VALUE_PREFIX = "last_value_"

    @Volatile
    private var cachedPrefs: SharedPreferences? = null

    private fun prefs(context: Context): SharedPreferences {
        cachedPrefs?.let { return it }
        return synchronized(this) {
            cachedPrefs ?: buildPrefs(context.applicationContext).also { cachedPrefs = it }
        }
    }

    private fun buildPrefs(context: Context): SharedPreferences = try {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            PREFS_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    } catch (e: Exception) {
        // Fallback: unverschlüsselt, aber lauffähig
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    fun saveSettings(context: Context, smartspacerId: String, settings: SensorSettings) {
        val json = JSONObject()
            .put("baseUrl", settings.baseUrl)
            .put("token", settings.token)
            .put("entityId", settings.entityId)
            .put("showOnlyIf", settings.showOnlyIf)
        prefs(context).edit { putString(KEY_SETTINGS_PREFIX + smartspacerId, json.toString()) }
    }

    fun loadSettings(context: Context, smartspacerId: String): SensorSettings? {
        val raw = prefs(context).getString(KEY_SETTINGS_PREFIX + smartspacerId, null) ?: return null
        return runCatching {
            val json = JSONObject(raw)
            SensorSettings(
                baseUrl = json.getString("baseUrl"),
                token = json.getString("token"),
                entityId = json.getString("entityId"),
                // optString: Einstellungen, die vor dem Filter gespeichert wurden, haben das Feld nicht
                showOnlyIf = json.optString("showOnlyIf")
            )
        }.getOrNull()
    }

    fun saveLastValue(context: Context, smartspacerId: String, value: SensorValue) {
        val json = JSONObject()
            .put("state", value.state)
            .put("friendlyName", value.friendlyName)
            .put("unit", value.unit)
            .put("icon", value.icon)
            .put("deviceClass", value.deviceClass)
            .put("timestamp", value.timestamp)
        prefs(context).edit { putString(KEY_LAST_VALUE_PREFIX + smartspacerId, json.toString()) }
    }

    fun loadLastValue(context: Context, smartspacerId: String): SensorValue? {
        val raw = prefs(context).getString(KEY_LAST_VALUE_PREFIX + smartspacerId, null) ?: return null
        return runCatching {
            val json = JSONObject(raw)
            SensorValue(
                state = json.getString("state"),
                friendlyName = json.getString("friendlyName"),
                unit = json.optString("unit"),
                icon = json.optString("icon"),
                deviceClass = json.optString("deviceClass"),
                timestamp = json.getLong("timestamp")
            )
        }.getOrNull()
    }

    fun clear(context: Context, smartspacerId: String) {
        prefs(context).edit {
            remove(KEY_SETTINGS_PREFIX + smartspacerId)
            remove(KEY_LAST_VALUE_PREFIX + smartspacerId)
        }
    }

    private fun workName(smartspacerId: String) = "ha_refresh_$smartspacerId"

    /**
     * Abruf fürs Sensor-Target. [trigger] landet nur im [RefreshLog].
     *
     * Standardmäßig [ExistingWorkPolicy.KEEP]: Heartbeat, Smartspacer-Broadcast und Boot können
     * kurz nacheinander kommen — mit REPLACE würde der zweite Auslöser einen laufenden Abruf
     * abbrechen, im Doze womöglich mitten im knappen Netzfenster. Nur das Setup ersetzt ([replace]),
     * damit ein Abruf, der noch im Backoff hängt, nicht den ersten Wert verzögert.
     */
    fun enqueueRefresh(
        context: Context,
        smartspacerId: String,
        trigger: String,
        replace: Boolean = false
    ) {
        val request = OneTimeWorkRequestBuilder<HomeAssistantWorker>()
            .setInputData(refreshInput(smartspacerId, trigger))
            // Expedited + Netz-Constraint aus demselben Grund wie beim Anwesenheits-Refresh: im
            // Doze ist ohne dieses kurze Netzfenster kein DNS möglich, der Abruf scheitert und der
            // Cache bleibt auf dem Zustand von gestern stehen. Bei gesetztem Anzeige-Filter fällt
            // das doppelt auf — ein veralteter Zustand heißt dann „Target gar nicht sichtbar“.
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .setConstraints(
                Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
            )
            .setBackoffCriteria(
                BackoffPolicy.EXPONENTIAL,
                WorkRequest.MIN_BACKOFF_MILLIS,
                TimeUnit.MILLISECONDS
            )
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            workName(smartspacerId),
            if (replace) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP,
            request
        )
    }

    fun cancelRefresh(context: Context, smartspacerId: String) {
        WorkManager.getInstance(context).cancelUniqueWork(workName(smartspacerId))
    }

    private fun presenceWorkName(smartspacerId: String) = "ha_presence_$smartspacerId"

    /**
     * Refresh für die Anwesenheits-Bedingung (Requirement). Wird u. a. aus
     * [HomePresenceRequirement.isRequirementMet] angestoßen; deshalb standardmäßig
     * [ExistingWorkPolicy.KEEP], damit ein laufender Abruf nicht bei jeder Auswertung neu startet.
     * [replace] wie bei [enqueueRefresh] nur fürs Setup.
     */
    fun enqueuePresenceRefresh(
        context: Context,
        smartspacerId: String,
        trigger: String,
        replace: Boolean = false
    ) {
        val request = OneTimeWorkRequestBuilder<HomePresenceWorker>()
            .setInputData(refreshInput(smartspacerId, trigger))
            // Expedited: Android gewährt diesem Job auch im Doze/Standby ein kurzes Zeitfenster mit
            // Netzzugriff. Ohne das scheitert der Abruf zur (Cloud-)URL im Ruhezustand meist an DNS,
            // sodass eine Anwesenheitsänderung unterwegs/nach dem Heimkommen nicht bemerkt wird.
            // Fällt bei erschöpftem Expedited-Kontingent auf normale Ausführung zurück.
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .setConstraints(
                Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
            )
            .setBackoffCriteria(
                BackoffPolicy.EXPONENTIAL,
                WorkRequest.MIN_BACKOFF_MILLIS,
                TimeUnit.MILLISECONDS
            )
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            presenceWorkName(smartspacerId),
            if (replace) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP,
            request
        )
    }

    fun cancelPresenceRefresh(context: Context, smartspacerId: String) {
        WorkManager.getInstance(context).cancelUniqueWork(presenceWorkName(smartspacerId))
    }

    private fun refreshInput(smartspacerId: String, trigger: String) = workDataOf(
        HaRefreshWorker.KEY_SMARTSPACER_ID to smartspacerId,
        HaRefreshWorker.KEY_TRIGGER to trigger
    )

    /** Alle registrierten Targets und Bedingungen auffrischen (Heartbeat, Boot). */
    fun refreshAll(context: Context, trigger: String) {
        presenceIds(context).forEach { enqueuePresenceRefresh(context, it, trigger) }
        targetIds(context).forEach { enqueueRefresh(context, it, trigger) }
    }

    // Frühere Versionen hatten zusätzlich einen periodischen WorkManager-Job pro Bedingung. Der ist
    // durch den Heartbeat überflüssig (im Doze ohnehin unzuverlässig) und wird hier einmalig
    // abgeräumt. Aufgerufen beim App-Update/Boot und — als Netz — aus isRequirementMet.
    private const val KEY_LEGACY_PERIODIC_CANCELLED = "legacy_periodic_cancelled"

    private fun presencePeriodicWorkName(smartspacerId: String) = "ha_presence_periodic_$smartspacerId"

    fun cancelPresencePeriodicRefresh(context: Context, smartspacerId: String) {
        WorkManager.getInstance(context).cancelUniqueWork(presencePeriodicWorkName(smartspacerId))
    }

    fun cancelLegacyPeriodicRefreshes(context: Context) {
        val prefs = prefs(context)
        if (prefs.getBoolean(KEY_LEGACY_PERIODIC_CANCELLED, false)) return
        presenceIds(context).forEach { cancelPresencePeriodicRefresh(context, it) }
        prefs.edit { putBoolean(KEY_LEGACY_PERIODIC_CANCELLED, true) }
    }

    // Doze-fester Heartbeat für Anwesenheits-Bedingungen *und* Sensor-Targets. Weder
    // WorkManager-Periodic noch der Update-Broadcast von Smartspacer sind im Standby/Doze
    // verlässlich (Praxistest Anwesenheit: >90 Min Lücke beim Heimkommen). AlarmManager mit
    // setAndAllowWhileIdle feuert dagegen auch im Doze und stößt dann einen expedited Refresh an.
    // Es gibt keine wiederholende Idle-Variante, daher als Einzel-Alarm, den der Receiver bei jedem
    // Feuern neu setzt (self-heilend auch aus Setup und jedem Worker-Lauf).
    private const val HEARTBEAT_REQUEST_CODE = 4711
    private val HEARTBEAT_INTERVAL_MS = TimeUnit.MINUTES.toMillis(15)

    // Zeitpunkt, auf den der Alarm zuletzt gestellt wurde. AlarmManager lässt sich nicht fragen, ob
    // ein Alarm noch aussteht; ohne diese Merkhilfe hat jede Selbstheilung den Alarm um 15 Min nach
    // hinten geschoben — am wachen Gerät (isRequirementMet bei jeder Auswertung) feuerte er so nie.
    private const val KEY_HEARTBEAT_AT = "heartbeat_at"

    private fun heartbeatPendingIntent(context: Context): PendingIntent {
        val intent = Intent(context.applicationContext, PresenceAlarmReceiver::class.java)
            .setAction(PresenceAlarmReceiver.ACTION_PRESENCE_HEARTBEAT)
        return PendingIntent.getBroadcast(
            context.applicationContext,
            HEARTBEAT_REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    /**
     * Setzt den nächsten Heartbeat auf jetzt + 15 Min — aber nur, wenn keiner aussteht. Als
     * „ausstehend“ gilt ein Alarm, dessen Termin höchstens ein Intervall zurückliegt (im Doze
     * feuert er etwas später). Liegt der Termin weiter zurück, ist der Alarm verloren gegangen
     * und wird neu gesetzt. [force] setzt immer neu: für den Receiver selbst und nach Boot/Update,
     * wo der gemerkte Termin nichts über den tatsächlichen Alarm sagt.
     */
    fun scheduleHeartbeat(context: Context, force: Boolean = false) {
        // Nur sinnvoll, wenn überhaupt etwas aufzufrischen ist.
        if (hasNothingToRefresh(context)) return
        val now = System.currentTimeMillis()
        val prefs = prefs(context)
        if (!force) {
            val scheduledAt = prefs.getLong(KEY_HEARTBEAT_AT, 0L)
            if (scheduledAt > 0L && now < scheduledAt + HEARTBEAT_INTERVAL_MS) return
        }
        val triggerAt = now + HEARTBEAT_INTERVAL_MS
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, heartbeatPendingIntent(context))
        prefs.edit { putLong(KEY_HEARTBEAT_AT, triggerAt) }
    }

    fun cancelHeartbeat(context: Context) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        am.cancel(heartbeatPendingIntent(context))
        prefs(context).edit { remove(KEY_HEARTBEAT_AT) }
    }

    /** `true`, wenn weder eine Bedingung noch ein Target registriert ist — dann kann der Alarm weg. */
    fun hasNothingToRefresh(context: Context): Boolean =
        presenceIds(context).isEmpty() && targetIds(context).isEmpty()

    // Register der aktiven Anwesenheits-Requirement-IDs. Nötig, weil der Target-Update-Broadcast
    // (der von Smartspacer getaktete Auslöser) nur Target-IDs kennt — so können wir beim selben
    // Weckruf auch die Requirements auffrischen. Als \n-getrennter String abgelegt (IDs sind UUIDs),
    // um StringSet-Eigenheiten von EncryptedSharedPreferences zu vermeiden.
    private const val KEY_PRESENCE_IDS = "presence_ids"

    // Dasselbe für die Sensor-Targets: Der Doze-Heartbeat läuft außerhalb von Smartspacer und
    // erfährt sonst nicht, welche Targets es überhaupt gibt.
    private const val KEY_TARGET_IDS = "target_ids"

    fun presenceIds(context: Context): Set<String> = ids(context, KEY_PRESENCE_IDS)

    fun addPresenceId(context: Context, smartspacerId: String) =
        addId(context, KEY_PRESENCE_IDS, smartspacerId)

    fun removePresenceId(context: Context, smartspacerId: String) =
        removeId(context, KEY_PRESENCE_IDS, smartspacerId)

    fun targetIds(context: Context): Set<String> = ids(context, KEY_TARGET_IDS)

    fun addTargetId(context: Context, smartspacerId: String) =
        addId(context, KEY_TARGET_IDS, smartspacerId)

    fun removeTargetId(context: Context, smartspacerId: String) =
        removeId(context, KEY_TARGET_IDS, smartspacerId)

    private fun ids(context: Context, key: String): Set<String> =
        prefs(context).getString(key, null)
            ?.split("\n")?.filter { it.isNotBlank() }?.toSet() ?: emptySet()

    private fun addId(context: Context, key: String, smartspacerId: String) {
        val ids = ids(context, key)
        // Die Provider melden sich bei jeder Auswertung selbstheilend an — steht die ID schon drin,
        // gar nicht erst schreiben.
        if (smartspacerId in ids) return
        prefs(context).edit { putString(key, (ids + smartspacerId).joinToString("\n")) }
    }

    private fun removeId(context: Context, key: String, smartspacerId: String) {
        val ids = ids(context, key)
        if (smartspacerId !in ids) return
        prefs(context).edit { putString(key, (ids - smartspacerId).joinToString("\n")) }
    }
}
