package com.abyssal.files

import android.content.res.AssetManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.media.ThumbnailUtils
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.LruCache
import android.util.Size
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Pixel-art icons defined as text grids (assets/icons/<name>.txt, format described in each file).
 * Colours can refer to the theme colour, so the icons follow the colour picker.
 * Files with the same name in [CustomIcons.dir] (.txt grids or .png images) replace the defaults.
 */
object PixelIcons {
    private class Def(val w: Int, val rows: List<String>, val colors: Map<Char, String>, val type: String)

    private var defaults: Map<String, Def> = emptyMap()
    private var overrides: Map<String, Def> = emptyMap()
    private val cache = LruCache<String, ImageBitmap>(400)

    /** Bumped on every (re)load so composables redraw. */
    var version by mutableIntStateOf(0); private set

    val names: List<String> get() = defaults.keys.sorted()

    fun loadDefaults(assets: AssetManager) {
        defaults = assets.list("icons").orEmpty().filter { it.endsWith(".txt") }.mapNotNull { f ->
            parse(assets.open("icons/$f").bufferedReader().use { it.readText() })?.let { f.removeSuffix(".txt") to it }
        }.toMap()
        changed()
    }

    /** Returns how many .txt overrides were loaded. Call off the main thread. */
    fun loadOverrides(dir: File): Int {
        overrides = dir.listFiles().orEmpty().filter { it.isFile && it.extension.equals("txt", true) }.mapNotNull { f ->
            runCatching { parse(f.readText()) }.getOrNull()?.let { f.nameWithoutExtension.lowercase() to it }
        }.toMap()
        changed()
        return overrides.size
    }

    fun exportDefaults(assets: AssetManager, dir: File): Int {
        dir.mkdirs()
        var n = 0
        assets.list("icons").orEmpty().forEach { f ->
            val out = File(dir, f)
            if (!out.exists()) { assets.open("icons/$f").use { i -> out.outputStream().use { i.copyTo(it) } }; n++ }
        }
        return n
    }

    private fun changed() {
        cache.evictAll()
        version++
    }

    fun has(name: String) = name in overrides || name in defaults

    fun bitmap(name: String, accent: Int, colorful: Boolean, version: Int): ImageBitmap? {
        val d = overrides[name] ?: defaults[name] ?: return null
        val key = "$name|$accent|$colorful|$version"
        cache.get(key)?.let { return it }
        // "Theme colour" mode: every icon's own colour becomes the accent
        val type = if (colorful && d.type != "@accent") eval(d.type, accent, accent) else accent
        val palette = d.colors.mapValues { eval(it.value, accent, type) }
        val px = IntArray(d.w * d.rows.size)
        d.rows.forEachIndexed { y, r -> r.forEachIndexed { x, c -> if (c != '.') px[y * d.w + x] = palette[c] ?: 0 } }
        val bmp = Bitmap.createBitmap(px, d.w, d.rows.size, Bitmap.Config.ARGB_8888).asImageBitmap()
        cache.put(key, bmp)
        return bmp
    }

    private fun parse(text: String): Def? {
        val colors = HashMap<Char, String>()
        var type = "@accent"
        val rows = ArrayList<String>()
        text.lineSequence().forEach { raw ->
            val line = raw.trimEnd()
            when {
                line.isBlank() || line == "#" || line.startsWith("# ") -> {}
                '=' in line -> {
                    val k = line.substringBefore('=').trim()
                    val v = line.substringAfter('=').trim()
                    if (k == "type") type = v else if (k.length == 1) colors[k[0]] = v
                }
                else -> rows += line.trim()
            }
        }
        if (rows.isEmpty()) return null
        val w = rows.maxOf { it.length }
        return Def(w, rows.map { it.padEnd(w, '.') }, colors, type)
    }

