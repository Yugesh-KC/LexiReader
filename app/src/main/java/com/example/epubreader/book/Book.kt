package com.example.epubreader.book

import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.Entities
import org.jsoup.nodes.TextNode
import org.jsoup.select.NodeTraversor
import org.jsoup.select.NodeVisitor
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.util.Collections
import java.util.WeakHashMap

data class TocEntry(val title: String, val chapter: Int, val anchor: String, val depth: Int)

data class Resource(val mime: String, val stream: InputStream)

/**
 * A reflowable book: every format except PDF is converted into a list of HTML chapters that the
 * WebView engine renders. PDF is rendered by pdf.js directly and has no [Book] instance.
 */
interface Book {
    val title: String
    val author: String
    val chapterCount: Int
    /** Relative chapter sizes, used to compute a whole-book percentage. */
    val weights: List<Long>
    val toc: List<TocEntry>
    fun chapterHtml(i: Int): String
    fun resource(path: String): Resource?
    fun cover(): ByteArray?
    fun close() {}

    /** Whitespace-collapsed text of a chapter, matching what the reader's JS search sees. */
    fun chapterText(i: Int): String = TextCache.get(this, i) { plainText(chapterHtml(i)) }

    fun chapterTitle(i: Int): String =
        toc.lastOrNull { it.chapter == i && it.depth == 0 }?.title
            ?: toc.firstOrNull { it.chapter == i }?.title
            ?: toc.lastOrNull { it.chapter in 0 until i }?.title
            ?: "Section ${i + 1}"

    companion object {
        val FORMATS = mapOf(
            "epub" to "epub", "pdf" to "pdf", "txt" to "txt", "text" to "txt", "md" to "md",
            "markdown" to "md", "html" to "html", "htm" to "html", "xhtml" to "html", "fb2" to "fb2",
        )

        fun open(file: File, format: String): Book = guardMemory {
            when (format) {
                "epub" -> EpubBook(file)
                "txt" -> TextBook(file, markdown = false)
                "md" -> TextBook(file, markdown = true)
                "html" -> HtmlBook(file)
                "fb2" -> Fb2Book(file)
                else -> throw IllegalArgumentException("Unsupported format: $format")
            }
        }
    }
}

/** A book (or a part of one) that is too big to load safely. The message is meant for the user. */
class BookTooLargeException(message: String) : IOException(message)

object Limits {
    /** Whole-file formats (TXT, Markdown, HTML, FB2) are parsed in memory several times over. */
    const val MAX_TEXT_FILE = 50L * 1024 * 1024
    /** A single decompressed EPUB entry: guards against zip bombs. */
    const val MAX_ZIP_ENTRY = 32L * 1024 * 1024
    const val MAX_COVER = 10L * 1024 * 1024
    /** Streamed EPUB resources (images, fonts, media) are cut off past this. */
    const val MAX_RESOURCE = 64L * 1024 * 1024

    fun mb(bytes: Long) = (bytes + (1 shl 20) - 1) / (1 shl 20)
}

/** Turns an [OutOfMemoryError] while parsing into a normal, user-readable exception. */
inline fun <T> guardMemory(block: () -> T): T = try {
    block()
} catch (_: OutOfMemoryError) {
    throw BookTooLargeException("Not enough memory to open this book. It may be too large for this device.")
}

/** Reads a whole file, refusing ones larger than [max] instead of running out of memory. */
fun readCapped(file: File, max: Long = Limits.MAX_TEXT_FILE): ByteArray {
    val len = file.length()
    if (len > max) throw BookTooLargeException("This book is too large to open (${Limits.mb(len)} MB; the limit is ${Limits.mb(max)} MB).")
    return file.inputStream().use { it.readBounded(max) }
}

/** Reads at most [max] bytes; throws [BookTooLargeException] if the stream holds more (e.g. a zip bomb). */
fun InputStream.readBounded(max: Long): ByteArray {
    val out = ByteArrayOutputStream()
    val buf = ByteArray(64 * 1024)
    var total = 0L
    while (true) {
        val n = read(buf)
        if (n < 0) break
        total += n
        if (total > max) throw BookTooLargeException("Part of this book is too large to open (over ${Limits.mb(max)} MB).")
        out.write(buf, 0, n)
    }
    return out.toByteArray()
}

