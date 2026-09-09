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

    "keeps arena generators before My_Worlds and declares the optional ARC service" {
        val text = requireNotNull(javaClass.getResourceAsStream("/plugin.yml")).bufferedReader().use { it.readText() }
        val yaml = org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(java.io.StringReader(text))
        yaml.getStringList("loadbefore").contains("My_Worlds") shouldBe true
        yaml.getStringList("softdepend").contains("ARC") shouldBe true
        yaml.getStringList("depend").contains("ARC") shouldBe false
    }
})
