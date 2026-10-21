package com.abyssal.files

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipFile

class BrarchiveTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun writeAndReadBack() {
        val f = tmp.newFile("a.brarchive")
        Brarchive.write(f, listOf(Brarchive.Entry("b.json", "{}".toByteArray()), Brarchive.Entry("a.png", byteArrayOf(1, 2, 3)), Brarchive.Entry("c.ogg", ByteArray(0))))
        assertTrue(Brarchive.isBrarchive(f))
        val items = Brarchive.list(f)
        assertEquals(listOf("a.png", "b.json", "c.ogg"), items.map { it.name })
        assertEquals(listOf(3, 2, 0), items.map { it.size })
        assertEquals("{}", String(Brarchive.read(f, items[1])))
        assertEquals(16 + 3 * 256 + 5, f.length().toInt())
    }

    @Test fun minifierMatchesBedrock() {
        assertEquals("""{"a":[1,2.50,-3e2],"b":"x y // not a comment","c":true}""",
            JsonMin.minify("// head\n{ \"a\": [1, 2.50, -3e2], /* c */ \"b\": \"x y // not a comment\",\n \"c\": true }\n// tail"))
        // Anything after the root value is ignored
        assertEquals("""{"a":1}""", JsonMin.minify("{\"a\":1}\n{\"b\":2}"))
        assertEquals("""["é\"q"]""", JsonMin.minify("﻿[ \"é\\\"q\" ]"))
        // Bare words (e.g. unquoted Molang) and trailing commas are rejected, so the file stays loose
        assertNull(JsonMin.minify("{\"range\": v.particle_lifetime}"))
        assertNull(JsonMin.minify("{\"a\": 1,}"))
        assertNull(JsonMin.minify(""))
    }

    @Test fun manifestGetsOptimizationVersion() {
        val m = """
            {
                "format_version": 2,
                "header": {
                    "name": "X",
                    "min_engine_version": [1, 21, 0]
                },
                "modules": [{ "type": "resources" }]
            }
        """.trimIndent()
        val out = Brarchive.markOptimized(m)
        assertTrue(out.contains("\"min_engine_version\": [1, 21, 0],\n        \"pack_optimization_version\": \"0.1.0\"\n    },"))
        assertEquals(out, Brarchive.markOptimized(out))
    }

    @Test fun compilesAResourcePack() {
        val rp = tmp.newFolder("MyRP")
        File(rp, "manifest.json").writeText("""{"format_version":2,"header":{"name":"RP"},"modules":[{"type":"resources"}]}""")
        File(rp, "pack_icon.png").writeBytes(byteArrayOf(9))
        File(rp, "textures/blocks").mkdirs()
        File(rp, "textures/terrain_texture.json").writeText("{ \"a\": 1 }")
        File(rp, "textures/blocks/stone.png").writeBytes(byteArrayOf(1, 2))
        File(rp, "entity").mkdirs()
        File(rp, "entity/pig.json").writeText("{\n  \"x\": [1, 2] // c\n}")
        File(rp, "entity/bad.json").writeText("{ \"x\": v.y }")
        File(rp, "sounds").mkdirs()
        File(rp, "sounds/a.ogg").writeBytes(byteArrayOf(5, 5, 5))
        File(rp, "subpacks/low/textures").mkdirs()
        File(rp, "subpacks/low/textures/x.png").writeBytes(byteArrayOf(7))

        val stats = Brarchive.Stats()
        val out = File(tmp.root, "out/MyRP")
        Brarchive.compilePack(rp, out, Brarchive.PackKind.RESOURCES, stats)

        fun names(rel: String) = Brarchive.list(File(out, "__brarchive/$rel.brarchive")).associate { it.name to it.size }
        assertEquals(mapOf("terrain_texture.json" to 7), names("textures"))
        assertEquals(mapOf("stone.png" to 2), names("textures/blocks"))
        assertEquals(mapOf("bad.json" to 0, "pig.json" to 11), names("entity"))
        assertEquals(mapOf("a.ogg" to 0), names("sounds"))
        assertTrue(File(out, "subpacks/low/__brarchive/textures.brarchive").isFile)
        // Loose: root files, sounds, invalid JSON, and terrain_texture.json (minified)
        assertTrue(File(out, "pack_icon.png").isFile)
        assertTrue(File(out, "sounds/a.ogg").isFile)
        assertTrue(File(out, "entity/bad.json").isFile)
        assertFalse(File(out, "entity/pig.json").exists())
        assertFalse(File(out, "textures/blocks/stone.png").exists())
        assertEquals("""{"a":1}""", File(out, "textures/terrain_texture.json").readText())
        assertTrue(File(out, "manifest.json").readText().contains("pack_optimization_version"))
        assertEquals(1, stats.packs)
    }

    @Test fun packagesAddonsAndPacks() {
        val src = tmp.newFolder("src")
        File(src, "RP").mkdirs(); File(src, "BP").mkdirs()
        File(src, "RP/manifest.json").writeText("""{"header":{},"modules":[{"type":"resources"}]}""")
        File(src, "BP/manifest.json").writeText("""{"header":{},"modules":[{"type":"data"}]}""")
        File(src, "BP/functions").mkdirs(); File(src, "BP/functions/a.mcfunction").writeText("say hi")
        val outDir = tmp.newFolder("dist")
        val (addon, _) = Brarchive.compileToPackage(listOf(src), outDir, "Pack", tmp.newFolder("w1"))
        assertEquals("Pack.mcaddon", addon.name)
        ZipFile(addon).use { z ->
            val names = z.entries().toList().map { it.name }
            assertTrue("BP/functions/a.mcfunction" in names)
            assertTrue("RP/manifest.json" in names)
        }
        val (pack, _) = Brarchive.compileToPackage(listOf(File(src, "RP")), outDir, "Pack", tmp.newFolder("w2"))
        assertEquals("Pack.mcpack", pack.name)
        ZipFile(pack).use { z -> assertTrue(z.getEntry("manifest.json") != null) }
    }

    /**
     * Compares with real PackOptimizer output when available:
     * BRARCHIVE_SAMPLE_SRC=<pack before> BRARCHIVE_SAMPLE_DIST=<same pack compiled by bedrock_server>
     */
    @Test fun matchesPackOptimizerOutput() {
        val src = System.getenv("BRARCHIVE_SAMPLE_SRC")?.let(::File)
        val dist = System.getenv("BRARCHIVE_SAMPLE_DIST")?.let(::File)
        assumeTrue(src?.isDirectory == true && dist?.isDirectory == true)
        val out = File(tmp.root, "mine")
        Brarchive.compilePack(src!!, out, Brarchive.PackKind.RESOURCES, Brarchive.Stats())

        fun archives(root: File) = root.walkTopDown().filter { it.isFile && it.extension == "brarchive" }
            .associateBy { it.relativeTo(root).invariantSeparatorsPath }
        val theirs = archives(dist!!)
        val mine = archives(out)
        assertEquals("only theirs: ${theirs.keys - mine.keys}, only mine: ${mine.keys - theirs.keys}", theirs.keys.sorted(), mine.keys.sorted())

        val num = Regex("-?(?:0|[1-9][0-9]*)(?:\\.[0-9]+)?(?:[eE][+-]?[0-9]+)?")
        fun norm(s: String) = num.replace(s) { it.value.toDouble().toString() }
        var compared = 0
        for ((rel, t) in theirs) {
            val a = Brarchive.list(t)
            val b = Brarchive.list(mine.getValue(rel))
            assertEquals(rel, a.map { it.name }, b.map { it.name })
            for (i in a.indices) {
                val x = Brarchive.read(t, a[i]); val y = Brarchive.read(mine.getValue(rel), b[i])
                if (a[i].name.endsWith(".json")) assertEquals("$rel/${a[i].name}", norm(String(x)), norm(String(y)))
                else assertTrue("$rel/${a[i].name}", x.contentEquals(y))
                compared++
            }
        }
        // Same loose files (ours may keep a few extra JSON the game reads by path)
        fun loose(root: File) = root.walkTopDown().filter { it.isFile && "__brarchive" !in it.path }
            .map { it.relativeTo(root).invariantSeparatorsPath }.toSet()
        assertEquals("only theirs: ${loose(dist) - loose(out)}, only mine: ${loose(out) - loose(dist)}", loose(dist), loose(out))
        println("Compared $compared entries in ${theirs.size} archives")
    }
}
