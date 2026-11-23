package com.opentune.ui.components

import android.content.Context
import android.os.Build
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import com.opentune.ui.theme.Icon
import androidx.compose.material3.MaterialTheme
import com.opentune.ui.theme.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import androidx.compose.ui.res.vectorResource
import com.opentune.ui.theme.AppIcons

/**
 * What changed, shown once after an update: the notes for each version
 * since the one last opened, newest first. Not shown on a fresh install.
 */
object WhatsNew {
    data class Note(val iconRes: Int = 0, val title: String, val line: String, val iconVector: ImageVector? = null)

    /** Newest first. Add the coming version's notes at the top when releasing. */
    val NOTES: List<Pair<String, List<Note>>> = listOf(
        "0.4.17" to listOf(
            Note(AppIcons.Notifications, "In the status bar", "On Android 16 and newer, the playing song shows as a Live Update: a chip in the status bar counts down the time left, and the lock screen shows a bar in the cover's colour. Turn it off in Settings › Playback."),
            Note(AppIcons.VisibilityOff, "Not interested", "A song's menu can now keep that song, or everything by its artist, out of radio, mixes and Home. Your own playlists, likes and downloads stay as they are. Undo it in Settings › Playback › Hidden songs and artists."),
            Note(AppIcons.AutoAwesome, "Smart shuffle", "Tap shuffle twice in the player, or long-press Shuffle on a playlist, and a similar song joins after every four. The added ones have a sparkle, and you can keep any of them in your playlist."),
            Note(AppIcons.Swipe, "Swipe to queue", "Swipe a song right to play it next, or left to add it to the queue."),
            Note(AppIcons.Image, "Covers that move", "Song and album covers on Home drift gently like the player's, while they're on screen and you're not scrolling. They stay still in battery saver."),
            Note(AppIcons.TabletAndroid, "Room on big screens", "On tablets and unfolded phones the tabs move to a rail on the left, pages keep a readable width, and on large screens the player stays open beside the page."),
            Note(AppIcons.WavingHand, "A proper welcome", "A new install starts by picking a few artists you like, then a theme and glass, so the first Home already has your music on it. To see it, go to Settings › About › Show the welcome again."),
        ),
        "0.4.16" to listOf(
            Note(AppIcons.Lyrics, "Lyrics in step", "Lyrics are now looked up with the length of the song actually playing, so they no longer come from another version of it, timed differently. While they're found, a Setting up the lyrics badge shows."),
            Note(AppIcons.FormatQuote, "Better Lyrics", "A new word-by-word lyrics source, for songs Better Lyrics' listeners have already looked up. In Settings › Lyrics sources."),
            Note(AppIcons.Download, "Downloaded playlists", "Download a whole album or playlist and it stays in your library under Playlists, with its downloaded songs ready offline. Downloads still lists every song. In a song's menu, the download button fills round its edge as it goes; tap it to cancel."),
            Note(AppIcons.Favorite, "A burst of hearts", "Liking a song with the player's heart sends out a little burst of hearts. Albums and playlists have a heart too, to save them to your library, and every list can be shared."),
            Note(AppIcons.GraphicEq, "A new equalizer", "A big response curve you drag the bands on, faders that glide, and presets in three groups, from Hi-Fi to Late night, each showing its own curve."),
            Note(AppIcons.Lyrics, "Lyrics take the screen", "On the lyrics view, a few seconds untouched and the controls slide away; touch to bring them back."),
            Note(AppIcons.Mic, "Name that song, anywhere", "A home-screen widget and a Quick Settings tile open NepTune listening, and what it finds goes into a Recognized playlist."),
            Note(AppIcons.BlurOn, "Glass and motion", "Liquid Glass is deeper and brighter at the edges, page transitions each move their own way, and leaving the app glides into a pill. The mini player can keep plain glass, in Settings › Look."),
        ),
        "0.4.10" to listOf(
            Note(AppIcons.Mic, "Call the room", "Join a room's call and just talk: what you say is heard as you say it, and a ring lights up round whoever is speaking. Hold Talk for a quick word instead. Nothing is kept in the chat."),
            Note(AppIcons.Groups, "Nobody wrongly gone", "A guest whose check-ins got lost no longer shows as having left on the host's phone, and comes back on their own without knocking again."),
            Note(AppIcons.Waves, "A beam for lossless", "When a song plays lossless, a glowing beam runs round the quality pill. Words in synced lyrics no longer jump twice."),
            Note(AppIcons.TouchApp, "Opening and closing the player", "Tap the mini player and its cover grows into the player. Going back from Home while music plays shrinks the app up towards the notification."),
            Note(AppIcons.Download, "Updates that don't fall behind", "The update offered is always the newest, even when several came out since yours. One you put off leaves a red dot on the account button until it's installed, and Settings › Storage can delete a downloaded update."),
            Note(AppIcons.AutoAwesome, "Clarity, as loud as off", "Clarity no longer makes music quieter. Settings still being worked on carry a Beta tag."),
            Note(AppIcons.Favorite, "Support NepTune", "From Settings › About you can star NepTune on GitHub, or send a small thank-you."),
        ),
        "0.4.9" to listOf(
            Note(AppIcons.Lock, "Rooms you let people into", "People with the code knock; you let them in or turn them away. Only those you let in can hear what's playing or read the chat, and removing someone locks them out."),
            Note(AppIcons.Mic, "Voice notes and talk", "Hold the mic in a room's chat to send a voice note, or hold Talk and everyone hears you a moment later, the music dipping under you."),
            Note(AppIcons.Groups, "Listen together, fixed and handier", "Joining a room lists you straight away, marked You. As a guest you can choose for a song you pick to play now for everyone, when the host allows it."),
            Note(AppIcons.Download, "Faster downloads", "Songs download in pieces, several at once, instead of trickling in at playback speed. A playlist downloads three songs at a time."),
            Note(AppIcons.Waves, "A cleaner seek bar", "Where the song's parts begin now shows as small dots ahead of the playhead, not as breaks in the played line."),
        ),
        "0.4.8" to listOf(
            Note(AppIcons.TouchApp, "Neater player buttons", "Play, pause and skip are a little smaller in every button style, and the cover gets the room."),
            Note(AppIcons.NewReleases, "What's new, named right", "This sheet now names the version you installed, and older notes carry their own number."),
        ),
        "0.4.7" to listOf(
            Note(AppIcons.Lyrics, "The best lyrics, from the start", "New defaults: every lyrics source on, word-by-word lyrics first, and romanized lines under other scripts. Settings you changed yourself stay as they were."),
            Note(AppIcons.NewReleases, "Swipe away to stop", "By default, removing NepTune from your recent apps stops the music."),
        ),
        "0.4.6" to listOf(
            Note(AppIcons.Lyrics, "Lyrics settings that fit", "Settings › Lyrics sources scrolls, so every switch keeps its label."),
        ),
        "0.4.5" to listOf(
            Note(AppIcons.Lyrics, "Lyrics, faster and word by word", "Every lyrics source is asked at once, and LyricsPlus joins them with word-by-word timing for many songs."),
            Note(AppIcons.Groups, "Duets and backing vocals", "A second singer's lines sit on the other side, and backing vocals show smaller under their line."),
            Note(AppIcons.FastForward, "Skip to the chorus", "A button beside the source pill jumps to the next chorus, and the seek bar marks where each part starts."),
            Note(AppIcons.Translate, "Romanized lyrics", "Hindi, Korean, Japanese and other scripts get a line in Latin letters underneath. In the player's menu."),
            Note(AppIcons.Lyrics, "Pick the lyrics source", "Player menu › Lyrics source shows what each source has for the song; the one you pick stays for that song."),
        ),
        "0.4.0" to listOf(
            Note(AppIcons.SurroundSound, "3D sound", "In Remix: the music goes all the way round you, overhead and underneath too. Put headphones on."),
            Note(AppIcons.Explicit, "Explicit, marked", "Songs and albums marked explicit show a small E, in lists, on Home, in search and in the player."),
            Note(AppIcons.Lyrics, "Lyrics found more often", "Remaster and deluxe tags are cleaned off titles, and artists spelled another way are still matched."),
        ),
        "0.3.9" to listOf(
            Note(AppIcons.Lyrics, "Lyrics while seeking, fixed", "Dragging the seek bar now shows the line sung at that spot."),
            Note(AppIcons.FormatQuote, "Easier lyric cards", "The share sheet opens on the line being sung. Tap lines to tick them onto the card."),
        ),
        "0.3.8" to listOf(
            Note(AppIcons.FormatQuote, "Lyric cards", "Tap the quote button in the lyrics, pick up to five lines and share them as a picture."),
            Note(iconVector = com.opentune.ui.player.PlayerIcons.Headphones, title = "8D audio", line = "In Remix: the music circles your head, slow, medium or fast. Best with headphones."),
            Note(AppIcons.Bedtime, "A gentler sleep timer", "Over its last minutes the music fades out and the player dims."),
            Note(AppIcons.TouchApp, "Double-tap the cover", "Either side seeks 10 seconds. A glow under the cover follows the bass."),
            Note(AppIcons.Lyrics, "Lyrics while seeking", "Drag the seek bar to see the line at that spot. Settings › Player."),
            Note(AppIcons.Image, "Playlist covers", "Your playlists get a collage of their songs, or colours from their name."),
            Note(AppIcons.BlurOn, "Real glass", "Page bars, floating buttons and popups blur what's behind them."),
        ),
        "0.3.7" to listOf(
            Note(AppIcons.QueueMusic, "Daily mixes", "New on Home every day, made from what you play: your top artists, this time of day, what's on repeat and old favourites."),
            Note(AppIcons.Waves, "Waveform seek bar", "See the song's loud and quiet parts as you scrub. Settings › Player."),
            Note(AppIcons.Favorite, "A burst of hearts", "Liking a song in the player sends hearts out in the cover's colours."),
            Note(AppIcons.Lyrics, "Lyrics in colour", "The cover's colours drift slowly behind the lyrics."),
            Note(AppIcons.Image, "Blurred headers", "Album, artist and playlist pages have their cover blurred behind the top."),
        ),
        "0.3.6" to listOf(
            Note(AppIcons.Cast, "Cast to a TV or speaker", "Play on Chromecasts, smart TVs and network speakers on your Wi-Fi."),
            Note(AppIcons.GraphicEq, "What's this song?", "From Home or the search bar, NepTune names the song playing near you."),
            Note(AppIcons.NewReleases, "More after one song", "A song started on its own now always carries on into similar songs."),
        ),
    )

