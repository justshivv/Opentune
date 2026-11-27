package com.opentune.data.connect

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import androidx.core.content.edit
import com.opentune.data.model.Song
import com.opentune.data.together.RelayPool
import com.opentune.data.together.Together
import com.opentune.i18n.Tr
import com.opentune.playback.PlayerConnection
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.withContext
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import com.opentune.playback.next
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Connect: this person's devices see what the others play, control it, and
 * hand playback between them, like Spotify Connect. It runs over the same
 * public Nostr relays as Listen Together, in a group of their own devices
 * that shares one secret (see [ConnectGroup]).
 *
 * The phone is in the group while the app's player is attached: open, or
 * in the background with the app still running. Closing the app leaves it.
 */
object Connect {
    /** What this device is to the others: "phone", or "watch" on the watch (see [init]). */
    private var form = "phone"

    private val main by lazy { CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate) }
    private var prefs: SharedPreferences? = null
    private var connection: PlayerConnection? = null
    private var session: ConnectSession? = null
    private var watching: Job? = null

    private val _enabled = MutableStateFlow(true)
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    private val _group = MutableStateFlow<ConnectGroup?>(null)
    val group: StateFlow<ConnectGroup?> = _group.asStateFlow()

    private val _accountSync = MutableStateFlow<Boolean?>(null)
    /**
     * Whether Connect may keep its key in the YouTube account (a private
     * playlist): null until the person has been asked, once, after signing
     * in. Nothing is read or written there before a yes.
     */
    val accountSync: StateFlow<Boolean?> = _accountSync.asStateFlow()

    private val _name = MutableStateFlow("")
    val deviceName: StateFlow<String> = _name.asStateFlow()

    private val _view = MutableStateFlow<ConnectView?>(null)
    /** The group as this device sees it; null while Connect isn't running. */
    val view: StateFlow<ConnectView?> = _view.asStateFlow()

    /** The other device whose playback this one shows and controls, if any. */
    val controlling: StateFlow<Device?> by lazy {
        _view.map { it?.controlling }.let { flow ->
            // Starts from what's known now: the copy is kept up on the main thread, so a first
            // read before that ran said "nobody" while the phone was playing.
            val out = MutableStateFlow(target())
            main.launch { flow.collect { out.value = it } }
            out.asStateFlow()
        }
    }

    /**
     * The device the buttons act on, read from the group as it is now. [controlling] is a
     * copy that follows a moment later: a press seen through it as "nobody" played here.
     */
    private fun target(): Device? = _view.value?.controlling

    private val _notices = MutableSharedFlow<String>(extraBufferCapacity = 8)
    /** Short messages for the UI to show, already translated. */
    val notices: SharedFlow<String> = _notices.asSharedFlow()

    lateinit var myId: String
        private set

    fun init(context: Context, form: String = "phone") {
        this.form = form
        val p = context.getSharedPreferences("connect", Context.MODE_PRIVATE)
        prefs = p
        myId = p.getString("device_id", null) ?: UUID.randomUUID().toString().replace("-", "").take(12).also { p.edit { putString("device_id", it) } }
        _enabled.value = p.getBoolean("enabled", true)
        // Asked for, never assumed ("account_sync" was a default-on switch before).
        _accountSync.value = if (p.contains("account_consent")) p.getBoolean("account_consent", false) else null
        _group.value = ConnectGroup.parse(p.getString("secret", null))
        _name.value = p.getString("name", null) ?: defaultName(context)
        rememberWatches()
    }

    private fun defaultName(context: Context): String =
        runCatching { android.provider.Settings.Global.getString(context.contentResolver, "device_name") }.getOrNull()?.takeIf { it.isNotBlank() }
            ?: "${Build.MANUFACTURER.replaceFirstChar { it.uppercase() }} ${Build.MODEL}"

    // ---- The player ------------------------------------------------------------------------

    /** The app's screens' player, while they're open. */
    private var fromUi: PlayerConnection? = null
    /** The playback service's own, while music plays with the app closed (see PlaybackService). */
    private var fromService: PlayerConnection? = null
    private var accountWatch: Job? = null
    private var screenWatch: Job? = null

    /** The app's player, when its screens open; Connect runs with it. */
    fun attach(player: PlayerConnection) {
        fromUi = player
        use(fromUi ?: fromService)
    }

    /** The screens went away; Connect carries on with the service's player while music plays. */
    fun detach() {
        fromUi = null
        use(fromService)
    }

    /** The playback service's player while music plays (null once it stops): Connect keeps going with the app closed. */
    fun keepWith(player: PlayerConnection?) {
        fromService = player
        use(fromUi ?: fromService)
    }

    /** Runs with [player], or stops with none. Moving to another player keeps the session: the others see no change. */
    @OptIn(FlowPreview::class)
    private fun use(player: PlayerConnection?) {
        if (player === connection) return
        if (player == null) {
            watching?.cancel()
            watching = null
            stopSession()
            connection = null
            return
        }
        val had = connection != null && session != null
        connection = player
        watching?.cancel()
        // The player's changes go out a moment after they settle.
        watching = main.launch {
            merge(
                merge(
                    combine(player.currentSong, player.isPlaying, player.queue, player.upNext, player.volume) { _, _, _, _, _ -> Unit },
                    // A stall starting or ending too: other devices' clocks for this one stop and start with it.
                    combine(player.shuffleMode, player.repeatMode, player.audioFormat, com.opentune.data.NerdStats.playbackSource, player.isSounding) { _, _, _, _, _ -> Unit },
                    combine(player.smartShuffleAvailable, com.opentune.data.settings.AppSettings.playback.map { it.autoplay }.distinctUntilChanged()) { _, _ -> Unit },
                ).debounce(300),
                // A seek goes out at once: held back with the rest, the other devices ran on from the old
                // place meanwhile, and a stall or a quality label changing kept putting it off.
                player.seeks,
            ).collect { session?.localChanged() }
        }
        if (!had) restart()
        // Back on screen: relays lost while it was off are reached at once, so the first press isn't held up.
        if (screenWatch == null) screenWatch = main.launch {
            com.opentune.playback.AppOnScreen.shown.collect { if (it) session?.wake() }
        }
        // Once signed in (at start, or later), the account's group is looked for.
        if (accountWatch == null) accountWatch = main.launch { com.opentune.data.account.AccountStore.signedIn.collect { if (it) syncAccount() } }
    }

    // ---- Settings --------------------------------------------------------------------------

    fun setEnabled(on: Boolean) {
        _enabled.value = on
        prefs?.edit { putBoolean("enabled", on) }
        restart()
    }

    fun setDeviceName(name: String) {
        val clean = name.trim().take(40).ifBlank { return }
        _name.value = clean
        prefs?.edit { putString("name", clean) }
        session?.rename(clean)
    }

    private val _askAccount = MutableStateFlow(false)

    /**
     * True once the account has been looked in and holds no group yet: only
     * then is this device asked about account sync. An account that already
     * holds one said yes on another device, so this one joins without asking.
     */
    val askAccount: StateFlow<Boolean> = _askAccount.asStateFlow()

    fun setAccountSync(on: Boolean) {
        _askAccount.value = false
        _accountSync.value = on
        prefs?.edit { putBoolean("account_consent", on) }
        if (on) syncAccount()
    }

    /**
     * With account sync on and signed in: joins the group kept in the account,
     * or keeps this device's group there when the account has none yet.
     * [push] puts this device's group there even when the account has one
     * (a new secret after "Disconnect all").
     */
    fun syncAccount(push: Boolean = false) {
        if (!com.opentune.data.account.AccountStore.signedIn.value) return
        when (_accountSync.value) {
            false -> return
            null -> { lookInAccount(); return }
            true -> Unit
        }
        main.launch {
            runCatching {
                kotlinx.coroutines.withContext(Dispatchers.IO) {
                    val kept = if (push) null else ConnectAccountSync.read()
                    if (kept == null) ConnectAccountSync.write(ensureGroupOff())
                    kept
                }
            }.onSuccess { kept -> if (kept != null && kept != _group.value) join(kept) }
                .onFailure { com.opentune.data.DebugLog.w("Connect", "account sync: ${it.message}") }
        }
    }

    private var looking = false

    /** Not asked yet on this device: a group kept in the account means yes; none means ask. A failed look asks nothing yet. */
    private fun lookInAccount() {
        if (looking) return
        looking = true
        main.launch {
            runCatching { kotlinx.coroutines.withContext(Dispatchers.IO) { ConnectAccountSync.read() } }
                .onSuccess { kept ->
                    if (_accountSync.value == null) {
                        if (kept != null) {
                            _accountSync.value = true
                            prefs?.edit { putBoolean("account_consent", true) }
                            if (kept != _group.value) join(kept)
                        } else {
                            _askAccount.value = true
                        }
                    }
                }
                .onFailure { com.opentune.data.DebugLog.w("Connect", "account look: ${it.message}") }
            looking = false
        }
    }

    /**
     * Adds a watch whose pairing code was scanned: sends it this device's
     * group (made now if there is none), and the YouTube Music session too
     * when [withAccount]. The watch's name once it answers, or null.
     */
    suspend fun addWatch(key: WatchPairingKey, withAccount: Boolean): String? {
        val group = kotlinx.coroutines.withContext(Dispatchers.Main) { ensureGroup() }
        val cookie = if (withAccount) com.opentune.data.innertube.Innertube.cookie?.takeIf { com.opentune.data.account.AccountStore.signedIn.value } else null
        return WatchPairing.send(key, WatchOffer(group.encoded, cookie, _name.value))
    }

    /** The watch's side of [addWatch]: joins the group it was sent, and signs in with the session if one came. */
    fun accept(offer: WatchOffer) {
        ConnectGroup.parse(offer.group)?.let { join(it, bring = false) }
        offer.cookie?.let { com.opentune.data.account.AccountStore.signIn(it) }
    }

    /** Leaves the group altogether: this device forgets it, and is in no group until paired again. */
    fun leave() {
        stopSession()
        _group.value = null
        prefs?.edit { remove("secret"); remove("former") }
    }

    /** [ensureGroup] from a background thread: the group is made, its restart left to the main one. */
    private suspend fun ensureGroupOff(): ConnectGroup =
        _group.value ?: kotlinx.coroutines.withContext(Dispatchers.Main) { ensureGroup() }

    /** The group, made now if this device has none yet. */
    fun ensureGroup(): ConnectGroup = _group.value ?: ConnectGroup.generate().also { join(it) }

    /**
     * Makes this device one of [group]'s. With [bring], the devices of its
     * old group come along: pairing only ever adds devices, it never leaves
     * some behind.
     */
    fun join(group: ConnectGroup, bring: Boolean = true, remember: Boolean = true) {
        if (group == _group.value) return
        val old = _group.value
        _group.value = group
        // The old group is listened to for a while longer, for its devices that were off just now.
        val former = if (remember) (listOfNotNull(old?.encoded) + formerSecrets()).filter { it != group.encoded }.distinct().take(MAX_FORMER) else emptyList()
        prefs?.edit { putString("secret", group.encoded); putString("former", former.joinToString(",")) }
        if (bring) session?.let { took = it.tookAt; it.stop(next = group.encoded); session = null }
        restart()
    }

    private const val MAX_FORMER = 3

    private fun formerSecrets(): List<String> = prefs?.getString("former", null)?.split(',')?.filter { it.isNotBlank() }.orEmpty()

    /** Pairing with another device's code: its group, with this one's devices brought along and kept in the account. */
    fun pair(group: ConnectGroup) {
        join(group)
        syncAccount(push = true)
    }

    /** A new secret: every other device falls out until it's added again. */
    fun disconnectAll(): ConnectGroup {
        val fresh = ConnectGroup.generate()
        // Nobody is told where it went: the devices left behind stay out.
        join(fresh, bring = false, remember = false)
        syncAccount(push = true)
        return fresh
    }

    // ---- Acting on the device playing --------------------------------------------------------

    /**
     * The player's play/pause, next, previous and seek, sent to the other
     * device when it's the one playing. True when it went there, so the
     * caller does nothing here.
     */
    fun togglePlayPause(): Boolean {
        val target = target() ?: return false
        // By what it's meant to do: one stalled to load is playing, and Pause pauses it.
        return session?.intercept(if (target.state?.meant == true) "pause" else "play") ?: false
    }
    fun skipNext(): Boolean = session?.intercept("next") ?: false

    /**
     * What's started here while another device plays goes there: [songs]
     * from [index]. Songs only this phone can play (its own files) stay out;
     * false when the one to start is such a song, or no other device plays.
     */
    fun playOnRemote(songs: List<com.opentune.data.model.Song>, index: Int): Boolean {
        val target = target() ?: return false
        val start = songs.getOrNull(index)?.let(CTrack::of) ?: return false
        val before = songs.take(index).mapNotNull(CTrack::of)
        val after = songs.drop(index + 1).mapNotNull(CTrack::of)
        session?.playOn(target.id, before + start + after, before.size) ?: return false
        return true
    }
    /** "Play next" while another device plays: there. */
    fun playNextOnRemote(songs: List<com.opentune.data.model.Song>): Boolean {
        val target = target() ?: return false
        val tracks = songs.mapNotNull(CTrack::of).ifEmpty { return false }
        session?.playNextOn(target.id, tracks) ?: return false
        return true
    }
    /** "Add to queue" while another device plays: there. */
    fun addToQueueOnRemote(songs: List<com.opentune.data.model.Song>): Boolean {
        val target = target() ?: return false
        val tracks = songs.mapNotNull(CTrack::of).ifEmpty { return false }
        session?.addToQueueOn(target.id, tracks) ?: return false
        return true
    }
    /** Plays entry [at] ([track]) of the Up Next on the device playing elsewhere. */
    fun playQueued(at: Int, track: CTrack): Boolean {
        val target = target() ?: return false
        session?.jumpOn(target.id, at, track) ?: return false
        return true
    }
    /** Moves entry [from] ([track]) of the Up Next on the device playing elsewhere to place [to]. */
    fun moveQueued(from: Int, track: CTrack, to: Int): Boolean {
        val target = target() ?: return false
        session?.moveOn(target.id, from, to, track) ?: return false
        return true
    }
    /** Takes entry [at] ([track]) out of the Up Next on the device playing elsewhere. */
    fun removeQueued(at: Int, track: CTrack): Boolean {
        val target = target() ?: return false
        session?.removeOn(target.id, at, track) ?: return false
        return true
    }
    fun skipPrevious(): Boolean = session?.intercept("previous") ?: false
    fun seekTo(positionMs: Long): Boolean = session?.intercept("seek", pos = positionMs) ?: false
    fun setVolume(value: Float): Boolean = session?.intercept("volume", value = value) ?: false
    /**
     * The shuffle button for the device playing elsewhere: off, shuffle, then
     * smart shuffle where it can, then off. The mode it goes to, or null when
     * no other device is playing.
     */
    fun cycleShuffle(): com.opentune.playback.ShuffleMode? {
        val target = target() ?: return null
        val s = target.state
        val from = when {
            s?.smart == true -> com.opentune.playback.ShuffleMode.SMART
            s?.shuffle == true -> com.opentune.playback.ShuffleMode.ON
            else -> com.opentune.playback.ShuffleMode.OFF
        }
        val next = from.next(s?.canSmart == true)
        session?.intercept(
            "shuffle",
            value = if (next == com.opentune.playback.ShuffleMode.OFF) 0f else 1f,
            mode = if (next == com.opentune.playback.ShuffleMode.SMART) "smart" else null,
        ) ?: return null
        return next
    }
    /** Shuffle on or off on the device playing elsewhere (smart shuffle goes off too). */
    fun toggleShuffle(): Boolean {
        val target = target() ?: return false
        return session?.intercept("shuffle", value = if (target.state?.shuffle == true) 0f else 1f) ?: false
    }
    /** Autoplay on or off on the device playing elsewhere; false when none is (or it has no autoplay to say). */
    fun toggleAutoplay(): Boolean {
        val target = target() ?: return false
        val on = target.state?.autoplay ?: return false
        return session?.intercept("autoplay", value = if (on) 0f else 1f) ?: false
    }
    /** Off, all, one, as this player's own repeat button goes, on the device playing elsewhere. */
    fun cycleRepeat(): Boolean {
        val target = target() ?: return false
        val next = when (target.state?.repeat) { "off" -> "all"; "all" -> "one"; else -> "off" }
        return session?.intercept("repeat", mode = next) ?: false
    }

    /** How the music sounds here, in the player's words ("Lossless · 16-bit/44.1 kHz"); set by the app. */
    @Volatile var qualityLabel: () -> String? = { null }

    /** Moves playback to [deviceId]; this device's own id brings it here. */
    fun transferTo(deviceId: String) = session?.transferTo(deviceId)

    // ---- Running ---------------------------------------------------------------------------

    private fun restart() {
        stopSession()
        val group = _group.value ?: return
        val player = connection ?: return
        if (!_enabled.value) return
        val former = formerSecrets().mapNotNull(ConnectGroup::parse).filter { it != group }
        val filter = buildJsonObject {
            put("kinds", JsonArray(listOf(JsonPrimitive(ConnectSession.KIND))))
            put("#t", JsonArray((listOf(group) + former).map { JsonPrimitive(it.tag) }))
            put("since", System.currentTimeMillis() / 1000 - 10)
        }
        val view = MutableStateFlow(ConnectView(myId, _name.value))
        main.launch { view.collect { _view.value = it } }.also { viewJob = it }
        val name = _name.value
        val carried = took
        starting = main.launch {
            // The keys are made off the main thread: the first one builds an elliptic-curve table,
            // seconds on a watch, and the app stood still meanwhile (the menu froze as it opened).
            val (mail, pool, made) = withContext(Dispatchers.Default) {
                // The watches' mailbox rides on the same relay connections, under a filter of its own.
                val mail = WatchMail(group.secret)
                val pool = RelayPool(Together.RELAYS, listOf(filter, mail.filter()))
                val made = ConnectSession(
                    group, myId, name, form, ConnectionConnectPlayer { connection ?: player }, pool, view,
                    { template, name -> _notices.tryEmit(Tr.f(template, name)) }, main,
                    moved = { secret -> ConnectGroup.parse(secret)?.let { main.launch { join(it, bring = false) } } },
                    former = former,
                    took = carried,
                )
                Triple(mail, pool, made)
            }
            val box = WatchMailbox(mail, myId, isWatch = form == "watch", pool, mailStore(), mailScope(), takeRequests)
            mailbox = box
            box.start()
            main.launch { box.waiting.collect { _waiting.value = it } }.also { waitingJob = it }
            session = made.also { it.start() }
        }
    }

    /** The network changed under the app (a watch taking Wi-Fi to download): relays lost are reached again at once. */
    fun wake() { session?.wake() }

    /** A session being made (its keys, off the main thread); stopping cancels it. */
    private var starting: Job? = null

    private var viewJob: Job? = null

    // ---- Send to watch ---------------------------------------------------------------------

    private var mailbox: WatchMailbox? = null
    private var waitingJob: Job? = null
    private var mailJobs: CoroutineScope? = null
    private fun mailScope(): CoroutineScope {
        mailJobs?.coroutineContext?.get(Job)?.cancel()
        return CoroutineScope(SupervisorJob() + Dispatchers.Default).also { mailJobs = it }
    }
    private fun mailStore(): MailStore = PrefsMailStore(prefs ?: error("Connect not initialised"))

    /**
     * On a watch: queues the downloads other devices asked for (see [WatchMail]) and gives back
     * the ids it managed. Set by the watch app before Connect starts.
     */
    @Volatile var takeRequests: (suspend (List<WatchRequest>) -> Set<String>)? = null

    private val _watches = MutableStateFlow<List<KnownWatch>>(emptyList())
    /** The watches in the group, here now or seen before, for "Send to watch". */
    val watches: StateFlow<List<KnownWatch>> = _watches.asStateFlow()

    private val _waiting = MutableStateFlow<Map<String, Int>>(emptyMap())
    /** How many sent requests each watch hasn't picked up yet. */
    val waiting: StateFlow<Map<String, Int>> = _waiting.asStateFlow()

    /** Asks [watch] to download a song or a playlist or album; it picks this up next time it's online. */
    fun sendToWatch(watch: String, kind: String, ytId: String, title: String, subtitle: String? = null, thumbnailUrl: String? = null, durationText: String? = null) {
        val request = WatchRequest(UUID.randomUUID().toString(), kind, ytId, title, subtitle, thumbnailUrl, System.currentTimeMillis(), durationText)
        val box = mailbox
        if (box != null) { box.send(watch, request); return }
        // Connect isn't running: kept, and put on the relays once it is.
        val store = mailStore()
        val boxes = store.wanted
        val mine = boxes[watch] ?: WantedBox(to = watch, from = myId)
        store.wanted = boxes + (watch to mine.copy(requests = mine.requests + request))
        _waiting.value = store.wanted.mapValues { it.value.requests.size }
    }

    private val watchesSer = kotlinx.serialization.builtins.ListSerializer(KnownWatch.serializer())

    /** Watches seen here are remembered, so they can be sent to while they're away. */
    private fun rememberWatches() {
        val p = prefs ?: return
        _watches.value = p.getString("watches", null)?.let { runCatching { WatchMail.json.decodeFromString(watchesSer, it) }.getOrNull() }.orEmpty()
        _waiting.value = mailStore().wanted.mapValues { it.value.requests.size }.filterValues { it > 0 }
        main.launch {
            _view.collect { v ->
                val seen = v?.devices?.filter { it.form == "watch" }?.map { KnownWatch(it.id, it.name) }.orEmpty()
                if (seen.isEmpty()) return@collect
                val next = (seen + _watches.value.filter { w -> seen.none { it.id == w.id } })
                if (next != _watches.value) {
                    _watches.value = next
                    p.edit { putString("watches", WatchMail.json.encodeToString(watchesSer, next)) }
                }
            }
        }
    }

    /**
     * When this phone last took the music, kept across sessions: Connect
     * starting over (the player screen made again, a group joined, the
     * switch flipped) isn't taking it. Counted as taking it, a paused phone
     * became the device shown everywhere, and Play pressed elsewhere played here.
     */
    private var took = 0L

    private fun stopSession() {
        starting?.cancel()
        starting = null
        session?.let { took = it.tookAt }
        mailbox?.stop()
        mailbox = null
        waitingJob?.cancel()
        waitingJob = null
        session?.stop()
        session = null
        viewJob?.cancel()
        viewJob = null
        _view.value = null
    }
}

