package com.abyssal.files

import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.webkit.MimeTypeMap
import java.io.*
import java.text.DateFormat
import java.util.*
import java.util.zip.*

/** [normal] / [reversed] describe the order, e.g. "Newest first" / "Oldest first". */
enum class Sort(val label: String, val normal: String, val reversed: String) {
    NAME("Name", "A → Z", "Z → A"),
    DATE("Date", "Newest first", "Oldest first"),
    SIZE("Size", "Largest first", "Smallest first"),
    TYPE("Type", "A → Z", "Z → A"),
}

/** What a file is, for behaviour (thumbnails, player, editor). [key] is its generic icon. */
enum class Kind(val key: String) {
    FOLDER("folder"), IMAGE("image"), VIDEO("video"), AUDIO("audio"), TEXT("text"),
    CODE("code"), ARCHIVE("archive"), APK("apk"), DOCUMENT("document"), FILE("file"),
}

data class FileItem(
    val path: String,
    val name: String,
    val isDir: Boolean,
    val size: Long,
    val modified: Long,
    val count: Int,
) {
    val ext: String get() = if (isDir) "" else name.substringAfterLast('.', "").lowercase()
    val kind: Kind get() = if (isDir) Kind.FOLDER else FileOps.kind(ext)

    companion object {
        fun of(f: File) = FileItem(
            path = f.path,
            name = f.name,
            isDir = f.isDirectory,
            size = if (f.isFile) f.length() else 0L,
            modified = f.lastModified(),
            count = if (f.isDirectory) (f.list()?.size ?: 0) else -1,
        )
    }
}

object FileOps {
    val IMAGES = setOf("jpg", "jpeg", "png", "gif", "webp", "bmp", "heic", "heif", "avif", "tif", "tiff", "ico", "dng")
    val VIDEOS = setOf("mp4", "mkv", "webm", "3gp", "3g2", "avi", "mov", "m4v", "flv", "wmv", "mpg", "mpeg", "ogv")
    val AUDIO = setOf("mp3", "m4a", "wav", "flac", "ogg", "oga", "aac", "opus", "amr", "mid", "midi", "wma", "aiff", "aif", "mka", "m4b")
    val DOCS = setOf("pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "txt", "md", "csv", "rtf", "odt", "ods", "odp", "epub", "mobi")
    val ARCHIVES = setOf("zip", "gz", "tgz", "xz", "bz2", "zst", "tar", "7z", "rar", "lz", "lzma", "cab", "mcpack", "mcaddon", "mcworld", "mctemplate", "jar")
    /** Zip files under another name: these can be extracted too. */
    val ZIPLIKE = setOf("zip", "mcpack", "mcaddon", "mcworld", "mctemplate", "jar", "apk", "xapk", "apks", "epub", "docx", "xlsx", "pptx", "odt")
    /** Archives that open in the built-in archive browser when tapped. */
    val ZIP_BROWSE = setOf("zip", "jar", "aar", "war", "mcpack", "mcaddon", "mcworld", "mctemplate")
    val APKS = setOf("apk", "apks", "xapk", "apkm", "aab")
    val TEXT = setOf(
        "txt", "md", "markdown", "log", "csv", "tsv", "ini", "cfg", "conf", "properties", "srt", "vtt", "ass", "lrc",
        "nfo", "env", "rst", "lang", "mcfunction", "mcmeta", "editorconfig", "gitattributes", "gitignore",
    )
    val CODE = setOf(
        "json", "jsonc", "json5", "xml", "html", "htm", "xhtml", "css", "scss", "sass", "less", "js", "mjs", "cjs",
        "ts", "jsx", "tsx", "kt", "kts", "java", "py", "pyw", "ipynb", "sh", "bash", "zsh", "fish", "c", "cpp", "cc",
        "h", "hpp", "cs", "gradle", "sql", "yaml", "yml", "toml", "php", "rb", "go", "rs", "swift", "dart", "lua",
        "bat", "cmd", "ps1", "vue", "svelte", "svg", "plist", "r", "pl", "scala", "groovy", "dockerfile",
    )

    fun kind(ext: String): Kind = when (ext) {
        in IMAGES -> Kind.IMAGE
        in VIDEOS -> Kind.VIDEO
        in AUDIO -> Kind.AUDIO
        in CODE -> Kind.CODE
        in TEXT -> Kind.TEXT
        in DOCS -> Kind.DOCUMENT
        in ARCHIVES -> Kind.ARCHIVE
        in APKS -> Kind.APK
        else -> Kind.FILE
    }

    /** Opened in the built-in editor on tap. */
    fun editable(item: FileItem) = !item.isDir && (item.kind == Kind.TEXT || item.kind == Kind.CODE || item.ext.isEmpty())

    /** Folders always first; [reversed] flips the order inside each group. */
    fun sort(list: List<FileItem>, s: Sort, reversed: Boolean = false): List<FileItem> {
        val c: Comparator<FileItem> = when (s) {
            Sort.NAME -> compareBy<FileItem, String>(String.CASE_INSENSITIVE_ORDER) { it.name }
            Sort.DATE -> compareByDescending<FileItem> { it.modified }
            Sort.SIZE -> compareByDescending<FileItem> { it.size }
            Sort.TYPE -> compareBy<FileItem> { it.ext }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.name }
        }
        return list.sortedWith(compareBy<FileItem> { !it.isDir }.then(if (reversed) c.reversed() else c))
    }

