package com.abyssal.files

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Bedrock's `.brarchive` (what bedrock_server's PackOptimizer writes) and a compiler that does the same job on the phone.
 *
 * File layout, little endian: u64 magic, u32 entry count, u32 version (1), then one 256-byte entry per file
 * (u8 name length, 247 bytes of UTF-8 name, u32 offset, u32 size) and finally the file contents; offsets count
 * from the end of the entry table.
 *
 * What the optimizer does to a resource pack (checked against its real output):
 * - files at the pack root stay as they are; manifest.json gets "pack_optimization_version" in its header
 * - every other folder becomes `__brarchive/<folder>.brarchive` with one entry per file, sorted by name (folders
 *   with files only in subfolders get an empty archive)
 * - JSON is minified (comments and anything after the root value dropped) and moves into the archive;
 *   PNG/TGA/JPG move in unchanged
 * - JSON that doesn't parse, and every other type (sounds, .lang, .material…), stays loose with an empty entry
 * - a few JSON files the game reads by path stay loose too (minified), as well as in the archive
 * - each subpack under `subpacks/` gets its own `__brarchive`
 */
object Brarchive {
    const val MAGIC = 0x267052A0B125277DL
    private const val ENTRY_SIZE = 256
    private const val NAME_MAX = 247
    const val OPTIMIZATION_VERSION = "0.1.0"

    private val IMAGES = setOf("png", "tga", "jpg", "jpeg")

    /** Folder-relative JSON paths the game opens directly: kept loose as well as archived. */
    private val KEEP_LOOSE = setOf(
        "textures/terrain_texture.json", "textures/flipbook_textures.json", "texts/languages.json",
        // Not in the reference output, kept loose to be safe: the game also reads these by path
        "textures/textures_list.json", "texts/language_names.json",
    )

    // ───────── reading ─────────

    data class Item(val name: String, val offset: Long, val size: Int)

    fun isBrarchive(f: File): Boolean = runCatching {
        RandomAccessFile(f, "r").use { r ->
            r.length() >= 16 && ByteBuffer.wrap(ByteArray(8).also { r.readFully(it) }).order(ByteOrder.LITTLE_ENDIAN).long == MAGIC
        }
    }.getOrDefault(false)

    fun list(f: File): List<Item> = RandomAccessFile(f, "r").use { r ->
        val head = ByteArray(16).also { r.readFully(it) }
        val hb = ByteBuffer.wrap(head).order(ByteOrder.LITTLE_ENDIAN)
        require(hb.long == MAGIC) { "Not a .brarchive file" }
        val count = hb.int
        require(count in 0..1_000_000 && 16L + count.toLong() * ENTRY_SIZE <= r.length()) { "Broken .brarchive" }
        val table = ByteArray(count * ENTRY_SIZE).also { r.readFully(it) }
        val base = 16L + count.toLong() * ENTRY_SIZE
        val tb = ByteBuffer.wrap(table).order(ByteOrder.LITTLE_ENDIAN)
        List(count) { i ->
            val o = i * ENTRY_SIZE
            val len = (table[o].toInt() and 0xFF).coerceAtMost(NAME_MAX)
            val name = String(table, o + 1, len, Charsets.UTF_8)
            val off = tb.getInt(o + 248).toLong() and 0xFFFFFFFFL
            val size = tb.getInt(o + 252)
            require(size >= 0 && base + off + size <= r.length()) { "Broken entry: $name" }
            Item(name, base + off, size)
        }
    }

    fun read(f: File, item: Item): ByteArray = RandomAccessFile(f, "r").use { r ->
        ByteArray(item.size).also { r.seek(item.offset); r.readFully(it) }
    }

    /** Unpacks every entry into [outDir] (empty placeholder entries are skipped). Returns the files written. */
    fun unpack(f: File, outDir: File, only: String? = null): Int {
        outDir.mkdirs()
        val root = outDir.canonicalFile
        var n = 0
        for (it in list(f)) {
            if (it.size == 0 || (only != null && it.name != only)) continue
            val out = File(root, it.name).canonicalFile
            if (!out.path.startsWith(root.path + File.separator)) throw SecurityException("Blocked unsafe path: ${it.name}")
            out.writeBytes(read(f, it))
            n++
        }
        return n
    }

