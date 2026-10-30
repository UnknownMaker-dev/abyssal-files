package com.abyssal.files

import android.content.Context
import org.json.JSONArray

class Prefs(ctx: Context) {
    private val sp = ctx.getSharedPreferences("abyssalfiles", Context.MODE_PRIVATE)

    var accent: Int
        get() = sp.getInt("accent", DEFAULT_ACCENT)
        set(v) = sp.edit().putInt("accent", v).apply()

    var showHidden: Boolean
        get() = sp.getBoolean("hidden", false)
        set(v) = sp.edit().putBoolean("hidden", v).apply()

    var sort: Sort
        get() = runCatching { Sort.valueOf(sp.getString("sort", null)!!) }.getOrDefault(Sort.NAME)
        set(v) = sp.edit().putString("sort", v.name).apply()

    /** Order chosen per folder/category ("dir:/path" or "cat:Images" → sort, reversed); others use [sort]. */
    var folderSorts: Map<String, Pair<Sort, Boolean>>
        get() = runCatching {
            val o = org.json.JSONObject(sp.getString("folderSorts", null) ?: return emptyMap())
            o.keys().asSequence().mapNotNull { k ->
                val v = o.getString(k).split(':')
                runCatching { k to (Sort.valueOf(v[0]) to (v.getOrNull(1) == "1")) }.getOrNull()
            }.toMap()
        }.getOrDefault(emptyMap())
        set(v) {
            val o = org.json.JSONObject()
            v.forEach { (k, s) -> o.put(k, "${s.first.name}:${if (s.second) 1 else 0}") }
            sp.edit().putString("folderSorts", o.toString()).apply()
        }

    var sortReversed: Boolean
        get() = sp.getBoolean("sortReversed", false)
        set(v) = sp.edit().putBoolean("sortReversed", v).apply()

    var viewMode: ViewMode
        get() = runCatching { ViewMode.valueOf(sp.getString("viewMode", null)!!) }.getOrDefault(ViewMode.LIST)
        set(v) = sp.edit().putString("viewMode", v.name).apply()

    var viewSize: ViewSize
        get() = runCatching { ViewSize.valueOf(sp.getString("viewSize", null)!!) }.getOrDefault(ViewSize.MEDIUM)
        set(v) = sp.edit().putString("viewSize", v.name).apply()

    /** Accent that cycles through every hue ("RGB" preset). */
    var rgbAccent: Boolean
        get() = sp.getBoolean("rgbAccent", false)
        set(v) = sp.edit().putBoolean("rgbAccent", v).apply()

    /** null = not chosen yet (follows the installed version: betas get betas). */
    var updateBeta: Boolean?
        get() = if (sp.contains("updateBeta")) sp.getBoolean("updateBeta", false) else null
        set(v) = sp.edit().apply { if (v == null) remove("updateBeta") else putBoolean("updateBeta", v) }.apply()

    var autoUpdate: Boolean
        get() = sp.getBoolean("autoUpdate", true)
        set(v) = sp.edit().putBoolean("autoUpdate", v).apply()

    var lastUpdateCheck: Long
        get() = sp.getLong("lastUpdateCheck", 0L)
        set(v) = sp.edit().putLong("lastUpdateCheck", v).apply()

    var colorfulIcons: Boolean
        get() = sp.getBoolean("colorfulIcons", false)
        set(v) = sp.edit().putBoolean("colorfulIcons", v).apply()

    var editorFontSize: Int
        get() = sp.getInt("editorFontSize", 13)
        set(v) = sp.edit().putInt("editorFontSize", v).apply()

    var editorWrap: Boolean
        get() = sp.getBoolean("editorWrap", false)
        set(v) = sp.edit().putBoolean("editorWrap", v).apply()

    /** Edit with Ace in a WebView (fast on big files); off = the built-in Compose editor. */
    var aceEditor: Boolean
        get() = sp.getBoolean("aceEditor", true)
        set(v) = sp.edit().putBoolean("aceEditor", v).apply()

    var bedrockMode: Boolean
        get() = sp.getBoolean("bedrockMode", false)
        set(v) = sp.edit().putBoolean("bedrockMode", v).apply()

    var favorites: List<String>
        get() = list("favorites")
        set(v) = putList("favorites", v)

    var history: List<String>
        get() = list("history")
        set(v) = putList("history", v)

    private fun list(key: String): List<String> = runCatching {
        val a = JSONArray(sp.getString(key, null) ?: return emptyList())
        List(a.length()) { a.getString(it) }
    }.getOrDefault(emptyList())

    private fun putList(key: String, v: List<String>) = sp.edit().putString(key, JSONArray(v).toString()).apply()
}
