package ru.ruscrafting.events.paper.apocalypse

import org.bukkit.Bukkit
import org.bukkit.HeightMap
import org.bukkit.Location
import org.bukkit.World
import org.bukkit.block.data.BlockData
import org.bukkit.block.Biome
import org.bukkit.generator.BiomeProvider
import org.bukkit.generator.ChunkGenerator
import org.bukkit.generator.WorldInfo
import ru.ruscrafting.events.domain.apocalypse.*
import java.util.Random
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap

/** Deferred content is prepared off-thread even when My_Worlds resolves this before plugin enable. */
class ApocalypseOriginalGenerator(private val seed: Long, private val layout: CompletableFuture<ApocalypseLayout>) : ChunkGenerator() {
    private val terrain = ApocalypseChunkGenerator(seed)
    private val states = ConcurrentHashMap<String, BlockData>()
    override fun generateSurface(info: WorldInfo, random: Random, chunkX: Int, chunkZ: Int, data: ChunkData) {
        check(!Bukkit.isPrimaryThread()) { "Original structure generation must run on Paper's generation worker" }
        layout.join().generate(object : ApocalypseChunkCanvas(chunkX, chunkZ, data.minHeight, data.maxHeight - 1) {
            override fun fill(x1: Int, y1: Int, z1: Int, x2: Int, y2: Int, z2: Int, block: ApocalypseBlock) {
                data.setRegion(x1, y1, z1, x2 + 1, y2 + 1, z2 + 1, state(block.blockState))
            }
            override fun fillState(x: Int, y: Int, z: Int, state: String) { data.setBlock(x, y, z, state(state)) }
        })
    }
    private fun state(value: String): BlockData = states.computeIfAbsent(value, Bukkit::createBlockData)
    override fun getBaseHeight(info: WorldInfo, random: Random, x: Int, z: Int, map: HeightMap) = terrain.getBaseHeight(info, random, x, z, map)
    override fun getDefaultBiomeProvider(info: WorldInfo): BiomeProvider = object : BiomeProvider() {
        private val supported = listOf(Biome.PLAINS, Biome.FOREST, Biome.TAIGA, Biome.STONY_PEAKS,
            Biome.DEEP_OCEAN, Biome.RIVER, Biome.DRIPSTONE_CAVES, Biome.SNOWY_PLAINS, Biome.DESERT)
        override fun getBiomes(info: WorldInfo): List<Biome> = supported
        override fun getBiome(info: WorldInfo, x: Int, y: Int, z: Int): Biome {
            if (y < 10) return Biome.DRIPSTONE_CAVES
            val prepared = layout.join() as ApocalypseOriginalLayout
            return when (prepared.surfaceBiome(x, z)) {
                "deep_ocean" -> Biome.DEEP_OCEAN
                "river" -> Biome.RIVER
                "snowy_plains" -> Biome.SNOWY_PLAINS
                "desert" -> Biome.DESERT
                "stony_peaks" -> Biome.STONY_PEAKS
                "taiga" -> Biome.TAIGA
                "forest" -> Biome.FOREST
                else -> Biome.PLAINS
            }
        }
    }
    override fun getFixedSpawnLocation(world: World, random: Random): Location? = layout.getNow(null)?.spawn?.let {
        Location(world, it.x + .5, it.y.toDouble(), it.z + .5, 180f, 0f)
    }
    override fun shouldGenerateNoise() = false
    override fun shouldGenerateSurface() = false
    override fun shouldGenerateCaves() = false
    override fun shouldGenerateDecorations() = false
    override fun shouldGenerateMobs() = false
    override fun shouldGenerateStructures() = false
    companion object { const val TEMPLATE = "apocalypse-original-v1" }
}
