package com.abyssal.files

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.View
import android.webkit.JavascriptInterface
import android.webkit.ValueCallback
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.json.JSONTokener

/**
 * Editing through Ace (ace.c9.io, BSD licence), the engine Acode uses, running in a WebView. Ace keeps the text as an
 * array of lines, colours it in the background starting from the edited line and draws only the rows on screen, so
 * typing stays fast in big files. The app keeps a copy of the text, updated from Ace's change events, for its own
 * autocomplete (Bedrock, Molang, snippets).
 *
 * Line breaks are '\n' inside the editor; files that used "\r\n" get them back when the text is read with [withText].
 */
class AceState(val lang: Lang, initial: String, val fileName: String, baseDirty: Boolean) : EditorKeys {
    private val crlf = initial.contains("\r\n")
    private val start = if (crlf) initial.replace("\r\n", "\n") else initial

    /** Same text as Ace. Main thread only. */
    val text = StringBuilder(start)

    /** Bumped on every change. */
    var version by mutableIntStateOf(0); private set
    var ready by mutableStateOf(false); private set

    /** The WebView couldn't be created (no WebView installed): the built-in editor is used instead. */
    var failed by mutableStateOf(false); internal set

    private var aceDirty by mutableStateOf(false)
    private var pendingDirty by mutableStateOf(baseDirty)
    val dirty get() = aceDirty || pendingDirty
    override var canUndo by mutableStateOf(false); private set
    override var canRedo by mutableStateOf(false); private set
    var row by mutableIntStateOf(0); private set
    var col by mutableIntStateOf(0); private set
    var cursor by mutableIntStateOf(0); private set
    var collapsed by mutableStateOf(true); private set

    /** Caret on screen in pixels, relative to the editor: x, top, bottom. */
    var caret by mutableStateOf<Triple<Int, Int, Int>?>(null); private set

    /** Ace's "Find" (touch menu or Ctrl+F) opens the app's find bar. */
    var onFind: () -> Unit = {}

    private var view: WebView? = null
    private val waiting = HashMap<Int, (String) -> Unit>()
    private var nextId = 0
    private var options = JSONObject()
    private var themeCss = ""

    private fun js(code: String, cb: ((String?) -> Unit)? = null) {
        val v = view ?: return
        if (ready) v.evaluateJavascript(code, cb?.let { f -> ValueCallback<String> { f(it) } })
    }

    override fun tab() = js("abyssal.tab()")
    override fun undo() = js("abyssal.undo()")
    override fun redo() = js("abyssal.redo()")
    override fun type(s: String) = js("abyssal.insert(${q(s)})")
    override fun move(delta: Int) = js("abyssal.move($delta)")
    fun focus() = js("abyssal.focus()")

    /** Replaces [start, end) and puts the caret at start + [caret]. */
    fun replace(start: Int, end: Int, s: String, caret: Int) = js("abyssal.replace($start,$end,${q(s)},$caret)")

    /** The whole text, as it will be saved. */
    fun withText(cb: (String) -> Unit) {
        if (!ready) { cb(restore(text.toString())); return }
        val id = ++nextId
        waiting[id] = cb
        js("abyssal.sendText($id)")
    }

    fun markClean() { pendingDirty = false; js("abyssal.markClean()") }

    /** Find & replace; [onCount] gets "current/total". */
    fun find(query: String, backwards: Boolean, next: Boolean, onCount: (String) -> Unit) =
        js("abyssal.find(${q(query)},$backwards,$next)") { onCount(str(it) ?: "0/0") }

    fun replaceOne(query: String, with: String, onCount: (String) -> Unit) =
        js("abyssal.replace1(${q(query)},${q(with)})") { onCount(str(it) ?: "0/0") }

    fun replaceAll(query: String, with: String, onDone: (Int) -> Unit) =
        js("abyssal.replaceAll(${q(query)},${q(with)})") { onDone(it?.toIntOrNull() ?: 0) }

    fun setFontSize(px: Float) = js("abyssal.fontSize($px)")
    fun setWrap(on: Boolean) = js("abyssal.wrap($on)")
    fun setTheme(css: String) { themeCss = css; js("abyssal.theme(${q(css)})") }

    private fun restore(s: String) = if (crlf) s.replace("\n", "\r\n") else s

