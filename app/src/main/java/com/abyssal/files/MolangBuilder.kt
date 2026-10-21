@file:OptIn(ExperimentalMaterial3Api::class)

package com.abyssal.files

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/** No-code Molang: conditions joined with AND/OR, turned into an expression to copy or insert. */
object MolangBuild {
    enum class Subject { QUERY, VARIABLE, CUSTOM }
    enum class Result(val label: String) { CONDITION("Condition"), TERNARY("If / else"), ASSIGN("Set variable") }

    val OPS = listOf("" to "is true", "==" to "=", "!=" to "≠", ">" to ">", ">=" to "≥", "<" to "<", "<=" to "≤")

    data class Cond(
        val join: String = "&&",
        val not: Boolean = false,
        val subject: Subject = Subject.QUERY,
        /** Query name, variable name or the custom expression. */
        val name: String = "is_on_ground",
        val args: List<String> = emptyList(),
        val op: String = "",
        val value: String = "1",
    )

    private val NAMESPACED = Regex("^(?:q|query|v|variable|t|temp|c|context|math)\\.", RegexOption.IGNORE_CASE)
    private val NUMBER = Regex("^-?(?:\\d+\\.?\\d*|\\.\\d+)$")

    /** Numbers, Molang names and quoted text are used as typed; anything else becomes a 'string'. */
    fun literal(raw: String): String {
        val v = raw.trim()
        return when {
            v.isEmpty() -> "''"
            NUMBER.matches(v) || NAMESPACED.containsMatchIn(v) || v.startsWith("'") || v.startsWith("(") || v == "true" || v == "false" -> v
            else -> "'" + v.replace("'", "") + "'"
        }
    }

    fun condition(c: Cond, short: Boolean): String {
        val base = when (c.subject) {
            Subject.QUERY -> (if (short) "q." else "query.") + c.name.trim() +
                (if (c.args.any { it.isNotBlank() }) c.args.filter { it.isNotBlank() }.joinToString(", ", "(", ")") { literal(it) } else "")
            Subject.VARIABLE -> (if (short) "v." else "variable.") + c.name.trim().removePrefix("v.").removePrefix("variable.")
            Subject.CUSTOM -> c.name.trim().ifEmpty { "0" }
        }
        val needsParens = c.subject == Subject.CUSTOM && (c.op.isNotEmpty() || c.not) && base.any { it == ' ' }
        val left = if (needsParens) "($base)" else base
        val expr = if (c.op.isEmpty()) left else "$left ${c.op} ${literal(c.value)}"
        return when {
            !c.not -> expr
            c.op.isEmpty() -> "!$left"
            else -> "!($expr)"
        }
    }

    fun build(conds: List<Cond>, short: Boolean, result: Result, then: String, otherwise: String, variable: String): String {
        if (conds.isEmpty()) return ""
        val sb = StringBuilder(condition(conds[0], short))
        for (c in conds.drop(1)) sb.append(if (c.join == "||") " || " else " && ").append(condition(c, short))
        val cond = sb.toString()
        val multi = conds.size > 1
        return when (result) {
            Result.CONDITION -> cond
            Result.TERNARY -> (if (multi) "($cond)" else cond) + " ? ${literal(then)} : ${literal(otherwise)}"
            Result.ASSIGN -> (if (short) "v." else "variable.") +
                variable.trim().removePrefix("v.").removePrefix("variable.").ifEmpty { "result" } + " = $cond;"
        }
    }

    /** Argument names for the query's fields ("…" = free text). */
    fun argNames(fn: MolangFn?): List<String> = when (fn?.args) {
        null -> emptyList()
        "…" -> listOf("arguments, comma separated")
        else -> fn.args.split(",").map { it.trim() }.filter { it.isNotEmpty() }
    }
}

