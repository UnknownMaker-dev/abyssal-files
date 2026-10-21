package com.abyssal.files

import android.Manifest
import android.content.ClipData
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Process
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.graphics.toArgb
import kotlinx.coroutines.delay
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color as ComposeColor
import java.io.File

class MainActivity : ComponentActivity() {
    private var granted by mutableStateOf(false)
    private val vm: FmViewModel by viewModels()

    private val legacyPerm = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { granted = hasAccess() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        val prefs = Prefs(this)
        Pal.accent = ComposeColor(prefs.accent)
        Pal.rgb = prefs.rgbAccent
        Pal.colorfulIcons = prefs.colorfulIcons
        PixelIcons.loadDefaults(assets)
        granted = hasAccess()
        handleIntent()
        if (savedInstanceState == null && intent?.action == Intent.ACTION_MAIN) vm.autoCheckUpdates()
        setContent {
            FmTheme {
                if (Pal.rgb) LaunchedEffect(Unit) {
                    // "RGB" accent: walk around the colour wheel
                    val hsv = FloatArray(3).also { android.graphics.Color.colorToHSV(Pal.accent.toArgb(), it) }
                    var hue = hsv[0]
                    while (true) {
                        hue = (hue + 3f) % 360f
                        Pal.accent = ComposeColor.hsv(hue, 0.78f, 0.95f)
                        delay(110)
                    }
                }
                val picked = vm.pickResult
                LaunchedEffect(picked) { picked?.let(::returnPicked) }
                LaunchedEffect(vm.externalDone) { if (vm.externalDone) finish() }
                // Files sent by other apps open without all-files access
                if (granted || vm.external) FileManagerApp(vm) else PermissionScreen(::requestAccess)
            }
        }
    }

    /** "Open with" / "Edit with" from other apps, and file pickers (ACTION_GET_CONTENT). */
    private fun handleIntent() {
        val i = intent ?: return
        when (i.action) {
            Intent.ACTION_GET_CONTENT -> {
                val mimes = i.getStringArrayExtra(Intent.EXTRA_MIME_TYPES)?.toList()?.takeIf { it.isNotEmpty() }
                    ?: listOfNotNull(i.type)
                vm.startPick(
                    PickRequest(mimes.filter { it.isNotBlank() }, i.getBooleanExtra(Intent.EXTRA_ALLOW_MULTIPLE, false), callerName())
                )
            }
            Intent.ACTION_VIEW, Intent.ACTION_EDIT -> {
                val uri = i.data ?: return finish()
                val edit = i.action == Intent.ACTION_EDIT
                val writeGranted = i.flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION != 0 ||
                    checkUriPermission(uri, Process.myPid(), Process.myUid(), Intent.FLAG_GRANT_WRITE_URI_PERMISSION) ==
                    PackageManager.PERMISSION_GRANTED
                vm.openIncoming(uri, i.type, edit, writable = edit || writeGranted, direct = hasAccess())
            }
        }
    }

    private fun callerName(): String? = runCatching {
        val pkg = callingPackage ?: return null
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
    }.getOrNull()

    private fun returnPicked(paths: List<String>) {
        if (paths.isEmpty()) {
            setResult(RESULT_CANCELED)
        } else {
            val uris = paths.map { uriFor(this, File(it)) }
            val data = Intent().addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            // Like the system picker: one file goes in data, several only in ClipData (apps read data first)
            if (uris.size == 1) {
                data.data = uris[0]
            } else {
                data.clipData = ClipData.newUri(contentResolver, "files", uris[0]).apply {
                    uris.drop(1).forEach { addItem(ClipData.Item(it)) }
                }
            }
            setResult(RESULT_OK, data)
        }
        finish()
    }

    override fun onResume() {
        super.onResume()
        granted = hasAccess()
        if (granted) vm.onResume()
    }

    private fun hasAccess(): Boolean =
        if (Build.VERSION.SDK_INT >= 30) Environment.isExternalStorageManager()
        else checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED

    private fun requestAccess() {
        if (Build.VERSION.SDK_INT >= 30) {
            startActivity(
                Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName"))
            )
        } else {
            legacyPerm.launch(
                arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE)
            )
        }
    }
}
