package com.abyssal.files

import android.os.SystemClock
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardTab
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.*
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.math.abs

/** Above this size the editor stops colouring (typing would get slow). */
const val HIGHLIGHT_LIMIT = 300_000

private val PAIRS = mapOf('(' to ')', '[' to ']', '{' to '}', '"' to '"', '\'' to '\'', '`' to '`')
private const val CLOSERS = ")]}"
private val VOID_TAGS = setOf("area", "base", "br", "col", "embed", "hr", "img", "input", "link", "meta", "source", "track", "wbr")
private val OPEN_TAG_AT_END = Regex("<([A-Za-z][\\w:.-]*)(?:\\s[^<>]*)?>$")

data class Suggestion(val label: String, val insert: String, val kind: Char, val detail: String)

/** Suggestions replacing text[start, end). */
data class Completion(val start: Int, val end: Int, val items: List<Suggestion>)

/** Text, undo/redo history and the "smart" editing rules of the code editor. */
class CodeState(val lang: Lang) {
    /** Minecraft Bedrock autocomplete for JS/TS (Script API) and JSON (add-ons). */
    var bedrock by mutableStateOf(false)
    val bedrockActive get() = bedrock && (lang === Langs.JS || lang === Langs.TS || lang === Langs.JSON)

    var value by mutableStateOf(TextFieldValue(""))
        private set
    private val undoStack = ArrayDeque<TextFieldValue>()
    private val redoStack = ArrayDeque<TextFieldValue>()
    private var lastEdit = 0L
    var canUndo by mutableStateOf(false); private set
    var canRedo by mutableStateOf(false); private set

    /** Bumped when the editor should scroll the cursor/selection into view. */
    var reveal by mutableIntStateOf(0); private set

    /** Identifiers found in the document, for autocomplete. */
    var docWords by mutableStateOf<Set<String>>(emptySet())

    private val unit get() = " ".repeat(lang.indent)
    private val codeLike get() = lang !== Langs.PLAIN && lang !== Langs.MARKDOWN

    fun reset(text: String) {
        value = TextFieldValue(text)
        undoStack.clear(); redoStack.clear(); sync()
    }

    fun onEdit(n: TextFieldValue) {
        val o = value
        if (n.text == o.text) { value = n; return }
        val r = smart(o, n)
        record(o, r)
        value = r
    }

    private fun record(o: TextFieldValue, r: TextFieldValue) {
        val now = SystemClock.uptimeMillis()
        val boundary = abs(r.text.length - o.text.length) > 1 ||
            r.text.getOrNull(r.selection.start - 1).let { it == '\n' || it == ' ' }
        // Consecutive keystrokes are undone together
        if (now - lastEdit > 800 || boundary || undoStack.isEmpty()) {
            undoStack.addLast(o.copy(composition = null))
            // Each step is a copy of the file: big files keep fewer steps (~16 MB of history at most)
            val cap = (8_000_000 / (o.text.length + 1)).coerceIn(20, 300)
            while (undoStack.size > cap) undoStack.removeFirst()
        }
        lastEdit = now
        redoStack.clear()
        sync()
    }

    fun undo() {
        val p = undoStack.removeLastOrNull() ?: return
        redoStack.addLast(value)
        value = p; lastEdit = 0; sync(); reveal++
    }

    fun redo() {
        val p = redoStack.removeLastOrNull() ?: return
        undoStack.addLast(value)
        value = p; lastEdit = 0; sync(); reveal++
    }

    private fun sync() { canUndo = undoStack.isNotEmpty(); canRedo = redoStack.isNotEmpty() }

    /** Extra-keys bar input: goes through the same rules as the keyboard. */
    fun type(s: String) {
        val v = value
        val sel = v.selection
        onEdit(TextFieldValue(v.text.replaceRange(sel.min, sel.max, s), TextRange(sel.min + s.length)))
    }

    fun tab() = type(unit)

    fun move(delta: Int) {
        val p = (value.selection.start + delta).coerceIn(0, value.text.length)
        value = value.copy(selection = TextRange(p), composition = null)
    }

    fun select(start: Int, end: Int) {
        value = value.copy(selection = TextRange(start, end), composition = null)
        reveal++
    }

