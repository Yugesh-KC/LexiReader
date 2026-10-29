package com.example.epubreader.reader

import org.junit.Assert.assertEquals
import org.junit.Test

class HttpRangeTest {
    private fun p(h: String?, len: Long = 1000) = HttpRange.parse(h, len)

    @Test
    fun wholeWhenAbsentOrUnsupported() {
        assertEquals(HttpRange.Whole, p(null))
        assertEquals(HttpRange.Whole, p("items=0-1"))
        assertEquals(HttpRange.Whole, p("bytes=0-1,5-9")) // multi-range: a full 200 is allowed
        assertEquals(HttpRange.Whole, p("bytes=abc-"))
    }

    @Test
    fun explicitOpenAndSuffixRanges() {
        assertEquals(HttpRange.Part(0, 99), p("bytes=0-99"))
        assertEquals(HttpRange.Part(500, 999), p("bytes=500-"))
        assertEquals(HttpRange.Part(900, 999), p("bytes=-100"))
        assertEquals(HttpRange.Part(0, 999), p("bytes=-5000"))
        assertEquals(HttpRange.Part(10, 999), p("bytes=10-123456")) // end clamped to the file
    }

    @Test
    fun unsatisfiable() {
        assertEquals(HttpRange.Unsatisfiable, p("bytes=1000-"))
        assertEquals(HttpRange.Unsatisfiable, p("bytes=50-10"))
        assertEquals(HttpRange.Unsatisfiable, p("bytes=-0"))
    }
}
