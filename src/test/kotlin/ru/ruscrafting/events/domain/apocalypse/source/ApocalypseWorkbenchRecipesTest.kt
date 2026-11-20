package ru.ruscrafting.events.domain.apocalypse.source

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

class ApocalypseWorkbenchRecipesTest : StringSpec({
    "bundled source recipes retain all three gun tiers exact materials and separate ammunition stations" {
        val catalog = ApocalypseWorkbenchRecipes.load()
        catalog.all.count { it.kind == ApocalypseWorkbenchRecipeKind.GUN } shouldBe 66
        listOf(ApocalypseWorkbenchStation.T1, ApocalypseWorkbenchStation.T2, ApocalypseWorkbenchStation.T3).map { station ->
            catalog.recipes(station).count { it.kind == ApocalypseWorkbenchRecipeKind.GUN }
        } shouldBe listOf(12, 37, 17)
        catalog.all.count { it.kind == ApocalypseWorkbenchRecipeKind.AMMO } shouldBe 21
        catalog.recipes(ApocalypseWorkbenchStation.AMMO).size shouldBe 18
        catalog.recipes(ApocalypseWorkbenchStation.T3).count { it.kind == ApocalypseWorkbenchRecipeKind.AMMO } shouldBe 9
        catalog.availableAt("tacz:ammo/12g", ApocalypseWorkbenchStation.T3) shouldBe true
        catalog.availableAt("tacz:ammo/12g", ApocalypseWorkbenchStation.T1) shouldBe false
        catalog.find("bf1:ammo/450") shouldBe null
        catalog.all.none { "attachment" in it.id } shouldBe true
        val upgrade = catalog.find("bf1:misc/tester")!!
        upgrade.ingredients.map { it.alternatives.single().itemId to it.count } shouldBe listOf(
            "create:iron_sheet" to 45, "create:golden_sheet" to 16, "minecraft:quartz" to 25)
        upgrade.result.itemId shouldBe "tacz:gun_smith_table"
        val gun = catalog.find("sfms:gun/har50")!!
        gun.ingredients.first().alternatives.single() shouldBe ApocalypseSourceItemIdentity("tacz:modern_kinetic_gun", gunId = "tacz:m249")
        gun.ingredients[1].alternatives.single().itemId shouldBe "the_deeper_void:netherite_rhenium_alloy"
        gun.ingredients[1].count shouldBe 2
        gun.unavailableInput shouldBe null
        catalog.all.count { it.unavailableInput != null } shouldBe 0
        catalog.recipes(ApocalypseWorkbenchStation.PRESS).map { it.result.itemId to it.result.count } shouldBe listOf(
            "create:iron_sheet" to 1, "create:golden_sheet" to 1, "createdeco:industrial_iron_sheet" to 1)
        catalog.recipes(ApocalypseWorkbenchStation.REFINERY).map { it.fuelUnits to it.byproducts.single().count } shouldBe
            listOf(320 to 2, 400 to 2, 600 to 4)
        val fuel = catalog.find("the_deeper_void:make_fuel")!!
        fuel.ingredients.map { it.alternatives.single().itemId to it.count } shouldBe listOf(
            "minecraft:blaze_rod" to 10, "minecraft:coal_block" to 10, "powderedup:sulfur_dust" to 10, "minecraft:lava_bucket" to 1)
        fuel.result.count shouldBe 10
        fuel.byproducts.single() shouldBe ApocalypseSourceLootItem("minecraft:bucket", 1)
    }

    "source tags retain exact source members instead of native carrier materials" {
        val ingredients = ApocalypseWorkbenchRecipes.load().all.flatMap { it.ingredients }
        fun members(tag: String) = ingredients.first { it.sourceTag == tag }.alternatives.map { it.itemId }
        members("forge:plates/iron") shouldBe listOf("create:iron_sheet")
        members("forge:plates/obsidian") shouldBe listOf("create:sturdy_sheet")
        members("forge:ingots/titanium") shouldBe listOf("mcore:titanium_ingot")
        members("forge:ingots/brass") shouldBe listOf("create:brass_ingot")
        members("minecraft:logs").size shouldBe 40
        members("minecraft:planks").size shouldBe 11
    }

    "native vaccine recipe is available only at the ordinary crafting table" {
        val catalog = ApocalypseWorkbenchRecipes.load()
        val recipe = requireNotNull(catalog.find("arcevents:vaccine"))
        ApocalypseWorkbenchStation.entries.filter { catalog.availableAt(recipe.id, it) } shouldBe
            listOf(ApocalypseWorkbenchStation.CRAFTING)
        catalog.recipes(ApocalypseWorkbenchStation.CRAFTING).first() shouldBe recipe
        recipe.kind shouldBe ApocalypseWorkbenchRecipeKind.MATERIAL
        recipe.result shouldBe ApocalypseSourceLootItem("cure:vaccine", 1)
        recipe.ingredients.map { it.alternatives.single() to it.count } shouldBe listOf(
            ApocalypseSourceItemIdentity("minecraft:glass_bottle") to 1,
            ApocalypseSourceItemIdentity("minecraft:gold_ingot") to 1,
            ApocalypseSourceItemIdentity("minecraft:redstone") to 2,
        )
    }

    "catalog rejects unresolved tags and unsupported partial NBT rather than weakening ingredients" {
        val base = """{"schema":1,"recipes":[{"id":"test:gun","station":"bf1:telegraph","kind":"gun",
            "inputs":[%s],"output":{"kind":"gun","id":"tacz:m1911","count":1}}]}"""
        shouldThrow<IllegalArgumentException> { ApocalypseWorkbenchRecipes.decode(base.format("""{"tag":"forge:unknown","count":1}""")) }
        shouldThrow<IllegalArgumentException> { ApocalypseWorkbenchRecipes.decode(base.format(
            """{"item":"tacz:modern_kinetic_gun","count":1,"nbt":{"Unrecognized":1}}""")) }
        val expected = ApocalypseSourceItemIdentity("tacz:modern_kinetic_gun", gunId = "tacz:m1911")
        expected.accepts(ApocalypseSourceItemIdentity("tacz:modern_kinetic_gun", gunId = "tacz:m1911")) shouldBe true
        expected.accepts(ApocalypseSourceItemIdentity("tacz:modern_kinetic_gun", gunId = "tacz:p320")) shouldBe false
    }
})
