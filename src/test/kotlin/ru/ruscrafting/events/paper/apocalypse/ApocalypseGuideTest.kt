package ru.ruscrafting.events.paper.apocalypse

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import net.kyori.adventure.inventory.Book
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.TextDecoration
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType
import ru.arc.paper.menu.PaperDialogClickContext
import ru.arc.paper.menu.PaperDialogRuntime
import ru.arc.paper.menu.PaperDialogScreen
import ru.arc.paper.testing.MockBukkitTestRuntime
import ru.ruscrafting.events.config.ArcEventsConfig
import ru.ruscrafting.events.config.ArcEventsLocale
import ru.ruscrafting.events.domain.apocalypse.ApocalypseInfectionStage
import ru.ruscrafting.events.domain.apocalypse.ApocalypsePoint
import ru.ruscrafting.events.paper.MIN_DIALOG_PROTOCOL
import java.util.UUID

class ApocalypseGuideTest : StringSpec({
    val first = GuideRoute(ApocalypsePoint(6, 35, -9))
    val second = GuideRoute(ApocalypsePoint(18, 37, -9))
    val snapshot = ApocalypseGuideSnapshot(0, 10, ApocalypseInfectionStage.HEALTHY, false, 0, true,
        listOf(first, second), GuideRoute(ApocalypsePoint(3, 72, 3)), null, GuideRoute(ApocalypsePoint(600, 40, -90)))

    "only the marked book opens the field guide and its complete tooltip is non-italic" {
        MockBukkitTestRuntime.open().use { paper ->
            val plugin = paper.createSimplePlugin("ApocalypseGuideItem")
            val root = plugin.dataFolder.toPath()
            val settings = ArcEventsConfig.load(root)
            val locale = ArcEventsLocale(root) { settings }
            ApocalypseGuide(plugin, locale, {}, { _, _ -> }, {}).use { guide ->
                val book = guide.item(paper.addPlayer("Survivor"))
                val name = requireNotNull(book.itemMeta.displayName())
                val lore = requireNotNull(book.itemMeta.lore())
                val plain = PlainTextComponentSerializer.plainText()
                plain.serialize(name) shouldBe "Полевой справочник"
                lore.map(plain::serialize) shouldBe listOf("", "Ориентиры, рецепты и советы по выживанию.", "", "[▶] ПКМ — открыть справочник")
                (listOf(name) + lore).all { it.decoration(TextDecoration.ITALIC) == TextDecoration.State.FALSE } shouldBe true
                book.type shouldBe Material.BOOK
                val key = NamespacedKey(plugin, "apocalypse_guide")
                book.itemMeta.persistentDataContainer.get(key, PersistentDataType.BYTE) shouldBe 1.toByte()
                guide.isItem(book.clone()) shouldBe true
                val ordinary = ItemStack.of(Material.BOOK)
                ordinary.editMeta { it.displayName(name) }
                guide.isItem(ordinary) shouldBe false
                guide.isItem(null) shouldBe false
            }
        }
    }

    "the journal opens place notes before tracking and keeps flight in the equipment chapter" {
        MockBukkitTestRuntime.open().use { paper ->
            val plugin = paper.createSimplePlugin("ApocalypseJournal")
            val root = plugin.dataFolder.toPath()
            val settings = ArcEventsConfig.load(root)
            val locale = ArcEventsLocale(root) { settings }
            val player = mockk<Player>(relaxed = true)
            every { player.isOnline } returns true
            every { player.uniqueId } returns UUID.randomUUID()
            every { player.protocolVersion } returns MIN_DIALOG_PROTOCOL
            every { player.location } returns Location(null, 3.0, 35.0, 39.0)
            var admin = false
            every { player.hasPermission("arcevents.admin") } answers { admin }
            every { player.hasPermission("arcevents.play") } returns true
            val click = mockk<PaperDialogClickContext>()
            every { click.player } returns player
            val dialogs = mockk<PaperDialogRuntime>(relaxed = true)
            lateinit var current: PaperDialogScreen
            every { dialogs.open(player, any(), any(), any(), any()) } answers { current = secondArg() }
            every { dialogs.open(player, any()) } answers { current = secondArg() }
            val selected = mutableListOf<GuideTarget>()
            var left = false
            var administrativeVisits = 0
            var campaignOwner = false
            var membersVisits = 0
            var objectiveVisits = 0
            val guide = ApocalypseGuide(plugin, locale, {}, { _, target -> selected += target }, { left = true },
                canFly = { true }, dialogs = dialogs, onAdmin = { administrativeVisits++ }, ownsDialogs = false,
                canManageCampaign = { campaignOwner }, onCampaign = { membersVisits++ }, onObjectives = { objectiveVisits++ })
            guide.beginFlow(player)
            guide.show(player, snapshot.copy(tracked = GuideTarget.SUPPLIES))
            val journal = current
            journal.buttons.map { it.id.value } shouldBe listOf("map", "equipment", "hazards", "objectives", "leave")
            journal.exitButton?.id?.value shouldBe "close"
            val map = journal.buttons.first()
            map.closeDialogBeforeAction shouldBe false
            map.onClick.handle(click)
            current.id shouldBe "events.apocalypse.guide.map"
            val supply = current.buttons.single { it.id.value == "place_supplies" }
            val plain = PlainTextComponentSerializer.plainText()
            plain.serialize(supply.label) shouldBe "✔ Кладовая бункера ›"
            supply.closeDialogBeforeAction shouldBe false
            supply.onClick.handle(click)
            current.id shouldBe "events.apocalypse.guide.map.supplies"
            current.exitButton?.id?.value shouldBe "back"
            selected.isEmpty() shouldBe true
            val mark = current.buttons.single()
            mark.closeDialogBeforeAction shouldBe true
            mark.onClick.handle(click)
            selected shouldBe listOf(GuideTarget.SUPPLIES)
            journal.buttons.single { it.id.value == "equipment" }.onClick.handle(click)
            current.buttons.single().id.value shouldBe "admin_fly"
            journal.buttons.single { it.id.value == "hazards" }.onClick.handle(click)
            current.buttons.isEmpty() shouldBe true
            journal.buttons.single { it.id.value == "objectives" }.onClick.handle(click)
            objectiveVisits shouldBe 1
            guide.showObjectives(player, listOf(Component.text("Pier generator")), emptyList())
            current.id shouldBe "events.apocalypse.guide.objectives"
            plain.serialize(current.body.single().text).contains("Pier generator") shouldBe true
            journal.buttons.last().onClick.handle(click)
            left shouldBe true
            campaignOwner = true
            guide.show(player, snapshot)
            current.buttons.single { it.id.value == "campaign" }.onClick.handle(click)
            membersVisits shouldBe 1
            campaignOwner = false
            current.buttons.single { it.id.value == "campaign" }.onClick.handle(click)
            membersVisits shouldBe 1
            verify(exactly = 1) { dialogs.close(player) }

            lateinit var book: Book
            every { player.openBook(any<Book>()) } answers { book = firstArg() }
            every { player.protocolVersion } returns MIN_DIALOG_PROTOCOL - 1
            guide.show(player, snapshot)
            plain.serialize(book.title()) shouldBe "Полевой дневник"
            book.pages().first().children().last().clickEvent()?.value() shouldBe "/arcevents leave"
            book.pages()[1].children().last().clickEvent()?.value() shouldBe "/arcevents apocalypse objectives"
            book.pages()[2].children().last().clickEvent()?.value() shouldBe "/arcevents apocalypse track supplies"
            book.pages().last().children().last().clickEvent()?.value() shouldBe "/arcevents leave"
            campaignOwner = true
            guide.show(player, snapshot)
            book.pages()[1].children().last().clickEvent()?.value() shouldBe "/arcevents apocalypse campaign"
            guide.showObjectives(player, emptyList(), listOf(Component.text("Pier generator")))
            plain.serialize(book.pages().single()) shouldBe "Pier generator"
            campaignOwner = false
            guide.show(player, snapshot)
            fun bookAdminLinks() = book.pages().first().children().filter { it.clickEvent()?.value() == "/events admin apocalypse" }
            bookAdminLinks().isEmpty() shouldBe true
            admin = true
            guide.show(player, snapshot)
            plain.serialize(bookAdminLinks().single()) shouldBe "Админ-панель"

            every { player.protocolVersion } returns MIN_DIALOG_PROTOCOL
            guide.show(player, snapshot)
            val panel = current.buttons.single { it.id.value == "admin" }
            plain.serialize(panel.label) shouldBe "Админ-панель ›"
            panel.closeDialogBeforeAction shouldBe false
            panel.onClick.handle(click)
            administrativeVisits shouldBe 1
            guide.show(player, snapshot)
            val stalePanel = current.buttons.single { it.id.value == "admin" }
            admin = false
            stalePanel.onClick.handle(click)
            administrativeVisits shouldBe 1
            current.id shouldBe "events.apocalypse.guide"
            current.buttons.map { it.id.value } shouldBe journal.buttons.map { it.id.value }
            verify(exactly = 2) { dialogs.beginFlow(player) }
            guide.close()
            verify(exactly = 0) { dialogs.close() }
        }
    }

    "guide players to an unstocked barrel before suggesting the exit, with infection taking priority" {
        val from = ApocalypsePoint(3, 35, 39)
        snapshot.nextStep() shouldBe "supplies"
        snapshot.route(GuideTarget.SUPPLIES, from) shouldBe first
        val afterFirst = snapshot.copy(supplies = listOf(first.copy(claimed = true), second))
        afterFirst.route(GuideTarget.SUPPLIES, from) shouldBe second
        afterFirst.copy(supplies = afterFirst.supplies.map { it.copy(claimed = true) }).nextStep() shouldBe "exit"
        afterFirst.copy(infection = ApocalypseInfectionStage.FEVER).nextStep() shouldBe "cure"
    }

    "recommend a weapon route only when a real target exists and distinguish loose rounds from a firearm" {
        val outside = snapshot.copy(inStarter = false, looseAmmo = 24)
        outside.nextStep() shouldBe "weapons"
        outside.copy(weapons = null, city = first).nextStep() shouldBe "city"
        outside.copy(weapons = null).nextStep() shouldBe "shelter"
        outside.copy(hasGun = true, looseAmmo = 0).nextStep() shouldBe "ammo"
        outside.copy(hasGun = true).nextStep() shouldBe "shelter"
    }

    "choose a real surface landmark and retain the optional workshop route" {
        snapshot.copy(city = first, structure = second).surfaceTarget() shouldBe GuideTarget.CITY
        snapshot.copy(structure = second).surfaceTarget() shouldBe GuideTarget.STRUCTURE
        snapshot.surfaceTarget() shouldBe GuideTarget.WEAPONS
        snapshot.copy(weapons = null).surfaceTarget() shouldBe null
        snapshot.copy(workbench = second).route(GuideTarget.WORKBENCH, first.point) shouldBe second
    }
})
