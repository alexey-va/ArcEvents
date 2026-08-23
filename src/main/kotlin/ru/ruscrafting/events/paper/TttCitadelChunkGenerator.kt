package ru.ruscrafting.events.paper

import org.bukkit.Location
import org.bukkit.World
import org.bukkit.generator.ChunkGenerator
import org.bukkit.generator.WorldInfo
import java.util.Random

class TttCitadelChunkGenerator : ChunkGenerator() {
    override fun generateSurface(
        worldInfo: WorldInfo,
        random: Random,
        chunkX: Int,
        chunkZ: Int,
        chunkData: ChunkData,
    ) {
        val startX = chunkX shl 4
        val startZ = chunkZ shl 4
        val minY = maxOf(chunkData.minHeight, TttCitadelBlueprint.MIN_Y)
        val maxY = minOf(chunkData.maxHeight - 1, TttCitadelBlueprint.MAX_Y)
        for (localX in 0..15) {
            val x = startX + localX
            if (x !in TttCitadelBlueprint.MIN_X..TttCitadelBlueprint.MAX_X) continue
            for (localZ in 0..15) {
                val z = startZ + localZ
                if (z !in TttCitadelBlueprint.MIN_Z..TttCitadelBlueprint.MAX_Z) continue
                for (y in minY..maxY) {
                    TttCitadelBlueprint.materialAt(x, y, z)?.let { chunkData.setBlock(localX, y, localZ, it) }
                }
            }
        }
    }

    override fun getFixedSpawnLocation(world: World, random: Random): Location =
        Location(world, TttCitadelBlueprint.lobby.x, TttCitadelBlueprint.lobby.y, TttCitadelBlueprint.lobby.z, 180f, 0f)

    override fun shouldGenerateNoise(worldInfo: WorldInfo, random: Random, chunkX: Int, chunkZ: Int): Boolean = false
    override fun shouldGenerateSurface(worldInfo: WorldInfo, random: Random, chunkX: Int, chunkZ: Int): Boolean = false
    override fun shouldGenerateCaves(worldInfo: WorldInfo, random: Random, chunkX: Int, chunkZ: Int): Boolean = false
    override fun shouldGenerateDecorations(worldInfo: WorldInfo, random: Random, chunkX: Int, chunkZ: Int): Boolean = false
    override fun shouldGenerateMobs(worldInfo: WorldInfo, random: Random, chunkX: Int, chunkZ: Int): Boolean = false
    override fun shouldGenerateStructures(worldInfo: WorldInfo, random: Random, chunkX: Int, chunkZ: Int): Boolean = false
}