    @SuppressLint("SetJavaScriptEnabled")
    internal fun attach(v: WebView, opts: JSONObject, css: String) {
        view = v
        options = opts
        themeCss = css
        v.settings.javaScriptEnabled = true
        v.settings.textZoom = 100
        v.settings.setSupportZoom(false)
        v.isVerticalScrollBarEnabled = false
        v.isHorizontalScrollBarEnabled = false
        v.overScrollMode = View.OVER_SCROLL_NEVER
        // Only our own page: anything else (a link in a file…) is not opened in the editor
        v.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) = true
        }
        v.addJavascriptInterface(Bridge(v.context.applicationContext), "AbyssalBridge")
        v.loadUrl("file:///android_asset/ace/editor.html")
    }

    internal fun detach() {
        view?.let { it.removeJavascriptInterface("AbyssalBridge"); it.destroy() }
        view = null
        ready = false
    }

    /** Called by the page. Runs on a WebView thread, so everything is handed to the main thread in order. */
    private inner class Bridge(private val ctx: Context) {
        private val main = Handler(Looper.getMainLooper())
        private val clipboard get() = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager

        @JavascriptInterface fun text(): String = start

        @JavascriptInterface fun loaded() {
            main.post {
                val v = view ?: return@post
                v.evaluateJavascript("abyssal.theme(${q(themeCss)});abyssal.init(${q(options.toString())})", null)
            }
        }

        @JavascriptInterface fun ready() { main.post { ready = true } }

        @JavascriptInterface fun delta(insert: Boolean, at: Int, s: String) {
            main.post {
                val i = at.coerceIn(0, text.length)
                if (insert) text.insert(i, s) else text.delete(i, (i + s.length).coerceAtMost(text.length))
                version++
            }
        }

        @JavascriptInterface fun cursor(r: Int, c: Int, index: Int, empty: Boolean) {
            main.post { row = r; col = c; cursor = index; collapsed = empty }
        }

        @JavascriptInterface fun caret(x: Int, top: Int, bottom: Int) { main.post { caret = Triple(x, top, bottom) } }

        @JavascriptInterface fun undoState(undo: Boolean, redo: Boolean) { main.post { canUndo = undo; canRedo = redo } }

        @JavascriptInterface fun dirty(d: Boolean) { main.post { aceDirty = d } }

        @JavascriptInterface fun got(id: Int, s: String) { main.post { waiting.remove(id)?.invoke(restore(s)) } }

        @JavascriptInterface fun find() { main.post { onFind() } }

        @JavascriptInterface fun clipboardGet(): String =
            runCatching { clipboard.primaryClip?.getItemAt(0)?.coerceToText(ctx)?.toString() }.getOrNull() ?: ""

        @JavascriptInterface fun clipboardSet(s: String) {
            main.post { runCatching { clipboard.setPrimaryClip(ClipData.newPlainText("text", s)) } }
        }
    }

    companion object {
        fun q(s: String): String = JSONObject.quote(s)
        private fun str(json: String?): String? = runCatching { JSONTokener(json ?: return null).nextValue() as? String }.getOrNull()
    }
}

private fun Color.css() = "rgba(${(red * 255).toInt()},${(green * 255).toInt()},${(blue * 255).toInt()},$alpha)"

/** Ace theme in the app's colours (same token colours as [SyntaxColors]). */
fun aceTheme(accent: Color): String {
    val light = lerp(accent, Color.White, 0.35f).css()
    val a = accent.css()
    val t = ".ace-abyssal"
    return """
        html, body { background: ${Bg.css()}; }
        $t { background: ${Bg.css()}; color: ${Ink.css()}; }
        $t .ace_gutter { background: ${Panel.css()}; color: ${Dim.copy(alpha = 0.6f).css()}; }
        $t .ace_gutter-active-line { background: ${accent.copy(alpha = 0.12f).css()}; color: ${Ink.css()}; }
        $t .ace_cursor { color: $a; border-left-width: 2px; }
        $t .ace_marker-layer .ace_selection { background: ${accent.copy(alpha = 0.32f).css()}; }
        $t.ace_multiselect .ace_selection.ace_start { box-shadow: 0 0 3px 0 ${Bg.css()}; }
        $t .ace_marker-layer .ace_active-line { background: rgba(255,255,255,0.04); }
        $t .ace_marker-layer .ace_step { background: ${accent.copy(alpha = 0.2f).css()}; }
        $t .ace_marker-layer .ace_bracket { margin: -1px 0 0 -1px; border: 1px solid ${accent.copy(alpha = 0.6f).css()}; }
        $t .ace_marker-layer .ace_selected-word { border: 1px solid ${accent.copy(alpha = 0.45f).css()}; }
        $t .ace_indent-guide { background: none; box-shadow: inset -1px 0 0 rgba(255,255,255,0.07); }
        $t .ace_indent-guide-active { box-shadow: inset -1px 0 0 ${accent.copy(alpha = 0.45f).css()}; }
        $t .ace_fold { background-color: ${accent.copy(alpha = 0.5f).css()}; border-color: ${Ink.css()}; }
        $t .ace_invisible { color: rgba(255,255,255,0.15); }
        $t .ace_comment { color: #6E7681; font-style: italic; }
        $t .ace_string, $t .ace_string.ace_regexp { color: #9ECE6A; }
        $t .ace_constant.ace_numeric, $t .ace_constant.ace_language, $t .ace_constant.ace_character,
        $t .ace_constant.ace_other, $t .ace_constant.ace_buildin { color: #FF9E64; }
        $t .ace_keyword, $t .ace_storage, $t .ace_variable.ace_language { color: $light; }
        $t .ace_keyword.ace_operator { color: ${Ink.copy(alpha = 0.8f).css()}; }
        $t .ace_support.ace_type, $t .ace_support.ace_class, $t .ace_entity.ace_name.ace_type,
        $t .ace_storage.ace_type.ace_class, $t .ace_variable, $t .ace_support.ace_variable { color: #7DCFFF; }
        $t .ace_variable.ace_parameter { color: ${Ink.css()}; }
        $t .ace_entity.ace_name.ace_function, $t .ace_support.ace_function { color: #E0AF68; }
        $t .ace_entity.ace_other.ace_attribute-name { color: #E0AF68; }
        $t .ace_meta.ace_tag, $t .ace_entity.ace_name.ace_tag { color: $light; }
        $t .ace_markup.ace_heading { color: $light; font-weight: bold; }
        $t .ace_meta.ace_annotation, $t .ace_storage.ace_type.ace_annotation { color: #F472B6; }
        $t .ace_invalid { color: ${Danger.css()}; }
        $t .ace_paren, $t .ace_punctuation { color: ${Ink.copy(alpha = 0.75f).css()}; }
        .ace_mobile-menu { background: ${Panel2.css()} !important; color: ${Ink.css()} !important;
          border: 1px solid ${accent.copy(alpha = 0.35f).css()} !important; box-shadow: 0 2px 8px rgba(0,0,0,0.5) !important; }
        .ace_mobile-button:hover { background: ${accent.copy(alpha = 0.25f).css()} !important; }
        .ace_scrollbar::-webkit-scrollbar { width: 6px; height: 6px; }
        .ace_scrollbar::-webkit-scrollbar-thumb { background: rgba(255,255,255,0.18); border-radius: 3px; }
    """.trimIndent()
}