    fun list(dir: File, hidden: Boolean, s: Sort, reversed: Boolean): List<FileItem> {
        val arr = dir.listFiles() ?: return emptyList()
        return sort(
            arr.filter { hidden || !it.name.startsWith(".") }.map { FileItem.of(it) },
            s, reversed,
        )
    }

    /** Everything below [root] whose name contains [q]. */
    fun search(root: File, q: String, hidden: Boolean): Sequence<File> =
        root.walkTopDown()
            .onEnter { it == root || hidden || !it.name.startsWith(".") }
            .filter { it != root && (hidden || !it.name.startsWith(".")) && it.name.contains(q, ignoreCase = true) }

    /**
     * Category view: files grouped by the folder that contains them. Folders are ordered by name,
     * newest file or total size, following the current sort; files inside keep the list order.
     */
    fun groupByFolder(list: List<FileItem>, s: Sort, reversed: Boolean): List<Pair<String, List<FileItem>>> {
        val groups = list.groupBy { it.path.substringBeforeLast('/') }.toList()
        val c: Comparator<Pair<String, List<FileItem>>> = when (s) {
            Sort.DATE -> compareByDescending { g -> g.second.maxOf { it.modified } }
            Sort.SIZE -> compareByDescending { g -> g.second.sumOf { it.size } }
            else -> compareBy(String.CASE_INSENSITIVE_ORDER) { it.first.substringAfterLast('/') }
        }
        return groups.sortedWith(if (reversed) c.reversed() else c)
    }

    /** Recursive scan by extension; skips hidden dirs and Android/ (app-private data). */
    fun scan(root: File, exts: Set<String>, limit: Int = 3000): List<FileItem> {
        val out = ArrayList<FileItem>()
        root.walkTopDown()
            .onEnter { d -> !d.name.startsWith(".") && !(d.parentFile == root && d.name == "Android") }
            .forEach { f ->
                if (f.isFile && f.extension.lowercase() in exts) {
                    out += FileItem.of(f)
                    if (out.size >= limit) return out
                }
            }
        return out
    }

    fun unique(dir: File, name: String, isDir: Boolean): File {
        var f = File(dir, name)
        if (!f.exists()) return f
        val hasExt = !isDir && name.contains('.') && !name.startsWith(".")
        val base = if (hasExt) name.substringBeforeLast('.') else name
        val ext = if (hasExt) "." + name.substringAfterLast('.') else ""
        var i = 1
        while (true) {
            f = File(dir, "$base ($i)$ext")
            if (!f.exists()) return f
            i++
        }
    }

    fun copy(src: File, dstDir: File): File {
        val dst = unique(dstDir, src.name, src.isDirectory)
        if (src.isDirectory) src.copyRecursively(dst, overwrite = false) else src.copyTo(dst)
        return dst
    }

    fun move(src: File, dstDir: File) {
        if (src.parentFile?.canonicalPath == dstDir.canonicalPath) return
        val dst = unique(dstDir, src.name, src.isDirectory)
        if (!src.renameTo(dst)) {
            copy(src, dstDir)
            src.deleteRecursively()
        }
    }

    fun createFile(dir: File, name: String): File {
        val f = File(dir, name)
        if (f.exists()) error("Name already exists")
        if (!f.createNewFile()) error("Could not create file")
        template(f.extension.lowercase())?.let { f.writeText(it) }
        return f
    }