    fun replace(start: Int, end: Int, s: String, cursor: Int = start + s.length) {
        val o = value
        val n = TextFieldValue(o.text.replaceRange(start, end, s), TextRange(cursor))
        lastEdit = 0
        record(o, n)
        value = n
    }

    fun replaceAll(text: String) {
        val o = value
        val n = TextFieldValue(text, TextRange(o.selection.start.coerceAtMost(text.length)))
        lastEdit = 0
        record(o, n)
        value = n
    }

    // ---- smart editing ----

    private fun smart(o: TextFieldValue, n: TextFieldValue): TextFieldValue {
        val os = o.selection
        val ns = n.selection
        if (!os.collapsed || !ns.collapsed) return n
        val t = n.text
        val p = ns.start
        // One character typed at the cursor
        if (t.length == o.text.length + 1 && p == os.start + 1 &&
            t.regionMatches(0, o.text, 0, os.start) && t.regionMatches(p, o.text, os.start, o.text.length - os.start)
        ) {
            val c = t[p - 1]
            val next = t.getOrNull(p)
            val prev = t.getOrNull(p - 2)
            val freeAfter = next == null || next.isWhitespace() || next in CLOSERS || next == ',' || next == ';'
            return when {
                c == '\n' -> newline(t, p)
                (c in CLOSERS || c in lang.quotes) && next == c && codeLike -> TextFieldValue(o.text, TextRange(p))
                c in "([{" && freeAfter && codeLike -> insertAt(t, p, PAIRS.getValue(c).toString(), p)
                c in lang.quotes && c in PAIRS && freeAfter && codeLike && (prev == null || !prev.isLetterOrDigit()) ->
                    insertAt(t, p, c.toString(), p)
                c == '}' -> dedent(t, p)
                c == '>' && lang.html -> closeTag(t, p) ?: n
                else -> n
            }
        }
        // Backspace inside an empty pair removes both halves
        if (t.length == o.text.length - 1 && p == os.start - 1 && codeLike) {
            val del = o.text[os.start - 1]
            if (o.text.getOrNull(os.start) == PAIRS[del]) return TextFieldValue(t.removeRange(p, p + 1), ns)
        }
        return n
    }

    private fun insertAt(t: String, at: Int, s: String, cursor: Int) =
        TextFieldValue(t.substring(0, at) + s + t.substring(at), TextRange(cursor))

    private fun openTag(before: String): String? {
        if (before.endsWith("/>")) return null
        val tag = OPEN_TAG_AT_END.find(before.takeLast(300))?.groupValues?.get(1) ?: return null
        return tag.takeIf { it.lowercase() !in VOID_TAGS }
    }

    /** New line keeps the indentation, adds a level after an opener and splits "{|}". */
    private fun newline(t: String, p: Int): TextFieldValue {
        val ls = t.lastIndexOf('\n', p - 2) + 1
        val line = t.substring(ls, p - 1)
        val indent = line.takeWhile { it == ' ' || it == '\t' }
        val trimmed = line.trimEnd()
        val last = trimmed.lastOrNull()
        val tagOpen = lang.html && openTag(trimmed) != null
        val opens = last != null && (last in "{[(" || (lang.colonIndent && last == ':')) || tagOpen
        val next = t.getOrNull(p)
        val splits = opens && next != null && ((last != null && PAIRS[last] == next) || (tagOpen && t.startsWith("</", p)))
        return if (splits) {
            val ins = indent + unit + "\n" + indent
            insertAt(t, p, ins, p + indent.length + unit.length)
        } else {
            val ins = indent + if (opens) unit else ""
            insertAt(t, p, ins, p + ins.length)
        }
    }

    private fun dedent(t: String, p: Int): TextFieldValue {
        val ls = t.lastIndexOf('\n', p - 2) + 1
        val before = t.substring(ls, p - 1)
        if (before.isEmpty() || before.isNotBlank()) return TextFieldValue(t, TextRange(p))
        val rm = minOf(lang.indent, before.length)
        return TextFieldValue(t.removeRange(p - 1 - rm, p - 1), TextRange(p - rm))
    }

    /** HTML/XML: typing '>' of an opening tag adds its closing tag. */
    private fun closeTag(t: String, p: Int): TextFieldValue? {
        val tag = openTag(t.substring(0, p)) ?: return null
        if (t.startsWith("</$tag>", p)) return null
        return insertAt(t, p, "</$tag>", p)
    }

    // ---- autocomplete ----

