package com.example.epubreader.book

import org.jsoup.Jsoup
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.IOException

class PathsTest {
    @Test
    fun normalizeCollapsesDotsAndSlashes() {
        assertEquals("OEBPS/images/a.png", Paths.normalize("OEBPS/text/../images/./a.png"))
        assertEquals("a/b", Paths.normalize("/a//b/"))
        assertEquals("x", Paths.normalize("../../x")) // can't climb above the root
    }

    @Test
    fun resolveHandlesRelativeAbsoluteAndEscapes() {
        assertEquals("OEBPS/images/my pic.png", Paths.resolve("OEBPS/text", "../images/my%20pic.png#frag"))
        assertEquals("root.xhtml", Paths.resolve("OEBPS", "/root.xhtml"))
        assertEquals("c.xhtml", Paths.resolve("", "c.xhtml?x=1"))
        assertEquals("OEBPS/a+b.xhtml", Paths.resolve("OEBPS", "a+b.xhtml")) // '+' is literal in paths
        assertEquals("OEBPS/é.xhtml", Paths.resolve("OEBPS", "%C3%A9.xhtml"))
        assertEquals("OEBPS/100%.xhtml", Paths.resolve("OEBPS", "100%.xhtml")) // malformed escape kept
    }

    @Test
    fun resUrlEncodesButKeepsSlashes() {
        assertEquals("/book/res/OEBPS/my%20pic%23.png", Paths.resUrl("OEBPS/my pic#.png"))
        assertEquals("/book/res/%C3%A9", Paths.resUrl("é"))
        assertEquals("a/é b", Paths.percentDecode(Paths.percentEncode("a/é b")))
    }
}

class DecodeTextTest {
    @Test
    fun boms() {
        assertEquals("hé", decodeText(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + "hé".toByteArray()))
        assertEquals("hé", decodeText(byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + "hé".toByteArray(Charsets.UTF_16LE)))
        assertEquals("hé", decodeText(byteArrayOf(0xFE.toByte(), 0xFF.toByte()) + "hé".toByteArray(Charsets.UTF_16BE)))
    }

    @Test
    fun declaredEncodingWins() {
        val xml = "<?xml version=\"1.0\" encoding=\"windows-1251\"?><p>Привет</p>"
        assertTrue(decodeText(xml.toByteArray(charset("windows-1251"))).contains("Привет"))
    }

    @Test
    fun utf8ThenCp1252Fallback() {
        assertEquals("café", decodeText("café".toByteArray(Charsets.UTF_8)))
        assertEquals("café", decodeText("café".toByteArray(charset("windows-1252"))))
    }
}

class StreamLimitsTest {
    @Test
    fun readBoundedAcceptsUpToLimitAndRejectsMore() {
        assertArrayEquals(ByteArray(10), ByteArrayInputStream(ByteArray(10)).readBounded(10))
        try {
            ByteArrayInputStream(ByteArray(11)).readBounded(10)
            fail()
        } catch (_: BookTooLargeException) {}
    }

    @Test
    fun limitedStreamTruncatesForRanges() {
        val s = LimitedInputStream(ByteArrayInputStream(ByteArray(100) { it.toByte() }), 5, failOnOverflow = false)
        assertArrayEquals(byteArrayOf(0, 1, 2, 3, 4), s.readBytes())
    }

    @Test
    fun limitedStreamFailsOnOverflowWhenAsked() {
        val ok = LimitedInputStream(ByteArrayInputStream(ByteArray(5)), 5, failOnOverflow = true)
        assertEquals(5, ok.readBytes().size)
        val bomb = LimitedInputStream(ByteArrayInputStream(ByteArray(6)), 5, failOnOverflow = true)
        try {
            bomb.readBytes()
            fail()
        } catch (_: IOException) {}
    }
}

class HtmlCleanTest {
    private fun clean(html: String, link: (String) -> Pair<Int, String>? = { null }): String =
        HtmlClean.process(Jsoup.parseBodyFragment(html).body(), resUrl = { "/book/res/$it" }, link = link)

    @Test
    fun removesScriptsHandlersAndActiveContent() {
        val out = clean(
            "<p onclick=\"x()\">hi</p><script>evil()</script><iframe src=x></iframe><object></object>" +
                "<form><input></form><base href=\"https://evil\"><embed src=y>",
        )
        assertEquals("<p>hi</p>", out)
    }

    @Test
    fun imageMapAreasAreMadeInert() {
        val out = clean("<map name=m><area shape=rect coords=0,0,9,9 href=\"javascript:alert(1)\"></map>")
        assertFalse(out, out.contains("javascript"))
        assertFalse(out, out.contains("href"))
    }

    @Test
    fun javascriptUrlsAreStrippedEvenObfuscated() {
        val out = clean("<img src=\" java\tscript:alert(1)\"><video poster=\"JaVaScRiPt:x\"></video><p data-x=\"javascript: the book\">t</p>")
        assertFalse(out, out.contains("script:alert"))
        assertFalse(out, out.contains("poster"))
        assertTrue("non-URL attributes keep their text", out.contains("data-x=\"javascript: the book\""))
    }

    @Test
    fun linksAreRewrittenForTheReader() {
        val out = clean(
            "<a href=\"https://example.com\">ext</a><a href=\"ch2.xhtml#n1\">int</a><a href=\"javascript:alert(1)\">bad</a>",
            link = { h -> if (h.startsWith("ch2")) 1 to "n1" else null },
        )
        assertTrue(out, out.contains("<a href=\"#\" data-ext=\"https://example.com\">ext</a>"))
        assertTrue(out, out.contains("<a href=\"#\" data-lch=\"1\" data-lanchor=\"n1\">int</a>"))
        assertFalse(out, out.contains("javascript"))
    }

    @Test
    fun svgLinksAndScriptsAreHandled() {
        val svg = "<svg><a xlink:href=\"javascript:alert(1)\"><text>x</text></a><script>e()</script>" +
            "<a><set attributeName=\"href\" to=\"javascript:alert(1)\"/></a></svg>"
        val out = clean(svg)
        assertFalse(out, out.contains("javascript"))
        assertFalse(out, out.contains("<script"))
        assertFalse(out, out.contains("<set"))
    }

    @Test
    fun dropsPublisherColoursButKeepsLayout() {
        val out = clean("<p style=\"color: red; text-indent: 1em; font-size: 30px\">x</p>")
        assertEquals("<p style=\"text-indent: 1em\">x</p>", out)
    }

    @Test
    fun plainTextCollapsesWhitespace() {
        assertEquals("a b c", plainText("<p>a\n  b <span>c</span></p>").trim())
    }
}
