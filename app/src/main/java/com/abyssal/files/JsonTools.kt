package com.abyssal.files

/** Re-indents JSON (comments and strings kept as written). Never fails: broken JSON is just reflowed. */
object JsonPretty {
    fun format(s: String, indent: Int = 2): String {
        val sb = StringBuilder(s.length + s.length / 2)
        var level = 0
        var i = 0
        fun newline() {
            sb.append('\n')
            repeat(level * indent) { sb.append(' ') }
        }
        fun trimTail() {
            while (sb.isNotEmpty() && (sb.last() == ' ' || sb.last() == '\t')) sb.setLength(sb.length - 1)
            if (sb.isNotEmpty() && sb.last() == '\n') sb.setLength(sb.length - 1)
        }
        while (i < s.length) {
            val c = s[i]
            when {
                c == '"' -> {
                    val start = i++
                    while (i < s.length && s[i] != '"' && s[i] != '\n') { if (s[i] == '\\') i++; i++ }
                    i = minOf(i + 1, s.length)
                    sb.append(s, start, i)
                    continue
                }
                c == '/' && s.getOrNull(i + 1) == '/' -> {
                    val end = s.indexOf('\n', i).let { if (it < 0) s.length else it }
                    if (sb.isNotEmpty() && sb.last() != ' ' && sb.last() != '\n') sb.append(' ')
                    sb.append(s, i, end).append('\n')
                    repeat(level * indent) { sb.append(' ') }
                    i = end
                    continue
                }
                c == '/' && s.getOrNull(i + 1) == '*' -> {
                    val end = s.indexOf("*/", i + 2).let { if (it < 0) s.length else it + 2 }
                    sb.append(s, i, end)
                    i = end
                    continue
                }
                c == '{' || c == '[' -> {
                    var j = i + 1
                    while (j < s.length && s[j].isWhitespace()) j++
                    if (j < s.length && s[j] == (if (c == '{') '}' else ']')) {
                        sb.append(c).append(s[j]); i = j + 1; continue
                    }
                    sb.append(c); level++; newline()
                }
                c == '}' || c == ']' -> {
                    level = (level - 1).coerceAtLeast(0)
                    trimTail(); newline(); sb.append(c)
                }
                c == ',' -> { sb.append(','); newline() }
                c == ':' -> sb.append(": ")
                c.isWhitespace() -> {}
                else -> sb.append(c)
            }
            i++
        }
        return sb.toString().trimEnd() + "\n"
    }
}

/**
 * Foldable blocks: for each line that opens a `{`/`[` closed on a later line, the closing line ([end], -1 if none)
 * and how deep it is ([depth], the outermost block is 0). Strings and comments are skipped.
 */
class Folds(val end: IntArray, val depth: IntArray) {
    val lines get() = end.size
    fun has(i: Int) = i in end.indices && end[i] > i

    /** Lines shown when [collapsed] blocks are folded (a folded block shows only its first line). */
    fun visible(collapsed: Set<Int>): IntArray {
        val out = IntArray(lines)
        var n = 0
        var i = 0
        while (i < lines) {
            out[n++] = i
            i = if (i in collapsed && has(i)) end[i] + 1 else i + 1
        }
        return out.copyOf(n)
    }

    /** Folds blocks at [minDepth] and deeper. */
    fun deeperThan(minDepth: Int): Set<Int> = (0 until lines).filter { has(it) && depth[it] >= minDepth }.toSet()

    /** For big files: fold from the shallowest depth that leaves at most [target] lines on screen (root stays open). */
    fun auto(target: Int = 600): Set<Int> {
        for (d in 3 downTo 1) {
            val c = deeperThan(d)
            if (visible(c).size <= target) return c
        }
        return deeperThan(1)
    }

    companion object {
        fun of(text: String): Folds {
            val starts = lineStarts(text)
            val end = IntArray(starts.size) { -1 }
            val depth = IntArray(starts.size)
            val stack = IntArrayStack()
            var line = 0
            var i = 0
            while (i < text.length) {
                val c = text[i]
                when {
                    c == '\n' -> line++
                    c == '"' -> {
                        i++
                        while (i < text.length && text[i] != '"' && text[i] != '\n') { if (text[i] == '\\') i++; i++ }
                        if (i < text.length && text[i] == '\n') line++
                    }
                    c == '/' && text.getOrNull(i + 1) == '/' -> {
                        i = text.indexOf('\n', i).let { if (it < 0) text.length else it - 1 }
                    }
                    c == '/' && text.getOrNull(i + 1) == '*' -> {
                        val e = text.indexOf("*/", i + 2).let { if (it < 0) text.length else it + 1 }
                        for (k in i until minOf(e, text.length)) if (text[k] == '\n') line++
                        i = e
                    }
                    c == '{' || c == '[' -> { stack.push(line) }
                    c == '}' || c == ']' -> if (stack.size > 0) {
                        val open = stack.pop()
                        if (line > open && line > end[open]) { end[open] = line; depth[open] = stack.size }
                    }
                }
                i++
            }
            return Folds(end, depth)
        }
    }
}

private class IntArrayStack {
    private var a = IntArray(64)
    var size = 0; private set
    fun push(v: Int) { if (size == a.size) a = a.copyOf(size * 2); a[size++] = v }
    fun pop(): Int = a[--size]
}
