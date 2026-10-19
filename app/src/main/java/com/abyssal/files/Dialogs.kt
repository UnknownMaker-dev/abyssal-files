@file:OptIn(ExperimentalLayoutApi::class)

package com.abyssal.files

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

sealed interface Dlg {
    data class Rename(val item: FileItem) : Dlg
    data object NewFolder : Dlg
    data object NewFile : Dlg
    data object ZipName : Dlg
    data object Delete : Dlg
    data class Props(val paths: List<String>) : Dlg
    data class ExtractTo(val zip: String, val entry: String? = null) : Dlg
}

@Composable
fun NameDialog(title: String, initial: String, onOk: (String) -> Unit, onDismiss: () -> Unit) {
    // Pre-select the name (without extension) so typing replaces it
    val end = initial.lastIndexOf('.').takeIf { it > 0 } ?: initial.length
    var text by remember { mutableStateOf(TextFieldValue(initial, TextRange(0, end))) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Panel2,
        title = { Text(title, fontSize = 16.sp) },
        text = { OutlinedTextField(text, { text = it }, singleLine = true, modifier = Modifier.focusRequester(focus)) },
        confirmButton = { TextButton({ onOk(text.text) }) { Text("OK") } },
        dismissButton = { TextButton(onDismiss) { Text("Cancel", color = Dim) } },
    )
}

private val QUICK_EXTS = listOf("txt", "json", "md", "html", "css", "js", "py", "xml", "csv", "sh", "kt", "log")

@Composable
fun NewFileDialog(onOk: (String) -> Unit, onDismiss: () -> Unit) {
    var tf by remember { mutableStateOf(TextFieldValue("new.txt", TextRange(0, 3))) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Panel2,
        title = { Text("New file", fontSize = 16.sp) },
        text = {
            Column {
                OutlinedTextField(
                    tf, { tf = it }, singleLine = true,
                    label = { Text("Name.extension") },
                    modifier = Modifier.focusRequester(focus),
                )
                Spacer(Modifier.height(10.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    QUICK_EXTS.forEach { ext ->
                        FilterChip(
                            selected = tf.text.endsWith(".$ext", ignoreCase = true),
                            onClick = {
                                val base = tf.text.substringBeforeLast('.', tf.text).ifBlank { "new" }
                                tf = TextFieldValue("$base.$ext", TextRange(0, base.length))
                            },
                            label = { Text(".$ext", fontSize = 12.sp) },
                        )
                    }
                }
                Text("Any extension works — just type it.", color = Dim, fontSize = 12.sp)
            }
        },
        confirmButton = { TextButton({ onOk(tf.text) }) { Text("Create") } },
        dismissButton = { TextButton(onDismiss) { Text("Cancel", color = Dim) } },
    )
}

@Composable
fun DeleteDialog(count: Int, onOk: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Panel2,
        title = { Text("Delete $count item(s)?", fontSize = 16.sp) },
        text = { Text("This can't be undone.", color = Dim, fontSize = 13.sp) },
        confirmButton = { TextButton(onOk) { Text("Delete", color = Danger) } },
        dismissButton = { TextButton(onDismiss) { Text("Cancel") } },
    )
}

