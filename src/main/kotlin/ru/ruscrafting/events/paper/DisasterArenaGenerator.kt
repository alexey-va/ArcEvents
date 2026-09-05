package ru.ruscrafting.events.paper

import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.World
import org.bukkit.generator.ChunkGenerator
import org.bukkit.generator.WorldInfo
import java.util.Random
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** A bounded arena with climbable terraces and shelters; hazards never modify its blocks. */
class DisasterArenaGenerator : ChunkGenerator() {
    override fun generateSurface(worldInfo: WorldInfo, random: Random, chunkX: Int, chunkZ: Int, chunkData: ChunkData) {
        for (x in 0..15) for (z in 0..15) for (y in 60..72) {
            materialAt(chunkX * 16 + x, y, chunkZ * 16 + z)?.let { chunkData.setBlock(x, y, z, it) }
        }
    }

    override fun getFixedSpawnLocation(world: World, random: Random) = Location(world, 0.5, 65.0, 0.5)
    override fun shouldGenerateNoise() = false
    override fun shouldGenerateSurface() = false
    override fun shouldGenerateCaves() = false
    override fun shouldGenerateDecorations() = false
    override fun shouldGenerateMobs() = false
    override fun shouldGenerateStructures() = false

    companion object {
        const val TEMPLATE = "disasters-v1"

        fun materialAt(x: Int, y: Int, z: Int): Material? {
            if (abs(x) > 24 || abs(z) > 24 || y !in 60..72) return null
            if (y < 64) return Material.STONE
            if (y == 64) return if (abs(x) <= 2 || abs(z) <= 2) Material.POLISHED_ANDESITE else Material.MOSS_BLOCK
            if ((abs(x) == 24 || abs(z) == 24) && y <= 67) return Material.STONE_BRICKS
            val terraceDistance = max(abs(abs(x) - 13), abs(abs(z) - 13))
            if (terraceDistance <= 6 && y <= 64 + min(3, 7 - terraceDistance)) return Material.SMOOTH_STONE
            // Two open shelters beside the central crossroads; roof height leaves five blocks of headroom.
            if (abs(abs(x) - 13) <= 4 && abs(z) <= 3) {
                if (y == 70) return Material.WAXED_CUT_COPPER
                if (abs(abs(x) - 13) == 4 && abs(z) == 3 && y < 70) return Material.STONE_BRICKS
            }
            return null
        }
    }
}
