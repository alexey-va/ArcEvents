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
import ru.ruscrafting.events.domain.apocalypse.source.*
import ru.ruscrafting.events.paper.TttFirearms
import java.nio.file.Files

class ApocalypseWorkbenchCraftingTest : StringSpec({
    "component predicates exclude ordinary carriers and renamed spoof items while source renames remain valid" {
        WorkbenchCraftingFixture().use { fixture ->
            val crafting = fixture.crafting
            crafting.sourceItem(ApocalypseSourceItemIdentity("the_deeper_void:void_refinery_casing")).type shouldBe Material.IRON_INGOT
            val steel = crafting.sourceItem(ApocalypseSourceItemIdentity("mcore:steel_ingot"), 4)
            steel.editMeta { it.displayName(net.kyori.adventure.text.Component.text("Steel renamed")) }
            val sheet = crafting.sourceItem(ApocalypseSourceItemIdentity("mcore:steel_sheet"), 7)
            val plain = ItemStack.of(Material.IRON_INGOT, 64)
            val spoof = plain.clone().also { it.editMeta { meta -> meta.displayName(net.kyori.adventure.text.Component.text("mcore:steel_ingot")) } }
            val storage = arrayOfNulls<ItemStack>(36).also { it[0] = steel; it[1] = sheet; it[2] = plain; it[3] = spoof }
            val recipe = recipe(ApocalypseSourceLootItem("minecraft:diamond", 1), "mcore:steel_ingot" to 3)
            crafting.ingredients(recipe, storage).single().available shouldBe 4
            val result = crafting.plan(recipe, storage, fixture.player) as ApocalypseWorkbenchCraftResult.Ready
            result.afterStorage[0]!!.amount shouldBe 1
            result.afterStorage[1] shouldBe sheet
            result.afterStorage[2] shouldBe plain
            result.afterStorage[3] shouldBe spoof
            storage[0]!!.amount shouldBe 4
            crafting.plan(recipe.copy(ingredients = listOf(ApocalypseWorkbenchIngredient(
                listOf(ApocalypseSourceItemIdentity("mcore:steel_ingot")), 5))), storage, fixture.player)::class shouldBe ApocalypseWorkbenchCraftResult.Missing::class
        }
    }

    "upgrades distinguish source guns sharing one native profile and create an unloaded typed result" {
        WorkbenchCraftingFixture().use { fixture ->
            val crafting = fixture.crafting
            val requirement = ApocalypseWorkbenchIngredient(listOf(ApocalypseSourceItemIdentity("tacz:modern_kinetic_gun", gunId = "tacz:m249")), 1)
            val recipe = ApocalypseWorkbenchRecipe("test:upgrade", ApocalypseWorkbenchStation.T3, ApocalypseWorkbenchRecipeKind.GUN,
                ApocalypseSourceLootItem("tacz:modern_kinetic_gun", 1, gunId = "sfms:har50"), listOf(requirement))
            val storage = arrayOfNulls<ItemStack>(36)
            storage[0] = crafting.sourceItem(ApocalypseSourceItemIdentity("tacz:modern_kinetic_gun", gunId = "tacz:rpk"))
            crafting.plan(recipe, storage, fixture.player)::class shouldBe ApocalypseWorkbenchCraftResult.Missing::class
            storage[1] = crafting.sourceItem(ApocalypseSourceItemIdentity("tacz:modern_kinetic_gun", gunId = "tacz:m249"))
            val result = crafting.plan(recipe, storage, fixture.player) as ApocalypseWorkbenchCraftResult.Ready
            result.afterStorage[0] shouldBe storage[0]
            val output = result.afterStorage[1]!!
            crafting.identity(output) shouldBe ApocalypseSourceItemIdentity("tacz:modern_kinetic_gun", gunId = "sfms:har50")
            output.itemMeta.persistentDataContainer.get(NamespacedKey(fixture.plugin, "world_loaded_rounds"), PersistentDataType.INTEGER) shouldBe 0
            storage[1]!!.amount shouldBe 1
        }
    }

    "capacity is checked after deductions and overflow leaves every input unchanged" {
        WorkbenchCraftingFixture().use { fixture ->
            val crafting = fixture.crafting
            val storage = Array<ItemStack?>(36) { ItemStack.of(Material.COBBLESTONE, 64) }
            storage[0] = crafting.sourceItem(ApocalypseSourceItemIdentity("create:golden_sheet"))
            val one = recipe(ApocalypseSourceLootItem("tacz:modern_kinetic_gun", 1, gunId = "tacz:m1911"), "create:golden_sheet" to 1)
            val ready = crafting.plan(one, storage, fixture.player) as ApocalypseWorkbenchCraftResult.Ready
            crafting.identity(ready.afterStorage[0])!!.gunId shouldBe "tacz:m1911"
            val before = storage.map { it?.clone() }
            crafting.plan(one.copy(result = one.result.copy(count = 2)), storage, fixture.player) shouldBe ApocalypseWorkbenchCraftResult.Full
            storage.asList() shouldBe before
            ready.afterStorage[0]!!.amount = 9
            ready.afterStorage[0]!!.amount shouldBe 1
            ready.outputs.single().amount = 9
            ready.outputs.single().amount shouldBe 1
        }
    }

    "all bundled source outputs and ingredients have explicit native identities and ammo keeps its caliber across split stacks" {
        WorkbenchCraftingFixture().use { fixture ->
            for (recipe in fixture.crafting.catalog.all) {
                val outputs = fixture.crafting.preview(recipe, fixture.player)
                outputs.groupBy { fixture.crafting.identity(it)!!.itemId }.mapValues { (_, stacks) -> stacks.sumOf(ItemStack::getAmount) } shouldBe
                    (listOf(recipe.result) + recipe.byproducts).groupBy { it.itemId }.mapValues { (_, items) -> items.sumOf { it.count } }
                recipe.ingredients.flatMap { it.alternatives }.forEach { identity ->
                    fixture.crafting.identity(fixture.crafting.sourceItem(identity)) shouldBe identity
                }
            }
            val recipe = recipe(ApocalypseSourceLootItem("tacz:ammo", 130, ammoId = "tacz:9mm"), "minecraft:iron_nugget" to 1)
            val storage = arrayOfNulls<ItemStack>(36).also { it[0] = ItemStack.of(Material.IRON_NUGGET) }
            val result = fixture.crafting.plan(recipe, storage, fixture.player) as ApocalypseWorkbenchCraftResult.Ready
            result.outputs.map(ItemStack::getAmount) shouldBe listOf(64, 64, 2)
            result.outputs.forEach { fixture.crafting.identity(it) shouldBe ApocalypseSourceItemIdentity("tacz:ammo", ammoId = "tacz:9mm") }
        }
    }

    "vaccine plan spends the exact native ingredients and returns usable medicine" {
        WorkbenchCraftingFixture().use { fixture ->
            val crafting = fixture.crafting
            val recipe = requireNotNull(crafting.catalog.find("arcevents:vaccine"))
            val storage = arrayOfNulls<ItemStack>(36).also {
                it[0] = ItemStack.of(Material.GLASS_BOTTLE)
                it[1] = ItemStack.of(Material.GOLD_INGOT)
                it[2] = ItemStack.of(Material.REDSTONE)
            }
            val before = storage.map { it?.clone() }
            val missing = crafting.plan(recipe, storage, fixture.player) as ApocalypseWorkbenchCraftResult.Missing
            missing.ingredients.filterNot { it.satisfied }.single().ingredient.alternatives.single() shouldBe
                ApocalypseSourceItemIdentity("minecraft:redstone")
            storage.asList() shouldBe before

            storage[2] = ItemStack.of(Material.REDSTONE, 3)
            val ready = crafting.plan(recipe, storage, fixture.player) as ApocalypseWorkbenchCraftResult.Ready
            val vaccine = ready.outputs.single()
            vaccine.type shouldBe Material.HONEY_BOTTLE
            vaccine.amount shouldBe 1
            vaccine.itemMeta.persistentDataContainer.get(NamespacedKey(fixture.plugin, "apocalypse_medicine"), PersistentDataType.BYTE) shouldBe 1.toByte()
            crafting.identity(vaccine) shouldBe ApocalypseSourceItemIdentity("cure:vaccine")
            ready.afterStorage.filterNotNull() shouldBe listOf(vaccine, ItemStack.of(Material.REDSTONE))
            storage[2]!!.amount shouldBe 3
        }
    }

    "one native stack cannot satisfy duplicated ingredient predicates twice" {
        WorkbenchCraftingFixture().use { fixture ->
            val recipe = recipe(ApocalypseSourceLootItem("minecraft:diamond", 1), "minecraft:iron_ingot" to 2, "minecraft:iron_ingot" to 2)
            val storage = arrayOfNulls<ItemStack>(36).also { it[0] = ItemStack.of(Material.IRON_INGOT, 3) }
            fixture.crafting.plan(recipe, storage, fixture.player)::class shouldBe ApocalypseWorkbenchCraftResult.Missing::class
            storage[0]!!.amount shouldBe 3
        }
    }

    "mixed alloy fuel costs share one cell exactly and successive titanium batches retain forty units" {
        WorkbenchCraftingFixture().use { fixture ->
            val crafting = fixture.crafting
            val base = recipe(ApocalypseSourceLootItem("minecraft:diamond", 1), "minecraft:iron_ingot" to 1)
            fun storage() = arrayOfNulls<ItemStack>(36).also {
                it[0] = ItemStack.of(Material.IRON_INGOT, 3)
                it[1] = crafting.sourceItem(ApocalypseSourceItemIdentity(ApocalypseLootItems.REFINERY_FUEL_CELL))
            }
            var mixed = storage()
            for (cost in listOf(400, 600)) mixed = (crafting.plan(base.copy(fuelUnits = cost), mixed, fixture.player)
                as ApocalypseWorkbenchCraftResult.Ready).afterStorage.toTypedArray()
            mixed.sumOf(crafting::fuelUnits) shouldBe 0
            mixed.none { crafting.identity(it)?.itemId == ApocalypseLootItems.REFINERY_FUEL_CELL } shouldBe true
            var titanium = storage()
            repeat(3) { titanium = (crafting.plan(base.copy(fuelUnits = 320), titanium, fixture.player)
                as ApocalypseWorkbenchCraftResult.Ready).afterStorage.toTypedArray() }
            titanium.sumOf(crafting::fuelUnits) shouldBe 40
            val remainder = titanium.filterNotNull().single { crafting.fuelUnits(it) > 0 }
            remainder.amount shouldBe 1
            remainder.maxStackSize shouldBe 1
            remainder.editMeta { it.displayName(net.kyori.adventure.text.Component.text("Full cell renamed")) }
            crafting.fuelUnits(remainder) shouldBe 40
            crafting.fuelUnits(remainder.asQuantity(2)) shouldBe 0
        }
    }

    "fuel overflow rejects the entire plan and fresh-cell splitting or counterfeit carriers cannot add energy" {
        WorkbenchCraftingFixture().use { fixture ->
            val crafting = fixture.crafting
            val recipe = recipe(ApocalypseSourceLootItem("minecraft:diamond", 1), "minecraft:iron_ingot" to 1).copy(fuelUnits = 320)
            val storage = Array<ItemStack?>(36) { ItemStack.of(Material.COBBLESTONE, 64) }
            storage[0] = ItemStack.of(Material.IRON_INGOT, 2)
            storage[1] = crafting.sourceItem(ApocalypseSourceItemIdentity(ApocalypseLootItems.REFINERY_FUEL_CELL), 2)
            storage[2] = ItemStack.of(Material.DIAMOND, 63)
            val before = storage.map { it?.clone() }
            crafting.plan(recipe, storage, fixture.player) shouldBe ApocalypseWorkbenchCraftResult.Full
            storage.asList() shouldBe before
            storage.sumOf(crafting::fuelUnits) shouldBe 2000
            val cells = crafting.sourceItem(ApocalypseSourceItemIdentity(ApocalypseLootItems.REFINERY_FUEL_CELL), 10)
            crafting.fuelUnits(cells.asQuantity(4)) + crafting.fuelUnits(cells.asQuantity(6)) shouldBe 10_000
            val fake = ItemStack.of(Material.BLAZE_POWDER, 64).also { it.editMeta { meta ->
                meta.displayName(net.kyori.adventure.text.Component.text("Refinery fuel cell"))
                meta.persistentDataContainer.set(NamespacedKey(fixture.plugin, "apocalypse_refinery_fuel_units"), PersistentDataType.INTEGER, 1000)
            } }
            crafting.fuelUnits(fake) shouldBe 0
            val invalid = cells.asQuantity(1).also { it.editMeta { meta ->
                meta.persistentDataContainer.set(NamespacedKey(fixture.plugin, "apocalypse_refinery_fuel_units"), PersistentDataType.INTEGER, 1001)
            } }
            crafting.fuelUnits(invalid) shouldBe 0
            crafting.identity(invalid) shouldBe null
        }
    }

    "bulk source fuel recipe charges one complete lava bucket and returns the bucket with ten cells" {
        WorkbenchCraftingFixture().use { fixture ->
            val crafting = fixture.crafting
            val recipe = crafting.catalog.find("the_deeper_void:make_fuel")!!
            val storage = arrayOfNulls<ItemStack>(36).also {
                it[0] = ItemStack.of(Material.BLAZE_ROD, 10)
                it[1] = ItemStack.of(Material.COAL_BLOCK, 10)
                it[2] = crafting.sourceItem(ApocalypseSourceItemIdentity("powderedup:sulfur_dust"), 10)
                it[3] = ItemStack.of(Material.LAVA_BUCKET)
            }
            val result = crafting.plan(recipe, storage, fixture.player) as ApocalypseWorkbenchCraftResult.Ready
            result.afterStorage.sumOf(crafting::fuelUnits) shouldBe 10_000
            result.afterStorage.filterNotNull().filter { it.type == Material.BUCKET }.sumOf(ItemStack::getAmount) shouldBe 1
            result.outputs.sumOf(ItemStack::getAmount) shouldBe 11
            storage[3]!!.type shouldBe Material.LAVA_BUCKET
        }
    }
})

