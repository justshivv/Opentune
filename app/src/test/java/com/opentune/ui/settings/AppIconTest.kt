package com.opentune.ui.settings

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class AppIconTest {
    @Test fun changingIconsKeepsOneLauncherAndTheRealActivityEnabled() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val pm = context.packageManager
        for (icon in AppIcon.entries + AppIcon.Classic) {
            selectAppIcon(context, icon)
            assertEquals(icon, AppIcon.selected(context))
            val launchers = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setPackage(context.packageName), 0)
            assertEquals(1, launchers.size)
            assertEquals(icon.component(context).className, launchers.single().activityInfo.name)
            assertNotEquals(PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                pm.getComponentEnabledSetting(ComponentName(context.packageName, "com.opentune.MainActivity")))
        }
    }
}
