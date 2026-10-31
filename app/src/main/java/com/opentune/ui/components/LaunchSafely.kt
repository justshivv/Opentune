package com.opentune.ui.components

import android.content.Context
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher

/**
 * Launches a system screen or file picker. Some devices have no app for it
 * (TVs, cut-down phones, a disabled Files app), and launching then throws
 * ActivityNotFoundException; here it says so instead of closing the app.
 */
fun <I> ActivityResultLauncher<I>.launchSafely(context: Context, input: I) {
    runCatching { launch(input) }.onFailure {
        Toast.makeText(context, "No app on this device can open that", Toast.LENGTH_SHORT).show()
    }
}