    /** "#RRGGBB", "@accent" or "@type", optionally followed by "*f" (darken) or "+f" (lighten). */
    private fun eval(expr: String, accent: Int, type: Int): Int {
        val e = expr.replace(" ", "")
        val op = e.indexOfAny(charArrayOf('*', '+'), startIndex = 1)
        val base = if (op > 0) e.substring(0, op) else e
        var c = when (base) {
            "@accent" -> accent
            "@type" -> type
            else -> runCatching { android.graphics.Color.parseColor(base) }.getOrDefault(0xFFFF00FF.toInt())
        }
        if (op > 0) {
            val f = e.substring(op + 1).toFloatOrNull()?.coerceIn(0f, 1f) ?: 0f
            c = if (e[op] == '*') mapRgb(c) { it * f } else mapRgb(c) { it + (255 - it) * f }
        }
        return c
    }

    private fun mapRgb(c: Int, f: (Float) -> Float): Int {
        fun ch(shift: Int) = f(((c shr shift) and 0xFF).toFloat()).toInt().coerceIn(0, 255) shl shift
        return (c and 0xFF000000.toInt()) or ch(16) or ch(8) or ch(0)
    }
}

/** PNG replacements in Internal storage/AbyssalFiles/icons (name.png). */
object CustomIcons {
    val dir: File get() = File(Environment.getExternalStorageDirectory(), "AbyssalFiles/icons")
    var map by mutableStateOf<Map<String, ImageBitmap>>(emptyMap())

    fun load(): Map<String, ImageBitmap> = dir.listFiles().orEmpty()
        .filter { it.isFile && it.extension.lowercase() in setOf("png", "webp", "jpg", "jpeg") }
        .mapNotNull { f -> BitmapFactory.decodeFile(f.path)?.let { f.nameWithoutExtension.lowercase() to it.asImageBitmap() } }
        .toMap()
}