    private fun isWord(c: Char) = c.isLetterOrDigit() || c == '_' || c in lang.wordExtra

    /** Start offset and text of the word being typed, if any. */
    fun prefix(): Pair<Int, String>? {
        val v = value
        if (!v.selection.collapsed) return null
        val p = v.selection.start
        var s = p
        while (s > 0 && isWord(v.text[s - 1])) s--
        if (s == p || v.text[s].isDigit()) return null
        if (p < v.text.length && isWord(v.text[p])) return null
        return s to v.text.substring(s, p)
    }

    fun suggestions(prefix: String): List<Suggestion> {
        val out = ArrayList<Suggestion>()
        val upper = lang.ignoreCase && prefix.all { !it.isLetter() || it.isUpperCase() }
        lang.snippets.filter { it.trigger.startsWith(prefix, true) }
            .forEach { out += Suggestion(it.trigger, it.body, 'S', it.label) }
        lang.vocabulary.filter { (w, _) -> w.startsWith(prefix, true) && w != prefix }.forEach { (w, k) ->
            val text = if (upper) w.uppercase() else w
            if (out.none { it.label == text }) out += Suggestion(text, text, k, KIND_NAMES.getValue(k))
        }
        docWords.filter { it.startsWith(prefix, true) && it != prefix }.sorted().take(20).forEach { w ->
            if (out.none { it.label.equals(w, lang.ignoreCase) }) out += Suggestion(w, w, 'W', "in this file")
        }
        return out.sortedWith(
            compareBy<Suggestion> { !it.label.startsWith(prefix) }.thenBy { it.kind == 'W' }.thenBy { it.label.length }
        ).take(8)
    }

    /** What the popup shows for the current cursor, or null. */
    fun complete(): Completion? {
        val v = value
        if (!v.selection.collapsed) return null
        val cur = v.selection.start
        // Molang inside JSON strings: q. / query. / math. / v. / t. / c.
        if (lang === Langs.JSON && Molang.inJsonString(v.text, cur)) {
            Molang.complete(v.text, cur)?.let { return it.takeIf { c -> c.items.isNotEmpty() } }
        }
        if (bedrockActive) {
            val c = if (lang === Langs.JSON) Bedrock.completeJson(v.text, cur) else Bedrock.completeJs(v.text, cur)
            if (c != null) return c.takeIf { it.items.isNotEmpty() }
        }
        val (start, word) = prefix() ?: return null
        var items = suggestions(word)
        if (bedrockActive && lang !== Langs.JSON) {
            items = (Bedrock.jsGlobals(word) + items).distinctBy { it.label }.take(10)
        }
        return Completion(start, cur, items).takeIf { items.isNotEmpty() }
    }

    /** Replaces [c]'s range; snippets keep the line's indentation and place the cursor at "$0". */
    fun accept(s: Suggestion, c: Completion) {
        val v = value
        val start = c.start.coerceIn(0, v.text.length)
        val end = c.end.coerceIn(start, v.text.length)
        val ls = v.text.lastIndexOf('\n', start - 1) + 1
        val indent = v.text.substring(ls, start).takeWhile { it == ' ' || it == '\t' }
        var text = s.insert.replace("\n", "\n$indent")
        val cur = text.indexOf("$0").let { if (it >= 0) it else text.length }
        text = text.replace("$0", "")
        replace(start, end, text, start + cur)
    }

    companion object {
        val KIND_NAMES = mapOf(
            'K' to "keyword", 'T' to "type", 'F' to "function", 'C' to "constant", 'S' to "snippet", 'W' to "word", 'P' to "property",
        )

        fun collectWords(text: String, lang: Lang): Set<String> {
            val set = HashSet<String>()
            val extra = Regex.escape(lang.wordExtra)
            val re = if (lang.wordExtra.isEmpty()) Regex("[A-Za-z_][A-Za-z0-9_]{2,}") else Regex("[A-Za-z_][A-Za-z0-9_$extra]{2,}")
            for (m in re.findAll(text.take(HIGHLIGHT_LIMIT))) {
                set += m.value
                if (set.size >= 5000) break
            }
            return set
        }
    }
}

// ───────────────────────── colours ─────────────────────────

