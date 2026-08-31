package ru.ruscrafting.events.paper

import ru.ruscrafting.events.config.EventLocation
import ru.ruscrafting.events.domain.FirearmId
import ru.ruscrafting.events.domain.FirearmSpec
import ru.ruscrafting.events.domain.TttFirearmCatalog
import kotlin.math.floor
import kotlin.random.Random

data class LootBlockKey(val world: String, val x: Int, val y: Int, val z: Int)

internal fun EventLocation.blockKey(): LootBlockKey = LootBlockKey(
    world = world,
    x = floor(x).toInt(),
    y = floor(y).toInt(),
    z = floor(z).toInt(),
)

data class TttLootSpawn(
    val point: EventLocation,
    val firearm: FirearmId?,
    val ammunition: Int,
    val exact: Boolean,
)

/** Produces one deterministic imported-map layout while keeping the configured pickup budget bounded. */
object TttLootLayoutPlanner {
    fun imported(
        points: List<EventLocation>,
        guaranteedWeaponPoints: List<EventLocation>,
        weaponCount: Int,
        seed: Long,
        catalog: Map<FirearmId, FirearmSpec> = TttFirearmCatalog.specs,
    ): List<TttLootSpawn> {
        require(points.isNotEmpty()) { "Imported loot catalog is empty" }
        require(points.map(EventLocation::blockKey).distinct().size == points.size) {
            "Imported loot catalog contains duplicate blocks"
        }
        require(guaranteedWeaponPoints.map(EventLocation::blockKey).distinct().size == guaranteedWeaponPoints.size) {
            "Guaranteed weapon points contain duplicate blocks"
        }
        require(weaponCount in 1..points.size) { "weapon-count must fit the imported loot catalog" }
        require(guaranteedWeaponPoints.size <= weaponCount) {
            "Guaranteed weapon points exceed weapon-count"
        }

        val random = Random(seed)
        val guaranteedKeys = guaranteedWeaponPoints.map(EventLocation::blockKey).toSet()
        val randomSlotCount = points.size - guaranteedWeaponPoints.size
        val randomPoints = points.filterNot { it.blockKey() in guaranteedKeys }.shuffled(random)
        require(randomPoints.size >= randomSlotCount) {
            "Guaranteed weapon points overlap an inconsistent loot catalog"
        }
        val selectedRandomPoints = randomPoints.take(randomSlotCount)
        val randomWeaponCount = weaponCount - guaranteedWeaponPoints.size
        val firearms = TttFirearmCatalog.lootSelection(catalog, weaponCount, seed xor LOOT_SEED_SALT).iterator()

        return buildList(points.size) {
            guaranteedWeaponPoints.forEach { point ->
                add(TttLootSpawn(point, firearms.next(), ammunition = 0, exact = true))
            }
            selectedRandomPoints.forEachIndexed { index, point ->
                if (index < randomWeaponCount) {
                    add(TttLootSpawn(point, firearms.next(), ammunition = 0, exact = false))
                } else {
                    add(TttLootSpawn(point, firearm = null, ammunition = AMMO_COUNTS.random(random), exact = false))
                }
            }
        }
    }

    private val AMMO_COUNTS = listOf(12, 16, 20, 24)
    private const val LOOT_SEED_SALT = 0x5EED5EEDL
}
