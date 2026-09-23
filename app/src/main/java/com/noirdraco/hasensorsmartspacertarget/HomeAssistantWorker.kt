package com.noirdraco.hasensorsmartspacertarget

import android.content.Context
import androidx.work.WorkerParameters
import com.kieronquinn.app.smartspacer.sdk.provider.SmartspacerTargetProvider

/**
 * Abruf fürs Sensor-Target. Benachrichtigt Smartspacer nach **jedem** Lauf, auch bei Fehlern —
 * so erscheint der erste Wert bzw. verschwindet der „Lädt …“-Platzhalter zuverlässig.
 */
class HomeAssistantWorker(
    appContext: Context,
    params: WorkerParameters
) : HaRefreshWorker(appContext, params) {

    override val kind = "target"

    override fun onFinished(smartspacerId: String) {
        SmartspacerTargetProvider.notifyChange(
            applicationContext, HomeAssistantTarget::class.java, smartspacerId
        )
    }
}
