package com.abyssal.files

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class JsonToolsTest {
    @Test fun formatsMinifiedJson() {
        val out = JsonPretty.format("""{"a":[1,2],"b":{},"c":"x,{y}:\"z\"","d":[]}""")
        assertEquals(
            """
            {
              "a": [
                1,
                2
              ],
              "b": {},
              "c": "x,{y}:\"z\"",
              "d": []
            }
            """.trimIndent() + "\n",
            out,
        )
        // Same data after formatting, comments kept, trailing commas don't break the layout
        assertEquals(JsonMin.minify("""{"a":[1,2],"b":{}}"""), JsonMin.minify(JsonPretty.format("""{"a":[1,2],"b":{}}""")))
        assertTrue(JsonPretty.format("{// note\n\"a\":1}").contains("// note"))
        assertEquals("{\n  \"a\": 1,\n}\n", JsonPretty.format("{\"a\":1,}"))
    }

    @Test fun findsFoldableBlocks() {
        val t = JsonPretty.format("""{"a":{"b":[1,2]},"c":3,"s":"{not a block"}""")
        // {            0
        //   "a": {     1
        //     "b": [   2
        //       1,     3
        //       2      4
        //     ]        5
        //   },         6
        //   "c": 3,    7
        //   "s": "{not a block"  8
        // }            9
        val f = Folds.of(t)
        assertEquals(9, f.end[0]); assertEquals(6, f.end[1]); assertEquals(5, f.end[2])
        assertFalse(f.has(3)); assertFalse(f.has(8))
        assertEquals(listOf(0, 1, 2), (0 until f.lines).filter { f.has(it) }.map { f.depth[it] })
        assertArrayEquals(intArrayOf(0, 1, 7, 8, 9, 10), f.visible(setOf(1)))  // 10: empty last line
        assertArrayEquals(intArrayOf(0, 10), f.visible(setOf(0)))
        assertEquals(setOf(1, 2), f.deeperThan(1))
    }
}