@Composable
fun PropertiesDialog(paths: List<String>, displayPath: (String) -> String, onDismiss: () -> Unit) {
    val files = remember(paths) { paths.map(::File) }
    val single = files.singleOrNull()
    val needsStats = single == null || single.isDirectory
    val stats by produceState<FileOps.Stats?>(null, paths) {
        if (needsStats) value = withContext(Dispatchers.IO) { FileOps.stats(files) }
    }
    val extra by produceState(emptyList<Pair<String, String>>(), paths) {
        if (single != null && single.isFile) value = withContext(Dispatchers.IO) { FileOps.mediaInfo(single) }
    }

    fun sizeText(b: Long) = "${FileOps.fmtSize(b)}  (${"%,d".format(b)} bytes)"
    val rows = buildList {
        if (single != null) {
            add("Name" to single.name)
            add("Location" to displayPath(single.parent ?: "/"))
            add("Full path" to single.path)
            if (single.isDirectory) {
                add("Type" to "Folder")
                add("Size" to (stats?.let { sizeText(it.size) } ?: "Calculating…"))
                add("Contains" to (stats?.let { "${it.files} files, ${it.dirs} folders" } ?: "Calculating…"))
            } else {
                add("Type" to "${single.extension.uppercase().ifEmpty { "Unknown" }} file · ${FileOps.mime(single)}")
                add("Size" to sizeText(single.length()))
            }
            add("Modified" to FileOps.fmtDateFull(single.lastModified()))
            add("Access" to when {
                single.canRead() && single.canWrite() -> "Read & write"
                single.canRead() -> "Read only"
                else -> "No access"
            })
            add("Hidden" to if (single.isHidden) "Yes" else "No")
            addAll(extra)
        } else {
            val parents = files.mapNotNull { it.parent }.distinct()
            add("Selected" to "${files.size} items (${files.count { it.isDirectory }} folders)")
            add("Location" to (parents.singleOrNull()?.let(displayPath) ?: "Multiple folders"))
            add("Total size" to (stats?.let { sizeText(it.size) } ?: "Calculating…"))
            add("Contains" to (stats?.let { "${it.files} files, ${it.dirs} folders inside" } ?: "Calculating…"))
            add("Last modified" to FileOps.fmtDateFull(files.maxOf { it.lastModified() }))
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Panel2,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (single != null) FileIcon(FileItem.of(single), 32.dp) else KindIcon(Kind.FILE)
                Spacer(Modifier.width(10.dp))
                Text(
                    "Properties", fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
        },
        text = {
            SelectionContainer {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    rows.forEach { (k, v) ->
                        Column {
                            Text(k, color = Dim, fontSize = 11.sp)
                            Text(v, color = Ink, fontSize = 14.sp)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onDismiss) { Text("Close") } },
    )
}

/** Simple folder browser: storage volumes at the top level, then subfolders. */
@Composable
fun FolderPickerDialog(vm: FmViewModel, start: File?, title: String, confirm: String, onPick: (File) -> Unit, onDismiss: () -> Unit) {
    var dir by remember { mutableStateOf(start) }
    val subdirs by produceState(emptyList<File>(), dir) {
        value = withContext(Dispatchers.IO) {
            dir?.listFiles()?.filter { it.isDirectory && !it.name.startsWith(".") }?.sortedBy { it.name.lowercase() }.orEmpty()
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Panel2,
        title = {
            Column {
                Text(title, fontSize = 16.sp)
                Text(dir?.let { vm.displayPath(it.path) } ?: "Storage", fontSize = 12.sp, color = Dim, maxLines = 2)
            }
        },
        text = {
            androidx.compose.foundation.lazy.LazyColumn(Modifier.heightIn(max = 360.dp)) {
                val d = dir
                if (d == null) {
                    items(vm.volumes.size) { i ->
                        val v = vm.volumes[i]
                        PickerRow(if (v.removable) "sdcard" else "storage", v.label) { dir = v.root }
                    }
                } else {
                    item {
                        PickerRow("folder", "..") {
                            val root = vm.volumeOf(d)?.root
                            dir = if (root == null || d.path == root.path) null else d.parentFile
                        }
                    }
                    items(subdirs.size) { i -> val f = subdirs[i]; PickerRow(IconMap.forFolder(f.name), f.name) { dir = f } }
                }
            }
        },
        confirmButton = { TextButton({ dir?.let(onPick) }, enabled = dir != null) { Text(confirm) } },
        dismissButton = { TextButton(onDismiss) { Text("Cancel", color = Dim) } },
    )
}

@Composable
private fun PickerRow(icon: String, name: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PixIcon(icon, 26.dp)
        Spacer(Modifier.width(10.dp))
        Text(name, color = Ink, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
