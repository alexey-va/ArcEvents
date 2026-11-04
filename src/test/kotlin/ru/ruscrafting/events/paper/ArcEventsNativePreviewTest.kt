package ru.ruscrafting.events.paper

import com.google.gson.GsonBuilder
import com.google.gson.JsonParser
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.entity.Player
import ru.arc.paper.menu.PaperDialogRuntime
import ru.arc.paper.menu.PaperDialogScreen
import ru.ruscrafting.events.config.ArcEventsConfig
import ru.ruscrafting.events.config.ArcEventsLocale
import ru.ruscrafting.events.config.NodeMode
import ru.ruscrafting.events.domain.EventMode
import ru.ruscrafting.events.domain.FishingProgress
import ru.ruscrafting.events.domain.ZombiesOffer
import ru.ruscrafting.events.domain.ZombiesSurvivor
import ru.ruscrafting.events.domain.TttTeam
import ru.ruscrafting.events.domain.TttRole
import ru.ruscrafting.events.domain.MatchEndReason
import ru.ruscrafting.events.network.QueueState
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale
import java.util.UUID

class ArcEventsNativePreviewTest : StringSpec({
    "native screens keep short summaries and export actual production components" {
        val root = Files.createTempDirectory("arcevents-native-preview-")
        try {
            Files.writeString(root.resolve("config.yml"), "locale:\n  default: ru\n  use-client-locale: true\nui:\n  dialogs-enabled: true\n")
            val config = ArcEventsConfig.load(root)
            val locale = ArcEventsLocale(root) { config }
            val player = mockk<Player>(relaxed = true)
            every { player.uniqueId } returns UUID.randomUUID()
            every { player.hasPermission(any<String>()) } returns false
            val service = mockk<ArcEventsService>(relaxed = true)
            every { service.snapshot() } returns ServiceSnapshot("host", NodeMode.HOST, "host", true, true, true, null, 1, null, null, 0, 0, 0, 0, 0, 0)
            every { service.currentMode() } returns null
            every { service.modeAvailable(any()) } returns true
            every { service.isParticipant(any()) } returns false
            var last: PaperDialogScreen? = null
            val menu = ArcEventsDialogMenu(mockk<PaperDialogRuntime>(), service, locale, { config }, { _, _, _ -> },
                ClientProtocolResolver { MIN_DIALOG_PROTOCOL }, { false }) { _, screen, _, _, _ -> last = screen }
            val trader = FishingTraderDialog(mockk<PaperDialogRuntime>(), service, locale, { config }, { false })
            val supply = ZombiesSupplyDialog(mockk<PaperDialogRuntime>(), service, locale)
            val exports = linkedMapOf<String, Any>()
            val legacy = LegacyComponentSerializer.builder().character('&').hexColors().build()
            val gson = GsonComponentSerializer.gson()
            val plain = PlainTextComponentSerializer.plainText()
            fun save(id: String) {
                val screen = requireNotNull(last)
                val components = listOf(screen.title) + screen.body.map { it.text } + screen.buttons.flatMap { listOf(it.label, it.tooltip) }
                components.map(plain::serialize).none { Regex("<(?:queue|waves|minimum|maximum|mode|minutes|rounds|availability)>").containsMatchIn(it) } shouldBe true
                exports[id] = mapOf(
                    "title" to legacy.serialize(screen.title), "columns" to screen.columns,
                    "body" to screen.body.map { mapOf("text" to legacy.serialize(it.text), "width" to it.width) },
                    "buttons" to screen.buttons.map { mapOf("id" to it.id.value, "text" to legacy.serialize(it.label),
                        "tooltip" to legacy.serialize(it.tooltip), "width" to it.width) },
                    "exit" to screen.exitButton?.let { mapOf("text" to legacy.serialize(it.label), "tooltip" to legacy.serialize(it.tooltip), "width" to it.width) },
                    "components" to components.map { JsonParser.parseString(gson.serialize(it)) },
                )
            }
            for (language in listOf("ru", "en")) {
                every { player.locale() } returns Locale.forLanguageTag(language)
                menu.open(player, EventsView.Main); save("$language-root")
                menu.open(player, EventsView.Help); save("$language-rules-index")
                last!!.buttons.map { it.id.value }.toSet() shouldBe EventMode.entries.map { "rules_${it.id}" }.toSet()
                menu.open(player, EventsView.Fishing); save("$language-fishing")
                last!!.buttons.any { it.id.value == "rules_fishing" } shouldBe true
                for (mode in listOf(EventMode.ZOMBIES, EventMode.GUN_GAME, EventMode.DISASTERS)) {
                    menu.openArcade(player, mode, null, false, false, true, false, 22, 24, {}, queueSize = 1)
                    save("$language-${mode.id}")
                    last!!.buttons.any { it.id.value == "rules_${mode.id}" } shouldBe true
                    menu.openArcade(player, mode, QueueState.QUEUED, true, false, true, false, 22, 24, {}, queueSize = 1)
                    save("$language-${mode.id}-queued")
                }
                menu.openArcadeLoading(player, EventMode.ZOMBIES, {}, {}); save("$language-zombies-loading")
                menu.openArcade(player, EventMode.ZOMBIES, QueueState.QUEUED, true, false, true, false, 22, 24, {}, "queue.start-network-failed", 1)
                save("$language-zombies-error")
                menu.openTtt(player, null, eventMenuPlan(null, true, true, false, false, false, false, false, false), Component.text("Inferno"))
                save("$language-ttt")
                last!!.buttons.any { it.id.value == "rules_ttt" } shouldBe true
                last = trader.model(player, FishingProgress().copy(coins = 300), false) {}; save("$language-trader")
                last = trader.model(player, FishingProgress().copy(coins = 300), true) {}; save("$language-armory")
                last = trader.model(player, FishingProgress().copy(coins = 10), false, "changed") {}; save("$language-trader-error")
                for (offer in listOf(ZombiesOffer.AMMO, ZombiesOffer.POWER, ZombiesOffer.VITALITY)) {
                    last = supply.model(player, offer, ZombiesSurvivor(points = 3250), offer.basePrice, true) {}
                    save("$language-supply-${offer.name.lowercase()}")
                }
                last = supply.model(player, ZombiesOffer.VITALITY, ZombiesSurvivor(points = 500), 1500, false) {}
                last!!.buttons.isEmpty() shouldBe true
                save("$language-supply-poor")
                last = supply.model(player, ZombiesOffer.POWER, ZombiesSurvivor(points = 3250, power = 3), 3000, false) {}
                last!!.buttons.isEmpty() shouldBe true
                save("$language-supply-maxed")
                val report = RoundReportView(UUID.randomUUID(), TttTeam.INNOCENTS, MatchEndReason.ELIMINATION, 307,
                    (1..9).map { RoundParticipantView(UUID.randomUUID(), "LongPlayerName16", TttRole.entries[it % 3],
                        it, 1, 1234.5, 32.0) }, emptyList())
                menu.openReport(player, report, 0, 40, {}, {}); save("$language-results")
                menu.openReport(player, report, 1, 40, {}, {}); save("$language-results-last")
                for (mode in EventMode.entries) {
                    menu.openRules(player, mode); save("$language-rules-${mode.id}")
                    if (mode in setOf(EventMode.TTT, EventMode.ZOMBIES)) {
                        menu.openRules(player, mode, 1); save("$language-rules-${mode.id}-controls")
                    }
                }
            }
            val output = Path.of("build/reports/native-ui/content.json")
            Files.createDirectories(output.parent)
            Files.writeString(output, GsonBuilder().disableHtmlEscaping().setPrettyPrinting().create().toJson(exports))
        } finally { root.toFile().deleteRecursively() }
    }
})
