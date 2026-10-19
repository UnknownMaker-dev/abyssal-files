package com.abyssal.files

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BedrockTest {
    /** Completion labels at the "|" marker. */
    private fun js(src: String): List<String> {
        val cur = src.indexOf('|')
        val text = src.removeRange(cur, cur + 1)
        return Bedrock.completeJs(text, cur)?.items?.map { it.label } ?: listOf("<null>")
    }

    private fun json(src: String): Completion? {
        val cur = src.indexOf('|')
        return Bedrock.completeJson(src.removeRange(cur, cur + 1), cur)
    }

    @Test fun worldMembers() {
        val l = js("import { world } from \"@minecraft/server\";\nworld.|")
        assertTrue(l.containsAll(listOf("afterEvents", "getAllPlayers", "getDimension")))
        assertTrue(js("world.get|").all { it.startsWith("get") })
    }

    @Test fun eventsChain() {
        val l = js("world.afterEvents.|")
        assertTrue(l.contains("playerSpawn") && l.contains("entityHurt"))
        assertTrue(js("world.afterEvents.playerSpawn.|").contains("subscribe"))
        assertTrue(js("system.afterEvents.|").contains("scriptEventReceive"))
    }

    @Test fun callbackParameterTypes() {
        val src = "world.afterEvents.entityHurt.subscribe((ev) => {\n  ev.|"
        assertTrue(js(src).containsAll(listOf("damage", "damageSource", "hurtEntity")))
        val d = "world.afterEvents.playerSpawn.subscribe(({ player, initialSpawn }) => {\n  player.|"
        assertTrue(js(d).containsAll(listOf("sendMessage", "onScreenDisplay", "teleport")))   // Player + Entity members
        val chain = "world.afterEvents.entityHurt.subscribe((ev) => {\n  ev.damageSource.|"
        assertTrue(js(chain).contains("cause"))
    }

    @Test fun declarationsAndComponents() {
        val src = "const dim = world.getDimension(\"overworld\");\ndim.|"
        assertTrue(js(src).containsAll(listOf("spawnEntity", "getBlock", "getEntities")))
        val inv = "for (const pl of world.getAllPlayers()) {\n  const inv = pl.getComponent(\"minecraft:inventory\");\n  inv.container.|"
        assertTrue(js(inv).containsAll(listOf("addItem", "getItem", "setItem")))
        val h = "const hp = target.getComponent(EntityComponentTypes.Health);\nhp.|"
        assertTrue(js(h).contains("currentValue"))
        assertTrue(js("player.onScreenDisplay.|").contains("setActionBar"))
        assertTrue(js("const form = new ActionFormData();\nform.|").contains("button"))
    }

    @Test fun enumsAndStrings() {
        assertTrue(js("EquipmentSlot.|").containsAll(listOf("Mainhand", "Head")))
        assertTrue(js("import { world } from \"@minecraft/se|").contains("@minecraft/server"))
        assertTrue(js("new ItemStack(\"diam|").contains("minecraft:diamond"))
        assertTrue(js("player.addEffect(\"spe|").contains("speed"))
        assertTrue(js("dim.runCommand(\"tellr|").contains("tellraw"))
        assertTrue(js("if (block.typeId === \"minecraft:st|").contains("minecraft:stone"))
    }

    @Test fun notMinecraftContexts() {
        assertEquals(listOf("<null>"), js("console.|"))
        assertEquals(listOf("<null>"), js("const x = 0.|"))
        assertEquals(listOf("<null>"), js("cons|"))   // plain word: normal completion
    }

    @Test fun jsonComponentKeys() {
        val c = json("{\n  \"minecraft:entity\": {\n    \"components\": {\n      \"minecraft:hea|\"\n    }\n  }\n}")
        assertNotNull(c)
        val health = c!!.items.first { it.label == "minecraft:health" }
        assertTrue(health.insert.startsWith("\"minecraft:health\": {"))
        // The range covers the opening quote and the auto-inserted closing quote
        val src = "{\n  \"minecraft:entity\": {\n    \"components\": {\n      \"minecraft:hea\"\n    }\n  }\n}"
        assertEquals(src.indexOf("\"minecraft:hea"), c.start)
        assertEquals(src.indexOf("\"minecraft:hea") + "\"minecraft:hea\"".length, c.end)
    }

    @Test fun jsonKeysByPath() {
        val item = json("{ \"minecraft:item\": { \"components\": { \"|\" } } }")!!.items.map { it.label }
        assertTrue(item.contains("minecraft:icon") && item.contains("minecraft:durability"))
        val block = json("{ \"minecraft:block\": { \"components\": { \"minecraft:mat|\" } } }")!!.items.map { it.label }
        assertTrue(block.contains("minecraft:material_instances"))
        val header = json("{ \"header\": { \"name\": \"x\", \"|\" } }")!!.items.map { it.label }
        assertTrue(header.contains("uuid") && !header.contains("name"))   // existing keys are skipped
        val module = json("{ \"modules\": [ { \"|\" } ] }")!!.items.map { it.label }
        assertTrue(module.contains("entry"))
        val filter = json("{ \"minecraft:entity\": { \"events\": { \"e\": { \"filters\": { \"|\" } } } } }")!!.items.map { it.label }
        assertTrue(filter.contains("test"))
    }

    @Test fun jsonValues() {
        val v = json("{ \"modules\": [ { \"type\": \"scr|\" } ] }")!!.items.map { it.label }
        assertEquals("script", v.first())
        val t = json("{ \"filters\": { \"test\": \"is_fam|\" } }")!!.items.map { it.label }
        assertTrue(t.contains("is_family"))
        assertNull(json("{ \"description\": \"|\" }"))   // no hints: normal completion
    }

    @Test fun manifestSnippetHasFreshUuids() {
        val c = json("mani|")!!
        val script = c.items.first { it.label == "manifest_script" }.insert
        val uuids = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}").findAll(script).map { it.value }.toList()
        assertEquals(3, uuids.size)
        assertEquals(3, uuids.toSet().size)
        assertFalse(script.contains("<uuid>"))
    }

    @Test fun editorAcceptsJsonKey() {
        val s = CodeState(Langs.JSON)
        s.bedrock = true
        val text = "{\n  \"minecraft:item\": {\n    \"components\": {\n      \"minecraft:max\"\n    }\n  }\n}"
        val cur = text.indexOf("max\"") + 3
        s.reset(text); s.onEdit(TextFieldValue(text, TextRange(cur)))
        val c = s.complete()!!
        s.accept(c.items.first { it.label == "minecraft:max_stack_size" }, c)
        assertTrue(s.value.text.contains("      \"minecraft:max_stack_size\": 64\n"))
    }

    @Test fun editorMergesGlobalsForPlainWords() {
        val s = CodeState(Langs.JS)
        s.bedrock = true
        s.reset("wor"); s.onEdit(TextFieldValue("wor", TextRange(3)))
        assertTrue(s.complete()!!.items.any { it.label == "world" })
        s.bedrock = false
        assertTrue(s.complete()?.items.orEmpty().none { it.label == "world" })
    }
}
