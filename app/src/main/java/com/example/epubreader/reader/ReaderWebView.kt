package com.example.epubreader.reader

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.ActionMode
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.annotation.RequiresApi
import androidx.webkit.WebViewAssetLoader
import com.example.epubreader.book.EpubBook
import com.example.epubreader.book.LimitedInputStream
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileInputStream

/**
 * WebView that replaces the system text-selection menu with reader actions
 * (Define · Highlight · Note · Tag · Copy) while keeping the native selection handles.
 */
@SuppressLint("ViewConstructor")
class ReaderWebView(ctx: Context) : WebView(ctx) {
    var onSelectionAction: ((Int) -> Unit)? = null

    override fun startActionMode(callback: ActionMode.Callback?, type: Int): ActionMode? =
        super.startActionMode(wrap(callback), type)

    override fun startActionMode(callback: ActionMode.Callback?): ActionMode? =
        super.startActionMode(wrap(callback))

    private fun wrap(cb: ActionMode.Callback?): ActionMode.Callback? {
        if (cb == null) return null
        return object : ActionMode.Callback2() {
            override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean {
                cb.onCreateActionMode(mode, menu)
                build(menu)
                return true
            }

            override fun onPrepareActionMode(mode: ActionMode, menu: Menu): Boolean {
                cb.onPrepareActionMode(mode, menu)
                build(menu)
                return true
            }

            override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean {
                val h = onSelectionAction
                if (h != null && item.itemId in ACTIONS) {
                    h(item.itemId)
                    mode.finish()
                    return true
                }
                return cb.onActionItemClicked(mode, item)
            }

            override fun onDestroyActionMode(mode: ActionMode) = cb.onDestroyActionMode(mode)

            override fun onGetContentRect(mode: ActionMode, view: View, outRect: Rect) {
                if (cb is ActionMode.Callback2) cb.onGetContentRect(mode, view, outRect)
                else super.onGetContentRect(mode, view, outRect)
            }
        }
    }

    private fun build(menu: Menu) {
        menu.clear()
        menu.add(0, ACTION_DEFINE, 0, "Define")
        menu.add(0, ACTION_HIGHLIGHT, 1, "Highlight")
        menu.add(0, ACTION_NOTE, 2, "Note")
        menu.add(0, ACTION_TAG, 3, "Tag")
        menu.add(0, ACTION_COPY, 4, "Copy")
    }

    companion object {
        const val ACTION_DEFINE = 0x5101
        const val ACTION_HIGHLIGHT = 0x5102
        const val ACTION_NOTE = 0x5103
        const val ACTION_TAG = 0x5104
        const val ACTION_COPY = 0x5105
        val ACTIONS = setOf(ACTION_DEFINE, ACTION_HIGHLIGHT, ACTION_NOTE, ACTION_TAG, ACTION_COPY)
        const val ORIGIN = "https://appassets.androidplatform.net"
    }
}

/** JS → Kotlin bridge. Methods run on a WebView binder thread, so UI work is posted to main. */
class ReaderBridge(private val m: ReaderModel) {
    private val main = Handler(Looper.getMainLooper())
    private fun ui(f: () -> Unit) { main.post(f) }

    @JavascriptInterface fun onLoaded() = ui { m.onPageLoaded() }
    @JavascriptInterface fun onReady() = ui { m.onReady() }
    @JavascriptInterface fun onError(msg: String) = ui { m.onError(msg) }
    @JavascriptInterface fun log(msg: String) { Log.d("ReaderJS", msg) }
    @JavascriptInterface fun getAnnotations(ch: Int): String = m.annotationsJson(ch)
    @JavascriptInterface fun onPosition(ch: Int, p: Float) = ui { m.onPosition(ch, p) }
    @JavascriptInterface fun onTap(x: Float, y: Float) = ui { m.onTap(x, y) }
    @JavascriptInterface fun onUserScroll() = ui { m.onUserScroll() }
    @JavascriptInterface fun onInternalLink(ch: Int, anchor: String) = ui { m.onInternalLink(ch, anchor) }
    @JavascriptInterface fun openExternal(url: String) = ui { m.openExternal(url) }
    @JavascriptInterface fun onPdfInfo(json: String) = ui { m.onPdfInfo(json) }
    @JavascriptInterface fun onSearchResults(token: Int, json: String) = ui { m.onPdfSearchResults(token, json) }
    @JavascriptInterface fun onSearchProgress(token: Int, f: Float) = ui { m.onPdfSearchProgress(token, f) }
    @JavascriptInterface fun onPage(page: Int, total: Int, chPage: Int, chPages: Int) = ui { m.onPage(page, total, chPage, chPages) }
    @JavascriptInterface fun onPages(json: String) = ui { m.onPages(json) }

