package com.abyssal.files

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ZipTest {
    @get:Rule val tmp = TemporaryFolder()

    /** pack.mcpack with: manifest.json, textures/blocks/a.png, textures/items/b.png (no folder entries). */
    private fun archive(name: String = "pack.mcpack", extra: Map<String, String> = emptyMap()): File {
        val f = File(tmp.root, name)
        ZipOutputStream(f.outputStream()).use { z ->
            (mapOf("manifest.json" to "{}", "textures/blocks/a.png" to "A", "textures/items/b.png" to "BB") + extra).forEach { (n, c) ->
                z.putNextEntry(ZipEntry(n)); z.write(c.toByteArray()); z.closeEntry()
            }
        }
        return f
    }

    @Test fun listingBuildsTree() {
        val l = FileOps.zipListing(archive())
        assertEquals(null, l.error)
        assertEquals(3, l.files)
        assertEquals(listOf("textures", "manifest.json"), l.children[""]!!.map { it.name })     // folders first
        assertEquals(listOf("blocks", "items"), l.children["textures/"]!!.map { it.name })
        assertEquals("a.png", l.children["textures/blocks/"]!!.single().name)
    }

    @Test fun badArchiveReportsError() {
        val f = File(tmp.root, "broken.zip").apply { writeText("not a zip") }
        assertNotNull(FileOps.zipListing(f).error)
    }

    @Test fun extractAllIntoFolder() {
        val out = tmp.newFolder("out")
        FileOps.unzip(archive(), out)
        assertEquals("A", File(out, "textures/blocks/a.png").readText())
        assertEquals("{}", File(out, "manifest.json").readText())
    }

    @Test fun extractSingleFileAndFolder() {
        val z = archive()
        val one = tmp.newFolder("one")
        FileOps.unzip(z, one, "textures/items/b.png")
        assertEquals(listOf("b.png"), one.list()!!.toList())          // placed directly, without its parent folders

        val dir = tmp.newFolder("dir")
        FileOps.unzip(z, dir, "textures/blocks/")
        assertTrue(File(dir, "blocks/a.png").isFile)
        assertFalse(File(dir, "items").exists())
    }

    @Test fun existingFilesAreKept() {
        val out = tmp.newFolder("keep")
        File(out, "manifest.json").writeText("mine")
        FileOps.unzip(archive(), out)
        assertEquals("mine", File(out, "manifest.json").readText())
        assertEquals("{}", File(out, "manifest (1).json").readText())
    }

    @Test(expected = SecurityException::class)
    fun zipSlipIsBlocked() {
        FileOps.unzip(archive("evil.zip", mapOf("../../escape.txt" to "x")), tmp.newFolder("safe"))
    }

    @Test fun extractToCacheCopiesOneEntry() {
        val cache = tmp.newFolder("cache")
        val out = FileOps.extractToCache(cache, archive(), "textures/items/b.png")
        assertEquals("BB", out.readText())
        assertTrue(out.path.startsWith(cache.path))
    }
}
