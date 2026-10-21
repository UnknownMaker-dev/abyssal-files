package com.abyssal.files

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdaterTest {
    private fun v(s: String) = AppVersion.parse(s)!!

    @Test fun versionsCompare() {
        assertTrue(v("1.7-beta.3") > v("1.7-beta.2"))
        assertTrue(v("1.7") > v("1.7-beta.9"))
        assertTrue(v("1.8-beta.1") > v("1.7"))
        assertTrue(v("v1.10") > v("1.9"))
        assertEquals(0, v("v1.7").compareTo(v("1.7.0")))
        assertEquals("1.7-beta.3", v("v1.7-beta.3").toString())
        assertNull(AppVersion.parse("latest"))
    }

    private fun rel(tag: String, pre: Boolean) = Release(tag, v(tag), tag, "", pre, "https://x/$tag.apk", 1, "")

    @Test fun picksByChannel() {
        val all = listOf(rel("v1.6", false), rel("v1.7-beta.3", true), rel("v1.7-beta.4", true), rel("v1.7", false), rel("v1.8-beta.1", true))
        assertEquals("v1.8-beta.1", Updater.pick(all, v("1.7-beta.3"), beta = true)?.tag)
        assertEquals("v1.7", Updater.pick(all, v("1.7-beta.3"), beta = false)?.tag)
        assertNull(Updater.pick(all, v("1.7"), beta = false))
        assertNull(Updater.pick(all.take(2), v("1.7-beta.3"), beta = true))
    }

    @Test fun notesLosemarkdown() {
        assertEquals("Beta: new\n• List or grid", Updater.plainNotes("**Beta**: [new](http://x)\n- List or `grid`"))
    }
}
