package com.example.epubreader.book

import android.util.Base64
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.TextNode
import org.jsoup.parser.Parser
import java.io.ByteArrayInputStream
import java.io.File

/** FictionBook 2 (.fb2) — XML with sections, inline base64 images and a notes body. */
class Fb2Book(file: File) : Book {
    private class Chapter(val title: String, val el: Element)

    private val chapters = mutableListOf<Chapter>()
    private val binaries = HashMap<String, Pair<String, String>>()
    private val idToChapter = HashMap<String, Int>()
    private val cache = HtmlLru()
    private val coverId: String?

    override val title: String
    override val author: String
    override val chapterCount get() = chapters.size
    override val weights: List<Long>
    override val toc: List<TocEntry>

    init {
        val doc = Jsoup.parse(decodeText(readCapped(file)), "", Parser.xmlParser())
        val info = doc.getElementsByTag("title-info").firstOrNull()
        title = info?.getElementsByTag("book-title")?.firstOrNull()?.text()?.trim().orEmpty().ifEmpty { file.nameWithoutExtension }
        author = info?.getElementsByTag("author")?.joinToString(", ") { a ->
            listOf("first-name", "middle-name", "last-name").mapNotNull { a.getElementsByTag(it).firstOrNull()?.text() }
                .joinToString(" ").trim()
        }.orEmpty()
        coverId = info?.getElementsByTag("coverpage")?.firstOrNull()?.getElementsByTag("image")?.firstOrNull()
            ?.let { href(it) }?.removePrefix("#")
        for (b in doc.getElementsByTag("binary")) binaries[b.attr("id")] = b.attr("content-type") to b.text()

        val tocList = mutableListOf<TocEntry>()
        for (body in doc.getElementsByTag("body")) {
            val isNotes = body.attr("name").isNotEmpty()
            val sections = body.children().filter { it.normalName() == "section" }
            if (isNotes) {
                chapters += Chapter(body.children().firstOrNull { it.normalName() == "title" }?.text() ?: "Notes", body)
                tocList += TocEntry(chapters.last().title, chapters.lastIndex, "", 0)
            } else if (sections.isEmpty()) {
                chapters += Chapter(title, body)
                tocList += TocEntry(title, chapters.lastIndex, "", 0)
            } else {
                for (s in sections) {
                    val t = sectionTitle(s) ?: "Section ${chapters.size + 1}"
                    chapters += Chapter(t, s)
                    tocList += TocEntry(t, chapters.lastIndex, "", 0)
                    s.children().filter { it.normalName() == "section" }.forEach { sub ->
                        sectionTitle(sub)?.let { st ->
                            if (sub.id().isEmpty()) sub.attr("id", "s${tocList.size}")
                            tocList += TocEntry(st, chapters.lastIndex, sub.id(), 1)
                        }
                    }
                }
            }
        }
        chapters.forEachIndexed { i, c -> c.el.getElementsByAttribute("id").forEach { idToChapter[it.id()] = i } }
        toc = tocList
        weights = chapters.map { it.el.text().length.toLong().coerceAtLeast(1) }
    }

    private fun sectionTitle(s: Element) =
        s.children().firstOrNull { it.normalName() == "title" }?.text()?.trim()?.takeIf { it.isNotEmpty() }

    private fun href(el: Element) = el.attributes().asList().firstOrNull { it.key.endsWith("href") }?.value.orEmpty()

    private fun convert(el: Element, sb: StringBuilder) {
        for (n in el.childNodes()) {
            when (n) {
                is TextNode -> sb.append(escapeHtml(n.wholeText))
                is Element -> {
                    val idAttr = if (n.id().isNotEmpty()) " id=\"${escapeHtml(n.id())}\"" else ""
                    fun wrap(tag: String, cls: String? = null) {
                        sb.append('<').append(tag).append(idAttr)
                        if (cls != null) sb.append(" class=\"").append(cls).append('"')
                        sb.append('>')
                        convert(n, sb)
                        sb.append("</").append(tag).append('>')
                    }
                    when (n.normalName()) {
                        "p", "v", "text-author" -> wrap("p")
                        "emphasis" -> wrap("em")
                        "strong" -> wrap("strong")
                        "strikethrough" -> wrap("s")
                        "sub" -> wrap("sub")
                        "sup" -> wrap("sup")
                        "code" -> wrap("code")
                        "title" -> wrap("h2")
                        "subtitle" -> wrap("h3")
                        "epigraph", "cite" -> wrap("blockquote")
                        "poem", "stanza", "section" -> wrap("div")
                        "empty-line" -> sb.append("<br>")
                        "image" -> {
                            val id = href(n).removePrefix("#")
                            if (binaries.containsKey(id)) sb.append("<img src=\"").append(Paths.resUrl(id)).append("\" alt=\"\">")
                        }
                        "a" -> {
                            val h = href(n)
                            val target = h.removePrefix("#")
                            val ch = idToChapter[target]
                            when {
                                h.startsWith("http") -> sb.append("<a href=\"#\" data-ext=\"").append(escapeHtml(h)).append("\">")
                                ch != null -> sb.append("<a href=\"#\" data-lch=\"").append(ch).append("\" data-lanchor=\"")
                                    .append(escapeHtml(target)).append("\">")
                                else -> sb.append("<a>")
                            }
                            convert(n, sb)
                            sb.append("</a>")
                        }
                        "table" -> wrap("table")
                        "tr" -> wrap("tr")
                        "td" -> wrap("td")
                        "th" -> wrap("th")
                        else -> convert(n, sb)
                    }
                }
            }
        }
    }

    override fun chapterHtml(i: Int): String = cache.getOrPut(i) {
        val c = chapters.getOrNull(i) ?: return@getOrPut ""
        StringBuilder().also { convert(c.el, it) }.toString()
    }

    override fun resource(path: String): Resource? {
        val (mime, b64) = binaries[path] ?: return null
        val bytes = runCatching { guardMemory { Base64.decode(b64, Base64.DEFAULT) } }.getOrNull() ?: return null
        return Resource(mime.ifEmpty { "image/jpeg" }, ByteArrayInputStream(bytes))
    }

    /** Malformed or oversized base64 just means no cover. */
    override fun cover(): ByteArray? = coverId?.let { binaries[it] }
        ?.takeIf { it.second.length / 4 * 3 <= Limits.MAX_COVER }
        ?.let { runCatching { guardMemory { Base64.decode(it.second, Base64.DEFAULT) } }.getOrNull() }

    override fun close() = TextCache.evict(this)
}
