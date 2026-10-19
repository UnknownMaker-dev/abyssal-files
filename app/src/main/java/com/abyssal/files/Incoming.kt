package com.abyssal.files

import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import java.io.File

/** Another app asked for files (ACTION_GET_CONTENT): tapping a file hands it back instead of opening it. */
class PickRequest(val mimes: List<String>, val multiple: Boolean, val caller: String?) {
    fun accepts(item: FileItem): Boolean =
        !item.isDir && (mimes.isEmpty() || mimes.any { Incoming.mimeMatches(FileOps.mime(File(item.path)), it) })

    /** "a file", "an image", "files"… for the banner. */
    val noun: String
        get() {
            val kinds = mimes.map { it.substringBefore('/') }.toSet()
            val k = kinds.singleOrNull()?.takeIf { it != "*" && mimes.all { m -> m.endsWith("/*") } }
            return when (k) {
                "image" -> if (multiple) "images" else "an image"
                "video" -> if (multiple) "videos" else "a video"
                "audio" -> if (multiple) "audio files" else "an audio file"
                "text" -> if (multiple) "text files" else "a text file"
                else -> if (multiple) "files" else "a file"
            }
        }
}

/** Files that other apps send us with "Open with" / "Edit with". */
object Incoming {
    fun mimeMatches(mime: String, pattern: String): Boolean {
        val p = pattern.lowercase().trim()
        val m = mime.lowercase()
        return p == "*/*" || p == "*" || p == m || (p.endsWith("/*") && m.startsWith(p.dropLast(1)))
    }

    /** File name for a uri, with an extension guessed from [mime] when the name has none. */
    fun nameFor(ctx: Context, uri: Uri, mime: String?): String {
        val n = runCatching {
            ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        }.getOrNull() ?: uri.lastPathSegment?.substringAfterLast('/')
        val clean = n.orEmpty().replace('/', '_').replace('\u0000', '_').trim().trimStart('.').take(120).ifEmpty { "file" }
        if ('.' in clean) return clean
        val ext = (mime ?: runCatching { ctx.contentResolver.getType(uri) }.getOrNull())
            ?.let { MimeTypeMap.getSingleton().getExtensionFromMimeType(it) }
        return if (ext != null) "$clean.$ext" else clean
    }

    /** The file behind [uri] when we can read it in place (needs all-files access), so edits save directly. */
    fun realFile(ctx: Context, uri: Uri): File? {
        val f = when (uri.scheme) {
            "file" -> uri.path?.let(::File)
            "content" -> runCatching { fromProvider(ctx, uri) }.getOrNull()
            else -> null
        } ?: return null
        val c = runCatching { f.canonicalFile }.getOrNull() ?: return null
        // Only shared storage: never let another app point us at our own private files
        val ok = c.path.startsWith("/storage/") || c.path.startsWith("/sdcard/") || c.path.startsWith("/mnt/")
        return c.takeIf { ok && it.isFile && it.canRead() }
    }

    private fun fromProvider(ctx: Context, uri: Uri): File? {
        val auth = uri.authority ?: return null
        val segs = uri.pathSegments
        if (auth == "${ctx.packageName}.fileprovider" && segs.size >= 2) {
            val rest = segs.drop(1).joinToString("/")
            return when (segs[0]) {
                "storage" -> File(Environment.getExternalStorageDirectory(), rest)
                "root" -> File("/", rest)
                else -> null
            }
        }
        if (DocumentsContract.isDocumentUri(ctx, uri)) {
            val id = DocumentsContract.getDocumentId(uri)
            when (auth) {
                "com.android.externalstorage.documents" -> {
                    val vol = id.substringBefore(':')
                    val rel = id.substringAfter(':', "")
                    val root = if (vol.equals("primary", true)) Environment.getExternalStorageDirectory() else File("/storage", vol)
                    return File(root, rel)
                }
                "com.android.providers.downloads.documents" ->
                    if (id.startsWith("raw:")) return File(id.removePrefix("raw:"))
            }
        }
        // MediaStore and many file managers still report the path
        return ctx.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.DATA), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0)?.let(::File) else null
        }
    }

    /** A private copy of [uri] in the cache, named [name] so viewers and the editor see the real name. */
    fun copy(ctx: Context, uri: Uri, name: String): File {
        val dir = File(ctx.cacheDir, "incoming/${System.nanoTime()}").apply { mkdirs() }
        val out = File(dir, name)
        val input = ctx.contentResolver.openInputStream(uri) ?: error("Can't read the file")
        input.use { i -> out.outputStream().use { i.copyTo(it) } }
        return out
    }

    /** Saves an edited copy back to the app that sent it. */
    fun writeBack(ctx: Context, file: File, uri: Uri) {
        val out = runCatching { ctx.contentResolver.openOutputStream(uri, "wt") }.getOrNull()
            ?: ctx.contentResolver.openOutputStream(uri, "w")
            ?: error("The other app doesn't accept changes")
        out.use { o -> file.inputStream().use { it.copyTo(o) } }
    }

    /** Copies older than a day are leftovers from files opened by other apps. */
    fun cleanCache(ctx: Context) {
        val old = System.currentTimeMillis() - 24 * 3600_000L
        for (name in listOf("incoming", "zipview")) {
            File(ctx.cacheDir, name).listFiles()?.filter { it.lastModified() < old }?.forEach { it.deleteRecursively() }
        }
    }
}