    // ───────── writing ─────────

    class Entry(val name: String, val data: ByteArray)

    fun write(out: File, entries: List<Entry>) {
        val sorted = entries.sortedBy { it.name }
        val table = ByteBuffer.allocate(16 + sorted.size * ENTRY_SIZE).order(ByteOrder.LITTLE_ENDIAN)
        table.putLong(MAGIC).putInt(sorted.size).putInt(1)
        var off = 0L
        for (e in sorted) {
            val name = e.name.toByteArray(Charsets.UTF_8)
            require(name.size <= NAME_MAX) { "Name too long: ${e.name}" }
            val start = table.position()
            table.put(name.size.toByte()).put(name)
            table.position(start + 248)
            table.putInt(off.toInt()).putInt(e.data.size)
            off += e.data.size
        }
        out.parentFile?.mkdirs()
        out.outputStream().buffered().use { s ->
            s.write(table.array())
            sorted.forEach { s.write(it.data) }
        }
    }

    // ───────── compiling ─────────

    enum class PackKind { RESOURCES, BEHAVIOR }

    class Stats {
        var packs = 0; var archives = 0; var packed = 0; var loose = 0; var copied = 0
        val notes = ArrayList<String>()
    }

    /** The pack type from manifest.json's modules, like the desktop compiler: null if it isn't a pack. */
    fun packKind(dir: File): PackKind? {
        val m = File(dir, "manifest.json")
        if (!m.isFile) return null
        val text = runCatching { m.readText() }.getOrNull() ?: return null
        val types = Regex("\"type\"\\s*:\\s*\"(\\w+)\"").findAll(text).map { it.groupValues[1] }.toList()
        return when {
            "resources" in types -> PackKind.RESOURCES
            types.any { it in setOf("data", "script", "javascript") } -> PackKind.BEHAVIOR
            else -> null
        }
    }

    /** Packs inside [dir]: the folder itself, or any folder below it with a manifest (not looking inside packs). */
    fun findPacks(dir: File): List<Pair<PackKind, File>> {
        packKind(dir)?.let { return listOf(it to dir) }
        val out = ArrayList<Pair<PackKind, File>>()
        dir.listFiles()?.filter { it.isDirectory && !it.name.startsWith(".") }?.sortedBy { it.name }?.forEach { out += findPacks(it) }
        return out
    }

    /**
     * Optimizes one pack from [src] into [dst]. Resource packs are compiled to brarchives; behavior packs are
     * copied unchanged (the format was only checked against resource-pack output).
     */
    fun compilePack(src: File, dst: File, kind: PackKind, stats: Stats) {
        stats.packs++
        if (kind == PackKind.BEHAVIOR) {
            src.copyRecursively(dst, overwrite = true)
            stats.copied += src.walkTopDown().count { it.isFile }
            stats.notes += "${src.name}: behavior pack copied as is"
            return
        }
        compileRoot(src, dst, stats, manifest = true)
    }

    private fun compileRoot(src: File, dst: File, stats: Stats, manifest: Boolean) {
        dst.mkdirs()
        val children = src.listFiles().orEmpty().sortedBy { it.name }
        for (f in children.filter { it.isFile }) {
            val out = File(dst, f.name)
            if (manifest && f.name == "manifest.json") out.writeText(markOptimized(f.readText())) else f.copyTo(out, overwrite = true)
            stats.loose++
        }
        for (d in children.filter { it.isDirectory }) {
            when (d.name) {
                "__brarchive" -> {}
                "subpacks" -> d.listFiles().orEmpty().sortedBy { it.name }.forEach { sub ->
                    if (sub.isDirectory) compileRoot(sub, File(dst, "subpacks/${sub.name}"), stats, manifest = false)
                    else { sub.copyTo(File(dst, "subpacks/${sub.name}"), overwrite = true); stats.loose++ }
                }
                else -> compileDir(d, d.name, dst, stats)
            }
        }
    }