/** VS Code-style icon theme: file name / extension / folder name → icon name. */
object IconMap {
    private val byExt = HashMap<String, String>().apply {
        fun add(icon: String, vararg exts: String) = exts.forEach { put(it, icon) }
        add("pdf", "pdf")
        add("word", "doc", "docx", "odt", "rtf", "pages", "wps")
        add("excel", "xls", "xlsx", "xlsm", "ods", "numbers")
        add("csv", "csv", "tsv")
        add("powerpoint", "ppt", "pptx", "odp")
        add("json", "json", "jsonc", "json5", "geojson", "mcmeta")
        add("html", "html", "htm", "xhtml")
        add("css", "css", "scss", "sass", "less")
        add("js", "js", "mjs", "cjs", "jsx")
        add("ts", "ts", "tsx", "mts", "cts")
        add("python", "py", "pyw", "pyc", "pyi")
        add("notebook", "ipynb")
        add("kotlin", "kt", "kts")
        add("java", "java", "class")
        add("jar", "jar", "war", "aar")
        add("c", "c", "h")
        add("cpp", "cpp", "cc", "cxx", "hpp", "hh", "hxx")
        add("csharp", "cs", "csx")
        add("go", "go")
        add("rust", "rs")
        add("php", "php")
        add("ruby", "rb", "gemspec")
        add("swift", "swift")
        add("dart", "dart")
        add("lua", "lua")
        add("vue", "vue", "svelte")
        add("xml", "xml", "plist", "xaml", "xsd", "xsl")
        add("svg", "svg")
        add("markdown", "md", "markdown", "mdx")
        add("text", "txt", "nfo", "rst", "lang")
        add("log", "log", "out")
        add("subtitle", "srt", "vtt", "ass", "ssa", "sub", "lrc")
        add("exe", "exe", "msi", "bin", "run", "appimage", "deb", "rpm", "com", "elf")
        add("library", "dll", "so", "dylib", "a", "lib", "o")
        add("git", "gitignore", "gitattributes", "gitmodules", "patch", "diff")
        add("shell", "sh", "bash", "zsh", "fish", "command")
        add("bat", "bat", "cmd", "ps1", "psm1", "vbs")
        add("config", "ini", "cfg", "conf", "properties", "env", "toml", "editorconfig", "prefs")
        add("yaml", "yaml", "yml")
        add("sql", "sql")
        add("database", "db", "sqlite", "sqlite3", "db3", "mdb", "accdb", "realm", "dbf")
        add("font", "ttf", "otf", "woff", "woff2", "fnt", "fon")
        add("ebook", "epub", "mobi", "azw", "azw3", "fb2", "djvu", "cbz", "cbr")
        add("key", "pem", "key", "crt", "cer", "der", "p12", "pfx", "jks", "keystore", "pub", "asc", "sig")
        add("lock", "lock", "kdbx", "aes", "enc", "gpg", "locked")
        add("disk", "iso", "img", "dmg", "vhd", "vhdx", "vmdk", "vdi", "qcow2")
        add("torrent", "torrent")
        add("model3d", "obj", "stl", "fbx", "blend", "glb", "gltf", "3ds", "dae", "ply", "usdz")
        add("psd", "psd", "psb")
        add("illustrator", "ai", "eps")
        add("backup", "bak", "old", "tmp", "temp", "swp", "orig", "backup", "crdownload", "part")
        add(
            "game", "nes", "sfc", "smc", "gb", "gbc", "gba", "n64", "z64", "v64", "nds", "3ds", "cia", "xci", "nsp",
            "wbfs", "gcm", "rvz", "cso", "pbp", "sav", "srm", "gg", "sms",
        )
        add("mail", "eml", "msg", "mbox", "emlx")
        add("link", "url", "webloc", "lnk", "desktop", "website")
        add("contact", "vcf", "vcard")
        add("calendar", "ics", "ical", "vcs")
        add("mcworld", "mcworld")
        add("mctemplate", "mctemplate")
        add("mcpack", "mcpack")
        add("mcaddon", "mcaddon")
        add("mcstructure", "mcstructure")
        add("brarchive", "brarchive")
        add("mcfunction", "mcfunction")
        add("nbt", "nbt", "schem", "schematic", "litematic")
        add("mca", "mca", "mcr")
        add("apk", "apk", "apks", "xapk", "apkm", "aab")
        add("archive", "zip", "rar", "7z", "tar", "gz", "tgz", "xz", "bz2", "tbz2", "zst", "lz", "lzma", "cab", "arj", "z")
        add("docker", "dockerfile")
        add("gradle", "gradle")
    }

    private val byName = mapOf(
        "dockerfile" to "docker", "docker-compose.yml" to "docker", "docker-compose.yaml" to "docker",
        "makefile" to "config", "cmakelists.txt" to "config",
        "license" to "license", "license.md" to "license", "license.txt" to "license", "licence" to "license", "copying" to "license",
        "package.json" to "npm", "package-lock.json" to "npm", ".npmrc" to "npm",
        "build.gradle" to "gradle", "build.gradle.kts" to "gradle", "settings.gradle" to "gradle",
        "settings.gradle.kts" to "gradle", "gradlew" to "gradle", "gradle.properties" to "gradle",
        "pack.mcmeta" to "mcpack", "level.dat" to "mcworld", "manifest.json" to "json",
    )

    private val folders = HashMap<String, String>().apply {
        fun add(icon: String, vararg names: String) = names.forEach { put(it, icon) }
        add("folder-download", "download", "downloads")
        add("folder-camera", "dcim", "camera")
        add("folder-images", "pictures", "images", "screenshots", "wallpapers", "photos", "fotos", "imagens")
        add("folder-music", "music", "audio", "ringtones", "notifications", "alarms", "podcasts", "recordings", "audiobooks", "músicas")
        add("folder-video", "movies", "videos", "vídeos", "filmes")
        add("folder-docs", "documents", "docs", "documentos")
        add("folder-android", "android", "obb")
        add("folder-code", "src", "code", "projects", "dev", "github", "git", "scripts")
        add("folder-archive", "archives", "backup", "backups", "zips")
        add("folder-games", "games", "roms", "emulators", "jogos")
        add(
            "folder-minecraft", "minecraft", ".minecraft", "com.mojang", "minecraftworlds", "minecraftpe",
            "resource_packs", "behavior_packs", "resourcepacks", "saves", "development_behavior_packs",
            "development_resource_packs", "world_templates", "skin_packs",
        )
        add("folder-app", "abyssalfiles")
    }