class SyntaxColors(accent: Color) {
    private val accentLight = lerp(accent, Color.White, 0.35f)
    private val map: Map<Tok, SpanStyle> = mapOf(
        Tok.COMMENT to SpanStyle(color = Color(0xFF6E7681), fontStyle = FontStyle.Italic),
        Tok.STRING to SpanStyle(color = Color(0xFF9ECE6A)),
        Tok.NUMBER to SpanStyle(color = Color(0xFFFF9E64)),
        Tok.KEYWORD to SpanStyle(color = accentLight, fontWeight = FontWeight.Medium),
        Tok.TYPE to SpanStyle(color = Color(0xFF7DCFFF)),
        Tok.FUNCTION to SpanStyle(color = Color(0xFFE0AF68)),
        Tok.ANNOTATION to SpanStyle(color = Color(0xFFF472B6)),
        Tok.CONSTANT to SpanStyle(color = Color(0xFFFF9E64)),
        Tok.TAG to SpanStyle(color = accentLight),
        Tok.ATTR to SpanStyle(color = Color(0xFFE0AF68)),
        Tok.PROPERTY to SpanStyle(color = Color(0xFF7DCFFF)),
        Tok.HEADING to SpanStyle(color = accentLight, fontWeight = FontWeight.Bold),
        Tok.VARIABLE to SpanStyle(color = Color(0xFFF7768E)),
    )
    val matchStyle = SpanStyle(background = accent.copy(alpha = 0.28f))
    val currentStyle = SpanStyle(background = accent.copy(alpha = 0.6f))
    operator fun get(t: Tok) = map.getValue(t)
}

fun highlight(text: String, lang: Lang, c: SyntaxColors, matches: List<Int> = emptyList(), matchLen: Int = 0, current: Int = -1) =
    buildAnnotatedString {
        append(text)
        if (text.length <= HIGHLIGHT_LIMIT && lang !== Langs.PLAIN) lang.scan(text) { s, e, t -> addStyle(c[t], s, e) }
        matches.forEachIndexed { i, s -> addStyle(if (i == current) c.currentStyle else c.matchStyle, s, s + matchLen) }
    }

/** Colours per line for the read-only viewer, in one pass over the tokens. */
fun highlightLines(text: String, lang: Lang, c: SyntaxColors, limit: Int = HIGHLIGHT_LIMIT): List<AnnotatedString> {
    val toks = ArrayList<Triple<Int, Int, Tok>>()
    if (text.length <= limit && lang !== Langs.PLAIN) lang.scan(text) { s, e, t -> toks += Triple(s, e, t) }
    val out = ArrayList<AnnotatedString>()
    var first = 0
    var ls = 0
    while (ls <= text.length) {
        val le = text.indexOf('\n', ls).let { if (it < 0) text.length else it }
        while (first < toks.size && toks[first].second <= ls) first++
        out += buildAnnotatedString {
            append(text, ls, le)
            var i = first
            while (i < toks.size && toks[i].first < le) {
                val (s, e, t) = toks[i]
                addStyle(c[t], maxOf(s, ls) - ls, minOf(e, le) - ls)
                i++
            }
        }
        ls = le + 1
    }
    return out
}

fun findAll(text: String, q: String): List<Int> {
    if (q.isEmpty()) return emptyList()
    val out = ArrayList<Int>()
    var i = text.indexOf(q, 0, ignoreCase = true)
    while (i >= 0 && out.size < 5000) {
        out += i
        i = text.indexOf(q, i + q.length, ignoreCase = true)
    }
    return out
}

private class Highlighted(private val styled: AnnotatedString) : VisualTransformation {
    override fun filter(text: AnnotatedString) =
        TransformedText(if (styled.text == text.text) styled else text, OffsetMapping.Identity)
}

fun editorStyle(fontSize: Int) = TextStyle(
    fontFamily = FontFamily.Monospace, fontSize = fontSize.sp, lineHeight = (fontSize * 1.5f).sp, color = Ink,
)

// ───────────────────────── editor ─────────────────────────

