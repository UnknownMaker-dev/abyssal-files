@file:OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)

package com.abyssal.files

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val PRESETS = listOf(
    0xFFA324EC, 0xFF4DA3FF, 0xFF2DD4BF, 0xFF4CAF7A, 0xFFA3E635,
    0xFFFFC83D, 0xFFFF8A3D, 0xFFFF5C7A, 0xFFF472B6, 0xFFC9CDD6, 0xFF4E4E4E,
).map { Color(it) }

@Composable
fun SettingsScreen(vm: FmViewModel) {
    Column(Modifier.fillMaxSize().background(Bg).systemBarsPadding()) {
        Row(Modifier.fillMaxWidth().height(52.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton({ vm.back() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Ink) }
            Text("Settings", fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = Ink)
        }
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Label("Theme color")
            Card {
                ColorPicker(Pal.accent, onChange = { vm.setAccent(it, persist = false) }, onCommit = { vm.setAccent(it) })
                Spacer(Modifier.height(14.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    PRESETS.forEach { c ->
                        val on = c.toArgb() == Pal.accent.toArgb()
                        Box(
                            Modifier.size(32.dp).clip(CircleShape).background(c)
                                .border(if (on) 3.dp else 0.dp, if (on) Ink else Color.Transparent, CircleShape)
                                .clickable { vm.setAccent(c) }
                        )
                    }
                }
                Spacer(Modifier.height(6.dp))
                TextButton({ vm.setAccent(Color(DEFAULT_ACCENT)) }, contentPadding = PaddingValues(0.dp)) {
                    Text("Reset to default", fontSize = 13.sp)
                }
            }

            Label("View")
            Card {
                ViewOptions(vm)
                Text(
                    "List or grid, and how big files, photos and albums are shown. You can also pinch any list to resize it.",
                    color = Dim, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp),
                )
            }

            Label("Files")
            Card {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Show hidden files", color = Ink, fontSize = 14.sp, modifier = Modifier.weight(1f))
                    Switch(vm.showHidden, { vm.toggleHidden() })
                }
            }

            Label("Code editor")
            Card {
                val ctx = androidx.compose.ui.platform.LocalContext.current
                val prefs = remember { Prefs(ctx) }
                var bedrock by remember { mutableStateOf(prefs.bedrockMode) }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    PixIcon("mcworld", 28.dp)
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Bedrock mode", color = Ink, fontSize = 14.sp)
                        Text(
                            "Autocomplete for the Minecraft Script API (@minecraft/server, server-ui) in JS/TS and for " +
                                "add-on JSON: manifest, entities, items, blocks, recipes, loot tables…",
                            color = Dim, fontSize = 12.sp,
                        )
                    }
                    Switch(bedrock, { bedrock = it; prefs.bedrockMode = it })
                }
            }

            Label("Icons")
            Card {
                Text("Icon colours", color = Ink, fontSize = 14.sp)
                Spacer(Modifier.height(8.dp))
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    listOf(false to "Theme colour", true to "Colourful").forEachIndexed { i, (colorful, label) ->
                        SegmentedButton(
                            selected = Pal.colorfulIcons == colorful,
                            onClick = { vm.setColorfulIcons(colorful) },
                            shape = SegmentedButtonDefaults.itemShape(i, 2),
                        ) { Text(label, fontSize = 13.sp) }
                    }
                }
                Text(
                    if (Pal.colorfulIcons) "Each file type keeps its own colour, like VS Code."
                    else "Every icon follows the theme colour above.",
                    color = Dim, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp),
                )
                Spacer(Modifier.height(12.dp))
                var showAll by remember { mutableStateOf(false) }
                val names = remember(PixelIcons.version) { PixelIcons.names }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    (if (showAll) names else names.take(16)).forEach { name ->
                        Column(Modifier.width(72.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            PixIcon(name, 32.dp)
                            Text(name, color = Dim, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
                TextButton({ showAll = !showAll }, contentPadding = PaddingValues(0.dp)) {
                    Text(if (showAll) "Show fewer" else "Show all ${names.size} icons", fontSize = 13.sp)
                }
            }

            Label("Custom icons")
            Card {
                Text(
                    "Every icon is a small text file with a pixel grid. To change one, put a file with the same " +
                        "name — an edited name.txt or your own name.png — in",
                    color = Dim, fontSize = 13.sp,
                )
                Text(
                    CustomIcons.dir.path, color = Ink, fontSize = 12.sp, fontFamily = FontFamily.Monospace,
                    modifier = Modifier.padding(vertical = 6.dp),
                )
                Text("\"Copy icons\" puts all the default .txt icons there so you can edit them right here in the app.", color = Dim, fontSize = 13.sp)
                Spacer(Modifier.height(10.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button({ vm.reloadIcons(notify = true) }) { Text("Reload icons") }
                    OutlinedButton({ vm.exportIcons() }) { Text("Copy icons") }
                    OutlinedButton({ vm.openIconsFolder() }) { Text("Open folder") }
                }
            }

            Label("About")
            Card {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Logo(40.dp)
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text("Abyssal Files", color = Ink, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                        Text("Version ${BuildConfig.VERSION_NAME}", color = Dim, fontSize = 12.sp)
                    }
                }
            }
            Column(
                Modifier.fillMaxWidth().padding(top = 20.dp, bottom = 28.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("Created by Jh0nzxx and Unknown Maker", color = Ink, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

/** The 32×32 pixel-art logo, scaled without smoothing. */
@Composable
fun Logo(size: Dp) {
    val bmp = ImageBitmap.imageResource(R.drawable.logo)
    Image(remember(bmp) { BitmapPainter(bmp, filterQuality = FilterQuality.None) }, null, Modifier.size(size))
}

@Composable
private fun Label(t: String) = Text(t, color = Dim, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))

@Composable
private fun Card(content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Panel).padding(14.dp),
        content = content,
    )
}

/** HSV picker: saturation/value square + hue bar + hex field. */
@Composable
fun ColorPicker(color: Color, onChange: (Color) -> Unit, onCommit: (Color) -> Unit) {
    val hsv = remember { FloatArray(3).also { android.graphics.Color.colorToHSV(color.toArgb(), it) } }
    var h by remember { mutableFloatStateOf(hsv[0]) }
    var s by remember { mutableFloatStateOf(hsv[1]) }
    var v by remember { mutableFloatStateOf(hsv[2]) }
    val change by rememberUpdatedState(onChange)
    val commit by rememberUpdatedState(onCommit)

    // Follow external changes (presets, reset, hex)
    LaunchedEffect(color) {
        if (Color.hsv(h, s, v).toArgb() != color.toArgb()) {
            android.graphics.Color.colorToHSV(color.toArgb(), hsv)
            h = hsv[0]; s = hsv[1]; v = hsv[2]
        }
    }

    fun emit(done: Boolean) {
        val c = Color.hsv(h, s, v)
        change(c)
        if (done) commit(c)
    }

    Box(
        Modifier.fillMaxWidth().height(170.dp).clip(RoundedCornerShape(10.dp))
            .background(Brush.horizontalGradient(listOf(Color.White, Color.hsv(h, 1f, 1f))))
            .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black)))
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    fun at(p: Offset) {
                        s = (p.x / size.width).coerceIn(0f, 1f)
                        v = 1f - (p.y / size.height).coerceIn(0f, 1f)
                        emit(false)
                    }
                    at(down.position); down.consume()
                    drag(down.id) { at(it.position); it.consume() }
                    emit(true)
                }
            }
            .drawWithContent {
                drawContent()
                val c = Offset(s * size.width, (1 - v) * size.height)
                drawCircle(Color.Black.copy(alpha = 0.5f), 11.dp.toPx(), c, style = Stroke(4.dp.toPx()))
                drawCircle(Color.White, 11.dp.toPx(), c, style = Stroke(2.dp.toPx()))
            }
    )
    Spacer(Modifier.height(12.dp))
    Box(
        Modifier.fillMaxWidth().height(26.dp).clip(RoundedCornerShape(13.dp))
            .background(Brush.horizontalGradient((0..6).map { Color.hsv(it * 60f % 360f, 1f, 1f) }))
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    fun at(p: Offset) { h = (p.x / size.width).coerceIn(0f, 0.999f) * 360f; emit(false) }
                    at(down.position); down.consume()
                    drag(down.id) { at(it.position); it.consume() }
                    emit(true)
                }
            }
            .drawWithContent {
                drawContent()
                val x = (h / 360f) * size.width
                drawCircle(Color.White, size.height / 2 - 2.dp.toPx(), Offset(x.coerceIn(size.height / 2, size.width - size.height / 2), size.height / 2), style = Stroke(3.dp.toPx()))
            }
    )
    Spacer(Modifier.height(12.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(44.dp).clip(RoundedCornerShape(10.dp)).background(color))
        Spacer(Modifier.width(12.dp))
        var hex by remember(color) { mutableStateOf("%06X".format(color.toArgb() and 0xFFFFFF)) }
        OutlinedTextField(
            hex,
            { t ->
                hex = t.removePrefix("#").uppercase().take(6)
                if (hex.length == 6) hex.toLongOrNull(16)?.let { onCommit(Color(0xFF000000 or it)) }
            },
            prefix = { Text("#", color = Dim) },
            singleLine = true,
            textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace),
            modifier = Modifier.width(150.dp),
        )
    }
}
