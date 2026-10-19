package com.abyssal.files

import java.util.regex.Pattern

/** Token classes the highlighter colours. */
enum class Tok { COMMENT, STRING, NUMBER, KEYWORD, TYPE, FUNCTION, ANNOTATION, CONSTANT, TAG, ATTR, PROPERTY, HEADING, VARIABLE }

/** Autocomplete template; "$0" marks where the cursor goes. */
data class Snippet(val trigger: String, val body: String, val label: String)

class Lang(
    val name: String,
    keywords: String = "",
    types: String = "",
    builtins: String = "",
    constants: String = "",
    val lineComment: String? = null,
    val blockComment: Pair<String, String>? = null,
    val quotes: String = "\"'",
    val tripleQuotes: Boolean = false,
    val annotations: Boolean = false,
    val preprocessor: Boolean = false,
    val capsAreTypes: Boolean = false,
    val ignoreCase: Boolean = false,
    val indent: Int = 4,
    /** Python/YAML: a line ending in ':' opens a block. */
    val colonIndent: Boolean = false,
    /** Extra characters that belong to a word for autocomplete (e.g. '-' in CSS). */
    val wordExtra: String = "",
    val html: Boolean = false,
    /** Rules tried before the generic ones (or instead of them when [custom] is set). */
    extra: List<Pair<Tok, String>> = emptyList(),
    custom: Boolean = false,
    val snippets: List<Snippet> = emptyList(),
) {
    private fun words(s: String) = s.split(Regex("\\s+")).filter { it.isNotEmpty() }
        .map { if (ignoreCase) it.lowercase() else it }.toSet()

    val keywords = words(keywords)
    val types = words(types)
    val builtins = words(builtins)
    val constants = words(constants)

    /** Rule order matters at equal start positions; rule regexes must not contain capturing groups. */
    private val rules: List<Pair<Tok?, String>> = buildList {
        addAll(extra)
        if (!custom) {
            val comments = listOfNotNull(
                blockComment?.let { Pattern.quote(it.first) + "[\\s\\S]*?(?:" + Pattern.quote(it.second) + "|\\z)" },
                lineComment?.let { Pattern.quote(it) + ".*" },
            )
            if (comments.isNotEmpty()) add(Tok.COMMENT to comments.joinToString("|"))
            if (tripleQuotes) add(Tok.STRING to "\"\"\"[\\s\\S]*?(?:\"\"\"|\\z)|'''[\\s\\S]*?(?:'''|\\z)")
            quotes.forEach { q ->
                val e = Pattern.quote(q.toString())
                add(Tok.STRING to "$e(?:\\\\.|(?!$e)[^\\\\\\n])*$e?")
            }
            if (annotations) add(Tok.ANNOTATION to "@[A-Za-z_][\\w.]*")
            if (preprocessor) add(Tok.ANNOTATION to "^[ \\t]*#[ \\t]*\\w+")
            add(Tok.NUMBER to "\\b(?:0[xX][0-9a-fA-F_]+|0[bB][01_]+|\\d[\\d_]*(?:\\.\\d[\\d_]*)?(?:[eE][+-]?\\d+)?)[fFdDlLuU]*\\b")
            add(null to "[A-Za-z_][A-Za-z0-9_]*")
        }
    }

    val pattern: Pattern by lazy {
        Pattern.compile(rules.joinToString("|") { "(${it.second})" }, Pattern.MULTILINE)
    }

    private val ruleToks = rules.map { it.first }

    /** Calls [out] with (start, end, token) for every coloured range. */
    fun scan(text: CharSequence, out: (Int, Int, Tok) -> Unit) {
        val m = pattern.matcher(text)
        while (m.find()) {
            if (m.end() == m.start()) continue
            var g = 0
            while (g < ruleToks.size && m.start(g + 1) == -1) g++
            if (g == ruleToks.size) continue
            val tok = ruleToks[g] ?: classify(text, m.start(), m.end()) ?: continue
            out(m.start(), m.end(), tok)
        }
    }

    private fun classify(text: CharSequence, s: Int, e: Int): Tok? {
        val raw = text.subSequence(s, e).toString()
        val w = if (ignoreCase) raw.lowercase() else raw
        if (w in keywords) return Tok.KEYWORD
        if (w in constants) return Tok.CONSTANT
        if (w in types) return Tok.TYPE
        var i = e
        while (i < text.length && text[i] == ' ') i++
        if (i < text.length && (text[i] == '(' || (text[i] == '!' && name == "Rust"))) return Tok.FUNCTION
        if (w in builtins) return Tok.FUNCTION
        if (capsAreTypes && raw[0].isUpperCase() && raw.any { it.isLowerCase() }) return Tok.TYPE
        return null
    }

    /** Keywords, types, functions and snippets for autocomplete, with a one-letter kind. */
    val vocabulary: List<Pair<String, Char>> by lazy {
        (this.keywords.map { it to 'K' } + this.types.map { it to 'T' } + this.builtins.map { it to 'F' } + this.constants.map { it to 'C' })
            .distinctBy { it.first }
    }
}

