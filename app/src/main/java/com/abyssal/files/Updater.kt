package com.abyssal.files

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import org.json.JSONArray
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** "1.7-beta.3" / "v1.8": numbers first, then a stable release beats any beta of the same numbers. */
data class AppVersion(val nums: List<Int>, val beta: Int?) : Comparable<AppVersion> {
    val isBeta: Boolean get() = beta != null

    override fun compareTo(other: AppVersion): Int {
        for (i in 0 until maxOf(nums.size, other.nums.size)) {
            val a = nums.getOrElse(i) { 0 }
            val b = other.nums.getOrElse(i) { 0 }
            if (a != b) return a.compareTo(b)
        }
        return when {
            beta == other.beta -> 0
            beta == null -> 1
            other.beta == null -> -1
            else -> beta.compareTo(other.beta)
        }
    }

    override fun toString() = nums.joinToString(".") + (beta?.let { "-beta.$it" } ?: "")

    companion object {
        private val RE = Regex("^v?(\\d+(?:\\.\\d+)*)(?:-beta\\.?(\\d+))?", RegexOption.IGNORE_CASE)
        fun parse(s: String): AppVersion? {
            val m = RE.find(s.trim()) ?: return null
            return AppVersion(m.groupValues[1].split('.').map { it.toInt() }, m.groupValues[2].takeIf { it.isNotEmpty() }?.toInt())
        }
    }
}

data class Release(
    val tag: String, val version: AppVersion, val title: String, val notes: String,
    val prerelease: Boolean, val apkUrl: String, val size: Long, val page: String,
)

sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data object UpToDate : UpdateState
    data class Available(val release: Release) : UpdateState
    data class Downloading(val release: Release, val progress: Float) : UpdateState
    data class Ready(val release: Release, val apk: File) : UpdateState
    data class Failed(val message: String) : UpdateState
}

/** Updates from this project's GitHub releases. */
object Updater {
    const val REPO = "UnknownMaker-dev/abyssal-files"
    const val PAGE = "https://github.com/$REPO/releases"

    /** The newest release newer than [current], from the channel the user chose (betas only when [beta]). */
    fun pick(releases: List<Release>, current: AppVersion, beta: Boolean): Release? =
        releases.filter { beta || !it.prerelease }.filter { it.version > current }.maxByOrNull { it.version }

    fun fetch(): List<Release> {
        val c = URL("https://api.github.com/repos/$REPO/releases?per_page=30").openConnection() as HttpURLConnection
        c.connectTimeout = 15000; c.readTimeout = 20000
        c.setRequestProperty("Accept", "application/vnd.github+json")
        c.setRequestProperty("User-Agent", "AbyssalFiles")
        try {
            if (c.responseCode != 200) error("GitHub answered ${c.responseCode}")
            val a = JSONArray(c.inputStream.bufferedReader().readText())
            return (0 until a.length()).mapNotNull { i ->
                val o = a.getJSONObject(i)
                if (o.optBoolean("draft")) return@mapNotNull null
                val tag = o.optString("tag_name")
                val version = AppVersion.parse(tag) ?: return@mapNotNull null
                val assets = o.optJSONArray("assets") ?: JSONArray()
                val apk = (0 until assets.length()).map { assets.getJSONObject(it) }
                    .firstOrNull { it.optString("name").endsWith(".apk") } ?: return@mapNotNull null
                Release(
                    tag, version, o.optString("name").ifEmpty { tag }, o.optString("body"), o.optBoolean("prerelease"),
                    apk.optString("browser_download_url"), apk.optLong("size"), o.optString("html_url"),
                )
            }
        } finally {
            c.disconnect()
        }
    }

    fun download(r: Release, dir: File, onProgress: (Float) -> Unit): File {
        dir.mkdirs()
        dir.listFiles()?.forEach { it.delete() }
        val out = File(dir, "AbyssalFiles-${r.version}.apk")
        val part = File(dir, out.name + ".part")
        var url = URL(r.apkUrl)
        // GitHub redirects to its file CDN
        repeat(5) {
            val c = url.openConnection() as HttpURLConnection
            c.instanceFollowRedirects = true
            c.connectTimeout = 15000; c.readTimeout = 30000
            c.setRequestProperty("User-Agent", "AbyssalFiles")
            try {
                val code = c.responseCode
                if (code in 300..399) { url = URL(url, c.getHeaderField("Location")); return@repeat }
                if (code != 200) error("Download failed ($code)")
                val total = c.contentLengthLong.takeIf { it > 0 } ?: r.size
                c.inputStream.use { input ->
                    part.outputStream().use { output ->
                        val buf = ByteArray(64 * 1024)
                        var done = 0L
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            output.write(buf, 0, n)
                            done += n
                            if (total > 0) onProgress((done.toFloat() / total).coerceIn(0f, 1f))
                        }
                    }
                }
                if (!part.renameTo(out)) error("Couldn't save the update")
                return out
            } finally {
                c.disconnect()
            }
        }
        error("Too many redirects")
    }

    /** Hands the APK to Android's installer (asks for "install unknown apps" the first time). */
    fun install(ctx: Context, apk: File): String? {
        if (Build.VERSION.SDK_INT >= 26 && !ctx.packageManager.canRequestPackageInstalls()) {
            ctx.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${ctx.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            return "Allow Abyssal Files to install updates, then tap Install again"
        }
        ctx.startActivity(
            Intent(Intent.ACTION_VIEW).setDataAndType(uriFor(ctx, apk), "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        return null
    }

    /** Release notes without the Markdown markers. */
    fun plainNotes(md: String): String = md
        .replace(Regex("\\*\\*(.+?)\\*\\*"), "$1")
        .replace(Regex("\\[([^\\]]+)\\]\\([^)]*\\)"), "$1")
        .replace(Regex("`([^`]*)`"), "$1")
        .replace(Regex("(?m)^\\s*[-*] "), "• ")
        .trim()
}
