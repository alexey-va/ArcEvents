package ru.ruscrafting.events.paper

import org.bukkit.generator.ChunkGenerator
import org.bukkit.generator.WorldInfo
import java.util.Random

class EmptyArenaChunkGenerator : ChunkGenerator() {
    override fun generateNoise(
        worldInfo: WorldInfo,
        random: Random,
        chunkX: Int,
        chunkZ: Int,
        chunkData: ChunkData,
    ) = Unit

    override fun shouldGenerateNoise(): Boolean = false
    override fun shouldGenerateSurface(): Boolean = false
    override fun shouldGenerateCaves(): Boolean = false
    override fun shouldGenerateDecorations(): Boolean = false
    override fun shouldGenerateMobs(): Boolean = false
    override fun shouldGenerateStructures(): Boolean = false
}