    @JavascriptInterface
    fun onFootnote(text: String, ch: Int, anchor: String, l: Float, t: Float, r: Float, b: Float) =
        ui { m.onFootnote(text, ch, anchor, Box(l, t, r, b)) }

    @JavascriptInterface
    fun onWordTap(word: String, ch: Int, s: Int, e: Int, l: Float, t: Float, r: Float, b: Float, ann: Int) =
        ui { m.onWordTap(word, ch, s, e, Box(l, t, r, b), ann.toLong()) }

    @JavascriptInterface
    fun onAnnotationTap(id: Int, l: Float, t: Float, r: Float, b: Float) = ui { m.onAnnotationTap(id.toLong(), Box(l, t, r, b)) }
}

/** Parses a single-range HTTP `Range` header against a resource of [length] bytes. */
internal object HttpRange {
    sealed interface Result
    /** No usable Range header: serve the whole resource. */
    data object Whole : Result
    data class Part(val start: Long, val end: Long) : Result
    data object Unsatisfiable : Result

    fun parse(header: String?, length: Long): Result {
        val h = header?.trim() ?: return Whole
        if (!h.startsWith("bytes=", ignoreCase = true)) return Whole
        val spec = h.substring(6).trim()
        if (',' in spec) return Whole // multipart ranges aren't supported; a full response is valid
        val dash = spec.indexOf('-')
        if (dash < 0) return Whole
        val a = spec.substring(0, dash).trim()
        val b = spec.substring(dash + 1).trim()
        if (a.isEmpty()) {
            // suffix range: the last N bytes
            val n = b.toLongOrNull() ?: return Whole
            if (n <= 0 || length == 0L) return Unsatisfiable
            return Part(maxOf(0, length - n), length - 1)
        }
        val start = a.toLongOrNull() ?: return Whole
        val end = if (b.isEmpty()) length - 1 else (b.toLongOrNull() ?: return Whole).coerceAtMost(length - 1)
        if (start >= length || start > end) return Unsatisfiable
        return Part(start, end)
    }
}

/** Serves the book file with byte-range support, so pdf.js can load large PDFs piece by piece. */
internal fun serveFile(f: File, mime: String, rangeHeader: String?): WebResourceResponse {
    val len = f.length()
    val base = mapOf("Accept-Ranges" to "bytes", "Cache-Control" to "no-store", "Access-Control-Expose-Headers" to "Accept-Ranges, Content-Range, Content-Length")
    return when (val r = HttpRange.parse(rangeHeader, len)) {
        HttpRange.Whole -> WebResourceResponse(mime, null, 200, "OK", base + ("Content-Length" to len.toString()), FileInputStream(f))
        HttpRange.Unsatisfiable -> WebResourceResponse(
            mime, null, 416, "Range Not Satisfiable", base + ("Content-Range" to "bytes */$len"), ByteArrayInputStream(ByteArray(0)),
        )
        is HttpRange.Part -> {
            val count = r.end - r.start + 1
            val input = FileInputStream(f)
            try {
                input.channel.position(r.start)
            } catch (t: Throwable) {
                input.close(); throw t
            }
            WebResourceResponse(
                mime, null, 206, "Partial Content",
                base + mapOf("Content-Range" to "bytes ${r.start}-${r.end}/$len", "Content-Length" to count.toString()),
                LimitedInputStream(input, count, failOnOverflow = false),
            )
        }
    }
}

/** Serves chapters, embedded resources and the raw PDF to the reader page under /book/. */
class BookPathHandler(private val m: ReaderModel) : WebViewAssetLoader.PathHandler {
    override fun handle(path: String): WebResourceResponse? = handle(path, null)

