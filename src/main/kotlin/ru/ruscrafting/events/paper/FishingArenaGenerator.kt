package ru.ruscrafting.events.paper

import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.World
import org.bukkit.block.Biome
import org.bukkit.block.data.type.Leaves
import org.bukkit.generator.BiomeProvider
import org.bukkit.generator.ChunkGenerator
import org.bukkit.generator.WorldInfo
import java.util.Random
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.sin

enum class FishingArenaStage(
    val centerX: Int,
    val stone: Material,
    val shore: Material,
    val turf: Material,
    val deck: Material,
    val timber: Material,
    val canopy: Material,
    val accent: Material,
) {
    CAMP(0, Material.SANDSTONE, Material.SAND, Material.GRASS_BLOCK, Material.OAK_PLANKS, Material.STRIPPED_SPRUCE_LOG,
        Material.ORANGE_TERRACOTTA, Material.OCHRE_FROGLIGHT),
    REEF(384, Material.PRISMARINE, Material.SAND, Material.MOSS_BLOCK, Material.JUNGLE_PLANKS, Material.STRIPPED_JUNGLE_LOG,
        Material.OXIDIZED_CUT_COPPER, Material.SEA_LANTERN),
    SHRINE(768, Material.TUFF, Material.GRAVEL, Material.MOSS_BLOCK, Material.DARK_OAK_PLANKS, Material.STRIPPED_DARK_OAK_LOG,
        Material.DARK_PRISMARINE, Material.AMETHYST_BLOCK),
    CLIFFS(1152, Material.STONE, Material.GRAVEL, Material.GRASS_BLOCK, Material.SPRUCE_PLANKS, Material.STRIPPED_SPRUCE_LOG,
        Material.WEATHERED_CUT_COPPER, Material.PEARLESCENT_FROGLIGHT),
    VOLCANO(1536, Material.BASALT, Material.BLACKSTONE, Material.SMOOTH_BASALT, Material.CRIMSON_PLANKS, Material.POLISHED_BASALT,
        Material.RED_NETHER_BRICKS, Material.SHROOMLIGHT),
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

/** Five distant harbours in continuous ocean. All structures are generated in their own chunks. */
class FishingArenaGenerator : ChunkGenerator() {
    private val foliage by lazy {
        listOf(Material.SPRUCE_LEAVES, Material.MANGROVE_LEAVES).associateWith {
            (it.createBlockData() as Leaves).apply { isPersistent = true }
        }
    }

    override fun generateSurface(worldInfo: WorldInfo, random: Random, chunkX: Int, chunkZ: Int, chunkData: ChunkData) {
        chunkData.setRegion(0, MIN_Y, 0, 16, MIN_Y + 1, 16, Material.BEDROCK)
        chunkData.setRegion(0, MIN_Y + 1, 0, 16, OCEAN_FLOOR_Y, 16, Material.STONE)
        chunkData.setRegion(0, OCEAN_FLOOR_Y, 0, 16, SEA_Y, 16, Material.GRAVEL)
        chunkData.setRegion(0, SEA_Y, 0, 16, WATER_SURFACE_Y + 1, 16, Material.WATER)
        val x0 = chunkX shl 4
        val z0 = chunkZ shl 4
        if (z0 > 47 || z0 + 15 < -40) return
        val stage = stageNear(x0 + 8) ?: return
        if (x0 - stage.centerX > 40 || x0 + 15 - stage.centerX < -40) return
        for (x in 0..15) for (z in 0..15) {
            val dx = x0 + x - stage.centerX
            val dz = z0 + z
            val top = coastHeight(stage, dx, dz)
            for (y in SEA_Y..MAX_Y) {
                val material = islandBlock(stage, dx, y, dz, top) ?: continue
                if (material == Material.SPRUCE_LEAVES || material == Material.MANGROVE_LEAVES) {
                    chunkData.setBlock(x, y, z, foliage.getValue(material))
                } else chunkData.setBlock(x, y, z, material)
            }
        }
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
        const val TEMPLATE = "fishing-v4"
        const val SEED = 0x415243464953484CL
        const val MIN_Y = 54
        const val MAX_Y = 100
        const val OCEAN_FLOOR_Y = 59
        const val SEA_Y = OCEAN_FLOOR_Y + 1
        const val WATER_SURFACE_Y = 63
        const val WALK_Y = 64
        const val SPAWN_Y = WALK_Y + 1
        const val MIN_FISHING_DEPTH = WATER_SURFACE_Y - SEA_Y + 1
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

        /** Same column rules as generateSurface, exposed for world geometry and route checks. */
        fun materialAt(x: Int, y: Int, z: Int): Material? {
            if (y !in MIN_Y..MAX_Y) return null
            val stage = stageNear(x)
            if (stage != null && abs(x - stage.centerX) <= 40 && z in -40..47 && y >= SEA_Y) {
                val dx = x - stage.centerX
                islandBlock(stage, dx, y, z, coastHeight(stage, dx, z))?.let { return it }
            }
            return when (y) {
                MIN_Y -> Material.BEDROCK
                in (MIN_Y + 1) until OCEAN_FLOOR_Y -> Material.STONE
                OCEAN_FLOOR_Y -> Material.GRAVEL
                in SEA_Y..WATER_SURFACE_Y -> Material.WATER
                else -> null
            }
        }

        private fun coastHeight(stage: FishingArenaStage, x: Int, z: Int): Int {
            if (abs(x) <= 9 && z >= 18) return OCEAN_FLOOR_Y
            // A low landing beside the pier can be climbed from swimming height on every island.
            if (x in 7..9 && z in 10..17) return when (z) {
                in 10..12 -> WALK_Y
                in 13..14 -> WATER_SURFACE_Y - 1
                else -> SEA_Y
            }
            val phase = stage.ordinal * 1.7
            val xx = x + 1.5 * sin(z * 0.21 + phase) + 0.9 * sin(z * 0.57)
            val zz = z + 1.8 * cos(x * 0.17 - phase) + 0.7 * sin(x * 0.43)
            fun mound(cx: Double, cz: Double, rx: Double, rz: Double, height: Double) =
                OCEAN_FLOOR_Y + height * (1.0 - hypot((xx - cx) / rx, (zz - cz) / rz)).coerceAtLeast(0.0)
            val beach = minOf(WALK_Y.toDouble(), mound(0.0, -4.0, 31.0, 24.0, 20.0))
            val rear = mound(-12.0, -16.0, 17.0, 13.0, 13.0 + stage.ordinal)
            val east = mound(18.0, -9.0, 15.0, 15.0, 12.0)
            val west = when (stage) {
                FishingArenaStage.CAMP -> mound(-16.0, 31.0, 12.0, 12.0, 12.0)
                FishingArenaStage.REEF -> mound(-19.0, 21.0, 13.0, 22.0, 8.0)
                FishingArenaStage.SHRINE -> mound(-17.0, 27.0, 13.0, 14.0, 10.0)
                FishingArenaStage.CLIFFS -> mound(-18.0, 25.0, 15.0, 17.0, 10.0)
                FishingArenaStage.VOLCANO -> mound(-22.0, 28.0, 16.0, 19.0, 13.0)
            }
            val serviceQuay = x in -10..12 && z in -11..8
            if (serviceQuay) return WALK_Y
            return floor(maxOf(beach, rear, east, west)).toInt()
        }

        private fun islandBlock(stage: FishingArenaStage, x: Int, y: Int, z: Int, top: Int): Material? {
            val dock = (abs(x) <= 3 && z in 5..17) || (abs(x) <= 5 && z in 9..13)
            if (dock && y == WALK_Y) {
                return if (abs(x) == 3 && (z == 9 || z == 13)) Material.CUT_COPPER else stage.deck
            }
            if (abs(x) == 3 && z in 7..17 && (z - 7) % 5 == 0 && y in SEA_Y..WALK_Y) return stage.timber
            if (abs(x) == 3 && z == 17 && y == SPAWN_Y) return stage.timber
            if (abs(x) == 9 && (z == 19 || z == 35) && y in WATER_SURFACE_Y..66) return when (y) {
                WATER_SURFACE_Y -> Material.STRIPPED_DARK_OAK_LOG
                64 -> Material.RED_TERRACOTTA
                65 -> Material.WHITE_CONCRETE
                else -> Material.LANTERN
            }
            if (stage == FishingArenaStage.CLIFFS && x in -32..-10 && z in 14..37) {
                // The passage follows the diagonal line of sight from the landing through the headland.
                val across = x + 20 + (z - 25) * 0.65
                if (across * across + (y - 65) * (y - 65) < 50) {
                    return if (y <= WATER_SURFACE_Y) Material.WATER else null
                }
            }
            if (y <= top) {
                if (y < top - 2) return stage.stone
                if ((abs(x) <= 1 && z in -11..8 || x in 1..9 && abs(z) <= 1) && y == WALK_Y) return stage.deck
                if (top <= WATER_SURFACE_Y) return stage.shore
                if (top == WALK_Y && y == top && !dock && z in 10..16 && abs(x) in 6..11) {
                    return if (stage == FishingArenaStage.CAMP || stage == FishingArenaStage.REEF)
                        Material.SMOOTH_SANDSTONE_SLAB else Material.STONE_SLAB
                }
                return if (top > WALK_Y + 1 && y == top) stage.turf else if (top <= WALK_Y + 1) stage.shore else stage.stone
            }
            station(stage, x, y, z)?.let { return it }
            return when (stage) {
                FishingArenaStage.CAMP -> shipwreckShore(x, y, z)
                FishingArenaStage.REEF -> coralGardens(x, y, z)
                FishingArenaStage.SHRINE -> tideShrine(x, y, z)
                FishingArenaStage.CLIFFS -> birdCliffs(x, y, z)
                FishingArenaStage.VOLCANO -> caldera(x, y, z)
            } ?: vegetation(stage, x, y, z)
        }

        private fun station(stage: FishingArenaStage, x: Int, y: Int, z: Int): Material? = when {
            x == 8 && z == 0 && y == SPAWN_Y -> Material.BARREL
            x in 9..10 && z == 0 && y == SPAWN_Y -> stage.deck
            x == 11 && z == 0 && y == SPAWN_Y -> Material.SMOKER
            x in 7..12 && (x == 7 || x == 12) && abs(z) == 3 && y in 65..68 -> stage.timber
            x in 6..13 && z in -4..4 && y == 69 + (4 - abs(z)) / 2 ->
                if (x == 6 || x == 13 || z == 0) stage.timber else stage.canopy
            x == 11 && z == 0 && y == 66 -> Material.LANTERN
            x == -2 && z == -9 && y in 65..75 -> stage.timber
            x in -2..2 && z == -9 && y == 73 -> stage.timber
            x in -1..2 && z == -9 && y in 71..72 && (x < 2 || y == 72) ->
                if (x == -1) Material.WHITE_WOOL else Material.CYAN_WOOL
            x == 2 && z == -9 && y in 65..66 -> stage.timber
            x == 2 && z == -9 && y == 67 -> Material.LANTERN
            else -> null
        }

        private fun shipwreckShore(x: Int, y: Int, z: Int): Material? {
            // The white and vermilion daymark is the first landfall seen from the camp.
            val lx = x + 16
            val lz = z - 33
            val radius = maxOf(abs(lx), abs(lz))
            if (radius <= 4 && abs(lx) + abs(lz) <= 6) {
                if (y in 63..69) return Material.STONE_BRICKS
                if (radius <= 3 && abs(lx) + abs(lz) <= 5 && y in 70..75) {
                    return if (y in 72..74) Material.RED_TERRACOTTA else Material.CALCITE
                }
                if (radius <= 2 && abs(lx) + abs(lz) <= 3 && y in 76..79) return Material.RED_TERRACOTTA
                if (radius <= 3 && abs(lx) + abs(lz) <= 5 && y == 80) return Material.DARK_OAK_PLANKS
                if (radius == 2 && abs(lx) + abs(lz) == 3 && y in 81..83) return Material.STRIPPED_DARK_OAK_LOG
                if (radius <= 1 && y in 81..83) return Material.OCHRE_FROGLIGHT
                if (radius <= 3 && abs(lx) + abs(lz) <= 5 && y == 84) return Material.WAXED_CUT_COPPER
                if (radius <= 1 && y == 85) return Material.WAXED_CUT_COPPER
                if (radius == 0 && y == 86) return Material.LIGHTNING_ROD
            }
            // An open bow, splintered ribs, one surviving mast and a torn half-sail.
            val sx = x - 16
            val sz = z - 25
            if (sz !in -7..7 || abs(sx) > 4) return null
            val beam = (4 - (abs(sz) + 1) / 3).coerceAtLeast(1)
            return when {
                abs(sx) < beam && y in 62..64 -> Material.DARK_OAK_PLANKS
                abs(sx) == beam && y in 63..(65 + abs(sz) / 4) -> Material.SPRUCE_PLANKS
                abs(sx) == beam && sz % 3 == 0 && y in 65..67 -> Material.STRIPPED_SPRUCE_LOG
                (sz == 7 || sz == -7) && abs(sx) <= 1 && y in 64..68 -> Material.STRIPPED_SPRUCE_LOG
                sx == 0 && sz == -1 && y in 64..76 -> Material.STRIPPED_SPRUCE_LOG
                sx in -3..3 && sz == -1 && y == 74 -> Material.SPRUCE_PLANKS
                sx in -3..-1 && sz == -1 && y in (69 - sx)..73 -> Material.WHITE_WOOL
                sx in 1..2 && sz == -1 && y in (70 + sx)..73 -> Material.LIGHT_GRAY_WOOL
                else -> null
            }
        }

        /** Branching limestone reef heads. Living coral only occupies submerged cells. */
        private fun coralGardens(x: Int, y: Int, z: Int): Material? {
            fun coral(dx: Int, dz: Int, high: Int, color: Material): Material? {
                val h = y - WATER_SURFACE_Y
                if (h !in -3..high || abs(dx) > 6 || abs(dz) > 5) return null
                val thickness = if (h < 2) 3 else 1
                val trunk = dx * dx + dz * dz <= thickness * thickness
                val branch = h in 1..high - 1 && (
                    abs(dx - (h + 1) / 2) <= 1 && abs(dz) <= 1 ||
                    abs(dx + (h - 1) / 2) <= 1 && abs(dz - h / 3) <= 1)
                val fork = h in 4..high && (abs(dx - (h + 1) / 2) + abs(dz - 2) <= 2 ||
                    abs(dx + h / 2) + abs(dz - h / 3) <= 2)
                if (!trunk && !branch && !fork) return null
                return when {
                    y <= WATER_SURFACE_Y - 1 -> color
                    h < 4 -> Material.PRISMARINE
                    h == high || h == high - 1 -> Material.CALCITE
                    else -> Material.PRISMARINE_BRICKS
                }
            }
            return when {
                x in -26..-12 && z in 20..33 -> coral(x + 19, z - 26, 10, Material.BRAIN_CORAL_BLOCK)
                x in 12..25 && z in 20..32 -> coral(x - 18, z - 26, 7, Material.TUBE_CORAL_BLOCK)
                x in -24..-12 && z in 8..18 -> coral(x + 18, z - 13, 5, Material.FIRE_CORAL_BLOCK)
                abs(x) in 11..18 && z in 33..39 && y in 60..62 && (abs(x) + z) % 4 != 0 ->
                    if (x < 0) Material.BUBBLE_CORAL_BLOCK else Material.HORN_CORAL_BLOCK
                else -> null
            }
        }

        /** The flood-mark gate faces the incoming pier; its ring has a clear opening through to the sea. */
        private fun tideShrine(x: Int, y: Int, z: Int): Material? {
            val dx = x + 17
            val dz = z - 29
            val r = dx * dx + (y - 75) * (y - 75)
            if (abs(dx) <= 6 && abs(dz) <= 2) {
                if (y in 61..66) return Material.DEEPSLATE_BRICKS
                if (abs(dx) >= 4 && y in 67..72) return if (y == 69) Material.AMETHYST_BLOCK else Material.POLISHED_BLACKSTONE_BRICKS
                if (abs(dz) <= 1 && r in 17..43) return if (r <= 26) Material.AMETHYST_BLOCK else Material.DARK_PRISMARINE
                if (abs(dx) <= 1 && abs(dz) <= 1 && y == 82) return Material.SEA_LANTERN
            }
            val plinth = abs(x - 16) + abs(z - 23)
            return when {
                plinth <= 4 && y in 61..65 -> Material.TUFF_BRICKS
                plinth <= 2 && y in 66..70 -> Material.POLISHED_BLACKSTONE_BRICKS
                plinth <= 1 && y in 71..72 -> Material.AMETHYST_BLOCK
                plinth == 0 && y == 73 -> Material.SEA_LANTERN
                else -> null
            }
        }

        /** A hollowed headland and two isolated stacks, with pale ledges under the grassy caps. */
        private fun birdCliffs(x: Int, y: Int, z: Int): Material? {
            val west = x in -32..-10 && z in 14..37
            val east = x in 12..27 && z in 17..38
            if (!west && !east || y < SEA_Y) return null
            val rough = 0.7 * sin(z * 0.77) + sin(x * 0.53 + z * 0.33)
            val d = if (west) hypot((x + 20.0) / 12.0, (z - 25.0) / 9.0)
                else minOf(hypot((x - 16.0) / 4.4, (z - 24.0) / 6.0), hypot((x - 22.0) / 3.8, (z - 32.0) / 4.8))
            val roof = floor((if (west) 81 else 77) - d * d * 9.5 + rough).toInt()
            if (d > 1.03 || y > roof) return null
            return when {
                y == roof -> Material.GRASS_BLOCK
                y == roof - 1 -> Material.DIRT
                y in 68..69 || y in 74..75 -> Material.CALCITE
                y < 65 -> Material.TUFF
                else -> Material.ANDESITE
            }
        }

        /** Broken crater rim and a cooling lava seam. All incandescent surfaces are solid. */
        private fun caldera(x: Int, y: Int, z: Int): Material? {
            val dx = x + 23
            val dz = z - 32
            if (abs(dx) <= 16 && abs(dz) <= 18) {
                val distance = hypot(dx * 1.12, dz.toDouble())
                val rough = sin(dx * 0.63 + dz * 0.31) + 0.75 * cos(dz * 0.85)
                val rim = 81 + rough - 1.27 * abs(distance - 5) - (if (dz < -1 && abs(dx) < 3) 3 else 0)
                if (distance + rough * 0.4 < 15.6 && y in SEA_Y..floor(rim).toInt()) {
                    val seam = abs(dx - sin(dz * 0.48) * 1.7)
                    return when {
                        (distance < 2.6 || dz < -2 && seam < 1.55) && y >= rim - 2.0 -> Material.MAGMA_BLOCK
                        dz < -2 && seam < 2.85 && y >= rim - 2.0 -> Material.RED_TERRACOTTA
                        distance in 3.8..6.0 && y >= rim - 1.0 -> Material.RED_NETHER_BRICKS
                        y in 67..68 || y in 74..75 -> Material.BLACKSTONE
                        else -> Material.SMOOTH_BASALT
                    }
                }
            }
            val vent = abs(x - 16) + abs(z - 20)
            return when {
                vent <= 4 && y in 60..66 -> Material.BLACKSTONE
                vent <= 2 && y in 67..70 -> Material.BASALT
                vent <= 1 && y == 71 -> Material.MAGMA_BLOCK
                else -> null
            }
        }

        private fun vegetation(stage: FishingArenaStage, x: Int, y: Int, z: Int): Material? {
            if (stage == FishingArenaStage.VOLCANO || z !in -23..-4) return null
            val west = x in -21..-10
            val east = x in 14..25
            if (!west && !east) return null
            val cx = if (west) -16 else 19
            val cz = if (west) -17 else -9
            val dx = x - cx
            val dz = z - cz
            val dy = y - coastHeight(stage, cx, cz)
            if (dy !in 1..10) return null
            val reef = stage == FishingArenaStage.REEF
            val lean = if (dy > 5 && stage != FishingArenaStage.SHRINE) 1 else 0
            if (dx == lean && dz == 0 && dy <= 8) return if (reef) Material.MANGROVE_LOG else Material.SPRUCE_LOG
            val crown = if (reef) 5 - abs(dy - 7) else when (dy) { 4, 6 -> 4; 5, 7 -> 3; 8 -> 2; 9, 10 -> 1; else -> 0 }
            if (crown < 1 || abs(dx - lean) + abs(dz) > crown || maxOf(abs(dx - lean), abs(dz)) > crown - 1) return null
            return if (reef) Material.MANGROVE_LEAVES else Material.SPRUCE_LEAVES
        }
    }
}
