@file:OptIn(ExperimentalFoundationApi::class)

package com.abyssal.files

import android.net.Uri
import android.content.Intent
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.graphics.BitmapFactory
import android.media.MediaPlayer
import android.os.Build
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Unarchive
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Full-screen viewers opened on top of the browser; the VM keeps them as a stack. */
sealed class Viewer {
    class Images(paths: List<String>, start: Int) : Viewer() {
        var paths by mutableStateOf(paths)
        var index by mutableIntStateOf(start)
    }

    class Videos(val paths: List<String>, start: Int) : Viewer() {
        var index by mutableIntStateOf(start)
    }

    class Zip(val path: String) : Viewer() {
        /** Folder inside the archive, "" = root, otherwise ends with '/'. */
        var dir by mutableStateOf("")
    }
}

fun Context.findActivity(): Activity? {
    var c: Context? = this
    while (c is ContextWrapper) {
        if (c is Activity) return c
        c = c.baseContext
    }
    return null
}

/** Hides the status/navigation bars while [hidden]; they come back on swipe or when leaving. */
@Composable
private fun Immersive(hidden: Boolean) {
    val view = LocalView.current
    val window = view.context.findActivity()?.window ?: return
    DisposableEffect(hidden) {
        val c = WindowCompat.getInsetsController(window, view)
        c.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        if (hidden) c.hide(WindowInsetsCompat.Type.systemBars()) else c.show(WindowInsetsCompat.Type.systemBars())
        onDispose { c.show(WindowInsetsCompat.Type.systemBars()) }
    }
}

@Composable
private fun ViewerBar(title: String, subtitle: String, onBack: () -> Unit, actions: @Composable RowScope.() -> Unit) {
    Row(
        Modifier.fillMaxWidth().background(Color.Black.copy(alpha = 0.55f)).statusBarsPadding().height(56.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Color.White) }
        Column(Modifier.weight(1f)) {
            Text(title, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(subtitle, color = Color.White.copy(alpha = 0.7f), fontSize = 11.sp, maxLines = 1)
        }
        actions()
    }
}

// ───────────────────────── images ─────────────────────────

private sealed interface Loaded {
    data object Loading : Loaded
    data object Failed : Loaded
    data class Ok(val bmp: ImageBitmap) : Loaded
}

/** Decodes at most ~2560 px on the long side, rotated per EXIF. */
private fun decodeForScreen(path: String): ImageBitmap? = runCatching {
    val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(path, o)
    var s = 1
    while (maxOf(o.outWidth, o.outHeight) / s > 2560) s *= 2
    BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = s })?.let { rotateByExif(path, it) }?.asImageBitmap()
}.getOrNull()

@Composable
fun ImageViewer(v: Viewer.Images, vm: FmViewModel, onDlg: (Dlg) -> Unit) {
    val ctx = LocalContext.current
    val pager = rememberPagerState(initialPage = v.index.coerceIn(0, maxOf(0, v.paths.lastIndex))) { v.paths.size }
    LaunchedEffect(pager.currentPage) { v.index = pager.currentPage }
    var chrome by remember { mutableStateOf(true) }
    var confirmDelete by remember { mutableStateOf(false) }
    Immersive(!chrome)

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        HorizontalPager(pager, Modifier.fillMaxSize(), key = { v.paths.getOrElse(it) { "$it" } }, beyondViewportPageCount = 1) { page ->
            ZoomableImage(v.paths.getOrElse(page) { "" }) { chrome = !chrome }
        }
        val file = File(v.paths.getOrElse(pager.currentPage) { "" })
        if (chrome) {
            ViewerBar(
                file.name,
                "${pager.currentPage + 1} / ${v.paths.size} · ${FileOps.fmtSize(file.length())} · ${FileOps.fmtDate(file.lastModified())}",
                onBack = { vm.back() },
            ) {
                IconButton({ share(ctx, listOf(FileItem.of(file))) { vm.message = it } }) { Icon(Icons.Outlined.Share, "Share", tint = Color.White) }
                IconButton({ openWith(ctx, file) { vm.message = it } }) { Icon(Icons.AutoMirrored.Filled.OpenInNew, "Open with", tint = Color.White) }
                IconButton({ onDlg(Dlg.Props(listOf(file.path))) }) { Icon(Icons.Outlined.Info, "Properties", tint = Color.White) }
                // Files opened from an archive are temporary copies: nothing to delete
                if (!file.path.startsWith(ctx.cacheDir.path)) {
                    IconButton({ confirmDelete = true }) { Icon(Icons.Outlined.Delete, "Delete", tint = Color.White) }
                }
            }
        }
    }

    if (confirmDelete) {
        val file = File(v.paths.getOrElse(pager.currentPage) { "" })
        DeleteDialog(1, {
            confirmDelete = false
            vm.deletePaths(listOf(file.path)) {
                v.paths = v.paths - file.path
                if (v.paths.isEmpty()) vm.closeViewer()
            }
        }) { confirmDelete = false }
    }
}

