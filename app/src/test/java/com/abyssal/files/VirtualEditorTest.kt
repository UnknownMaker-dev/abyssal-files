package com.abyssal.files

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class VirtualEditorTest {
    @Test fun splitsLines() {
        val t = "a\nbc\n\nd"
        val s = lineStarts(t)
        assertArrayEquals(intArrayOf(0, 2, 5, 6), s)
        assertEquals(0, lineOf(s, 0))
        assertEquals(0, lineOf(s, 1))
        assertEquals(1, lineOf(s, 2))
        assertEquals(1, lineOf(s, 4))
        assertEquals(2, lineOf(s, 5))
        assertEquals(3, lineOf(s, 7))
        assertArrayEquals(intArrayOf(0), lineStarts(""))
        assertEquals(2, longestLine(t))
        assertEquals(0, longestLine(""))
    }

    @Test fun guardIsStripped() {
        // Normal typing: guard at 0, cursor after it
        assertEquals(Triple("abc", 2, 2), stripGuard("​abc", 3, 3))
        // Text typed before the guard
        assertEquals(Triple("xabc", 1, 1), stripGuard("x​abc", 1, 1))
        assertEquals(Triple("", 0, 0), stripGuard("​", 1, 1))
    }
}
