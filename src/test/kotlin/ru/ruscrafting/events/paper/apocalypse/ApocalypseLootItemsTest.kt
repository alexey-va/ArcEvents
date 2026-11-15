package ru.ruscrafting.events.paper.apocalypse

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType
import ru.arc.config.ConfigManager
import ru.arc.paper.testing.MockBukkitTestRuntime
import ru.ruscrafting.events.config.ArcEventsConfig
import ru.ruscrafting.events.config.ArcEventsLocale
import ru.ruscrafting.events.domain.apocalypse.source.ApocalypseSourceLootItem
import ru.ruscrafting.events.domain.apocalypse.source.ApocalypseSourceItemIdentity
import ru.ruscrafting.events.paper.TttFirearms
import java.nio.file.Files
import java.util.Random

class ApocalypseLootItemsTest : StringSpec({
    "native source weapons stay unloaded, ammunition quantities survive splitting, and plain materials remain usable" {
        MockBukkitTestRuntime.open().use { paper ->
            val plugin = paper.createSimplePlugin("ApocalypseItemsTest")
            val data = Files.createTempDirectory("apocalypse-items-")
            ConfigManager.clear()
            for (resource in listOf("config.yml", "lang/ru.yml", "lang/en.yml")) {
                val target = data.resolve(resource); Files.createDirectories(target.parent)
                javaClass.getResourceAsStream("/$resource")!!.use { Files.copy(it, target) }
            }
            val settings = ArcEventsConfig.load(data)
            val locale = ArcEventsLocale(data) { settings }
            val firearms = TttFirearms(plugin, locale) { settings }
            val factory = ApocalypseLootItems(plugin, locale, firearms)
            val player = paper.addPlayer("Survivor")
            val guns = factory.create(ApocalypseSourceLootItem("tacz:modern_kinetic_gun", 2,
                nbt = "{GunCurrentAmmoCount:0}", gunId = "bf1:sw_model3"), player, Random(1))
            guns.size shouldBe 2
            guns.forEach {
                it.amount shouldBe 1
                it.itemMeta.persistentDataContainer.get(NamespacedKey(plugin, "world_loaded_rounds"), PersistentDataType.INTEGER) shouldBe 0
                factory.identity(it) shouldBe ApocalypseSourceItemIdentity("tacz:modern_kinetic_gun", gunId = "bf1:sw_model3")
            }
            val loaded = factory.create(ApocalypseSourceLootItem("tacz:modern_kinetic_gun", 1,
                nbt = "{\"GunCurrentAmmoCount\":3}", gunId = "bf1:sw_model3"), player, Random(1)).single()
            loaded.itemMeta.persistentDataContainer.get(NamespacedKey(plugin, "world_loaded_rounds"), PersistentDataType.INTEGER) shouldBe 3
            val book = factory.create(ApocalypseSourceLootItem("minecraft:enchanted_book", 1,
                nbt = "{\"StoredEnchantments\":[{\"id\":\"minecraft:unbreaking\",\"lvl\":2s}]}"), player, Random(1)).single()
            (book.itemMeta as org.bukkit.inventory.meta.EnchantmentStorageMeta).getStoredEnchantLevel(org.bukkit.enchantments.Enchantment.UNBREAKING) shouldBe 2
            val rollBook = factory.create(ApocalypseSourceLootItem("minecraft:enchanted_book", 1,
                nbt = "{\"StoredEnchantments\":[{\"id\":\"combatroll:multi_roll\",\"lvl\":3s}]}"), player, Random(1)).single()
            (rollBook.itemMeta as org.bukkit.inventory.meta.EnchantmentStorageMeta).getStoredEnchantLevel(org.bukkit.enchantments.Enchantment.UNBREAKING) shouldBe 3
            val worn = factory.create(ApocalypseSourceLootItem("minecraft:iron_axe", 1,
                nbt = "{\"Damage\":42}"), player, Random(1)).single()
            (worn.itemMeta as org.bukkit.inventory.meta.Damageable).damage shouldBe 42
            val rounds = factory.create(ApocalypseSourceLootItem("tacz:ammo", 77, ammoId = "tacz:9mm"), player, Random(1))
            rounds.map(ItemStack::getAmount) shouldBe listOf(64, 13)
            rounds.all(firearms::isWorldAmmunition) shouldBe true
            rounds.forEach { factory.identity(it) shouldBe ApocalypseSourceItemIdentity("tacz:ammo", ammoId = "tacz:9mm") }
            val metal = factory.create(ApocalypseSourceLootItem("minecraft:iron_nugget", 4), player, Random(1)).single()
            metal shouldBe ItemStack.of(Material.IRON_NUGGET, 4)
            factory.identity(metal) shouldBe ApocalypseSourceItemIdentity("minecraft:iron_nugget")
            val steel = factory.create(ApocalypseSourceLootItem("mcore:steel_ingot", 2), player, Random(1)).single()
            steel.editMeta { it.displayName(net.kyori.adventure.text.Component.text("Renamed source steel")) }
            factory.identity(steel) shouldBe ApocalypseSourceItemIdentity("mcore:steel_ingot")
            factory.identity(ItemStack.of(Material.IRON_INGOT)) shouldBe ApocalypseSourceItemIdentity("minecraft:iron_ingot")
            factory.identity(ItemStack.of(Material.IRON_INGOT).also { item -> item.editMeta {
                it.displayName(net.kyori.adventure.text.Component.text("mcore:steel_ingot"))
            } }) shouldBe null
            factory.identity(ItemStack.of(Material.IRON_INGOT).also { item -> item.editMeta {
                it.persistentDataContainer.set(NamespacedKey(plugin, "apocalypse_source_item"), PersistentDataType.STRING, "minecraft:iron_ingot")
            } }) shouldBe null
            val crowbar = factory.create(ApocalypseSourceLootItem("marbledsmelees:crowbar", 1), player, Random(1)).single()
            ApocalypseLootItems.isCrowbar(crowbar, plugin) shouldBe true
            ApocalypseLootItems.isCrowbar(ItemStack.of(Material.IRON_SWORD), plugin) shouldBe false
        }
        ConfigManager.clear()
    }
})
