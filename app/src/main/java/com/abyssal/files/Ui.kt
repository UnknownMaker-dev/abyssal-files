@file:OptIn(ExperimentalFoundationApi::class)

package com.abyssal.files

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.ViewList
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import java.io.File

private data class Cat(val title: String, val exts: Set<String>, val kind: Kind)

private val CATS = listOf(
    Cat("Images", FileOps.IMAGES, Kind.IMAGE),
    Cat("Videos", FileOps.VIDEOS, Kind.VIDEO),
    Cat("Audio", FileOps.AUDIO, Kind.AUDIO),
    Cat("Documents", FileOps.DOCS, Kind.DOCUMENT),
    Cat("Archives", FileOps.ARCHIVES, Kind.ARCHIVE),
    Cat("APKs", FileOps.APKS, Kind.APK),
)

// ───────────────────────── root ─────────────────────────

@Composable
fun FileManagerApp(vm: FmViewModel) {
    val ctx = LocalContext.current
    val snack = remember { SnackbarHostState() }
    var dlg by remember { mutableStateOf<Dlg?>(null) }

    LaunchedEffect(vm.message) { vm.message?.let { snack.showSnackbar(it); vm.message = null } }
    LaunchedEffect(vm.player.error) { vm.player.error?.let { vm.message = it; vm.player.error = null } }
    BackHandler(enabled = vm.canGoBack) { vm.back() }

    val edit = vm.editing
    Box(Modifier.fillMaxSize().background(Bg)) {
        when {
            edit != null -> EditorScreen(
                req = edit,
                onClose = { vm.closeEditor() },
                onSaved = { vm.refresh(); vm.saveBack(edit) },
                onOpenWith = { openWith(ctx, it) { m -> vm.message = m } },
                onMsg = { vm.message = it },
                onNeedSd = { f, retry -> vm.withSd(f) { retry() } },
            )
            vm.viewers.isNotEmpty() -> when (val v = vm.viewers.last()) {
                is Viewer.Images -> ImageViewer(v, vm) { dlg = it }
                is Viewer.Videos -> VideoPlayer(v, vm)
                is Viewer.Zip -> ZipViewer(v, vm) { dlg = it }
            }
            vm.external -> ExternalScreen(vm)
            vm.settingsOpen -> SettingsScreen(vm)
            else -> Column(Modifier.fillMaxSize().systemBarsPadding()) {
                vm.pick?.let { PickBanner(vm, it) }
                Box(Modifier.weight(1f)) {
                    when (vm.screen) {
                        Screen.Home -> HomeScreen(vm)
                        Screen.History -> HistoryScreen(vm)
                        Screen.Favorites -> FavoritesScreen(vm)
                        else -> BrowserScreen(vm, ctx) { dlg = it }
                    }
                }
                PlayerBar(vm.player)
                if (vm.selected.isEmpty()) NavBar(vm)
            }
        }
        SnackbarHost(snack, Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 64.dp))
    }

    if (vm.updateDialog) UpdateDialog(vm)

    // SD card: explain, then Android's "allow access" screen
    val sdLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        vm.onSdAccess(r.data?.data.takeIf { r.resultCode == android.app.Activity.RESULT_OK })
    }
    vm.sdRequest?.let { root ->
        AlertDialog(
            onDismissRequest = { vm.cancelSdRequest() },
            containerColor = Panel2,
            icon = { PixIcon("sdcard", 40.dp) },
            title = { Text("Allow SD card access", fontSize = 17.sp) },
            text = {
                Text(
                    "On this phone, Android only lets apps change files on the memory card after you allow it once.\n\n" +
                        if (vm.sd.wholeCard) "On the next screen, stay on the card's top folder and tap \"Use this folder\" → \"Allow\"."
                        else "On the next screen, tap \"Use this folder\" → \"Allow\" on the folder that opens " +
                            "(${vm.displayName(root.path)}), or on a folder that contains it. Android doesn't allow the whole card at once.",
                    color = Dim, fontSize = 14.sp,
                )
            },
            confirmButton = {
                TextButton({
                    runCatching { sdLauncher.launch(vm.sd.accessIntent(root)) }.onFailure { vm.onSdAccess(null) }
                }) { Text("Continue") }
            },
            dismissButton = { TextButton({ vm.cancelSdRequest() }) { Text("Cancel", color = Dim) } },
        )
    }

    val close = { dlg = null }
    when (val d = dlg) {
        is Dlg.Rename -> RenameDialog(d.item.name, d.item.isDir, { vm.rename(d.item, it); close() }, close)
        Dlg.NewFolder -> NameDialog("New folder", "", { vm.newFolder(it); close() }, close)
        Dlg.NewFile -> NewFileDialog({ vm.newFile(it); close() }, close)
        Dlg.ZipName -> NameDialog("Archive name", vm.defaultArchiveName(), { vm.zipSelected(it); close() }, close)
        Dlg.CompileName -> NameDialog("Compiled pack name", vm.defaultArchiveName(), { vm.compileBrarchive(vm.selected.toList(), it); close() }, close)
        Dlg.Delete -> DeleteDialog(vm.selected.size, { vm.deleteSelected(); close() }, close)
        is Dlg.Props -> PropertiesDialog(d.paths, vm::displayPath, close)
        is Dlg.ExtractTo -> FolderPickerDialog(vm, File(d.zip).parentFile, "Extract to", "Extract here", { dir ->
            vm.extract(d.zip, ExtractMode.TO, dir, d.entry); close()
        }, close)
        null -> {}
    }
}