/** Editable code area: line numbers, colours, autocomplete popup, find highlights. */
@Composable
fun CodeEditor(
    state: CodeState,
    fontSize: Int,
    wrap: Boolean,
    matches: List<Int>,
    matchLen: Int,
    currentMatch: Int,
    modifier: Modifier = Modifier,
) {
    val v = state.value
    val colors = remember(Pal.accent) { SyntaxColors(Pal.accent) }
    val styled = remember(v.text, colors, matches, currentMatch) { highlight(v.text, state.lang, colors, matches, matchLen, currentMatch) }
    val transform = remember(styled) { Highlighted(styled) }
    val style = remember(fontSize) { editorStyle(fontSize) }
    val vScroll = rememberScrollState()
    val hScroll = rememberScrollState()
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    var focused by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    val density = LocalDensity.current
    val padPx = with(density) { 8.dp.roundToPx() }
    val lines = remember(v.text) { v.text.count { it == '\n' } + 1 }
    val gutter = remember(lines) { (1..lines).joinToString("\n") }

    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }

    // Words of the document for autocomplete, refreshed after typing pauses
    LaunchedEffect(v.text) {
        delay(400)
        state.docWords = withContext(Dispatchers.Default) { CodeState.collectWords(v.text, state.lang) }
    }

    // Scroll the selection into view (find, undo)
    LaunchedEffect(state.reveal) {
        if (state.reveal == 0) return@LaunchedEffect
        val l = layout ?: return@LaunchedEffect
        val off = state.value.selection.start.coerceIn(0, l.layoutInput.text.length)
        val r = l.getCursorRect(off)
        vScroll.animateScrollTo((r.top.toInt() + padPx - vScroll.viewportSize / 3).coerceIn(0, vScroll.maxValue))
        if (!wrap) hScroll.animateScrollTo((r.left.toInt() - hScroll.viewportSize / 3).coerceIn(0, hScroll.maxValue))
    }

    val completion = remember(v.text, v.selection, state.docWords, state.bedrock) { state.complete() }

    BoxWithConstraints(modifier) {
        val viewportW = maxWidth
        val viewportH = maxHeight
        Row(Modifier.fillMaxSize().verticalScroll(vScroll)) {
            if (!wrap) {
                Text(
                    gutter, style = style.copy(color = Dim.copy(alpha = 0.55f), textAlign = TextAlign.End),
                    modifier = Modifier.background(Panel).padding(start = 8.dp, end = 6.dp, top = 8.dp, bottom = 8.dp),
                )
            }
            Box(Modifier.weight(1f).then(if (wrap) Modifier else Modifier.horizontalScroll(hScroll))) {
                BasicTextField(
                    value = v,
                    onValueChange = state::onEdit,
                    textStyle = style,
                    cursorBrush = SolidColor(Accent),
                    visualTransformation = transform,
                    onTextLayout = { layout = it },
                    modifier = Modifier
                        .then(if (wrap) Modifier.fillMaxWidth() else Modifier.widthIn(min = viewportW))
                        .heightIn(min = viewportH)
                        .padding(8.dp)
                        .focusRequester(focus)
                        .onFocusChanged { focused = it.isFocused },
                )
                val l = layout
                if (focused && completion != null && l != null) {
                    val r = l.getCursorRect(v.selection.start.coerceIn(0, l.layoutInput.text.length))
                    val provider = remember(r) {
                        CursorPopup(r.left.toInt() + padPx, r.top.toInt() + padPx, r.bottom.toInt() + padPx)
                    }
                    Popup(popupPositionProvider = provider, properties = PopupProperties(focusable = false)) {
                        SuggestionList(completion.items.take(120), fontSize) { state.accept(it, completion) }
                    }
                }
            }
        }
    }
}

internal class CursorPopup(val x: Int, val top: Int, val bottom: Int) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection, popupContentSize: IntSize,
    ): IntOffset {
        val px = (anchorBounds.left + x).coerceIn(0, maxOf(0, windowSize.width - popupContentSize.width))
        val below = anchorBounds.top + bottom + 4
        val py = if (below + popupContentSize.height <= windowSize.height) below
        else anchorBounds.top + top - popupContentSize.height - 4
        return IntOffset(px, py.coerceAtLeast(0))
    }
}

private val KIND_COLORS = mapOf(
    'K' to Color(0xFFC792EA), 'T' to Color(0xFF7DCFFF), 'F' to Color(0xFFE0AF68),
    'C' to Color(0xFFFF9E64), 'S' to Color(0xFF9ECE6A), 'W' to Color(0xFF8B929B), 'P' to Color(0xFF7DCFFF),
)