/** Full-screen query builder. [onInsert] null = copy only (e.g. opened from Settings). */
@Composable
fun MolangBuilderDialog(onDismiss: () -> Unit, onInsert: ((String) -> Unit)?) {
    val conds = remember { mutableStateListOf(MolangBuild.Cond()) }
    var short by remember { mutableStateOf(true) }
    var result by remember { mutableStateOf(MolangBuild.Result.CONDITION) }
    var then by remember { mutableStateOf("1") }
    var otherwise by remember { mutableStateOf("0") }
    var variable by remember { mutableStateOf("result") }
    var picking by remember { mutableStateOf(-1) }
    val clipboard = LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }
    // Free-text args typed for "…" queries are split on commas
    val effective = conds.map { c ->
        if (c.subject == MolangBuild.Subject.QUERY && Molang.query(c.name)?.args == "…") c.copy(args = c.args.firstOrNull().orEmpty().split(",")) else c
    }
    val output = MolangBuild.build(effective, short, result, then, otherwise, variable)

    Dialog(onDismiss, DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Column(Modifier.fillMaxSize().background(Bg).systemBarsPadding().imePadding()) {
            Row(Modifier.fillMaxWidth().height(52.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onDismiss) { Icon(Icons.Default.Close, "Close", tint = Ink) }
                Column(Modifier.weight(1f)) {
                    Text("Molang query builder", fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = Ink)
                    Text("Build it with blocks, then copy or insert", fontSize = 11.sp, color = Dim)
                }
            }
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 12.dp)) {
                conds.forEachIndexed { i, c ->
                    CondCard(i, c, conds, onPick = { picking = i })
                    Spacer(Modifier.height(10.dp))
                }
                OutlinedButton({ conds += MolangBuild.Cond() }) {
                    Icon(Icons.Default.Add, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Add condition")
                }
                Spacer(Modifier.height(16.dp))
                Text("Result", color = Dim, fontSize = 12.sp)
                Spacer(Modifier.height(6.dp))
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    MolangBuild.Result.entries.forEachIndexed { i, r ->
                        SegmentedButton(result == r, { result = r }, SegmentedButtonDefaults.itemShape(i, MolangBuild.Result.entries.size), icon = {}) {
                            Text(r.label, fontSize = 12.sp, maxLines = 1)
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                when (result) {
                    MolangBuild.Result.TERNARY -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Field(then, "Value if true", Modifier.weight(1f)) { then = it }
                        Field(otherwise, "Value if false", Modifier.weight(1f)) { otherwise = it }
                    }
                    MolangBuild.Result.ASSIGN -> Field(variable, "Variable name", Modifier.fillMaxWidth()) { variable = it }
                    MolangBuild.Result.CONDITION -> Text(
                        "Use it in animation controller transitions, render controller parts, \"condition\" fields…",
                        color = Dim, fontSize = 12.sp,
                    )
                }
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Short names (q. v.)", color = Ink, fontSize = 14.sp, modifier = Modifier.weight(1f))
                    Switch(short, { short = it })
                }
                Spacer(Modifier.height(16.dp))
            }
            // Output
            Column(Modifier.fillMaxWidth().background(Panel).padding(12.dp)) {
                SelectionContainer {
                    Text(
                        output, color = Accent, fontFamily = FontFamily.Monospace, fontSize = 14.sp,
                        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(Bg).padding(10.dp),
                    )
                }
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton({ clipboard.setText(AnnotatedString(output)); copied = true }, Modifier.weight(1f)) {
                        Icon(Icons.Default.ContentCopy, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp))
                        Text(if (copied) "Copied" else "Copy")
                    }
                    if (onInsert != null) {
                        Button(
                            { onInsert(output); onDismiss() }, Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = OnAccent),
                        ) { Text("Insert") }
                    }
                }
            }
        }
    }
    LaunchedEffect(output) { copied = false }

    if (picking >= 0) {
        QueryPicker(onDismiss = { picking = -1 }) { fn ->
            val i = picking
            if (i in conds.indices) conds[i] = conds[i].copy(name = fn.name, args = emptyList())
            picking = -1
        }
    }
}

