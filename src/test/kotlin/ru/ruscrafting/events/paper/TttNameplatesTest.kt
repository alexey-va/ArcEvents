package ru.ruscrafting.events.paper

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.attribute.Attribute
import org.bukkit.attribute.AttributeInstance
import org.bukkit.entity.Player
import ru.arc.nameplate.PlayerNameplateRegistry
import ru.ruscrafting.events.config.ArcEventsLocale
import ru.ruscrafting.events.domain.MatchPhase
import ru.ruscrafting.events.domain.ParticipantStatus
import ru.ruscrafting.events.domain.PlayerEventStats
import ru.ruscrafting.events.domain.TttMatch
import ru.ruscrafting.events.domain.TttParticipant
import ru.ruscrafting.events.domain.TttRole
import java.util.UUID

class TttNameplatesTest : StringSpec({
    "nameplate publishes public health, karma and record rows without role data" {
        val playerId = UUID.randomUUID()
        val player = mockk<Player>()
        val maximumHealth = mockk<AttributeInstance>()
        every { player.uniqueId } returns playerId
        every { player.name } returns "StarlightFox"
        every { player.health } returns 17.2
        every { player.getAttribute(Attribute.MAX_HEALTH) } returns maximumHealth
        every { maximumHealth.value } returns 20.0

        val locale = mockk<ArcEventsLocale>()
        var renderedLines = 2
        every { locale.text(any()) } answers { Component.text(firstArg<Any?>().toString()) }
        every { locale.lore("nameplate.lines", null, any()) } answers {
            val values = thirdArg<Map<String, Component>>()
            listOf(
                values.getValue("health").append(Component.text("/")).append(values.getValue("max_health")),
                values.getValue("karma")
                    .append(Component.text("   "))
                    .append(values.getValue("wins"))
                    .append(Component.text("/"))
                    .append(values.getValue("matches")),
            ).take(renderedLines)
        }
        val registry = PlayerNameplateRegistry()
        var refreshes = 0
        val nameplates = TttNameplates(
            registry = registry,
            locale = locale,
            statistics = { PlayerEventStats(matches = 12, wins = 9, karma = 875) },
            onlinePlayer = { player },
            refresh = { refreshes++ },
            healthPriority = 100,
            summaryPriority = 200,
        )
        val match = TttMatch(
            matchId = UUID.randomUUID(),
            revision = 1,
            phase = MatchPhase.ACTIVE,
            participants = mapOf(
                playerId to TttParticipant(playerId, "StarlightFox", "spawn", TttRole.TRAITOR, ParticipantStatus.ALIVE, 2),
            ),
            createdAtMs = 1,
            activeAtMs = 1,
            deadlineMs = 60_001,
        )

        nameplates.update(match)

        val snapshot = requireNotNull(registry.snapshot(playerId))
        snapshot.layers.map { it.key.value } shouldBe listOf("summary", "health")
        PlainTextComponentSerializer.plainText().serialize(snapshot.content) shouldBe
            "875   9/12\n18/20"
        refreshes shouldBe 1

        renderedLines = 1
        nameplates.update(match)
        registry.snapshot(playerId)?.layers?.map { it.key.value } shouldBe listOf("health")
        PlainTextComponentSerializer.plainText().serialize(requireNotNull(registry.snapshot(playerId)).content) shouldBe "18/20"
        refreshes shouldBe 2

        nameplates.remove(playerId)
        registry.snapshot(playerId) shouldBe null
        refreshes shouldBe 3
    }
})
