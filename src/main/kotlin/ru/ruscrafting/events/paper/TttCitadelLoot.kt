package ru.ruscrafting.events.paper

import ru.ruscrafting.events.domain.FirearmId
import ru.ruscrafting.events.domain.TttFirearmCatalog
import kotlin.random.Random

data class CitadelLootSpawn(val point: CitadelPoint, val firearm: FirearmId?, val ammunition: Int)

object TttCitadelLoot {
    private val weaponPoints = listOf(
        CitadelPoint(-12.0, 16.25, -12.0), CitadelPoint(12.0, 16.25, -12.0),
        CitadelPoint(-12.0, 16.25, 12.0), CitadelPoint(12.0, 16.25, 12.0),
        CitadelPoint(-10.0, 16.25, -40.0), CitadelPoint(10.0, 16.25, -50.0),
        CitadelPoint(40.0, 16.25, -10.0), CitadelPoint(50.0, 16.25, 10.0),
        CitadelPoint(-10.0, 16.25, 40.0), CitadelPoint(10.0, 16.25, 50.0),
        CitadelPoint(-40.0, 16.25, -10.0), CitadelPoint(-50.0, 16.25, 10.0),
        CitadelPoint(-10.0, 26.25, -8.0), CitadelPoint(10.0, 26.25, 8.0),
        CitadelPoint(-20.0, 7.25, -20.0), CitadelPoint(20.0, 7.25, 20.0),
        CitadelPoint(-20.0, 7.25, 20.0), CitadelPoint(20.0, 7.25, -20.0),
        CitadelPoint(0.0, 16.25, -30.0), CitadelPoint(0.0, 16.25, 30.0),
        CitadelPoint(2.0, 16.25, -48.0), CitadelPoint(48.0, 16.25, 2.0),
        CitadelPoint(2.0, 16.25, 48.0), CitadelPoint(-48.0, 16.25, 2.0),
        CitadelPoint(30.0, 16.25, -30.0), CitadelPoint(30.0, 16.25, 30.0),
        CitadelPoint(-30.0, 16.25, 30.0), CitadelPoint(-30.0, 16.25, -30.0),
    )
    private val ammunitionPoints = listOf(
        CitadelPoint(4.0, 16.25, -48.0), CitadelPoint(48.0, 16.25, 4.0),
        CitadelPoint(4.0, 16.25, 48.0), CitadelPoint(-48.0, 16.25, 4.0),
        CitadelPoint(-8.0, 26.25, 8.0), CitadelPoint(8.0, 26.25, -8.0),
        CitadelPoint(-32.0, 7.25, -24.0), CitadelPoint(32.0, 7.25, 24.0),
        CitadelPoint(-32.0, 7.25, 24.0), CitadelPoint(32.0, 7.25, -24.0),
        CitadelPoint(-28.0, 16.25, 0.0), CitadelPoint(28.0, 16.25, 0.0),
    )
    fun layout(seed: Long): List<CitadelLootSpawn> {
        val random = Random(seed)
        val weapons = TttFirearmCatalog.lootSelection(weaponPoints.size, seed)
        val weaponLoot = weaponPoints.zip(weapons) { point, firearm -> CitadelLootSpawn(point, firearm, 0) }
        val ammoLoot = ammunitionPoints.map { point -> CitadelLootSpawn(point, null, listOf(12, 16, 20, 24).random(random)) }
        return weaponLoot + ammoLoot
    }

    fun validate() {
        require(weaponPoints.size >= FirearmId.entries.size)
        require((weaponPoints + ammunitionPoints).distinctBy { Triple(it.x, it.y, it.z) }.size == weaponPoints.size + ammunitionPoints.size)
        (weaponPoints + ammunitionPoints).forEach { point ->
            require(point.x in TttCitadelBlueprint.PLAYABLE_MIN..TttCitadelBlueprint.PLAYABLE_MAX)
            require(point.z in TttCitadelBlueprint.PLAYABLE_MIN..TttCitadelBlueprint.PLAYABLE_MAX)
            val floorY = kotlin.math.floor(point.y).toInt() - 1
            require(TttCitadelBlueprint.materialAt(point.x.toInt(), floorY, point.z.toInt()) != null) {
                "Loot point has no generated floor: $point"
            }
            require(TttCitadelBlueprint.materialAt(point.x.toInt(), floorY + 1, point.z.toInt()) == null) {
                "Loot point is obstructed: $point"
            }
        }
    }
}