    private fun compileDir(dir: File, rel: String, packOut: File, stats: Stats) {
        val children = dir.listFiles().orEmpty().sortedBy { it.name }
        val entries = ArrayList<Entry>()
        for (f in children.filter { it.isFile }) {
            val ext = f.extension.lowercase()
            val looseFile = File(packOut, "$rel/${f.name}")
            if (f.name.toByteArray().size > NAME_MAX) {
                f.copyTo(looseFile, overwrite = true); stats.loose++; continue
            }
            when {
                ext == "json" -> {
                    val min = runCatching { JsonMin.minify(f.readText()) }.getOrNull()
                    if (min == null) {
                        entries += Entry(f.name, ByteArray(0))
                        f.copyTo(looseFile, overwrite = true); stats.loose++
                        stats.notes += "$rel/${f.name}: invalid JSON, left as is"
                    } else {
                        val bytes = min.toByteArray(Charsets.UTF_8)
                        entries += Entry(f.name, bytes); stats.packed++
                        if ("$rel/${f.name}" in KEEP_LOOSE) {
                            looseFile.parentFile?.mkdirs(); looseFile.writeBytes(bytes); stats.loose++
                        }
                    }
                }
                ext in IMAGES -> { entries += Entry(f.name, f.readBytes()); stats.packed++ }
                else -> {
                    entries += Entry(f.name, ByteArray(0))
                    f.copyTo(looseFile, overwrite = true); stats.loose++
                }
            }
        }
        // Like the optimizer: every folder with files somewhere below it gets an archive, even an empty one
        if (entries.isNotEmpty() || dir.walkTopDown().any { it.isFile }) {
            write(File(packOut, "__brarchive/$rel.brarchive"), entries)
            stats.archives++
        }
        for (d in children.filter { it.isDirectory }) compileDir(d, "$rel/${d.name}", packOut, stats)
    }

    /** Adds "pack_optimization_version" to the manifest header, keeping the rest of the file as written. */
    fun markOptimized(manifest: String): String {
        if ("pack_optimization_version" in manifest) return manifest
        val m = Regex("\"header\"\\s*:\\s*\\{").find(manifest) ?: return manifest
        // Insert as the header's last key: find its closing brace
        var depth = 0
        var i = m.range.last
        var inStr = false
        while (i < manifest.length) {
            val c = manifest[i]
            if (inStr) {
                if (c == '\\') i++ else if (c == '"') inStr = false
            } else when (c) {
                '"' -> inStr = true
                '{', '[' -> depth++
                '}', ']' -> { depth--; if (depth == 0) break }
            }
            i++
        }
        if (i >= manifest.length) return manifest
        val before = manifest.substring(0, i).trimEnd()
        val indent = Regex("\\n([ \\t]+)\"[^\"]+\"\\s*:[^\\n]*$").find(before)?.groupValues?.get(1) ?: "    "
        val closeIndent = manifest.substring(0, i).substringAfterLast('\n').takeWhile { it == ' ' || it == '\t' }
        val sep = if (before.endsWith("{")) "" else ","
        return before + sep + "\n" + indent + "\"pack_optimization_version\": \"$OPTIMIZATION_VERSION\"\n" + closeIndent + manifest.substring(i)
    }