@Composable
fun PermissionScreen(onGrant: () -> Unit) {
    Column(
        Modifier.fillMaxSize().background(Bg).systemBarsPadding().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Logo(96.dp)
        Spacer(Modifier.height(16.dp))
        Text("Abyssal Files", fontSize = 22.sp, fontWeight = FontWeight.SemiBold, color = Ink)
        Spacer(Modifier.height(8.dp))
        Text(
            "Allow access to all files so you can browse and manage your storage.",
            color = Dim, fontSize = 14.sp,
        )
        Spacer(Modifier.height(20.dp))
        Button(onClick = onGrant) { Text("Grant access") }
    }
}

// ───────────────────────── home ─────────────────────────

@Composable
fun HomeScreen(vm: FmViewModel) {
    val internal = vm.volumes.firstOrNull()?.root
    val quick = remember(internal) {
        internal?.let { r ->
            listOf("Download", "DCIM", "Pictures", "Documents", "Music", "Movies").map { File(r, it) }.filter { it.isDirectory }
        }.orEmpty()
    }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Logo(30.dp)
                Spacer(Modifier.width(10.dp))
                Text("Abyssal Files", fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = Ink, modifier = Modifier.weight(1f))
                IconButton({ vm.settingsOpen = true }) { Icon(Icons.Outlined.Settings, "Settings", tint = Ink) }
            }
        }

        items(vm.volumes, key = { it.root.path }) { v -> StorageCard(v) { vm.openDir(v.root) } }

        if (vm.favorites.isNotEmpty()) {
            item { SectionLabel("Favorites") }
            items(vm.favorites, key = { "fav:$it" }) { p ->
                FolderCard(vm.displayName(p), vm.displayPath(p), onClick = { vm.openDir(File(p)) }, path = p) {
                    IconButton({ vm.toggleFavorite(p) }) { PixIcon("star", 24.dp, contentDescription = "Remove favorite") }
                }
            }
        }

        item { SectionLabel("Categories") }
        items(CATS.chunked(3)) { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { c ->
                    Row(
                        Modifier.weight(1f).clip(RoundedCornerShape(10.dp)).background(Panel)
                            .clickable { vm.openCategory(c.title, c.exts, albums = c.kind == Kind.IMAGE || c.kind == Kind.VIDEO) }
                            .padding(horizontal = 8.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        KindIcon(c.kind, 24.dp)
                        Spacer(Modifier.width(6.dp))
                        Text(c.title, fontSize = 13.sp, color = Ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }

        if (vm.history.isNotEmpty()) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SectionLabel("Recent folders", Modifier.weight(1f))
                    TextButton({ vm.openHistory() }, contentPadding = PaddingValues(horizontal = 8.dp)) {
                        Text("See all", fontSize = 12.sp)
                    }
                }
            }
            items(vm.history.take(4), key = { "rec:$it" }) { p ->
                FolderCard(vm.displayName(p), vm.displayPath(p), onClick = { vm.openDir(File(p)) }, path = p)
            }
        }

        if (quick.isNotEmpty()) item { SectionLabel("Quick access") }
        items(quick, key = { "q:${it.path}" }) { f -> FolderCard(f.name, null, onClick = { vm.openDir(f) }) }
    }
}

@Composable
private fun SectionLabel(t: String, modifier: Modifier = Modifier) =
    Text(t, fontSize = 12.sp, color = Dim, modifier = modifier.padding(top = 4.dp))

@Composable
private fun StorageCard(v: Volume, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Panel)
            .clickable(onClick = onClick).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PixIcon(if (v.removable) "sdcard" else "storage", 34.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(v.label, fontSize = 14.sp, color = Ink, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                Text("${FileOps.fmtSize(v.used)} / ${FileOps.fmtSize(v.total)}", fontSize = 12.sp, color = Dim)
            }
            Spacer(Modifier.height(8.dp))
            LinearProgressIndicator(
                progress = { (v.used.toFloat() / v.total.coerceAtLeast(1)).coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth().height(4.dp).clip(CircleShape),
                color = Accent, trackColor = Panel2,
                drawStopIndicator = {},
            )
            Spacer(Modifier.height(4.dp))
            Text("${FileOps.fmtSize(v.total - v.used)} free", fontSize = 11.sp, color = Dim)
        }
    }
}

@Composable
private fun FolderCard(
    title: String, subtitle: String?, onClick: () -> Unit,
    icon: String = IconMap.forFolder(title), path: String? = null, trailing: @Composable () -> Unit = {},
) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Panel)
            .clickable(onClick = onClick).padding(start = 10.dp, end = 4.dp).heightIn(min = 48.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (path != null) FolderIcon(path, title, 30.dp) else PixIcon(icon, 30.dp)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f).padding(vertical = 6.dp)) {
            Text(title.ifEmpty { "/" }, fontSize = 14.sp, color = Ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (subtitle != null) Text(subtitle, fontSize = 11.sp, color = Dim, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        trailing()
    }
}

// ───────────────────────── history ─────────────────────────

@Composable
fun HistoryScreen(vm: FmViewModel) {
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().height(48.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton({ vm.back() }, Modifier.size(44.dp)) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Ink) }
            PixIcon("history", 22.dp)
            Spacer(Modifier.width(8.dp))
            Text("History", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = Ink, modifier = Modifier.weight(1f))
            if (vm.history.isNotEmpty()) TextButton({ vm.clearHistory() }) { Text("Clear", color = Danger) }
        }
        if (vm.history.isEmpty()) {
            Box(Modifier.fillMaxSize(), Alignment.Center) { Text("No folders visited yet", color = Dim, fontSize = 14.sp) }
        } else {
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(vm.history, key = { it }) { p ->
                    FolderCard(vm.displayName(p), vm.displayPath(p), onClick = { vm.openDir(File(p)) }, path = p)
                }
            }
        }
    }
}

