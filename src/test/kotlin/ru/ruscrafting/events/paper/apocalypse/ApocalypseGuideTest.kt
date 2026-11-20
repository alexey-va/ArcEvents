package ru.ruscrafting.events.paper.apocalypse

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import net.kyori.adventure.text.format.TextDecoration
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType
import ru.arc.paper.testing.MockBukkitTestRuntime
import ru.ruscrafting.events.config.ArcEventsConfig
import ru.ruscrafting.events.config.ArcEventsLocale
import ru.ruscrafting.events.domain.apocalypse.ApocalypseInfectionStage
import ru.ruscrafting.events.domain.apocalypse.ApocalypsePoint

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