@Composable
private fun ZoomableImage(path: String, onTap: () -> Unit) {
    val state by produceState<Loaded>(Loaded.Loading, path) {
        value = withContext(Dispatchers.IO) { decodeForScreen(path) }?.let { Loaded.Ok(it) } ?: Loaded.Failed
    }
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var size by remember { mutableStateOf(IntSize.Zero) }
    val tap by rememberUpdatedState(onTap)

    fun clamp(o: Offset): Offset {
        val mx = size.width * (scale - 1) / 2
        val my = size.height * (scale - 1) / 2
        return Offset(o.x.coerceIn(-mx, mx), o.y.coerceIn(-my, my))
    }

    Box(
        Modifier.fillMaxSize().onSizeChanged { size = it }
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { tap() },
                    onDoubleTap = { p ->
                        if (scale > 1f) { scale = 1f; offset = Offset.Zero } else {
                            scale = 2.5f
                            offset = clamp((Offset(size.width / 2f, size.height / 2f) - p) * 1.5f)
                        }
                    },
                )
            }
            .pointerInput(Unit) {
                // Pinch always zooms; one finger pans only when zoomed, otherwise the pager swipes
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    do {
                        val event = awaitPointerEvent()
                        val fingers = event.changes.count { it.pressed }
                        if (fingers > 1 || scale > 1f) {
                            scale = (scale * event.calculateZoom()).coerceIn(1f, 6f)
                            offset = if (scale == 1f) Offset.Zero else clamp(offset + event.calculatePan())
                            event.changes.forEach { if (it.positionChanged()) it.consume() }
                        }
                    } while (event.changes.any { it.pressed })
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        when (val s = state) {
            Loaded.Loading -> CircularProgressIndicator(color = Accent)
            Loaded.Failed -> Text("Can't show this image", color = Color.White.copy(alpha = 0.7f))
            is Loaded.Ok -> Image(
                s.bmp, null,
                Modifier.fillMaxSize().graphicsLayer {
                    scaleX = scale; scaleY = scale; translationX = offset.x; translationY = offset.y
                },
                contentScale = ContentScale.Fit,
            )
        }
    }
}

// ───────────────────────── video ─────────────────────────

private val SPEEDS = listOf(1f, 1.25f, 1.5f, 2f, 0.5f, 0.75f)

