package com.example.epubreader.book

import java.io.File

/** Plain text and Markdown. Chapters are detected from headings, otherwise the text is chunked. */
class TextBook(file: File, private val markdown: Boolean) : Book {
    private class Chapter(val title: String, val html: String)

    private val chapters: List<Chapter>
    override val title: String
    override val author: String = ""
    override val chapterCount get() = chapters.size
    override val weights: List<Long>
    override val toc: List<TocEntry>

    init {
        val text = decodeText(readCapped(file)).replace("\r\n", "\n").replace('\r', '\n')
        title = (if (markdown) Regex("^#\\s+(.+)$", RegexOption.MULTILINE).find(text)?.groupValues?.get(1) else null)
            ?.trim() ?: file.nameWithoutExtension.replace('_', ' ')
        chapters = (if (markdown) splitMarkdown(text) else splitText(text)).ifEmpty { listOf(Chapter(title, "<p></p>")) }
        weights = chapters.map { it.html.length.toLong().coerceAtLeast(1) }
        toc = chapters.mapIndexed { i, c -> TocEntry(c.title, i, "", 0) }
    }

    override fun chapterHtml(i: Int) = chapters.getOrNull(i)?.html ?: ""
    override fun resource(path: String): Resource? = null
    override fun cover(): ByteArray? = null
    override fun close() = TextCache.evict(this)

    // ------------------------------------------------------------------ plain text

    private fun splitText(text: String): List<Chapter> {
        val heads = headingRe.findAll(text).filter { it.value.isNotBlank() }.toList()
        val pieces = mutableListOf<Pair<String, String>>()
        if (heads.size >= 2) {
            val pre = text.substring(0, heads[0].range.first)
            if (pre.isNotBlank()) pieces += "Opening" to pre
            heads.forEachIndexed { i, m ->
                val end = if (i + 1 < heads.size) heads[i + 1].range.first else text.length
                pieces += m.value.trim() to text.substring(m.range.last + 1, end)
            }
        } else {
            var start = 0
            var n = 1
            while (start < text.length) {
                var end = minOf(text.length, start + 40_000)
                if (end < text.length) {
                    val br = text.indexOf("\n\n", end).takeIf { it in end..end + 8000 } ?: text.indexOf('\n', end)
                    end = if (br < 0) text.length else br
                }
                pieces += "Part ${n++}" to text.substring(start, end)
                start = end
            }
        }
        val blankLines = Regex("\\n\\s*\\n").findAll(text).count()
        val lines = text.count { it == '\n' } + 1
        val paragraphsByBlank = blankLines > lines / 20
        return pieces.map { (t, body) ->
            val paras = if (paragraphsByBlank) body.split(Regex("\\n\\s*\\n")).map { it.replace(Regex("\\s*\\n\\s*"), " ") }
            else body.split('\n')
            val html = buildString {
                append("<h2>").append(escapeHtml(t)).append("</h2>")
                for (p in paras) {
                    val s = p.trim()
                    if (s.isNotEmpty()) append("<p>").append(escapeHtml(s)).append("</p>")
                }
            }
            Chapter(t, html)
        }
    }

    // ------------------------------------------------------------------ markdown

    private fun splitMarkdown(text: String): List<Chapter> {
        val lines = text.split('\n')
        val level = if (lines.count { it.startsWith("# ") } >= 2) "# " else if (lines.count { it.startsWith("## ") } >= 2) "## " else null
        val parts = mutableListOf<Pair<String, MutableList<String>>>()
        for (line in lines) {
            if (level != null && line.startsWith(level)) parts += line.removePrefix(level).trim() to mutableListOf(line)
            else {
                if (parts.isEmpty()) parts += title to mutableListOf()
                parts.last().second += line
            }
        }
        return parts.filter { it.second.any { l -> l.isNotBlank() } }.map { (t, ls) -> Chapter(t, markdownToHtml(ls)) }
    }

