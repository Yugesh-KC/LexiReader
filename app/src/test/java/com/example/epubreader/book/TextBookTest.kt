package com.example.epubreader.book

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class TextBookTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun file(name: String, text: String): File = tmp.newFile(name).apply { writeText(text) }

    @Test
    fun plainTextSplitsOnChapterHeadings() {
        val text = buildString {
            append("A preface line.\n\n")
            for (i in 1..3) append("Chapter $i\n\n").append("Body of chapter $i. ".repeat(20)).append("\n\n")
        }
        val b = TextBook(file("novel.txt", text), markdown = false)
        assertEquals(listOf("Opening", "Chapter 1", "Chapter 2", "Chapter 3"), b.toc.map { it.title })
        assertEquals(4, b.chapterCount)
        assertTrue(b.chapterHtml(2).contains("Body of chapter 2."))
        assertEquals("novel", b.title)
    }

    @Test
    fun plainTextWithoutHeadingsIsChunked() {
        val text = ("word ".repeat(30) + "\n\n").repeat(600) // ~90k chars
        val b = TextBook(file("long.txt", text), markdown = false)
        assertTrue(b.chapterCount >= 2)
        assertEquals("Part 1", b.toc.first().title)
    }

    @Test
    fun plainTextIsEscaped() {
        val b = TextBook(file("x.txt", "<script>alert(1)</script> & more"), markdown = false)
        val html = b.chapterHtml(0)
        assertFalse(html.contains("<script>"))
        assertTrue(html.contains("&lt;script&gt;"))
    }

    @Test
    fun markdownLinksAreRoutedThroughDataExt() {
        val md = "# Title\n\nSee [the site](https://example.com/a?b=1) for more.\n"
        val html = TextBook(file("a.md", md), markdown = true).chapterHtml(0)
        assertTrue(html, html.contains("<a href=\"#\" data-ext=\"https://example.com/a?b=1\">the site</a>"))
    }

    @Test
    fun markdownLinkCannotBreakOutOfAttribute() {
        val md = "Click [me](https://x.com/\"onmouseover=\"alert(1)) now\n"
        val html = TextBook(file("b.md", md), markdown = true).chapterHtml(0)
        val injected = org.jsoup.Jsoup.parseBodyFragment(html).body().getAllElements().any { it.hasAttr("onmouseover") }
        assertFalse(html, injected)
    }

    @Test
    fun markdownTitleAndChapters() {
        val md = "# One\n\ntext one\n\n# Two\n\n- a\n- b\n\n**bold** and *it*\n"
        val b = TextBook(file("c.md", md), markdown = true)
        assertEquals("One", b.title)
        assertEquals(listOf("One", "Two"), b.toc.map { it.title })
        val two = b.chapterHtml(1)
        assertTrue(two.contains("<ul><li>a</li><li>b</li></ul>"))
        assertTrue(two.contains("<strong>bold</strong>"))
        assertTrue(two.contains("<em>it</em>"))
    }

    @Test
    fun oversizedFileIsRefusedWithReadableError() {
        val f = tmp.newFile("huge.txt")
        java.io.RandomAccessFile(f, "rw").use { it.setLength(Limits.MAX_TEXT_FILE + 1) } // sparse, no real allocation
        val e = runCatching { TextBook(f, markdown = false) }.exceptionOrNull()
        assertTrue(e is BookTooLargeException)
        assertTrue(e!!.message!!.contains("too large"))
    }

    @Test
    fun chapterTextIsCachedPerBook() {
        val a = TextBook(file("a.txt", "alpha text"), markdown = false)
        val b = TextBook(file("b.txt", "beta text"), markdown = false)
        assertTrue(a.chapterText(0).contains("alpha"))
        assertTrue(b.chapterText(0).contains("beta"))
        a.close()
        assertTrue(a.chapterText(0).contains("alpha")) // recomputed after eviction
    }
}
