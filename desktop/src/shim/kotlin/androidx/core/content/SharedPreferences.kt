package androidx.core.content

import android.content.SharedPreferences

/** The AndroidX KTX edit helper, on the desktop's file-backed prefs. */
inline fun SharedPreferences.edit(commit: Boolean = false, action: SharedPreferences.Editor.() -> Unit) {
    val editor = edit()
    action(editor)
    if (commit) editor.commit() else editor.apply()
}
