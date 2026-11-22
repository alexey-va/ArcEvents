package ru.ruscrafting.events.paper

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.entity.Player
import ru.arc.paper.api.ArcSidebarFrame
import ru.arc.paper.api.ArcSidebarHandle
import ru.ruscrafting.events.config.ArcEventsConfig
import ru.ruscrafting.events.config.ArcEventsLocale
import ru.ruscrafting.events.domain.FishingProgress
import java.nio.file.Files
import java.util.Locale
import java.util.UUID

class FishingSidebarTest : StringSpec({
    "the expedition sidebar follows pickup and payment, localizes per player, and releases its slot" {
        val root = Files.createTempDirectory("fishing-sidebar-")
        try {
            val config = root.resolve("config.yml")
            Files.writeString(config, "locale:\n  use-client-locale: true\nui:\n  scoreboard: true\n")
            var settings = ArcEventsConfig.load(root)
            val locale = ArcEventsLocale(root) { settings }
            val player = mockk<Player>()
            val id = UUID.randomUUID()
            every { player.uniqueId } returns id
            every { player.locale() } returns Locale.US
            val frames = mutableListOf<ArcSidebarFrame>()
            val sidebar = mockk<ArcSidebarHandle>(relaxed = true)
            every { sidebar.show(player, capture(frames)) } returns Unit
            val hud = TttHud(mockk(), { settings }, locale, mockk(), sidebar)
            val plain = PlainTextComponentSerializer.plainText()
            fun rows(progress: FishingProgress): List<String> {
                hud.updateFishingSidebar(player, progress)
                return frames.last().rows.map(plain::serialize)
            }
            val start = FishingProgress()
            rows(start) shouldBe listOf("1/5  Shipwreck shore", "Defeated 0/3", "Expedition coins 0💰",
                "Carrying 0", "Cast between the buoys")
            plain.serialize(frames.last().title) shouldBe "Islands of the Deep"
            frames.last().hiddenNameEntries shouldBe emptySet()
            val grounded = start.beginCast().recordCatch().placeCatch().damageCreature(1_000.0)
            rows(grounded).drop(1) shouldBe listOf("Defeated 1/3", "Expedition coins 0💰", "Carrying 0", "Pick up the catch with RMB")
            val carried = grounded.pickUpCatch()
            rows(carried).takeLast(2) shouldBe listOf("Carrying 1", "Take the catch to the trader")
            every { player.locale() } returns Locale.forLanguageTag("ru")
            rows(carried.feedCatch()).drop(1) shouldBe listOf("Побеждено 1/3", "Монеты экспедиции 10💰", "С собой 0", "Забросьте между буями")

            Files.writeString(config, "ui:\n  scoreboard: false\n")
            settings = ArcEventsConfig.load(root)
            hud.updateFishingSidebar(player, start)
            hud.updateFishingSidebar(player, null)
            verify(exactly = 2) { sidebar.hide(id) }
            frames.size shouldBe 4
        } finally { root.toFile().deleteRecursively() }
    }
})
