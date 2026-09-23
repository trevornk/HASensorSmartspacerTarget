package com.noirdraco.hasensorsmartspacertarget

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Doze-fester Heartbeat für Anwesenheits-Bedingungen und Sensor-Targets. Wird von
 * [HomeAssistantPrefs.scheduleHeartbeat] per AlarmManager (`setAndAllowWhileIdle`) getaktet —
 * anders als WorkManager-Periodic und der Update-Broadcast von Smartspacer feuert das auch im
 * Standby/Doze. Bei jedem Feuern: alle registrierten IDs auffrischen (expedited, also mit
 * Netzzugriff) und den nächsten Alarm setzen (die Idle-Variante ist ein Einzel-Alarm).
 *
 * Der Klassenname stammt aus der Zeit, als nur die Anwesenheit daran hing; er bleibt, weil ein
 * umbenannter Receiver die bereits eingeplanten Alarme ins Leere laufen lassen würde.
 */
class PresenceAlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_PRESENCE_HEARTBEAT) return
        RefreshLog.log(context, "heartbeat")
        HomeAssistantPrefs.refreshAll(context, RefreshLog.TRIGGER_HEARTBEAT)
        // Nächsten Heartbeat setzen (setAndAllowWhileIdle wiederholt nicht von selbst). force: der
        // gemerkte Termin ist gerade der, der eben gefeuert hat.
        HomeAssistantPrefs.scheduleHeartbeat(context, force = true)
    }

    companion object {
        const val ACTION_PRESENCE_HEARTBEAT = "com.noirdraco.hasensor.PRESENCE_HEARTBEAT"
    }
}
