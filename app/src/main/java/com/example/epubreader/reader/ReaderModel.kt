package com.example.epubreader.reader

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.util.Log
import android.webkit.WebView
import android.widget.Toast
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.FileProvider
import com.example.epubreader.App
import com.example.epubreader.R
import com.example.epubreader.book.Book
import com.example.epubreader.book.TocEntry
import com.example.epubreader.data.Annotation
import com.example.epubreader.data.BookEntity
import com.example.epubreader.data.Palette
import com.example.epubreader.data.Tag
import com.example.epubreader.dict.DictEntry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.Locale
import kotlin.coroutines.resume

/** Rectangle in dp (CSS px) relative to the reader viewport. */
data class Box(val l: Float, val t: Float, val r: Float, val b: Float)

enum class OnlineState { Idle, Loading, Done, NotFound, NoNetwork, Failed, Disabled }

class Lookup(
    val word: String, val chapter: Int, val start: Int, val end: Int, val rect: Box, val annotationId: Long,
) {
    var offline by mutableStateOf<List<DictEntry>?>(null)
    var online by mutableStateOf<List<DictEntry>?>(null)
    var onlineState by mutableStateOf(OnlineState.Idle)
    /** null = automatic (online when available, else offline). */
    var preferOnline by mutableStateOf<Boolean?>(null)
}

/** Draft shown in the annotation editor sheet. */
data class Draft(
    val id: Long, val chapter: Int, val start: Int, val end: Int, val quote: String,
    val note: String, val color: String?, val tagIds: Set<Long>, val focus: Focus,
    val suggestedTag: String? = null, val created: Long = 0,
) {
    enum class Focus { None, Note, Tag }
}

/** A location inside the book, either by exact offsets or by n-th text match. */
data class Loc(
    val chapter: Int, val start: Int? = null, val end: Int? = null,
    val find: String? = null, val nth: Int = 0, val whole: Boolean = false, val progress: Float? = null,
)

/** Step-through navigation over a list of places (tag passages, mentions, search hits). */
data class Trail(val label: String, val color: Int?, val items: List<Loc>, val index: Int)

/** A just-made change that the reader can take back from the snackbar. */
class UndoAction(val message: String, val action: () -> Unit)

data class Footnote(val text: String, val chapter: Int, val anchor: String, val rect: Box)

data class SearchHit(val chapter: Int, val nth: Int, val snippet: String, val at: Int, val len: Int)

sealed interface Panel {
    data object None : Panel
    data object Toc : Panel
    data object Notes : Panel
    data object Settings : Panel
    data object Search : Panel
    data class TagPage(val tagId: Long) : Panel
}

class ReaderModel(val app: App, val bookId: Long, private val scope: CoroutineScope) {
    var entity by mutableStateOf<BookEntity?>(null)
        private set
    var book: Book? = null
        private set
    val isPdf get() = entity?.format == "pdf"
    var chapterCount by mutableIntStateOf(0)
        private set
    var toc by mutableStateOf<List<TocEntry>>(emptyList())
        private set
    var pdfTitle by mutableStateOf("")
        private set
    private var cumWeights: DoubleArray = DoubleArray(1)

    val annotations = mutableStateListOf<Annotation>()
    val tags = mutableStateListOf<Tag>()
    @Volatile private var annSnapshot: List<Annotation> = emptyList()
    @Volatile private var tagSnapshot: Map<Long, Tag> = emptyMap()

    var chapter by mutableIntStateOf(0)
        private set
    var progress by mutableFloatStateOf(0f)
        private set
    var percent by mutableFloatStateOf(0f)
        private set

    /** Book-wide page numbering (1-based); estimated until every chapter has been laid out. */
    var pageNumber by mutableIntStateOf(0)
        private set
    var pageTotal by mutableIntStateOf(0)
        private set
    var chapterPage by mutableIntStateOf(0)
        private set
    var chapterPages by mutableIntStateOf(0)
        private set
    var pagesExact by mutableStateOf(false)
        private set
    private var pageStarts: IntArray = IntArray(0)

    var footnote by mutableStateOf<Footnote?>(null)
    var undo by mutableStateOf<UndoAction?>(null)

    var ready by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var chromeVisible by mutableStateOf(false)
    var lookup by mutableStateOf<Lookup?>(null)
        private set
    var draft by mutableStateOf<Draft?>(null)
    var panel by mutableStateOf<Panel>(Panel.None)
    var trail by mutableStateOf<Trail?>(null)
    val history = mutableStateListOf<Loc>()

