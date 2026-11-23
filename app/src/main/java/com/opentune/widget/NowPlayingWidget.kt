package com.opentune.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.os.Bundle
import android.util.SizeF
import android.view.KeyEvent
import android.view.View
import android.widget.RemoteViews
import androidx.annotation.OptIn
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import coil3.imageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.toBitmap
import com.opentune.MainActivity
import com.opentune.R
import com.opentune.data.library.LibraryStore
import com.opentune.data.model.Song
import com.opentune.data.model.artworkAt
import com.opentune.data.lyrics.LyricLine
import com.opentune.data.lyrics.readsRightToLeft
import com.opentune.data.settings.AppSettings
import com.opentune.data.subsonic.Subsonic
import com.opentune.lyrics.LyricsNow
import com.opentune.lyrics.LyricsOutlet
import com.opentune.playback.PlaybackService
import com.opentune.playback.toSong
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The home-screen widget: cover, title and artist, previous / play-pause /
 * next, and the heart.
 *
 * The playback buttons are media-button events sent straight to the player
 * service, as Media3's own notification buttons are. Play starts the service
 * in the foreground if it isn't running (resuming the saved queue). They
 * don't go through Media3's MediaButtonReceiver: from Android 8 on it drops
 * every key but play, so Previous and Next did nothing. The heart goes
 * straight to the library. The last song shown is kept, cover included, so
 * the widget isn't blank after a restart.
 *
 * With Lyrics on widgets, the larger ones (Album artwork, Studio, and Now
 * playing two rows tall) show the sung line and the next under the title,
 * from the lyrics the player already has; see [WidgetLyrics].
 */
