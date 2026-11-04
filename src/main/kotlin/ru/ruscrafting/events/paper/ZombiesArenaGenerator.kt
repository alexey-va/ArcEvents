package ru.ruscrafting.events.paper

import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.World
import org.bukkit.block.BlockFace
import org.bukkit.block.data.type.Chest
import org.bukkit.block.data.type.Fence
import org.bukkit.generator.ChunkGenerator
import org.bukkit.generator.WorldInfo
import ru.ruscrafting.events.domain.ZombiesOffer
import java.util.Random
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.floor

/** Zero Sector: ground-level circulation around a reactor, with readable cover and four supply bays. */
class ZombiesArenaGenerator : ChunkGenerator() {
    override fun generateSurface(worldInfo: WorldInfo, random: Random, chunkX: Int, chunkZ: Int, chunkData: ChunkData) {
        for (x in 0..15) for (z in 0..15) for (y in 60..77) {
            val wx = chunkX * 16 + x
            val wz = chunkZ * 16 + z
            materialAt(wx, y, wz)?.let { material ->
                if (material == Material.CHEST) chunkData.setBlock(x, y, z, chestData(wx))
                else if (material == Material.SPRUCE_FENCE) chunkData.setBlock(x, y, z, fenceData(wx, wz))
                else chunkData.setBlock(x, y, z, material)
            }
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
        const val TEMPLATE = "zombies-zero-v2"
        val STATIONS = linkedMapOf(ZombiesOffer.AMMO to -12, ZombiesOffer.MYSTERY to -4,
            ZombiesOffer.POWER to 4, ZombiesOffer.VITALITY to 12)
        val WINDOWS = listOf(Window(-25, -15, 1, 0), Window(25, -15, -1, 0),
            Window(-25, 5, 1, 0), Window(25, 5, -1, 0), Window(-7, 25, 0, -1), Window(7, 25, 0, -1))
        val SPAWNS = WINDOWS.map { floor(it.spawnX).toInt() to floor(it.spawnZ).toInt() }
        val MYSTERY_CHEST_X = -5..-4

        data class Window(val x: Int, val z: Int, val inwardX: Int, val inwardZ: Int) {
            val alongX get() = inwardZ
            val alongZ get() = -inwardX
            val spawnX get() = x + .5 - inwardX * 1.5
            val spawnZ get() = z + .5 - inwardZ * 1.5
            fun along(x: Double, z: Double) = (x - this.x - .5) * alongX + (z - this.z - .5) * alongZ
            fun inward(x: Double, z: Double) = (x - this.x - .5) * inwardX + (z - this.z - .5) * inwardZ
            fun gateCells() = (-1..1).map { offset -> Triple(x + alongX * offset, 65, z + alongZ * offset) }
        }

        fun chestData(x: Int): Chest = (Material.CHEST.createBlockData() as Chest).apply {
            facing = BlockFace.SOUTH
            type = if (x == MYSTERY_CHEST_X.first) Chest.Type.RIGHT else Chest.Type.LEFT
        }

        fun fenceData(x: Int, z: Int): Fence = (Material.SPRUCE_FENCE.createBlockData() as Fence).apply {
            val window = WINDOWS.first { abs(it.along(x + .5, z + .5)) <= 1 && abs(it.inward(x + .5, z + .5)) < .1 }
            val faces = if (window.alongX == 0) listOf(BlockFace.NORTH, BlockFace.SOUTH) else listOf(BlockFace.EAST, BlockFace.WEST)
            faces.forEach { setFace(it, true) }
        }

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
            // Six sealed ingress alcoves. Only their owned gate cells change during a match.
            for (window in WINDOWS) {
                val along = (x - window.x) * window.alongX + (z - window.z) * window.alongZ
                val inward = (x - window.x) * window.inwardX + (z - window.z) * window.inwardZ
                if (abs(along) <= 2 && inward in -2..0 && y <= 68) {
                    if (y == 68) return if (along == 0 && inward == 0) Material.SEA_LANTERN else Material.POLISHED_DEEPSLATE
                    if (abs(along) == 2) return if (inward == 0) Material.IRON_BLOCK else Material.DEEPSLATE_TILES
                    if (inward == 0 && y == 65) return Material.SPRUCE_FENCE
                    return null
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
                if (station.key == ZombiesOffer.MYSTERY && y == 65 && z == -22 && x in MYSTERY_CHEST_X) return Material.CHEST
                if (y == 65) return if (x == station.value && z == -22) when (station.key) {
                    ZombiesOffer.AMMO -> Material.BARREL
                    ZombiesOffer.MYSTERY -> Material.CHEST
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