// ───────────────────────── languages ─────────────────────────

private fun s(trigger: String, body: String, label: String = trigger) = Snippet(trigger, body, label)

object Langs {
    val PLAIN = Lang("Plain text", custom = true)

    val KOTLIN = Lang(
        "Kotlin",
        keywords = "package import class interface fun val var if else when for while do return break continue object companion " +
            "data sealed enum abstract open override private protected public internal lateinit by in is as try catch finally " +
            "throw this super typealias constructor init get set inline suspend operator infix vararg reified crossinline " +
            "noinline const annotation out where external tailrec value it",
        types = "Int Long Short Byte Float Double Boolean Char String Unit Any Nothing List MutableList Map MutableMap Set " +
            "MutableSet Array ArrayList HashMap Pair Triple Sequence",
        builtins = "println print listOf mutableListOf mapOf mutableMapOf setOf arrayOf require check error lazy let apply " +
            "also run with repeat TODO emptyList buildList takeIf",
        constants = "true false null",
        lineComment = "//", blockComment = "/*" to "*/", tripleQuotes = true, annotations = true, capsAreTypes = true,
        snippets = listOf(
            s("fun", "fun $0() {\n    \n}", "fun name() { }"),
            s("main", "fun main() {\n    $0\n}", "fun main()"),
            s("class", "class $0 {\n    \n}", "class Name { }"),
            s("data", "data class $0()", "data class"),
            s("println", "println($0)", "println(…)"),
            s("for", "for (i in 0 until $0) {\n    \n}", "for (i in 0 until n)"),
            s("when", "when ($0) {\n    else -> {}\n}", "when (x) { }"),
            s("if", "if ($0) {\n    \n}", "if (…) { }"),
        ),
    )

    val JAVA = Lang(
        "Java",
        keywords = "abstract assert break case catch class const continue default do else enum extends final finally for goto " +
            "if implements import instanceof interface native new package private protected public return static strictfp " +
            "super switch synchronized this throw throws transient try volatile while var record yield sealed permits",
        types = "int long short byte float double boolean char void String Object Integer Character List ArrayList Map " +
            "HashMap Set HashSet System Math Exception",
        constants = "true false null",
        lineComment = "//", blockComment = "/*" to "*/", annotations = true, capsAreTypes = true,
        snippets = listOf(
            s("main", "public static void main(String[] args) {\n    $0\n}", "main method"),
            s("sout", "System.out.println($0);", "System.out.println"),
            s("class", "public class $0 {\n    \n}", "public class"),
            s("for", "for (int i = 0; i < $0; i++) {\n    \n}", "for loop"),
            s("if", "if ($0) {\n    \n}", "if (…) { }"),
        ),
    )

