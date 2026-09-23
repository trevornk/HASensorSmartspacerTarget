package com.noirdraco.hasensorsmartspacertarget

import android.content.Context
import com.kieronquinn.app.smartspacer.sdk.receivers.SmartspacerTargetUpdateReceiver

class HomeAssistantUpdateReceiver : SmartspacerTargetUpdateReceiver() {

    override fun onRequestSmartspaceTargetUpdate(
        context: Context,
        requestTargets: List<RequestTarget>
    ) {
        val authority = "${context.packageName}.target.homeassistant"
        val targetIds = requestTargets.filter { it.authority == authority }.map { it.smartspacerId }
        targetIds.forEach {
            HomeAssistantPrefs.enqueueRefresh(context, it, RefreshLog.TRIGGER_BROADCAST)
        }

        // Anwesenheits-Requirements bekommen von Smartspacer keinen eigenen Update-Broadcast.
        // Wir nutzen diesen zuverlässigen, von Smartspacer getakteten Weckruf, um auch sie
        // aufzufrischen (deutlich verlässlicher als WorkManager-Periodic im Standby/Doze).
        HomeAssistantPrefs.presenceIds(context).forEach {
            HomeAssistantPrefs.enqueuePresenceRefresh(context, it, RefreshLog.TRIGGER_BROADCAST)
        }
    }
}
