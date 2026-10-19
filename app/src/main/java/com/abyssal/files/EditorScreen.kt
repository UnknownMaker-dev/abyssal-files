package com.abyssal.files

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private const val MAX_VIEW = 4L * 1024 * 1024
private const val MAX_EDIT = 1L * 1024 * 1024

private sealed interface Doc {
    data object Loading : Doc
    data object Binary : Doc
    data class Failed(val msg: String) : Doc
    data class Ready(val text: String, val readOnly: Boolean, val truncated: Boolean) : Doc
}

private fun load(f: File, force: Boolean): Doc = try {
    when {
        !f.canRead() -> Doc.Failed("Can't read this file")
        !force && FileOps.looksBinary(f) -> Doc.Binary
        else -> {
            val len = f.length()
            val text = if (len <= MAX_VIEW) f.readText() else f.inputStream().use { s ->
                val b = ByteArray(MAX_VIEW.toInt())
                var off = 0
                while (off < b.size) {
                    val r = s.read(b, off, b.size - off)
                    if (r < 0) break
                    off += r
                }
                String(b, 0, off)
            }
            Doc.Ready(text, readOnly = len > MAX_EDIT || !f.canWrite(), truncated = len > MAX_VIEW)
        }
    }
} catch (e: Exception) {
    Doc.Failed(e.message ?: "Can't open file")
}