/**
 * Passes through at most [limit] bytes. With [failOnOverflow] a stream holding more than that throws
 * (untrusted zip entries); otherwise it simply ends at the limit (serving HTTP byte ranges).
 */
class LimitedInputStream(input: InputStream, private val limit: Long, private val failOnOverflow: Boolean) : FilterInputStream(input) {
    private var left = limit

    override fun read(): Int {
        if (left <= 0) return atLimit()
        val b = super.read()
        if (b >= 0) left--
        return b
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (len == 0) return 0
        if (left <= 0) return atLimit()
        val n = super.read(b, off, minOf(len.toLong(), left).toInt())
        if (n > 0) left -= n
        return n
    }

    override fun skip(n: Long): Long {
        val s = super.skip(minOf(n, left.coerceAtLeast(0)))
        left -= s
        return s
    }

    override fun available() = minOf(super.available().toLong(), left.coerceAtLeast(0)).toInt()

    override fun markSupported() = false

    /** At the limit: EOF, unless the underlying stream still has data and overflow must be reported. */
    private fun atLimit(): Int {
        if (failOnOverflow && super.read() >= 0) throw IOException("Resource exceeds ${Limits.mb(limit)} MB")
        return -1
    }
}

/** Per-book plain-text cache. Weak keys, so a closed and dropped book doesn't keep its text alive. */
internal object TextCache {
    private const val PER_BOOK = 32
    private val books = Collections.synchronizedMap(WeakHashMap<Book, MutableMap<Int, String>>())

    private fun mapFor(book: Book): MutableMap<Int, String> = synchronized(books) {
        books.getOrPut(book) {
            object : LinkedHashMap<Int, String>(16, .75f, true) {
                override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, String>?) = size > PER_BOOK
            }
        }
    }

    fun get(book: Book, i: Int, compute: () -> String): String {
        val map = mapFor(book)
        synchronized(map) { map[i] }?.let { return it }
        val v = compute() // outside the lock: parsing one chapter mustn't block lookups of others
        return synchronized(map) { map.getOrPut(i) { v } }
    }

    fun evict(book: Book) { books.remove(book) }
}

/** Concatenate raw text nodes (like DOM textContent) and collapse whitespace runs. */
fun plainText(html: String): String {
    val body = Jsoup.parseBodyFragment(html).body()
    val sb = StringBuilder()
    NodeTraversor.traverse(NodeVisitor { node, _ -> if (node is TextNode) sb.append(node.wholeText) }, body)
    return sb.replace(Regex("\\s+"), " ")
}

fun escapeHtml(s: String): String = Entities.escape(s)

/** Small LRU used for processed chapter HTML. */
class HtmlLru(private val max: Int = 6) {
    private val map = object : LinkedHashMap<Int, String>(16, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, String>?) = size > max
    }

    /**
     * The lock is not held while [compute] runs, so a slow parse (e.g. the background measuring pass)
     * never blocks the chapter the user is waiting for. Two threads may occasionally compute the same chapter.
     */
    fun getOrPut(i: Int, compute: () -> String): String {
        synchronized(map) { map[i] }?.let { return it }
        val v = compute()
        return synchronized(map) { map.getOrPut(i) { v } }
    }
}

object Paths {
    fun dirOf(path: String) = path.substringBeforeLast('/', "")

    fun normalize(p: String): String {
        val out = ArrayDeque<String>()
        for (seg in p.split('/')) when (seg) {
            "", "." -> Unit
            ".." -> out.removeLastOrNull()
            else -> out.addLast(seg)
        }
        return out.joinToString("/")
    }

    fun resolve(baseDir: String, href: String): String {
        val clean = percentDecode(href.substringBefore('#').substringBefore('?'))
        if (clean.startsWith("/")) return normalize(clean)
        return normalize(if (baseDir.isEmpty()) clean else "$baseDir/$clean")
    }

    fun resUrl(path: String) = "/book/res/" + percentEncode(path)

