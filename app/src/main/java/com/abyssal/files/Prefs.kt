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

    var sortReversed: Boolean
        get() = sp.getBoolean("sortReversed", false)
        set(v) = sp.edit().putBoolean("sortReversed", v).apply()

    var viewMode: ViewMode
        get() = runCatching { ViewMode.valueOf(sp.getString("viewMode", null)!!) }.getOrDefault(ViewMode.LIST)
        set(v) = sp.edit().putString("viewMode", v.name).apply()

    var viewSize: ViewSize
        get() = runCatching { ViewSize.valueOf(sp.getString("viewSize", null)!!) }.getOrDefault(ViewSize.MEDIUM)
        set(v) = sp.edit().putString("viewSize", v.name).apply()

    var colorfulIcons: Boolean
        get() = sp.getBoolean("colorfulIcons", false)
        set(v) = sp.edit().putBoolean("colorfulIcons", v).apply()

    var editorFontSize: Int
        get() = sp.getInt("editorFontSize", 13)
        set(v) = sp.edit().putInt("editorFontSize", v).apply()

    var editorWrap: Boolean
        get() = sp.getBoolean("editorWrap", false)
        set(v) = sp.edit().putBoolean("editorWrap", v).apply()

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