    var searchQuery by mutableStateOf("")
    var searchResults by mutableStateOf<List<SearchHit>?>(null)
        private set
    var searching by mutableStateOf(false)
        private set
    var searchProgress by mutableFloatStateOf(0f)
        private set
    private var searchJob: Job? = null
    /** The PDF text search running in the page; it runs one at a time, shared by search and tag mentions. */
    private var pdfSearch: PdfSearch? = null
    private var pdfToken = 0

    private class PdfSearch(
        val owner: Any, val token: Int,
        val onProgress: (Float) -> Unit, val onCancel: () -> Unit, val onDone: (List<SearchHit>) -> Unit,
    )

    /** True while a note is being written, so a second tap on Save can't store it twice. */
    var saving by mutableStateOf(false)
        private set
    /** Bumped when Back is pressed with the note editor open; the sheet decides whether to ask first. */
    var draftCloseRequests by mutableIntStateOf(0)

    var web: WebView? = null
    private var pageLoaded = false
    private var saveJob: Job? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var pendingSpeech: String? = null
    /** Highlight ranges being written right now, so a double tap doesn't add the same highlight twice. */
    private val pendingHighlights = mutableSetOf<Triple<Int, Int, Int>>()

    /** Changed to make the screen build a fresh WebView after the page renderer died. */
    var webGeneration by mutableIntStateOf(0)
        private set
    private var rendererCrashes = 0

    val settings get() = app.settings.value

    // ------------------------------------------------------------------ loading

    suspend fun load() {
        try {
            val e = withContext(Dispatchers.IO) { app.db.book(bookId) } ?: run { error = app.getString(R.string.reader_book_not_found); return }
            entity = e
            chapter = e.chapter
            progress = e.progress
            percent = e.percent
            reloadAnnotations()
            if (e.format != "pdf") {
                val b = withContext(Dispatchers.IO) { Book.open(File(e.path), e.format) }
                book = b
                chapterCount = b.chapterCount
                toc = b.toc
                val total = b.weights.sum().toDouble().coerceAtLeast(1.0)
                cumWeights = DoubleArray(b.weights.size + 1).also { arr ->
                    b.weights.forEachIndexed { i, w -> arr[i + 1] = arr[i] + w / total }
                }
            }
            withContext(Dispatchers.IO) { app.db.touchBook(bookId) }
            maybeOpen()
        } catch (c: CancellationException) {
            throw c
        } catch (t: Throwable) {
            // Throwable, not Exception: a huge or malformed book can run the parser out of memory.
            Log.e("Reader", "open failed", t)
            error = if (t is OutOfMemoryError) app.getString(R.string.reader_too_large)
            else app.getString(R.string.reader_open_failed, t.message ?: t.javaClass.simpleName)
        }
    }

    fun onPageLoaded() {
        pageLoaded = true
        maybeOpen()
    }

    var insetTop = 24f
    var insetBottom = 24f

    private fun maybeOpen() {
        val e = entity ?: return
        if (!pageLoaded || (e.format != "pdf" && book == null)) return
        val o = JSONObject().apply {
            put("mode", if (e.format == "pdf") "pdf" else "flow")
            put("count", chapterCount)
            put("ch", chapter)
            put("progress", progress.toDouble())
            put("top", insetTop.toDouble())
            put("bottom", insetBottom.toDouble())
            put("settings", JSONObject(settings.toJs()))
            put("bookKey", "book$bookId")
            book?.let { b -> put("weights", JSONArray(b.weights)) }
        }
        js("Reader.open($o)")
    }

    fun onReady() {
        ready = true
    }

    fun onError(msg: String) {
        error = msg
    }

    /** The WebView's renderer process died (usually out of memory). Rebuild it once, then give up with an error. */
    fun onRendererGone() {
        web = null
        pageLoaded = false
        ready = false
        pdfSearch = null
        searching = false
        flushPosition()
        if (++rendererCrashes > 1) {
            error = app.getString(R.string.reader_renderer_crashed)
            return
        }
        webGeneration++
    }

    fun onPdfInfo(json: String) {
        val o = JSONObject(json)
        chapterCount = o.getInt("pages")
        pdfTitle = o.optString("title")
        val arr = o.optJSONArray("toc") ?: JSONArray()
        toc = (0 until arr.length()).mapNotNull { i ->
            val t = arr.getJSONObject(i)
            val p = t.optInt("page", -1)
            if (p < 0) null else TocEntry(t.optString("title"), p, "", t.optInt("depth"))
        }
        // Prefer the PDF's embedded title over the file name it was imported with.
        val e = entity
        val t = pdfTitle.trim()
        if (e != null && t.length in 3..200 && e.title == e.sourceName.substringBeforeLast('.').replace('_', ' ').trim()) {
            entity = e.copy(title = t)
            app.appScope.launch(Dispatchers.IO) { app.db.updateTitle(bookId, t); app.library.refresh() }
        }
    }

