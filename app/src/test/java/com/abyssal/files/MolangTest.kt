package com.abyssal.files

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import com.abyssal.files.MolangBuild as B

class MolangTest {
    private fun at(text: String): Completion? {
        val cur = text.indexOf('|')
        return Molang.complete(text.removeRange(cur, cur + 1), cur)
    }

    @Test fun dataIsComplete() {
        assertTrue(MolangData.QUERIES.size > 300)
        assertTrue(MolangData.MATH.size > 30)
        assertEquals("equipment slot, slot index, item", Molang.query("is_item_name_any")!!.args)
        assertTrue(Molang.query("get_equipped_item_name")!!.deprecated)
    }

    @Test fun queriesAfterQAndQuery() {
        val c = at("\"q.|\"")!!
        assertEquals("is_on_ground", c.items.first().label)
        val d = at("\"query.is_item_n|\"")!!
        val s = d.items.first()
        assertEquals("is_item_name_any(equipment slot, slot index, item)", s.label)
        assertEquals("is_item_name_any(\$0)", s.insert)
        assertEquals("\"query.".length, d.start)
        // Queries without arguments are inserted bare
        assertEquals("is_moving", at("\"q.is_movi|\"")!!.items.first { it.label == "is_moving" }.insert)
        // Deprecated ones come last
        assertTrue(at("\"q.get_|\"")!!.items.last().detail.startsWith("deprecated"))
    }

    @Test fun otherNamespaces() {
        assertEquals("clamp(value, min, max)", at("\"math.cla|\"")!!.items.first().label)
        assertTrue(at("\"c.|\"")!!.items.any { it.label == "other" })
        val v = at("\"v.speed = 1; v.size = 2; v.s|\"")!!.items.map { it.label }
        assertEquals(listOf("size", "speed"), v)
        assertEquals(listOf("speed"), at("\"variable.speed = 1; v.sp|\"")!!.items.map { it.label })
    }

    @Test fun onlyRealNamespaces() {
        assertNull(at("\"variable.q.|\""))
        assertNull(at("\"foo.|\""))
        assertNull(at("\"q|\""))
        // An existing "(" isn't doubled
        assertEquals("is_item_name_any", at("\"q.is_item_name_a|('slot.weapon.mainhand')\"")!!.items.first().insert)
    }

    @Test fun stringDetection() {
        assertTrue(Molang.inJsonString("{ \"a\": \"q.", 10))
        assertFalse(Molang.inJsonString("{ \"a\": 1 }", 9))
        assertTrue(Molang.inJsonString("\"say \\\"hi\\\" q.", 14))
    }

    @Test fun builderMakesExpressions() {
        assertEquals("q.is_on_ground", B.condition(B.Cond(), short = true))
        assertEquals("!query.is_sneaking", B.condition(B.Cond(not = true, name = "is_sneaking"), short = false))
        assertEquals(
            "q.is_item_name_any('slot.weapon.mainhand', 0, 'minecraft:stick')",
            B.condition(B.Cond(name = "is_item_name_any", args = listOf("slot.weapon.mainhand", "0", "minecraft:stick")), true),
        )
        assertEquals("!(v.speed > 0.5)", B.condition(B.Cond(subject = B.Subject.VARIABLE, name = "speed", op = ">", value = "0.5", not = true), true))
        val conds = listOf(
            B.Cond(name = "is_moving"),
            B.Cond(join = "&&", name = "is_on_ground"),
            B.Cond(join = "||", subject = B.Subject.CUSTOM, name = "math.sin(q.anim_time * 90)", op = ">", value = "0"),
        )
        assertEquals("q.is_moving && q.is_on_ground || (math.sin(q.anim_time * 90)) > 0", B.build(conds, true, B.Result.CONDITION, "", "", ""))
        assertEquals("(q.is_moving && q.is_on_ground || (math.sin(q.anim_time * 90)) > 0) ? 1 : 0", B.build(conds, true, B.Result.TERNARY, "1", "0", ""))
        assertEquals("v.walking = q.is_moving;", B.build(conds.take(1), true, B.Result.ASSIGN, "", "", "walking"))
        assertEquals("q.is_moving ? 'walk' : q.anim_time", B.build(conds.take(1), true, B.Result.TERNARY, "walk", "q.anim_time", ""))
    }
}