    private fun template(ext: String): String? = when (ext) {
        "json" -> "{\n  \n}\n"
        "html", "htm" -> "<!doctype html>\n<html>\n<head>\n  <meta charset=\"utf-8\">\n  <title></title>\n</head>\n<body>\n\n</body>\n</html>\n"
        "xml" -> "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n"
        "sh" -> "#!/bin/sh\n"
        else -> null
    }

    fun looksBinary(f: File): Boolean = runCatching {
        f.inputStream().use { s ->
            val b = ByteArray(8000)
            val n = s.read(b)
            (0 until n.coerceAtLeast(0)).any { b[it] == 0.toByte() }
        }
    }.getOrDefault(false)

    fun zip(files: List<File>, out: File) {
        ZipOutputStream(BufferedOutputStream(FileOutputStream(out))).use { z ->
            fun add(f: File, path: String) {
                if (f.isDirectory) {
                    val kids = f.listFiles().orEmpty()
                    if (kids.isEmpty()) { z.putNextEntry(ZipEntry("$path/")); z.closeEntry() }
                    kids.forEach { add(it, "$path/${it.name}") }
                } else {
                    z.putNextEntry(ZipEntry(path))
                    f.inputStream().use { it.copyTo(z) }
                    z.closeEntry()
                }
            }
            files.forEach { add(it, it.name) }
        }
    }

    /** Opens a zip; falls back to the old DOS charset for archives with non-UTF-8 names. */
    fun openZip(f: File): ZipFile = try {
        ZipFile(f).also { z -> z.entries().toList() }
    } catch (e: ZipException) {
        ZipFile(f, Charsets.ISO_8859_1)
    } catch (e: IllegalArgumentException) {
        ZipFile(f, Charsets.ISO_8859_1)
    }

    /**
     * Extracts [zip] into [outDir]. With [only] ("dir/" or "dir/file") just that entry is extracted,
     * placed directly in [outDir]. Existing files are kept: new ones get a " (1)" suffix.
     */
    fun unzip(zip: File, outDir: File, only: String? = null) {
        outDir.mkdirs()
        val base = outDir.canonicalPath + File.separator
        val strip = only?.trimEnd('/')?.substringBeforeLast('/', "")?.let { if (it.isEmpty()) "" else "$it/" } ?: ""
        openZip(zip).use { z ->
            for (e in z.entries()) {
                val name = e.name.replace('\\', '/')
                if (only != null && !(name == only || (only.endsWith("/") && name.startsWith(only)))) continue
                val rel = name.removePrefix(strip)
                if (rel.isEmpty()) continue
                var t = File(outDir, rel)
                if (!t.canonicalPath.startsWith(base)) throw SecurityException("Unsafe archive entry")
                if (e.isDirectory) t.mkdirs() else {
                    t.parentFile?.mkdirs()
                    if (t.exists()) t = unique(t.parentFile!!, t.name, false)
                    z.getInputStream(e).use { i -> FileOutputStream(t).use { i.copyTo(it) } }
                }
            }
        }
    }

    data class ZEntry(val path: String, val name: String, val isDir: Boolean, val size: Long, val compressed: Long, val time: Long)

    class ZipListing(
        val children: Map<String, List<ZEntry>>, val files: Int, val size: Long, val compressed: Long, val error: String? = null,
    )

    private fun zipParent(p: String) = p.trimEnd('/').substringBeforeLast('/', "").let { if (it.isEmpty()) "" else "$it/" }