    fun onPage(page: Int, total: Int, chPage: Int, chPages: Int) {
        pageNumber = page
        pageTotal = total
        chapterPage = chPage
        chapterPages = chPages
    }

    fun onPages(json: String) {
        val o = JSONObject(json)
        val arr = o.getJSONArray("starts")
        pageStarts = IntArray(arr.length()) { arr.getInt(it) }
        pageTotal = o.getInt("total")
        pagesExact = o.optBoolean("exact")
    }

    /** Chapter containing a 1-based book page (for the slider preview). */
    fun chapterOfPage(page: Int): Int {
        if (isPdf) return (page - 1).coerceIn(0, maxOf(0, chapterCount - 1))
        val p = page - 1
        var ch = 0
        while (ch < pageStarts.size - 1 && pageStarts[ch + 1] <= p) ch++
        return ch
    }

    fun goToPage(page: Int) {
        history.add(currentLoc())
        if (history.size > 30) history.removeAt(0)
        dismissLookup()
        js("Reader.goToPage($page)")
    }

    fun onFootnote(text: String, ch: Int, anchor: String, rect: Box) {
        dismissLookup()
        chromeVisible = false
        footnote = Footnote(text, ch, anchor, rect)
    }

    fun followFootnote(f: Footnote) {
        footnote = null
        goToAnchor(f.chapter, f.anchor)
    }

    fun setInsets(top: Float, bottom: Float) {
        if (top == insetTop && bottom == insetBottom) return
        insetTop = top
        insetBottom = bottom
        if (ready) js("Reader.setInsets($top,$bottom)")
    }

    fun js(code: String) {
        web?.evaluateJavascript(code, null)
    }

    // ------------------------------------------------------------------ position

    fun chapterTitle(i: Int = chapter): String {
        if (isPdf) {
            val t = toc.lastOrNull { it.chapter <= i }?.title
            return if (t != null) app.getString(R.string.reader_pdf_chapter_page, t, i + 1) else app.getString(R.string.reader_page_n, i + 1)
        }
        return book?.chapterTitle(i) ?: ""
    }

    fun percentOf(ch: Int, p: Float): Float {
        if (chapterCount <= 0) return 0f
        if (isPdf || cumWeights.size != chapterCount + 1) return ((ch + p) / chapterCount).coerceIn(0f, 1f)
        val a = cumWeights[ch.coerceIn(0, chapterCount - 1)]
        val b = cumWeights[(ch + 1).coerceIn(0, chapterCount)]
        return (a + (b - a) * p).toFloat().coerceIn(0f, 1f)
    }

    fun locationOfPercent(f: Float): Loc {
        if (chapterCount <= 0) return Loc(0, progress = 0f)
        if (isPdf || cumWeights.size != chapterCount + 1) {
            val x = f * chapterCount
            val ch = x.toInt().coerceIn(0, chapterCount - 1)
            return Loc(ch, progress = (x - ch).coerceIn(0f, 1f))
        }
        var ch = 0
        while (ch < chapterCount - 1 && cumWeights[ch + 1] <= f) ch++
        val a = cumWeights[ch]
        val b = cumWeights[ch + 1]
        return Loc(ch, progress = if (b > a) ((f - a) / (b - a)).toFloat().coerceIn(0f, 1f) else 0f)
    }

    fun onPosition(ch: Int, p: Float) {
        chapter = ch
        progress = p
        percent = percentOf(ch, p)
        saveJob?.cancel()
        val pc = percent
        saveJob = scope.launch {
            delay(700)
            withContext(Dispatchers.IO) { runCatching { app.db.updatePosition(bookId, ch, p, pc) }.onFailure { Log.w("Reader", "save position", it) } }
        }
    }

    fun flushPosition() {
        if (entity == null) return
        val ch = chapter
        val p = progress
        val pc = percent
        app.appScope.launch(Dispatchers.IO) { runCatching { app.db.updatePosition(bookId, ch, p, pc) }.onFailure { Log.w("Reader", "save position", it) } }
    }

    // ------------------------------------------------------------------ navigation

    private fun currentLoc() = Loc(chapter, progress = progress)

    fun goTo(loc: Loc, remember: Boolean = true, flash: Boolean = true) {
        if (remember) {
            history.add(currentLoc())
            if (history.size > 30) history.removeAt(0)
        }
        val o = JSONObject()
        loc.progress?.let { o.put("progress", it.toDouble()) }
        loc.start?.let { o.put("s", it) }
        loc.end?.let { o.put("e", it) }
        loc.find?.let { o.put("find", it); o.put("nth", loc.nth); o.put("whole", loc.whole) }
        o.put("flash", flash)
        js("Reader.goTo(${loc.chapter},$o)")
        dismissLookup()
    }

