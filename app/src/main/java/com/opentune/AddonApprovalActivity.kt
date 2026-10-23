package com.opentune

import android.app.AlertDialog
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.opentune.data.addons.FirstPartyAddonAuth
import kotlinx.coroutines.launch

/** An explicit native approval; opening a link never authorizes a browser. */
class AddonApprovalActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        FirstPartyAddonAuth.init(this)
        val uri = intent?.data
        val id = uri?.getQueryParameter("id").orEmpty()
        if (uri?.scheme != "opentune" || uri.host != "addon-approve" || !id.matches(Regex("[A-Za-z0-9_-]{43}"))) { finish(); return }
        val waiting = AlertDialog.Builder(this).setTitle("Verify OpenTune").setMessage("Checking this device and browser request…")
            .setNegativeButton("Cancel") { _, _ -> finish() }.setOnCancelListener { finish() }.show()
        lifecycleScope.launch {
            try {
                val info = FirstPartyAddonAuth.pairingInfo(id)
                if (isFinishing) return@launch
                waiting.dismiss()
                if (info.optBoolean("approved")) { showResult("This browser request was already approved."); return@launch }
                val dialog = AlertDialog.Builder(this@AddonApprovalActivity).setTitle("Allow OpenTune web player?")
                    .setMessage("${info.getString("origin")}\n\nCode: ${info.getString("code")}\n\nOnly allow if you started this request and this exact code is visible in your web player. Access lasts up to 12 hours.")
                    .setNegativeButton("Deny") { _, _ -> finish() }.setPositiveButton("Allow", null)
                    .setOnCancelListener { finish() }.create()
                dialog.setOnShowListener {
                    dialog.getButton(AlertDialog.BUTTON_POSITIVE).apply {
                        filterTouchesWhenObscured = true
                        setOnClickListener {
                            isEnabled = false
                            lifecycleScope.launch {
                                try { FirstPartyAddonAuth.approveBrowser(id); dialog.dismiss(); showResult("Browser approved. Return to the web player.") }
                                catch (e: Exception) { dialog.dismiss(); showResult(e.message ?: "Approval failed") }
                            }
                        }
                    }
                }
                dialog.show()
            } catch (e: Exception) { waiting.dismiss(); if (!isFinishing) showResult(e.message ?: "Verification failed") }
        }
    }
    private fun showResult(message: String) {
        AlertDialog.Builder(this).setTitle("OpenTune verification").setMessage(message)
            .setPositiveButton("Done") { _, _ -> finish() }.setOnCancelListener { finish() }.show()
    }
}