// ───────────────────────── favorites ─────────────────────────

@Composable
fun FavoritesScreen(vm: FmViewModel) {
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().height(48.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton({ vm.back() }, Modifier.size(44.dp)) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Ink) }
            PixIcon("star", 22.dp)
            Spacer(Modifier.width(8.dp))
            Text("Favorites", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = Ink)
        }
        if (vm.favorites.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(32.dp), Alignment.Center) {
                Text("No favorites yet — tap ☆ at the top of any folder", color = Dim, fontSize = 14.sp)
            }
        } else {
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(vm.favorites, key = { it }) { p ->
                    FolderCard(vm.displayName(p), vm.displayPath(p), onClick = { vm.openDir(File(p)) }, path = p) {
                        IconButton({ vm.toggleFavorite(p) }) { PixIcon("star", 24.dp, contentDescription = "Remove favorite") }
                    }
                }
            }
        }
    }
}

// ───────────────────────── navigation bar ─────────────────────────

/** Always-visible way back to Home, Favorites and History. */
@Composable
private fun NavBar(vm: FmViewModel) {
    val s = vm.screen
    Row(
        Modifier.fillMaxWidth().background(Panel).height(56.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        NavItem(Icons.Outlined.Home, Icons.Filled.Home, "Home", s is Screen.Home || s is Screen.Dir || s is Screen.Category) { vm.openHome() }
        NavItem("Favorites", s is Screen.Favorites, { vm.openFavorites() }) { on ->
            PixIcon(if (on) "star" else "star-outline", 22.dp, Modifier.alpha(if (on) 1f else 0.75f))
        }
        NavItem(Icons.Outlined.History, Icons.Filled.History, "History", s is Screen.History) { vm.openHistory() }
    }
}

@Composable
private fun RowScope.NavItem(icon: ImageVector, active: ImageVector, label: String, selected: Boolean, onClick: () -> Unit) =
    NavItem(label, selected, onClick) { on -> Icon(if (on) active else icon, label, tint = if (on) Accent else Dim, modifier = Modifier.size(22.dp)) }

@Composable
private fun RowScope.NavItem(label: String, selected: Boolean, onClick: () -> Unit, icon: @Composable (Boolean) -> Unit) {
    Column(
        Modifier.weight(1f).fillMaxHeight().clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            Modifier.clip(CircleShape).background(if (selected) Accent.copy(alpha = 0.22f) else Color.Transparent)
                .padding(horizontal = 16.dp, vertical = 3.dp),
        ) {
            icon(selected)
        }
        Text(label, fontSize = 11.sp, color = if (selected) Accent else Dim)
    }
}

// ───────────────────────── browser ─────────────────────────