@Composable
fun VideoPlayer(v: Viewer.Videos, vm: FmViewModel) {
    val ctx = LocalContext.current
    val activity = remember { ctx.findActivity() }
    val path = v.paths[v.index.coerceIn(0, v.paths.lastIndex)]
    val file = remember(path) { File(path) }
    var surface by remember { mutableStateOf<SurfaceHolder?>(null) }
    var mp by remember { mutableStateOf<MediaPlayer?>(null) }
    var prepared by remember { mutableStateOf(false) }
    var playing by remember { mutableStateOf(false) }
    var duration by remember { mutableIntStateOf(0) }
    var position by remember { mutableIntStateOf(0) }
    var videoW by remember { mutableIntStateOf(0) }
    var videoH by remember { mutableIntStateOf(0) }
    var error by remember { mutableStateOf<String?>(null) }
    var chrome by remember { mutableStateOf(true) }
    var touch by remember { mutableIntStateOf(0) }
    var speed by remember { mutableFloatStateOf(1f) }
    var landscape by remember { mutableStateOf(false) }
    var drag by remember { mutableStateOf<Float?>(null) }

    fun applySpeed(p: MediaPlayer) {
        if (Build.VERSION.SDK_INT >= 23) runCatching {
            val was = p.isPlaying
            p.playbackParams = p.playbackParams.setSpeed(speed)
            if (!was) p.pause()
        }
    }

    LaunchedEffect(Unit) { vm.player.pause() }

    DisposableEffect(path) {
        val p = MediaPlayer()
        prepared = false; playing = false; error = null; position = 0; duration = 0; videoW = 0; videoH = 0
        try {
            p.setDataSource(path)
            p.setOnPreparedListener {
                duration = it.duration; videoW = it.videoWidth; videoH = it.videoHeight
                prepared = true
                it.start(); playing = true
                applySpeed(it)
            }
            p.setOnVideoSizeChangedListener { _, w, h -> videoW = w; videoH = h }
            p.setOnCompletionListener { playing = false; chrome = true; position = duration }
            p.setOnErrorListener { _, _, _ -> error = "Can't play this video"; playing = false; true }
            surface?.let { p.setDisplay(it) }
            p.prepareAsync()
        } catch (e: Exception) {
            error = "Can't open this video"
        }
        mp = p
        onDispose { p.release(); if (mp === p) mp = null }
    }
    LaunchedEffect(surface, mp) {
        val h = surface
        val p = mp
        if (h != null && p != null) runCatching { p.setDisplay(h) }
    }
    LaunchedEffect(playing, mp) {
        while (playing) {
            mp?.let { runCatching { position = it.currentPosition } }
            delay(250)
        }
    }
    // Controls hide themselves while playing
    LaunchedEffect(chrome, playing, touch) {
        if (chrome && playing) { delay(3000); chrome = false }
    }
    // Pause when the app goes to the background
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val obs = LifecycleEventObserver { _, e ->
            if (e == Lifecycle.Event.ON_PAUSE) {
                mp?.takeIf { prepared && it.isPlaying }?.pause()
                playing = false
            }
        }
        lifecycle.addObserver(obs)
        onDispose { lifecycle.removeObserver(obs) }
    }
    DisposableEffect(Unit) {
        onDispose { activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED }
    }
    Immersive(!chrome || landscape)

    fun seekTo(ms: Int) {
        val p = mp ?: return
        if (!prepared) return
        val t = ms.coerceIn(0, duration)
        p.seekTo(t); position = t
    }

    fun toggle() {
        val p = mp ?: return
        if (!prepared) return
        if (p.isPlaying) { p.pause(); playing = false } else {
            if (position >= duration - 200) seekTo(0)
            p.start(); playing = true; applySpeed(p)
        }
        touch++
    }

    Box(
        Modifier.fillMaxSize().background(Color.Black).pointerInput(Unit) {
            detectTapGestures(
                onTap = { chrome = !chrome; touch++ },
                onDoubleTap = { p -> seekTo(position + if (p.x < size.width / 2) -10_000 else 10_000); chrome = true; touch++ },
            )
        },
    ) {
        val ratio = if (videoW > 0 && videoH > 0) videoW / videoH.toFloat() else 16f / 9f
        AndroidView(
            factory = { c ->
                SurfaceView(c).apply {
                    holder.addCallback(object : SurfaceHolder.Callback {
                        override fun surfaceCreated(h: SurfaceHolder) { surface = h }
                        override fun surfaceChanged(h: SurfaceHolder, f: Int, w: Int, hh: Int) {}
                        override fun surfaceDestroyed(h: SurfaceHolder) {
                            runCatching { mp?.setDisplay(null) }
                            surface = null
                        }
                    })
                }
            },
            modifier = Modifier.align(Alignment.Center).aspectRatio(ratio),
        )

        // Links from other apps stream; "Open with" hands the link itself to another app
        val stream = path.startsWith("http://") || path.startsWith("https://")
        val openElsewhere = {
            if (stream) runCatching {
                ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_VIEW, Uri.parse(path)), "Open with"))
            }.onFailure { vm.message = "Can't open: ${it.message}" }
            else openWith(ctx, file) { vm.message = it }
        }
        if (stream) LaunchedEffect(path) {
            delay(30_000)
            if (!prepared && error == null) error = "Couldn't load this video"
        }
        if (!prepared && error == null) {
            CircularProgressIndicator(Modifier.align(Alignment.Center), color = Accent)
        }

        error?.let { msg ->
            Column(Modifier.align(Alignment.Center).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(msg, color = Color.White, fontSize = 15.sp)
                TextButton({ openElsewhere() }) { Text("Open with another app") }
            }
        }

        if (chrome) {
            ViewerBar(
                Uri.decode(file.name),
                when {
                    v.paths.size > 1 -> "${v.index + 1} / ${v.paths.size}"
                    stream -> Uri.parse(path).host ?: "Streaming"
                    else -> FileOps.fmtSize(file.length())
                },
                onBack = { vm.back() },
            ) {
                IconButton({ openElsewhere() }) { Icon(Icons.AutoMirrored.Filled.OpenInNew, "Open with", tint = Color.White) }
            }

            Row(
                Modifier.align(Alignment.Center),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                RoundButton(Icons.Filled.SkipPrevious, "Previous video", 44, enabled = v.index > 0) { v.index-- }
                RoundButton(Icons.Filled.Replay10, "Back 10 seconds", 48) { seekTo(position - 10_000); touch++ }
                RoundButton(if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow, "Play/pause", 68, accent = true) { toggle() }
                RoundButton(Icons.Filled.Forward10, "Forward 10 seconds", 48) { seekTo(position + 10_000); touch++ }
                RoundButton(Icons.Filled.SkipNext, "Next video", 44, enabled = v.index < v.paths.lastIndex) { v.index++ }
            }

            Column(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(Color.Black.copy(alpha = 0.55f))
                    .navigationBarsPadding().padding(horizontal = 12.dp, vertical = 4.dp),
            ) {
                Slider(
                    value = (drag ?: position.toFloat()).coerceIn(0f, duration.coerceAtLeast(1).toFloat()),
                    onValueChange = { drag = it; touch++ },
                    onValueChangeFinished = { drag?.let { seekTo(it.toInt()) }; drag = null },
                    valueRange = 0f..duration.coerceAtLeast(1).toFloat(),
                    colors = SliderDefaults.colors(thumbColor = Accent, activeTrackColor = Accent, inactiveTrackColor = Color.White.copy(alpha = 0.3f)),
                    modifier = Modifier.height(30.dp),
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "${FileOps.fmtTime((drag?.toLong() ?: position.toLong()))} / ${FileOps.fmtTime(duration.toLong())}",
                        color = Color.White, fontSize = 12.sp, modifier = Modifier.weight(1f),
                    )
                    TextButton({
                        speed = SPEEDS[(SPEEDS.indexOf(speed) + 1) % SPEEDS.size]
                        mp?.takeIf { prepared }?.let { applySpeed(it) }
                        touch++
                    }) { Text("${if (speed % 1f == 0f) speed.toInt().toString() else speed.toString()}×", color = Color.White) }
                    IconButton({
                        landscape = !landscape
                        activity?.requestedOrientation =
                            if (landscape) ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE else ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                        touch++
                    }) { Icon(Icons.Filled.ScreenRotation, "Rotate", tint = Color.White) }
                }
            }
        }
    }
}