    private const val JS_KW = "break case catch class const continue debugger default delete do else export extends finally for " +
        "function if import in instanceof let new return super switch this throw try typeof var void while with yield " +
        "async await of static get set from as"
    private val JS_SNIPPETS = listOf(
        s("log", "console.log($0);", "console.log"),
        s("function", "function $0() {\n  \n}", "function name() { }"),
        s("arrow", "const $0 = () => {\n  \n};", "const fn = () => { }"),
        s("for", "for (let i = 0; i < $0; i++) {\n  \n}", "for loop"),
        s("forof", "for (const item of $0) {\n  \n}", "for…of"),
        s("if", "if ($0) {\n  \n}", "if (…) { }"),
        s("import", "import $0 from '';", "import … from"),
        s("class", "class $0 {\n  constructor() {\n    \n  }\n}", "class"),
    )
    val JS = Lang(
        "JavaScript", keywords = JS_KW,
        types = "Array Object String Number Boolean Map Set Promise Date RegExp Error JSON Math",
        builtins = "console log require setTimeout setInterval parseInt parseFloat fetch alert document window",
        constants = "true false null undefined NaN Infinity",
        lineComment = "//", blockComment = "/*" to "*/", quotes = "\"'`", indent = 2, snippets = JS_SNIPPETS,
    )
    val TS = Lang(
        "TypeScript",
        keywords = "$JS_KW interface type enum implements private public protected readonly declare namespace abstract keyof infer is",
        types = "Array Object String Number Boolean Map Set Promise Date RegExp Error JSON Math number string boolean any " +
            "unknown never void",
        builtins = "console log require setTimeout setInterval parseInt parseFloat fetch",
        constants = "true false null undefined NaN Infinity",
        lineComment = "//", blockComment = "/*" to "*/", quotes = "\"'`", annotations = true, capsAreTypes = true, indent = 2,
        snippets = JS_SNIPPETS + s("interface", "interface $0 {\n  \n}", "interface"),
    )

    val PYTHON = Lang(
        "Python",
        keywords = "and as assert async await break class continue def del elif else except finally for from global if import " +
            "in is lambda nonlocal not or pass raise return try while with yield match case",
        builtins = "print len range open input int str float list dict set tuple bool type isinstance enumerate zip map filter " +
            "sorted sum min max abs round super self cls",
        constants = "True False None",
        lineComment = "#", tripleQuotes = true, annotations = true, colonIndent = true, capsAreTypes = true,
        snippets = listOf(
            s("def", "def $0():\n    pass", "def name():"),
            s("class", "class $0:\n    def __init__(self):\n        pass", "class Name:"),
            s("main", "if __name__ == \"__main__\":\n    $0", "if __name__ == \"__main__\""),
            s("for", "for i in range($0):\n    ", "for i in range(n):"),
            s("with", "with open($0) as f:\n    ", "with open(…) as f:"),
            s("print", "print($0)", "print(…)"),
        ),
    )

