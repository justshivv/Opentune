package com.opentune.data.connect

import android.util.Log
import com.opentune.data.together.NostrEvent
import com.opentune.data.together.RoomTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.encodeToString

/** What a device keeps of its mailbox between runs. */
internal interface MailStore {
    /** This device's Wanted boxes, by watch. */
    var wanted: Map<String, WantedBox>
    /** On a watch: the requests it has queued, by id, with when they were made. */
    var handled: Map<String, Long>
}

/** A [MailStore] in Connect's preferences. */
internal class PrefsMailStore(private val prefs: android.content.SharedPreferences) : MailStore {
    private val json = WatchMail.json
    private val wantedSer = MapSerializer(String.serializer(), WantedBox.serializer())
    private val handledSer = MapSerializer(String.serializer(), Long.serializer())

    override var wanted: Map<String, WantedBox>
        get() = prefs.getString("mail_wanted", null)?.let { runCatching { json.decodeFromString(wantedSer, it) }.getOrNull() }.orEmpty()
        set(value) { prefs.edit().putString("mail_wanted", json.encodeToString(wantedSer, value)).apply() }

    override var handled: Map<String, Long>
        get() = prefs.getString("mail_handled", null)?.let { runCatching { json.decodeFromString(handledSer, it) }.getOrNull() }.orEmpty()
        set(value) { prefs.edit().putString("mail_handled", json.encodeToString(handledSer, value)).apply() }
}

/** A watch this device has seen in the group, remembered for "Send to watch" while it's away. */
@Serializable
data class KnownWatch(val id: String, val name: String)

/**
 * The mailbox at work while Connect runs: on a watch it takes the requests addressed to it and
 * says which it took; on any device it keeps its own Wanted boxes on the relays and drops
 * what the watches took. See [WatchMail].
 */
internal class WatchMailbox(
    private val mail: WatchMail,
    private val myId: String,
    private val isWatch: Boolean,
    private val transport: RoomTransport,
    private val store: MailStore,
    private val scope: CoroutineScope,
    /** Queues requests on this watch; the ids it managed (a playlist that wouldn't load waits for next time). */
    private val take: (suspend (List<WatchRequest>) -> Set<String>)?,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val lock = Mutex()
    private val jobs = mutableListOf<Job>()
    /** The newest version of each box heard, by `d` tag. */
    private val wantedHeard = mutableMapOf<String, Pair<Long, WantedBox>>()
    private val takenHeard = mutableMapOf<String, Pair<Long, TakenBox>>()
    private var lastCreated = 0L
    private var takenSaid: Set<String>? = null

    private val _waiting = MutableStateFlow(store.wanted.mapValues { it.value.requests.size }.filterValues { it > 0 })
    /** How many requests each watch hasn't picked up yet. */
    val waiting: StateFlow<Map<String, Int>> = _waiting.asStateFlow()

    fun start() {
        jobs += scope.launch {
            var before = 0
            transport.connected.collect { n ->
                // Relays reached (again): our boxes are said again, for a relay that missed them.
                if (before == 0 && n > 0) lock.withLock { republish() }
                before = n
            }
        }
        jobs += scope.launch {
            transport.events.collect { event ->
                if (event.kind != WatchMail.KIND) return@collect
                val envelope = mail.open(event) ?: return@collect
                lock.withLock {
                    envelope.wanted?.let { heardWanted(event, it) }
                    envelope.taken?.let { heardTaken(event, it) }
                }
            }
        }
    }

    fun stop() = jobs.forEach { it.cancel() }

    /** Asks [watch] to download [request] when it's next online. */
    fun send(watch: String, request: WatchRequest) {
        scope.launch {
            lock.withLock {
                val boxes = store.wanted
                val box = boxes[watch] ?: WantedBox(to = watch, from = myId)
                val next = WatchMailRules.prune(box.copy(requests = box.requests + request), null, clock())
                save(boxes + (watch to next))
                publish(mail.wantedEvent(next, created()))
            }
        }
    }

    private suspend fun heardWanted(event: NostrEvent, box: WantedBox) {
        val d = mail.wantedTag(box.to, box.from)
        val known = wantedHeard[d]
        if (known != null && known.first >= event.createdAt) return
        wantedHeard[d] = event.createdAt to box
        if (isWatch && box.to == myId) takeNew()
    }

    private fun heardTaken(event: NostrEvent, box: TakenBox) {
        val d = mail.takenTag(box.by)
        val known = takenHeard[d]
        if (known != null && known.first >= event.createdAt) return
        takenHeard[d] = event.createdAt to box
        val boxes = store.wanted
        val mine = boxes[box.by] ?: return
        val pruned = WatchMailRules.prune(mine, box, clock())
        if (pruned == mine) return
        save(boxes + (box.by to pruned))
        publish(mail.wantedEvent(pruned, created()))
    }

    /** On the watch: queues what's new, then says what it took. */
    private suspend fun takeNew() {
        val now = clock()
        val handled = store.handled.filterValues { now - it < WatchMail.KEEP_MS }
        val wanted = wantedHeard.values.map { it.second }
        val fresh = WatchMailRules.toTake(myId, wanted, handled.keys, now)
        var all = handled
        if (fresh.isNotEmpty()) {
            val took = take?.let { runCatching { it(fresh) }.getOrElse { e -> Log.w(TAG, "couldn't take requests", e); emptySet() } }.orEmpty()
            all = handled + fresh.filter { it.id in took }.associate { it.id to it.at }
        }
        if (all != store.handled) store.handled = all
        val say = WatchMailRules.keepTaken(myId, wanted, all.keys)
        if (say != takenSaid) {
            takenSaid = say
            publish(mail.takenEvent(TakenBox(myId, say.sorted()), created()))
        }
    }

    private suspend fun republish() {
        store.wanted.values.filter { it.requests.isNotEmpty() }.forEach { publish(mail.wantedEvent(it, created())) }
        if (isWatch) {
            takenSaid?.let { publish(mail.takenEvent(TakenBox(myId, it.sorted()), created())) }
            takeNew()
        }
    }

    private fun save(boxes: Map<String, WantedBox>) {
        val kept = boxes.filterValues { it.requests.isNotEmpty() }
        store.wanted = kept
        _waiting.update { kept.mapValues { it.value.requests.size } }
    }

    private fun publish(event: NostrEvent) {
        if (!transport.publish(event)) Log.i(TAG, "no relay for the mailbox now; said again once one is reached")
    }

    /** Seconds, always after the last: a relay keeps the box with the later time. */
    private fun created(): Long {
        lastCreated = maxOf(clock() / 1000, lastCreated + 1)
        return lastCreated
    }

    private companion object {
        const val TAG = "WatchMailbox"
    }
}
