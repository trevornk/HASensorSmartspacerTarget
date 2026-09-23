package com.noirdraco.hasensorsmartspacertarget

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Nach Neustart und App-Update: Heartbeat neu setzen und alles einmal auffrischen.
 *
 * Früher übernahm das der periodische WorkManager-Job (WorkManager stellt ihn nach dem Boot wieder
 * her, der Worker setzte den Alarm neu). Seit der weg ist, braucht es diesen Receiver — sonst hinge
 * eine reine Anwesenheits-Bedingung ohne Target bis zur nächsten Auswertung ohne Takt da.
 * `force`, weil der gemerkte Alarm-Termin den Neustart überlebt, der Alarm selbst aber nicht.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED &&
            intent.action != Intent.ACTION_MY_PACKAGE_REPLACED
        ) return
        RefreshLog.log(context, "boot/update")
        HomeAssistantPrefs.cancelLegacyPeriodicRefreshes(context)
        HomeAssistantPrefs.refreshAll(context, RefreshLog.TRIGGER_BOOT)
        HomeAssistantPrefs.scheduleHeartbeat(context, force = true)
    }
}
