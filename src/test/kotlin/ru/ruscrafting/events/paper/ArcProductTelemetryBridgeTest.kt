package ru.ruscrafting.events.paper

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import java.util.UUID

class ArcProductTelemetryBridgeTest : StringSpec({
    "fails closed when ARC is absent" {
        ArcProductTelemetryBridge.completed(UUID.randomUUID(), "match:missing:player") shouldBe false
    }

    "keeps the event contract and stable operation id" {
        val playerId = UUID.randomUUID()
        val calls = mutableListOf<List<Any>>()
        ArcProductTelemetryBridge.recordWith(
            gateway = { id, source, event, operationId ->
                calls += listOf(id, source, event, operationId)
                true
            },
            playerId,
            "match:1:$playerId",
        ) shouldBe true
        calls shouldContainExactly listOf(listOf(playerId, "arcevents", "event_completed", "match:1:$playerId"))
    }

    "fails closed when the optional gateway throws" {
        ArcProductTelemetryBridge.recordWith({ _, _, _, _ -> error("ARC unavailable") }, UUID.randomUUID(), "match:throw") shouldBe false
    }

    "resolves ARC through the supplied plugin loader after an earlier absence" {
        val player = UUID.randomUUID()
        ArcProductTelemetryBridge.completedWithLoader(player, "match:late") { null } shouldBe false
        var apiLoads = 0
        val loader = object : ClassLoader(ArcProductTelemetryBridge::class.java.classLoader) {
            override fun loadClass(name: String, resolve: Boolean): Class<*> {
                if (name == "ru.arc.metrics.ExternalProductTelemetryBridge") apiLoads++
                return super.loadClass(name, resolve)
            }
        }
        ru.arc.metrics.ExternalProductTelemetryBridge.calls.clear()
        ArcProductTelemetryBridge.completedWithLoader(player, "match:late") { loader } shouldBe true
        apiLoads shouldBe 1
        ru.arc.metrics.ExternalProductTelemetryBridge.calls shouldContainExactly
            listOf(listOf(player, "arcevents", "event_completed", "match:late"))
    }

    "keeps arena generators before My_Worlds without the reverse ARC ordering edge" {
        val text = requireNotNull(javaClass.getResourceAsStream("/plugin.yml")).bufferedReader().use { it.readText() }
        val yaml = org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(java.io.StringReader(text))
        yaml.getStringList("loadbefore").contains("My_Worlds") shouldBe true
        yaml.getStringList("softdepend").contains("ARC") shouldBe false
        yaml.getStringList("depend").contains("ARC") shouldBe false
    }
})