    fun forFolder(name: String): String =
        folders[name.lowercase()] ?: if (name.startsWith(".")) "folder-hidden" else "folder"

    fun forFile(name: String, ext: String): String {
        val n = name.lowercase()
        byName[n]?.let { return it }
        if (n.startsWith("readme")) return "readme"
        byExt[ext]?.let { return it }
        return FileOps.kind(ext).key
    }

    fun of(item: FileItem): String = if (item.isDir) forFolder(item.name) else forFile(item.name, item.ext)
}

/** Draws a named icon: custom PNG > custom/default pixel grid > generic fallback. */
@Composable
fun PixIcon(name: String, size: Dp = 32.dp, modifier: Modifier = Modifier, contentDescription: String? = null) {
    val png = CustomIcons.map[name]
    val bmp = png ?: run {
        val accent = Pal.accent.toArgb()
        val v = PixelIcons.version
        PixelIcons.bitmap(name, accent, Pal.colorfulIcons, v)
            ?: PixelIcons.bitmap(if (name.startsWith("folder")) "folder" else "file", accent, Pal.colorfulIcons, v)
    }
    if (bmp == null) {
        Box(modifier.size(size))
        return
    }
    val painter = remember(bmp) {
        // Pixel art stays crisp; big custom PNGs are smoothed
        BitmapPainter(bmp, filterQuality = if (bmp.width <= 64) FilterQuality.None else FilterQuality.Medium)
    }
    Image(painter, contentDescription, modifier.size(size))
}

@Composable
fun KindIcon(kind: Kind, size: Dp = 32.dp) = PixIcon(kind.key, size)

// ───────── thumbnails ─────────

private val thumbCache = LruCache<String, ImageBitmap>(300)
private val thumbGate = Semaphore(3)
private const val THUMB_PX = 144

/** Thumbnail of a photo or video, loaded in the background (null until ready or for other files). */
@Composable
fun rememberThumb(item: FileItem, px: Int = THUMB_PX): ImageBitmap? {
    val kind = item.kind
    if (kind != Kind.IMAGE && kind != Kind.VIDEO) return null
    val key = "${item.path}:${item.modified}:$px"
    return produceState(thumbCache.get(key), key) {
        if (value == null) {
            value = thumbGate.withPermit { withContext(Dispatchers.IO) { loadThumb(item.path, kind, px) } }
                ?.also { thumbCache.put(key, it) }
        }
    }.value
}

// ───────── Minecraft pack icons ─────────

private val packIcons = LruCache<String, ImageBitmap>(80)
private val notPacks = java.util.Collections.synchronizedSet(HashSet<String>())

/**
 * pack_icon.png of a behavior or resource pack folder (development ones too): any folder whose manifest.json
 * declares a resources/data/script module. Other folders with a pack_icon.png keep their normal icon.
 */
@Composable
fun rememberPackIcon(path: String): ImageBitmap? = produceState(packIcons.get(path), path) {
    if (value == null && path !in notPacks) {
        value = withContext(Dispatchers.IO) {
            val dir = File(path)
            val icon = File(dir, "pack_icon.png")
            if (!icon.isFile || Brarchive.packKind(dir) == null) { notPacks += path; return@withContext null }
            runCatching {
                val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(icon.path, o)
                var s = 1
                while (o.outWidth / (s * 2) >= 128) s *= 2
                BitmapFactory.decodeFile(icon.path, BitmapFactory.Options().apply { inSampleSize = s })?.asImageBitmap()
            }.getOrNull()?.also { packIcons.put(path, it) }
        }
    }
}.value