@Composable
fun BrowserScreen(vm: FmViewModel, ctx: Context, onDlg: (Dlg) -> Unit) {
    val list = vm.visible
    val sel = vm.selected

    Column(Modifier.fillMaxSize()) {
        if (sel.isEmpty()) BrowserTopBar(vm, onDlg) else SelectionTopBar(vm)

        Box(Modifier.fillMaxWidth().height(2.dp)) {
            if (vm.busy || vm.searching) LinearProgressIndicator(Modifier.fillMaxSize(), color = Accent, trackColor = Color.Transparent)
        }

        val pick = vm.pick
        val onClick: (FileItem) -> Unit = { item ->
            when {
                pick != null && !item.isDir -> when {
                    !pick.accepts(item) -> vm.message = "Choose ${pick.noun}"
                    pick.multiple -> vm.toggle(item)
                    else -> vm.finishPick(listOf(item.path))
                }
                pick != null -> vm.openDir(File(item.path))
                sel.isNotEmpty() -> vm.toggle(item)
                item.isDir -> vm.openDir(File(item.path))
                item.kind == Kind.AUDIO -> {
                    val q = list.filter { it.kind == Kind.AUDIO }
                    vm.player.play(q.map { it.path }, q.indexOf(item))
                }
                item.kind == Kind.IMAGE -> {
                    val q = list.filter { it.kind == Kind.IMAGE }
                    vm.openViewer(Viewer.Images(q.map { it.path }, q.indexOf(item)))
                }
                item.kind == Kind.VIDEO -> {
                    val q = list.filter { it.kind == Kind.VIDEO }
                    vm.openViewer(Viewer.Videos(q.map { it.path }, q.indexOf(item)))
                }
                item.ext in FileOps.ZIP_BROWSE -> vm.openViewer(Viewer.Zip(item.path))
                FileOps.editable(item) -> vm.openEditor(item.path)
                else -> openFile(ctx, File(item.path)) { vm.message = it }
            }
        }
        val onLong: (FileItem) -> Unit = { item ->
            when {
                pick == null -> vm.toggle(item)
                pick.multiple && pick.accepts(item) -> vm.toggle(item)
                else -> onClick(item)
            }
        }
        val deep = vm.deepSearch

        @Composable
        fun ItemRow(item: FileItem, location: String?) = FileRow(
            item = item,
            selected = item.path in sel,
            favorite = item.isDir && vm.isFavorite(item.path),
            location = location,
            enabled = pick == null || item.isDir || pick.accepts(item),
            size = vm.viewSize,
            onClick = { onClick(item) },
            onLong = { onLong(item) },
        )

        @Composable
        fun ItemTile(item: FileItem, location: String?) = FileTile(
            item = item,
            size = vm.viewSize,
            selected = item.path in sel,
            favorite = item.isDir && vm.isFavorite(item.path),
            location = location,
            enabled = pick == null || item.isDir || pick.accepts(item),
            onClick = { onClick(item) },
            onLong = { onLong(item) },
        )
        val grid = vm.viewMode == ViewMode.GRID
        val gridCells = GridCells.Adaptive(vm.viewSize.tile)
        val posKey = vm.scrollKey
        val ready = list.isNotEmpty() && !vm.busy && !vm.searching
        val gridPad = PaddingValues(6.dp)

        val cat = vm.screen as? Screen.Category
        val noun = if (cat?.exts === FileOps.VIDEOS) "video" else "photo"
        Box(Modifier.weight(1f).pinchToResize { vm.resizeView(it) }) {
            if (cat != null && cat.album != null) {
                MediaGrid(list, sel, vm.viewSize.media, rememberGridPosition(vm, posKey, ready), onClick = onClick, onLong = onLong)
            } else if (cat != null && cat.albums && vm.query.isNullOrBlank()) {
                val groups = remember(list, vm.sort, vm.sortReversed) { FileOps.groupByFolder(list, vm.sort, vm.sortReversed) }
                AlbumGrid(vm, groups, noun, vm.viewSize.album, rememberGridPosition(vm, posKey, ready))
            } else if (cat != null && grid) {
                val groups = remember(list, vm.sort, vm.sortReversed) { FileOps.groupByFolder(list, vm.sort, vm.sortReversed) }
                LazyVerticalGrid(gridCells, Modifier.fillMaxSize(), rememberGridPosition(vm, "$posKey:grid", ready), contentPadding = gridPad) {
                    groups.forEach { (folder, items) ->
                        item(key = "h:$folder", span = { GridItemSpan(maxLineSpan) }) {
                            GroupHeader(vm.displayName(folder), vm.displayPath(folder), items.size) { vm.openDir(File(folder)) }
                        }
                        items(items, key = { it.path }) { ItemTile(it, null) }
                    }
                }
            } else if (cat != null) {
                val groups = remember(list, vm.sort, vm.sortReversed) { FileOps.groupByFolder(list, vm.sort, vm.sortReversed) }
                LazyColumn(Modifier.fillMaxSize(), rememberListPosition(vm, posKey, ready)) {
                    groups.forEach { (folder, items) ->
                        stickyHeader(key = "h:$folder") {
                            GroupHeader(vm.displayName(folder), vm.displayPath(folder), items.size) { vm.openDir(File(folder)) }
                        }
                        items(items, key = { it.path }) { ItemRow(it, null) }
                    }
                }
            } else {
                @Composable
                fun SearchInfo() = Text(
                    if (vm.searching) "Searching in all subfolders… ${list.size} found"
                    else "${list.size} result(s) in this folder and its subfolders",
                    color = Dim, fontSize = 11.sp, modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                )
                if (grid) {
                    LazyVerticalGrid(gridCells, Modifier.fillMaxSize(), rememberGridPosition(vm, "$posKey:grid", ready), contentPadding = gridPad) {
                        if (deep) item(key = "search-info", span = { GridItemSpan(maxLineSpan) }) { SearchInfo() }
                        items(list, key = { it.path }) { ItemTile(it, if (deep) vm.relativeParent(it) else null) }
                    }
                } else {
                    LazyColumn(Modifier.fillMaxSize(), rememberListPosition(vm, posKey, ready)) {
                        if (deep) item(key = "search-info") { SearchInfo() }
                        items(list, key = { it.path }) { ItemRow(it, if (deep) vm.relativeParent(it) else null) }
                    }
                }
            }
            if (list.isEmpty() && !vm.busy && !vm.searching) {
                Text(if (vm.query.isNullOrBlank()) "Empty" else "No results", color = Dim, fontSize = 14.sp, modifier = Modifier.align(Alignment.Center))
            }
        }

        when {
            sel.isNotEmpty() && pick != null -> PickBar(vm, pick)
            sel.isNotEmpty() -> ActionBar(vm, ctx, onDlg)
            vm.clip != null && vm.screen is Screen.Dir && pick == null -> PasteBar(vm)
        }
    }
}

@Composable
private fun MenuItem(text: String, checked: Boolean? = null, onClick: () -> Unit) {
    DropdownMenuItem(
        text = { Text((if (checked == true) "✓  " else if (checked == false) "     " else "") + text, fontSize = 14.sp) },
        onClick = onClick,
    )
}

