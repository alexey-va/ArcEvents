package ru.ruscrafting.events.paper.apocalypse

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import ru.ruscrafting.events.domain.apocalypse.ApocalypseMarker
import ru.ruscrafting.events.domain.apocalypse.ApocalypseMarkerKind
import ru.ruscrafting.events.domain.apocalypse.ApocalypsePoint
import ru.ruscrafting.events.domain.apocalypse.source.ApocalypseSourceLoot
import ru.ruscrafting.events.domain.apocalypse.source.ApocalypseSourceLootDrop
import ru.ruscrafting.events.domain.apocalypse.source.ApocalypseSourceLootItem
import java.util.Random

class ApocalypseGuideWeaponCacheTest : StringSpec({
    "route to the closest unclaimed container whose seeded batch yields a native gun" {
        val table = "test:weapons"
        val loot = ApocalypseSourceLoot.decode(mapOf(table to """{
          "pools":[{"rolls":1,"entries":[
            {"type":"item","name":"minecraft:gunpowder"},
            {"type":"item","name":"tacz:modern_kinetic_gun","functions":[
              {"function":"minecraft:set_nbt","tag":"{GunId:\"tacz:m4a1\"}"}]}]}]}"""))

        fun armory(x: Int) = ApocalypseMarker("test", ApocalypseMarkerKind.LOOT, x, 1, 0, lootTable = table)
        fun drops(marker: ApocalypseMarker): List<ApocalypseSourceLootDrop> =
            if (marker.lootTable != null) {
                val id = marker.siteId + ":" + marker.x + ":" + marker.y + ":" + marker.z
                loot.roll(marker.lootTable, Random(68200133L xor id.hashCode().toLong()))
            } else marker.sourceItems.map { ApocalypseSourceLootDrop.Item(it) }

        val claimed = armory(2)
        val materials = armory(3)
        val unmapped = armory(4).copy(lootTable = null,
            sourceItems = listOf(ApocalypseSourceLootItem("tacz:modern_kinetic_gun", 1, gunId = "tacz:unknown")))
        val ammo = armory(5).copy(lootTable = null,
            sourceItems = listOf(ApocalypseSourceLootItem("pointblank:ammo9mm", 1)))
        val near = armory(8)
        val far = armory(20)
        val candidates = sequenceOf(far, materials, unmapped, claimed, ammo, near)
        val from = ApocalypsePoint(0, 1, 0)

        loot.hasFirearm(table) shouldBe true
        drops(materials).single() shouldBe ApocalypseSourceLootDrop.Item(ApocalypseSourceLootItem("minecraft:gunpowder", 1))
        nearestApocalypseWeaponCache(candidates, from, { false }, ::drops) shouldBe claimed
        nearestApocalypseWeaponCache(candidates, from, { it == claimed }, ::drops) shouldBe near
        nearestApocalypseWeaponCache(candidates, from, { it == claimed || it == near }, ::drops) shouldBe far
        nearestApocalypseWeaponCache(candidates, from, { it in setOf(claimed, near, far) }, ::drops) shouldBe null
    }

    "recognize authored direct firearm drops without a loot table" {
        val crate = ApocalypseMarker("test", ApocalypseMarkerKind.LOOT, 1, 1, 0,
            sourceItems = listOf(ApocalypseSourceLootItem("pointblank:ak47", 1)))
        nearestApocalypseWeaponCache(sequenceOf(crate), ApocalypsePoint(0, 1, 0), { false }) { marker ->
            marker.sourceItems.map { ApocalypseSourceLootDrop.Item(it) }
        } shouldBe crate
    }
})