/** The app's player, as Connect sees it. */
internal class ConnectionConnectPlayer(private val source: () -> PlayerConnection) : ConnectPlayer {
    /** Whichever player Connect runs with now: the app's, or the service's with the app closed. */
    private val c: PlayerConnection get() = source()
    override fun snapshot(): Snapshot? {
        val song = c.currentSong.value ?: return null
        val queue = c.queue.value
        val track = CTrack.of(song)?.withLength(c.durationMs.value)
        return Snapshot(
            track = track,
            private = track == null,
            // Moving, not just meant to: other devices run this one's clock on from its last report
            // while it says it's playing, so a stall said as playing ran their timestamp ahead of it.
            // Another player carrying the song through a quality switch counts as moving.
            playing = c.isPlaying.value && (c.isSounding.value || com.opentune.playback.MixState.showing.value != null),
            wants = c.isPlaying.value,
            pos = c.currentPositionMs(),
            queue = c.upNext.value.mapNotNull { queue.getOrNull(it)?.let(CTrack::of) },
            volume = c.volume.value,
            // The app's shuffle reorders the queue and leaves the player's own flag off.
            shuffle = c.shuffleMode.value != com.opentune.playback.ShuffleMode.OFF,
            smart = c.shuffleMode.value == com.opentune.playback.ShuffleMode.SMART,
            canSmart = c.smartShuffleAvailable.value,
            autoplay = com.opentune.data.settings.AppSettings.playback.value.autoplay,
            repeat = when (c.repeatMode.value) {
                androidx.media3.common.Player.REPEAT_MODE_ONE -> "one"
                androidx.media3.common.Player.REPEAT_MODE_ALL -> "all"
                else -> "off"
            },
            quality = runCatching { Connect.qualityLabel() }.getOrNull(),
        )
    }
    override fun setShuffle(on: Boolean, smart: Boolean) {
        val off = com.opentune.playback.ShuffleMode.OFF
        val plain = com.opentune.playback.ShuffleMode.ON
        val smartMode = com.opentune.playback.ShuffleMode.SMART
        val want = when {
            !on -> off
            smart && c.smartShuffleAvailable.value -> smartMode
            else -> plain
        }
        // Plain shuffle turns on and off by itself; smart shuffle comes after it, and goes off as its next step.
        if (c.shuffleMode.value == smartMode && want != smartMode) c.cycleShuffle()
        if (c.shuffleMode.value == off && want != off) c.toggleShuffle()
        if (c.shuffleMode.value == plain && want == off) c.toggleShuffle()
        if (c.shuffleMode.value == plain && want == smartMode) c.cycleShuffle()
    }
    override fun setAutoplay(on: Boolean) = com.opentune.data.settings.AppSettings.setAutoplay(on)
    override fun setRepeat(mode: String) {
        val want = when (mode) {
            "one" -> androidx.media3.common.Player.REPEAT_MODE_ONE
            "all" -> androidx.media3.common.Player.REPEAT_MODE_ALL
            else -> androidx.media3.common.Player.REPEAT_MODE_OFF
        }
        c.setRepeatMode(want)
    }
    override fun play() = c.play()
    override fun pause() = c.pause()
    override fun next() = c.skipNext()
    override fun previous() = c.skipPrevious()
    override fun seekTo(ms: Long) = c.seekTo(ms)
    override fun setVolume(v: Float) = c.setVolume(v)
    override fun playTrack(t: CTrack) {
        c.playNext(t.toSong())
        c.skipNext()
    }
    override fun addToQueue(ts: List<CTrack>) = ts.forEach { c.addToQueue(it.toSong()) }
    // Each goes right after the song playing, so the last one first keeps their order.
    override fun playNext(ts: List<CTrack>) = ts.asReversed().forEach { c.playNext(it.toSong()) }

