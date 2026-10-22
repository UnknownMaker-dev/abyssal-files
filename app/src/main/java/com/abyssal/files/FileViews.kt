@file:OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)

package com.abyssal.files

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ViewList
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material3.*
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

enum class ViewMode { LIST, GRID }

/** One size knob for everything: list rows, grid tiles, photo grids and albums grow together. */
enum class ViewSize(
    val label: String,
    val listIcon: Dp, val listPad: Dp, val nameSp: Int, val subSp: Int,
    /** Minimum widths: the grids fit as many columns as the screen allows. */
    val tile: Dp, val media: Dp, val album: Dp,
) {
    SMALL("S", 28.dp, 3.dp, 13, 10, 78.dp, 80.dp, 110.dp),
    MEDIUM("M", 38.dp, 6.dp, 14, 11, 100.dp, 110.dp, 150.dp),
    LARGE("L", 52.dp, 9.dp, 15, 12, 132.dp, 150.dp, 190.dp),
    HUGE("XL", 72.dp, 11.dp, 16, 13, 170.dp, 200.dp, 260.dp),
}

/** List/grid switch and size buttons, used in the ⋮ menu and in Settings. */
@Composable
fun ViewOptions(vm: FmViewModel, modifier: Modifier = Modifier) {
    Column(modifier) {
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            listOf(
                Triple(ViewMode.LIST, "List", Icons.AutoMirrored.Outlined.ViewList),
                Triple(ViewMode.GRID, "Grid", Icons.Outlined.GridView),
            ).forEachIndexed { i, (mode, label, icon) ->
                SegmentedButton(
                    selected = vm.viewMode == mode,
                    onClick = { vm.changeViewMode(mode) },
                    shape = SegmentedButtonDefaults.itemShape(i, 2),
                    icon = { Icon(icon, null, Modifier.size(18.dp)) },
                ) { Text(label, fontSize = 13.sp) }
            }
        }
        Spacer(Modifier.height(8.dp))
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            ViewSize.entries.forEachIndexed { i, s ->
                SegmentedButton(
                    selected = vm.viewSize == s,
                    onClick = { vm.changeViewSize(s) },
                    shape = SegmentedButtonDefaults.itemShape(i, ViewSize.entries.size),
                    icon = {},
                ) { Text(s.label, fontSize = 13.sp) }
            }
        }
    }
}

/** Two-finger pinch: spread to make things bigger (+1), pinch to make them smaller (-1). One finger still scrolls. */
fun Modifier.pinchToResize(onStep: (Int) -> Unit): Modifier = pointerInput(Unit) {
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        var zoom = 1f
        do {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            if (event.changes.count { it.pressed } >= 2) {
                zoom *= event.calculateZoom()
                when {
                    zoom > 1.3f -> { onStep(1); zoom = 1f }
                    zoom < 0.77f -> { onStep(-1); zoom = 1f }
                }
                event.changes.forEach { it.consume() }
            }
        } while (event.changes.any { it.pressed })
    }
}

/** Grid view: a square with the photo or the big pixel icon, the name below. */
@Composable
fun FileTile(
    item: FileItem, size: ViewSize, selected: Boolean, favorite: Boolean, location: String?,
    enabled: Boolean, onClick: () -> Unit, onLong: () -> Unit,
) {
    Column(
        Modifier.clip(RoundedCornerShape(12.dp))
            .background(if (selected) Accent.copy(alpha = 0.18f) else Color.Transparent)
            .combinedClickable(onClick = onClick, onLongClick = onLong)
            .alpha(if (enabled) 1f else 0.38f)
            .padding(6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.fillMaxWidth().aspectRatio(1f), contentAlignment = Alignment.Center) {
            if (item.kind == Kind.IMAGE || item.kind == Kind.VIDEO) {
                MediaThumb(item, Modifier.fillMaxSize().clip(RoundedCornerShape(8.dp)), px = if (size >= ViewSize.LARGE) 400 else 260)
            } else if (item.isDir) {
                FolderIcon(item.path, item.name, size.tile * 0.62f)
            } else {
                PixIcon(IconMap.of(item), size.tile * 0.62f)
            }
            if (favorite) PixIcon("star", 18.dp, Modifier.align(Alignment.TopStart))
            if (selected) {
                Icon(
                    Icons.Filled.CheckCircle, "Selected", tint = Accent,
                    modifier = Modifier.align(Alignment.TopEnd).size(22.dp).clip(CircleShape).background(Bg),
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            item.name, fontSize = (size.nameSp - 1).sp, lineHeight = (size.nameSp + 2).sp, color = Ink,
            maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
        )
        if (size != ViewSize.SMALL) {
            val sub = if (item.isDir) "${item.count} items" else FileOps.fmtSize(item.size)
            Text(
                sub + (location?.let { " · $it" } ?: ""), fontSize = (size.subSp - 1).sp, color = Dim,
                maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
            )
        }
    }
}

/**
 * A list state that comes back to where it was for [key] (after opening a file, a subfolder…): the position is
 * restored once the list has loaded ([ready]) and saved when the list goes away.
 */
@Composable
fun rememberListPosition(vm: FmViewModel, key: String, ready: Boolean): LazyListState {
    val state = remember(key) { LazyListState() }
    var restored by remember(key) { mutableStateOf(false) }
    LaunchedEffect(key, ready) {
        if (ready && !restored) {
            vm.scrollMemory[key]?.let { (i, o) -> runCatching { state.scrollToItem(i, o) } }
            restored = true
        }
    }
    DisposableEffect(key) {
        onDispose { if (restored) vm.scrollMemory[key] = state.firstVisibleItemIndex to state.firstVisibleItemScrollOffset }
    }
    return state
}

@Composable
fun rememberGridPosition(vm: FmViewModel, key: String, ready: Boolean): LazyGridState {
    val state = remember(key) { LazyGridState() }
    var restored by remember(key) { mutableStateOf(false) }
    LaunchedEffect(key, ready) {
        if (ready && !restored) {
            vm.scrollMemory[key]?.let { (i, o) -> runCatching { state.scrollToItem(i, o) } }
            restored = true
        }
    }
    DisposableEffect(key) {
        onDispose { if (restored) vm.scrollMemory[key] = state.firstVisibleItemIndex to state.firstVisibleItemScrollOffset }
    }
    return state
}
