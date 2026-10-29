package com.example.epubreader.book

import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.parser.Parser
import java.io.File
import java.util.zip.ZipFile

class EpubBook private constructor(file: File, private val zip: ZipFile) : Book {
    private class Item(val id: String, val path: String, val mediaType: String, val properties: String)

    private val entryNames: Map<String, String> = zip.entries().asSequence().associate { it.name.lowercase() to it.name }
    private val manifest: Map<String, Item>
    private val spine: List<String>
    private val spineIndex: Map<String, Int>
    private val cache = HtmlLru()
    private val coverPath: String?

    override val title: String
    override val author: String
    override val chapterCount get() = spine.size
    override val weights: List<Long>
    override val toc: List<TocEntry>

    init {
        val container = readText("META-INF/container.xml") ?: error("Not an EPUB (no container.xml)")
        val opfPath = Jsoup.parse(container, "", Parser.xmlParser()).getElementsByTag("rootfile").firstOrNull()
            ?.attr("full-path")?.let { Paths.normalize(it) } ?: error("EPUB has no package document")
        val opfDir = Paths.dirOf(opfPath)
        val opf = Jsoup.parse(readText(opfPath) ?: error("Missing $opfPath"), "", Parser.xmlParser())

        fun tag(name: String) = opf.getElementsByTag("dc:$name").firstOrNull() ?: opf.getElementsByTag(name).firstOrNull()
        title = tag("title")?.text()?.trim().orEmpty().ifEmpty { file.nameWithoutExtension }
        author = opf.getElementsByTag("dc:creator").joinToString(", ") { it.text().trim() }.ifEmpty { tag("creator")?.text().orEmpty() }

        manifest = opf.getElementsByTag("item").associate { el ->
            val id = el.attr("id")
            id to Item(id, Paths.resolve(opfDir, el.attr("href")), el.attr("media-type"), el.attr("properties"))
        }
        val spineEl = opf.getElementsByTag("spine").firstOrNull()
        val refs = opf.getElementsByTag("itemref").mapNotNull { manifest[it.attr("idref")] }
        val docs = refs.filter { it.mediaType.contains("html") || it.mediaType.contains("xml") || it.mediaType.isEmpty() }
        spine = docs.map { it.path }.ifEmpty { error("EPUB spine is empty") }
        spineIndex = spine.withIndex().associate { (i, p) -> p to i }
        weights = spine.map { (entry(it)?.size ?: 1L).coerceAtLeast(1L) }

        val metaCover = opf.getElementsByTag("meta").firstOrNull { it.attr("name") == "cover" }?.attr("content")
        coverPath = (manifest.values.firstOrNull { "cover-image" in it.properties.split(' ') }
            ?: metaCover?.let { manifest[it] }?.takeIf { it.mediaType.startsWith("image") }
            ?: manifest.values.firstOrNull { it.mediaType.startsWith("image") && it.id.contains("cover", true) }
            ?: manifest.values.firstOrNull { it.mediaType.startsWith("image") && it.path.contains("cover", true) })?.path

        val nav = manifest.values.firstOrNull { "nav" in it.properties.split(' ') }
        val ncx = spineEl?.attr("toc")?.let { manifest[it] }
            ?: manifest.values.firstOrNull { it.mediaType == "application/x-dtbncx+xml" }
        toc = (nav?.let { runCatching { parseNav(it.path) }.getOrNull() }?.takeIf { it.isNotEmpty() }
            ?: ncx?.let { runCatching { parseNcx(it.path) }.getOrNull() }
            ?: emptyList())
    }

    private fun entry(path: String) = (zip.getEntry(path) ?: entryNames[path.lowercase()]?.let { zip.getEntry(it) })

    /** Reads a whole entry, refusing ones that declare or turn out to inflate past [max] (zip bombs). */
    private fun read(path: String, max: Long = Limits.MAX_ZIP_ENTRY): ByteArray? = entry(path)?.let { e ->
        if (e.size > max) throw BookTooLargeException("Part of this book is too large to open (${Limits.mb(e.size)} MB).")
        guardMemory { zip.getInputStream(e).use { it.readBounded(max) } }
    }

    private fun readText(path: String) = read(path)?.let { decodeText(it) }

    private fun locate(baseDir: String, href: String): Pair<Int, String>? {
        val path = Paths.resolve(baseDir, href)
        val ch = spineIndex[path] ?: return null
        return ch to href.substringAfter('#', "")
    }

