package ru.ruscrafting.events.paper

import com.google.gson.GsonBuilder
import com.google.gson.JsonParser
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.mockk.every
import io.mockk.mockk
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import org.bukkit.Location
import org.bukkit.plugin.Plugin
import ru.arc.paper.menu.PaperDialogClickContext
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
import ru.ruscrafting.events.domain.apocalypse.source.ApocalypseWorkbenchRecipes
import ru.ruscrafting.events.domain.apocalypse.source.ApocalypseWorkbenchRecipe
import ru.ruscrafting.events.domain.apocalypse.source.ApocalypseWorkbenchStation
import ru.ruscrafting.events.paper.apocalypse.ApocalypseWorkbenchCrafting
import ru.ruscrafting.events.paper.apocalypse.ApocalypseWorkbenchCraftResult
import ru.ruscrafting.events.paper.apocalypse.ApocalypseWorkbenchMenu
import ru.ruscrafting.events.paper.apocalypse.ApocalypseGuide
import ru.ruscrafting.events.paper.apocalypse.ApocalypseGuideSnapshot
import ru.ruscrafting.events.paper.apocalypse.GuideRoute
import ru.ruscrafting.events.paper.apocalypse.GuideTarget
import ru.ruscrafting.events.domain.apocalypse.ApocalypseInfectionStage
import ru.ruscrafting.events.domain.apocalypse.ApocalypsePoint
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
            every { service.modeUnavailable(any()) } returns null
            every { service.isParticipant(any()) } returns false
            var last: PaperDialogScreen? = null
            val menu = ArcEventsDialogMenu(mockk<PaperDialogRuntime>(), service, locale, { config }, { _, _, _ -> },
                ClientProtocolResolver { MIN_DIALOG_PROTOCOL }, { false }) { _, screen, _, _, _ -> last = screen }
            val trader = FishingTraderDialog(mockk<PaperDialogRuntime>(), service, locale, { config }, { false })
            val supply = ZombiesSupplyDialog(mockk<PaperDialogRuntime>(), service, locale)
            val workbenchRuntime = mockk<PaperDialogRuntime>(relaxed = true)
            every { workbenchRuntime.open(player, any(), any(), any(), any()) } answers { last = secondArg() }
            every { workbenchRuntime.open(player, any(), any(), any()) } answers { last = secondArg() }
            every { workbenchRuntime.open(player, any()) } answers { last = secondArg() }
            val schematics = ApocalypseWorkbenchRecipes.load()
            val plugin = mockk<Plugin>(relaxed = true)
            every { plugin.name } returns "ArcEvents"
            val requirements = ApocalypseWorkbenchCrafting(plugin, locale, mockk(), schematics)
            val crafting = mockk<ApocalypseWorkbenchCrafting>()
            every { crafting.plan(any(), any(), player) } answers {
                val recipe = firstArg<ApocalypseWorkbenchRecipe>()
                ApocalypseWorkbenchCraftResult.Missing(requirements.ingredients(recipe, arrayOfNulls(36)).mapIndexed { index, it ->
                    it.copy(available = if (index == 0) it.required else it.required / 2)
                })
            }
            every { player.isOnline } returns true
            every { player.protocolVersion } returns MIN_DIALOG_PROTOCOL
            every { player.location } returns Location(null, 3.5, 72.0, 3.5)
            every { player.inventory.storageContents } returns arrayOfNulls<ItemStack>(36)
            val workbench = ApocalypseWorkbenchMenu(mockk(), locale, schematics, crafting, workbenchRuntime) { true }
            val guide = ApocalypseGuide(plugin, locale, {}, { _, _ -> }, {}, dialogs = workbenchRuntime)
            val routes = ApocalypseGuideSnapshot(7, 10, ApocalypseInfectionStage.HEALTHY, false, 0, false,
                listOf(GuideRoute(ApocalypsePoint(12, 37, -11), true)), GuideRoute(ApocalypsePoint(3, 72, 3)),
                GuideRoute(ApocalypsePoint(-193, 78, 213)), GuideRoute(ApocalypsePoint(478, 48, -376)), GuideTarget.CITY,
                GuideRoute(ApocalypsePoint(8, 35, 12)), GuideRoute(ApocalypsePoint(120, 71, 84)))
            val exports = linkedMapOf<String, Any>()
            val legacy = LegacyComponentSerializer.builder().character('&').hexColors().build()
            val gson = GsonComponentSerializer.gson()
            val plain = PlainTextComponentSerializer.plainText()
            fun primaryArena(mode: EventMode) = locale.render("arena.${mode.primaryArenaId}.name", player)
            fun save(id: String) {
                val screen = requireNotNull(last)
                val components = listOf(screen.title) + screen.body.map { it.text } + screen.buttons.flatMap { listOf(it.label, it.tooltip) }
                components.map(plain::serialize).none { Regex("<(?:queue|waves|minimum|maximum|mode|minutes|rounds|availability|action|roster|arena|selected_arena)>").containsMatchIn(it) } shouldBe true
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
                every { player.hasPermission("arcevents.admin") } returns false
                workbench.open(player, ApocalypseWorkbenchStation.CRAFTING, { true }, { _, _ -> }, {})
                save("$language-workbench-starter")
                workbench.open(player, ApocalypseWorkbenchStation.T2, { true }, { _, _ -> })
                save("$language-workbench-catalog")
                last!!.buttons.first { it.id.value == "recipe_0" }.onClick.handle(mockk<PaperDialogClickContext>())
                last!!.buttons.isEmpty() shouldBe true
                save("$language-workbench-cost")
                workbench.open(player, ApocalypseWorkbenchStation.REFINERY, { true }, { _, _ -> })
                last!!.buttons.first { it.id.value == "recipe_0" }.onClick.handle(mockk<PaperDialogClickContext>())
                save("$language-refinery-cost")
                val click = mockk<PaperDialogClickContext>()
                every { click.player } returns player
                guide.show(player, routes)
                save("$language-apocalypse-route")
                val journal = requireNotNull(last)
                journal.buttons.single { it.id.value == "map" }.onClick.handle(click)
                save("$language-apocalypse-map")
                last!!.buttons.single { it.id.value == "place_city" }.onClick.handle(click)
                save("$language-apocalypse-place")
                journal.buttons.single { it.id.value == "equipment" }.onClick.handle(click)
                save("$language-apocalypse-equipment")
                journal.buttons.single { it.id.value == "hazards" }.onClick.handle(click)
                save("$language-apocalypse-hazards")
                menu.open(player, EventsView.Main); save("$language-root")
                val subjects = last!!.buttons.take(6).map { legacy.serialize(it.label).take(8) }
                subjects.toSet().size shouldBe 6
                menu.open(player, EventsView.Help); save("$language-rules-index")
                last!!.buttons.map { it.id.value }.toSet() shouldBe (EventMode.entries.map { "rules_${it.id}" } + "rules_apocalypse").toSet()
                menu.open(player, EventsView.Apocalypse); save("$language-apocalypse")
                last!!.buttons.map { it.id.value } shouldBe listOf("action_apocalypse_enter_name", "rules_apocalypse")
                last!!.buttons.last().onClick.handle(click)
                last!!.id shouldBe "events.rules.apocalypse.0"
                save("$language-rules-apocalypse")
                menu.openFishing(player, EventEntryStatus(null), {}); save("$language-fishing")
                last!!.buttons.any { it.id.value == "rules_fishing" } shouldBe true
                menu.openFishing(player, EventEntryStatus(QueueState.RESERVED, ReservationStartResult.BUSY), {}); save("$language-fishing-transfer")
                menu.openFishing(player, EventEntryStatus(null, ReservationStartResult.ARENA_UNAVAILABLE), {}); save("$language-fishing-unavailable")
                for (mode in listOf(EventMode.ZOMBIES, EventMode.GUN_GAME, EventMode.DISASTERS)) {
                    menu.openArcade(player, mode, EventEntryStatus(null), false, false, primaryArena(mode), 22, 24, {}, queueSize = 1)
                    save("$language-${mode.id}")
                    last!!.buttons.any { it.id.value == "rules_${mode.id}" } shouldBe true
                    menu.openArcade(player, mode, EventEntryStatus(QueueState.QUEUED), true, false, primaryArena(mode), 22, 24, {}, queueSize = 1)
                    save("$language-${mode.id}-queued")
                }
                menu.openArcadeLoading(player, EventMode.ZOMBIES, {}, {}); save("$language-zombies-loading")
                menu.openArcade(player, EventMode.ZOMBIES, EventEntryStatus(QueueState.QUEUED), true, false, primaryArena(EventMode.ZOMBIES), 22, 24, {}, "queue.start-network-failed", 1)
                save("$language-zombies-error")
                menu.openTtt(player, EventEntryStatus(null), eventMenuPlan(null, true, true, false, false, false, false, false, false), primaryArena(EventMode.TTT))
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
                every { player.hasPermission("arcevents.admin") } returns true
                val ready = EventEntryStatus(null)
                for (mode in EventMode.entries) {
                    when (mode) {
                        EventMode.TTT -> menu.openTtt(player, ready, eventMenuPlan(null, true, true, false, false, false, false, false, false), primaryArena(mode))
                        EventMode.FISHING -> menu.openFishing(player, ready, {})
                        else -> menu.openArcade(player, mode, ready, false, false, primaryArena(mode), 22, 24, {})
                    }
                    val name = when (mode) {
                        EventMode.TTT -> "Riot in Prison"
                        EventMode.GUN_GAME -> "Deadwood"
                        EventMode.DISASTERS -> "Pirate Cove"
                        EventMode.FISHING -> if (language == "ru") "Архипелаг пяти островов" else "Five Isles"
                        EventMode.ZOMBIES -> "Infection: Store"
                    }
                    plain.serialize(last!!.body.first().text) shouldContain name
                    last!!.buttons.count { it.id.value == "action_menu_admin_solo_test_name" } shouldBe 1
                    save("$language-${mode.id}-admin")
                }
                menu.open(player, EventsView.Apocalypse); save("$language-apocalypse-admin")
                last!!.buttons.map { it.id.value } shouldBe listOf("action_apocalypse_test_name", "rules_apocalypse")
            }
            val output = Path.of("build/reports/native-ui/content.json")
            Files.createDirectories(output.parent)
            Files.writeString(output, GsonBuilder().disableHtmlEscaping().setPrettyPrinting().create().toJson(exports))
        } finally { root.toFile().deleteRecursively() }
    }
})
