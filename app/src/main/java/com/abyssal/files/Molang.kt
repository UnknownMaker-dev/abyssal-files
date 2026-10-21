package com.abyssal.files

/** A Molang query or math function. [args]: null = none, "…" = takes arguments the docs don't name. */
data class MolangFn(val name: String, val args: String?, val doc: String, val deprecated: Boolean) {
    val signature: String get() = if (args == null) name else "$name($args)"
}

/** Molang autocomplete: typing "q." / "query.", "math.", "v." / "variable.", "t." / "temp." or "c." / "context.". */
object Molang {
    val CONTEXT = listOf(
        "other" to "The other entity in an interaction",
        "owning_entity" to "The entity that owns this one (e.g. who shot a projectile)",
        "player" to "The player",
        "item_slot" to "The item slot being rendered (attachables)",
        "block_face" to "The face of the block being interacted with",
        "cardinal_block_face_placed_on" to "The face the block was placed on",
        "is_first_person" to "1.0 when rendered in first person",
        "player_offhand_arm_height" to "Height of the off-hand arm (attachables)",
    )

    /** Listed first right after "q.", before anything is typed. */
    private val POPULAR = listOf(
        "is_on_ground", "is_moving", "is_sneaking", "is_sprinting", "is_jumping", "is_swimming", "is_in_water", "is_riding",
        "is_alive", "health", "max_health", "modified_distance_moved", "modified_move_speed", "ground_speed",
        "vertical_speed", "anim_time", "life_time", "time_of_day", "is_using_item", "is_item_name_any", "has_any_family",
        "is_name_any", "variant", "mark_variant", "skin_id", "property", "is_baby", "is_on_fire", "hurt_time",
        "target_x_rotation", "target_y_rotation", "head_x_rotation", "head_y_rotation", "body_y_rotation",
        "is_first_person", "position", "distance_from_camera",
    )

    val NAMESPACES = mapOf(
        "q" to "query", "query" to "query", "v" to "variable", "variable" to "variable", "t" to "temp", "temp" to "temp",
        "c" to "context", "context" to "context", "math" to "math",
    )

    private fun isId(c: Char) = c.isLetterOrDigit() || c == '_'

    /** True when [cursor] is inside a JSON string on its line (Molang always lives in strings). */
    fun inJsonString(text: String, cursor: Int): Boolean {
        val start = text.lastIndexOf('\n', cursor - 1) + 1
        var inside = false
        var i = start
        while (i < cursor) {
            when (text[i]) {
                '\\' -> if (inside) i++
                '"' -> inside = !inside
            }
            i++
        }
        return inside
    }

    /** null when the cursor isn't right after a Molang namespace. */
    fun complete(text: String, cursor: Int): Completion? {
        var s = cursor
        while (s > 0 && isId(text[s - 1])) s--
        if (s == 0 || text[s - 1] != '.') return null
        var n = s - 1
        while (n > 0 && isId(text[n - 1])) n--
        // "variable.q." is a member chain, not the query namespace
        if (n > 0 && text[n - 1] == '.') return null
        val ns = NAMESPACES[text.substring(n, s - 1).lowercase()] ?: return null
        val word = text.substring(s, cursor)
        val items = when (ns) {
            "query" -> functions(MolangData.QUERIES, word, POPULAR)
            "math" -> functions(MolangData.MATH, word, emptyList())
            "context" -> CONTEXT.filter { it.first.contains(word, true) && it.first != word }
                .map { Suggestion(it.first, it.first, 'P', it.second) }
            else -> usedNames(text, ns, word).map { Suggestion(it, it, 'W', "$ns used in this file") }
        }
        val hasParen = text.getOrNull(cursor) == '('
        return Completion(s, cursor, if (hasParen) items.map { it.copy(insert = it.insert.substringBefore('(')) } else items)
    }

    private fun functions(list: List<MolangFn>, word: String, popular: List<String>): List<Suggestion> =
        list.filter { it.name.contains(word, ignoreCase = true) && it.name != word }
            .sortedWith(
                compareBy<MolangFn> { it.deprecated }
                    .thenBy { !it.name.startsWith(word, true) }
                    .thenBy { if (word.isEmpty()) popular.indexOf(it.name).let { i -> if (i < 0) Int.MAX_VALUE else i } else 0 }
                    .thenBy { it.name }
            )
            .take(80)
            .map { f ->
                Suggestion(
                    f.signature, if (f.args != null) "${f.name}(\$0)" else f.name, 'F',
                    (if (f.deprecated) "deprecated · " else "") + f.doc,
                )
            }

    /** Names already used in the file; "v.x" and "variable.x" are the same variable. */
    fun usedNames(text: String, ns: String, word: String = ""): List<String> {
        val short = when (ns) { "variable" -> "v"; "temp" -> "t"; "context" -> "c"; else -> ns }
        return Regex("\\b(?:$ns|$short)\\.([A-Za-z_][A-Za-z0-9_]*)").findAll(text)
            .map { it.groupValues[1] }.filter { it != word && it.startsWith(word, true) }
            .distinct().sorted().take(40).toList()
    }

    fun query(name: String): MolangFn? = MolangData.QUERIES.firstOrNull { it.name == name }
}
