package com.opentune.data.metadata

import com.opentune.data.Http
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.Request

/**
 * The token Apple Music's web player uses for its catalogue reads, taken
 * from the player's own script (as BitChord does). Apple answers 401 unless
 * the request also says it comes from music.apple.com, so [request] sets
 * both. Shared by the Apple lyrics lookup and the animated covers.
 */
internal object AppleWebToken {
    const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36"
    private val INDEX_SCRIPT = Regex("""/assets/index~[^"]+\.js""")
    private val TOKEN = Regex("""eyJ[A-Za-z0-9_-]+\.eyJ[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+""")

    private val lock = Mutex()
    @Volatile private var token: String? = null

    suspend fun get(): String? = token ?: lock.withLock {
        token ?: run {
            val page = text("https://music.apple.com/us/new") ?: return@run null
            val script = INDEX_SCRIPT.find(page)?.value ?: return@run null
            text("https://music.apple.com$script")?.let { TOKEN.find(it)?.value }
        }?.also { token = it }
    }

    /** After a refusal: Apple has turned the token over, so the next read takes a fresh one. */
    fun forget() {
        token = null
    }

    fun request(url: String, bearer: String): Request = Request.Builder().url(url)
        .header("User-Agent", USER_AGENT)
        .header("Authorization", "Bearer $bearer")
        .header("Origin", "https://music.apple.com")
        .header("Referer", "https://music.apple.com/")
        .build()

    private suspend fun text(url: String): String? = runInterruptible(Dispatchers.IO) {
        Http.client.newCall(Request.Builder().url(url).header("User-Agent", USER_AGENT).build()).execute()
            .use { if (it.isSuccessful) it.body?.string() else null }
    }
}
