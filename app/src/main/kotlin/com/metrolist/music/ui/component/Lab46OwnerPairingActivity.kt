package com.metrolist.music.ui.component

import androidx.activity.ComponentActivity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Explicit, stand-alone LAB46 public-key export screen.
 *
 * Never opens without the exact owner-facing staging deep link. It does not
 * touch the approved player/navigation UI, activate sync, register a device,
 * access Cloudflare, read user music, or copy/export PRIVATE Keystore keys.
 * Generating and sharing a PUBLIC SPKI key still requires deliberate taps.
 */
internal class Lab46OwnerPairingActivity : ComponentActivity() {
    private var enrollment: Lab46StagingDeviceSigner.Enrollment? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (intent?.action != Intent.ACTION_VIEW ||
            intent?.data?.toString() != "musiclabturbo://lab46/pair") {
            finish()
            return
        }
        val density = resources.displayMetrics.density
        fun dp(amount: Int): Int = (amount * density + 0.5f).toInt()
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20),dp(24),dp(20),dp(24))
        }
        val scroll = ScrollView(this).apply {
            addView(column, ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ))
        }
        setContentView(scroll)

        fun label(value: String, size: Float = 16f): TextView =
            TextView(this).apply {
                text = value
                textSize = size
                setPadding(0,dp(5),0,dp(13))
                setTextIsSelectable(true)
                column.addView(this)
            }
        fun button(textValue: String, action: () -> Unit): Button =
            Button(this).apply {
                text = textValue
                isAllCaps = false
                setOnClickListener { action() }
                column.addView(this)
            }

        label("MusicLab Turbo LAB46 · Associazione dispositivo",22f)
        label(
            "Soltanto ambiente staging. La chiave privata resta protetta nel " +
            "Keystore Android. Nessuna sincronizzazione, modifica Cloudflare " +
            "o autorizzazione server viene effettuata da questa schermata."
        )
        val status = label("Non associato. Premi il pulsante per generare la chiave pubblica.")
        val fingerprint = label("")
        val spki = label("")
        val copyButton=button("Copia chiave PUBBLICA") {
            enrollment?.let { item ->
                val clipboard=getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText(
                    "MusicLab LAB46 public SPKI", item.publicSpkiBase64Url
                ))
                status.text="Chiave pubblica copiata. Confronta sempre l'impronta prima dell'associazione."
            }
        }.apply { isEnabled=false }
        val shareButton=button("Condividi dati PUBBLICI per l'associazione") {
            enrollment?.let { item ->
                val message="MusicLab LAB46 STAGING PUBLIC KEY ONLY\n"+
                    "keyId (SHA256 SPKI): ${item.keyId}\n"+
                    "SPKI base64url: ${item.publicSpkiBase64Url}\n"+
                    "Worker staging: musiclab-turbo-lab46-isolated-staging\n"+
                    "Non include chiavi private né token privilegiati."
                val share=Intent(Intent.ACTION_SEND).apply {
                    type="text/plain"
                    putExtra(Intent.EXTRA_TEXT,message)
                }
                startActivity(Intent.createChooser(share,"Condividi chiave pubblica LAB46"))
            }
        }.apply { isEnabled=false }
        lateinit var probeButton: Button
        button("Genera / recupera chiave pubblica sul telefono") {
            status.text="Lettura Android Keystore in corso..."
            lifecycleScope.launch {
                try {
                    val item=Lab46StagingDeviceSigner.enrollment()
                    enrollment=item
                    fingerprint.text="Impronta pubblica del dispositivo:\n${item.keyId}"
                    spki.text="Chiave pubblica SPKI:\n${item.publicSpkiBase64Url}"
                    status.text="Chiave pubblica pronta. Associazione NON ancora attivata."
                    copyButton.isEnabled=true
                    shareButton.isEnabled=true
                    probeButton.isEnabled=true
                } catch (_: Exception) {
                    status.text="Keystore non disponibile. Nessuna associazione eseguita."
                    copyButton.isEnabled=false
                    shareButton.isEnabled=false
                }
            }
        }
        label("Dopo il deploy autorizzato: verifica la firma senza salvare brani.")
        val originInput=EditText(this).apply {
            hint="https://NOME-WORKER.SUBDOMAIN.workers.dev"
            inputType=android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_VARIATION_URI
            isSingleLine=true
            column.addView(this)
        }
        probeButton=button("Verifica staging: nessuna scrittura Cloud") {
            enrollment?.let { item ->
                status.text="Verifica firma in corso, senza modificare il database..."
                lifecycleScope.launch {
                    val result=Lab46StagingSignedTransport.probePairingNoWrite(
                        originInput.text.toString(), item.keyId
                    )
                    status.text=when(result) {
                        Lab46StagingSignedTransport.PairingProbeResult.VERIFIED_NO_WRITE ->
                            "Firma e staging verificati. Nessuna decisione scritta su D1."
                        Lab46StagingSignedTransport.PairingProbeResult.BAD_ENDPOINT ->
                            "Indirizzo staging non valido: serve HTTPS."
                        Lab46StagingSignedTransport.PairingProbeResult.NOT_PAIRED ->
                            "Dispositivo non ancora associato correttamente."
                        Lab46StagingSignedTransport.PairingProbeResult.NETWORK_FAILED ->
                            "Connessione non disponibile: nessuna decisione modificata."
                        Lab46StagingSignedTransport.PairingProbeResult.NOT_VERIFIED ->
                            "Firma o risposta server non confermata. Nessuna decisione modificata."
                    }
                }
            }
        }.apply { isEnabled=false }
        // The button remains disabled until the owner explicitly requests
        // the public key above; no pairing or network request at startup.
        label("Collaudo reale facoltativo: scegli UNA decisione che hai già approvato o rifiutato. Nessuna sincronizzazione automatica.")
        val choiceStatus = label("Non è stata selezionata alcuna decisione.")
        var chosen: CoverCloudPendingOutbox.PendingChoice? = null
        lateinit var sendButton: Button
        val choicesColumn = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            column.addView(this)
        }
        button("Mostra decisioni già in attesa sul telefono") {
            chosen = null
            sendButton.isEnabled = false
            choiceStatus.text = "Lettura delle sole decisioni locali in corso..."
            lifecycleScope.launch {
                val pending = withContext(Dispatchers.IO) {
                    CoverCloudPendingOutbox.pendingChoices(applicationContext)
                }
                choicesColumn.removeAllViews()
                if (pending.isEmpty()) {
                    choiceStatus.text = "Nessuna decisione locale in attesa. Non è stato creato nessun dato di prova."
                } else {
                    choiceStatus.text = "Tocca una sola decisione per selezionarla. Non sarà inviata finché non confermi."
                    for (choice in pending) {
                        val option = Button(this@Lab46OwnerPairingActivity).apply {
                            isAllCaps = false
                            text = "${choice.title} · ${choice.artist} [${choice.status}]"
                            setOnClickListener {
                                chosen = choice
                                choiceStatus.text = "Scelta: ${choice.title} · ${choice.artist}. Conferma richiesta prima dell'invio."
                                sendButton.isEnabled = enrollment != null
                            }
                        }
                        choicesColumn.addView(option)
                    }
                }
            }
        }
        sendButton = button("Invia UNA decisione scelta e verifica anti-duplicato") {
            val choice = chosen
            val enrolled = enrollment
            if (choice == null || enrolled == null) {
                choiceStatus.text = "Prima recupera la chiave pubblica e scegli una decisione."
            } else {
                AlertDialog.Builder(this)
                    .setTitle("Confermare un solo invio allo staging?")
                    .setMessage(
                        "Decisione già in attesa: ${choice.title} · ${choice.artist}\n" +
                        "Stato: ${choice.status}\n" +
                        "Solo MusicLab D1 staging. Saranno controllate ricevuta e anti-duplicato. " +
                        "Nessuna modifica alle altre decisioni o al player."
                    )
                    .setNegativeButton("Annulla", null)
                    .setPositiveButton("Confermo una sola decisione") { _, _ ->
                        sendButton.isEnabled = false
                        choiceStatus.text = "Invio firmato e verifica della ricevuta in corso..."
                        lifecycleScope.launch {
                            val result = Lab46StagingSignedTransport.sendOneSelectedPending(
                                applicationContext, choice,
                                Lab46StagingSignedTransport.EnrollmentBinding(
                                    originInput.text.toString(), enrolled.keyId, true
                                )
                            )
                            choiceStatus.text = when (result) {
                                Lab46StagingSignedTransport.Result.ACKNOWLEDGED ->
                                    "Ricevuta Cloud confermata e duplicato riutilizzato. Decisione locale sincronizzata."
                                Lab46StagingSignedTransport.Result.LOCAL_ACK_FAILED ->
                                    "Ricevuta Cloud confermata; archivio locale non aggiornato. Non ripetere senza controllo."
                                Lab46StagingSignedTransport.Result.EMPTY,
                                Lab46StagingSignedTransport.Result.INVALID_PENDING ->
                                    "Decisione cambiata o non più disponibile. Nessun nuovo invio confermato."
                                Lab46StagingSignedTransport.Result.BAD_ENDPOINT ->
                                    "Indirizzo non valido: usa solo il Worker staging LAB46."
                                Lab46StagingSignedTransport.Result.NOT_PAIRED ->
                                    "Chiave Android non corrispondente a quella associata."
                                Lab46StagingSignedTransport.Result.NETWORK_FAILED,
                                Lab46StagingSignedTransport.Result.REFUSED_OR_UNCERTAIN ->
                                    "Ricevuta non completamente verificata. Non ripetere prima di un controllo D1."
                            }
                            chosen = null
                            sendButton.isEnabled = false
                            choicesColumn.removeAllViews()
                        }
                    }
                    .show()
            }
        }.apply { isEnabled = false }
        button("Chiudi senza attivare sincronizzazione") { finish() }
    }
}