    private const val C_KW = "auto break case char const continue default do double else enum extern float for goto if inline int " +
        "long register return short signed sizeof static struct switch typedef union unsigned void volatile while bool"
    val C = Lang(
        "C", keywords = C_KW, types = "size_t uint8_t uint16_t uint32_t uint64_t int8_t int16_t int32_t int64_t FILE",
        builtins = "printf scanf malloc free memcpy strlen strcmp fopen fclose puts", constants = "true false NULL",
        lineComment = "//", blockComment = "/*" to "*/", preprocessor = true,
        snippets = listOf(
            s("main", "int main(void) {\n    $0\n    return 0;\n}", "int main()"),
            s("include", "#include <$0>", "#include <…>"),
            s("printf", "printf(\"$0\\n\");", "printf"),
            s("for", "for (int i = 0; i < $0; i++) {\n    \n}", "for loop"),
        ),
    )
    val CPP = Lang(
        "C++",
        keywords = "$C_KW class namespace template typename public private protected virtual override new delete this using " +
            "try catch throw nullptr constexpr noexcept auto friend operator",
        types = "std string vector map unordered_map set size_t cout cin endl",
        constants = "true false nullptr NULL",
        lineComment = "//", blockComment = "/*" to "*/", preprocessor = true, capsAreTypes = true,
        snippets = listOf(
            s("main", "int main() {\n    $0\n    return 0;\n}", "int main()"),
            s("cout", "std::cout << $0 << std::endl;", "std::cout"),
            s("include", "#include <$0>", "#include <…>"),
            s("class", "class $0 {\npublic:\n    \n};", "class"),
        ),
    )
    val CSHARP = Lang(
        "C#",
        keywords = "abstract as base break case catch checked class const continue default delegate do else enum event " +
            "explicit extern finally fixed for foreach goto if implicit in interface internal is lock namespace new " +
            "operator out override params private protected public readonly ref return sealed sizeof stackalloc static " +
            "struct switch this throw try typeof unchecked unsafe using virtual volatile while var async await get set",
        types = "bool byte char decimal double float int long object sbyte short string uint ulong ushort void",
        constants = "true false null", lineComment = "//", blockComment = "/*" to "*/", capsAreTypes = true,
        snippets = listOf(s("cw", "Console.WriteLine($0);", "Console.WriteLine")),
    )
    val GO = Lang(
        "Go",
        keywords = "break case chan const continue default defer else fallthrough for func go goto if import interface map " +
            "package range return select struct switch type var",
        types = "int int8 int16 int32 int64 uint uint8 uint32 uint64 float32 float64 string bool error byte rune any",
        builtins = "make new len cap append copy delete panic recover print println", constants = "true false nil iota",
        lineComment = "//", blockComment = "/*" to "*/", quotes = "\"'`", capsAreTypes = true,
        snippets = listOf(
            s("main", "func main() {\n\t$0\n}", "func main()"),
            s("iferr", "if err != nil {\n\treturn $0err\n}", "if err != nil"),
            s("func", "func $0() {\n\t\n}", "func name()"),
        ),
    )
    val RUST = Lang(
        "Rust",
        keywords = "as break const continue crate else enum extern fn for if impl in let loop match mod move mut pub ref " +
            "return self Self static struct super trait type unsafe use where while async await dyn",
        types = "i8 i16 i32 i64 i128 isize u8 u16 u32 u64 u128 usize f32 f64 bool char str String Vec Option Result Box",
        builtins = "println print format vec panic", constants = "true false Some None Ok Err",
        lineComment = "//", blockComment = "/*" to "*/", quotes = "\"", capsAreTypes = true,
        snippets = listOf(
            s("main", "fn main() {\n    $0\n}", "fn main()"),
            s("println", "println!(\"{}\", $0);", "println!"),
            s("fn", "fn $0() {\n    \n}", "fn name()"),
        ),
    )
    val PHP = Lang(
        "PHP",
        keywords = "abstract and array as break callable case catch class clone const continue declare default do echo else " +
            "elseif empty extends final finally fn for foreach function global if implements include include_once " +
            "instanceof interface isset list match namespace new or print private protected public require require_once " +
            "return static switch throw trait try unset use var while xor yield",
        constants = "true false null TRUE FALSE NULL", lineComment = "//", blockComment = "/*" to "*/",
        extra = listOf(Tok.VARIABLE to "\\$[A-Za-z_]\\w*", Tok.TAG to "<\\?php|\\?>"),
        snippets = listOf(s("echo", "echo $0;", "echo"), s("function", "function $0() {\n    \n}", "function")),
    )
    val RUBY = Lang(
        "Ruby",
        keywords = "alias and begin break case class def defined do else elsif end ensure for if in module next not or redo " +
            "rescue retry return self super then undef unless until when while yield require attr_accessor",
        builtins = "puts print p gets", constants = "true false nil", lineComment = "#", indent = 2, capsAreTypes = true,
        snippets = listOf(s("def", "def $0\n  \nend", "def … end")),
    )
    val SWIFT = Lang(
        "Swift",
        keywords = "class deinit enum extension func import init inout internal let open operator private protocol public " +
            "static struct subscript typealias var break case continue default defer do else fallthrough for guard if in " +
            "repeat return switch where while as catch is rethrows super self throw throws try async await",
        types = "Int Double Float String Bool Character Array Dictionary Set Optional Any", builtins = "print",
        constants = "true false nil", lineComment = "//", blockComment = "/*" to "*/", annotations = true, capsAreTypes = true,
        snippets = listOf(s("func", "func $0() {\n    \n}", "func name()")),
    )
    val DART = Lang(
        "Dart",
        keywords = "abstract as assert async await break case catch class const continue default deferred do dynamic else " +
            "enum export extends extension external factory final finally for get if implements import in interface is " +
            "late library mixin new on operator part required rethrow return set show static super switch sync this " +
            "throw try typedef var void while with yield",
        types = "int double num String bool List Map Set Future Stream Object Widget", builtins = "print",
        constants = "true false null", lineComment = "//", blockComment = "/*" to "*/", annotations = true,
        capsAreTypes = true, indent = 2,
        snippets = listOf(s("main", "void main() {\n  $0\n}", "void main()")),
    )
    val LUA = Lang(
        "Lua",
        keywords = "and break do else elseif end for function goto if in local not or repeat return then until while",
        builtins = "print pairs ipairs tostring tonumber type require table string math", constants = "true false nil",
        lineComment = "--", blockComment = "--[[" to "]]", indent = 2,
        snippets = listOf(
            s("function", "function $0()\n  \nend", "function … end"),
            s("for", "for i = 1, $0 do\n  \nend", "for i = 1, n"),
        ),
    )
    val SHELL = Lang(
        "Shell",
        keywords = "if then else elif fi case esac for while until do done in function return exit export local readonly " +
            "declare source alias unset shift",
        builtins = "echo printf cd ls rm cp mv mkdir cat grep sed awk chmod chown find curl wget sudo apt pkg",
        constants = "true false", lineComment = "#", indent = 2,
        extra = listOf(Tok.VARIABLE to "\\$\\{[^}\\n]*\\}|\\$[A-Za-z_0-9@#?*]\\w*"),
        snippets = listOf(
            s("shebang", "#!/bin/sh\n$0", "#!/bin/sh"),
            s("if", "if [ $0 ]; then\n  \nfi", "if [ … ]; then"),
            s("for", "for f in $0; do\n  \ndone", "for … do"),
        ),
    )
    val BATCH = Lang(
        "Batch",
        keywords = "echo set if else for in do goto call exit rem pause cls setlocal endlocal not exist defined errorlevel " +
            "off on start shift", lineComment = "::", ignoreCase = true,
        extra = listOf(Tok.COMMENT to "(?i)^[ \\t]*rem\\b.*", Tok.VARIABLE to "%[^%\\s]+%|%~?\\d"),
    )
    val SQL = Lang(
        "SQL",
        keywords = "select from where insert into values update set delete create table drop alter add column primary key " +
            "foreign references index view join inner left right outer on group by order having limit offset as and or not " +
            "is in like between distinct union all case when then else end begin commit rollback if exists default unique",
        types = "int integer bigint smallint text varchar char date datetime timestamp boolean real float double decimal blob",
        builtins = "count sum avg min max coalesce length upper lower now", constants = "null true false",
        lineComment = "--", blockComment = "/*" to "*/", ignoreCase = true, indent = 2,
        snippets = listOf(
            s("select", "SELECT $0 FROM ", "SELECT … FROM"),
            s("insert", "INSERT INTO $0 () VALUES ();", "INSERT INTO"),
            s("create", "CREATE TABLE $0 (\n  id INTEGER PRIMARY KEY\n);", "CREATE TABLE"),
        ),
    )
    val DOCKER = Lang(
        "Dockerfile",
        keywords = "from run cmd copy add env expose workdir entrypoint arg label user volume healthcheck shell onbuild as",
        lineComment = "#", ignoreCase = true, indent = 2,
    )

