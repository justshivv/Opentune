package com.opentune.data.audiobooks

import android.net.Uri
import com.opentune.data.Http
import com.opentune.data.model.Song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject

/**
 * Free audiobooks: LibriVox's recordings of public-domain books, read by
 * volunteers, as kept by the Internet Archive (some 22,000 of them). A book
 * is one Archive item; each chapter is one of its MP3 files, played straight
 * from the Archive. A chapter's id names the book and the file
 * (`book:<item>/<file>`), so where it plays from never needs storing.
 */
object Audiobooks {
    private const val PREFIX = "book:"
    private const val COLLECTION = "librivoxaudio"
    private const val ARCHIVE = "https://archive.org"

    data class Book(val id: String, val title: String, val author: String, val runtime: String? = null) {
        val coverUrl: String get() = "$ARCHIVE/services/img/$id"
    }

    data class Chapter(val id: String, val number: Int, val title: String, val durationMs: Long)

    data class BookPage(val book: Book, val description: String?, val readers: String?, val chapters: List<Chapter>)

    fun isChapter(id: String) = id.startsWith(PREFIX)

    // ---- Where each book is up to ----------------------------------------------

    /** How far into a book: the chapter, the place in it, and the book's name for the shelf. */
    data class Progress(val bookId: String, val chapterId: String, val positionMs: Long, val updatedAt: Long, val title: String, val author: String)

    private var prefs: android.content.SharedPreferences? = null
    private val _progress = kotlinx.coroutines.flow.MutableStateFlow<Map<String, Progress>>(emptyMap())
    val progress: kotlinx.coroutines.flow.StateFlow<Map<String, Progress>> = _progress

    fun init(context: android.content.Context) {
        val p = context.getSharedPreferences("audiobooks", android.content.Context.MODE_PRIVATE)
        prefs = p
        _progress.value = runCatching {
            val arr = JSONArray(p.getString("progress", "[]"))
            (0 until arr.length()).map { arr.getJSONObject(it) }.associate { o ->
                o.getString("book") to Progress(o.getString("book"), o.getString("chapter"), o.getLong("pos"), o.getLong("at"), o.optString("title"), o.optString("author"))
            }
        }.getOrDefault(emptyMap())
    }

    /** Remembers where [chapterId] is up to, from the player as it plays. */
    fun save(chapterId: String, positionMs: Long, title: String, author: String) {
        val book = bookOf(chapterId)
        val known = _progress.value[book]
        _progress.value = (_progress.value + (book to Progress(book, chapterId, positionMs, System.currentTimeMillis(), known?.title ?: title, known?.author ?: author)))
            .values.sortedByDescending { it.updatedAt }.take(MAX_BOOKS).associateBy { it.bookId }
        val arr = JSONArray()
        _progress.value.values.forEach { p ->
            arr.put(JSONObject().put("book", p.bookId).put("chapter", p.chapterId).put("pos", p.positionMs).put("at", p.updatedAt).put("title", p.title).put("author", p.author))
        }
        prefs?.edit()?.putString("progress", arr.toString())?.apply()
    }

    /** Where to pick [chapterId] up, when it's the chapter its book was left in. */
    fun resumeAt(chapterId: String): Long? =
        _progress.value[bookOf(chapterId)]?.takeIf { it.chapterId == chapterId && it.positionMs > RESUME_MIN_MS }?.positionMs

    /** Books started, most recent first. */
    fun inProgress(): List<Progress> = _progress.value.values.sortedByDescending { it.updatedAt }

    private const val MAX_BOOKS = 50
    private const val RESUME_MIN_MS = 15_000L

    /** The book a chapter belongs to. */
    fun bookOf(chapterId: String): String = chapterId.removePrefix(PREFIX).substringBefore('/')

    /** Where a chapter plays from. */
    fun urlOf(chapterId: String): String {
        val rest = chapterId.removePrefix(PREFIX)
        val item = rest.substringBefore('/')
        val file = rest.substringAfter('/')
        return "$ARCHIVE/download/${Uri.encode(item)}/${file.split('/').joinToString("/") { Uri.encode(it) }}"
    }

    /** The most listened-to books, a page at a time. */
    suspend fun popular(page: Int = 1): List<Book> = search(null, page)

    /** Books whose title or author matches [query]; with no query, the most popular. */
    suspend fun search(query: String?, page: Int = 1): List<Book> = withContext(Dispatchers.IO) {
        val q = buildString {
            append("collection:$COLLECTION AND mediatype:audio")
            query?.trim()?.takeIf { it.isNotEmpty() }?.let { text ->
                val clean = text.replace(Regex("""[^\p{L}\p{N}\s'-]"""), " ").trim()
                if (clean.isNotEmpty()) append(" AND (title:($clean) OR creator:($clean))")
            }
        }
        val url = "$ARCHIVE/advancedsearch.php".toHttpUrl().newBuilder()
            .addQueryParameter("q", q)
            .addQueryParameter("fl[]", "identifier").addQueryParameter("fl[]", "title")
            .addQueryParameter("fl[]", "creator").addQueryParameter("fl[]", "runtime")
            .addQueryParameter("sort[]", "downloads desc")
            .addQueryParameter("rows", "$PAGE_SIZE").addQueryParameter("page", "$page")
            .addQueryParameter("output", "json")
            .build()
        val docs = JSONObject(get(url.toString())).getJSONObject("response").getJSONArray("docs")
        (0 until docs.length()).mapNotNull { i -> parseBook(docs.getJSONObject(i)) }
    }

