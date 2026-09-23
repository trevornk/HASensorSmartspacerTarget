package com.noirdraco.hasensorsmartspacertarget

import android.content.Context
import androidx.work.WorkerParameters
import com.kieronquinn.app.smartspacer.sdk.provider.SmartspacerRequirementProvider

/**
 * Holt den Zustand der Anwesenheits-Entität (z. B. `person.thomas`) von Home Assistant und legt
 * ihn im selben Cache wie der Sensor-Target ab (pro smartspacerId). Läuft asynchron, weil
 * [HomePresenceRequirement.isRequirementMet] nicht blockieren darf.
 *
 * Anders als [HomeAssistantWorker] wird [SmartspacerRequirementProvider.notifyChange] nur bei
 * einer tatsächlichen Zustandsänderung ausgelöst — sonst würde jede Auswertung einen neuen
 * Refresh anstoßen und eine Endlosschleife erzeugen.
 */
class HomePresenceWorker(
    appContext: Context,
    params: WorkerParameters
) : HaRefreshWorker(appContext, params) {

    override val kind = "presence"

    override fun onValueStored(smartspacerId: String, changed: Boolean) {
        if (changed) {
            SmartspacerRequirementProvider.notifyChange(
                applicationContext, HomePresenceRequirement::class.java, smartspacerId
            )
        }
    }
}
