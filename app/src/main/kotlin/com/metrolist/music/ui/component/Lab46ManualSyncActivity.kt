package com.metrolist.music.ui.component

import android.app.AlertDialog
import android.os.Bundle
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * LAB46 only: native owner-initiated manual synchronization.
 * Opening this screen reads LOCAL pending records and an EXISTING Keystore
 * alias only. It never creates keys, sends a request or starts any worker.
 * Exactly one explicit selection AND a separate confirmation are required
 * before using the existing staging-only signed transport.
 */
internal class Lab46ManualSyncActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val density = resources.displayMetrics.density
        fun dp(amount: Int) = (amount * density + 0.5f).toInt()
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(24), dp(20), dp(24))
        }
        setContentView(ScrollView(this).apply {
            addView(column, ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ))
        })
        fun label(value: String, size: Float = 16f): TextView = TextView(this).apply {
            text = value
            textSize = size
            setPadding(0, dp(5), 0, dp(13))
            column.addView(this)
        }
        fun button(value: String, action: () -> Unit): Button = Button(this).apply {
            text = value
            isAllCaps = false
            setOnClickListener { action() }
            column.addView(this)
        }

        label("MusicLab · Sincronizzazione Cloud", 22f)
        label("Solo staging LAB46. Nessun invio automatico. " +
            "Le tue decisioni restano sul telefono fino alla conferma manuale " +
            "di una sola canzone.")
        val deviceStatus = label("Verifico la chiave già associata sul telefono...")
        val choiceStatus = label("Lettura delle decisioni in attesa...")
        val status = label("Non è stata inviata alcuna decisione.")
        val choicesColumn = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            column.addView(this)
        }
        var pairedKeyId: String? = null
        var selected: CoverCloudPendingOutbox.PendingChoice? = null
        var sending = false
        lateinit var sendButton: Button

        val refreshChoices: () -> Unit = {
            if (!sending) {
                selected = null
                sendButton.isEnabled = false
                choiceStatus.text = "Lettura delle decisioni locali..."
                lifecycleScope.launch {
                    val pending = withContext(Dispatchers.IO) {
                        CoverCloudPendingOutbox.pendingChoices(applicationContext)
                    }
                    choicesColumn.removeAllViews()
                    choiceStatus.text = if (pending.isEmpty())
                        "Nessuna decisione in attesa."
                    else
                        "${pending.size} decisioni disponibili. Tocca UNA canzone."
                    pending.forEach { choice ->
                        Button(this@Lab46ManualSyncActivity).apply {
                            isAllCaps = false
                            text = "${choice.title} · ${choice.artist} [${choice.status}]"
                            setOnClickListener {
                                if (!sending) {
                                    selected = choice
                                    choiceStatus.text =
                                        "Selezionata: ${choice.title} · ${choice.artist}"
                                    sendButton.isEnabled = pairedKeyId != null
                                }
                            }
                            choicesColumn.addView(this)
                        }
                    }
                }
            }
        }

        sendButton = button("Sincronizza UNA decisione selezionata") {
            val choice = selected
            val key = pairedKeyId
            if (sending || choice == null || key == null) {
                status.text = "Prima seleziona una canzone e verifica il dispositivo."
            } else {
                AlertDialog.Builder(this)
                    .setTitle("Confermi l'invio di UNA decisione?")
                    .setMessage("${choice.title} · ${choice.artist}\n" +
                        "Stato: ${choice.status}\n\n" +
                        "Solo database staging LAB46. " +
                        "La canzone sarà rimossa dalle attese solo dopo " +
                        "la ricevuta Cloud autenticata e verificata.")
                    .setNegativeButton("Annulla", null)
                    .setPositiveButton("Confermo una decisione") { _, _ ->
                        if (!sending) {
                            sending = true
                            sendButton.isEnabled = false
                            status.text = "Invio firmato e verifica duplicati in corso..."
                            lifecycleScope.launch {
                                val result = Lab46StagingSignedTransport.sendOneSelectedPending(
                                    applicationContext, choice,
                                    Lab46StagingSignedTransport.EnrollmentBinding(
                                        Lab46StagingSignedTransport.STAGING_ORIGIN, key, true
                                    ),
                                )
                                status.text = when (result) {
                                    Lab46StagingSignedTransport.Result.ACKNOWLEDGED ->
                                        "Ricevuta verificata. Decisione sincronizzata, " +
                                            "senza duplicati."
                                    Lab46StagingSignedTransport.Result.LOCAL_ACK_FAILED ->
                                        "Ricevuta Cloud verificata, ma archivio locale " +
                                            "non aggiornato. Non ripetere l'invio."
                                    Lab46StagingSignedTransport.Result.NETWORK_FAILED,
                                    Lab46StagingSignedTransport.Result.REFUSED_OR_UNCERTAIN ->
                                        "Risposta incerta: decisione conservata. " +
                                            "Non ripetere senza controllo D1."
                                    Lab46StagingSignedTransport.Result.BAD_ENDPOINT ->
                                        "Indirizzo staging non verificato: nessun invio."
                                    Lab46StagingSignedTransport.Result.NOT_PAIRED ->
                                        "Dispositivo non associato: nessun invio."
                                    Lab46StagingSignedTransport.Result.INVALID_PENDING,
                                    Lab46StagingSignedTransport.Result.EMPTY ->
                                        "Decisione modificata o non disponibile: " +
                                            "selezione annullata."
                                }
                                sending = false
                                refreshChoices()
                            }
                        }
                    }.show()
            }
        }.apply { isEnabled = false }

        button("Aggiorna decisioni locali") { refreshChoices() }
        button("Chiudi") { finish() }
        refreshChoices()
        lifecycleScope.launch {
            val device = Lab46StagingDeviceSigner.existingEnrollmentOrNull()
            pairedKeyId = device?.keyId?.takeIf {
                it == Lab46StagingSignedTransport.PAIRED_KEY_ID
            }
            deviceStatus.text = if (pairedKeyId != null)
                "Dispositivo già associato e riconosciuto. Nessuna nuova chiave creata."
            else
                "Nessuna chiave staging già associata e verificata su questo telefono. " +
                    "La sincronizzazione resta disattivata."
            sendButton.isEnabled = pairedKeyId != null && selected != null && !sending
        }
    }
}
