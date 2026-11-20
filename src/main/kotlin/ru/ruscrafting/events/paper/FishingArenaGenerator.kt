package ru.ruscrafting.events.paper

import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.World
import org.bukkit.block.Biome
import org.bukkit.generator.BiomeProvider
import org.bukkit.generator.ChunkGenerator
import org.bukkit.generator.WorldInfo
import java.util.Random
import kotlin.math.abs

enum class FishingArenaStage(val centerX: Int, val deck: Material, private val maxShoreZ: Double = 96.0) {
    CAMP(0, Material.OAK_PLANKS, maxShoreZ = 121.0),
    REEF(384, Material.JUNGLE_PLANKS),
    SHRINE(768, Material.DARK_OAK_PLANKS),
    CLIFFS(1152, Material.SPRUCE_PLANKS),
    VOLCANO(1536, Material.CRIMSON_PLANKS);

    internal fun containsShore(x: Double, y: Double, z: Double): Boolean =
        x in (centerX - 40.0)..(centerX + 40.0) && y in 53.0..130.0 && z in -28.0..maxShoreZ

    internal val harbour by lazy {
        AuthoredArenaVolume.load("/arena-templates/${FishingArenaGenerator.TEMPLATE}/${name.lowercase()}.schem")
    }
}

data class FishingArenaPoint(
    val x: Double,
    val y: Double,
    val z: Double,
    val yaw: Float = 0f,
    val pitch: Float = 0f,
)

data class FishingArenaZone(
    val center: FishingArenaPoint,
    val minX: Int,
    val maxX: Int,
    val minZ: Int,
    val maxZ: Int,
    val waterSurfaceY: Int,
    val minimumDepth: Int,
)

/** Five authored harbours share a continuous ocean. Their individual credits ship alongside the schematics. */
class FishingArenaGenerator : ChunkGenerator() {
    override fun generateSurface(worldInfo: WorldInfo, random: Random, chunkX: Int, chunkZ: Int, chunkData: ChunkData) {
        chunkData.setRegion(0, MIN_Y, 0, 16, MIN_Y + 1, 16, Material.BEDROCK)
        chunkData.setRegion(0, MIN_Y + 1, 0, 16, OCEAN_FLOOR_Y, 16, Material.STONE)
        chunkData.setRegion(0, OCEAN_FLOOR_Y, 0, 16, SEA_Y, 16, Material.GRAVEL)
        chunkData.setRegion(0, SEA_Y, 0, 16, WATER_SURFACE_Y + 1, 16, Material.WATER)
        val z = chunkZ * 16
        if (z + 15 < MIN_HARBOUR_Z) return
        val x = chunkX * 16
        val stage = stageNear(x + 8) ?: return
        if (x + 15 < stage.centerX - HARBOUR_HALF_WIDTH || x > stage.centerX + HARBOUR_HALF_WIDTH) return
        stage.harbour.paint(chunkData, chunkX, chunkZ, stage.centerX - HARBOUR_HALF_WIDTH, SEA_Y, MIN_HARBOUR_Z)
    }

    override fun getFixedSpawnLocation(world: World, random: Random): Location =
        spawn(FishingArenaStage.CAMP).let { Location(world, it.x, it.y, it.z, it.yaw, it.pitch) }

    override fun getDefaultBiomeProvider(worldInfo: WorldInfo): BiomeProvider = object : BiomeProvider() {
        override fun getBiome(info: WorldInfo, x: Int, y: Int, z: Int): Biome = when (stageNear(x)) {
            FishingArenaStage.CAMP -> Biome.LUKEWARM_OCEAN
            FishingArenaStage.REEF -> Biome.WARM_OCEAN
            FishingArenaStage.CLIFFS -> Biome.COLD_OCEAN
            else -> Biome.OCEAN
        }
        override fun getBiomes(info: WorldInfo) = listOf(Biome.LUKEWARM_OCEAN, Biome.WARM_OCEAN, Biome.COLD_OCEAN, Biome.OCEAN)
    }

    override fun shouldGenerateNoise(worldInfo: WorldInfo, random: Random, chunkX: Int, chunkZ: Int) = false
    override fun shouldGenerateSurface(worldInfo: WorldInfo, random: Random, chunkX: Int, chunkZ: Int) = false
    override fun shouldGenerateCaves(worldInfo: WorldInfo, random: Random, chunkX: Int, chunkZ: Int) = false
    override fun shouldGenerateDecorations(worldInfo: WorldInfo, random: Random, chunkX: Int, chunkZ: Int) = false
    override fun shouldGenerateMobs(worldInfo: WorldInfo, random: Random, chunkX: Int, chunkZ: Int) = false
    override fun shouldGenerateStructures(worldInfo: WorldInfo, random: Random, chunkX: Int, chunkZ: Int) = false

    companion object {
        const val TEMPLATE = "fishing-archipelago-v2"
        const val SEED = 0x415243464953484CL
        const val MIN_Y = 54
        const val MAX_Y = 130
        const val OCEAN_FLOOR_Y = 59
        const val SEA_Y = OCEAN_FLOOR_Y + 1
        const val WATER_SURFACE_Y = 63
        const val WALK_Y = 64
        const val SPAWN_Y = WALK_Y + 1
        const val MIN_FISHING_DEPTH = WATER_SURFACE_Y - SEA_Y + 1
        internal const val HARBOUR_HALF_WIDTH = 65
        private const val MIN_HARBOUR_Z = -30
        private const val STAGE_SPACING = 384

        fun spawn(stage: FishingArenaStage) = point(stage, 0, 0)
        fun fightCenter(stage: FishingArenaStage) = point(stage, 0, -4)
        fun catchLanding(stage: FishingArenaStage) = point(stage, 0, 10)
        fun trader(stage: FishingArenaStage) = point(stage, 8, 0)
        fun exit(stage: FishingArenaStage) = point(stage, 0, -9, yaw = 180f)
        fun destination(stage: FishingArenaStage) = exit(stage)

        fun fishingZone(stage: FishingArenaStage) = FishingArenaZone(
            point(stage, 0, 25, WATER_SURFACE_Y), stage.centerX - 8, stage.centerX + 8, 18, 36,
            WATER_SURFACE_Y, MIN_FISHING_DEPTH,
        )

        private fun point(stage: FishingArenaStage, dx: Int, z: Int, y: Int = SPAWN_Y, yaw: Float = 0f) =
            FishingArenaPoint(stage.centerX + dx + 0.5, y.toDouble(), z + 0.5, yaw)

        private fun stageNear(x: Int) = FishingArenaStage.entries.getOrNull((x + STAGE_SPACING / 2).floorDiv(STAGE_SPACING))

        fun materialAt(x: Int, y: Int, z: Int): Material? {
            if (y !in MIN_Y..MAX_Y) return null
            val stage = stageNear(x)
            if (stage != null && abs(x - stage.centerX) <= HARBOUR_HALF_WIDTH && z >= MIN_HARBOUR_Z && y >= SEA_Y) {
                stage.harbour.materialAt(x - stage.centerX + HARBOUR_HALF_WIDTH, y - SEA_Y, z - MIN_HARBOUR_Z)?.let { return it }
            }
            return when (y) {
                MIN_Y -> Material.BEDROCK
                in (MIN_Y + 1) until OCEAN_FLOOR_Y -> Material.STONE
                OCEAN_FLOOR_Y -> Material.GRAVEL
                in SEA_Y..WATER_SURFACE_Y -> Material.WATER
                else -> null
            }
        }
    }
}