/** Ace editor with the app's autocomplete popup on top. [code] provides the suggestions (Bedrock mode, words…). */
@Composable
fun AceEditor(
    state: AceState,
    code: CodeState,
    fontSize: Int,
    wrap: Boolean,
    showSuggestions: Boolean,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val px = fontSize * density.fontScale
    val themeKey = if (Pal.rgb) Color.Unspecified else Pal.accent

    LaunchedEffect(px) { state.setFontSize(px) }
    LaunchedEffect(wrap) { state.setWrap(wrap) }
    LaunchedEffect(themeKey, state.ready) { if (state.ready) state.setTheme(aceTheme(Pal.accent)) }

    // Words of the document for autocomplete, refreshed after typing pauses
    LaunchedEffect(state.version) {
        delay(if (state.version == 0) 0 else 1200)
        val t = state.text.toString()
        code.docWords = withContext(Dispatchers.Default) { CodeState.collectWords(t, state.lang) }
    }

    var computed by remember { mutableStateOf<Pair<Long, Completion?>?>(null) }
    val key = state.version.toLong() shl 32 or state.cursor.toLong()
    LaunchedEffect(key, state.collapsed, code.docWords, code.bedrock) {
        if (!state.collapsed || state.version == 0) { computed = null; return@LaunchedEffect }
        val t = state.text.toString()
        val c = state.cursor.coerceIn(0, t.length)
        computed = key to withContext(Dispatchers.Default) {
            runCatching { code.complete(TextFieldValue(t, TextRange(c))) }.getOrNull()
        }
    }
    val completion = computed?.takeIf { it.first == key }?.second

    Box(modifier) {
        AndroidView(
            factory = { ctx ->
                runCatching {
                    WebView(ctx).also { v ->
                        v.setBackgroundColor(Bg.toArgb())
                        val opts = JSONObject()
                            .put("name", state.fileName).put("fontSize", px.toDouble()).put("wrap", wrap)
                            .put("indent", state.lang.indent).put("index", 0)
                        state.attach(v, opts, aceTheme(Pal.accent))
                    }
                }.getOrElse { state.failed = true; View(ctx) }
            },
            modifier = Modifier.fillMaxSize(),
            onRelease = { state.detach() },
        )
        val caret = state.caret
        if (showSuggestions && completion != null && caret != null) {
            val provider = remember(caret) { CursorPopup(caret.first, caret.second, caret.third) }
            Popup(popupPositionProvider = provider, properties = PopupProperties(focusable = false)) {
                SuggestionList(completion.items.take(120), fontSize) { s ->
                    val ins = CodeState.expand(state.text, s, completion)
                    state.replace(ins.start, ins.end, ins.text, ins.caret)
                }
            }
        }
    }
}