    /** A book's details and its chapters, in order. */
    suspend fun book(id: String): BookPage = withContext(Dispatchers.IO) {
        val root = JSONObject(get("$ARCHIVE/metadata/${Uri.encode(id)}"))
        val meta = root.getJSONObject("metadata")
        val book = Book(id, clean(text(meta, "title")).ifEmpty { id }, cleanAuthor(text(meta, "creator")), text(meta, "runtime").ifEmpty { null })
        BookPage(book, text(meta, "description").ifEmpty { null }?.let(::stripHtml), null, chapters(id, root.getJSONArray("files")))
    }

    internal fun chapters(id: String, files: JSONArray): List<Chapter> {
        val all = (0 until files.length()).map { files.getJSONObject(it) }
        // One file per chapter, in the best of the MP3 forms the book has.
        val format = FORMATS.firstOrNull { f -> all.any { it.optString("format") == f } } ?: return emptyList()
        return all.filter { it.optString("format") == format }
            .mapIndexed { i, f ->
                val track = f.optString("track").substringBefore('/').toIntOrNull() ?: (i + 1)
                Chapter(
                    id = "$PREFIX$id/${f.optString("name")}",
                    number = track,
                    title = f.optString("title").ifBlank { "Chapter $track" },
                    durationMs = parseLength(f.optString("length")),
                )
            }
            .sortedWith(compareBy<Chapter> { it.number }.thenBy { it.id })
    }

    /** A chapter as the player takes it: read by the book's author, the book as its album. */
    fun Chapter.toSong(book: Book) = Song(
        videoId = id,
        title = title,
        artist = book.author,
        thumbnailUrl = book.coverUrl,
        durationText = durationMs.takeIf { it > 0 }?.let(::clock),
        albumName = book.title,
    )

    internal fun parseLength(text: String): Long {
        text.toDoubleOrNull()?.let { return (it * 1000).toLong() }
        val parts = text.split(':').mapNotNull { it.toLongOrNull() }
        return when (parts.size) {
            3 -> ((parts[0] * 60 + parts[1]) * 60 + parts[2]) * 1000
            2 -> (parts[0] * 60 + parts[1]) * 1000
            else -> 0
        }
    }

    private fun clock(ms: Long): String {
        val s = ms / 1000
        return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, (s / 60) % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
    }

    private fun parseBook(doc: JSONObject): Book? {
        val id = doc.optString("identifier").ifBlank { return null }
        val title = clean(text(doc, "title")).ifBlank { return null }
        return Book(id, title, cleanAuthor(text(doc, "creator")), text(doc, "runtime").ifEmpty { null })
    }

    /** A field that may be a string or a list of them. */
    private fun text(o: JSONObject, key: String): String = when (val v = o.opt(key)) {
        is JSONArray -> (0 until v.length()).joinToString(", ") { v.optString(it) }
        null, JSONObject.NULL -> ""
        else -> v.toString()
    }

    /** "Alice's Adventures in Wonderland, by Lewis Carroll": the title alone. */
    private fun clean(title: String) = title.replace(Regex(""",?\s+by\s+[^,]+$""", RegexOption.IGNORE_CASE), "").trim()

    /** "Austen, Jane (1775-1817)" or "Jane Austen": "Jane Austen". */
    internal fun cleanAuthor(raw: String): String {
        val first = raw.split(';').first().replace(Regex("""\s*\(.*?\)"""), "").trim()
        val parts = first.split(',').map { it.trim() }
        return if (parts.size == 2 && !parts[1].contains(' ')) "${parts[1]} ${parts[0]}" else first.ifEmpty { "LibriVox" }
    }

    private fun stripHtml(html: String) = html.replace(Regex("<br\\s*/?>", RegexOption.IGNORE_CASE), "\n")
        .replace(Regex("<[^>]+>"), "").replace("&amp;", "&").replace("&quot;", "\"").replace("&#39;", "'").trim()

    private fun get(url: String): String =
        Http.client.newCall(Request.Builder().url(url).build()).execute().use { r ->
            check(r.isSuccessful) { "The Internet Archive said ${r.code}" }
            r.body?.string().orEmpty()
        }

    private const val PAGE_SIZE = 30
    /** The MP3 forms an Archive LibriVox item carries, best first. */
    private val FORMATS = listOf("128Kbps MP3", "VBR MP3", "64Kbps MP3")
}