    fun goToAnchor(ch: Int, anchor: String) {
        history.add(currentLoc())
        val o = JSONObject().apply { if (anchor.isNotEmpty()) put("anchor", anchor) else put("progress", 0) }
        js("Reader.goTo($ch,$o)")
        dismissLookup()
    }

    fun goBack() {
        val loc = history.removeLastOrNull() ?: return
        goTo(loc, remember = false, flash = false)
    }

    fun startTrail(label: String, color: Int?, items: List<Loc>, index: Int) {
        if (items.isEmpty()) return
        val i = index.coerceIn(0, items.lastIndex)
        trail = Trail(label, color, items, i)
        panel = Panel.None
        chromeVisible = false
        goTo(items[i], remember = true)
    }

    fun trailStep(d: Int) {
        val t = trail ?: return
        val i = (t.index + d).coerceIn(0, t.items.lastIndex)
        if (i == t.index) return
        trail = t.copy(index = i)
        goTo(t.items[i], remember = false)
    }

    fun page(dir: Int) {
        js("Reader.page($dir)")
    }

    // ------------------------------------------------------------------ taps from JS

    fun onTap(x: Float, y: Float) {
        if (footnote != null) { footnote = null; return }
        if (lookup != null) { dismissLookup(); return }
        if (settings.tapZones && !chromeVisible && y > 0.12f && y < 0.92f) {
            if (x < 0.22f) { page(-1); return }
            if (x > 0.78f) { page(1); return }
        }
        chromeVisible = !chromeVisible
    }

    fun onUserScroll() {
        footnote = null
        if (lookup != null) dismissLookup()
        if (chromeVisible) chromeVisible = false
    }

    fun onWordTap(word: String, ch: Int, s: Int, e: Int, rect: Box, annId: Long) {
        chromeVisible = false
        lookupWord(word, ch, s, e, rect, annId)
    }

    fun onAnnotationTap(id: Long, rect: Box) {
        val a = annotations.firstOrNull { it.id == id } ?: return
        editAnnotation(a)
    }

    fun onInternalLink(ch: Int, anchor: String) = goToAnchor(ch, anchor)

