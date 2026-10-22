package com.abyssal.files

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.gestures.rememberScrollableState
import androidx.compose.foundation.gestures.scrollable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/**
 * Files longer than this use [VirtualCodeEditor]: one big text field re-lays out the whole file on every
 * keystroke (seconds for a few thousand lines), so only the visible lines are drawn and only the cursor's
 * line is a text field.
 */
const val VIRTUAL_LINES = 150

/** Lines longer than this can't be virtualised (they'd be one giant field); such files use the classic editor. */
const val VIRTUAL_MAX_LINE = 3000

/** Kept at the start of the cursor's line so a backspace there can be seen (it joins the line to the previous one). */
private const val GUARD = '​'

fun lineStarts(text: String): IntArray {
    var n = 1
    for (c in text) if (c == '\n') n++
    val out = IntArray(n)
    var k = 1
    for (i in text.indices) if (text[i] == '\n') out[k++] = i + 1
    return out
}

/** Index of the line holding [offset]. */
fun lineOf(starts: IntArray, offset: Int): Int {
    var lo = 0
    var hi = starts.size - 1
    while (lo < hi) {
        val mid = (lo + hi + 1) / 2
        if (starts[mid] <= offset) lo = mid else hi = mid - 1
    }
    return lo
}

fun longestLine(text: String): Int {
    var best = 0
    var cur = 0
    for (c in text) if (c == '\n') { if (cur > best) best = cur; cur = 0 } else cur++
    return maxOf(best, cur)
}

/** Field text/selection without the guard characters: (text, start, end). */
internal fun stripGuard(t: String, selStart: Int, selEnd: Int): Triple<String, Int, Int> {
    fun map(p: Int): Int {
        var n = 0
        for (i in 0 until p.coerceAtMost(t.length)) if (t[i] == GUARD) n++
        return p - n
    }
    return Triple(t.replace(GUARD.toString(), ""), map(selStart), map(selEnd))
}

