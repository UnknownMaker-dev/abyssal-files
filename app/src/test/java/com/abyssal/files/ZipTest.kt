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

    @Test fun extractConflictChoices() {
        val z = archive()
        fun prepared(name: String) = tmp.newFolder(name).also {
            File(it, "manifest.json").writeText("mine")
            File(it, "textures/blocks").mkdirs(); File(it, "textures/blocks/a.png").writeText("old"); File(it, "textures/keep.txt").writeText("k")
        }
        assertEquals(listOf("manifest.json" to false, "textures" to true), FileOps.extractConflicts(z, prepared("probe")))

        val over = prepared("over")
        FileOps.unzip(z, over) { Conflict.OVERWRITE }
        assertEquals("{}", File(over, "manifest.json").readText())
        assertEquals("A", File(over, "textures/blocks/a.png").readText())      // replaced
        assertTrue(File(over, "textures/keep.txt").isFile)                     // folders merge
        assertTrue(File(over, "textures/items/b.png").isFile)

        val skip = prepared("skip")
        FileOps.unzip(z, skip) { Conflict.SKIP }
        assertEquals("mine", File(skip, "manifest.json").readText())
        assertEquals("old", File(skip, "textures/blocks/a.png").readText())
        assertFalse(File(skip, "textures/items").exists())

        val ren = prepared("ren")
        FileOps.unzip(z, ren) { if (it == "textures") Conflict.RENAME else Conflict.SKIP }
        assertEquals("A", File(ren, "textures (1)/blocks/a.png").readText())
        assertEquals("old", File(ren, "textures/blocks/a.png").readText())
        assertFalse(File(ren, "manifest (1).json").exists())
    }

    @Test fun pasteConflictChoices() {
        val src = tmp.newFolder("src"); File(src, "a.txt").writeText("new")
        val dst = tmp.newFolder("dst"); File(dst, "a.txt").writeText("old")
        val f = File(src, "a.txt")
        assertEquals(null, FileOps.copy(f, dst, Conflict.SKIP)); assertEquals("old", File(dst, "a.txt").readText())
        assertEquals("a (1).txt", FileOps.copy(f, dst, Conflict.RENAME)!!.name)
        FileOps.copy(f, dst, Conflict.OVERWRITE); assertEquals("new", File(dst, "a.txt").readText())
        // Skipped moves leave the source where it was
        File(dst, "a.txt").writeText("old")
        assertFalse(FileOps.move(f, dst, Conflict.SKIP)); assertTrue(f.exists())
        assertTrue(FileOps.move(f, dst, Conflict.OVERWRITE)); assertFalse(f.exists()); assertEquals("new", File(dst, "a.txt").readText())
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
