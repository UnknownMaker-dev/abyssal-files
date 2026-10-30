package com.abyssal.files

import android.app.ActivityManager
import android.app.Application
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import android.os.Environment
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Installs the crash recorder before anything else runs. */
class AbyssalApp : Application() {
    override fun onCreate() {
        super.onCreate()
        CrashLog.install(this)
    }
}

/**
 * Crash logs: uncaught exceptions are written to a text file just before the app closes, and on Android 11+ the
 * system's own record of ANRs ("not responding") and native crashes is picked up on the next launch. Logs live in
 * the app's private storage and are also copied to Internal storage/AbyssalFiles/crashes so they're easy to send.
 */
object CrashLog {
    private const val KEEP = 30
    private const val PREFS = "crashlog"

    fun dir(ctx: Context) = File(ctx.filesDir, "crashes")
    val publicDir: File get() = File(Environment.getExternalStorageDirectory(), "AbyssalFiles/crashes")

    fun install(ctx: Context) {
        val app = ctx.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching { write(app, "crash", report(app, "Crash in thread \"${thread.name}\"", stackTrace(error))) }
            previous?.uncaughtException(thread, error)
        }
    }

    private fun stackTrace(e: Throwable) = StringWriter().also { e.printStackTrace(PrintWriter(it)) }.toString()

    private fun version(ctx: Context) = runCatching {
        val p = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
        @Suppress("DEPRECATION") val code = if (Build.VERSION.SDK_INT >= 28) p.longVersionCode else p.versionCode.toLong()
        "${p.versionName} ($code)"
    }.getOrDefault("?")

    fun report(ctx: Context, title: String, body: String, time: Long = System.currentTimeMillis()): String {
        val rt = Runtime.getRuntime()
        return buildString {
            appendLine("Abyssal Files ${version(ctx)}")
            appendLine(title)
            appendLine("Time: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(time))}")
            appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE})")
            appendLine("Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            appendLine("ABI: ${Build.SUPPORTED_ABIS.joinToString()}")
            appendLine("Memory: ${(rt.totalMemory() - rt.freeMemory()) / 1_048_576} MB used of ${rt.maxMemory() / 1_048_576} MB")
            appendLine()
            append(body)
        }
    }

    fun write(ctx: Context, kind: String, text: String, time: Long = System.currentTimeMillis()): File {
        val name = "$kind-${SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date(time))}.txt"
        val f = File(dir(ctx).apply { mkdirs() }, name)
        f.writeText(text)
        f.setLastModified(time)
        runCatching { File(publicDir.apply { mkdirs() }, name).writeText(text) }
        list(ctx).drop(KEEP).forEach { it.delete() }
        return f
    }

    fun list(ctx: Context): List<File> =
        dir(ctx).listFiles().orEmpty().filter { it.isFile && it.extension == "txt" }.sortedByDescending { it.lastModified() }

    fun deleteAll(ctx: Context) {
        dir(ctx).deleteRecursively()
        runCatching { publicDir.listFiles()?.forEach { it.delete() } }
    }

    /** Android 11+: ANRs and native crashes the in-app handler can't catch, recorded once each. */
    fun collectSystemReports(ctx: Context) {
        if (Build.VERSION.SDK_INT < 30) return
        val sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val since = sp.getLong("lastExit", 0L)
        var newest = since
        runCatching {
            val am = ctx.getSystemService(ActivityManager::class.java)
            for (info in am.getHistoricalProcessExitReasons(ctx.packageName, 0, 10)) {
                if (info.timestamp <= since) continue
                newest = maxOf(newest, info.timestamp)
                val kind = when (info.reason) {
                    ApplicationExitInfo.REASON_ANR -> "anr"
                    ApplicationExitInfo.REASON_CRASH_NATIVE -> "native"
                    else -> continue
                }
                val trace = runCatching { info.traceInputStream?.use { s -> String(s.readBytes().take(400_000).toByteArray()) } }
                    .getOrNull().orEmpty()
                val title = if (kind == "anr") "App not responding (ANR)" else "Native crash"
                write(ctx, kind, report(ctx, title, "${info.description.orEmpty()}\n\n$trace", info.timestamp), info.timestamp)
            }
        }
        if (newest > since) sp.edit().putLong("lastExit", newest).apply()
    }

    /** The newest log the user hasn't been told about yet, if any. */
    fun unseen(ctx: Context): File? {
        val seen = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong("seen", 0L)
        return list(ctx).firstOrNull()?.takeIf { it.lastModified() > seen }
    }

    fun markSeen(ctx: Context) {
        val newest = list(ctx).firstOrNull()?.lastModified() ?: return
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putLong("seen", newest).apply()
    }
}