    private fun parseNav(path: String): List<TocEntry> {
        val doc = Jsoup.parse(readText(path) ?: return emptyList(), "", Parser.xmlParser())
        val navs = doc.getElementsByTag("nav")
        val nav = navs.firstOrNull { it.attr("epub:type") == "toc" } ?: navs.firstOrNull() ?: return emptyList()
        val dir = Paths.dirOf(path)
        val out = mutableListOf<TocEntry>()
        fun walk(ol: Element, depth: Int) {
            for (li in ol.children().filter { it.normalName() == "li" }) {
                val a = li.children().firstOrNull { it.normalName() == "a" }
                if (a != null) {
                    locate(dir, a.attr("href"))?.let { (ch, anchor) -> out += TocEntry(a.text().trim(), ch, anchor, depth) }
                }
                li.children().firstOrNull { it.normalName() == "ol" }?.let { walk(it, depth + 1) }
            }
        }
        nav.getElementsByTag("ol").firstOrNull()?.let { walk(it, 0) }
        return out
    }

    private fun parseNcx(path: String): List<TocEntry> {
        val doc = Jsoup.parse(readText(path) ?: return emptyList(), "", Parser.xmlParser())
        val dir = Paths.dirOf(path)
        val out = mutableListOf<TocEntry>()
        fun walk(parent: Element, depth: Int) {
            for (np in parent.children().filter { it.normalName() == "navpoint" }) {
                val label = np.getElementsByTag("navLabel").firstOrNull()?.text()?.trim().orEmpty()
                val src = np.children().firstOrNull { it.normalName() == "content" }?.attr("src")
                if (src != null) locate(dir, src)?.let { (ch, anchor) -> out += TocEntry(label, ch, anchor, depth) }
                walk(np, depth + 1)
            }
        }
        doc.getElementsByTag("navMap").firstOrNull()?.let { walk(it, 0) }
        return out
    }

    override fun chapterHtml(i: Int): String = cache.getOrPut(i) {
        val path = spine.getOrNull(i) ?: return@getOrPut "<p>Missing chapter</p>"
        val text = readText(path) ?: return@getOrPut "<p>Missing chapter</p>"
        val dir = Paths.dirOf(path)
        var doc: Document = Jsoup.parse(text, "", Parser.xmlParser())
        var body = doc.getElementsByTag("body").firstOrNull()
        if (body == null) {
            doc = Jsoup.parse(text)
            body = doc.body()
        }
        doc.outputSettings().syntax(Document.OutputSettings.Syntax.html).prettyPrint(false)
        HtmlClean.process(
            body,
            resUrl = { src ->
                if (src.isBlank() || src.startsWith("http", true)) null else Paths.resUrl(Paths.resolve(dir, src))
            },
            link = { href ->
                if (href.startsWith("#")) i to href.drop(1) else locate(dir, href)
            },
        )
    }

    override fun resource(path: String): Resource? {
        val e = entry(path) ?: return null
        if (e.size > Limits.MAX_RESOURCE) return null
        return Resource(mimeOf(path), LimitedInputStream(zip.getInputStream(e), Limits.MAX_RESOURCE, failOnOverflow = true))
    }

    /** A missing or oversized cover just means no cover. */
    override fun cover(): ByteArray? = coverPath?.let { runCatching { read(it, Limits.MAX_COVER) }.getOrNull() }

    override fun close() {
        TextCache.evict(this)
        zip.close()
    }

    companion object {
        /** Opens [file]; the zip is closed again if the book turns out to be malformed. */
        operator fun invoke(file: File): EpubBook {
            val zip = ZipFile(file)
            return try { EpubBook(file, zip) } catch (t: Throwable) { runCatching { zip.close() }; throw t }
        }

        fun mimeOf(path: String) = when (path.substringAfterLast('.').lowercase()) {
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "gif" -> "image/gif"
            "svg" -> "image/svg+xml"
            "webp" -> "image/webp"
            "css" -> "text/css"
            "ttf" -> "font/ttf"
            "otf" -> "font/otf"
            "woff" -> "font/woff"
            "woff2" -> "font/woff2"
            "mp3" -> "audio/mpeg"
            "mp4" -> "video/mp4"
            else -> "application/octet-stream"
        }
    }
}
