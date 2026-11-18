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

/** Sector Zero switchyard. The reactor and four distinct industrial frontages orient the six ingress lanes. */
class ZombiesArenaGenerator : ChunkGenerator() {
    override fun generateSurface(worldInfo: WorldInfo, random: Random, chunkX: Int, chunkZ: Int, chunkData: ChunkData) {
        for (x in 0..15) for (z in 0..15) for (y in 60..MAX_Y) {
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
        const val TEMPLATE = "zombies-zero-v3"
        const val MAX_Y = 89
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

        private val supplySymbols = mapOf(
            ZombiesOffer.AMMO to listOf("..#..", ".###.", ".###.", ".###.", "..#.."),
            ZombiesOffer.MYSTERY to listOf(".###.", "...#.", "..##.", ".....", "..#.."),
            ZombiesOffer.POWER to listOf("...#.", "..##.", ".###.", ".#...", "#...."),
            ZombiesOffer.VITALITY to listOf("..#..", "..#..", "#####", "..#..", "..#.."),
        )

        private fun perimeter(x: Int, y: Int, z: Int): Material? {
            val along = if (abs(z) == 28) x else z
            val roof = when {
                z == -28 -> if (abs(x) <= 17) 77 else 75
                z == 28 -> 81 - (abs(x) / 3).coerceAtMost(7)
                x < 0 -> 75 + Math.floorMod(z + 25, 10) / 3
                else -> if (z in 12..24) 80 else 74
            }
            if (y > roof) return null
            return when {
                z == 28 && abs(x) <= 5 && y <= 72 -> when {
                    abs(x) == 5 || y == 72 -> Material.WAXED_EXPOSED_CUT_COPPER
                    y == 65 || y % 3 == 0 -> Material.POLISHED_DEEPSLATE
                    else -> Material.LIGHT_GRAY_CONCRETE
                }
                y == 65 || y == roof || Math.floorMod(along + 3, 9) == 0 -> Material.DEEPSLATE_BRICKS
                z == 28 && abs(x) in 6..19 && y in 71..74 -> if (along % 4 == 0) Material.TUFF_BRICKS else Material.YELLOW_TERRACOTTA
                x == -28 && z !in -27..-24 -> when {
                    y in 70..72 && along % 3 != 0 -> Material.GRAY_STAINED_GLASS
                    y == 69 || y == 73 -> Material.POLISHED_GRANITE
                    else -> Material.BRICKS
                }
                x == 28 -> when {
                    y in 69..70 && Math.floorMod(along, 6) in 1..3 -> Material.OCHRE_FROGLIGHT
                    y == 68 || y == 71 -> Material.CYAN_TERRACOTTA
                    else -> Material.TUFF_BRICKS
                }
                z == -28 && y in 71..74 -> Material.POLISHED_TUFF
                y == 68 -> Material.RED_TERRACOTTA
                else -> Material.STONE_BRICKS
            }
        }

        /** Solid machine bases stay four blocks high; the elevated fittings never form a player-only step. */
        private fun machinery(x: Int, y: Int, z: Int): Material? {
            val dx = abs(x) - 15
            val dz = abs(z) - 9
            if (abs(dx) > 4 || abs(dz) > 3 || abs(dx) + abs(dz) > 6) return null
            if (x < 0) return when {
                y == 65 || y == 68 -> Material.POLISHED_DEEPSLATE
                y <= 67 && (dx == -3 || dx == 2) -> Material.WAXED_OXIDIZED_CUT_COPPER
                y <= 67 && abs(dx) == 4 -> Material.CHISELED_TUFF
                y <= 67 -> if (z < 0) Material.WAXED_CUT_COPPER else Material.RED_TERRACOTTA
                y == 69 && dx == -3 && abs(dz) <= 1 -> Material.WAXED_OXIDIZED_COPPER_GRATE
                else -> null
            }
            return when {
                y == 65 || y == 68 -> Material.TUFF_BRICKS
                y <= 67 && abs(dz) == 3 && dx % 2 == 0 -> Material.POLISHED_DEEPSLATE
                y <= 67 -> if (z < 0) Material.WAXED_WEATHERED_CUT_COPPER else Material.LIGHT_GRAY_CONCRETE
                y in 69..71 && abs(dx) == 2 && abs(dz) == 1 -> if (y == 70) Material.BLACKSTONE_WALL else Material.QUARTZ_PILLAR
                else -> null
            }
        }

        private fun chimney(dx: Int, y: Int, dz: Int, top: Int): Material? {
            val wide = if (y <= 71) 2 else 1
            if (y > top || max(abs(dx), abs(dz)) > wide || wide == 2 && abs(dx) + abs(dz) == 4) return null
            return when {
                y == top -> Material.POLISHED_BLACKSTONE
                y == 71 || y == 75 || y == top - 4 -> Material.LIGHT_GRAY_TERRACOTTA
                y <= 70 -> Material.DEEPSLATE_BRICKS
                else -> Material.BRICKS
            }
        }

        fun materialAt(x: Int, y: Int, z: Int): Material? {
            if (abs(x) > 28 || abs(z) > 28 || y !in 60..MAX_Y) return null
            // Flush lamps illuminate both shoulders of the circuit during the arena's fixed midnight.
            if (y in 63..64) {
                val ax = abs(x)
                val az = abs(z)
                val lamp = ax == 7 && (az in 2..3 || az in 11..12) ||
                    az == 16 && ax in 3..4 || az == 1 && ax in 21..22
                if (lamp) return if (y == 64) Material.WAXED_OXIDIZED_COPPER_GRATE else Material.OCHRE_FROGLIGHT
            }
            if (y < 64) return Material.DEEPSLATE
            val radius = max(abs(x), abs(z))
            if (y == 64) return when {
                // A continuous buff-coloured ring and two station crossings stay legible in motion.
                radius == 6 -> if (Math.floorMod(x + z, 4) < 2) Material.YELLOW_CONCRETE else Material.BLACK_CONCRETE
                (abs(x) == 8 && abs(z) <= 17 || abs(z) == 17 && abs(x) <= 8) -> Material.SMOOTH_SANDSTONE
                abs(z + 20) <= 1 && abs(x) <= 17 -> if (z == -21 && abs(x) % 8 in 1..3) Material.YELLOW_TERRACOTTA else Material.SMOOTH_STONE
                abs(x) in 6..9 && abs(z) <= 17 || abs(z) in 14..17 && abs(x) <= 8 || abs(z) <= 2 -> Material.POLISHED_ANDESITE
                abs(x) == 25 || abs(z) == 25 -> Material.CUT_COPPER
                abs(x) <= 5 || abs(z) in 15..23 -> Material.GRAY_CONCRETE
                x < 0 -> Material.TUFF_BRICKS
                else -> Material.POLISHED_TUFF
            }
            if (radius == 28) return perimeter(x, y, z)
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
            // Octagonal pressure vessel: banded glass, a lit inner column and a flanged exhaust crown.
            if (radius <= 4 && abs(x) + abs(z) <= 6 && y <= 76) {
                val shell = radius == 4 || abs(x) + abs(z) == 6
                return when {
                    y == 65 || y == 74 -> Material.POLISHED_DEEPSLATE
                    y >= 75 -> if (radius <= 3) Material.WAXED_EXPOSED_CUT_COPPER else null
                    radius <= 1 -> Material.SEA_LANTERN
                    !shell -> null
                    y == 66 || y == 72 || abs(x) + abs(z) == 6 -> Material.WAXED_OXIDIZED_CUT_COPPER
                    else -> Material.CYAN_STAINED_GLASS
                }
            }
            if (radius <= 2 && y in 77..81 && abs(x) + abs(z) <= 3) return when (y) {
                77, 80 -> Material.POLISHED_DEEPSLATE
                81 -> Material.WAXED_OXIDIZED_COPPER_GRATE
                else -> if (radius == 2) Material.WAXED_CUT_COPPER else Material.SEA_LANTERN
            }
            // The valve gallery crosses above head height; its diagonal braces leave sky between them.
            if (abs(z) <= 1 && y in 74..78 && abs(x) in 4..27) {
                val bay = Math.floorMod(x, 6)
                val brace = if (Math.floorMod(x, 12) < 6) bay else 5 - bay
                if (y == 74 || y == 78 || abs(z) == 1 && (bay == 0 || brace == y - 74)) {
                    return if (y == 74 && bay == 3 && z == 0) Material.OCHRE_FROGLIGHT
                        else if (y == 78) Material.POLISHED_DEEPSLATE else Material.WAXED_EXPOSED_CUT_COPPER
                }
            }
            machinery(x, y, z)?.let { return it }

            // The brick boiler house and elevated enamel switch room break the skyline on opposite sides.
            chimney(x + 24, y, z - 17, 89)?.let { return it }
            chimney(x + 21, y, z - 23, 84)?.let { return it }
            if (x in -27..-23 && z in -24..13 && y >= 69) {
                val bay = Math.floorMod(z + 25, 10)
                val roof = 75 + bay / 3
                if (y == roof) return Material.DEEPSLATE_TILES
                if (x == -23 && y < roof) return when {
                    bay == 0 || y <= 70 -> Material.BRICKS
                    y == 71 -> Material.POLISHED_GRANITE
                    else -> Material.GRAY_STAINED_GLASS
                }
                if (x == -25 && bay in 3..5 && y == 72) return Material.OCHRE_FROGLIGHT
            }
            if (x in 20..27 && z in 15..24) {
                val edge = x == 20 || x == 27 || z == 15 || z == 24
                val corner = (x == 20 || x == 27) && (z == 15 || z == 24)
                return when {
                    y in 65..70 && corner -> Material.TUFF_BRICKS
                    y == 71 || y == 80 -> Material.WAXED_OXIDIZED_CUT_COPPER
                    y in 72..79 && edge -> when {
                        corner || y == 76 -> Material.POLISHED_ANDESITE
                        y in 74..75 -> if ((x + z) % 4 == 0) Material.CYAN_TERRACOTTA else Material.LIGHT_BLUE_STAINED_GLASS
                        else -> Material.SMOOTH_STONE
                    }
                    y == 74 && x == 23 && z in 17..21 -> Material.OCHRE_FROGLIGHT
                    y in 81..84 && x == 25 && z == 22 -> if (y == 84) Material.OCHRE_FROGLIGHT else Material.POLISHED_BLACKSTONE_WALL
                    else -> null
                }
            }
            // Portal frames support the elevated gallery with a clear passage underneath each end.
            if (abs(x) == 26 && abs(z) == 2 && y <= 78) return Material.CHISELED_TUFF
            if (abs(x) >= 24 && abs(z) <= 2 && y == 78) return Material.POLISHED_DEEPSLATE

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
                if (z == -22 && y in 70..74) {
                    val mark = supplySymbols.getValue(station.key)[74 - y][x - station.value + 2] == '#'
                    return if (mark && y == 72) Material.OCHRE_FROGLIGHT else if (mark) Material.SMOOTH_QUARTZ else when (station.key) {
                        ZombiesOffer.AMMO -> Material.GREEN_TERRACOTTA
                        ZombiesOffer.MYSTERY -> Material.PURPLE_TERRACOTTA
                        ZombiesOffer.POWER -> Material.BLUE_TERRACOTTA
                        ZombiesOffer.VITALITY -> Material.RED_TERRACOTTA
                    }
                }
                if (z == -23 && abs(x - station.value) == 2 && y in 70..74) return Material.CHISELED_TUFF
                if (y == 75 || z == -24 && abs(x - station.value) == 2 && y <= 74) return Material.DEEPSLATE_BRICKS
            }
            // North concourse ties the four supply canopies into the headhouse.
            if (z in -27..-24 && abs(x) <= 17 && y == 75) return Material.POLISHED_DEEPSLATE
            if (z in 25..27 && abs(x) <= 18 && y == 76 - abs(x) / 8) return Material.WAXED_EXPOSED_CUT_COPPER
            // Tall light pylons mark the entries without narrowing the circulation loop.
            if (abs(x) == 23 && abs(z) == 19 && y <= 72) return if (y == 72) Material.SEA_LANTERN else Material.POLISHED_DEEPSLATE
            return null
        }
    }
}