    /** Minecraft functions (.mcfunction). */
    val MCFUNCTION = Lang(
        "mcfunction",
        keywords = "advancement attribute ban bossbar clear clone damage data datapack debug defaultgamemode difficulty effect " +
            "enchant execute experience fill fillbiome forceload function gamemode gamerule give help item kick kill list " +
            "locate loot me msg op pardon particle place playsound recipe reload return ride say schedule scoreboard seed " +
            "setblock setworldspawn spawnpoint spectate spreadplayers stop stopsound summon tag team teammsg tell tellraw " +
            "time title tp teleport trigger weather whitelist worldborder xp camera dialogue fog event structure tickingarea",
        types = "as at positioned rotated facing anchored align in if unless store run result success entity block blocks " +
            "score matches players objectives add remove set get merge modify value replace destroy keep hollow outline " +
            "dummy display sidebar list belowName creative survival adventure spectator clear rain thunder day night",
        constants = "true false",
        lineComment = "#", quotes = "\"", indent = 2,
        extra = listOf(
            Tok.ANNOTATION to "@[aeprsn](?:\\[[^\\]\\n]*\\])?",
            Tok.TAG to "\\b[a-z0-9_.-]+:[a-z0-9_/.-]+",
            Tok.NUMBER to "[~^]-?\\d*\\.?\\d*",
        ),
        snippets = listOf(
            s("execute", "execute as @a at @s run $0", "execute as @a at @s run …"),
            s("tellraw", "tellraw @a {\"text\":\"$0\"}", "tellraw @a {\"text\":…}"),
            s("summon", "summon minecraft:$0 ~ ~ ~", "summon … ~ ~ ~"),
            s("give", "give @p minecraft:$0 1", "give @p …"),
            s("scoreboard", "scoreboard objectives add $0 dummy", "scoreboard objectives add"),
            s("tp", "tp @s ~ ~ ~$0", "tp @s ~ ~ ~"),
            s("effect", "effect give @s minecraft:$0 30 1", "effect give"),
            s("setblock", "setblock ~ ~ ~ minecraft:$0", "setblock ~ ~ ~"),
            s("fill", "fill ~-1 ~-1 ~-1 ~1 ~1 ~1 minecraft:$0", "fill … …"),
        ),
    )

