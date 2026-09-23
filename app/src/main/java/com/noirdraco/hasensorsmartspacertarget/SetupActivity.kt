package com.noirdraco.hasensorsmartspacertarget

import android.app.Activity
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import com.kieronquinn.app.smartspacer.sdk.SmartspacerConstants

/**
 * Dient sowohl als setupActivity (erstes Einrichten) als auch als configActivity (späteres
 * Bearbeiten) — Smartspacer liefert die smartspacerId als Intent-Extra mit.
 */
class SetupActivity : Activity() {

    companion object {
        // Unterscheidet, ob diese Seite die Sensor-Anzeige (Target) oder die Anwesenheits-
        // Bedingung (Requirement) einrichtet. Der Wert kommt aus dem setup-/configActivity-Intent.
        const val EXTRA_MODE = "setup_mode"
        const val MODE_SENSOR = "sensor"
        const val MODE_PRESENCE = "presence"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val smartspacerId = intent.getStringExtra(SmartspacerConstants.EXTRA_SMARTSPACER_ID)
        if (smartspacerId == null) {
            finish()
            return
        }
        val isPresence = intent.getStringExtra(EXTRA_MODE) == MODE_PRESENCE
        setContentView(R.layout.activity_setup)

        val baseUrlField = findViewById<EditText>(R.id.input_base_url)
        val tokenField = findViewById<EditText>(R.id.input_token)
        val entityIdField = findViewById<EditText>(R.id.input_entity_id)
        val showOnlyIfField = findViewById<EditText>(R.id.input_show_only_if)
        val statusView = findViewById<TextView>(R.id.text_status)
        val testButton = findViewById<Button>(R.id.button_test)

        if (isPresence) {
            setTitle(R.string.requirement_setup_title)
            findViewById<TextView>(R.id.text_title).setText(R.string.requirement_setup_title)
            findViewById<TextView>(R.id.text_intro).setText(R.string.requirement_setup_intro)
            entityIdField.setHint(R.string.requirement_entity_hint)
            findViewById<TextView>(R.id.text_entity_help).setText(R.string.requirement_entity_help)
            // Der Anzeige-Filter gilt nur fürs Target — die Bedingung wird in Smartspacer ohnehin
            // an ein Target gehängt und kann dort invertiert werden.
            findViewById<View>(R.id.group_show_only_if).visibility = View.GONE
        }

        HomeAssistantPrefs.loadSettings(this, smartspacerId)?.let {
            baseUrlField.setText(it.baseUrl)
            tokenField.setText(it.token)
            entityIdField.setText(it.entityId)
            showOnlyIfField.setText(it.showOnlyIf)
        }

        fun currentInput(): SensorSettings? {
            val baseUrl = baseUrlField.text.toString().trim()
            val token = tokenField.text.toString().trim()
            val entityId = entityIdField.text.toString().trim()
            if (baseUrl.isEmpty() || token.isEmpty() || entityId.isEmpty()) {
                Toast.makeText(this, R.string.setup_error_empty, Toast.LENGTH_SHORT).show()
                return null
            }
            // Der Anzeige-Filter ist optional (leer = immer anzeigen) und im Presence-Modus ausgeblendet
            val showOnlyIf = if (isPresence) "" else showOnlyIfField.text.toString().trim()
            return SensorSettings(baseUrl, token, entityId, showOnlyIf)
        }

        testButton.setOnClickListener {
            val settings = currentInput() ?: return@setOnClickListener
            testButton.isEnabled = false
            statusView.setText(R.string.setup_test_running)
            // Netzwerk-Call gehört nicht auf den Main-Thread
            Thread {
                val result = HomeAssistantApi.fetch(settings)
                runOnUiThread {
                    testButton.isEnabled = true
                    statusView.text = when (result) {
                        is FetchResult.Success -> getString(
                            R.string.setup_test_ok,
                            result.value.friendlyName,
                            listOf(result.value.state, result.value.unit)
                                .filter { it.isNotBlank() }.joinToString(" ")
                        )
                        is FetchResult.HttpError -> getString(R.string.setup_test_http, result.code)
                        is FetchResult.NetworkError -> getString(
                            R.string.setup_test_network, result.message
                        )
                    }
                }
            }.start()
        }

        findViewById<Button>(R.id.button_save).setOnClickListener {
            val settings = currentInput() ?: return@setOnClickListener
            HomeAssistantPrefs.saveSettings(this, smartspacerId, settings)
            // Sofort abrufen, damit der erste Wert nicht erst mit dem nächsten Heartbeat erscheint
            if (isPresence) {
                // Fürs Auffrischen per Heartbeat und Target-Update-Broadcast registrieren
                HomeAssistantPrefs.addPresenceId(this, smartspacerId)
                HomeAssistantPrefs.enqueuePresenceRefresh(
                    this, smartspacerId, RefreshLog.TRIGGER_SETUP, replace = true
                )
                // Doze-fester Heartbeat (AlarmManager) — primärer autonomer Takt im Standby
                HomeAssistantPrefs.scheduleHeartbeat(this)
            } else {
                // Fürs Auffrischen per Doze-Heartbeat registrieren, bevor der Alarm gesetzt wird
                HomeAssistantPrefs.addTargetId(this, smartspacerId)
                HomeAssistantPrefs.enqueueRefresh(
                    this, smartspacerId, RefreshLog.TRIGGER_SETUP, replace = true
                )
                HomeAssistantPrefs.scheduleHeartbeat(this)
            }
            setResult(RESULT_OK)
            finish()
        }
    }
}
