package com.noirdraco.hasensorsmartspacertarget

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
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

        // Ab Android 17 (targetSdk 37) ist der Zugriff aufs lokale Netz (LAN) ohne diese
        // Laufzeit-Berechtigung blockiert; TCP-Verbindungen laufen dann in einen Timeout.
        private const val REQUEST_LOCAL_NETWORK = 1
    }

    // Läuft weiter, sobald der Nutzer die Berechtigung für das lokale Netz entschieden hat
    private var pendingAfterLocalNetwork: ((granted: Boolean) -> Unit)? = null

    private fun hasLocalNetworkPermission() =
        checkSelfPermission(Manifest.permission.ACCESS_LOCAL_NETWORK) ==
            PackageManager.PERMISSION_GRANTED

    /** Ruft [action] mit dem Ergebnis auf; fragt bei Bedarf zuerst nach der Berechtigung. */
    private fun withLocalNetwork(action: (granted: Boolean) -> Unit) {
        if (hasLocalNetworkPermission()) {
            action(true)
        } else {
            pendingAfterLocalNetwork = action
            requestPermissions(
                arrayOf(Manifest.permission.ACCESS_LOCAL_NETWORK), REQUEST_LOCAL_NETWORK
            )
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQUEST_LOCAL_NETWORK) return
        val action = pendingAfterLocalNetwork
        pendingAfterLocalNetwork = null
        val granted = grantResults.isNotEmpty() &&
            grantResults[0] == PackageManager.PERMISSION_GRANTED
        if (!granted) {
            findViewById<TextView>(R.id.text_status)
                ?.setText(R.string.setup_error_local_network_denied)
            Toast.makeText(this, R.string.setup_error_local_network_denied, Toast.LENGTH_LONG)
                .show()
        }
        action?.invoke(granted)
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

        fun runTest(settings: SensorSettings) {
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

        testButton.setOnClickListener {
            val settings = currentInput() ?: return@setOnClickListener
            withLocalNetwork { granted -> if (granted) runTest(settings) }
        }

        fun saveAndFinish(settings: SensorSettings) {
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

        findViewById<Button>(R.id.button_save).setOnClickListener {
            val settings = currentInput() ?: return@setOnClickListener
            // Auch ohne vorherigen Test nach der Berechtigung fragen, sonst scheitern die
            // Hintergrund-Abrufe im LAN stillschweigend. Gespeichert wird in jedem Fall — erst
            // nach der Entscheidung, damit der Dialog nicht mit der Activity verschwindet.
            withLocalNetwork { saveAndFinish(settings) }
        }
    }
}
