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
    REEF(64),
    SHRINE(128),
    CLIFFS(192),
    VOLCANO(256),
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

/** Deterministic five-island fishing map; generation is the only block mutation. */
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
        const val TEMPLATE = "fishing-v3"
        const val SEED = 0x415243464953484CL
        const val MIN_X = -32
        const val MAX_X = 288
        const val MIN_Z = -32
        const val MAX_Z = 32
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
            center = point(stage, 20.0, WATER_SURFACE_Y.toDouble(), 0f),
            minX = stage.centerX - 4,
            maxX = stage.centerX + 4,
            minZ = 18,
            maxZ = 22,
            waterSurfaceY = WATER_SURFACE_Y,
            minimumDepth = MIN_FISHING_DEPTH,
        )

        fun fightCenter(stage: FishingArenaStage): FishingArenaPoint =
            point(stage, -4.0, SPAWN_Y.toDouble(), 0f)

        fun catchLanding(stage: FishingArenaStage): FishingArenaPoint =
            point(stage, 10.0, SPAWN_Y.toDouble(), 0f)

        /** Center of the stage's clickable merchant barrel block. */
        fun trader(stage: FishingArenaStage): FishingArenaPoint =
            FishingArenaPoint(stage.centerX + 8.5, SPAWN_Y.toDouble(), 0.5)

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
                    return if (y == WALK_Y) {
                        if (shoreline(stage.stage, x, z) && !dockFootprint(stage.stage, x, z)) Material.STONE_SLAB
                        else stage.surface(x, z)
                    } else stage.foundation
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
            if (dx <= 20 && z in -15..8) return true
            return z in 9..13 && dx <= 20 - (z - 8) * 3
        }

        private fun shoreline(stage: FishingArenaStage, x: Int, z: Int): Boolean =
            !islandFootprint(stage, x + 1, z) || !islandFootprint(stage, x - 1, z) ||
                !islandFootprint(stage, x, z + 1) || !islandFootprint(stage, x, z - 1)

        private fun dockFootprint(stage: FishingArenaStage, x: Int, z: Int): Boolean =
            x in (stage.centerX - 5)..(stage.centerX + 5) && z in 7..17

        private fun dockDeck(stage: FishingArenaStage, x: Int, y: Int, z: Int): Boolean =
            y == WALK_Y && x in (stage.centerX - 5)..(stage.centerX + 5) && z in 7..17

        private fun dockSupport(stage: FishingArenaStage, x: Int, y: Int, z: Int): Boolean {
            if (y !in OCEAN_FLOOR_Y..(WATER_SURFACE_Y - 1) || z !in 8..17) return false
            return x == stage.centerX - 5 || x == stage.centerX + 5
        }

        private fun StagePalette.decoration(x: Int, y: Int, z: Int): Material? {
            val dx = x - stage.centerX
            tradingStallDecoration(dx, y, z)?.let { return it }
            exitDecoration(dx, y, z)?.let { return it }
            if (abs(dx) == 5 && z in 8..16 && y in 65..66) return dockRail
            return when (stage) {
                FishingArenaStage.CAMP -> campDecoration(dx, y, z)
                FishingArenaStage.REEF -> reefDecoration(dx, y, z)
                FishingArenaStage.SHRINE -> shrineDecoration(dx, y, z)
                FishingArenaStage.CLIFFS -> cliffsDecoration(dx, y, z)
                FishingArenaStage.VOLCANO -> volcanoDecoration(dx, y, z)
            }
        }

        private fun StagePalette.tradingStallDecoration(dx: Int, y: Int, z: Int): Material? = when {
            dx == 8 && y == 65 && z == 0 -> Material.BARREL
            dx == 9 && y == 65 && z == 0 -> stallCounter
            dx == 10 && y == 65 && z == 0 -> Material.SMOKER
            dx == 10 && y == 66 && z == 0 -> Material.CHISELED_BOOKSHELF
            dx in 10..11 && abs(z) == 2 && y in 65..68 -> stallFrame
            dx in 7..11 && z in -2..2 && y == 69 ->
                if ((dx + z).mod(2) == 0) awning else awningAccent
            dx == 9 && y == 70 && z == 0 -> Material.LANTERN
            else -> null
        }

        private fun StagePalette.exitDecoration(dx: Int, y: Int, z: Int): Material? = when {
            z == -9 && abs(dx) == 2 && y in 65..67 -> stallFrame
            z == -9 && abs(dx) <= 2 && y == 68 -> exitAccent
            else -> null
        }

        private fun isPath(dx: Int, z: Int): Boolean =
            (abs(dx) <= 1 && z in -10..6) || (dx in 1..8 && z in -1..1)

        private fun campDecoration(dx: Int, y: Int, z: Int): Material? {
            if (dx in -8..-4 && z in -4..0) {
                if (y in 65..67 && (dx == -8 || dx == -4 || z == -4 || z == 0)) return Material.OAK_LOG
                if (y == 68 && dx in -9..-3 && z in -5..1) return Material.ORANGE_WOOL
            }
            if (dx == -6 && z == -2 && y == 65) return Material.SMOKER
            if (dx == -6 && z == -2 && y == 67) return Material.LANTERN
            return null
        }

        private fun reefDecoration(dx: Int, y: Int, z: Int): Material? {
            if (y == 65 && z in -6..5 && dx in -14..14 && !isPath(dx, z) && (dx + z).mod(7) == 0) {
                return when (abs(dx + z) % 3) {
                    0 -> Material.TUBE_CORAL_BLOCK
                    1 -> Material.BRAIN_CORAL_BLOCK
                    else -> Material.DEAD_TUBE_CORAL_BLOCK
                }
            }
            val ruin = (dx == -11 && z == -4) || (dx == 11 && z == 3) || (dx == -6 && z == 5)
            if (ruin && y in 65..71 && (y < 69 || (y + dx + z) % 3 != 0)) return Material.MOSSY_COBBLESTONE
            return null
        }

        private fun shrineDecoration(dx: Int, y: Int, z: Int): Material? {
            val pillar = (dx == -7 && z == -4) || (dx == 7 && z == -4) || (dx == -7 && z == 4) || (dx == 7 && z == 4)
            if (pillar && y in 65..74) return if (y >= 71) Material.AMETHYST_BLOCK else Material.BASALT
            if (y in 65..68 && abs(dx) in 2..4 && z == -4) return Material.POLISHED_BLACKSTONE_BRICKS
            if (y == 69 && abs(dx) in 2..4 && z == -4) return Material.PURPLE_STAINED_GLASS
            if (y == 70 && abs(dx) == 3 && z == -4) return Material.AMETHYST_BLOCK
            if (y == 69 && dx == 0 && z == -8) return Material.AMETHYST_BLOCK
            return null
        }

        private fun cliffsDecoration(dx: Int, y: Int, z: Int): Material? {
            if (abs(dx) !in 12..15 || z !in -6..6) return null
            val cliffTop = 67 + (abs(dx) * 3 + (z + 12) * 5).mod(6)
            if (y !in 65..cliffTop) return null
            if (y == cliffTop) return Material.MOSS_BLOCK
            if (y == 66 && (dx + z).mod(6) == 0) return Material.SEA_LANTERN
            return if ((y + z).mod(3) == 0) Material.COBBLED_DEEPSLATE else Material.STONE
        }

        private fun volcanoDecoration(dx: Int, y: Int, z: Int): Material? {
            val craterDx = dx + 12
            val craterDz = z + 3
            val radiusSquared = craterDx * craterDx + craterDz * craterDz
            if (radiusSquared in 8..20) {
                val rimTop = 66 + (abs(dx) + abs(z)).mod(3)
                if (y in 65..rimTop) {
                    if (radiusSquared in 13..15 && y == 66) return Material.GLOWSTONE
                    return if (y == rimTop) Material.POLISHED_BASALT else Material.BASALT
                }
            }
            if (radiusSquared in 4..7 && y == 65) return Material.BLACKSTONE
            if (radiusSquared <= 3 && y == 65) return Material.ORANGE_GLAZED_TERRACOTTA
            return null
        }

        private enum class StagePalette(
            val stage: FishingArenaStage,
            val foundation: Material,
            val surface: Material,
            val dock: Material,
            val dockSupport: Material,
            val path: Material,
            val dockRail: Material,
            val stallFrame: Material,
            val stallCounter: Material,
            val awning: Material,
            val awningAccent: Material,
            val exitAccent: Material,
        ) {
            CAMP(
                FishingArenaStage.CAMP, Material.SANDSTONE, Material.SAND, Material.OAK_PLANKS, Material.OAK_LOG,
                Material.CUT_SANDSTONE, Material.OAK_FENCE, Material.OAK_LOG, Material.OAK_PLANKS,
                Material.ORANGE_WOOL, Material.WHITE_WOOL, Material.ORANGE_WOOL,
            ),
            REEF(
                FishingArenaStage.REEF, Material.STONE, Material.PRISMARINE_BRICKS, Material.DARK_OAK_PLANKS, Material.DARK_OAK_LOG,
                Material.PRISMARINE, Material.PRISMARINE_WALL, Material.DARK_OAK_LOG, Material.PRISMARINE_BRICKS,
                Material.CYAN_WOOL, Material.LIGHT_BLUE_WOOL, Material.SEA_LANTERN,
            ),
            SHRINE(
                FishingArenaStage.SHRINE, Material.BASALT, Material.POLISHED_BLACKSTONE, Material.POLISHED_BASALT, Material.BASALT,
                Material.POLISHED_BLACKSTONE_BRICKS, Material.POLISHED_BLACKSTONE_WALL, Material.POLISHED_BLACKSTONE,
                Material.POLISHED_BLACKSTONE_BRICKS, Material.PURPLE_WOOL, Material.BLACK_WOOL, Material.AMETHYST_BLOCK,
            ),
            CLIFFS(
                FishingArenaStage.CLIFFS, Material.COBBLED_DEEPSLATE, Material.MOSSY_STONE_BRICKS,
                Material.SPRUCE_PLANKS, Material.SPRUCE_LOG, Material.MOSSY_STONE_BRICKS,
                Material.STONE_BRICK_WALL, Material.SPRUCE_LOG, Material.SPRUCE_PLANKS,
                Material.LIGHT_BLUE_WOOL, Material.WHITE_WOOL, Material.SEA_LANTERN,
            ),
            VOLCANO(
                FishingArenaStage.VOLCANO, Material.BASALT, Material.BLACKSTONE, Material.POLISHED_BLACKSTONE,
                Material.BASALT, Material.POLISHED_BASALT, Material.BLACKSTONE_WALL, Material.POLISHED_BASALT,
                Material.BLACKSTONE, Material.RED_WOOL, Material.BLACK_WOOL, Material.GLOWSTONE,
            );

            companion object {
                fun forStage(stage: FishingArenaStage): StagePalette = entries.first { it.stage == stage }
            }
        }

        private val FishingArenaStage.palette: StagePalette
            get() = StagePalette.forStage(this)

        private fun StagePalette.surface(x: Int, z: Int): Material = when {
            isPath(x - stage.centerX, z) -> path
            stage == FishingArenaStage.CAMP && (x + z) % 7 == 0 -> Material.CUT_SANDSTONE
            stage == FishingArenaStage.REEF && (x + z) % 5 == 0 -> Material.MOSSY_COBBLESTONE
            stage == FishingArenaStage.SHRINE && (x - z) % 5 == 0 -> Material.BLACKSTONE
            stage == FishingArenaStage.VOLCANO && (x + z).mod(5) == 0 -> Material.POLISHED_BASALT
            else -> surface
        }
    }
}
