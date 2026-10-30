package com.abyssal.files

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.io.File

/** "The app closed last time" prompt and the log viewer, shown over everything. */
@Composable
fun CrashDialogs(vm: FmViewModel) {
    vm.newCrash?.let { f ->
        AlertDialog(
            onDismissRequest = { vm.dismissNewCrash() },
            containerColor = Panel2,
            icon = { Logo(40.dp) },
            title = { Text("Abyssal Files closed unexpectedly", fontSize = 17.sp) },
            text = {
                Text(
                    "A crash log was saved (${f.name}). Sending it to the developers helps fix the problem. " +
                        "You can find it any time in Settings → Crash logs.",
                    color = Dim, fontSize = 14.sp,
                )
            },
            confirmButton = { TextButton({ vm.dismissNewCrash(); vm.viewingLog = f }) { Text("View log") } },
            dismissButton = { TextButton({ vm.dismissNewCrash() }) { Text("Later", color = Dim) } },
        )
    }
    vm.viewingLog?.let { f -> CrashLogDialog(f, vm) { vm.viewingLog = null } }
}

@Composable
private fun CrashLogDialog(f: File, vm: FmViewModel, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val text = remember(f) { runCatching { f.readText() }.getOrDefault("Can't read ${f.name}") }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Panel2,
        title = { Text(f.name, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        text = {
            Box(
                Modifier.fillMaxWidth().heightIn(max = 440.dp).clip(RoundedCornerShape(8.dp)).background(Bg)
                    .verticalScroll(rememberScrollState()).horizontalScroll(rememberScrollState()).padding(8.dp),
            ) {
                Text(text, color = Ink, fontSize = 10.sp, fontFamily = FontFamily.Monospace, lineHeight = 13.sp, softWrap = false)
            }
        },
        confirmButton = {
            Row {
                TextButton({ clipboard.setText(AnnotatedString(text)); vm.message = "Crash log copied" }) { Text("Copy") }
                TextButton({ shareLog(ctx, f) { vm.message = it } }) { Text("Share") }
            }
        },
        dismissButton = { TextButton(onDismiss) { Text("Close", color = Dim) } },
    )
}

private fun shareLog(ctx: Context, f: File, onMsg: (String) -> Unit) {
    runCatching {
        val i = Intent(Intent.ACTION_SEND).setType("text/plain")
            .putExtra(Intent.EXTRA_STREAM, uriFor(ctx, f))
            .putExtra(Intent.EXTRA_SUBJECT, "Abyssal Files crash log: ${f.name}")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        ctx.startActivity(Intent.createChooser(i, "Share crash log"))
    }.onFailure { onMsg("Can't share: ${it.message}") }
}

/** Settings → Crash logs. */
@Composable
fun CrashLogsSection(vm: FmViewModel) {
    LaunchedEffect(Unit) { vm.refreshCrashLogs() }
    val logs = vm.crashLogs
    Text(
        if (logs.isEmpty()) "No crashes recorded. If the app ever closes by itself, the log shows up here."
        else "${logs.size} log(s). Tap one to read, copy or share it with the developers.",
        color = Dim, fontSize = 12.sp,
        // Hidden: long-press makes a test crash, to check that logs are recorded
        modifier = Modifier.pointerInput(Unit) {
            detectTapGestures(onLongPress = { throw RuntimeException("Test crash (long-press in Settings → Crash logs)") })
        },
    )
    logs.take(10).forEach { f ->
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable { vm.viewingLog = f }.padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PixIcon("log", 26.dp)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(f.name, color = Ink, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(FileOps.fmtDateFull(f.lastModified()), color = Dim, fontSize = 11.sp)
            }
        }
    }
    Text("Also saved in ${vm.displayPath(CrashLog.publicDir.path)}", color = Dim, fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp))
    if (logs.isNotEmpty()) {
        TextButton({ vm.deleteCrashLogs() }, contentPadding = PaddingValues(0.dp)) { Text("Delete all", color = Danger, fontSize = 13.sp) }
    }
}