private fun recipe(result: ApocalypseSourceLootItem, vararg ingredients: Pair<String, Int>) = ApocalypseWorkbenchRecipe(
    "test:craft", ApocalypseWorkbenchStation.T1, ApocalypseWorkbenchRecipeKind.MATERIAL, result,
    ingredients.map { (id, count) -> ApocalypseWorkbenchIngredient(listOf(ApocalypseSourceItemIdentity(id)), count) })

private class WorkbenchCraftingFixture : AutoCloseable {
    val paper = MockBukkitTestRuntime.open()
    val plugin = paper.createSimplePlugin("WorkbenchTest")
    val player = paper.addPlayer("Crafter")
    val crafting: ApocalypseWorkbenchCrafting

    init {
        ConfigManager.clear()
        val root = Files.createTempDirectory("apocalypse-workbench-")
        for (resource in listOf("config.yml", "lang/ru.yml", "lang/en.yml")) {
            val target = root.resolve(resource); Files.createDirectories(target.parent)
            javaClass.getResourceAsStream("/$resource")!!.use { Files.copy(it, target) }
        }
        val settings = ArcEventsConfig.load(root)
        val locale = ArcEventsLocale(root) { settings }
        crafting = ApocalypseWorkbenchCrafting(plugin, locale, TttFirearms(plugin, locale) { settings }, ApocalypseWorkbenchRecipes.load())
    }

    override fun close() {
        paper.close()
        ConfigManager.clear()
    }
}