@Composable
fun VirtualCodeEditor(
    state: CodeState,
    fontSize: Int,
    matches: List<Int>,
    matchLen: Int,
    currentMatch: Int,
    modifier: Modifier = Modifier,
) {
    val v = state.value
    val text = v.text
    val lang = state.lang
    val colors = remember(Pal.accent) { SyntaxColors(Pal.accent) }
    val style = remember(fontSize) {
        editorStyle(fontSize).copy(lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None))
    }
    val density = LocalDensity.current
    val measurer = rememberTextMeasurer()
    val charW = remember(style) { measurer.measure("0123456789", style).size.width / 10f }
    val lhPx = with(density) { (fontSize * 1.5f).sp.toPx() }
    val lh = with(density) { lhPx.toDp() }
    val padPx = with(density) { 8.dp.toPx() }

    val starts = remember(text) { lineStarts(text) }
    val count = starts.size
    fun lineEnd(i: Int) = if (i + 1 < count) starts[i + 1] - 1 else text.length
    val longest = remember(text) { longestLine(text) }
    val cursor = v.selection.start.coerceIn(0, text.length)
    val active = lineOf(starts, cursor)
    val ls = starts[active]
    val le = lineEnd(active)
    val line = text.substring(ls, le)

    // Colours: a full pass in the background after typing pauses; lines keep their last colours meanwhile
    var hl by remember { mutableStateOf<Pair<String, List<AnnotatedString>>?>(null) }
    var byContent by remember { mutableStateOf<Map<String, AnnotatedString>>(emptyMap()) }
    LaunchedEffect(text, colors) {
        if (hl != null) delay(300)
        val lines = withContext(Dispatchers.Default) { highlightLines(text, lang, colors, limit = 2_000_000) }
        hl = text to lines
        byContent = withContext(Dispatchers.Default) { HashMap<String, AnnotatedString>(lines.size * 2).apply { lines.forEach { put(it.text, it) } } }
    }
    // Words of the document for autocomplete
    LaunchedEffect(text) {
        delay(600)
        state.docWords = withContext(Dispatchers.Default) { CodeState.collectWords(text, lang) }
    }

    var scrollY by remember { mutableFloatStateOf(0f) }
    var viewportH by remember { mutableIntStateOf(0) }
    var viewportW by remember { mutableIntStateOf(0) }
    val contentH = count * lhPx + padPx * 2
    val maxScroll = (contentH - viewportH).coerceAtLeast(0f)
    if (scrollY > maxScroll) scrollY = maxScroll
    val scrollable = rememberScrollableState { d ->
        val old = scrollY
        scrollY = (scrollY - d).coerceIn(0f, (count * lhPx + padPx * 2 - viewportH).coerceAtLeast(0f))
        old - scrollY
    }
    val hScroll = rememberScrollState()

    val focus = remember { FocusRequester() }
    var focused by remember { mutableStateOf(false) }
    var fieldLayout by remember { mutableStateOf<TextLayoutResult?>(null) }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }

    // Keep the cursor on screen while typing, centre it for find/undo
    fun scrollToLine(i: Int, center: Boolean) {
        val top = i * lhPx + padPx
        if (center) scrollY = (top - viewportH / 3f).coerceIn(0f, maxScroll)
        else if (top < scrollY) scrollY = (top - padPx).coerceAtLeast(0f)
        else if (top + lhPx > scrollY + viewportH) scrollY = (top + lhPx + padPx - viewportH).coerceIn(0f, maxScroll)
    }
    LaunchedEffect(active, viewportH) { if (viewportH > 0) scrollToLine(active, center = false) }
    LaunchedEffect(cursor - ls, viewportW) {
        val x = ((cursor - ls) * charW).roundToInt()
        if (x < hScroll.value) hScroll.scrollTo((x - 40).coerceAtLeast(0))
        else if (x > hScroll.value + viewportW - 120) hScroll.scrollTo((x - viewportW + 160).coerceIn(0, hScroll.maxValue))
    }
    LaunchedEffect(state.reveal) { if (state.reveal != 0) scrollToLine(active, center = true) }

    val completion = remember(text, v.selection, state.docWords, state.bedrock) { state.complete() }
    val sortedMatches = matches

    fun withMatches(base: AnnotatedString, lineStart: Int, shift: Int): AnnotatedString {
        if (sortedMatches.isEmpty() || matchLen == 0) return base
        val lineEndExcl = lineStart + base.length - shift
        var i = sortedMatches.binarySearch(lineStart).let { if (it < 0) -it - 1 else it }
        if (i > 0 && sortedMatches[i - 1] + matchLen > lineStart) i--
        if (i >= sortedMatches.size || sortedMatches[i] >= lineEndExcl) return base
        return buildAnnotatedString {
            append(base)
            while (i < sortedMatches.size && sortedMatches[i] < lineEndExcl) {
                val s = (sortedMatches[i] - lineStart).coerceAtLeast(0) + shift
                val e = (sortedMatches[i] + matchLen - lineStart + shift).coerceAtMost(base.length)
                if (e > s) addStyle(if (i == currentMatch) colors.currentStyle else colors.matchStyle, s, e)
                i++
            }
        }
    }

    fun styledLine(i: Int): AnnotatedString {
        val s = starts[i]
        val raw = text.substring(s, lineEnd(i))
        val h = hl
        val base = if (h != null && h.first === text) h.second.getOrNull(i) ?: AnnotatedString(raw)
        else byContent[raw] ?: AnnotatedString(raw)
        return withMatches(base, s, 0)
    }

    val digits = count.toString().length
    val gutterW = with(density) { (digits * charW + 14.dp.toPx()).toDp() }
    val contentW = with(density) { ((maxOf(longest, line.length) + 4) * charW + 16.dp.toPx()).toDp() }
    val from = (((scrollY - padPx) / lhPx).toInt() - 3).coerceAtLeast(0)
    val to = (from + (viewportH / lhPx).toInt() + 7).coerceAtMost(count)
    fun yOf(i: Int) = (i * lhPx + padPx - scrollY).roundToInt()

    Row(
        modifier.fillMaxSize().clipToBounds().scrollable(scrollable, Orientation.Vertical)
            .onSizeChanged { viewportH = it.height; viewportW = it.width },
    ) {
        // Line numbers
        Box(Modifier.width(gutterW).fillMaxHeight().background(Panel).clipToBounds()) {
            val numStyle = style.copy(color = Dim.copy(alpha = 0.55f), textAlign = TextAlign.End)
            for (i in from until to) {
                Text(
                    "${i + 1}", style = numStyle, maxLines = 1,
                    modifier = Modifier.offset { IntOffset(0, yOf(i)) }.width(gutterW - 6.dp).height(lh),
                )
            }
        }
        Box(Modifier.weight(1f).fillMaxHeight().clipToBounds().horizontalScroll(hScroll)) {
            // At least as wide as the screen, so taps past the end of short lines still land
            val minW = with(density) { (viewportW - gutterW.toPx() - 14.dp.toPx()).coerceAtLeast(0f).toDp() }
            Box(
                Modifier.width(maxOf(contentW, minW)).fillMaxHeight()
                    .pointerInput(starts, charW, lhPx) {
                        detectTapGestures { pos ->
                            val i = ((pos.y + scrollY - padPx) / lhPx).toInt().coerceIn(0, count - 1)
                            val s = starts[i]
                            val len = lineEnd(i) - s
                            val col = ((pos.x - padPx) / charW).roundToInt().coerceIn(0, len)
                            state.select(s + col, s + col)
                            runCatching { focus.requestFocus() }
                        }
                    },
            ) {
                for (i in from until to) {
                    if (i == active) continue
                    Text(
                        styledLine(i), style = style, softWrap = false, maxLines = 1,
                        modifier = Modifier.offset { IntOffset(padPx.roundToInt(), yOf(i)) }.height(lh),
                    )
                }
                // The cursor's line: the only text field
                val sel = v.selection
                val fieldValue = TextFieldValue(
                    GUARD + line,
                    TextRange((sel.start - ls).coerceIn(0, line.length) + 1, (sel.end - ls).coerceIn(0, line.length) + 1),
                    v.composition?.takeIf { it.min >= ls && it.max <= le }?.let { TextRange(it.start - ls + 1, it.end - ls + 1) },
                )
                val activeStyled = remember(line, colors, sortedMatches, currentMatch, ls) {
                    val base = buildAnnotatedString {
                        append(GUARD); append(line)
                        if (line.length <= 2000 && lang !== Langs.PLAIN) lang.scan(line) { s, e, t -> addStyle(colors[t], s + 1, e + 1) }
                    }
                    withMatches(base, ls, 1)
                }
                val transform = remember(activeStyled) { HighlightedLine(activeStyled) }
                Box(Modifier.offset { IntOffset((padPx - 0f).roundToInt(), yOf(active)) }) {
                    BasicTextField(
                        value = fieldValue,
                        onValueChange = { nv ->
                            val t = nv.text
                            // Backspace at the start of the line: join it to the previous one
                            if (!t.startsWith(GUARD) && t == line && fieldValue.selection == TextRange(1)) {
                                if (ls > 0) state.onEdit(TextFieldValue(text.removeRange(ls - 1, ls), TextRange(ls - 1)))
                                return@BasicTextField
                            }
                            val (clean, s, e) = stripGuard(t, nv.selection.start, nv.selection.end)
                            val comp = nv.composition?.let { c ->
                                val (_, cs, ce) = stripGuard(t, c.start, c.end)
                                TextRange(ls + cs, ls + ce)
                            }
                            state.onEdit(TextFieldValue(text.substring(0, ls) + clean + text.substring(le), TextRange(ls + s, ls + e), comp))
                        },
                        textStyle = style,
                        cursorBrush = SolidColor(Accent),
                        visualTransformation = transform,
                        onTextLayout = { fieldLayout = it },
                        modifier = Modifier.widthIn(min = contentW).height(lh)
                            .focusRequester(focus).onFocusChanged { focused = it.isFocused },
                    )
                    val l = fieldLayout
                    if (focused && completion != null && l != null && l.layoutInput.text.length == fieldValue.text.length) {
                        val r = l.getCursorRect(fieldValue.selection.start.coerceIn(0, fieldValue.text.length))
                        val provider = remember(r) { CursorPopup(r.left.toInt(), r.top.toInt(), r.bottom.toInt()) }
                        Popup(popupPositionProvider = provider, properties = PopupProperties(focusable = false)) {
                            SuggestionList(completion.items.take(120), fontSize) { state.accept(it, completion) }
                        }
                    }
                }
            }
        }
        // Scrollbar: long files need a fast way to move
        if (contentH > viewportH * 1.5f && viewportH > 0) {
            val thumbH = (viewportH * viewportH / contentH).coerceAtLeast(with(density) { 48.dp.toPx() })
            val thumbY = if (maxScroll > 0) (scrollY / maxScroll) * (viewportH - thumbH) else 0f
            Box(
                Modifier.width(14.dp).fillMaxHeight().pointerInput(maxScroll, thumbH) {
                    detectVerticalDragGestures { change, drag ->
                        change.consume()
                        val track = (viewportH - thumbH).coerceAtLeast(1f)
                        scrollY = (scrollY + drag / track * maxScroll).coerceIn(0f, maxScroll)
                    }
                },
            ) {
                Box(
                    Modifier.offset { IntOffset(0, thumbY.roundToInt()) }.padding(horizontal = 4.dp)
                        .width(6.dp).height(with(density) { thumbH.toDp() }).clip(RoundedCornerShape(3.dp))
                        .background(Accent.copy(alpha = 0.55f)),
                )
            }
        }
    }
}

private class HighlightedLine(private val styled: AnnotatedString) : androidx.compose.ui.text.input.VisualTransformation {
    override fun filter(text: AnnotatedString) = androidx.compose.ui.text.input.TransformedText(
        if (styled.text == text.text) styled else text, androidx.compose.ui.text.input.OffsetMapping.Identity,
    )
}
