package com.opentune

import android.app.Application
import com.opentune.data.history.History
import com.opentune.data.settings.AppSettings

class OpenTuneApp : Application() {
    override fun onCreate() {
        super.onCreate()
        AppSettings.init(this)
        History.init(this)
    }
}