    private const val PREFS = "whats_new"

    /**
     * The notes to show now, if any, and remembers the version as seen. A
     * fresh install has nothing new to tell, so it only remembers.
     */
    fun due(context: Context, version: String): List<Pair<String, List<Note>>> {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val seen = p.getString("seen", null)
        if (seen == version) return emptyList()
        p.edit().putString("seen", version).apply()
        val fresh = seen == null && runCatching {
            val info = if (Build.VERSION.SDK_INT >= 33) {
                context.packageManager.getPackageInfo(context.packageName, android.content.pm.PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION") context.packageManager.getPackageInfo(context.packageName, 0)
            }
            info.firstInstallTime == info.lastUpdateTime
        }.getOrDefault(true)
        if (fresh) return emptyList()
        return since(seen, version, com.opentune.BuildConfig.CHANGELOG)
    }

    /**
     * The notes for versions after [seen] up to [version], at most three
     * versions. When [version] has none of its own (it was released before
     * they were written), its store [changelog] stands in, so an update
     * from 0.4.5 to 0.4.7 never opens on 0.4.6's notes.
     */
    internal fun since(seen: String?, version: String, changelog: String = ""): List<Pair<String, List<Note>>> {
        val notes = NOTES.filter { (v, _) -> compare(v, version) <= 0 && (seen == null || compare(v, seen) > 0) }
        val own = notes.firstOrNull()?.first?.let { compare(it, version) == 0 } == true
        val stand = if (own) null else fromChangelog(changelog).takeIf { it.isNotEmpty() }?.let { version to it }
        return (listOfNotNull(stand) + notes).take(3)
    }

    /** A store changelog's "• line" items as notes. */
    internal fun fromChangelog(text: String): List<Note> =
        text.lineSequence().map { it.trim().removePrefix("•").removePrefix("-").trim() }.filter { it.isNotEmpty() }
            .map { Note(AppIcons.NewReleases, it, "") }.toList()

    internal fun compare(a: String, b: String): Int {
        val x = a.split('.', '-').map { it.toIntOrNull() ?: 0 }
        val y = b.split('.', '-').map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(x.size, y.size)) {
            val c = (x.getOrElse(i) { 0 }).compareTo(y.getOrElse(i) { 0 })
            if (c != 0) return c
        }
        return 0
    }
}

