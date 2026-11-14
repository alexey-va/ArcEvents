package ru.ruscrafting.events.paper.apocalypse

import org.bukkit.Bukkit
import org.bukkit.HeightMap
import org.bukkit.Location
import org.bukkit.World
import org.bukkit.block.Biome
import org.bukkit.generator.BiomeProvider
import org.bukkit.generator.ChunkGenerator
import org.bukkit.generator.WorldInfo
import ru.ruscrafting.events.domain.apocalypse.ApocalypseBlock
import ru.ruscrafting.events.domain.apocalypse.ApocalypseChunkCanvas
import ru.ruscrafting.events.domain.apocalypse.ApocalypseWorldLayout
import java.util.Random

class ApocalypseChunkGenerator(val layout: ApocalypseWorldLayout) : ChunkGenerator() {
    constructor(seed: Long) : this(ApocalypseWorldLayout(seed))

    private val palette by lazy { ApocalypseBlock.entries.map { Bukkit.createBlockData(it.blockState) } }

    override fun generateSurface(worldInfo: WorldInfo, random: Random, chunkX: Int, chunkZ: Int, chunkData: ChunkData) {
        val states = palette
        layout.generate(object : ApocalypseChunkCanvas(chunkX, chunkZ, chunkData.minHeight, chunkData.maxHeight - 1) {
            override fun fill(x1: Int, y1: Int, z1: Int, x2: Int, y2: Int, z2: Int, block: ApocalypseBlock) {
                chunkData.setRegion(x1, y1, z1, x2 + 1, y2 + 1, z2 + 1, states[block.ordinal])
            }
        })
    }

    override fun getBaseHeight(worldInfo: WorldInfo, random: Random, x: Int, z: Int, heightMap: HeightMap): Int {
        val ground = layout.terrainHeight(x, z)
        return 1 + when (heightMap) {
            HeightMap.WORLD_SURFACE, HeightMap.WORLD_SURFACE_WG, HeightMap.MOTION_BLOCKING, HeightMap.MOTION_BLOCKING_NO_LEAVES -> maxOf(61, ground)
            else -> ground
        }
    }

    override fun getFixedSpawnLocation(world: World, random: Random): Location = layout.spawn.let {
        Location(world, it.x + 0.5, it.y.toDouble(), it.z + 0.5, 180f, 0f)
    }

    override fun getDefaultBiomeProvider(worldInfo: WorldInfo): BiomeProvider = object : BiomeProvider() {
        private val biomes = listOf(Biome.PLAINS, Biome.FOREST, Biome.TAIGA, Biome.STONY_PEAKS, Biome.OCEAN, Biome.RIVER, Biome.DRIPSTONE_CAVES)
        override fun getBiome(info: WorldInfo, x: Int, y: Int, z: Int): Biome {
            if (y < 10) return Biome.DRIPSTONE_CAVES
            val ground = layout.terrain.land(x, z)
            return when {
                ground < 55 -> Biome.OCEAN
                ground <= 61 -> Biome.RIVER
                ground > 96 -> Biome.STONY_PEAKS
                layout.terrain.field(x, z, 75, 232) > 0.22 -> Biome.TAIGA
                layout.terrain.field(x, z, 75, 232) > -0.22 -> Biome.FOREST
                else -> Biome.PLAINS
            }
        }
        override fun getBiomes(info: WorldInfo): List<Biome> = biomes
    }

    override fun shouldGenerateNoise() = false
    override fun shouldGenerateSurface() = false
    override fun shouldGenerateCaves() = false
    override fun shouldGenerateDecorations() = false
    override fun shouldGenerateMobs() = false
    override fun shouldGenerateStructures() = false

    companion object { const val TEMPLATE = "apocalypse-v1" }
}
