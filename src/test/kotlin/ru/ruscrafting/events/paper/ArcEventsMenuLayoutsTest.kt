package ru.ruscrafting.events.paper

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.yaml.snakeyaml.Yaml
import java.nio.file.Files

class ArcEventsMenuLayoutsTest : StringSpec({
    "menu actions and regions follow the validated config" {
        val root = Files.createTempDirectory("arcevents-layout-")
        val source = requireNotNull(ArcEventsMenuLayoutsTest::class.java.classLoader.getResourceAsStream("config.yml"))
        val data = source.use { Yaml().load<MutableMap<String, Any?>>(it) }
        @Suppress("UNCHECKED_CAST")
        val ui = data.getValue("ui") as MutableMap<String, Any?>
        @Suppress("UNCHECKED_CAST")
        val layouts = ui.getValue("layouts") as MutableMap<String, Any?>
        @Suppress("UNCHECKED_CAST")
        val main = layouts.getValue("main") as MutableMap<String, Any?>
        @Suppress("UNCHECKED_CAST")
        ((main.getValue("elements") as MutableMap<String, Any?>).getValue("help") as MutableMap<String, Any?>)["slot"] = 21
        Files.writeString(root.resolve("config.yml"), Yaml().dump(data))

        val catalog = ArcEventsMenuLayouts.loadConfiguration(root)
        catalog.require(ArcEventsMenuLayouts.MAIN).slot("help").index shouldBe 21
        catalog.require(ArcEventsMenuLayouts.ARENAS).region("arenas").size shouldBe 8
    }

    "overlap rejects the whole layout generation" {
        val root = Files.createTempDirectory("arcevents-overlap-")
        val source = requireNotNull(ArcEventsMenuLayoutsTest::class.java.classLoader.getResourceAsStream("config.yml"))
        val yaml = source.bufferedReader().use { it.readText() }
            .replace("admin: {slot: 26}", "admin: {slot: 22}")
        Files.writeString(root.resolve("config.yml"), yaml)

        runCatching { ArcEventsMenuLayouts.loadConfiguration(root) }.exceptionOrNull()?.message.orEmpty() shouldContain
            "already occupied"
    }

    "undersized arena region is rejected before activation" {
        val root = Files.createTempDirectory("arcevents-capacity-")
        val source = requireNotNull(ArcEventsMenuLayoutsTest::class.java.classLoader.getResourceAsStream("config.yml"))
        val yaml = source.bufferedReader().use { it.readText() }
            .replace("arenas: {slots: [10, 12, 14, 16, 28, 30, 32, 34]}", "arenas: {slots: [10, 12]}")
        Files.writeString(root.resolve("config.yml"), yaml)

        runCatching { ArcEventsMenuLayouts.loadConfiguration(root) }.exceptionOrNull()?.message.orEmpty() shouldContain
            "needs at least 8 slots, got 2"
    }
})
