package ru.ruscrafting.events.paper

import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.World
import org.bukkit.generator.ChunkGenerator
import org.bukkit.generator.WorldInfo
import ru.ruscrafting.events.domain.ZombiesOffer
import java.util.Random
import kotlin.math.abs
import kotlin.math.max

/** Zero Sector: ground-level circulation around a reactor, with readable cover and four supply bays. */
class ZombiesArenaGenerator : ChunkGenerator() {
    override fun generateSurface(worldInfo: WorldInfo, random: Random, chunkX: Int, chunkZ: Int, chunkData: ChunkData) {
        for (x in 0..15) for (z in 0..15) for (y in 60..77) {
            materialAt(chunkX * 16 + x, y, chunkZ * 16 + z)?.let { chunkData.setBlock(x, y, z, it) }
        }
    }
    override fun getFixedSpawnLocation(world: World, random: Random) = Location(world, .5, 65.0, -18.5)
    override fun shouldGenerateNoise() = false
    override fun shouldGenerateSurface() = false
    override fun shouldGenerateCaves() = false
    override fun shouldGenerateDecorations() = false
    override fun shouldGenerateMobs() = false
    override fun shouldGenerateStructures() = false

    companion object {
        const val TEMPLATE = "zombies-zero-v1"
        val STATIONS = linkedMapOf(ZombiesOffer.AMMO to -12, ZombiesOffer.MYSTERY to -4,
            ZombiesOffer.POWER to 4, ZombiesOffer.VITALITY to 12)
        val SPAWNS = listOf(-23 to -15, 23 to -15, -23 to 5, 23 to 5, -19 to 23, 19 to 23, -7 to 24, 7 to 24)

        fun materialAt(x: Int, y: Int, z: Int): Material? {
            if (abs(x) > 28 || abs(z) > 28 || y !in 60..77) return null
            if (y < 64) return Material.DEEPSLATE
            val radius = max(abs(x), abs(z))
            if (y == 64) return when {
                radius == 6 -> if (Math.floorMod(x + z, 4) < 2) Material.YELLOW_CONCRETE else Material.BLACK_CONCRETE
                abs(x) <= 2 || abs(z) <= 2 || abs(z + 20) <= 1 -> Material.POLISHED_ANDESITE
                abs(x) == 25 || abs(z) == 25 -> Material.CUT_COPPER
                else -> Material.GRAY_CONCRETE
            }
            if (radius == 28 && y <= 73) {
                val alongWall = if (abs(x) == 28) z else x
                return when {
                    z == 28 && abs(x) <= 5 && y <= 70 -> if (x % 2 == 0) Material.IRON_BLOCK else Material.BLACK_CONCRETE
                    y == 70 -> Material.SEA_LANTERN
                    y == 65 || y == 73 || alongWall % 7 == 0 -> Material.POLISHED_DEEPSLATE
                    y == 68 -> Material.RED_CONCRETE
                    else -> Material.DEEPSLATE_TILES
                }
            }
            if (radius <= 4 && y <= 75) return when {
                y == 65 || y >= 74 -> Material.POLISHED_DEEPSLATE
                abs(x) <= 1 && abs(z) <= 1 -> Material.SEA_LANTERN
                radius == 4 && (abs(x) == abs(z) || y == 69) -> Material.WAXED_OXIDIZED_CUT_COPPER
                radius == 4 -> Material.CYAN_STAINED_GLASS
                else -> null
            }
            // Solid, four-block-high cargo masses cannot become safe player-only perches.
            if (abs(abs(x) - 15) <= 4 && abs(abs(z) - 9) <= 3 && y <= 68) return when {
                y == 68 || abs(abs(x) - 15) == 4 -> Material.BLACK_CONCRETE
                y == 66 && abs(abs(z) - 9) == 3 -> Material.LIGHT_GRAY_CONCRETE
                x < 0 -> Material.WAXED_CUT_COPPER
                else -> Material.CYAN_TERRACOTTA
            }
            val station = STATIONS.entries.firstOrNull { abs(x - it.value) <= 2 && z in -24..-22 }
            if (station != null) {
                if (y == 65) return if (x == station.value && z == -22) when (station.key) {
                    ZombiesOffer.AMMO -> Material.BARREL
                    ZombiesOffer.MYSTERY -> Material.ENDER_CHEST
                    ZombiesOffer.POWER -> Material.SMITHING_TABLE
                    ZombiesOffer.VITALITY -> Material.BREWING_STAND
                } else Material.POLISHED_DEEPSLATE
                if (y == 69) return if (x == station.value) Material.SEA_LANTERN else Material.WAXED_OXIDIZED_CUT_COPPER
                if (z == -24 && abs(x - station.value) == 2 && y < 69) return Material.IRON_BLOCK
            }
            // Tall light pylons mark the entries without narrowing the circulation loop.
            if (abs(x) == 23 && z in setOf(-19, 19) && y <= 72) return if (y == 72) Material.SEA_LANTERN else Material.POLISHED_DEEPSLATE
            return null
        }
    }
}
