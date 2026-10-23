package com.metrolist.music.ui.component

import androidx.activity.ComponentActivity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

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
                } catch (_: Exception) {
                    status.text="Keystore non disponibile. Nessuna associazione eseguita."
                    copyButton.isEnabled=false
                    shareButton.isEnabled=false
                }
            }
        }
        button("Chiudi senza attivare sincronizzazione") { finish() }
    }
}