/** Built-in code editor / text viewer. "Open with" hands the file to another app instead. */
@Composable
fun EditorScreen(
    req: EditRequest,
    onClose: () -> Unit,
    onSaved: () -> Unit,
    onOpenWith: (File) -> Unit,
    onMsg: (String) -> Unit,
) {
    val ctx = LocalContext.current
    val prefs = remember { Prefs(ctx) }
    val file = remember(req.path) { File(req.path) }
    val lang = remember(req.path) { Langs.forFile(file.name) }
    val code = remember(req.path) { CodeState(lang) }
    val scope = rememberCoroutineScope()
    var force by remember { mutableStateOf(false) }
    var doc by remember { mutableStateOf<Doc>(Doc.Loading) }
    var original by remember { mutableStateOf("") }
    var editing by remember { mutableStateOf(false) }
    var confirmExit by remember { mutableStateOf(false) }
    var modified by remember { mutableLongStateOf(file.lastModified()) }
    var fontSize by remember { mutableIntStateOf(prefs.editorFontSize) }
    var wrap by remember { mutableStateOf(prefs.editorWrap) }
    var bedrock by remember { mutableStateOf(prefs.bedrockMode) }
    SideEffect { code.bedrock = bedrock }
    var menu by remember { mutableStateOf(false) }
    var finding by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var replacement by remember { mutableStateOf("") }
    var current by remember { mutableIntStateOf(0) }

    LaunchedEffect(req.path, force) {
        val d = withContext(Dispatchers.IO) { load(file, force) }
        if (d is Doc.Ready) {
            original = d.text
            code.reset(d.text)
            editing = req.edit && !d.readOnly && !req.readOnly
        }
        doc = d
    }

    val text = code.value.text
    val dirty = editing && text != original
    val matches = remember(text, query, finding) { if (finding) findAll(text, query) else emptyList() }
    if (current >= matches.size) current = 0

    fun save(close: Boolean) {
        val t = code.value.text
        scope.launch {
            val r = withContext(Dispatchers.IO) { runCatching { file.writeText(t) } }
            r.onSuccess {
                original = t
                modified = file.lastModified()
                onSaved()
                onMsg("Saved")
                if (close) onClose()
            }.onFailure { onMsg("Failed: ${it.message}") }
        }
    }

    fun goTo(i: Int) {
        if (matches.isEmpty()) return
        current = (i + matches.size) % matches.size
        code.select(matches[current], matches[current] + query.length)
    }

    BackHandler {
        when {
            finding -> finding = false
            dirty -> confirmExit = true
            else -> onClose()
        }
    }

    Column(Modifier.fillMaxSize().background(Bg).systemBarsPadding().imePadding()) {
        // ── top bar
        Row(Modifier.fillMaxWidth().height(52.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton({ if (dirty) confirmExit = true else onClose() }) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Ink)
            }
            PixIcon(IconMap.forFile(file.name, file.extension.lowercase()), 22.dp)
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    file.name + if (dirty) "  •" else "", fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                    color = Ink, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                Text(
                    "${FileOps.fmtSize(file.length())} · ${FileOps.fmtDate(modified)}",
                    fontSize = 11.sp, color = Dim, maxLines = 1,
                )
            }
            val d = doc
            if (d is Doc.Ready && !d.readOnly && !req.readOnly) {
                IconButton({ editing = true; finding = !finding }) { Icon(Icons.Outlined.Search, "Find", tint = Ink) }
                if (editing) {
                    IconButton({ save(close = false) }, enabled = dirty) {
                        Icon(Icons.Outlined.Save, "Save", tint = if (dirty) Accent else Dim)
                    }
                } else {
                    IconButton({ editing = true }) { Icon(Icons.Outlined.Edit, "Edit", tint = Ink) }
                }
            }
            Box {
                IconButton({ menu = true }) { Icon(Icons.Default.MoreVert, "More", tint = Ink) }
                DropdownMenu(menu, { menu = false }) {
                    DropdownMenuItem(
                        text = { Text("Open with…", fontSize = 14.sp) },
                        leadingIcon = { Icon(Icons.AutoMirrored.Filled.OpenInNew, null) },
                        onClick = { menu = false; onOpenWith(file) },
                    )
                    DropdownMenuItem(
                        text = {
                            Column {
                                Text("Bedrock mode", fontSize = 14.sp)
                                Text("Minecraft JS API + add-on JSON", fontSize = 11.sp, color = Dim)
                            }
                        },
                        leadingIcon = { PixIcon("mcworld", 22.dp) },
                        trailingIcon = { Switch(bedrock, null, Modifier.scale(0.75f)) },
                        onClick = { bedrock = !bedrock; prefs.bedrockMode = bedrock },
                    )
                    DropdownMenuItem(
                        text = { Text((if (wrap) "✓  " else "     ") + "Word wrap", fontSize = 14.sp) },
                        onClick = { wrap = !wrap; prefs.editorWrap = wrap; menu = false },
                    )
                    DropdownMenuItem(
                        text = { Text("     Bigger text", fontSize = 14.sp) },
                        onClick = { fontSize = (fontSize + 1).coerceAtMost(24); prefs.editorFontSize = fontSize },
                    )
                    DropdownMenuItem(
                        text = { Text("     Smaller text", fontSize = 14.sp) },
                        onClick = { fontSize = (fontSize - 1).coerceAtLeast(9); prefs.editorFontSize = fontSize },
                    )
                }
            }
        }

        // ── find & replace
        if (finding && editing) {
            Column(Modifier.fillMaxWidth().background(Panel).padding(horizontal = 8.dp, vertical = 6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SmallField(query, "Find", Modifier.weight(1f), autoFocus = true) { query = it; current = 0 }
                    Text(
                        if (matches.isEmpty()) "0/0" else "${current + 1}/${matches.size}",
                        color = Dim, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 6.dp),
                    )
                    IconButton({ goTo(current - 1) }, Modifier.size(36.dp)) { Icon(Icons.Default.KeyboardArrowUp, "Previous", tint = Ink) }
                    IconButton({ goTo(current + 1) }, Modifier.size(36.dp)) { Icon(Icons.Default.KeyboardArrowDown, "Next", tint = Ink) }
                    IconButton({ finding = false }, Modifier.size(36.dp)) { Icon(Icons.Default.Close, "Close find", tint = Dim) }
                }
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SmallField(replacement, "Replace with", Modifier.weight(1f)) { replacement = it }
                    TextButton({
                        if (matches.isNotEmpty()) {
                            val s = matches[current]
                            code.replace(s, s + query.length, replacement)
                        }
                    }, contentPadding = PaddingValues(horizontal = 8.dp)) { Text("Replace", fontSize = 13.sp) }
                    TextButton({
                        if (matches.isNotEmpty()) {
                            val sb = StringBuilder(text)
                            matches.asReversed().forEach { sb.replace(it, it + query.length, replacement) }
                            code.replaceAll(sb.toString())
                            onMsg("Replaced ${matches.size}")
                        }
                    }, contentPadding = PaddingValues(horizontal = 8.dp)) { Text("All", fontSize = 13.sp) }
                }
            }
        }

        // ── content
        Box(Modifier.weight(1f)) {
            when (val d = doc) {
                Doc.Loading -> Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator(color = Accent) }
                is Doc.Failed -> Box(Modifier.fillMaxSize().padding(24.dp), Alignment.Center) {
                    Text(d.msg, color = Dim, textAlign = TextAlign.Center)
                }
                Doc.Binary -> Column(
                    Modifier.fillMaxSize().padding(24.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text("This looks like a binary file.", color = Ink, fontSize = 15.sp)
                    Spacer(Modifier.height(16.dp))
                    Button({ force = true }) { Text("Open as text anyway") }
                    TextButton({ onOpenWith(file) }) { Text("Open with another app") }
                }
                is Doc.Ready -> Column(Modifier.fillMaxSize()) {
                    if (d.truncated || d.readOnly || req.readOnly || req.note != null) {
                        Text(
                            when {
                                req.note != null -> req.note
                                req.readOnly -> "Opened from an archive · read-only"
                                d.truncated -> "Showing the first 4 MB · read-only"
                                !file.canWrite() -> "Read-only file"
                                else -> "Large file (over 1 MB) · read-only"
                            },
                            color = Dim, fontSize = 11.sp,
                            modifier = Modifier.fillMaxWidth().background(Panel).padding(horizontal = 12.dp, vertical = 4.dp),
                        )
                    }
                    if (editing) {
                        CodeEditor(
                            code, fontSize, wrap, matches, query.length, if (finding) current else -1,
                            Modifier.weight(1f).fillMaxWidth(),
                        )
                    } else {
                        CodeViewer(original, lang, fontSize, Modifier.weight(1f))
                    }
                }
            }
        }

        // ── status + extra keys
        val d = doc
        if (d is Doc.Ready) {
            val sel = code.value.selection.start
            val status = if (editing) {
                val before = text.substring(0, sel.coerceAtMost(text.length))
                val ln = before.count { it == '\n' } + 1
                val col = sel - before.lastIndexOf('\n')
                "${lang.name} · Ln $ln, Col $col · ${lang.indent} spaces" + if (code.bedrockActive) " · Bedrock" else ""
            } else {
                "${lang.name} · ${original.count { it == '\n' } + 1} lines" + if (d.readOnly) " · read-only" else ""
            }
            Text(
                status, color = Dim, fontSize = 11.sp,
                modifier = Modifier.fillMaxWidth().background(Bg).padding(horizontal = 12.dp, vertical = 3.dp),
            )
            if (editing) ExtraKeys(code)
        }
    }

    if (confirmExit) {
        AlertDialog(
            onDismissRequest = { confirmExit = false },
            containerColor = Panel2,
            title = { Text("Unsaved changes", fontSize = 16.sp) },
            text = { Text("Save your changes to ${file.name}?", color = Dim, fontSize = 13.sp) },
            confirmButton = { TextButton({ confirmExit = false; save(close = true) }) { Text("Save") } },
            dismissButton = {
                TextButton({ confirmExit = false; onClose() }) { Text("Discard", color = Danger) }
            },
        )
    }
}

@Composable
private fun SmallField(value: String, hint: String, modifier: Modifier, autoFocus: Boolean = false, onChange: (String) -> Unit) {
    val focus = remember { FocusRequester() }
    if (autoFocus) LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    Box(
        modifier.height(36.dp).border(1.dp, Dim.copy(alpha = 0.4f), RoundedCornerShape(6.dp)).padding(horizontal = 8.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        if (value.isEmpty()) Text(hint, color = Dim, fontSize = 13.sp)
        BasicTextField(
            value, onChange, singleLine = true,
            textStyle = TextStyle(color = Ink, fontSize = 13.sp),
            cursorBrush = SolidColor(Accent),
            modifier = Modifier.fillMaxWidth().focusRequester(focus),
        )
    }
}
