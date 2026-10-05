package com.opentune.data

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.core.content.IntentCompat
import androidx.core.net.toUri
import com.opentune.data.DebugLog as Log
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.Request

/**
 * Downloads a release's APK and hands it to Android's package installer,
 * which shows its own confirmation. The file is checked against the
 * release's SHA256SUMS first. Android itself refuses an APK signed with a
 * different key than the installed app.
 */
object Updater {
    private const val TAG = "Updater"

    /** Whether Android lets OpenTune install apps; if not, [allowInstallsIntent] opens the switch. */
    fun canInstall(context: Context) = context.packageManager.canRequestPackageInstalls()

    fun allowInstallsIntent(context: Context) =
        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, "package:${context.packageName}".toUri())

    /** Downloads [release]'s APK into the cache, reporting 0..1 to [onProgress]; checks it and returns the file. */
    suspend fun download(context: Context, release: UpdateCheck.Release, onProgress: (Float) -> Unit): File = withContext(Dispatchers.IO) {
        val apk = release.apk ?: throw IOException("This release has no APK for this phone")
        val dir = File(context.cacheDir, "updates").apply { deleteRecursively(); mkdirs() }
        val target = File(dir, apk.name)
        val digest = MessageDigest.getInstance("SHA-256")
        Http.client.newCall(Request.Builder().url(apk.url).build()).execute().use { r ->
            if (!r.isSuccessful) throw IOException("GitHub answered ${r.code}")
            val body = r.body ?: throw IOException("Empty download")
            val total = body.contentLength().takeIf { it > 0 } ?: apk.bytes
            body.byteStream().use { input ->
                target.outputStream().use { out ->
                    val buffer = ByteArray(64 * 1024)
                    var done = 0L
                    while (true) {
                        ensureActive()
                        val n = input.read(buffer)
                        if (n < 0) break
                        out.write(buffer, 0, n)
                        digest.update(buffer, 0, n)
                        done += n
                        if (total > 0) onProgress((done.toFloat() / total).coerceIn(0f, 1f))
                    }
                }
            }
        }
        val actual = digest.digest().joinToString("") { "%02x".format(it) }
        release.sums?.let { sums ->
            val listed = Http.client.newCall(Request.Builder().url(sums.url).build()).execute().use { r ->
                if (!r.isSuccessful) throw IOException("Couldn't fetch the checksums (${r.code})")
                expectedSum(r.body?.string().orEmpty(), apk.name)
            }
            if (listed != null && !listed.equals(actual, ignoreCase = true)) {
                target.delete()
                throw IOException("The download is damaged (checksum mismatch)")
            }
        }
        target
    }

    /** The hash `sha256sum` lists for [fileName]. */
    internal fun expectedSum(sums: String, fileName: String): String? =
        sums.lineSequence().map { it.trim().split(Regex("\\s+"), limit = 2) }
            .firstOrNull { it.size == 2 && it[1].removePrefix("*") == fileName }
            ?.get(0)

    /** Starts installing [apk]; Android asks the user to confirm. */
    fun install(context: Context, apk: File) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        val id = installer.createSession(params)
        installer.openSession(id).use { session ->
            session.openWrite("base.apk", 0, apk.length()).use { out ->
                apk.inputStream().use { it.copyTo(out) }
                session.fsync(out)
            }
            val status = PendingIntent.getBroadcast(
                context,
                id,
                Intent(context, StatusReceiver::class.java),
                PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            session.commit(status.intentSender)
        }
    }

    /** The installer's answers: the confirmation to show, or why it failed. */
    class StatusReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
                PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                    IntentCompat.getParcelableExtra(intent, Intent.EXTRA_INTENT, Intent::class.java)
                        ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        ?.let(context::startActivity)
                }
                PackageInstaller.STATUS_SUCCESS -> Unit // the app restarts as the new version
                else -> {
                    val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
                    Log.w(TAG, "Install failed: $status $message")
                    val text = when (status) {
                        PackageInstaller.STATUS_FAILURE_CONFLICT, PackageInstaller.STATUS_FAILURE_INCOMPATIBLE ->
                            "This update is signed differently from the installed app. Uninstall OpenTune, then install the update."
                        PackageInstaller.STATUS_FAILURE_ABORTED -> "Update cancelled"
                        PackageInstaller.STATUS_FAILURE_STORAGE -> "Not enough space for the update"
                        else -> "Update failed${message?.let { ": $it" } ?: ""}"
                    }
                    Toast.makeText(context, text, Toast.LENGTH_LONG).show()
                }
            }
        }
    }
}