@Composable
private fun BrowserTopBar(vm: FmViewModel, onDlg: (Dlg) -> Unit) {
    var menu by remember { mutableStateOf(false) }
    val isDir = vm.screen is Screen.Dir
    Row(Modifier.fillMaxWidth().height(48.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = { vm.back() }, Modifier.size(44.dp)) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Ink)
        }
        Box(Modifier.weight(1f)) {
            val q = vm.query
            val s = vm.screen
            when {
                q != null -> SearchField(q, if (isDir) "Search here and in subfolders" else "Search in this list") { vm.search(it) }
                s is Screen.Category -> {
                    val count = vm.visible.size
                    val noun = if (s.exts === FileOps.VIDEOS) "video" else "photo"
                    val (title, sub) = when {
                        s.album != null -> vm.displayName(s.album) to plural(count, noun)
                        s.albums -> s.title to plural(remember(vm.visible) { vm.visible.map { it.path.substringBeforeLast('/') }.toSet().size }, "album")
                        else -> s.title to plural(count, "file")
                    }
                    Column {
                        Text(title, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = Ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(sub, fontSize = 11.sp, color = Dim, maxLines = 1)
                    }
                }
                else -> Breadcrumb(vm)
            }
        }
        if (vm.query == null) {
            IconButton(onClick = { vm.search("") }, Modifier.size(44.dp)) { Icon(Icons.Default.Search, "Search", tint = Ink) }
            val cat = vm.screen as? Screen.Category
            if (cat == null || !cat.albums) {
                val grid = vm.viewMode == ViewMode.GRID
                IconButton(onClick = { vm.changeViewMode(if (grid) ViewMode.LIST else ViewMode.GRID) }, Modifier.size(44.dp)) {
                    Icon(
                        if (grid) Icons.AutoMirrored.Outlined.ViewList else Icons.Outlined.GridView,
                        if (grid) "List view" else "Grid view", tint = Ink,
                    )
                }
            }
            if (isDir) {
                val fav = vm.isFavorite(vm.dir.path)
                IconButton(onClick = { vm.toggleFavorite(vm.dir.path) }, Modifier.size(44.dp)) {
                    PixIcon(if (fav) "star" else "star-outline", 24.dp, contentDescription = if (fav) "Remove favorite" else "Favorite")
                }
            }
        } else {
            IconButton(onClick = { vm.search(null) }, Modifier.size(44.dp)) { Icon(Icons.Default.Close, "Close search", tint = Ink) }
        }
        Box {
            IconButton(onClick = { menu = true }, Modifier.size(44.dp)) { Icon(Icons.Default.MoreVert, "More", tint = Ink) }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                Text("View · pinch to resize", color = Dim, fontSize = 12.sp, modifier = Modifier.padding(start = 16.dp, top = 4.dp, bottom = 6.dp))
                ViewOptions(vm, Modifier.width(260.dp).padding(horizontal = 12.dp))
                HorizontalDivider(color = Panel, modifier = Modifier.padding(top = 8.dp))
                if (isDir) {
                    MenuItem("New folder") { onDlg(Dlg.NewFolder); menu = false }
                    MenuItem("New file") { onDlg(Dlg.NewFile); menu = false }
                    HorizontalDivider(color = Panel)
                }
                Sort.entries.forEach { s ->
                    val on = vm.sort == s
                    MenuItem(
                        "Sort by ${s.label}" + if (on) "  ·  ${if (vm.sortReversed) s.reversed else s.normal}" else "", on,
                    ) { vm.changeSort(s); menu = false }
                }
                MenuItem("Reverse order", vm.sortReversed) { vm.toggleReversed(); menu = false }
                MenuItem("Hidden files", vm.showHidden) { vm.toggleHidden(); menu = false }
                HorizontalDivider(color = Panel)
                MenuItem("Select all") { vm.selectAll(); menu = false }
                if (isDir) MenuItem("Folder properties") { onDlg(Dlg.Props(listOf(vm.dir.path))); menu = false }
                MenuItem("Settings") { vm.settingsOpen = true; menu = false }
            }
        }
    }
}

@Composable
private fun SelectionTopBar(vm: FmViewModel) {
    Row(Modifier.fillMaxWidth().height(48.dp).background(Panel), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = { vm.clearSelection() }, Modifier.size(44.dp)) { Icon(Icons.Default.Close, "Clear selection", tint = Ink) }
        Text("${vm.selected.size} selected", fontSize = 16.sp, color = Ink, modifier = Modifier.weight(1f))
        TextButton({ vm.selectAll() }) { Text("All") }
    }
}

@Composable
private fun Breadcrumb(vm: FmViewModel) {
    val scroll = rememberScrollState()
    val vol = vm.volumeOf(vm.dir)
    val base = vol?.root ?: File("/")
    val label = when {
        vol == null -> "/"
        vol.removable -> vol.label
        else -> "Internal"
    }
    val parts = vm.dir.relativeToOrNull(base)?.path?.split('/')?.filter { it.isNotEmpty() } ?: emptyList()
    LaunchedEffect(vm.dir, scroll.maxValue) { scroll.scrollTo(scroll.maxValue) }
    Row(Modifier.horizontalScroll(scroll), verticalAlignment = Alignment.CenterVertically) {
        Text(
            label, fontSize = 14.sp, color = if (parts.isEmpty()) Ink else Dim,
            fontWeight = if (parts.isEmpty()) FontWeight.SemiBold else FontWeight.Normal,
            modifier = Modifier.clickable { vm.openDir(base) }.padding(vertical = 8.dp),
        )
        parts.forEachIndexed { i, p ->
            Text("  ›  ", fontSize = 14.sp, color = Dim)
            Text(
                p, fontSize = 14.sp, maxLines = 1, color = if (i == parts.lastIndex) Ink else Dim,
                fontWeight = if (i == parts.lastIndex) FontWeight.SemiBold else FontWeight.Normal,
                modifier = Modifier
                    .clickable { vm.openDir(File(base, parts.take(i + 1).joinToString("/"))) }
                    .padding(vertical = 8.dp),
            )
        }
    }
}

@Composable
private fun SearchField(value: String, hint: String, onChange: (String) -> Unit) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    Box(Modifier.fillMaxWidth()) {
        if (value.isEmpty()) Text(hint, color = Dim, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        BasicTextField(
            value = value, onValueChange = onChange, singleLine = true,
            textStyle = TextStyle(color = Ink, fontSize = 15.sp),
            cursorBrush = SolidColor(Accent),
            modifier = Modifier.fillMaxWidth().focusRequester(focus),
        )
    }
}

// ───────────────────────── rows & bars ─────────────────────────

