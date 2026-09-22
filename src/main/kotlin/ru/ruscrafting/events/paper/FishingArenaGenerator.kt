package ru.ruscrafting.events.paper

import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.World
import org.bukkit.generator.ChunkGenerator
import org.bukkit.generator.WorldInfo
import java.util.Random
import kotlin.math.abs

enum class FishingArenaStage(
    val centerX: Int,
) {
    CAMP(0),
    REEF(48),
    SHRINE(96),
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

/** Deterministic three-island fishing map; generation is the only block mutation. */
class FishingArenaGenerator : ChunkGenerator() {
    override fun generateSurface(
        worldInfo: WorldInfo,
        random: Random,
        chunkX: Int,
        chunkZ: Int,
        chunkData: ChunkData,
    ) {
        val startX = chunkX shl 4
        val startZ = chunkZ shl 4
        for (localX in 0..15) {
            val x = startX + localX
            if (x !in MIN_X..MAX_X) continue
            for (localZ in 0..15) {
                val z = startZ + localZ
                if (z !in MIN_Z..MAX_Z) continue
                for (y in MIN_Y..MAX_Y) {
                    materialAt(x, y, z)?.let { chunkData.setBlock(localX, y, localZ, it) }
                }
            }
        }
    }

    override fun getFixedSpawnLocation(world: World, random: Random): Location {
        val spawn = spawn(FishingArenaStage.CAMP)
        return Location(world, spawn.x, spawn.y, spawn.z, spawn.yaw, spawn.pitch)
    }

    override fun shouldGenerateNoise(worldInfo: WorldInfo, random: Random, chunkX: Int, chunkZ: Int): Boolean = false
    override fun shouldGenerateSurface(worldInfo: WorldInfo, random: Random, chunkX: Int, chunkZ: Int): Boolean = false
    override fun shouldGenerateCaves(worldInfo: WorldInfo, random: Random, chunkX: Int, chunkZ: Int): Boolean = false
    override fun shouldGenerateDecorations(worldInfo: WorldInfo, random: Random, chunkX: Int, chunkZ: Int): Boolean = false
    override fun shouldGenerateMobs(worldInfo: WorldInfo, random: Random, chunkX: Int, chunkZ: Int): Boolean = false
    override fun shouldGenerateStructures(worldInfo: WorldInfo, random: Random, chunkX: Int, chunkZ: Int): Boolean = false

    companion object {
        const val TEMPLATE = "fishing-v1"
        const val SEED = 0x415243464953484CL
        const val MIN_X = -24
        const val MAX_X = 120
        const val MIN_Z = -24
        const val MAX_Z = 28
        const val MIN_Y = 54
        const val MAX_Y = 100
        const val OCEAN_FLOOR_Y = 61
        const val SEA_Y = 62
        const val WATER_SURFACE_Y = 63
        const val WALK_Y = 64
        const val SPAWN_Y = 65
        const val MIN_FISHING_DEPTH = WATER_SURFACE_Y - SEA_Y + 1

        fun spawn(stage: FishingArenaStage): FishingArenaPoint =
            point(stage, 0.0, SPAWN_Y.toDouble(), 0f)

        fun fishingZone(stage: FishingArenaStage): FishingArenaZone = FishingArenaZone(
            center = point(stage, 15.0, WATER_SURFACE_Y.toDouble(), 0f),
            minX = stage.centerX - 4,
            maxX = stage.centerX + 4,
            minZ = 14,
            maxZ = 18,
            waterSurfaceY = WATER_SURFACE_Y,
            minimumDepth = MIN_FISHING_DEPTH,
        )

        fun fightCenter(stage: FishingArenaStage): FishingArenaPoint =
            point(stage, -4.0, SPAWN_Y.toDouble(), 0f)

        fun exit(stage: FishingArenaStage): FishingArenaPoint =
            point(stage, -9.0, SPAWN_Y.toDouble(), 180f)

        fun destination(stage: FishingArenaStage): FishingArenaPoint = exit(stage)

        fun materialAt(x: Int, y: Int, z: Int): Material? {
            if (x !in MIN_X..MAX_X || y !in MIN_Y..MAX_Y || z !in MIN_Z..MAX_Z) return null
            if (y == MIN_Y) return Material.BEDROCK

            val stage = stageAt(x, z)
            if (stage != null) {
                if (dockSupport(stage.stage, x, y, z)) return stage.dockSupport
                if (y in (OCEAN_FLOOR_Y - 1)..WALK_Y && islandFootprint(stage.stage, x, z)) {
                    return if (y == WALK_Y) stage.surface(x, z) else stage.foundation
                }
                if (dockDeck(stage.stage, x, y, z)) return stage.dock
                stage.decoration(x, y, z)?.let { return it }
            }

            if (y in (MIN_Y + 1)..OCEAN_FLOOR_Y) return Material.STONE
            if (y in SEA_Y..WATER_SURFACE_Y) return Material.WATER
            return null
        }

        private fun point(stage: FishingArenaStage, relativeZ: Double, y: Double, yaw: Float): FishingArenaPoint =
            FishingArenaPoint(stage.centerX + 0.5, y, relativeZ + 0.5, yaw)

        private fun stageAt(x: Int, z: Int): StagePalette? =
            FishingArenaStage.entries.firstOrNull { islandFootprint(it, x, z) || dockFootprint(it, x, z) }?.palette

        private fun islandFootprint(stage: FishingArenaStage, x: Int, z: Int): Boolean {
            val dx = abs(x - stage.centerX)
            val dz = abs(z)
            if (dx <= 15 && dz <= 7) return true
            if (dz in 8..10 && dx <= 15 - (dz - 7) * 3) return true
            return dz == 11 && dx <= 5
        }

        private fun dockFootprint(stage: FishingArenaStage, x: Int, z: Int): Boolean =
            x in (stage.centerX - 5)..(stage.centerX + 5) && z in 7..13

        private fun dockDeck(stage: FishingArenaStage, x: Int, y: Int, z: Int): Boolean =
            y == WALK_Y && x in (stage.centerX - 5)..(stage.centerX + 5) && z in 7..13

        private fun dockSupport(stage: FishingArenaStage, x: Int, y: Int, z: Int): Boolean {
            if (y !in OCEAN_FLOOR_Y..(WATER_SURFACE_Y - 1) || z !in 8..13) return false
            return x == stage.centerX - 5 || x == stage.centerX + 5
        }

        private fun StagePalette.decoration(x: Int, y: Int, z: Int): Material? {
            val dx = x - stage.centerX
            return when (stage) {
                FishingArenaStage.CAMP -> campDecoration(dx, y, z)
                FishingArenaStage.REEF -> reefDecoration(dx, y, z)
                FishingArenaStage.SHRINE -> shrineDecoration(dx, y, z)
            }
        }

        private fun campDecoration(dx: Int, y: Int, z: Int): Material? {
            if (dx in -8..-4 && z in -4..0) {
                if (y in 65..67 && (dx == -8 || dx == -4 || z == -4 || z == 0)) return Material.OAK_LOG
                if (y == 68 && dx in -9..-3 && z in -5..1) return Material.ORANGE_WOOL
            }
            if (dx == 0 && z == -1 && y == 65) return Material.CAMPFIRE
            if (y == 65 && (dx == -2 || dx == 2) && z == -1) return Material.COBBLESTONE
            if (y == 69 && dx == 0 && z == -1) return Material.LANTERN
            if (y == 65 && dx in -4..4 && z == 10 && dx % 2 == 0) return Material.OAK_FENCE
            if (y == 66 && abs(dx) == 5 && z in 8..12) return Material.OAK_FENCE
            return null
        }

        private fun reefDecoration(dx: Int, y: Int, z: Int): Material? {
            if (y == 65 && z in -6..5 && dx in -14..14 && (dx + z).mod(7) == 0) {
                return when (abs(dx + z) % 3) {
                    0 -> Material.TUBE_CORAL_BLOCK
                    1 -> Material.BRAIN_CORAL_BLOCK
                    else -> Material.DEAD_TUBE_CORAL_BLOCK
                }
            }
            val ruin = (dx == -11 && z == -4) || (dx == 11 && z == 3) || (dx == -6 && z == 5)
            if (ruin && y in 65..71 && (y < 69 || (y + dx + z) % 3 != 0)) return Material.MOSSY_COBBLESTONE
            if (y == 65 && dx in -4..4 && z == 10 && dx % 2 == 0) return Material.PRISMARINE_WALL
            if (y == 66 && abs(dx) == 5 && z in 8..12) return Material.COBBLESTONE_WALL
            return null
        }

        private fun shrineDecoration(dx: Int, y: Int, z: Int): Material? {
            val pillar = (dx == -7 && z == -4) || (dx == 7 && z == -4) || (dx == -7 && z == 4) || (dx == 7 && z == 4)
            if (pillar && y in 65..74) return if (y >= 71) Material.AMETHYST_BLOCK else Material.BASALT
            if (y in 65..68 && dx in -3..3 && z == -4) return Material.POLISHED_BLACKSTONE_BRICKS
            if (y == 69 && dx in -2..2 && z == -4) return Material.PURPLE_STAINED_GLASS
            if (y == 70 && abs(dx) == 2 && z == -4) return Material.AMETHYST_BLOCK
            if (y == 65 && dx in -4..4 && z == 10 && dx % 2 == 0) return Material.POLISHED_BLACKSTONE_WALL
            if (y == 66 && abs(dx) == 5 && z in 8..12) return Material.BASALT
            if (y == 69 && dx == 0 && z == -8) return Material.AMETHYST_BLOCK
            return null
        }

        private enum class StagePalette(
            val stage: FishingArenaStage,
            val foundation: Material,
            val surface: Material,
            val dock: Material,
            val dockSupport: Material,
        ) {
            CAMP(FishingArenaStage.CAMP, Material.SANDSTONE, Material.SAND, Material.OAK_PLANKS, Material.OAK_LOG),
            REEF(FishingArenaStage.REEF, Material.STONE, Material.PRISMARINE_BRICKS, Material.DARK_OAK_PLANKS, Material.DARK_OAK_LOG),
            SHRINE(FishingArenaStage.SHRINE, Material.BASALT, Material.POLISHED_BLACKSTONE, Material.POLISHED_BASALT, Material.BASALT);

            companion object {
                fun forStage(stage: FishingArenaStage): StagePalette = entries.first { it.stage == stage }
            }
        }

        private val FishingArenaStage.palette: StagePalette
            get() = StagePalette.forStage(this)

        private fun StagePalette.surface(x: Int, z: Int): Material = when {
            stage == FishingArenaStage.CAMP && (x + z) % 7 == 0 -> Material.CUT_SANDSTONE
            stage == FishingArenaStage.REEF && (x + z) % 5 == 0 -> Material.MOSSY_COBBLESTONE
            stage == FishingArenaStage.SHRINE && (x - z) % 5 == 0 -> Material.BLACKSTONE
            else -> surface
        }
    }
}
