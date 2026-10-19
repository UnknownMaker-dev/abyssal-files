package com.abyssal.files

import android.app.Application
import android.os.Environment
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

sealed interface Screen {
    data object Home : Screen
    data object Dir : Screen
    data object History : Screen
    data object Favorites : Screen
    /** [albums]: photos/videos are shown as folder albums; [album] is the one opened, if any. */
    data class Category(val title: String, val exts: Set<String>, val albums: Boolean = false, val album: String? = null) : Screen
}

data class Clip(val files: List<String>, val cut: Boolean)
data class EditRequest(val path: String, val edit: Boolean, val readOnly: Boolean = false)

/** Where "Extract" puts the files: next to the archive, in a folder named after it, or a chosen folder. */
enum class ExtractMode { HERE, NAMED, TO }

class FmViewModel(app: Application) : AndroidViewModel(app) {
    private val prefs = Prefs(app)
    val player = AudioPlayer(viewModelScope)

    var volumes by mutableStateOf<List<Volume>>(emptyList()); private set
    var screen by mutableStateOf<Screen>(Screen.Home); private set
    var dir by mutableStateOf(Environment.getExternalStorageDirectory()); private set
    private var all by mutableStateOf<List<FileItem>>(emptyList())
    var selected by mutableStateOf<Set<String>>(emptySet()); private set
    var clip by mutableStateOf<Clip?>(null); private set
    var query by mutableStateOf<String?>(null); private set
    private var found by mutableStateOf<List<FileItem>>(emptyList())
    var searching by mutableStateOf(false); private set
    var showHidden by mutableStateOf(prefs.showHidden); private set
    var sort by mutableStateOf(prefs.sort); private set
    var sortReversed by mutableStateOf(prefs.sortReversed); private set
    var busy by mutableStateOf(false); private set
    var message by mutableStateOf<String?>(null)
    var favorites by mutableStateOf(prefs.favorites); private set
    var history by mutableStateOf(prefs.history); private set
    var editing by mutableStateOf<EditRequest?>(null); private set
    var settingsOpen by mutableStateOf(false)

    /** Full-screen image/video/archive viewers, topmost last. */
    var viewers by mutableStateOf<List<Viewer>>(emptyList()); private set

    private var job: Job? = null
    private var searchJob: Job? = null
    private var iconsLoaded = false

    /** In a folder, searching looks through every subfolder too. */
    val deepSearch: Boolean get() = screen is Screen.Dir && !query.isNullOrBlank()

    val visible: List<FileItem>
        get() {
            val q = query
            val album = (screen as? Screen.Category)?.album
            val base = if (album == null) all else all.filter { it.path.substringBeforeLast('/') == album }
            return when {
                q.isNullOrBlank() -> base
                screen is Screen.Dir -> found
                else -> base.filter { it.name.contains(q, ignoreCase = true) }
            }
        }
    val selectedItems: List<FileItem> get() = visible.filter { it.path in selected }
    val canGoBack: Boolean
        get() = viewers.isNotEmpty() || settingsOpen || screen != Screen.Home || selected.isNotEmpty() || query != null

    init {
        goHome()
        // Files opened from archives are temporary
        viewModelScope.launch(Dispatchers.IO) { File(app.cacheDir, "zipview").deleteRecursively() }
    }

    fun openViewer(v: Viewer) { viewers = viewers + v }
    fun closeViewer() { viewers = viewers.dropLast(1) }

    fun onResume() {
        refreshVolumes()
        if (screen is Screen.Dir || screen is Screen.Category) refresh()
        if (!iconsLoaded) reloadIcons()
    }

    // ---- volumes ----
    private fun refreshVolumes() {
        viewModelScope.launch {
            volumes = withContext(Dispatchers.IO) { Storage.volumes(getApplication()) }
        }
    }

    /** The storage volume a path belongs to. */
    fun volumeOf(f: File): Volume? =
        volumes.filter { f.path == it.root.path || f.path.startsWith(it.root.path + "/") }
            .maxByOrNull { it.root.path.length }

    /** Folder name, or the volume label for a storage root. */
    fun displayName(p: String): String = volumes.firstOrNull { it.root.path == p }?.label ?: File(p).name

    /** "Internal storage/Download/x" instead of "/storage/emulated/0/Download/x". */
    fun displayPath(p: String): String {
        val v = volumeOf(File(p)) ?: return p
        return v.label + p.removePrefix(v.root.path)
    }

    // ---- navigation ----
    fun goHome() {
        job?.cancel()
        screen = Screen.Home; selected = emptySet(); search(null); all = emptyList(); busy = false
        refreshVolumes()
    }

    fun openDir(f: File, record: Boolean = true) {
        settingsOpen = false
        screen = Screen.Dir; dir = f; selected = emptySet(); search(null)
        if (record) {
            history = (listOf(f.path) + history.filterNot { it == f.path }).take(50)
            prefs.history = history
        }
        refresh()
    }

    fun openCategory(title: String, exts: Set<String>, albums: Boolean = false) {
        screen = Screen.Category(title, exts, albums); selected = emptySet(); search(null)
        refresh()
    }