@Composable
private fun FileRow(
    item: FileItem, selected: Boolean, favorite: Boolean, location: String?,
    enabled: Boolean = true, size: ViewSize = ViewSize.MEDIUM, onClick: () -> Unit, onLong: () -> Unit,
) {
    val date = FileOps.fmtDate(item.modified)
    val sub = (if (item.isDir) "${item.count} items · $date" else "${FileOps.fmtSize(item.size)} · $date") +
        (location?.let { " · in $it" } ?: "")
    Row(
        Modifier.fillMaxWidth()
            .background(if (selected) Accent.copy(alpha = 0.18f) else Color.Transparent)
            .combinedClickable(onClick = onClick, onLongClick = onLong)
            .alpha(if (enabled) 1f else 0.38f)
            .padding(horizontal = 12.dp, vertical = size.listPad),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FileIcon(item, size.listIcon)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                item.name, fontSize = size.nameSp.sp, color = Ink, overflow = TextOverflow.Ellipsis,
                maxLines = if (size >= ViewSize.LARGE) 2 else 1,
            )
            Text(sub, fontSize = size.subSp.sp, color = Dim, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (favorite) PixIcon("star", 18.dp)
    }
}

private fun plural(n: Int, noun: String) = "$n $noun" + if (n == 1) "" else "s"

/** Photos/Videos: one card per folder, newest item as cover, like a gallery app. */
@Composable
private fun AlbumGrid(vm: FmViewModel, groups: List<Pair<String, List<FileItem>>>, noun: String, cell: Dp, state: LazyGridState) {
    LazyVerticalGrid(
        GridCells.Adaptive(cell),
        Modifier.fillMaxSize(),
        state,
        contentPadding = PaddingValues(12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        items(groups, key = { it.first }) { (folder, items) ->
            val cover = remember(items) { items.maxBy { it.modified } }
            val onSd = vm.volumeOf(File(folder))?.removable == true
            Column(
                Modifier.clip(RoundedCornerShape(14.dp))
                    .combinedClickable(onClick = { vm.openAlbum(folder) }, onLongClick = { vm.openDir(File(folder)) }),
            ) {
                Box(Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(14.dp))) {
                    MediaThumb(cover, Modifier.fillMaxSize(), px = 360)
                    if (onSd) PixIcon("sdcard", 22.dp, Modifier.align(Alignment.TopStart).padding(6.dp))
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    vm.displayName(folder), fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = Ink,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 2.dp),
                )
                Text(plural(items.size, noun), fontSize = 12.sp, color = Dim, modifier = Modifier.padding(horizontal = 2.dp))
            }
        }
    }
}