    /** The queue row of entry [at] in [Snapshot.queue] (the shareable songs after this one), checked against [id]. */
    private fun row(at: Int, id: String): Int? {
        val queue = c.queue.value
        val rows = c.upNext.value.filter { queue.getOrNull(it)?.let(CTrack::of) != null }
        return rows.getOrNull(at)?.takeIf { queue[it].videoId == id } ?: rows.firstOrNull { queue[it].videoId == id }
    }
    override fun playQueued(at: Int, id: String): Boolean {
        c.playQueueItem(row(at, id) ?: return false)
        return true
    }
    override fun removeQueued(at: Int, id: String): Boolean {
        c.removeQueueItem(row(at, id) ?: return false)
        return true
    }
    override fun moveQueued(from: Int, to: Int, id: String): Boolean {
        val queue = c.queue.value
        val rows = c.upNext.value.filter { queue.getOrNull(it)?.let(CTrack::of) != null }
        val row = row(from, id) ?: return false
        val target = rows.getOrNull(to) ?: return false
        if (row != target) c.moveQueueItem(row, target)
        return true
    }
    override fun load(queue: List<CTrack>, index: Int, pos: Long, playing: Boolean): Boolean {
        if (queue.isEmpty() || index !in queue.indices) return false
        // Not yet joined to the player service (the app just opened): it would go nowhere, and the
        // device handing it over, told it was taken, would stop with nothing playing anywhere.
        if (!c.connected.value) return false
        val songs: List<Song> = queue.map { it.toSong() }
        // That device's Up Next, as it was: songs left queued by hand here don't push in after its song.
        c.playAll(songs, index, shuffle = false, source = null, startPositionMs = pos, fromList = false, keepQueued = false)
        if (!playing) c.pause()
        return true
    }
}