    /** Decodes %XX escapes as UTF-8 ('+' stays literal, as in a URL path). Malformed escapes are kept as-is. */
    fun percentDecode(s: String): String {
        if ('%' !in s) return s
        val out = ByteArrayOutputStream(s.length)
        var i = 0
        while (i < s.length) {
            val hi = if (s[i] == '%' && i + 2 < s.length) Character.digit(s[i + 1], 16) else -1
            val lo = if (hi >= 0) Character.digit(s[i + 2], 16) else -1
            if (hi >= 0 && lo >= 0) {
                out.write(hi * 16 + lo)
                i += 3
            } else {
                val end = if (Character.isHighSurrogate(s[i]) && i + 1 < s.length) i + 2 else i + 1
                out.write(s.substring(i, end).toByteArray(Charsets.UTF_8))
                i = end
            }
        }
        return out.toString("UTF-8")
    }

    /** Percent-encodes everything except unreserved characters and '/' (like Uri.encode(path, "/")). */
    fun percentEncode(s: String): String {
        val hex = "0123456789ABCDEF"
        val sb = StringBuilder()
        for (b in s.toByteArray(Charsets.UTF_8)) {
            val c = b.toInt() and 0xFF
            val ch = c.toChar()
            if (c < 0x80 && (ch in 'a'..'z' || ch in 'A'..'Z' || ch in '0'..'9' || ch in "_-!.~'()*/")) sb.append(ch)
            else sb.append('%').append(hex[c shr 4]).append(hex[c and 15])
        }
        return sb.toString()
    }
}

fun decodeText(bytes: ByteArray): String {
    if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte())
        return String(bytes, 3, bytes.size - 3, Charsets.UTF_8)
    if (bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte())
        return String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE)
    if (bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte())
        return String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE)
    val head = String(bytes, 0, minOf(bytes.size, 400), Charsets.ISO_8859_1)
    Regex("""encoding=["']([\w.-]+)["']|charset=["']?([\w.-]+)""", RegexOption.IGNORE_CASE).find(head)?.let { m ->
        val name = m.groupValues[1].ifEmpty { m.groupValues[2] }
        runCatching { return String(bytes, Charset.forName(name)) }
    }
    return try {
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes)).toString()
    } catch (_: CharacterCodingException) {
        String(bytes, Charset.forName("windows-1252"))
    }
}

/** Shared HTML clean-up: strip scripts/publisher styling and rewrite links/resources for the reader. */
object HtmlClean {
    private val dropStyleProps = setOf(
        "color", "background", "background-color", "background-image", "font-family", "font-size",
        "line-height", "position", "height", "max-height",
    )
    private val voidTags = setOf(
        "area", "base", "br", "col", "embed", "hr", "img", "input", "link", "meta", "param", "source", "track", "wbr",
    )

    private fun cleanStyle(style: String): String = style.split(';').mapNotNull { decl ->
        if (!decl.contains(':')) return@mapNotNull null
        val k = decl.substringBefore(':').trim().lowercase()
        if (k.isEmpty() || k in dropStyleProps) null else decl.trim()
    }.joinToString("; ")

    /** XHTML self-closing tags like `<a id="x"/>` must not leak as open tags into HTML. */
    fun fixEmptyElements(root: Element) {
        for (el in root.getAllElements()) {
            if (el.childNodeSize() == 0 && el.normalName() !in voidTags) el.appendChild(TextNode(""))
        }
    }

    /** Elements removed outright, matched on the local name so `svg:script` etc. are caught too. */
    private val dropTags = setOf(
        "script", "style", "link", "meta", "title", "base", "iframe", "frame", "frameset", "object", "embed",
        "applet", "portal", "form", "input", "button", "textarea", "select", "template",
    )
    /** Attributes that hold a URL the browser may navigate to or load. */
    private val urlAttrs = setOf(
        "href", "src", "action", "formaction", "data", "poster", "background", "lowsrc", "dynsrc", "codebase",
        "cite", "longdesc", "usemap", "ping", "manifest", "to", "from", "values", "by",
    )
    private val badSchemes = listOf("javascript:", "vbscript:", "livescript:", "data:text/html", "data:application/xhtml")

    private fun localName(el: Element) = el.normalName().substringAfterLast(':')

    /** True for `javascript:` and friends, including tricks like "java\tscript:" or leading control characters. */
    fun isDangerousUrl(value: String): Boolean {
        val v = value.filter { it > ' ' }.lowercase()
        return badSchemes.any { v.startsWith(it) }
    }

