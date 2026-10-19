package com.abyssal.files

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CodeEditorTest {
    private val allLangs: List<Lang> = Langs::class.java.declaredFields
        .filter { it.type == Lang::class.java }
        .map { it.isAccessible = true; it.get(Langs) as Lang }

    private val sample = """
        // comment /* block */ # hash -- dash
        fun main() { val x = "str\"ing" + 'c' + 0x1F + 3.14e2; println(x) }
        <div class="a">&amp;</div> @Annotation ${'$'}var ~ ^1 @a[tag=x] minecraft:stone
        key: value [section] **bold** `code`
    """.trimIndent()

    @Test fun everyLanguageHighlights() {
        assertTrue(allLangs.size > 25)
        allLangs.forEach { lang ->
            var count = 0
            lang.scan(sample) { s, e, _ -> assertTrue("${lang.name} range", s in 0 until e && e <= sample.length); count++ }
            if (lang !== Langs.PLAIN) assertTrue("${lang.name} found nothing", count > 0)
        }
    }

    private fun toks(lang: Lang, text: String) = buildList { lang.scan(text) { s, e, t -> add(text.substring(s, e) to t) } }

    @Test fun kotlinTokens() {
        val t = toks(Langs.KOTLIN, "fun main() { val s = \"hi\" // note\n println(42) }")
        assertTrue(("fun" to Tok.KEYWORD) in t)
        assertTrue(("main" to Tok.FUNCTION) in t)
        assertTrue(("\"hi\"" to Tok.STRING) in t)
        assertTrue(("// note" to Tok.COMMENT) in t)
        assertTrue(("42" to Tok.NUMBER) in t)
    }

    @Test fun mcfunctionTokens() {
        val t = toks(Langs.MCFUNCTION, "# spawn\nexecute as @a[tag=x] at @s run summon minecraft:zombie ~ ~1 ~")
        assertTrue(("# spawn" to Tok.COMMENT) in t)
        assertTrue(("execute" to Tok.KEYWORD) in t)
        assertTrue(("@a[tag=x]" to Tok.ANNOTATION) in t)
        assertTrue(("minecraft:zombie" to Tok.TAG) in t)
        assertTrue(("~1" to Tok.NUMBER) in t)
    }

    @Test fun htmlAndJsonTokens() {
        val h = toks(Langs.HTML, "<a href=\"x\">it's</a>")
        assertTrue(("<a" to Tok.TAG) in h)
        assertTrue(("href" to Tok.ATTR) in h)
        assertTrue(h.none { it.second == Tok.STRING && it.first.contains("'s") })
        val j = toks(Langs.JSON, "{\"k\": \"v\", \"n\": 1, \"b\": true}")
        assertTrue(("\"k\"" to Tok.PROPERTY) in j)
        assertTrue(("\"v\"" to Tok.STRING) in j)
        assertTrue(("true" to Tok.CONSTANT) in j)
    }

    private fun typeAt(state: CodeState, text: String, cursor: Int, ch: String) {
        state.reset(text)
        state.onEdit(TextFieldValue(text, TextRange(cursor)))
        state.type(ch)
    }

    @Test fun autoIndentAndPairs() {
        val s = CodeState(Langs.KOTLIN)
        typeAt(s, "fun a() {}", 9, "\n")
        assertEquals("fun a() {\n    \n}", s.value.text)
        assertEquals(14, s.value.selection.start)

        typeAt(s, "x", 1, "(")
        assertEquals("x()", s.value.text)
        s.type(")")                     // types over the auto-inserted ')'
        assertEquals("x()", s.value.text)
        assertEquals(3, s.value.selection.start)

        typeAt(s, "    if (a)", 10, "\n")
        assertEquals("    if (a)\n    ", s.value.text)
    }

    @Test fun backspaceRemovesPair() {
        val s = CodeState(Langs.JS)
        s.reset("f()"); s.onEdit(TextFieldValue("f()", TextRange(2)))
        s.onEdit(TextFieldValue("f)", TextRange(1)))
        assertEquals("f", s.value.text)
    }

    @Test fun pythonColonIndent() {
        val s = CodeState(Langs.PYTHON)
        typeAt(s, "def f():", 8, "\n")
        assertEquals("def f():\n    ", s.value.text)
    }

    @Test fun htmlClosesTags() {
        val s = CodeState(Langs.HTML)
        typeAt(s, "<div class=\"a\"", 14, ">")
        assertEquals("<div class=\"a\"></div>", s.value.text)
        assertEquals(15, s.value.selection.start)
        typeAt(s, "<br", 3, ">")
        assertEquals("<br>", s.value.text)
    }

    @Test fun completionAndSnippet() {
        val s = CodeState(Langs.KOTLIN)
        s.reset("    pri"); s.onEdit(TextFieldValue("    pri", TextRange(7)))
        val (start, prefix) = s.prefix()!!
        assertEquals("pri", prefix)
        val sug = s.suggestions(prefix)
        assertTrue(sug.any { it.label == "println" })
        s.accept(sug.first { it.label == "println" && it.kind == 'S' }, Completion(start, 7, sug))
        assertEquals("    println()", s.value.text)
        assertEquals(12, s.value.selection.start)

        s.reset("    ma"); s.onEdit(TextFieldValue("    ma", TextRange(6)))
        s.accept(s.suggestions("ma").first { it.label == "main" }, Completion(4, 6, emptyList()))
        assertEquals("    fun main() {\n        \n    }", s.value.text)
    }

    @Test fun undoRedo() {
        val s = CodeState(Langs.PLAIN)
        s.reset("a"); s.onEdit(TextFieldValue("a", TextRange(1)))
        s.type(" b")
        assertEquals("a b", s.value.text)
        s.undo(); assertEquals("a", s.value.text)
        s.redo(); assertEquals("a b", s.value.text)
    }

    @Test fun languageDetection() {
        assertEquals("mcfunction", Langs.forFile("tick.mcfunction").name)
        assertEquals("Dockerfile", Langs.forFile("Dockerfile").name)
        assertEquals("JSON", Langs.forFile("pack.mcmeta").name)
        assertEquals("Plain text", Langs.forFile("notes.txt").name)
    }

    @Test fun linesKeepTokensAcrossLines() {
        val lines = highlightLines("/* a\nb */ x", Langs.JAVA, SyntaxColors(androidx.compose.ui.graphics.Color.Magenta))
        assertEquals(2, lines.size)
        assertEquals(1, lines[0].spanStyles.size)
        assertEquals(0 to 4, lines[1].spanStyles[0].let { it.start to it.end })
    }
}