    fun openExternal(url: String) {
        runCatching {
            app.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    // ------------------------------------------------------------------ dictionary

    fun lookupWord(word: String, ch: Int, s: Int, e: Int, rect: Box, annId: Long = 0) {
        val clean = word.trim()
        if (clean.isEmpty()) return
        val lk = Lookup(clean, ch, s, e, rect, annId)
        lookup = lk
        js("Reader.setPopupOpen(true)")
        scope.launch {
            lk.offline = withContext(Dispatchers.IO) { runCatching { app.dict.offline(clean) }.getOrDefault(emptyList()) }
        }
        scope.launch { fetchOnline(lk) }
    }

    private suspend fun fetchOnline(lk: Lookup) {
        val cached = withContext(Dispatchers.IO) { runCatching { app.dict.cached(lk.word) }.getOrNull() }
        if (!cached.isNullOrEmpty()) {
            lk.online = cached
            lk.onlineState = OnlineState.Done
            return
        }
        if (!settings.onlineDictionary) { lk.onlineState = OnlineState.Disabled; return }
        if (!app.dict.isOnline()) { lk.onlineState = OnlineState.NoNetwork; return }
        lk.onlineState = OnlineState.Loading
        try {
            val r = withContext(Dispatchers.IO) { app.dict.online(lk.word) }
            lk.online = r
            lk.onlineState = if (r.isEmpty()) OnlineState.NotFound else OnlineState.Done
        } catch (e: IOException) {
            Log.w("Reader", "online lookup network error", e)
            lk.onlineState = OnlineState.Failed
        } catch (e: Exception) {
            Log.w("Reader", "online lookup", e)
            lk.onlineState = OnlineState.Failed
        }
    }

    fun retryOnline() {
        val lk = lookup ?: return
        lk.preferOnline = true
        scope.launch { fetchOnline(lk) }
    }

    fun dismissLookup() {
        if (lookup != null) {
            lookup = null
            js("Reader.setPopupOpen(false)")
        }
    }

    fun speak(text: String) {
        val t = tts
        if (t != null && ttsReady) {
            if (t.speak(text, TextToSpeech.QUEUE_FLUSH, null, "w") == TextToSpeech.ERROR) toast(R.string.reader_tts_failed)
            return
        }
        pendingSpeech = text
        if (t != null) return // still starting up; the pending word is spoken once it's ready
        // The init callback can arrive before the constructor returns, so hold it until `engine` is assigned.
        lateinit var engine: TextToSpeech
        var constructed = false
        var early: Int? = null
        engine = TextToSpeech(app) { status -> if (constructed) onTtsInit(engine, status) else early = status }
        constructed = true
        tts = engine
        early?.let { onTtsInit(engine, it) }
    }

    private fun onTtsInit(engine: TextToSpeech, status: Int) {
        if (tts !== engine) { engine.shutdown(); return } // reader closed meanwhile
        if (status != TextToSpeech.SUCCESS) {
            // Drop the engine so the next tap tries again (e.g. after the user installs a voice).
            engine.shutdown()
            tts = null
            ttsReady = false
            pendingSpeech = null
            toast(R.string.reader_tts_unavailable)
            return
        }
        ttsReady = true
        val lang = engine.setLanguage(Locale.US)
        if (lang == TextToSpeech.LANG_MISSING_DATA || lang == TextToSpeech.LANG_NOT_SUPPORTED) toast(R.string.reader_tts_no_voice)
        pendingSpeech?.let { engine.speak(it, TextToSpeech.QUEUE_FLUSH, null, "w") }
        pendingSpeech = null
    }

    fun copy(text: String) {
        val cm = app.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("text", text))
        toast(R.string.reader_copied)
    }

    private fun toast(res: Int) = Toast.makeText(app, res, Toast.LENGTH_SHORT).show()

    /** Runs a database change; a failure is reported instead of crashing the reader. */
    private fun launchDb(block: suspend CoroutineScope.() -> Unit) = scope.launch {
        try {
            block()
        } catch (c: CancellationException) {
            throw c
        } catch (e: Exception) {
            Log.w("Reader", "database", e)
            toast(R.string.reader_save_failed)
        }
    }

    fun webSearch(text: String) = openExternal("https://www.google.com/search?q=" + Uri.encode("define $text"))

    // ------------------------------------------------------------------ selection actions (from WebView menu)

    fun onSelectionAction(action: Int) {
        val w = web ?: return
        w.evaluateJavascript("Reader.captureSelection()") { res ->
            if (res == null || res == "null") return@evaluateJavascript
            val o = runCatching { JSONObject(res) }.getOrNull() ?: return@evaluateJavascript
            val ch = o.getInt("ch")
            val s = o.getInt("s")
            val e = o.getInt("e")
            val text = o.getString("text")
            val rect = Box(o.getDouble("l").toFloat(), o.getDouble("t").toFloat(), o.getDouble("r").toFloat(), o.getDouble("b").toFloat())
            js("Reader.clearSelection()")
            when (action) {
                ReaderWebView.ACTION_DEFINE -> lookupWord(text, ch, s, e, rect)
                ReaderWebView.ACTION_HIGHLIGHT -> quickHighlight(ch, s, e, text)
                ReaderWebView.ACTION_NOTE -> newDraft(ch, s, e, text, Draft.Focus.Note)
                ReaderWebView.ACTION_TAG -> newDraft(ch, s, e, text, Draft.Focus.Tag)
                ReaderWebView.ACTION_COPY -> copy(text)
            }
        }
    }

    // ------------------------------------------------------------------ annotations

    private suspend fun reloadAnnotations() {
        val (a, t) = withContext(Dispatchers.IO) { app.db.annotations(bookId) to app.db.tags(bookId) }
        tagSnapshot = t.associateBy { it.id }
        annSnapshot = a
        annotations.clear(); annotations.addAll(a)
        tags.clear(); tags.addAll(t)
    }

    /** Called by JS on a binder thread. */
    fun annotationsJson(ch: Int): String {
        val arr = JSONArray()
        val tagsById = tagSnapshot
        for (a in annSnapshot) {
            if (a.chapter != ch) continue
            arr.put(JSONObject().apply {
                put("id", a.id); put("s", a.start); put("e", a.end); put("c", a.color ?: "")
                val tc = a.tagIds.firstNotNullOfOrNull { tagsById[it] }?.color
                put("u", if (tc != null) Palette.cssColor(tc) else "")
                put("n", if (a.note.isNotBlank()) 1 else 0)
                put("q", a.quote) // lets the page re-anchor a highlight whose offsets no longer match the text
            })
        }
        return arr.toString()
    }

    fun tag(id: Long) = tags.firstOrNull { it.id == id }

    fun annotationAt(ch: Int, s: Int, e: Int) = annotations.firstOrNull { it.chapter == ch && it.start <= s && it.end >= e }

    private fun sameRange(ch: Int, s: Int, e: Int) = annotations.firstOrNull { it.chapter == ch && it.start == s && it.end == e }

    fun quickHighlight(ch: Int, s: Int, e: Int, quote: String) {
        val color = settings.lastColor
        val key = Triple(ch, s, e)
        if (!pendingHighlights.add(key)) return
        val existing = sameRange(ch, s, e)
        if (existing != null && existing.color == color) { pendingHighlights.remove(key); return }
        launchDb {
            try {
                // Re-highlighting a range recolours the existing annotation instead of stacking a duplicate.
                val id = withContext(Dispatchers.IO) {
                    app.db.saveAnnotation(existing?.copy(color = color) ?: Annotation(0, bookId, ch, s, e, quote, "", color, 0, emptyList()))
                }
                reloadAnnotations()
                js("Reader.refreshAnnotations($ch)")
                undo = UndoAction(app.getString(R.string.reader_highlighted)) {
                    if (existing != null) restoreAnnotation(existing, asNew = false) else removeAnnotation(id, offerUndo = false)
                }
            } finally {
                pendingHighlights.remove(key)
            }
        }
    }

    fun newDraft(ch: Int, s: Int, e: Int, quote: String, focus: Draft.Focus) {
        dismissLookup()
        val existing = sameRange(ch, s, e)
        if (existing != null) { editAnnotation(existing, focus); return }
        val suggested = quote.trim().takeIf { it.length in 1..40 && '\n' !in it }
        draft = Draft(
            id = 0, chapter = ch, start = s, end = e, quote = quote, note = "",
            color = if (focus == Draft.Focus.Tag) null else settings.lastColor,
            tagIds = if (focus == Draft.Focus.Tag) setOfNotNull(tags.firstOrNull { it.name.equals(suggested, true) }?.id) else emptySet(),
            focus = focus, suggestedTag = suggested,
        )
    }

    fun editAnnotation(a: Annotation, focus: Draft.Focus = Draft.Focus.None) {
        dismissLookup()
        draft = Draft(a.id, a.chapter, a.start, a.end, a.quote, a.note, a.color, a.tagIds.toSet(), focus, null, a.created)
    }

    fun saveDraft(d: Draft) {
        if (saving) return
        saving = true
        launchDb {
            try {
                val linked = LINK_RE.findAll(d.note).map { it.groupValues[1].trim() }.filter { it.isNotEmpty() }.toList()
                // A new draft over a range that already has an annotation updates that one rather than adding a copy.
                val target = if (d.id == 0L) sameRange(d.chapter, d.start, d.end) else null
                withContext(Dispatchers.IO) {
                    val ids = d.tagIds.toMutableSet()
                    for (name in linked) ids += app.db.createTag(bookId, name, nextTagColor())
                    app.db.saveAnnotation(
                        Annotation(
                            target?.id ?: d.id, bookId, d.chapter, d.start, d.end, d.quote, d.note.trim(), d.color,
                            target?.created ?: d.created, ids.toList(),
                        )
                    )
                }
                if (d.color != null) app.settings.update { it.copy(lastColor = d.color) }
                draft = null
                reloadAnnotations()
                js("Reader.refreshAnnotations(${d.chapter})")
            } finally {
                saving = false
            }
        }
    }

    fun deleteAnnotation(id: Long) = removeAnnotation(id, offerUndo = true)

    private fun removeAnnotation(id: Long, offerUndo: Boolean) {
        val removed = annotations.firstOrNull { it.id == id }
        val ch = removed?.chapter ?: -1
        launchDb {
            withContext(Dispatchers.IO) { app.db.deleteAnnotation(id) }
            draft = null
            reloadAnnotations()
            js("Reader.refreshAnnotations($ch)")
            undo = if (offerUndo && removed != null) {
                UndoAction(app.getString(if (removed.note.isNotBlank()) R.string.reader_note_removed else R.string.reader_highlight_removed)) {
                    restoreAnnotation(removed)
                }
            } else null
        }
    }

    /** Puts an annotation back as it was (with its note and tags) — used by Undo. */
    private fun restoreAnnotation(a: Annotation, asNew: Boolean = true) {
        launchDb {
            withContext(Dispatchers.IO) { app.db.saveAnnotation(if (asNew) a.copy(id = 0) else a) }
            reloadAnnotations()
            js("Reader.refreshAnnotations(${a.chapter})")
            undo = null
        }
    }

    fun runUndo() {
        val u = undo ?: return
        undo = null
        u.action()
    }

    private fun nextTagColor(): Int = Palette.tagColors[tags.size % Palette.tagColors.size]

    /** Creates the tag (or returns the existing one with that name); null if it couldn't be stored. */
    suspend fun createTag(name: String): Long? = try {
        val id = withContext(Dispatchers.IO) { app.db.createTag(bookId, name.trim(), nextTagColor()) }
        reloadAnnotations()
        id
    } catch (c: CancellationException) {
        throw c
    } catch (e: Exception) {
        Log.w("Reader", "create tag", e)
        toast(R.string.reader_save_failed)
        null
    }

    fun updateTag(t: Tag) {
        launchDb {
            val ok = withContext(Dispatchers.IO) { app.db.updateTag(t) }
            if (!ok) toast(R.string.reader_tag_exists)
            reloadAnnotations()
            js("Reader.refreshAnnotations(-1)")
        }
    }

    fun deleteTag(id: Long) {
        launchDb {
            withContext(Dispatchers.IO) { app.db.deleteTag(id) }
            reloadAnnotations()
            panel = Panel.Notes
            js("Reader.refreshAnnotations(-1)")
        }
    }

    fun annotationsForTag(tagId: Long) = annotations.filter { tagId in it.tagIds }.sortedWith(compareBy({ it.chapter }, { it.start }))

    fun locOf(a: Annotation) = Loc(a.chapter, start = a.start, end = a.end)

    /** Quick "tag this word" from the dictionary card: one tap creates/links the tag. */
    fun tagWord(lk: Lookup) {
        dismissLookup()
        newDraft(lk.chapter, lk.start, lk.end, lk.word, Draft.Focus.Tag)
    }

    // ------------------------------------------------------------------ search

    /** In-book search (the search panel). Always ends with [searching] false, whether it finishes, fails or is replaced. */
    fun search(query: String) {
        val q = query.trim()
        searchJob?.cancel()
        searchJob = null
        if (q.length < 2) {
            cancelPdfSearch(SEARCH)
            searchResults = null
            searching = false
            return
        }
        searching = true
        searchProgress = 0f
        val done: (List<SearchHit>) -> Unit = { hits -> searching = false; searchResults = hits }
        if (isPdf) {
            startPdfSearch(SEARCH, q, whole = false, onProgress = { searchProgress = it }, onCancel = { searching = false }, onDone = done)
            return
        }
        val b = book ?: run { done(emptyList()); return }
        searchJob = scope.launch {
            try {
                done(scanBook(b, q, whole = false) { searchProgress = it })
            } catch (c: CancellationException) {
                throw c
            } catch (e: Exception) {
                Log.w("Reader", "search", e)
                done(emptyList())
            }
        }
    }

    /**
     * Whole-word occurrences of a tag name for its page. Kept apart from the search panel's state so the two
     * don't cancel or overwrite each other; cancelled together with the caller (the tag page leaving).
     */
    suspend fun findMentions(name: String): List<SearchHit> {
        val q = name.trim()
        if (q.isEmpty()) return emptyList()
        if (!isPdf) {
            val b = book ?: return emptyList()
            return try {
                scanBook(b, q, whole = true) {}
            } catch (c: CancellationException) {
                throw c
            } catch (e: Exception) {
                Log.w("Reader", "mentions", e)
                emptyList()
            }
        }
        return suspendCancellableCoroutine { cont ->
            val s = startPdfSearch(MENTIONS, q, whole = true, onProgress = {}, onCancel = { cont.cancel() }) { cont.resume(it) }
            cont.invokeOnCancellation {
                main.post { if (pdfSearch === s) { pdfSearch = null; js("Reader.cancelSearch()") } }
            }
        }
    }

    private suspend fun scanBook(b: Book, q: String, whole: Boolean, onProgress: (Float) -> Unit): List<SearchHit> =
        withContext(Dispatchers.Default) {
            val out = mutableListOf<SearchHit>()
            val nq = q.replace(Regex("\\s+"), " ").lowercase()
            for (i in 0 until b.chapterCount) {
                ensureActive() // stop when the reader closes or a newer search starts
                val text = b.chapterText(i)
                val lower = text.lowercase()
                var from = 0
                var k = 0
                while (out.size < 1000) {
                    val p = lower.indexOf(nq, from)
                    if (p < 0) break
                    from = p + 1
                    if (whole && !(isBoundary(lower, p - 1) && isBoundary(lower, p + nq.length))) continue
                    val a = maxOf(0, p - 50)
                    val e = minOf(text.length, p + nq.length + 70)
                    out += SearchHit(i, k++, text.substring(a, e), p - a, nq.length)
                }
                onProgress((i + 1f) / b.chapterCount)
            }
            out
        }

    private fun startPdfSearch(
        owner: Any, q: String, whole: Boolean,
        onProgress: (Float) -> Unit, onCancel: () -> Unit, onDone: (List<SearchHit>) -> Unit,
    ): PdfSearch {
        // The page runs one PDF search at a time; starting another replaces the previous one.
        pdfSearch?.onCancel?.invoke()
        val s = PdfSearch(owner, ++pdfToken, onProgress, onCancel, onDone)
        pdfSearch = s
        js("Reader.searchPdf(${JSONObject.quote(q)},$whole,${s.token})")
        return s
    }

    private fun cancelPdfSearch(owner: Any) {
        if (pdfSearch?.owner !== owner) return
        pdfSearch = null
        js("Reader.cancelSearch()")
    }

    fun onPdfSearchResults(token: Int, json: String) {
        val s = pdfSearch?.takeIf { it.token == token } ?: return
        pdfSearch = null
        val hits = runCatching {
            val arr = JSONArray(json)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                SearchHit(o.getInt("ch"), o.getInt("nth"), o.getString("snip"), o.getInt("at"), o.getInt("len"))
            }
        }.getOrDefault(emptyList())
        s.onDone(hits)
    }