    fun process(
        body: Element,
        resUrl: (String) -> String?,
        link: (String) -> Pair<Int, String>?,
    ): String {
        body.getAllElements().filter { it !== body && localName(it) in dropTags }.forEach { it.remove() }
        // SVG <set>/<animate> can rewrite an href to javascript: after load.
        body.getAllElements()
            .filter { localName(it).startsWith("animate") || localName(it) == "set" }
            .filter { it.attr("attributeName").contains("href", ignoreCase = true) }
            .forEach { it.remove() }
        for (el in body.getAllElements()) {
            val attrs = el.attributes().asList()
            for (a in attrs) {
                val key = a.key.lowercase()
                val local = key.substringAfterLast(':')
                when {
                    local.startsWith("on") || local == "srcdoc" || local == "formaction" -> el.removeAttr(a.key)
                    (local in urlAttrs || key.endsWith(":href")) && isDangerousUrl(a.value) -> el.removeAttr(a.key)
                }
            }
            if (el.hasAttr("style")) {
                val s = cleanStyle(el.attr("style"))
                if (s.isBlank()) el.removeAttr("style") else el.attr("style", s)
            }
        }
        for (img in body.select("img")) {
            val src = img.attr("src")
            val u = if (src.startsWith("data:")) src else resUrl(src)
            if (u != null) img.attr("src", u) else img.remove()
            img.removeAttr("srcset")
            img.attr("loading", "lazy")
        }
        for (img in body.getElementsByTag("image") + body.getElementsByTag("svg:image")) {
            val key = if (img.hasAttr("xlink:href")) "xlink:href" else "href"
            resUrl(img.attr(key))?.let { img.attr(key, it) }
        }
        for (el in body.select("source[src], video[src], audio[src]")) {
            resUrl(el.attr("src"))?.let { el.attr("src", it) }
        }
        // Image-map areas aren't routed through the reader's link handling; make them inert.
        for (area in body.getAllElements().filter { localName(it) == "area" }) {
            area.removeAttr("href"); area.removeAttr("xlink:href")
        }
        // HTML <a href> and SVG <a xlink:href> alike go through the reader's link routing.
        for (a in body.getAllElements().filter { localName(it) == "a" && (it.hasAttr("href") || it.hasAttr("xlink:href")) }) {
            val href = (if (a.hasAttr("href")) a.attr("href") else a.attr("xlink:href")).trim()
            a.removeAttr("xlink:href")
            if (href.startsWith("http://", true) || href.startsWith("https://", true) || href.startsWith("mailto:", true)) {
                a.attr("data-ext", href)
            } else {
                link(href)?.let { (ch, anchor) ->
                    a.attr("data-lch", ch.toString())
                    a.attr("data-lanchor", anchor)
                }
            }
            a.attr("href", "#")
        }
        fixEmptyElements(body)
        return body.html()
    }
}

/** Single HTML document. */
class HtmlBook(file: File) : Book {
    private val html: String
    override val title: String
    override val author: String = ""
    override val chapterCount = 1
    override val weights: List<Long>
    override val toc: List<TocEntry>

    init {
        val doc = Jsoup.parse(decodeText(readCapped(file)))
        doc.outputSettings().prettyPrint(false)
        title = doc.title().ifBlank { file.nameWithoutExtension }
        val body = doc.body()
        toc = body.select("h1, h2, h3").filter { it.text().isNotBlank() }.mapIndexed { idx, h ->
            if (h.id().isEmpty()) h.attr("id", "h$idx")
            TocEntry(h.text(), 0, h.id(), h.normalName()[1] - '1')
        }.let { list -> val min = list.minOfOrNull { it.depth } ?: 0; list.map { it.copy(depth = it.depth - min) } }
        html = HtmlClean.process(body, resUrl = { null }, link = { h -> if (h.startsWith("#")) 0 to h.drop(1) else null })
        weights = listOf(html.length.toLong())
    }

    override fun chapterHtml(i: Int) = html
    override fun resource(path: String): Resource? = null
    override fun cover(): ByteArray? = null
    override fun close() = TextCache.evict(this)
}