    /**
     * Compiles [inputs] (pack folders, or folders holding packs) into one .mcpack (a single resource pack) or
     * .mcaddon (anything else), like the desktop brarchive-compiler. [work] is a scratch folder.
     */
    fun compileToPackage(inputs: List<File>, outDir: File, baseName: String, work: File): Pair<File, Stats> {
        val packs = inputs.flatMap { findPacks(it) }.distinctBy { it.second.canonicalPath }
        require(packs.isNotEmpty()) { "No packs found (a pack is a folder with a manifest.json)" }
        work.deleteRecursively(); work.mkdirs()
        val stats = Stats()
        val used = HashSet<String>()
        val built = packs.map { (kind, dir) ->
            var name = dir.name; var n = 2
            while (!used.add(name.lowercase())) name = "${dir.name}_${n++}"
            File(work, name).also { compilePack(dir, it, kind, stats) }
        }
        val single = packs.size == 1 && packs[0].first == PackKind.RESOURCES
        val ext = if (single) "mcpack" else "mcaddon"
        val out = FileOps.unique(outDir, "$baseName.$ext", false)
        ZipOutputStream(out.outputStream().buffered()).use { zos ->
            for (pack in built) {
                pack.walkTopDown().filter { it.isFile }.sortedBy { it.path }.forEach { f ->
                    val rel = f.relativeTo(pack).invariantSeparatorsPath
                    zos.putNextEntry(ZipEntry(if (single) rel else "${pack.name}/$rel"))
                    f.inputStream().use { it.copyTo(zos) }
                    zos.closeEntry()
                }
            }
        }
        work.deleteRecursively()
        return out to stats
    }
}

/** Bedrock-style JSON minifier: allows comments, ignores anything after the root value, keeps literals as written. */
object JsonMin {
    fun minify(text: String): String? {
        val p = Parser(text.removePrefix("\uFEFF"))
        val sb = StringBuilder(text.length)
        return if (p.value(sb)) sb.toString() else null
    }

    private class Parser(val s: String) {
        var i = 0

        fun skip() {
            while (i < s.length) {
                val c = s[i]
                when {
                    c == ' ' || c == '\t' || c == '\n' || c == '\r' -> i++
                    c == '/' && s.getOrNull(i + 1) == '/' -> { i = s.indexOf('\n', i).let { if (it < 0) s.length else it } }
                    c == '/' && s.getOrNull(i + 1) == '*' -> { i = s.indexOf("*/", i + 2).let { if (it < 0) s.length else it + 2 } }
                    else -> return
                }
            }
        }

        fun value(out: StringBuilder): Boolean {
            skip()
            if (i >= s.length) return false
            return when (s[i]) {
                '{' -> obj(out)
                '[' -> arr(out)
                '"' -> str(out)
                't' -> word("true", out)
                'f' -> word("false", out)
                'n' -> word("null", out)
                else -> num(out)
            }
        }

        fun obj(out: StringBuilder): Boolean {
            out.append('{'); i++
            skip()
            if (s.getOrNull(i) == '}') { i++; out.append('}'); return true }
            while (true) {
                skip()
                if (s.getOrNull(i) != '"' || !str(out)) return false
                skip()
                if (s.getOrNull(i) != ':') return false
                i++; out.append(':')
                if (!value(out)) return false
                skip()
                when (s.getOrNull(i)) {
                    ',' -> { i++; out.append(',') }
                    '}' -> { i++; out.append('}'); return true }
                    else -> return false
                }
            }
        }

        fun arr(out: StringBuilder): Boolean {
            out.append('['); i++
            skip()
            if (s.getOrNull(i) == ']') { i++; out.append(']'); return true }
            while (true) {
                if (!value(out)) return false
                skip()
                when (s.getOrNull(i)) {
                    ',' -> { i++; out.append(',') }
                    ']' -> { i++; out.append(']'); return true }
                    else -> return false
                }
            }
        }

        fun str(out: StringBuilder): Boolean {
            val start = i
            i++
            while (i < s.length) {
                when (s[i]) {
                    '\\' -> i += 2
                    '"' -> { i++; out.append(s, start, i); return true }
                    '\n' -> return false
                    else -> i++
                }
            }
            return false
        }

        fun word(w: String, out: StringBuilder): Boolean {
            if (!s.startsWith(w, i)) return false
            i += w.length; out.append(w); return true
        }

        fun num(out: StringBuilder): Boolean {
            val m = NUM.matchAt(s, i) ?: return false
            out.append(m.value); i = m.range.last + 1
            return true
        }

        companion object {
            val NUM = Regex("-?(?:0|[1-9][0-9]*)(?:\\.[0-9]+)?(?:[eE][+-]?[0-9]+)?")
        }
    }
}