    /** Archive contents as a folder tree (folders implied by paths are added). */
    fun zipListing(f: File): ZipListing = try {
        openZip(f).use { z ->
            val map = LinkedHashMap<String, ZEntry>()
            fun ensureDir(p: String) {
                if (p.isEmpty() || p in map) return
                map[p] = ZEntry(p, p.trimEnd('/').substringAfterLast('/'), true, 0, 0, 0)
                ensureDir(zipParent(p))
            }
            var files = 0
            var size = 0L
            var compressed = 0L
            for (e in z.entries()) {
                val name = e.name.replace('\\', '/').trimStart('/')
                if (name.isEmpty()) continue
                if (e.isDirectory) ensureDir(if (name.endsWith("/")) name else "$name/") else {
                    map[name] = ZEntry(name, name.substringAfterLast('/'), false, e.size.coerceAtLeast(0), e.compressedSize.coerceAtLeast(0), e.time)
                    ensureDir(zipParent(name))
                    files++; size += e.size.coerceAtLeast(0); compressed += e.compressedSize.coerceAtLeast(0)
                }
            }
            val children = map.values.groupBy { zipParent(it.path) }.mapValues { (_, l) ->
                l.sortedWith(compareBy<ZEntry> { !it.isDir }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.name })
            }
            ZipListing(children, files, size, compressed)
        }
    } catch (e: Exception) {
        ZipListing(emptyMap(), 0, 0, 0, e.message ?: "unknown error")
    }

    /** Copies one entry to the app cache so it can be opened. */
    fun extractToCache(cacheDir: File, zip: File, entry: String): File {
        val dir = File(cacheDir, "zipview/${zip.path.hashCode().toUInt()}-${zip.lastModified()}")
        val out = File(dir, entry)
        if (!out.canonicalPath.startsWith(dir.canonicalPath + File.separator)) throw SecurityException("Unsafe archive entry")
        if (out.isFile) return out
        out.parentFile?.mkdirs()
        openZip(zip).use { z ->
            val e = z.getEntry(entry) ?: error("Entry not found")
            z.getInputStream(e).use { i -> FileOutputStream(out).use { i.copyTo(it) } }
        }
        return out
    }

    // ---- properties ----

    data class Stats(val size: Long, val files: Int, val dirs: Int)

    /** Total size and content count; the given roots themselves are not counted. */
    fun stats(roots: List<File>): Stats {
        var size = 0L
        var files = 0
        var dirs = 0
        roots.forEach { r ->
            r.walkTopDown().forEach {
                if (it.isFile) { size += it.length(); files++ } else if (it != r) dirs++
            }
        }
        return Stats(size, files, dirs)
    }

    fun mediaInfo(f: File): List<Pair<String, String>> = runCatching {
        when (kind(f.extension.lowercase())) {
            Kind.IMAGE -> {
                val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(f.path, o)
                if (o.outWidth > 0) listOf("Dimensions" to "${o.outWidth} × ${o.outHeight} px") else emptyList()
            }
            Kind.AUDIO, Kind.VIDEO -> {
                val r = MediaMetadataRetriever()
                try {
                    r.setDataSource(f.path)
                    fun m(k: Int) = r.extractMetadata(k)?.takeIf { it.isNotBlank() }
                    buildList {
                        m(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()?.let { add("Duration" to fmtTime(it)) }
                        val w = m(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
                        val h = m(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
                        if (w != null && h != null) add("Resolution" to "$w × $h")
                        m(MediaMetadataRetriever.METADATA_KEY_TITLE)?.let { add("Title" to it) }
                        m(MediaMetadataRetriever.METADATA_KEY_ARTIST)?.let { add("Artist" to it) }
                        m(MediaMetadataRetriever.METADATA_KEY_ALBUM)?.let { add("Album" to it) }
                        m(MediaMetadataRetriever.METADATA_KEY_BITRATE)?.toLongOrNull()?.let { add("Bitrate" to "${it / 1000} kbps") }
                    }
                } finally { r.release() }
            }
            else -> emptyList()
        }
    }.getOrDefault(emptyList())

    fun mime(f: File): String =
        MimeTypeMap.getSingleton().getMimeTypeFromExtension(f.extension.lowercase()) ?: "*/*"

    /** Decimal units, like Android's own Settings and the size printed on the box. */
    fun fmtSize(b: Long): String {
        if (b < 1000) return "$b B"
        val u = arrayOf("kB", "MB", "GB", "TB")
        var v = b / 1000.0
        var i = 0
        while (v >= 1000 && i < u.lastIndex) { v /= 1000; i++ }
        return "%.1f".format(v).replace(Regex("[.,]0$"), "") + " " + u[i]
    }

    fun fmtTime(ms: Long): String {
        val s = ms / 1000
        return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
    }

    private val df = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
    private val dfFull = DateFormat.getDateTimeInstance(DateFormat.LONG, DateFormat.MEDIUM)
    fun fmtDate(ms: Long): String = synchronized(df) { df.format(Date(ms)) }
    fun fmtDateFull(ms: Long): String = synchronized(dfFull) { dfFull.format(Date(ms)) }
}