@Composable
private fun CondCard(i: Int, c: MolangBuild.Cond, conds: SnapshotStateList<MolangBuild.Cond>, onPick: () -> Unit) {
    fun set(n: MolangBuild.Cond) { if (i in conds.indices) conds[i] = n }
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Panel).padding(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (i > 0) {
                SingleChoiceSegmentedButtonRow(Modifier.width(150.dp)) {
                    listOf("&&" to "AND", "||" to "OR").forEachIndexed { k, (op, label) ->
                        SegmentedButton(c.join == op, { set(c.copy(join = op)) }, SegmentedButtonDefaults.itemShape(k, 2), icon = {}) {
                            Text(label, fontSize = 12.sp)
                        }
                    }
                }
            } else {
                Text("When", color = Ink, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            }
            Spacer(Modifier.weight(1f))
            FilterChip(c.not, { set(c.copy(not = !c.not)) }, label = { Text("NOT") })
            if (conds.size > 1) IconButton({ conds.removeAt(i) }) { Icon(Icons.Default.Delete, "Remove", tint = Dim) }
        }
        Spacer(Modifier.height(6.dp))
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(MolangBuild.Subject.QUERY to "Query", MolangBuild.Subject.VARIABLE to "Variable", MolangBuild.Subject.CUSTOM to "Custom").forEach { (s, label) ->
                FilterChip(c.subject == s, {
                    set(c.copy(subject = s, name = when (s) { MolangBuild.Subject.QUERY -> "is_on_ground"; MolangBuild.Subject.VARIABLE -> "my_var"; else -> "" }, args = emptyList()))
                }, label = { Text(label) })
            }
        }
        Spacer(Modifier.height(6.dp))
        when (c.subject) {
            MolangBuild.Subject.QUERY -> {
                val fn = Molang.query(c.name)
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).border(1.dp, Dim.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
                        .clickable(onClick = onPick).padding(horizontal = 10.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("q.${c.name}", color = Accent, fontFamily = FontFamily.Monospace, fontSize = 14.sp, modifier = Modifier.weight(1f))
                    Icon(Icons.Default.ArrowDropDown, "Choose query", tint = Dim)
                }
                fn?.doc?.takeIf { it.isNotEmpty() }?.let { Text(it, color = Dim, fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp)) }
                val names = MolangBuild.argNames(fn)
                names.forEachIndexed { k, n ->
                    Spacer(Modifier.height(6.dp))
                    Field(c.args.getOrElse(k) { "" }, n, Modifier.fillMaxWidth()) { v ->
                        val a = c.args.toMutableList(); while (a.size <= k) a += ""; a[k] = v; set(c.copy(args = a))
                    }
                }
            }
            MolangBuild.Subject.VARIABLE -> Field(c.name, "Variable name (without v.)", Modifier.fillMaxWidth()) { set(c.copy(name = it)) }
            MolangBuild.Subject.CUSTOM -> Field(c.name, "Any Molang, e.g. math.sin(q.anim_time * 90)", Modifier.fillMaxWidth()) { set(c.copy(name = it)) }
        }
        Spacer(Modifier.height(8.dp))
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            MolangBuild.OPS.forEach { (op, label) ->
                FilterChip(c.op == op, { set(c.copy(op = op)) }, label = { Text(label) })
            }
        }
        if (c.op.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            Field(c.value, "Value (number, text or Molang)", Modifier.fillMaxWidth()) { set(c.copy(value = it)) }
        }
    }
}

@Composable
private fun Field(value: String, hint: String, modifier: Modifier, autoFocus: Boolean = false, onChange: (String) -> Unit) {
    val focus = remember { FocusRequester() }
    if (autoFocus) LaunchedEffect(Unit) { focus.requestFocus() }
    Box(
        modifier.clip(RoundedCornerShape(8.dp)).background(Bg).border(1.dp, Dim.copy(alpha = 0.4f), RoundedCornerShape(8.dp))
            .padding(horizontal = 10.dp, vertical = 10.dp),
    ) {
        if (value.isEmpty()) Text(hint, color = Dim, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        BasicTextField(
            value, onChange, singleLine = true, cursorBrush = SolidColor(Accent),
            textStyle = TextStyle(color = Ink, fontSize = 14.sp, fontFamily = FontFamily.Monospace),
            modifier = Modifier.fillMaxWidth().focusRequester(focus),
        )
    }
}

@Composable
private fun QueryPicker(onDismiss: () -> Unit, onPick: (MolangFn) -> Unit) {
    var q by remember { mutableStateOf("") }
    val list = remember(q) {
        MolangData.QUERIES.filter { it.name.contains(q.trim().removePrefix("q.").removePrefix("query."), true) || (q.length > 2 && it.doc.contains(q, true)) }
            .sortedWith(compareBy<MolangFn> { it.deprecated }.thenBy { !it.name.startsWith(q, true) }.thenBy { it.name })
    }
    Dialog(onDismiss, DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Column(Modifier.fillMaxSize().background(Bg).systemBarsPadding().imePadding()) {
            Row(Modifier.fillMaxWidth().height(52.dp).padding(end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onDismiss) { Icon(Icons.Default.Close, "Close", tint = Ink) }
                Field(q, "Search ${MolangData.QUERIES.size} queries (name or description)", Modifier.weight(1f), autoFocus = true) { q = it }
            }
            LazyColumn(Modifier.fillMaxSize()) {
                items(list, key = { it.name }) { fn ->
                    Column(Modifier.fillMaxWidth().clickable { onPick(fn) }.padding(horizontal = 14.dp, vertical = 8.dp)) {
                        Text(
                            "q." + fn.signature + if (fn.deprecated) "  · deprecated" else "",
                            color = if (fn.deprecated) Dim else Ink, fontFamily = FontFamily.Monospace, fontSize = 13.sp,
                        )
                        if (fn.doc.isNotEmpty()) Text(fn.doc, color = Dim, fontSize = 11.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}
