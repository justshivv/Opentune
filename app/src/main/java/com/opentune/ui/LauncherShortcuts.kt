package com.opentune.ui

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import com.opentune.MainActivity
import com.opentune.i18n.Tr
import com.opentune.ui.theme.AppIcons

/**
 * The shortcuts a long press on the app's icon offers: shuffle Liked Music, Search, find out
 * what's playing, shuffle downloads. Published from code rather than a shortcuts.xml so their
 * labels come in the app's own language, and again on every start, since the launcher icon is
 * one of several aliases ([com.opentune.ui.settings.AppIconDialog]) and a shortcut belongs to one.
 */
object LauncherShortcuts {
    private const val TAG = "LauncherShortcuts"

    private data class Entry(val id: String, val action: String, val short: String, val long: String, val icon: Int)

    private val entries = listOf(
        Entry("shuffle-liked", Links.ACTION_SHUFFLE_LIKED, "Shuffle liked", "Shuffle Liked Music", AppIcons.Favorite),
        Entry("search", Links.ACTION_SEARCH, "Search", "Search", AppIcons.Search),
        Entry("recognize", com.opentune.widget.RecognizeLaunch.ACTION, "Recognize", "What's this song?", AppIcons.Mic),
        Entry("shuffle-downloads", Links.ACTION_SHUFFLE_DOWNLOADS, "Shuffle downloads", "Shuffle downloaded songs", AppIcons.DownloadDone),
    )

    fun publish(context: Context) {
        runCatching {
            val app = context.applicationContext
            val launcher = launcherActivity(app)
            val shortcuts = entries.mapIndexed { rank, e ->
                ShortcutInfoCompat.Builder(app, e.id)
                    .setShortLabel(Tr.t(e.short))
                    .setLongLabel(Tr.t(e.long))
                    .setIcon(IconCompat.createWithResource(app, e.icon))
                    .setIntent(
                        Intent(app, MainActivity::class.java).setAction(e.action)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                    )
                    .setRank(rank)
                    .apply { launcher?.let(::setActivity) }
                    .build()
            }
            ShortcutManagerCompat.setDynamicShortcuts(app, shortcuts)
        }.onFailure { Log.w(TAG, "Couldn't publish launcher shortcuts", it) }
    }

    /** The launcher entry enabled now: whichever icon the user picked. */
    private fun launcherActivity(context: Context): ComponentName? {
        val launch = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setPackage(context.packageName)
        return context.packageManager.queryIntentActivities(launch, 0).firstOrNull()?.activityInfo
            ?.let { ComponentName(it.packageName, it.name) }
    }
}