    /** Opens one album of a photos/videos category (no rescan needed). */
    fun openAlbum(folder: String) {
        val s = screen as? Screen.Category ?: return
        screen = s.copy(album = folder); selected = emptySet(); search(null)
    }

    fun openHistory() {
        settingsOpen = false; screen = Screen.History; selected = emptySet(); search(null)
    }

    fun openFavorites() {
        settingsOpen = false; screen = Screen.Favorites; selected = emptySet(); search(null)
    }

    fun openHome() {
        settingsOpen = false
        goHome()
    }

    fun back() {
        val top = viewers.lastOrNull()
        when {
            top is Viewer.Zip && top.dir.isNotEmpty() ->
                top.dir = top.dir.trimEnd('/').substringBeforeLast('/', "").let { if (it.isEmpty()) "" else "$it/" }
            top != null -> closeViewer()
            settingsOpen -> settingsOpen = false
            selected.isNotEmpty() -> selected = emptySet()
            query != null -> search(null)
            (screen as? Screen.Category)?.album != null -> {
                screen = (screen as Screen.Category).copy(album = null); selected = emptySet()
            }
            screen is Screen.Category || screen is Screen.History || screen is Screen.Favorites -> goHome()
            screen is Screen.Dir -> {
                val root = volumeOf(dir)?.root
                val p = dir.parentFile
                if (root == null || dir.path == root.path || p == null) goHome() else openDir(p, record = false)
            }
        }
    }

    fun refresh() {
        val s = screen; val d = dir; val hidden = showHidden; val so = sort; val rev = sortReversed; val vols = volumes
        job?.cancel()
        job = viewModelScope.launch {
            busy = true
            try {
                all = withContext(Dispatchers.IO) {
                    when (s) {
                        Screen.Dir -> FileOps.list(d, hidden, so, rev)
                        is Screen.Category -> FileOps.sort(vols.flatMap { FileOps.scan(it.root, s.exts) }, so, rev)
                        else -> emptyList()
                    }
                }
            } finally { busy = false }
        }
    }

    /** Picking the current sort again flips its direction. */
    fun changeSort(s: Sort) {
        if (s == sort) sortReversed = !sortReversed else { sort = s; sortReversed = false }
        prefs.sort = sort; prefs.sortReversed = sortReversed
        refresh(); rerunSearch()
    }

    fun toggleReversed() { changeSort(sort) }

    fun toggleHidden() { showHidden = !showHidden; prefs.showHidden = showHidden; refresh(); rerunSearch() }

    // ---- search ----
    fun search(q: String?) {
        query = q
        searchJob?.cancel()
        searching = false
        found = emptyList()
        if (screen !is Screen.Dir || q.isNullOrBlank()) return
        val root = dir; val hidden = showHidden; val so = sort; val rev = sortReversed
        searchJob = viewModelScope.launch {
            delay(250) // wait for the user to stop typing
            searching = true
            try {
                val acc = ArrayList<FileItem>()
                withContext(Dispatchers.IO) {
                    var last = System.currentTimeMillis()
                    for (f in FileOps.search(root, q, hidden)) {
                        ensureActive()
                        acc += FileItem.of(f)
                        if (acc.size >= 3000) break
                        if (System.currentTimeMillis() - last > 300) {
                            val snapshot = FileOps.sort(acc, so, rev)
                            withContext(Dispatchers.Main) { found = snapshot }
                            last = System.currentTimeMillis()
                        }
                    }
                }
                found = FileOps.sort(acc, so, rev)
            } finally { searching = false }
        }
    }

    private fun rerunSearch() { if (deepSearch) search(query) }

    /** Location of a search result relative to the folder being searched. */
    fun relativeParent(item: FileItem): String {
        val parent = item.path.substringBeforeLast('/')
        val rel = parent.removePrefix(dir.path).trimStart('/')
        return if (rel.isEmpty()) "here" else rel
    }

    // ---- favorites & history ----
    fun isFavorite(p: String) = p in favorites

    fun toggleFavorite(p: String) {
        favorites = if (p in favorites) favorites - p else favorites + p
        prefs.favorites = favorites
        message = if (p in favorites) "Added to favorites" else "Removed from favorites"
    }

    fun clearHistory() { history = emptyList(); prefs.history = history }

    // ---- editor, settings, icons ----
    fun openEditor(path: String, edit: Boolean = false, readOnly: Boolean = false) { editing = EditRequest(path, edit, readOnly) }
    fun closeEditor() { editing = null }

    fun setAccent(c: Color, persist: Boolean = true) {
        Pal.accent = c
        if (persist) prefs.accent = c.toArgb()
    }

    fun setColorfulIcons(on: Boolean) {
        Pal.colorfulIcons = on
        prefs.colorfulIcons = on
    }

