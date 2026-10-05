package com.opentune.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.view.KeyEvent
import android.view.View
import android.widget.RemoteViews
import androidx.annotation.OptIn
import androidx.core.content.edit
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaButtonReceiver
import coil3.imageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.toBitmap
import com.opentune.MainActivity
import com.opentune.R
import com.opentune.data.library.LibraryStore
import com.opentune.data.model.Song
import com.opentune.data.subsonic.Subsonic
import com.opentune.playback.toSong
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * The home-screen widget: cover, title and artist, previous / play-pause /
 * next, and the heart.
 *
 * The playback buttons are media-button events sent through Media3's
 * [MediaButtonReceiver], which starts the player service if it isn't
 * running (resuming the saved queue), the same path a Bluetooth headset's
 * buttons take. The heart goes straight to the library. The last song shown
 * is kept, cover included, so the widget isn't blank after a restart.
 */
@OptIn(UnstableApi::class)
class NowPlayingWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        render(context, load(context))
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action != ACTION_LIKE) return
        val state = load(context)
        val id = state.videoId ?: return
        val song = Song(id, state.title, state.artist, state.thumbnailUrl)
        LibraryStore.setLiked(song, !LibraryStore.isLiked(id))
        render(context, state.copy(liked = LibraryStore.isLiked(id)))
    }

    private data class State(
        val videoId: String? = null,
        val title: String = "",
        val artist: String = "",
        val thumbnailUrl: String? = null,
        val playing: Boolean = false,
        val liked: Boolean = false,
    )

    companion object {
        private const val ACTION_LIKE = "com.opentune.widget.LIKE"
        private const val PREFS = "widget"
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        private var last: State? = null
        private var coverFor: String? = null
        private var coverJob: Job? = null

        /** Brings every placed widget up to date with [player]; cheap when nothing changed. */
        fun refresh(context: Context, player: Player?) {
            val item = player?.currentMediaItem
            val song = item?.toSong()
            val state = State(
                videoId = song?.videoId,
                title = song?.title.orEmpty(),
                artist = song?.artist.orEmpty(),
                thumbnailUrl = song?.thumbnailUrl,
                playing = player?.isPlaying == true,
                liked = song?.videoId?.let(LibraryStore::isLiked) == true,
            )
            if (state == last) return
            last = state
            save(context, state)
            render(context, state)
            if (state.thumbnailUrl != coverFor) {
                coverFor = state.thumbnailUrl
                loadCover(context.applicationContext, state)
            }
        }

        /** The player has gone: keep the song shown, with Play rather than Pause. */
        fun stopped(context: Context) {
            val state = (last ?: load(context)).copy(playing = false)
            last = state
            render(context, state)
        }

        private fun loadCover(context: Context, state: State) {
            coverJob?.cancel()
            val file = coverFile(context)
            val url = Subsonic.resolveCover(state.thumbnailUrl)
            if (url == null) {
                file.delete()
                render(context, state)
                return
            }
            coverJob = scope.launch {
                val request = ImageRequest.Builder(context).data(url).size(COVER_PX).allowHardware(false).build()
                val bitmap = (context.imageLoader.execute(request) as? SuccessResult)?.image?.toBitmap() ?: return@launch
                launch(Dispatchers.IO) {
                    runCatching { file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) } }
                }.join()
                render(context, last ?: state)
            }
        }

        private fun render(context: Context, state: State) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, NowPlayingWidget::class.java))
            if (ids.isEmpty()) return
            val views = RemoteViews(context.packageName, R.layout.widget_now_playing)
            val empty = state.videoId == null
            views.setTextViewText(R.id.widget_title, if (empty) context.getString(R.string.widget_nothing_playing) else state.title)
            views.setTextViewText(R.id.widget_artist, if (empty) context.getString(R.string.widget_open) else state.artist)
            val cover = coverFile(context).takeIf { !empty && it.exists() }?.let { BitmapFactory.decodeFile(it.path) }
            if (cover != null) views.setImageViewBitmap(R.id.widget_cover, cover) else views.setImageViewResource(R.id.widget_cover, R.drawable.ic_widget_cover)
            views.setImageViewResource(R.id.widget_play, if (state.playing) R.drawable.ic_widget_pause else R.drawable.ic_widget_play)
            views.setContentDescription(R.id.widget_play, context.getString(if (state.playing) R.string.widget_pause else R.string.widget_play))
            views.setImageViewResource(R.id.widget_like, if (state.liked) R.drawable.ic_widget_heart_filled else R.drawable.ic_widget_heart)
            views.setContentDescription(R.id.widget_like, context.getString(if (state.liked) R.string.widget_unlike else R.string.widget_like))
            views.setViewVisibility(R.id.widget_like, if (empty) View.GONE else View.VISIBLE)

            views.setOnClickPendingIntent(R.id.widget_root, PendingIntent.getActivity(
                context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            ))
            views.setOnClickPendingIntent(R.id.widget_previous, mediaKey(context, KeyEvent.KEYCODE_MEDIA_PREVIOUS))
            views.setOnClickPendingIntent(R.id.widget_play, mediaKey(context, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE))
            views.setOnClickPendingIntent(R.id.widget_next, mediaKey(context, KeyEvent.KEYCODE_MEDIA_NEXT))
            views.setOnClickPendingIntent(R.id.widget_like, PendingIntent.getBroadcast(
                context, 1, Intent(context, NowPlayingWidget::class.java).setAction(ACTION_LIKE),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            ))
            manager.updateAppWidget(ids, views)
        }

        private fun mediaKey(context: Context, keyCode: Int): PendingIntent {
            val intent = Intent(Intent.ACTION_MEDIA_BUTTON)
                .setComponent(ComponentName(context, MediaButtonReceiver::class.java))
                .putExtra(Intent.EXTRA_KEY_EVENT, KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
            return PendingIntent.getBroadcast(context, keyCode, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }

        private fun coverFile(context: Context) = File(context.cacheDir, "widget-cover.jpg")

        private fun save(context: Context, s: State) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit {
            putString("id", s.videoId)
            putString("title", s.title)
            putString("artist", s.artist)
            putString("thumb", s.thumbnailUrl)
            putBoolean("liked", s.liked)
        }

        private fun load(context: Context): State {
            val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val id = p.getString("id", null)
            return State(id, p.getString("title", "").orEmpty(), p.getString("artist", "").orEmpty(), p.getString("thumb", null),
                playing = last?.playing == true, liked = id?.let(LibraryStore::isLiked) == true)
        }

        private const val COVER_PX = 256
    }
}