@OptIn(UnstableApi::class)
open class NowPlayingWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        val state = load(context)
        render(context, state)
        if (state.thumbnailUrl != null && (!coverFile(context).exists() ||
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("cover-thumbnail", null) != state.thumbnailUrl)) {
            coverFor = state.thumbnailUrl
            loadCover(context, state)
        }
    }

    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, id: Int, options: Bundle) {
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
        // The tap may have started the app just for this; keep it alive until the like is saved
        // (the library writes a moment after a change) and sent to YouTube Music.
        val pending = goAsync()
        scope.launch {
            try {
                delay(LIKE_SAVE_MS)
                withContext(Dispatchers.IO) { LibraryStore.flush() }
            } finally {
                pending.finish()
            }
        }
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
        /** The lines shown under the title; only for the song they're from. */
        private var lyrics: LyricsNow? = null
        /** The saved cover, decoded once rather than at every line of lyrics. */
        private var decodedCover: Pair<String, Bitmap>? = null

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
            val url = Subsonic.resolveCover(state.thumbnailUrl.artworkAt(COVER_PX))
            if (url == null) {
                file.delete()
                render(context, state)
                return
            }
            coverJob = scope.launch {
                val request = ImageRequest.Builder(context).data(url).size(COVER_PX).allowHardware(false).build()
                val bitmap = (context.imageLoader.execute(request) as? SuccessResult)?.image?.toBitmap() ?: return@launch
                val temporary = File.createTempFile("widget-cover-", ".jpg", context.cacheDir)
                try {
                    val written = withContext(Dispatchers.IO) {
                        runCatching { temporary.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 92, it) } }.getOrDefault(false)
                    }
                    if (!written || coverFor != state.thumbnailUrl || !temporary.renameTo(file)) return@launch
                    // Match the saved file to its song only after a successful atomic write.
                    context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit {
                        putString("cover-thumbnail", state.thumbnailUrl)
                    }
                    render(context, last ?: state)
                } finally {
                    temporary.delete()
                }
            }
        }

        private fun render(context: Context, state: State, only: Class<out NowPlayingWidget>? = null) {
            val manager = AppWidgetManager.getInstance(context)
            val cover = coverFile(context).takeIf {
                state.videoId != null && it.exists() && state.thumbnailUrl != null &&
                    context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("cover-thumbnail", null) == state.thumbnailUrl
            }?.let { file ->
                val key = "${state.thumbnailUrl}@${file.lastModified()}"
                decodedCover?.takeIf { it.first == key }?.second
                    ?: BitmapFactory.decodeFile(file.path)?.also { decodedCover = key to it }
            }
            val empty = state.videoId == null
            val designs = listOf(
                NowPlayingWidget::class.java to R.layout.widget_now_playing,
                AlbumArtWidget::class.java to R.layout.widget_album_art,
                StudioWidget::class.java to R.layout.widget_studio,
            )
            for ((provider, layout) in designs) {
                if (only != null && provider != only) continue
                val ids = manager.getAppWidgetIds(ComponentName(context, provider))
                if (ids.isEmpty()) continue
                for (id in ids) {
                    val width = manager.getAppWidgetOptions(id).getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 300)
                    val isNowPlaying = provider == NowPlayingWidget::class.java
                    val views = if (isNowPlaying && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        // API 31+: serve a size-keyed map so the launcher picks the right
                        // layout automatically as the user resizes the widget.
                        // ≥ 145dp tall  →  widget_now_playing_tall (2-row: cover + text + controls)
                        // ≥  40dp tall  →  widget_now_playing (1-row compact bar)
                        val compact = RemoteViews(context.packageName, R.layout.widget_now_playing)
                        applyState(compact, context, state, cover, empty,
                            showLike = !empty && width >= 420, showPrevious = width >= 360)
                        val tall = RemoteViews(context.packageName, R.layout.widget_now_playing_tall)
                        applyState(tall, context, state, cover, empty,
                            showLike = !empty, showPrevious = true)
                        applyLyrics(tall, state, titleLines = 1)
                        RemoteViews(mapOf(
                            SizeF(180f, 40f) to compact,
                            SizeF(180f, 145f) to tall,
                        ))
                    } else {
                        // API < 31 or non-NowPlaying providers: single layout as before.
                        val height = manager.getAppWidgetOptions(id).getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 40)
                        val useTall = isNowPlaying && height >= 145
                        val targetLayout = if (useTall) R.layout.widget_now_playing_tall else layout
                        val v = RemoteViews(context.packageName, targetLayout)
                        applyState(v, context, state, cover, empty,
                            // Keep real space for the song title on narrow launcher cells.
                            showLike = !empty && (!isNowPlaying || useTall || width >= 420),
                            showPrevious = !isNowPlaying || useTall || width >= 360)
                        if (!isNowPlaying || useTall) applyLyrics(v, state, titleLines = if (isNowPlaying) 1 else 2)
                        v
                    }
                    manager.updateAppWidget(id, views)
                }
            }
        }

        /**
         * Populates [views] with the current playback [state], cover art, and click
         * handlers. Called for every layout variant (compact, tall, album-art, studio)
         * so mutations are never duplicated.
         */
        private fun applyState(
            views: RemoteViews,
            context: Context,
            state: State,
            cover: Bitmap?,
            empty: Boolean,
            showLike: Boolean,
            showPrevious: Boolean,
        ) {
            views.setTextViewText(R.id.widget_title, if (empty) context.getString(R.string.widget_nothing_playing) else state.title)
            views.setTextViewText(R.id.widget_artist, if (empty) context.getString(R.string.widget_open) else state.artist)
            if (cover != null) views.setImageViewBitmap(R.id.widget_cover, cover) else views.setImageViewResource(R.id.widget_cover, R.drawable.ic_widget_cover)
            views.setImageViewResource(R.id.widget_play, if (state.playing) R.drawable.ic_widget_pause else R.drawable.ic_widget_play)
            views.setContentDescription(R.id.widget_play, context.getString(if (state.playing) R.string.widget_pause else R.string.widget_play))
            views.setImageViewResource(R.id.widget_like, if (state.liked) R.drawable.ic_widget_heart_filled else R.drawable.ic_widget_heart)
            views.setContentDescription(R.id.widget_like, context.getString(if (state.liked) R.string.widget_unlike else R.string.widget_like))
            views.setViewVisibility(R.id.widget_like, if (showLike) View.VISIBLE else View.GONE)
            views.setViewVisibility(R.id.widget_previous, if (showPrevious) View.VISIBLE else View.GONE)
            views.setOnClickPendingIntent(R.id.widget_root, PendingIntent.getActivity(
                context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            ))
            // Straight to the player service as media keys (see [mediaKey]): Media3's
            // MediaButtonReceiver drops every key but play from Android 8 on.
            views.setOnClickPendingIntent(R.id.widget_previous, mediaKey(context, KeyEvent.KEYCODE_MEDIA_PREVIOUS))
            views.setOnClickPendingIntent(R.id.widget_play, if (state.playing) {
                mediaKey(context, KeyEvent.KEYCODE_MEDIA_PAUSE)
            } else {
                mediaKey(context, KeyEvent.KEYCODE_MEDIA_PLAY, foreground = true)
            })
            views.setOnClickPendingIntent(R.id.widget_next, mediaKey(context, KeyEvent.KEYCODE_MEDIA_NEXT))
            views.setOnClickPendingIntent(R.id.widget_like, PendingIntent.getBroadcast(
                context, 1, Intent(context, NowPlayingWidget::class.java).setAction(ACTION_LIKE),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            ))
        }

        /**
         * The sung line and the next under the title, or neither: with the setting off, no synced
         * lyrics, or lines from another song than [state]'s. The title keeps to one line while
         * they show, so they fit; [titleLines] is what it takes otherwise.
         */
        private fun applyLyrics(views: RemoteViews, state: State, titleLines: Int) {
            val shown = lyrics?.takeIf {
                AppSettings.lyrics.value.onWidgets && state.videoId != null && it.videoId == state.videoId &&
                    (it.current != null || it.next != null)
            }
            val now = shown?.current
            val next = shown?.next
            views.setTextViewText(R.id.widget_lyric_now, now?.let(::lyricText).orEmpty())
            views.setTextViewText(R.id.widget_lyric_next, next?.let(::lyricText).orEmpty())
            // Through a break nothing is sung: the next line waits on its own row.
            views.setViewVisibility(R.id.widget_lyric_now, if (shown != null) View.VISIBLE else View.GONE)
            views.setViewVisibility(R.id.widget_lyric_next, if (next != null) View.VISIBLE else View.GONE)
            views.setInt(R.id.widget_title, "setMaxLines", if (shown != null) 1 else titleLines)
        }

        /** A right-to-left line gets a right-to-left mark in front, so it starts at the right even when it opens with a Latin word. */
        internal fun lyricText(line: LyricLine): String = if (readsRightToLeft(line)) "\u200F" + line.text else line.text

        private fun tall(manager: AppWidgetManager, id: Int): Boolean {
            val options = manager.getAppWidgetOptions(id)
            return maxOf(options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 0), options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 0)) >= TALL_DP
        }

        /** Whether any placed widget has room for lyrics: Album artwork, Studio, or Now playing two rows tall. */
        internal fun hasLyricRoom(context: Context): Boolean {
            val manager = AppWidgetManager.getInstance(context) ?: return false
            fun ids(provider: Class<*>) = manager.getAppWidgetIds(ComponentName(context, provider))
            return ids(AlbumArtWidget::class.java).isNotEmpty() || ids(StudioWidget::class.java).isNotEmpty() ||
                ids(NowPlayingWidget::class.java).any { tall(manager, it) }
        }

        /**
         * Shows [now] under the title, changing only the two lines where the widget allows it.
         * From Android 12 the Now playing widget hands the launcher a layout per size, which a
         * partial update can't reach, so that one is sent whole, cover and all.
         */
        internal fun showLyrics(context: Context, now: LyricsNow?) {
            if (now == lyrics) return
            lyrics = now
            val state = last ?: load(context)
            val manager = AppWidgetManager.getInstance(context) ?: return
            for ((provider, layout) in listOf(AlbumArtWidget::class.java to R.layout.widget_album_art, StudioWidget::class.java to R.layout.widget_studio)) {
                val ids = manager.getAppWidgetIds(ComponentName(context, provider))
                if (ids.isEmpty()) continue
                val views = RemoteViews(context.packageName, layout)
                applyLyrics(views, state, titleLines = 2)
                manager.partiallyUpdateAppWidget(ids, views)
            }
            val tallOnes = manager.getAppWidgetIds(ComponentName(context, NowPlayingWidget::class.java)).filter { tall(manager, it) }
            if (tallOnes.isEmpty()) return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                render(context, state, only = NowPlayingWidget::class.java)
            } else {
                val views = RemoteViews(context.packageName, R.layout.widget_now_playing_tall)
                applyLyrics(views, state, titleLines = 1)
                manager.partiallyUpdateAppWidget(tallOnes.toIntArray(), views)
            }
        }

        /**
         * A media key for the player service. Play is a foreground start, since it may have to
         * start the service and Media3 puts it in the foreground as soon as play is asked for.
         * The others go to a service that is already running; a foreground start for them would
         * crash the app when nothing starts playing within Android's time limit.
         */
        internal fun mediaKey(context: Context, keyCode: Int, foreground: Boolean = false): PendingIntent {
            val intent = mediaKeyIntent(context, keyCode)
            val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            return if (foreground) {
                PendingIntent.getForegroundService(context, keyCode, intent, flags)
            } else {
                PendingIntent.getService(context, keyCode, intent, flags)
            }
        }

        internal fun mediaKeyIntent(context: Context, keyCode: Int): Intent = Intent(Intent.ACTION_MEDIA_BUTTON)
            .setComponent(ComponentName(context, PlaybackService::class.java))
            .putExtra(Intent.EXTRA_KEY_EVENT, KeyEvent(KeyEvent.ACTION_DOWN, keyCode))

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

        private const val COVER_PX = 384
        /** From this height (dp) the Now playing widget is two rows, with room for lyrics. */
        private const val TALL_DP = 145
        private const val LIKE_SAVE_MS = 2_000L
    }
}

/** Artwork-first and spacious transport variants, sharing playback and durable state. */
class AlbumArtWidget : NowPlayingWidget()
class StudioWidget : NowPlayingWidget()

/**
 * Lyrics on widgets: the sung line and the next, at most one change a second, since a
 * launcher can't take widget updates any faster; nothing between lines.
 */
internal object WidgetLyrics : LyricsOutlet {
    override val minIntervalMs = 1_000L

    override fun wanted(context: Context, playing: Boolean): Boolean =
        AppSettings.lyrics.value.onWidgets && NowPlayingWidget.hasLyricRoom(context)

    override fun show(context: Context, now: LyricsNow?) = NowPlayingWidget.showLyrics(context, now)
}
