package com.ciftsim.mesajlar

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import com.ciftsim.mesajlar.ui.App

class MainActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) handleIntent(intent)
        setContent { App(viewModel) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        AppState.resumed = true
        SmsStore.clearNameCache()
        // Ayarlardan dönüldüğünde rol/izin ve SIM durumu değişmiş olabilir.
        viewModel.refreshStatus()
    }

    override fun onPause() {
        AppState.resumed = false
        super.onPause()
    }

    /** Bildirime dokunma ve sms:/smsto: bağlantıları. */
    private fun handleIntent(intent: Intent?) {
        intent ?: return
        when (intent.action) {
            ACTION_OPEN_CONVERSATION -> viewModel.openConversation(
                intent.getLongExtra(EXTRA_THREAD_ID, -1L),
                intent.getIntExtra(EXTRA_SUB_ID, -1),
                intent.getStringExtra(EXTRA_ADDRESS).orEmpty(),
            )
            Intent.ACTION_SENDTO, Intent.ACTION_SEND, Intent.ACTION_VIEW -> {
                val data = intent.data ?: return
                if (data.scheme !in SMS_SCHEMES) return
                val specific = data.schemeSpecificPart.orEmpty()
                var body = intent.getStringExtra("sms_body")
                    ?: intent.getStringExtra(Intent.EXTRA_TEXT)
                if (body == null && specific.contains('?')) {
                    body = try {
                        Uri.parse("x://x?" + data.encodedSchemeSpecificPart.substringAfter('?'))
                            .getQueryParameter("body")
                    } catch (_: Exception) {
                        null
                    }
                }
                viewModel.openNewMessage(
                    address = specific.substringBefore('?').trim(),
                    body = body.orEmpty(),
                )
            }
        }
    }

    companion object {
        const val ACTION_OPEN_CONVERSATION = "com.ciftsim.mesajlar.OPEN_CONVERSATION"
        const val EXTRA_THREAD_ID = "threadId"
        const val EXTRA_SUB_ID = "subId"
        const val EXTRA_ADDRESS = "address"
        private val SMS_SCHEMES = setOf("sms", "smsto", "mms", "mmsto")
    }
}