/** A release cover, readable feature cards, and older changes tucked into version groups. */
@Composable
fun WhatsNewSheet(notes: List<Pair<String, List<WhatsNew.Note>>>, version: String, onDismiss: () -> Unit) {
    val ownNotes = notes.firstOrNull()?.first == version
    FloatingCard(
        onDismiss = onDismiss,
        title = com.opentune.i18n.Tr.f("What's new in {0}", version),
        expandable = true,
        fullFraction = 1f,
        actions = {
            Button(onClick = onDismiss, modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp)) { Text("Start listening") }
        },
    ) {
        Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())
            .padding(horizontal = 4.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            ReleaseHero(version, "Installed · ready to listen")
            if (notes.isEmpty()) Text("You're up to date. Enjoy your music.", Modifier.padding(16.dp))
            notes.forEachIndexed { group, (releaseVersion, list) ->
                if (group == 0) {
                    Text(if (ownNotes) "The highlights" else com.opentune.i18n.Tr.f("In {0}", releaseVersion),
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.padding(top = 8.dp, start = 4.dp).semantics { heading() })
                    list.forEachIndexed { index, note -> NoteRow(note, index) }
                } else {
                    var expanded by rememberSaveable(releaseVersion) { mutableStateOf(false) }
                    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainer)) {
                        Row(Modifier.fillMaxWidth().clickable { expanded = !expanded }.padding(18.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(com.opentune.i18n.Tr.f("Earlier · {0}", releaseVersion), style = MaterialTheme.typography.titleMedium)
                                Text(com.opentune.i18n.Tr.f("{0} changes", list.size), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Icon(if (expanded) ImageVector.vectorResource(AppIcons.ExpandLess) else ImageVector.vectorResource(AppIcons.ExpandMore),
                                if (expanded) "Collapse changes" else "Expand changes")
                        }
                        if (expanded) list.forEachIndexed { index, note -> NoteRow(note, index) }
                    }
                }
            }
            Spacer(Modifier.size(8.dp))
        }
    }
}

@Composable
private fun NoteRow(note: WhatsNew.Note, index: Int) {
    val ui by com.opentune.data.settings.AppSettings.ui.collectAsState()
    val shown = remember { Animatable(if (ui.reduceAnimation) 1f else 0f) }
    LaunchedEffect(ui.reduceAnimation) {
        if (ui.reduceAnimation) shown.snapTo(1f) else {
            delay(60L + index.coerceAtMost(5) * 45L)
            shown.animateTo(1f, spring(dampingRatio = 0.9f, stiffness = 300f))
        }
    }
    Row(
        Modifier
            .fillMaxWidth()
            .graphicsLayer {
                alpha = shown.value.coerceIn(0f, 1f)
                translationY = (1f - shown.value) * 24.dp.toPx()
            }
            .clip(RoundedCornerShape(22.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .padding(18.dp),
        horizontalArrangement = Arrangement.Start,
        verticalAlignment = Alignment.Top,
    ) {
        Box(
            Modifier.size(42.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(note.iconVector ?: ImageVector.vectorResource(note.iconRes), null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(note.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            if (note.line.isNotEmpty()) Text(note.line, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
        }
    }
}