/** The popup follows the editor's text size, but never gets smaller than ~75% of the normal size. */
@Composable
internal fun SuggestionList(items: List<Suggestion>, fontSize: Int, onPick: (Suggestion) -> Unit) {
    val k = (fontSize / 14f).coerceIn(0.75f, 1.2f)
    Column(
        Modifier.width((290 * k).dp).heightIn(max = (250 * k).dp).clip(RoundedCornerShape(8.dp)).background(Panel2)
            .border(1.dp, Accent.copy(alpha = 0.35f), RoundedCornerShape(8.dp)).verticalScroll(rememberScrollState())
            .padding(vertical = 3.dp),
    ) {
        items.forEach { s ->
            Row(
                Modifier.fillMaxWidth().clickable { onPick(s) }.padding(horizontal = (7 * k).dp, vertical = (6 * k).dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val kc = KIND_COLORS.getValue(s.kind)
                Box(
                    Modifier.size((17 * k).dp).clip(RoundedCornerShape(4.dp)).background(kc.copy(alpha = 0.2f)),
                    contentAlignment = Alignment.Center,
                ) { Text(s.kind.toString(), color = kc, fontSize = (10 * k).sp, fontWeight = FontWeight.Bold) }
                Spacer(Modifier.width((7 * k).dp))
                Text(
                    s.label, color = Ink, fontSize = (13 * k).sp, fontFamily = FontFamily.Monospace,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                )
                Text(s.detail, color = Dim, fontSize = (10 * k).sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(start = 6.dp).widthIn(max = (105 * k).dp))
            }
        }
    }
}

/** Keys that are awkward on a phone keyboard, shown above it while editing. */
@Composable
fun ExtraKeys(state: CodeState) {
    Row(
        Modifier.fillMaxWidth().height(42.dp).background(Panel).horizontalScroll(rememberScrollState()),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        KeyIcon(Icons.AutoMirrored.Filled.KeyboardTab, "Tab") { state.tab() }
        KeyIcon(Icons.AutoMirrored.Filled.Undo, "Undo", state.canUndo) { state.undo() }
        KeyIcon(Icons.AutoMirrored.Filled.Redo, "Redo", state.canRedo) { state.redo() }
        Key("◀") { state.move(-1) }
        Key("▶") { state.move(1) }
        "{ } ( ) [ ] ; : = \" ' < > / \\ | & ! # $ _ - + * % ` ? , . @ ~ ^".split(' ').forEach { k -> Key(k) { state.type(k) } }
    }
}

@Composable
private fun Key(label: String, onClick: () -> Unit) {
    Box(Modifier.width(38.dp).fillMaxHeight().clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Text(label, color = Ink, fontSize = 16.sp, fontFamily = FontFamily.Monospace)
    }
}

@Composable
private fun KeyIcon(icon: ImageVector, desc: String, enabled: Boolean = true, onClick: () -> Unit) {
    Box(
        Modifier.width(42.dp).fillMaxHeight().clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, desc, tint = if (enabled) Accent else Dim.copy(alpha = 0.4f), modifier = Modifier.size(20.dp)) }
}

/** Read-only view: virtualised lines, so even large files open instantly. */
@Composable
fun CodeViewer(text: String, lang: Lang, fontSize: Int, modifier: Modifier = Modifier) {
    val colors = remember(Pal.accent) { SyntaxColors(Pal.accent) }
    val plain = remember(text) { text.split('\n').map { AnnotatedString(it) } }
    val lines by produceState(plain, text, lang, colors) {
        value = withContext(Dispatchers.Default) { highlightLines(text, lang, colors) }
    }
    val style = remember(fontSize) { editorStyle(fontSize) }
    val numWidth = with(LocalDensity.current) { (lines.size.toString().length * fontSize * 0.62f + 14).sp.toDp() }
    if (text.isEmpty()) {
        Box(modifier.fillMaxSize(), Alignment.Center) { Text("Empty file — tap ✎ to write", color = Dim, fontSize = 13.sp) }
        return
    }
    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(vertical = 8.dp)) {
        itemsIndexed(lines) { i, line ->
            Row(Modifier.padding(end = 10.dp)) {
                Text(
                    "${i + 1}", style = style.copy(color = Dim.copy(alpha = 0.55f), textAlign = TextAlign.End),
                    modifier = Modifier.width(numWidth).padding(end = 8.dp),
                )
                Text(line, style = style)
            }
        }
    }
}
