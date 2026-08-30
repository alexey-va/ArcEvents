package ru.ruscrafting.events.paper

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.verify
import net.kyori.adventure.text.Component
import org.bukkit.attribute.Attribute
import org.bukkit.attribute.AttributeInstance
import org.bukkit.entity.Player
import ru.arc.nameplate.PlayerNameplateRegistry
import ru.arc.paper.nameplate.PaperNameplateOptions
import ru.arc.paper.nameplate.PaperPlayerNameplates
import ru.ruscrafting.events.config.ArcEventsLocale
import ru.ruscrafting.events.config.NameplateBackgroundSettings
import ru.ruscrafting.events.config.NameplateSettings
import ru.ruscrafting.events.domain.MatchPhase
import ru.ruscrafting.events.domain.ParticipantStatus
import ru.ruscrafting.events.domain.PlayerEventStats
import ru.ruscrafting.events.domain.TttMatch
import ru.ruscrafting.events.domain.TttParticipant
import ru.ruscrafting.events.domain.TttRole
import java.util.UUID

class TttNameplateRuntimeTest : StringSpec({
    "reconfigure swaps the renderer and rebuilds current match rows immediately" {
        val playerId = UUID.randomUUID()
        val player = mockk<Player>()
        val maximumHealth = mockk<AttributeInstance>()
        every { player.uniqueId } returns playerId
        every { player.health } returns 20.0
        every { player.getAttribute(Attribute.MAX_HEALTH) } returns maximumHealth
        every { maximumHealth.value } returns 20.0

        val locale = mockk<ArcEventsLocale>()
        every { locale.text(any()) } answers { Component.text(firstArg<Any?>().toString()) }
        every { locale.lore("nameplate.lines", null, any()) } returns listOf(
            Component.text("health"),
            Component.text("summary"),
        )

        val firstRegistry = PlayerNameplateRegistry()
        val secondRegistry = PlayerNameplateRegistry()
        val firstRenderer = renderer(firstRegistry)
        val secondRenderer = renderer(secondRegistry)
        val renderers = ArrayDeque(listOf(firstRenderer, secondRenderer))
        val options = mutableListOf<PaperNameplateOptions>()
        val match = match(playerId)
        val runtime = TttNameplateRuntime(
            locale = locale,
            statistics = { PlayerEventStats(matches = 3, wins = 1, karma = 900) },
            onlinePlayer = { player },
            currentMatch = { match },
            rendererFactory = { candidate ->
                options += candidate
                renderers.removeFirst()
            },
        )

        runtime.reconfigure(settings(scale = 0.8))
        firstRegistry.snapshot(playerId)?.layers?.map { it.key.value } shouldBe listOf("health", "summary")

        runtime.reconfigure(settings(scale = 0.65))
        options.map(PaperNameplateOptions::scale) shouldBe listOf(0.8F, 0.65F)
        firstRegistry.snapshot(playerId) shouldBe null
        secondRegistry.snapshot(playerId)?.layers?.map { it.key.value } shouldBe listOf("health", "summary")
        verify(exactly = 1) { firstRenderer.close() }

        runtime.reconfigure(settings(scale = 0.65).copy(enabled = false))
        secondRegistry.snapshot(playerId) shouldBe null
        verify(exactly = 1) { secondRenderer.close() }
    }
}) {
    companion object {
        private fun renderer(registry: PlayerNameplateRegistry): PaperPlayerNameplates =
            mockk<PaperPlayerNameplates>().also { renderer ->
                every { renderer.registry } returns registry
                every { renderer.refreshNow() } just Runs
                every { renderer.close() } just Runs
            }

        private fun settings(scale: Double): NameplateSettings = NameplateSettings(
            enabled = true,
            reconcilePeriodTicks = 4,
            maxDistance = 32.0,
            lineWidth = 180,
            viewRange = 0.5,
            scale = scale,
            verticalOffset = 0.55,
            shadowed = true,
            background = NameplateBackgroundSettings(0, 0, 0, 0),
            hideInvisibleTargets = true,
            hideSpectatorTargets = true,
            requireLineOfSight = true,
            healthPriority = 200,
            summaryPriority = 100,
        )

        private fun match(playerId: UUID): TttMatch = TttMatch(
            matchId = UUID.randomUUID(),
            revision = 1,
            phase = MatchPhase.ACTIVE,
            participants = mapOf(
                playerId to TttParticipant(playerId, "Player", "spawn", TttRole.INNOCENT, ParticipantStatus.ALIVE, 0),
            ),
            createdAtMs = 1,
            activeAtMs = 1,
            deadlineMs = 60_001,
        )
    }
}