@Composable
private fun RoundButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector, desc: String, size: Int,
    enabled: Boolean = true, accent: Boolean = false, onClick: () -> Unit,
) {
    IconButton(
        onClick, enabled = enabled,
        modifier = Modifier.size(size.dp).clip(CircleShape)
            .background(if (accent) Accent else Color.Black.copy(alpha = 0.45f)),
    ) {
        Icon(
            icon, desc, modifier = Modifier.size((size * 0.55f).dp),
            tint = if (!enabled) Color.White.copy(alpha = 0.3f) else if (accent) OnAccent else Color.White,
        )
    }
}

// ───────────────────────── zip ─────────────────────────

/** Browses an archive without extracting it; files open from a cache copy. */
@Composable
fun ZipViewer(v: Viewer.Zip, vm: FmViewModel, onDlg: (Dlg) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val file = remember(v.path) { File(v.path) }
    val listing by produceState<FileOps.ZipListing?>(null, v.path) {
        value = withContext(Dispatchers.IO) { FileOps.zipListing(file) }
    }
    var menu by remember { mutableStateOf(false) }
    var opening by remember { mutableStateOf(false) }

    fun open(e: FileOps.ZEntry) {
        if (e.isDir) { v.dir = e.path; return }
        scope.launch {
            opening = true
            val out = withContext(Dispatchers.IO) { runCatching { FileOps.extractToCache(ctx.cacheDir, file, e.path) }.getOrNull() }
            opening = false
            if (out == null) { vm.message = "Can't open ${e.name}"; return@launch }
            val item = FileItem.of(out)
            when {
                item.kind == Kind.IMAGE -> vm.openViewer(Viewer.Images(listOf(out.path), 0))
                item.kind == Kind.VIDEO -> vm.openViewer(Viewer.Videos(listOf(out.path), 0))
                item.kind == Kind.AUDIO -> vm.player.play(listOf(out.path), 0)
                item.ext in FileOps.ZIP_BROWSE -> vm.openViewer(Viewer.Zip(out.path))
                FileOps.editable(item) -> vm.openEditor(out.path, edit = false, readOnly = true)
                else -> openFile(ctx, out) { vm.message = it }
            }
        }
    }

    Column(Modifier.fillMaxSize().background(Bg).systemBarsPadding()) {
        Row(Modifier.fillMaxWidth().height(52.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton({ vm.back() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Ink) }
            PixIcon(IconMap.forFile(file.name, file.extension.lowercase()), 22.dp)
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text(file.name, color = Ink, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("/" + v.dir, color = Dim, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Box {
                TextButton({ menu = true }) {
                    Icon(Icons.Outlined.Unarchive, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Extract")
                }
                ExtractMenu(menu, file, null, vm, onDlg) { menu = false }
            }
        }
        Box(Modifier.fillMaxWidth().height(2.dp)) {
            if (opening || vm.busy || listing == null) LinearProgressIndicator(Modifier.fillMaxSize(), color = Accent, trackColor = Color.Transparent)
        }
        val l = listing
        when {
            l == null -> {}
            l.error != null -> Box(Modifier.fillMaxSize().padding(24.dp), Alignment.Center) {
                Text("Can't read this archive: ${l.error}", color = Dim, textAlign = TextAlign.Center)
            }
            else -> {
                Text(
                    "${l.files} files · ${FileOps.fmtSize(l.size)}" + if (l.compressed in 1 until l.size) " (${FileOps.fmtSize(l.compressed)} compressed)" else "",
                    color = Dim, fontSize = 11.sp, modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                )
                val rows = l.children[v.dir].orEmpty()
                if (rows.isEmpty()) {
                    Box(Modifier.fillMaxSize(), Alignment.Center) { Text("Empty", color = Dim) }
                }
                LazyColumn(Modifier.fillMaxSize()) {
                    items(rows, key = { it.path }) { e -> ZipRow(e, l, file, vm, onDlg) { open(e) } }
                }
            }
        }
    }
}

@Composable
private fun ZipRow(e: FileOps.ZEntry, l: FileOps.ZipListing, zip: File, vm: FmViewModel, onDlg: (Dlg) -> Unit, onOpen: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Box {
        Row(
            Modifier.fillMaxWidth().combinedClickable(onClick = onOpen, onLongClick = { menu = true })
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PixIcon(if (e.isDir) IconMap.forFolder(e.name) else IconMap.forFile(e.name, e.name.substringAfterLast('.', "").lowercase()), 38.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(e.name, color = Ink, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    if (e.isDir) "${l.children[e.path]?.size ?: 0} items"
                    else "${FileOps.fmtSize(e.size)} · ${if (e.time > 0) FileOps.fmtDate(e.time) else "no date"}",
                    color = Dim, fontSize = 11.sp, maxLines = 1,
                )
            }
        }
        DropdownMenu(menu, { menu = false }) {
            if (!e.isDir) DropdownMenuItem(text = { Text("Open") }, onClick = { menu = false; onOpen() })
            ExtractMenuItems(zip, e.path, vm, onDlg) { menu = false }
        }
    }
}

/** "Extract here / to "name/" / to…" for a whole archive or one entry. */
@Composable
fun ExtractMenu(expanded: Boolean, zip: File, entry: String?, vm: FmViewModel, onDlg: (Dlg) -> Unit, onDismiss: () -> Unit) {
    DropdownMenu(expanded, onDismiss) { ExtractMenuItems(zip, entry, vm, onDlg, onDismiss) }
}

@Composable
private fun ExtractMenuItems(zip: File, entry: String?, vm: FmViewModel, onDlg: (Dlg) -> Unit, onDone: () -> Unit) {
    val inCache = zip.path.startsWith(LocalContext.current.cacheDir.path)
    val parentName = zip.parentFile?.let { vm.displayName(it.path) } ?: ""
    if (!inCache) {
        DropdownMenuItem(
            text = { Column { Text("Extract here"); Text("into $parentName", fontSize = 11.sp, color = Dim) } },
            onClick = { onDone(); vm.extract(zip.path, ExtractMode.HERE, entry = entry) },
        )
        DropdownMenuItem(
            text = { Column { Text("Extract to \"${zip.nameWithoutExtension}/\""); Text("new folder next to the archive", fontSize = 11.sp, color = Dim) } },
            onClick = { onDone(); vm.extract(zip.path, ExtractMode.NAMED, entry = entry) },
        )
    }
    DropdownMenuItem(
        text = { Column { Text("Extract to…"); Text("choose a folder", fontSize = 11.sp, color = Dim) } },
        onClick = { onDone(); onDlg(Dlg.ExtractTo(zip.path, entry)) },
    )
}