    /** Never throws: errors (including running out of memory on a huge chapter) become HTTP errors. */
    fun handle(path: String, rangeHeader: String?): WebResourceResponse? = try {
        when {
            path.startsWith("chapter/") -> {
                val i = path.removePrefix("chapter/").toInt()
                val html = m.book?.chapterHtml(i) ?: ""
                WebResourceResponse("text/html", "utf-8", ByteArrayInputStream(html.toByteArray()))
            }
            path.startsWith("res/") -> {
                m.book?.resource(path.removePrefix("res/"))?.let { WebResourceResponse(it.mime, null, it.stream) }
                    ?: WebResourceResponse(EpubBook.mimeOf(path), null, 404, "Not Found", emptyMap(), ByteArrayInputStream(ByteArray(0)))
            }
            path == "file" -> {
                val f = File(m.entity?.path ?: return null)
                if (!f.isFile) notFound("application/pdf") else serveFile(f, "application/pdf", rangeHeader)
            }
            else -> null
        }
    } catch (e: Throwable) {
        Log.w("Reader", "serve $path", e)
        WebResourceResponse("text/plain", "utf-8", 500, "Error", emptyMap(), ByteArrayInputStream(ByteArray(0)))
    }

    private fun notFound(mime: String) = WebResourceResponse(mime, null, 404, "Not Found", emptyMap(), ByteArrayInputStream(ByteArray(0)))
}

@SuppressLint("SetJavaScriptEnabled")
fun createReaderWebView(ctx: Context, model: ReaderModel, bg: Int): ReaderWebView {
    if (ctx.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0) {
        WebView.setWebContentsDebuggingEnabled(true)
    }
    val wv = ReaderWebView(ctx)
    wv.setBackgroundColor(bg)
    wv.settings.apply {
        javaScriptEnabled = true
        domStorageEnabled = true
        allowFileAccess = false
        allowContentAccess = false
        setSupportZoom(false)
        builtInZoomControls = false
        displayZoomControls = false
        textZoom = 100
        // pdf.js sizes its invisible text layer in tiny fonts; WebView's default 8px minimum
        // would stretch it out of line with the rendered page and break word taps/selection.
        minimumFontSize = 1
        minimumLogicalFontSize = 1
        mediaPlaybackRequiresUserGesture = true
    }
    wv.isVerticalScrollBarEnabled = false
    wv.isHorizontalScrollBarEnabled = false
    wv.overScrollMode = View.OVER_SCROLL_NEVER
    val books = BookPathHandler(model)
    val loader = WebViewAssetLoader.Builder()
        .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(ctx))
        .addPathHandler("/book/", books)
        .build()
    wv.webViewClient = object : WebViewClient() {
        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
            val url = request.url
            // The asset loader's PathHandler can't see request headers, so the PDF (which needs Range) is served here.
            if (url.toString().startsWith(ReaderWebView.ORIGIN) && url.path == "/book/file") {
                val range = request.requestHeaders?.entries?.firstOrNull { it.key.equals("Range", ignoreCase = true) }?.value
                return books.handle("file", range)
            }
            return loader.shouldInterceptRequest(url)
        }

        /**
         * The renderer crashed or was killed for memory (large PDFs). Without this the whole app dies.
         * This WebView is unusable now: detach and destroy it, and let the model show an error / rebuild.
         */
        @RequiresApi(Build.VERSION_CODES.O)
        override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
            Log.w("Reader", "WebView renderer gone (crash=${detail.didCrash()})")
            if (model.web === view) model.web = null
            model.onRendererGone()
            (view.parent as? ViewGroup)?.removeView(view)
            view.destroy()
            return true
        }

        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            val url = request.url.toString()
            if (!url.startsWith(ReaderWebView.ORIGIN)) model.openExternal(url)
            return true
        }
    }
    wv.addJavascriptInterface(ReaderBridge(model), "Android")
    wv.onSelectionAction = { model.onSelectionAction(it) }
    model.web = wv
    wv.loadUrl("${ReaderWebView.ORIGIN}/assets/reader/reader.html")
    return wv
}
