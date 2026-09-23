package com.noirdraco.hasensorsmartspacertarget

import android.content.Context
import android.content.Intent
import com.kieronquinn.app.smartspacer.sdk.model.CompatibilityState
import com.kieronquinn.app.smartspacer.sdk.provider.SmartspacerRequirementProvider
import android.graphics.drawable.Icon as AndroidIcon

/**
 * Bedingung (Requirement) für Smartspacer: erfüllt, wenn eine Home-Assistant-Anwesenheitsentität
 * (z. B. `person.thomas` oder `device_tracker.…`) den Zustand `home` hat.
 *
 * Der Nutzer kann diese Bedingung in Smartspacer an ein Target/Complication hängen und dort auch
 * invertieren ("nur wenn ich unterwegs bin").
 */
class HomePresenceRequirement : SmartspacerRequirementProvider() {

    override fun isRequirementMet(smartspacerId: String): Boolean {
        val context = provideContext()
        val settings = HomeAssistantPrefs.loadSettings(context, smartspacerId)
        val value = HomeAssistantPrefs.loadLastValue(context, smartspacerId)

        // isRequirementMet darf nicht blockieren -> hier nur den Cache lesen. Ist der Cache
        // veraltet oder leer, wird ein Abruf im Hintergrund angestoßen; der Worker meldet per
        // notifyChange zurück, sobald sich der Zustand geändert hat.
        if (settings != null) {
            // Selbstheilung: Registrierung fürs Auffrischen per Heartbeat und Target-Update-Broadcast
            // sicherstellen (auch für Instanzen, die vor dieser Änderung eingerichtet wurden) und den
            // Heartbeat setzen, falls keiner aussteht. Alte periodische Jobs einmalig abräumen.
            HomeAssistantPrefs.addPresenceId(context, smartspacerId)
            HomeAssistantPrefs.scheduleHeartbeat(context)
            HomeAssistantPrefs.cancelLegacyPeriodicRefreshes(context)
            val stale = value == null ||
                System.currentTimeMillis() - value.timestamp > REFRESH_THRESHOLD_MS
            if (stale) {
                HomeAssistantPrefs.enqueuePresenceRefresh(
                    context, smartspacerId, RefreshLog.TRIGGER_ON_DEMAND
                )
            }
        }

        return value != null && value.state.trim().equals(STATE_HOME, ignoreCase = true)
    }

    override fun getConfig(smartspacerId: String?): Config {
        val context = provideContext()
        return Config(
            label = context.getString(R.string.requirement_label),
            description = context.getString(R.string.requirement_description),
            icon = AndroidIcon.createWithResource(context, R.drawable.ic_home_assistant),
            allowAddingMoreThanOnce = true,
            setupActivity = presenceSetupIntent(context),
            configActivity = presenceSetupIntent(context),
            compatibilityState = CompatibilityState.Compatible
        )
    }

    override fun onProviderRemoved(smartspacerId: String) {
        val context = provideContext()
        HomeAssistantPrefs.removePresenceId(context, smartspacerId)
        HomeAssistantPrefs.cancelPresenceRefresh(context, smartspacerId)
        HomeAssistantPrefs.cancelPresencePeriodicRefresh(context, smartspacerId)
        // Hängt nichts mehr am Heartbeat (keine Bedingung, kein Target), den Alarm einstellen.
        if (HomeAssistantPrefs.hasNothingToRefresh(context)) {
            HomeAssistantPrefs.cancelHeartbeat(context)
        }
        HomeAssistantPrefs.clear(context, smartspacerId)
    }

    private fun presenceSetupIntent(context: Context) =
        Intent(context, SetupActivity::class.java)
            .putExtra(SetupActivity.EXTRA_MODE, SetupActivity.MODE_PRESENCE)

    companion object {
        private const val STATE_HOME = "home"

        // Ab dieser Cache-Alter wird bei einer Auswertung ein Hintergrund-Refresh angestoßen.
        private const val REFRESH_THRESHOLD_MS = 2 * 60 * 1000L
    }
}