    fun onPdfSearchProgress(token: Int, f: Float) {
        pdfSearch?.takeIf { it.token == token }?.onProgress?.invoke(f)
    }

    fun clearSearch() {
        searchJob?.cancel()
        searchJob = null
        cancelPdfSearch(SEARCH)
        searchResults = null
        searching = false
    }

    // ------------------------------------------------------------------ export

    fun exportNotes(ctx: Context) {
        val e = entity ?: return
        val sb = StringBuilder("# ").append(e.title).append('\n')
        if (e.author.isNotBlank()) sb.append("*").append(e.author).append("*\n")
        var lastCh = -1
        for (a in annotations) {
            if (a.chapter != lastCh) { sb.append("\n## ").append(chapterTitle(a.chapter)).append("\n\n"); lastCh = a.chapter }
            sb.append("> ").append(a.quote.replace("\n", "\n> ")).append('\n')
            if (a.note.isNotBlank()) sb.append('\n').append(a.note).append('\n')
            val names = a.tagIds.mapNotNull { tag(it)?.name }
            if (names.isNotEmpty()) sb.append('\n').append(names.joinToString(" ") { "#" + it.replace(' ', '_') }).append('\n')
            sb.append('\n')
        }
        if (tags.isNotEmpty()) {
            sb.append("\n## ").append(app.getString(R.string.reader_tags)).append("\n\n")
            for (t in tags) sb.append("- **").append(t.name).append("**").append(if (t.description.isNotBlank()) " — ${t.description}" else "").append('\n')
        }
        val text = sb.toString()
        val subject = app.getString(R.string.reader_export_subject, e.title)
        // Shared as a file: a big notebook in EXTRA_TEXT would overflow the binder limit and crash startActivity.
        scope.launch {
            val uri = try {
                withContext(Dispatchers.IO) {
                    val dir = File(app.cacheDir, "exports").apply { mkdirs() }
                    dir.listFiles()?.forEach { it.delete() } // only the latest export is kept
                    val safe = e.title.replace(Regex("[^\\p{L}\\p{N} ._-]"), "_").trim().take(60).ifEmpty { "book" }
                    val f = File(dir, "$safe - notes.md")
                    f.writeText(text)
                    FileProvider.getUriForFile(app, "${app.packageName}.fileprovider", f)
                }
            } catch (c: CancellationException) {
                throw c
            } catch (ex: Exception) {
                Log.w("Reader", "export", ex)
                toast(R.string.reader_export_failed)
                return@launch
            }
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_SUBJECT, subject)
                putExtra(Intent.EXTRA_STREAM, uri)
                if (text.length <= 50_000) putExtra(Intent.EXTRA_TEXT, text) // lets chat/notes apps take it as text too
                clipData = ClipData.newRawUri(subject, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            try {
                ctx.startActivity(Intent.createChooser(send, app.getString(R.string.reader_export_notes)))
            } catch (ex: ActivityNotFoundException) {
                toast(R.string.reader_no_share_app)
            }
        }
    }

    fun dispose() {
        flushPosition()
        searchJob?.cancel()
        pdfSearch = null
        tts?.shutdown()
        tts = null
        pendingSpeech = null
        web = null
        book?.close()
    }

    private val main = Handler(Looper.getMainLooper())

    companion object {
        private val SEARCH = Any()
        private val MENTIONS = Any()

        val LINK_RE = Regex("\\[\\[([^\\[\\]]{1,60})]]")
        private val WORD = Regex("[\\p{L}\\p{M}\\p{N}'’-]")
        fun isBoundary(s: String, i: Int) = i < 0 || i >= s.length || !WORD.matches(s[i].toString())
    }
}