/** A folder's icon; Minecraft packs show their pack_icon.png on the folder. */
@Composable
fun FolderIcon(path: String, name: String, size: Dp) {
    val pack = rememberPackIcon(path)
    if (pack == null) {
        PixIcon(IconMap.forFolder(name), size)
    } else {
        val painter = remember(pack) { BitmapPainter(pack, filterQuality = if (pack.width <= 64) FilterQuality.None else FilterQuality.Medium) }
        Box(Modifier.size(size)) {
            PixIcon("folder", size)
            // Over the folder's front, like a picture in it (3..13 × 4..14 of the 16-pixel grid)
            Image(
                painter, "Pack icon",
                Modifier.offset(x = size * 3 / 16, y = size * 4 / 16).size(size * 10 / 16),
            )
        }
    }
}

/** Photo / video thumbnail when available, otherwise the file-type icon. */
@Composable
fun FileIcon(item: FileItem, size: Dp = 36.dp) {
    if (item.isDir) { FolderIcon(item.path, item.name, size); return }
    val kind = item.kind
    val thumb = rememberThumb(item)

    if (thumb == null) {
        PixIcon(IconMap.of(item), size)
    } else {
        Box(Modifier.size(size).clip(RoundedCornerShape(6.dp)).background(Panel2)) {
            Image(thumb, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            if (kind == Kind.VIDEO) {
                Icon(
                    Icons.Filled.PlayArrow, null, tint = Color.White,
                    modifier = Modifier.align(Alignment.Center).size(size * 0.5f)
                        .clip(CircleShape).background(Color.Black.copy(alpha = 0.45f)).padding(2.dp),
                )
            }
        }
    }
}

/** Square, cropped media tile for galleries; falls back to the file icon. */
@Composable
fun MediaThumb(item: FileItem, modifier: Modifier, px: Int = 320) {
    val thumb = rememberThumb(item, px)
    Box(modifier.background(Panel2), contentAlignment = Alignment.Center) {
        if (thumb != null) Image(thumb, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        else PixIcon(IconMap.of(item), 40.dp)
        if (item.kind == Kind.VIDEO) {
            Icon(
                Icons.Filled.PlayArrow, "Video", tint = Color.White,
                modifier = Modifier.align(Alignment.BottomStart).padding(6.dp).size(22.dp)
                    .clip(CircleShape).background(Color.Black.copy(alpha = 0.5f)).padding(2.dp),
            )
        }
    }
}

private fun loadThumb(path: String, kind: Kind, px: Int): ImageBitmap? = runCatching {
    val bmp: Bitmap? = if (kind == Kind.IMAGE) {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, o)
        var s = 1
        while (o.outWidth / (s * 2) >= px && o.outHeight / (s * 2) >= px) s *= 2
        BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = s })?.let { rotateByExif(path, it) }
    } else if (Build.VERSION.SDK_INT >= 29) {
        ThumbnailUtils.createVideoThumbnail(File(path), Size(px, px), null)
    } else {
        @Suppress("DEPRECATION")
        ThumbnailUtils.createVideoThumbnail(path, MediaStore.Video.Thumbnails.MINI_KIND)
    }
    bmp?.asImageBitmap()
}.getOrNull()

internal fun rotateByExif(path: String, b: Bitmap): Bitmap {
    val deg = runCatching {
        when (ExifInterface(path).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90f
            ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            ExifInterface.ORIENTATION_ROTATE_270 -> 270f
            else -> 0f
        }
    }.getOrDefault(0f)
    if (deg == 0f) return b
    return Bitmap.createBitmap(b, 0, 0, b.width, b.height, Matrix().apply { postRotate(deg) }, true)
}
