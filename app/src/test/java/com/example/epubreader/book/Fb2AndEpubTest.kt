package com.example.epubreader.book

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class Fb2BookTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test
    fun parsesMetadataSectionsAndLinks() {
        val fb2 = """<?xml version="1.0" encoding="utf-8"?>
            <FictionBook xmlns="http://www.gribuser.ru/xml/fictionbook/2.0" xmlns:l="http://www.w3.org/1999/xlink">
              <description><title-info>
                <author><first-name>Leo</first-name><last-name>Tolstoy</last-name></author>
                <book-title>War &amp; Peace</book-title>
              </title-info></description>
              <body>
                <section><title><p>Book One</p></title><p>Well, Prince<a l:href="#n1">1</a>.</p>
                  <section id="s1"><title><p>Part I</p></title><p>Text.</p></section>
                </section>
                <section><title><p>Book Two</p></title><p>More <emphasis>text</emphasis>.</p></section>
              </body>
              <body name="notes"><section id="n1"><p>A note.</p></section></body>
            </FictionBook>"""
        val f = tmp.newFile("wp.fb2").apply { writeText(fb2) }
        val b = Fb2Book(f)
        assertEquals("War & Peace", b.title)
        assertEquals("Leo Tolstoy", b.author)
        assertEquals(3, b.chapterCount)
        assertEquals(listOf("Book One", "Part I", "Book Two", "Notes"), b.toc.map { it.title })
        val one = b.chapterHtml(0)
        assertTrue(one, one.contains("data-lch=\"2\" data-lanchor=\"n1\""))
        assertTrue(b.chapterHtml(1).contains("<em>text</em>"))
        assertTrue(b.chapterText(2).contains("A note."))
    }
}

class EpubBookTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun epub(entries: Map<String, String>): File {
        val f = tmp.newFile("book.epub")
        ZipOutputStream(f.outputStream()).use { z ->
            for ((name, body) in entries) {
                z.putNextEntry(ZipEntry(name)); z.write(body.toByteArray()); z.closeEntry()
            }
        }
        return f
    }

    private val container = """<?xml version="1.0"?><container><rootfiles>
        <rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles></container>"""

    private val opf = """<?xml version="1.0"?><package xmlns:dc="http://purl.org/dc/elements/1.1/">
        <metadata><dc:title>Tiny</dc:title><dc:creator>Ann Author</dc:creator></metadata>
        <manifest>
          <item id="c1" href="text/c1.xhtml" media-type="application/xhtml+xml"/>
          <item id="c2" href="text/c%202.xhtml" media-type="application/xhtml+xml"/>
          <item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/>
        </manifest>
        <spine><itemref idref="c1"/><itemref idref="c2"/></spine></package>"""

    private val nav = """<html xmlns:epub="http://www.idpf.org/2007/ops"><body><nav epub:type="toc"><ol>
        <li><a href="text/c1.xhtml">First</a></li><li><a href="text/c%202.xhtml#x">Second</a></li></ol></nav></body></html>"""

    @Test
    fun parsesSpineTocAndSanitisesChapters() {
        val f = epub(
            mapOf(
                "META-INF/container.xml" to container,
                "OEBPS/content.opf" to opf,
                "OEBPS/nav.xhtml" to nav,
                "OEBPS/text/c1.xhtml" to "<html><body><p>One <a href=\"c%202.xhtml#x\">next</a></p>" +
                    "<map><area href=\"javascript:alert(1)\"/></map><img src=\"../img/a.png\"/></body></html>",
                "OEBPS/text/c 2.xhtml" to "<html><body><p id=\"x\">Two</p></body></html>",
            ),
        )
        val b = EpubBook(f)
        try {
            assertEquals("Tiny", b.title)
            assertEquals("Ann Author", b.author)
            assertEquals(2, b.chapterCount)
            assertEquals(listOf("First" to 0, "Second" to 1), b.toc.map { it.title to it.chapter })
            val c1 = b.chapterHtml(0)
            assertTrue(c1, c1.contains("data-lch=\"1\""))
            assertTrue(c1, c1.contains("/book/res/OEBPS/img/a.png"))
            assertFalse(c1, c1.contains("javascript"))
            assertTrue(b.chapterHtml(1).contains("Two"))
            assertNull(b.cover())
        } finally {
            b.close()
        }
    }

    @Test
    fun rejectsNonEpubZip() {
        val f = epub(mapOf("hello.txt" to "hi"))
        val e = runCatching { EpubBook(f) }.exceptionOrNull()
        assertTrue(e?.message.orEmpty().contains("container.xml"))
    }
}