    private fun inline(s: String): String {
        var h = escapeHtml(s)
        h = h.replace(Regex("`([^`]+)`"), "<code>$1</code>")
        h = h.replace(Regex("\\*\\*([^*]+)\\*\\*|__([^_]+)__")) { "<strong>${it.groupValues[1].ifEmpty { it.groupValues[2] }}</strong>" }
        h = h.replace(Regex("(?<![*\\w])\\*([^*]+)\\*(?!\\*)|(?<![_\\w])_([^_]+)_(?![_\\w])")) {
            "<em>${it.groupValues[1].ifEmpty { it.groupValues[2] }}</em>"
        }
        h = h.replace(Regex("!\\[([^\\]]*)]\\(([^)]+)\\)"), "$1")
        // The reader only follows links tagged data-ext; quotes/brackets are excluded so the URL can't leave the attribute.
        h = h.replace(Regex("\\[([^\\]]+)]\\((https?://[^)\\s\"'<>]+)\\)"), "<a href=\"#\" data-ext=\"$2\">$1</a>")
        return h
    }

    private fun markdownToHtml(lines: List<String>): String = buildString {
        val para = StringBuilder()
        var list: String? = null
        var inCode = false
        fun flushPara() {
            if (para.isNotBlank()) append("<p>").append(inline(para.toString().trim())).append("</p>")
            para.clear()
        }
        fun closeList() {
            if (list != null) append("</$list>")
            list = null
        }
        for (raw in lines) {
            val line = raw.trimEnd()
            if (line.trimStart().startsWith("```")) {
                flushPara(); closeList()
                append(if (inCode) "</code></pre>" else "<pre><code>")
                inCode = !inCode
                continue
            }
            if (inCode) { append(escapeHtml(raw)).append('\n'); continue }
            val h = Regex("^(#{1,6})\\s+(.*)$").find(line)
            val ul = Regex("^\\s*[-*+]\\s+(.*)$").find(line)
            val ol = Regex("^\\s*\\d+[.)]\\s+(.*)$").find(line)
            when {
                line.isBlank() -> { flushPara(); closeList() }
                h != null -> {
                    flushPara(); closeList()
                    val n = h.groupValues[1].length
                    append("<h$n>").append(inline(h.groupValues[2])).append("</h$n>")
                }
                Regex("^\\s*([-*_])(\\s*\\1){2,}\\s*$").matches(line) -> { flushPara(); closeList(); append("<hr>") }
                ul != null || ol != null -> {
                    flushPara()
                    val kind = if (ul != null) "ul" else "ol"
                    if (list != kind) { closeList(); append("<$kind>"); list = kind }
                    append("<li>").append(inline((ul ?: ol)!!.groupValues[1])).append("</li>")
                }
                line.startsWith(">") -> {
                    flushPara(); closeList()
                    append("<blockquote><p>").append(inline(line.trimStart('>', ' '))).append("</p></blockquote>")
                }
                else -> { closeList(); para.append(line).append(' ') }
            }
        }
        flushPara(); closeList()
        if (inCode) append("</code></pre>")
    }

    companion object {
        // Lives in the companion: instance properties declared below `init` are still null inside it.
        private const val NUM = "[0-9]{1,4}|[ivxlcdm]{1,8}|one|two|three|four|five|six|seven|eight|nine|ten|eleven|twelve|" +
            "thirteen|fourteen|fifteen|sixteen|seventeen|eighteen|nineteen|twenty[a-z-]*|thirty[a-z-]*|forty[a-z-]*|fifty[a-z-]*"

        /** "Chapter 12: Title", "PART TWO", "Prologue", "IV.", "I. A SCANDAL IN BOHEMIA". */
        private val headingRe = Regex(
            "^[ \\t]{0,6}(" +
                "(?i:(chapter|part|book|act|volume)[ \\t]+($NUM)\\b[^\\n]{0,60})" +
                "|(?i:(prologue|epilogue|preface|introduction|interlude|afterword)[ \\t]*[.:]?[^\\n]{0,40})" +
                "|[IVXLC]{1,7}\\.([ \\t]+[A-Z][A-Z0-9'’ ,.!?-]{1,60})?" +
                ")[ \\t]*$",
            RegexOption.MULTILINE,
        )
    }
}
