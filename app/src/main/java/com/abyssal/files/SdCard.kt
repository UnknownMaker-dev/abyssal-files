package com.abyssal.files

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.storage.StorageManager
import android.provider.DocumentsContract
import java.io.File
import java.io.OutputStream

/**
 * Changing files on SD cards. Where plain file access can write to the card (most phones on Android 11+ with
 * all-files access) nothing changes; elsewhere (Android 7–10, some ROMs) Android only allows it through the Storage
 * Access Framework, after the user grants a folder once: the whole card on Android 7–10, or (Android 11+ refuses the
 * card's top folder) the folder being changed. Reading always works with plain files.
 */
class SdCard(private val ctx: Context) {
    private val cr get() = ctx.contentResolver

    /** "/storage/1234-5678" for files on a removable volume, null for internal storage. */
    fun rootOf(f: File): File? {
        val p = f.absolutePath
        if (!p.startsWith("/storage/")) return null
        val seg = p.removePrefix("/storage/").substringBefore('/')
        if (seg.isEmpty() || seg == "emulated" || seg == "self") return null
        return File("/storage/$seg")
    }

    /** True when [f]'s card can't be written with plain files. */
    fun needsSaf(f: File): Boolean {
        val root = rootOf(f) ?: return false
        if (forceSaf) return true
        return !(direct[root.path] ?: probe(root).also { direct[root.path] = it })
    }

    private fun probe(root: File): Boolean = runCatching {
        val t = File(root, ".abyssal-probe-${System.nanoTime()}")
        t.createNewFile().also { if (it) t.delete() }
    }.getOrDefault(false)

    /** "UUID:path/inside/card", the Storage Access Framework id of [f]. */
    private fun docId(f: File): String? {
        val root = rootOf(f) ?: return null
        return "${root.name}:" + f.absolutePath.removePrefix(root.path).trim('/')
    }

    /** The granted folder (tree) that contains [f], the closest one if several do. */
    fun treeFor(f: File): Uri? {
        val id = docId(f) ?: return null
        return cr.persistedUriPermissions.filter { it.isWritePermission }.mapNotNull { p ->
            val t = runCatching { DocumentsContract.getTreeDocumentId(p.uri) }.getOrNull() ?: return@mapNotNull null
            val covers = id == t || id.startsWith(if (t.endsWith(":")) t else "$t/")
            if (covers) p.uri to t.length else null
        }.maxByOrNull { it.second }?.first
    }

    /** Whether [f] can be changed right now (directly, or inside a folder the user already allowed). */
    fun canWrite(f: File): Boolean = !needsSaf(f) || treeFor(f) != null

    /** Android 11+ won't grant a card's top folder, only folders inside it. */
    val wholeCard: Boolean get() = Build.VERSION.SDK_INT < 30

    /** Android's "allow access" screen, opened at the card (Android 7–10) or at [folder] (Android 11+). */
    fun accessIntent(folder: File): Intent {
        val root = rootOf(folder) ?: folder
        val vol = ctx.getSystemService(StorageManager::class.java).getStorageVolume(root)
        val i = when {
            @Suppress("DEPRECATION")
            vol != null && Build.VERSION.SDK_INT < 29 -> return vol.createAccessIntent(null) ?: Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
            vol != null && Build.VERSION.SDK_INT >= 29 -> vol.createOpenDocumentTreeIntent()
            else -> Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
        }
        val start = if (wholeCard) root else folder
        if (Build.VERSION.SDK_INT >= 26) docId(start)?.let { id ->
            i.putExtra(DocumentsContract.EXTRA_INITIAL_URI, DocumentsContract.buildDocumentUri(AUTHORITY, id))
        }
        return i
    }

    /** Keeps the permission for [uri]; true if it's on [folder]'s card and covers [folder]. */
    fun accept(uri: Uri, folder: File): Boolean {
        val root = rootOf(folder) ?: return false
        val id = runCatching { DocumentsContract.getTreeDocumentId(uri) }.getOrNull() ?: return false
        if (!id.startsWith("${root.name}:")) return false
        cr.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        return treeFor(folder) != null
    }

    private fun doc(f: File): Uri {
        val tree = treeFor(f) ?: error("No access to this folder of the SD card")
        return DocumentsContract.buildDocumentUriUsingTree(tree, docId(f)!!)
    }

    fun rename(f: File, name: String) {
        DocumentsContract.renameDocument(cr, doc(f), name) ?: error("Rename failed")
    }

    fun delete(f: File) {
        if (!DocumentsContract.deleteDocument(cr, doc(f))) error("Could not delete ${f.name}")
    }

    fun mkdir(parent: File, name: String): File {
        DocumentsContract.createDocument(cr, doc(parent), DocumentsContract.Document.MIME_TYPE_DIR, name)
            ?: error("Could not create folder")
        return File(parent, name)
    }

    /** New file [name] in [parent]; octet-stream keeps the name exactly as given. */
    fun create(parent: File, name: String): OutputStream {
        val u = DocumentsContract.createDocument(cr, doc(parent), "application/octet-stream", name)
            ?: error("Could not create file")
        return cr.openOutputStream(u) ?: error("Could not write file")
    }

    /** Overwrites an existing file. */
    fun write(f: File): OutputStream =
        runCatching { cr.openOutputStream(doc(f), "wt") }.getOrNull() ?: cr.openOutputStream(doc(f), "w") ?: error("Could not write file")

    /** Moves inside the same card when the provider can, otherwise false. */
    fun move(src: File, dstDir: File): Boolean = runCatching {
        DocumentsContract.moveDocument(cr, doc(src), doc(src.parentFile!!), doc(dstDir)) != null
    }.getOrDefault(false)

    /**
     * Copies a local (readable) file or folder into [dstDir] on the card as [name]. Folders that already exist are
     * merged; files that exist get " (1)".
     */
    fun copyInto(src: File, dstDir: File, name: String = src.name) {
        if (src.isDirectory) {
            val existing = File(dstDir, name)
            val d = if (existing.isDirectory) existing else mkdir(dstDir, name)
            src.listFiles()?.forEach { copyInto(it, d) }
        } else {
            val n = if (File(dstDir, name).exists()) FileOps.unique(dstDir, name, false).name else name
            create(dstDir, n).use { o -> src.inputStream().use { it.copyTo(o) } }
        }
    }

    companion object {
        private const val AUTHORITY = "com.android.externalstorage.documents"
        private val direct = HashMap<String, Boolean>()

        /** Testing only: behave like a phone whose card needs the Storage Access Framework. */
        var forceSaf = false
    }
}