/** Inside an album: square thumbnails; long-press selects. */
@Composable
private fun MediaGrid(list: List<FileItem>, sel: Set<String>, cell: Dp, state: LazyGridState, onClick: (FileItem) -> Unit, onLong: (FileItem) -> Unit) {
    LazyVerticalGrid(
        GridCells.Adaptive(cell),
        Modifier.fillMaxSize(),
        state,
        contentPadding = PaddingValues(2.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        items(list, key = { it.path }) { item ->
            val selected = item.path in sel
            Box(
                Modifier.aspectRatio(1f)
                    .combinedClickable(onClick = { onClick(item) }, onLongClick = { onLong(item) }),
            ) {
                MediaThumb(item, Modifier.fillMaxSize().padding(if (selected) 8.dp else 0.dp).clip(RoundedCornerShape(if (selected) 10.dp else 0.dp)), px = if (cell > 140.dp) 400 else 260)
                if (selected) {
                    Icon(
                        Icons.Filled.CheckCircle, "Selected", tint = Accent,
                        modifier = Modifier.align(Alignment.TopEnd).padding(4.dp).size(24.dp).clip(CircleShape).background(Bg),
                    )
                } else if (sel.isNotEmpty()) {
                    Box(
                        Modifier.align(Alignment.TopEnd).padding(6.dp).size(20.dp).clip(CircleShape)
                            .background(Color.Black.copy(alpha = 0.3f)).border(2.dp, Color.White, CircleShape),
                    )
                }
            }
        }
    }
}

/** Category views: the folder that holds the files below it. */
@Composable
private fun GroupHeader(name: String, path: String, count: Int, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().background(Bg).clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PixIcon(IconMap.forFolder(name), 22.dp)
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(name, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(path, fontSize = 10.sp, color = Dim, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Text("$count", fontSize = 12.sp, color = Accent, modifier = Modifier.padding(start = 8.dp))
    }
}

@Composable
private fun ActionBar(vm: FmViewModel, ctx: Context, onDlg: (Dlg) -> Unit) {
    val one = vm.selectedItems.singleOrNull()
    var more by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().height(58.dp).background(Panel),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Act(Icons.Outlined.ContentCopy, "Copy", Modifier.weight(1f)) { vm.copySel() }
        Act(Icons.Outlined.ContentCut, "Cut", Modifier.weight(1f)) { vm.cutSel() }
        val archive = one?.takeIf { it.ext in FileOps.ZIPLIKE }
        if (archive != null) {
            var extractMenu by remember { mutableStateOf(false) }
            Box(Modifier.weight(1f)) {
                Act(Icons.Outlined.Unarchive, "Extract", Modifier.fillMaxWidth(), color = Accent) { extractMenu = true }
                ExtractMenu(extractMenu, File(archive.path), null, vm, onDlg) { extractMenu = false; vm.clearSelection() }
            }
        } else {
            Act(Icons.Outlined.DriveFileRenameOutline, "Rename", Modifier.weight(1f), enabled = one != null) {
                one?.let { onDlg(Dlg.Rename(it)) }
            }
        }
        Act(Icons.Outlined.Info, "Properties", Modifier.weight(1f)) { onDlg(Dlg.Props(vm.selected.toList())) }
        Act(Icons.Outlined.Delete, "Delete", Modifier.weight(1f), color = Danger) { onDlg(Dlg.Delete) }
        Box(Modifier.weight(1f)) {
            Act(Icons.Default.MoreVert, "More", Modifier.fillMaxWidth()) { more = true }
            DropdownMenu(expanded = more, onDismissRequest = { more = false }) {
                if (archive != null) {
                    MenuItem("Browse archive") { vm.openViewer(Viewer.Zip(archive.path)); vm.clearSelection(); more = false }
                    MenuItem("Rename") { onDlg(Dlg.Rename(archive)); more = false }
                }
                MenuItem("Compress to zip") { onDlg(Dlg.ZipName); more = false }
                if (vm.selectedItems.any { it.isDir || it.ext in setOf("mcpack", "mcaddon", "zip") }) {
                    MenuItem("Compile to brarchive") { onDlg(Dlg.CompileName); more = false }
                }
                MenuItem("Share") { share(ctx, vm.selectedItems) { vm.message = it }; more = false }
                if (one != null && !one.isDir) {
                    MenuItem("Open with…") { openWith(ctx, File(one.path)) { vm.message = it }; more = false }
                    MenuItem("Open as text") { vm.openEditor(one.path); vm.clearSelection(); more = false }
                }
                if (one != null && one.isDir) {
                    MenuItem(if (vm.isFavorite(one.path)) "Remove from favorites" else "Add to favorites") {
                        vm.toggleFavorite(one.path); vm.clearSelection(); more = false
                    }
                }
            }
        }
    }
}

@Composable
private fun Act(
    icon: ImageVector, label: String, modifier: Modifier,
    color: Color = Ink, enabled: Boolean = true, onClick: () -> Unit,
) {
    val c = if (enabled) color else Dim.copy(alpha = 0.5f)
    Column(
        modifier.fillMaxHeight().clickable(enabled = enabled, onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(icon, null, tint = c, modifier = Modifier.size(20.dp))
        Spacer(Modifier.height(3.dp))
        Text(label, fontSize = 10.sp, color = c, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun PasteBar(vm: FmViewModel) {
    val c = vm.clip ?: return
    Row(
        Modifier.fillMaxWidth().height(52.dp).background(Panel).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("${c.files.size} item(s) to ${if (c.cut) "move" else "copy"}", fontSize = 13.sp, color = Ink, modifier = Modifier.weight(1f))
        TextButton({ vm.cancelClip() }) { Text("Cancel", color = Dim) }
        Button({ vm.paste() }, contentPadding = PaddingValues(horizontal = 16.dp)) { Text("Paste here") }
    }
}

@Composable
private fun PlayerBar(p: AudioPlayer) {
    val f = p.current ?: return
    var drag by remember { mutableStateOf<Float?>(null) }
    Column(Modifier.fillMaxWidth().background(Panel2).padding(start = 12.dp, end = 4.dp, top = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            PixIcon(IconMap.forFile(f.name, f.extension.lowercase()), 30.dp)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(f.nameWithoutExtension, fontSize = 13.sp, color = Ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val pos = drag?.toLong() ?: p.position.toLong()
                Text(
                    "${FileOps.fmtTime(pos)} / ${FileOps.fmtTime(p.duration.toLong())} · ${p.index + 1} of ${p.queue.size}",
                    fontSize = 11.sp, color = Dim, maxLines = 1,
                )
            }
            IconButton({ p.prev() }, Modifier.size(40.dp)) { Icon(Icons.Filled.SkipPrevious, "Previous", tint = Ink) }
            IconButton(
                { p.toggle() },
                Modifier.size(42.dp).clip(CircleShape).background(Accent),
            ) { Icon(if (p.playing) Icons.Filled.Pause else Icons.Filled.PlayArrow, "Play/pause", tint = OnAccent) }
            IconButton({ p.next() }, Modifier.size(40.dp)) { Icon(Icons.Filled.SkipNext, "Next", tint = Ink) }
            IconButton({ p.stop() }, Modifier.size(40.dp)) { Icon(Icons.Default.Close, "Close player", tint = Dim) }
        }
        Slider(
            value = (drag ?: p.position.toFloat()).coerceIn(0f, p.duration.coerceAtLeast(1).toFloat()),
            onValueChange = { drag = it },
            onValueChangeFinished = { drag?.let { p.seek(it.toInt()) }; drag = null },
            valueRange = 0f..p.duration.coerceAtLeast(1).toFloat(),
            colors = SliderDefaults.colors(thumbColor = Accent, activeTrackColor = Accent, inactiveTrackColor = Panel),
            modifier = Modifier.fillMaxWidth().padding(end = 8.dp).height(30.dp),
        )
    }
}

// ───────────────────────── other apps ─────────────────────────

/** Shown while another app is waiting for us to pick files. */
@Composable
private fun PickBanner(vm: FmViewModel, p: PickRequest) {
    Row(
        Modifier.fillMaxWidth().background(Accent.copy(alpha = 0.16f)).heightIn(min = 48.dp).padding(end = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton({ vm.finishPick(null) }, Modifier.size(44.dp)) { Icon(Icons.Default.Close, "Cancel", tint = Ink) }
        Column(Modifier.weight(1f)) {
            Text("Choose ${p.noun}", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Ink)
            p.caller?.let { Text("for $it", fontSize = 11.sp, color = Dim, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
        if (p.multiple) Text("Tap to select", fontSize = 11.sp, color = Dim)
    }
}

@Composable
private fun PickBar(vm: FmViewModel, p: PickRequest) {
    val chosen = vm.selectedItems.filter { p.accepts(it) }
    val ok = chosen.isNotEmpty() && (p.multiple || chosen.size == 1)
    Row(
        Modifier.fillMaxWidth().height(58.dp).background(Panel).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(plural(chosen.size, "file") + " chosen", fontSize = 14.sp, color = Ink, modifier = Modifier.weight(1f))
        Button(
            { vm.finishPick(chosen.map { it.path }) }, enabled = ok,
            colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = OnAccent),
        ) { Text("Select") }
    }
}

/** Opened by another app: only the file itself (viewer, editor or player) is shown, never the browser. */
@Composable
private fun ExternalScreen(vm: FmViewModel) {
    val err = vm.openError
    val song = vm.player.current
    Column(Modifier.fillMaxSize().systemBarsPadding()) {
        Column(
            Modifier.weight(1f).fillMaxWidth().padding(32.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            when {
                err != null -> {
                    Logo(72.dp)
                    Spacer(Modifier.height(16.dp))
                    Text(err, color = Danger, fontSize = 14.sp)
                    Spacer(Modifier.height(12.dp))
                    TextButton({ vm.dismissOpenError() }) { Text("Close") }
                }
                song != null -> {
                    PixIcon(IconMap.forFile(song.name, song.extension.lowercase()), 96.dp)
                    Spacer(Modifier.height(16.dp))
                    Text(song.nameWithoutExtension, color = Ink, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    if (vm.player.loading) {
                        Spacer(Modifier.height(12.dp))
                        CircularProgressIndicator(color = Accent)
                    }
                }
                else -> {
                    Logo(72.dp)
                    Spacer(Modifier.height(16.dp))
                    CircularProgressIndicator(color = Accent)
                    Spacer(Modifier.height(12.dp))
                    Text("Opening…", color = Dim, fontSize = 14.sp)
                }
            }
        }
        PlayerBar(vm.player)
    }
}

/** New version found on GitHub: notes, download progress, then Android's installer. */
@Composable
private fun UpdateDialog(vm: FmViewModel) {
    val ctx = LocalContext.current
    val st = vm.update
    val rel = when (st) {
        is UpdateState.Available -> st.release
        is UpdateState.Downloading -> st.release
        is UpdateState.Ready -> st.release
        else -> null
    }
    LaunchedEffect(st) {
        when (st) {
            is UpdateState.Ready -> Updater.install(ctx, st.apk)?.let { vm.message = it }
            is UpdateState.Available, is UpdateState.Downloading, is UpdateState.Failed -> {}
            else -> vm.updateDialog = false
        }
    }
    AlertDialog(
        onDismissRequest = { if (st !is UpdateState.Downloading) vm.updateDialog = false },
        icon = { Logo(40.dp) },
        title = { Text(if (rel != null) "Update available" else "Update") },
        text = {
            Column {
                if (rel != null) {
                    Text(
                        "${vm.appVersion} → ${rel.version}" + if (rel.prerelease) "  (beta)" else "",
                        color = Ink, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                    )
                    Text(FileOps.fmtSize(rel.size), color = Dim, fontSize = 12.sp)
                    Spacer(Modifier.height(8.dp))
                    Box(Modifier.heightIn(max = 240.dp).verticalScroll(rememberScrollState())) {
                        Text(Updater.plainNotes(rel.notes), color = Dim, fontSize = 13.sp)
                    }
                }
                when (st) {
                    is UpdateState.Downloading -> {
                        Spacer(Modifier.height(12.dp))
                        LinearProgressIndicator(
                            progress = { st.progress }, modifier = Modifier.fillMaxWidth(), color = Accent, trackColor = Panel2,
                            drawStopIndicator = {},
                        )
                        Text("Downloading… ${(st.progress * 100).toInt()}%", color = Dim, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
                    }
                    is UpdateState.Failed -> Text(st.message, color = Danger, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp))
                    else -> {}
                }
            }
        },
        confirmButton = {
            when (st) {
                is UpdateState.Available -> TextButton({ vm.downloadUpdate() }) { Text("Update") }
                is UpdateState.Failed -> TextButton({ vm.downloadUpdate() }) { Text("Try again") }
                is UpdateState.Ready -> TextButton({ Updater.install(ctx, st.apk)?.let { vm.message = it } }) { Text("Install") }
                else -> {}
            }
        },
        dismissButton = {
            if (st !is UpdateState.Downloading) TextButton({ vm.updateDialog = false }) { Text("Later") }
        },
    )
}

// ───────────────────────── intents ─────────────────────────

internal fun uriFor(ctx: Context, f: File) =
    FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", f)

internal fun viewIntent(ctx: Context, f: File) = Intent(Intent.ACTION_VIEW)
    .setDataAndType(uriFor(ctx, f), FileOps.mime(f))
    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)

internal fun openFile(ctx: Context, f: File, onMsg: (String) -> Unit) {
    try {
        ctx.startActivity(viewIntent(ctx, f))
    } catch (e: ActivityNotFoundException) {
        onMsg("No app can open this file")
    } catch (e: Exception) {
        onMsg("Can't open: ${e.message}")
    }
}

/** Always shows the app chooser, even if a default app is set. */
fun openWith(ctx: Context, f: File, onMsg: (String) -> Unit) {
    try {
        ctx.startActivity(Intent.createChooser(viewIntent(ctx, f), "Open with"))
    } catch (e: Exception) {
        onMsg("Can't open: ${e.message}")
    }
}

internal fun share(ctx: Context, items: List<FileItem>, onMsg: (String) -> Unit) {
    val files = items.filter { !it.isDir }
    if (files.isEmpty()) { onMsg("Folders can't be shared — zip them first"); return }
    try {
        val uris = ArrayList(files.map { uriFor(ctx, File(it.path)) })
        val i = Intent(Intent.ACTION_SEND_MULTIPLE)
            .setType("*/*")
            .putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        ctx.startActivity(Intent.createChooser(i, null))
    } catch (e: Exception) {
        onMsg("Can't share: ${e.message}")
    }
}