    fun reloadIcons(notify: Boolean = false) {
        iconsLoaded = true
        viewModelScope.launch {
            val (pngs, grids) = withContext(Dispatchers.IO) {
                val pngs = runCatching { CustomIcons.load() }.getOrDefault(emptyMap())
                val grids = runCatching { PixelIcons.loadOverrides(CustomIcons.dir) }.getOrDefault(0)
                pngs to grids
            }
            CustomIcons.map = pngs
            if (notify) message = if (pngs.isEmpty() && grids == 0) "No custom icons found"
            else "Loaded ${pngs.size + grids} custom icon(s)"
        }
    }

    /** Copies the built-in .txt icons to the custom folder so they can be edited on the phone. */
    fun exportIcons() {
        runOp("Icons copied to AbyssalFiles/icons", after = { reloadIcons() }) {
            PixelIcons.exportDefaults(getApplication<Application>().assets, CustomIcons.dir)
        }
    }

    fun openIconsFolder() {
        val d = CustomIcons.dir
        d.mkdirs()
        openDir(d)
    }

    // ---- selection ----
    fun toggle(i: FileItem) { selected = if (i.path in selected) selected - i.path else selected + i.path }
    fun clearSelection() { selected = emptySet() }
    fun selectAll() { selected = visible.map { it.path }.toSet() }

    // ---- clipboard ----
    fun copySel() { clip = Clip(selected.toList(), cut = false); selected = emptySet(); message = "Copied — open a folder and paste" }
    fun cutSel() { clip = Clip(selected.toList(), cut = true); selected = emptySet(); message = "Cut — open a folder and paste" }
    fun cancelClip() { clip = null }

    fun paste() {
        val c = clip ?: return
        val target = dir
        runOp("Pasted ${c.files.size} item(s)") {
            c.files.map(::File).forEach { f ->
                val src = f.canonicalPath
                val dst = target.canonicalPath
                if (f.isDirectory && (dst == src || dst.startsWith(src + File.separator)))
                    error("Can't paste a folder into itself")
                if (c.cut) FileOps.move(f, target) else FileOps.copy(f, target)
            }
        }
        clip = null
    }

    // ---- file operations ----
    fun deleteSelected() {
        val files = selected.map(::File)
        runOp("Deleted ${files.size} item(s)", after = {
            favorites = favorites.filterNot { p -> files.any { p == it.path || p.startsWith(it.path + "/") } }
            prefs.favorites = favorites
        }) {
            files.forEach { if (!it.deleteRecursively()) error("Could not delete ${it.name}") }
        }
    }

    fun rename(item: FileItem, newName: String) = runOp("Renamed") {
        validName(newName)
        val f = File(item.path)
        val t = File(f.parentFile, newName.trim())
        if (t.exists()) error("Name already exists")
        if (!f.renameTo(t)) error("Rename failed")
    }

    fun newFolder(name: String) {
        val parent = dir
        runOp("Folder created") {
            validName(name)
            if (!File(parent, name.trim()).mkdir()) error("Could not create folder")
        }
    }

    fun newFile(name: String) {
        val parent = dir
        var created: File? = null
        runOp("File created", after = {
            created?.let { if (FileOps.editable(FileItem.of(it))) openEditor(it.path, edit = true) }
        }) {
            validName(name)
            created = FileOps.createFile(parent, name.trim())
        }
    }

    fun zipSelected(name: String) {
        val files = selected.map(::File)
        val parent = files.firstOrNull()?.parentFile ?: return
        runOp("Archive created") {
            validName(name)
            val n = name.trim().let { if (it.endsWith(".zip", true)) it else "$it.zip" }
            FileOps.zip(files, FileOps.unique(parent, n, false))
        }
    }

    /** Extracts a whole archive, or just [entry] inside it. */
    fun extract(zipPath: String, mode: ExtractMode, target: File? = null, entry: String? = null) {
        val f = File(zipPath)
        val parent = f.parentFile ?: return
        var dest: File? = null
        runOp("Extracted", after = { dest?.let { message = "Extracted to ${displayPath(it.path)}" } }) {
            val d = when (mode) {
                ExtractMode.HERE -> parent
                ExtractMode.NAMED -> FileOps.unique(parent, f.nameWithoutExtension, true)
                ExtractMode.TO -> target ?: parent
            }
            dest = d
            FileOps.unzip(f, d, entry)
        }
    }

    fun deletePaths(paths: List<String>, after: () -> Unit = {}) {
        runOp("Deleted ${paths.size} item(s)", after = after) {
            paths.map(::File).forEach { if (!it.deleteRecursively()) error("Could not delete ${it.name}") }
        }
    }

    private fun validName(n: String) = require(n.isNotBlank() && '/' !in n && n.trim() !in setOf(".", "..")) { "Invalid name" }

    private fun runOp(okMsg: String, after: (() -> Unit)? = null, block: () -> Unit) {
        viewModelScope.launch {
            busy = true
            val err = withContext(Dispatchers.IO) { runCatching(block).exceptionOrNull() }
            busy = false
            message = err?.let { "Failed: ${it.message}" } ?: okMsg
            selected = emptySet()
            refresh()
            rerunSearch()
            if (err == null) after?.invoke()
        }
    }

    override fun onCleared() {
        player.release()
    }
}