    private const val HTML_TAGS = "html head body title meta link script style div span p a img ul ol li table thead tbody tr " +
        "td th form input button select option textarea label h1 h2 h3 h4 h5 h6 header footer nav main section article " +
        "aside br hr strong em code pre canvas svg video audio source iframe"
    val HTML = Lang(
        "HTML", keywords = HTML_TAGS,
        builtins = "class id href src alt style type name value rel charset content width height placeholder onclick",
        indent = 2, wordExtra = "-", html = true, custom = true,
        extra = listOf(
            Tok.COMMENT to "<!--[\\s\\S]*?(?:-->|\\z)",
            Tok.KEYWORD to "(?i)<!DOCTYPE[^>]*>",
            Tok.TAG to "</?[A-Za-z][\\w:.-]*|/?>",
            Tok.ATTR to "\\b[\\w:-]+(?=\\s*=\\s*[\"'])",
            Tok.STRING to "(?<==)\\s*\"[^\"\\n]*\"?|(?<==)\\s*'[^'\\n]*'?",
            Tok.CONSTANT to "&#?\\w+;",
        ),
        snippets = listOf(
            s("html5", "<!doctype html>\n<html>\n<head>\n  <meta charset=\"utf-8\">\n  <title>$0</title>\n</head>\n<body>\n  \n</body>\n</html>", "HTML page"),
            s("a", "<a href=\"$0\"></a>", "<a href>"),
            s("img", "<img src=\"$0\" alt=\"\">", "<img src>"),
            s("script", "<script src=\"$0\"></script>", "<script src>"),
            s("link", "<link rel=\"stylesheet\" href=\"$0\">", "<link stylesheet>"),
        ),
    )
    val XML = Lang("XML", indent = 2, wordExtra = "-", html = true, custom = true, extra = listOf(
            Tok.COMMENT to "<!--[\\s\\S]*?(?:-->|\\z)",
            Tok.KEYWORD to "<\\?[\\s\\S]*?\\?>|<!\\[CDATA\\[[\\s\\S]*?\\]\\]>",
            Tok.TAG to "</?[A-Za-z_][\\w:.-]*|/?>",
            Tok.ATTR to "\\b[\\w:.-]+(?=\\s*=\\s*[\"'])",
            Tok.STRING to "(?<==)\\s*\"[^\"\\n]*\"?|(?<==)\\s*'[^'\\n]*'?",
        ),
    )
    val CSS = Lang(
        "CSS",
        builtins = "color background background-color margin padding border border-radius display flex grid position top " +
            "left right bottom width height font font-size font-weight font-family text-align line-height opacity " +
            "transition transform z-index overflow cursor box-shadow justify-content align-items gap",
        keywords = "none auto block inline flex grid absolute relative fixed sticky center bold solid hidden inherit important",
        indent = 2, wordExtra = "-", custom = true,
        extra = listOf(
            Tok.COMMENT to "/\\*[\\s\\S]*?(?:\\*/|\\z)",
            Tok.STRING to "\"[^\"\\n]*\"?|'[^'\\n]*'?",
            Tok.ANNOTATION to "@[\\w-]+",
            Tok.NUMBER to "#[0-9a-fA-F]{3,8}\\b|-?\\d*\\.?\\d+(?:px|em|rem|%|vh|vw|s|ms|deg|fr)?\\b",
            Tok.PROPERTY to "[\\w-]+(?=\\s*:[^:{]*[;}\\n])",
            Tok.TAG to "[.#][A-Za-z_][\\w-]*",
            Tok.KEYWORD to "!important|\\b(?:none|auto|block|inline|flex|grid|absolute|relative|fixed|center|bold|solid|hidden|inherit)\\b",
        ),
    )
    val JSON = Lang(
        "JSON", indent = 2, custom = true,
        extra = listOf(
            Tok.COMMENT to "//.*|/\\*[\\s\\S]*?(?:\\*/|\\z)",
            Tok.PROPERTY to "\"(?:\\\\.|[^\"\\\\\\n])*\"(?=\\s*:)",
            Tok.STRING to "\"(?:\\\\.|[^\"\\\\\\n])*\"?",
            Tok.NUMBER to "-?\\b\\d+(?:\\.\\d+)?(?:[eE][+-]?\\d+)?\\b",
            Tok.CONSTANT to "\\b(?:true|false|null)\\b",
        ),
    )
    val CONFIG = Lang(
        "Config", indent = 2, colonIndent = true, custom = true,
        extra = listOf(
            Tok.COMMENT to "(?:^|(?<=\\s))[#;].*",
            Tok.TAG to "^[ \\t]*\\[[^\\]\\n]*\\]",
            Tok.PROPERTY to "^[ \\t-]*[\\w.\"'-]+(?=[ \\t]*[:=])",
            Tok.STRING to "\"(?:\\\\.|[^\"\\\\\\n])*\"?|'[^'\\n]*'?",
            Tok.NUMBER to "-?\\b\\d+(?:\\.\\d+)?\\b",
            Tok.CONSTANT to "\\b(?:true|false|yes|no|on|off|null)\\b",
        ),
    )
    val MARKDOWN = Lang(
        "Markdown", indent = 2, custom = true,
        extra = listOf(
            Tok.STRING to "```[\\s\\S]*?(?:```|\\z)|`[^`\\n]*`",
            Tok.HEADING to "^#{1,6}[ \\t].*",
            Tok.COMMENT to "^>.*",
            Tok.KEYWORD to "\\*\\*[^*\\n]+\\*\\*|__[^_\\n]+__",
            Tok.ANNOTATION to "(?<![*\\w])\\*[^*\\n]+\\*(?!\\*)|(?<!\\w)_[^_\\n]+_(?!\\w)",
            Tok.TAG to "!?\\[[^\\]\\n]*\\]\\([^)\\n]*\\)",
            Tok.NUMBER to "^[ \\t]*(?:[-*+]|\\d+\\.)(?=[ \\t])",
        ),
        snippets = listOf(
            s("link", "[$0]()", "[text](url)"),
            s("code", "```\n$0\n```", "code block"),
            s("table", "| $0 |  |\n|---|---|\n|  |  |", "table"),
        ),
    )

