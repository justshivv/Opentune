package com.opentune.ui.lossless

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.browser.auth.AuthTabIntent
import androidx.browser.customtabs.CustomTabsClient
import androidx.browser.customtabs.CustomTabsIntent
import com.opentune.data.lossless.ExtensionSessions
import com.opentune.data.lossless.ProviderPackages

/** Owns the browser result across rotation; no foreign URL scheme is exported. */
class ProviderVerificationActivity : ComponentActivity() {
    private val launcher = AuthTabIntent.registerActivityResultLauncher(this) { result ->
        val provider = intent.getStringExtra(PROVIDER)
        if (provider != null && provider in ProviderPackages.supported && result.resultCode == AuthTabIntent.RESULT_OK) {
            val accepted = result.resultUri?.let {
                ExtensionSessions.forProvider(provider).receiveCallback(it.toString(), browserResult = true)
            } == true
            if (!accepted) Toast.makeText(this, "This result does not match the active verification. Connect again.", Toast.LENGTH_LONG).show()
        }
        finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState != null) return
        val provider = intent.getStringExtra(PROVIDER)
        if (provider == null || provider !in ProviderPackages.supported || ProviderPackages.get(provider) == null) { finish(); return }
        val manager = ExtensionSessions.forProvider(provider)
        val url = manager.pendingUrl() ?: run { finish(); return }
        try {
            val browser = CustomTabsClient.getPackageName(this, null)
            val authUrl = manager.authTabUrl()
            if (browser != null && authUrl != null && CustomTabsClient.isAuthTabSupported(this, browser)) {
                val tab = AuthTabIntent.Builder().build()
                tab.intent.setPackage(browser)
                tab.launch(launcher, Uri.parse(authUrl), "spotiflac")
            } else {
                // Older browsers keep the existing OpenTune callback and explicit paste fallback.
                try { CustomTabsIntent.Builder().setShowTitle(true).build().launchUrl(this, Uri.parse(url)) }
                catch (_: android.content.ActivityNotFoundException) { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
                finish()
            }
        } catch (_: Exception) {
            Toast.makeText(this, "Could not open verification. Update your browser or use Paste browser result.", Toast.LENGTH_LONG).show()
            finish()
        }
    }

    companion object { const val PROVIDER = "provider" }
}
