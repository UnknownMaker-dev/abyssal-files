package com.abyssal.files

import android.app.usage.StorageStatsManager
import android.content.Context
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.os.storage.StorageManager
import java.io.File

data class Volume(val root: File, val label: String, val removable: Boolean, val used: Long, val total: Long)

object Storage {
    /** Internal storage first, then SD cards / USB drives that are mounted and readable. */
    fun volumes(ctx: Context): List<Volume> {
        val sm = ctx.getSystemService(StorageManager::class.java)
        val primary = Environment.getExternalStorageDirectory().path
        val roots = LinkedHashSet<String>().apply { add(primary) }
        if (Build.VERSION.SDK_INT >= 30) {
            sm.storageVolumes.forEach { v ->
                val d = v.directory
                if (d != null && v.state.startsWith(Environment.MEDIA_MOUNTED)) roots += d.path
            }
        }
        // Works on every API level: <volume>/Android/data/<pkg>/files
        ctx.getExternalFilesDirs(null).forEach { f ->
            val p = f?.path?.substringBefore("/Android/data/", "").orEmpty()
            if (p.isNotEmpty()) roots += p
        }
        return roots.map(::File)
            .filter { it.path == primary || it.canRead() }
            .map { describe(ctx, sm, it, it.path == primary) }
    }

    private fun describe(ctx: Context, sm: StorageManager, root: File, primary: Boolean): Volume {
        var total = 0L
        var free = 0L
        if (primary && Build.VERSION.SDK_INT >= 26) runCatching {
            // Same numbers as Android's Settings: the advertised size (e.g. 64 GB), system included in "used"
            val ss = ctx.getSystemService(StorageStatsManager::class.java)
            total = ss.getTotalBytes(StorageManager.UUID_DEFAULT)
            free = ss.getFreeBytes(StorageManager.UUID_DEFAULT)
        }
        if (total <= 0) runCatching {
            val st = StatFs(root.path)
            total = if (primary) roundStorageSize(st.totalBytes) else st.totalBytes
            free = st.availableBytes
        }
        val label = if (primary) "Internal storage"
        else runCatching { sm.getStorageVolume(root)?.getDescription(ctx) }.getOrNull() ?: "SD card"
        return Volume(root, label, !primary, (total - free).coerceAtLeast(0), total)
    }

    /** Port of AOSP FileUtils.roundStorageSize: 53.2 GB data partition -> 64 GB device. */
    private fun roundStorageSize(size: Long): Long {
        var v = 1L
        var pow = 1L
        while (v * pow < size) {
            v = v shl 1
            if (v > 512) { v = 1; pow *= 1000 }
        }
        return v * pow
    }
}