    private val byExt = mapOf(
        "kt" to KOTLIN, "kts" to KOTLIN, "gradle" to KOTLIN, "java" to JAVA,
        "js" to JS, "mjs" to JS, "cjs" to JS, "jsx" to JS, "ts" to TS, "tsx" to TS, "vue" to JS, "svelte" to JS,
        "py" to PYTHON, "pyw" to PYTHON, "c" to C, "h" to C, "cpp" to CPP, "cc" to CPP, "cxx" to CPP, "hpp" to CPP,
        "cs" to CSHARP, "go" to GO, "rs" to RUST, "php" to PHP, "rb" to RUBY, "swift" to SWIFT, "dart" to DART,
        "lua" to LUA, "sh" to SHELL, "bash" to SHELL, "zsh" to SHELL, "fish" to SHELL, "bat" to BATCH, "cmd" to BATCH,
        "ps1" to SHELL, "sql" to SQL, "mcfunction" to MCFUNCTION, "html" to HTML, "htm" to HTML, "xhtml" to HTML,
        "xml" to XML, "svg" to XML, "plist" to XML, "xsd" to XML, "css" to CSS, "scss" to CSS, "less" to CSS,
        "json" to JSON, "jsonc" to JSON, "json5" to JSON, "mcmeta" to JSON, "ipynb" to JSON,
        "yaml" to CONFIG, "yml" to CONFIG, "toml" to CONFIG, "ini" to CONFIG, "cfg" to CONFIG, "conf" to CONFIG,
        "properties" to CONFIG, "env" to CONFIG, "lang" to CONFIG, "editorconfig" to CONFIG,
        "md" to MARKDOWN, "markdown" to MARKDOWN, "mdx" to MARKDOWN, "dockerfile" to DOCKER,
    )

    fun forFile(name: String): Lang {
        val n = name.lowercase()
        return when {
            n == "dockerfile" -> DOCKER
            n == "makefile" -> SHELL
            n.startsWith(".") && n.endsWith("rc") -> SHELL
            else -> byExt[n.substringAfterLast('.', "")] ?: PLAIN
        }
    }
}
