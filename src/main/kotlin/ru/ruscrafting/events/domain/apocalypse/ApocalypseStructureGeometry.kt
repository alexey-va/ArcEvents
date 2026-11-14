package ru.ruscrafting.events.domain.apocalypse

import ru.ruscrafting.events.domain.apocalypse.ApocalypseBlock.*
import ru.ruscrafting.events.domain.apocalypse.ApocalypseMarkerKind.*
import kotlin.math.abs

/** Original room geometry. The layouts use CW's surface entrance, deep vertical link, mixed corridors and rooms. */
internal object ApocalypseStructureGeometry {
    private fun depth(site: ApocalypseSite) = when (site.kind) {
        ApocalypseSiteKind.STARTER_BUNKER -> 12
        ApocalypseSiteKind.REACTOR -> 48
        else -> 42
    }

    fun markers(layout: ApocalypseWorldLayout, site: ApocalypseSite): List<ApocalypseMarker> = buildList {
        val a = site.anchor
        val floor = a.y - depth(site)
        fun mark(kind: ApocalypseMarkerKind, x: Int, y: Int, z: Int) { add(ApocalypseMarker(site.id, kind, a.x + x, y, a.z + z)) }
        when (site.kind) {
            ApocalypseSiteKind.CITY -> addAll(ApocalypseStructureCity.markers(layout, site))
            ApocalypseSiteKind.RUIN -> { mark(LOOT, -3, a.y + 1, 1); mark(MEDICAL, 2, a.y + 1, 1) }
            ApocalypseSiteKind.OIL_RIG -> {
                mark(ARMORY, 6, a.y + 1, 4)
                mark(LOOT, 5, a.y + 7, 5)
                mark(SOLDIER_SPAWNER, -6, a.y + 1, 3)
            }
            ApocalypseSiteKind.STARTER_BUNKER -> {
                mark(LOOT, -8, floor + 1, 22)
                mark(MEDICAL, 8, floor + 1, 22)
            }
            else -> {
                mark(LOOT, -18, floor + 1, 16)
                mark(MEDICAL, 18, floor + 1, 14)
                mark(ARMORY, -30, floor + 1, 28)
                mark(POWER, -16, floor + 1, 27)
                mark(SECURITY, 3, floor + 1, 31)
                for (x in -1..1) for (dy in 1..3) mark(FORCEFIELD, x, floor + dy, 31)
                mark(CORE, 3, floor + 1, 42)
                mark(BUNKER_SPAWNER, -7, floor + 1, 21)
                mark(if (site.kind == ApocalypseSiteKind.REACTOR) HAZMAT_SPAWNER else SOLDIER_SPAWNER, 22, floor + 1, 23)
                mark(if (site.kind == ApocalypseSiteKind.REACTOR) MUTATED_SPAWNER else WAREHOUSE_SPAWNER, -35, floor + 1, 23)
            }
        }
    }

    fun draw(layout: ApocalypseWorldLayout, canvas: ApocalypseChunkCanvas, site: ApocalypseSite) {
        val a = site.anchor
        if (!canvas.intersects(a.x - 50, a.z - 20, a.x + 48, a.z + 60)) return
        val room = ApocalypseStructureLocal(canvas, a)
        when (site.kind) {
            ApocalypseSiteKind.STARTER_BUNKER, ApocalypseSiteKind.LAB, ApocalypseSiteKind.REACTOR -> bunker(room, site)
            ApocalypseSiteKind.RUIN -> relay(room, layout.terrain.pick(a.x, a.z, 342, 3))
            ApocalypseSiteKind.OIL_RIG -> rig(room)
            ApocalypseSiteKind.CITY -> Unit
        }
    }

    private fun bunker(b: ApocalypseStructureLocal, site: ApocalypseSite) = with(b) {
        val starter = site.kind == ApocalypseSiteKind.STARTER_BUNKER
        val floor = -depth(site)
        // Packed concrete headworks and a red/ochre air vent mark the low hillside entrance.
        chamber(-4, 0, -8, 4, 5, 12, POLISHED_ANDESITE, DEEPSLATE_BRICKS)
        box(-1, 1, -8, 1, 3, -8, AIR)
        box(-3, 4, -9, 3, 4, -8, YELLOW_TERRACOTTA)
        box(-5, 5, -9, 5, 5, 12, STONE_BRICK_SLAB)
        box(3, 5, 5, 4, 8, 8, RED_TERRACOTTA)
        box(3, 9, 5, 4, 9, 8, SMOOTH_STONE_SLAB)
        box(-6, 0, -14, 6, 0, -9, GRAVEL)
        box(-3, 0, -14, 3, 0, -8, SMOOTH_STONE)
        box(-3, 1, -16, 3, 4, -9, AIR)
        // Stair flights fold across the shaft. Flat landings at either end connect without jumping.
        chamber(-4, floor, -4, 4, 4, 12, STONE_BRICKS, POLISHED_ANDESITE)
        box(-2, 1, -8, 2, 3, -3, AIR)
        box(-3, 0, -8, 2, 0, 2, POLISHED_ANDESITE)
        val flights = depth(site) / 6
        for (n in 0 until flights) {
            val top = -6 * n
            val east = n % 2 == 1
            val x1 = if (east) 0 else -3
            val x2 = if (east) 2 else -1
            for (step in 0..5) {
                val z = if (east) 8 - step else 3 + step
                // A stair faces the high end of its run.
                blockRun(x1, top - step, z, x2, if (east) STAIR_SOUTH else STAIR_NORTH)
                blockRun(x1, top - step - 1, z, x2, POLISHED_ANDESITE)
            }
            val firstZ = if (east) 9 else -2
            val lastZ = if (east) 11 else 2
            box(-3, top, firstZ, 2, top, lastZ, POLISHED_ANDESITE)
            val lowerZ = if (east) -2 else 9
            box(-3, top - 6, lowerZ, 2, top - 6, if (east) 2 else 11, POLISHED_ANDESITE)
            block(3, top - 2, if (east) 9 else 2, OCHRE_FROGLIGHT)
        }
        if (flights % 2 == 0) {
            // Final flight arrives at the north landing; the cleared east aisle leads to the south door.
            box(1, floor, -2, 3, floor, 12, POLISHED_ANDESITE)
            box(2, floor + 1, -2, 3, floor + 3, 12, AIR)
            box(-1, floor + 1, 10, 3, floor + 3, 12, AIR)
        }
        box(-1, floor + 1, 12, 1, floor + 3, 14, AIR)
        if (starter) {
            chamber(-12, floor, 13, 12, floor + 5, 29, STONE_BRICKS, SMOOTH_STONE)
            box(-1, floor + 1, 12, 1, floor + 3, 13, AIR)
            box(-8, floor + 4, 15, -8, floor + 4, 26, YELLOW_CONCRETE)
            box(8, floor + 4, 15, 8, floor + 4, 26, SEA_LANTERN)
            for (z in 16..26 step 5) {
                box(-11, floor + 1, z, -9, floor + 1, z + 1, SPRUCE_PLANKS)
                box(-11, floor + 2, z, -11, floor + 3, z + 1, GRAY_CONCRETE)
                box(9, floor + 1, z, 11, floor + 1, z + 1, SMOOTH_STONE_SLAB)
            }
            // Sleeping alcove and the sick bay flank the starter supplies. Leave the centre as a clear exit axis.
            box(-11, floor + 1, 18, -6, floor + 4, 18, STONE_BRICKS)
            box(-11, floor + 1, 24, -6, floor + 4, 24, STONE_BRICKS)
            box(6, floor + 1, 18, 11, floor + 3, 18, LIGHT_BLUE_STAINED_GLASS)
            box(6, floor + 1, 24, 11, floor + 3, 24, LIGHT_BLUE_STAINED_GLASS)
            for (x in -4..4 step 4) block(x, floor + 4, 28, OCHRE_FROGLIGHT)
            block(0, floor + 2, 28, LODESTONE)
            return@with
        }
        // Blast corridor. The orange service stripe and ceiling lights lead back to the stairs.
        chamber(-3, floor, 13, 3, floor + 5, 31, POLISHED_DEEPSLATE, POLISHED_ANDESITE)
        chamber(-41, floor, 19, 31, floor + 5, 25, DEEPSLATE_BRICKS, SMOOTH_STONE)
        chamber(-41, floor, 11, -11, floor + 7, 32, STONE_BRICKS, POLISHED_ANDESITE)
        chamber(11, floor, 11, 36, floor + 8, 32, if (site.kind == ApocalypseSiteKind.REACTOR) IRON_BLOCK else LIGHT_GRAY_CONCRETE, SMOOTH_STONE)
        chamber(-9, floor, 31, 10, floor + 8, 46, POLISHED_DEEPSLATE, POLISHED_ANDESITE)
        box(-1, floor + 1, 12, 1, floor + 3, 15, AIR)
        box(-2, floor + 1, 18, 2, floor + 4, 26, AIR)
        box(-11, floor + 1, 20, -10, floor + 3, 24, AIR)
        box(10, floor + 1, 20, 11, floor + 3, 24, AIR)
        box(-1, floor + 1, 30, 1, floor + 3, 32, AIR)
        for (z in 15..29 step 4) {
            block(-2, floor + 3, z, OCHRE_FROGLIGHT)
            block(2, floor + 1, z, ORANGE_TERRACOTTA)
        }
        for (x in -36..30 step 7) {
            block(x, floor + 4, 20, SEA_LANTERN)
            for (z in listOf(13, 29)) block(x, floor, z, YELLOW_TERRACOTTA)
        }
        // Storage aisles, an accessible switch, and gun cabinets beyond the field.
        for (x in -38..-21 step 6) for (z in listOf(14, 29)) {
            box(x, floor + 1, z, x + 2, floor + 1, z + 1, DARK_OAK_PLANKS)
            box(x, floor + 3, z, x + 2, floor + 3, z + 1, DARK_OAK_PLANKS)
            box(x, floor + 1, z, x, floor + 3, z, IRON_BARS)
        }
        for (x in listOf(-6, 7)) {
            box(x, floor + 1, 35, x, floor + 3, 43, GRAY_CONCRETE)
            block(x, floor + 4, 39, SEA_LANTERN)
        }
        // A maintenance loop connects the water plant, ammunition stores and a second service tunnel.
        // Nuclear rooms use the same circulation with a grated turbine gallery above the return corridor.
        chamber(-40, floor, 31, -34, floor + 5, 58, DEEPSLATE_BRICKS, POLISHED_ANDESITE)
        chamber(27, floor, 31, 33, floor + 5, 58, STONE_BRICKS, POLISHED_ANDESITE)
        chamber(-40, floor, 53, 33, floor + 5, 59, STONE_BRICKS, POLISHED_ANDESITE)
        chamber(-49, floor, 39, -27, floor + 7, 50, MUD_BRICKS, POLISHED_ANDESITE)
        chamber(19, floor, 39, 42, floor + 7, 51, LIGHT_GRAY_CONCRETE, POLISHED_ANDESITE)
        box(-38, floor + 1, 30, -36, floor + 3, 32, AIR)
        box(29, floor + 1, 30, 31, floor + 3, 32, AIR)
        for (x in listOf(-37, 30)) {
            for (z in listOf(39, 50, 51, 53)) box(x - 1, floor + 1, z, x + 1, floor + 3, z, AIR)
            for (z in listOf(34, 48, 56)) block(x - 2, floor + 3, z, OCHRE_FROGLIGHT)
        }
        for (x in listOf(-46, -32)) {
            box(x, floor + 1, 41, x + 2, floor + 2, 47, WAXED_OXIDIZED_CUT_COPPER)
            box(x, floor + 3, 41, x + 2, floor + 3, 47, COPPER_GRATE)
        }
        box(34, floor + 1, 41, 40, floor + 1, 46, CUT_COPPER)
        box(35, floor + 1, 42, 39, floor + 1, 45, WATER)
        for (x in listOf(-23, -12, 0, 15, 23)) block(x, floor + 4, 54, SEA_LANTERN)
        if (site.kind == ApocalypseSiteKind.REACTOR) {
            for (x in listOf(17, 26)) for (z in listOf(16, 26)) {
                box(x, floor + 1, z, x + 3, floor + 5, z + 2, CUT_COPPER)
                box(x, floor + 2, z, x + 3, floor + 3, z + 2, CYAN_TERRACOTTA)
                box(x, floor + 6, z, x + 3, floor + 6, z + 2, COPPER_GRATE)
            }
        } else {
            for (z in listOf(16, 27)) {
                box(14, floor + 1, z, 29, floor + 1, z, SMOOTH_STONE)
                block(24, floor + 2, z, CAULDRON)
                block(30, floor + 1, z, BLAST_FURNACE)
            }
        }
    }

    private fun relay(b: ApocalypseStructureLocal, variant: Int) = with(b) {
        chamber(-6, 0, -4, 4, 4, 4, MUD_BRICKS, POLISHED_ANDESITE)
        box(-2, 1, -4, -1, 3, -4, AIR)
        box(0, 2, -4, 2, 3, -4, GRAY_STAINED_GLASS)
        box(-6, 4, 1, -4, 5, 4, AIR)
        for (x in listOf(6, 10)) for (z in listOf(0, 4)) box(x, 0, z, x, 8, z, POLISHED_ANDESITE)
        for (dy in 0..8) {
            val k = dy % 5
            block(6 + k, dy + 1, 0, IRON_BARS_EW)
            block(10, dy + 1, k, IRON_BARS_NS)
        }
        box(6, 8, 0, 10, 8, 4, COPPER_GRATE)
        box(8, 9, 2, 8, 14 + variant, 2, IRON_BARS)
        block(8, 15 + variant, 2, REDSTONE_BLOCK)
    }

    private fun rig(b: ApocalypseStructureLocal) = with(b) {
        for (x in listOf(-10, 10)) for (z in listOf(-8, 8)) {
            box(x, -58, z, x + 1, -1, z + 1, DEEPSLATE_BRICKS)
            box(x, -5, z, x + 1, -4, z + 1, ORANGE_TERRACOTTA)
        }
        box(-12, -1, -10, 12, 0, 10, POLISHED_DEEPSLATE)
        for (z in -10..-3) {
            val y = z + 3
            blockRun(-3, y, z - 6, 0, STAIR_SOUTH)
        }
        chamber(2, 0, 0, 10, 5, 8, LIGHT_GRAY_CONCRETE, POLISHED_ANDESITE)
        box(3, 1, 0, 4, 3, 0, AIR)
        box(6, 2, 0, 9, 3, 0, LIGHT_BLUE_STAINED_GLASS)
        box(1, 5, -1, 11, 5, 9, YELLOW_TERRACOTTA)
        for (i in 0..5) block(1, i, i, if (i == 0) SMOOTH_STONE else STAIR_SOUTH)
        box(1, 5, 5, 2, 5, 6, GRAY_CONCRETE)
        for (dy in 1..19) {
            val width = if (dy < 8) 3 else if (dy < 15) 2 else 1
            for (dx in listOf(-width, width)) for (dz in listOf(-width, width)) block(-6 + dx, dy, -3 + dz, EXPOSED_CUT_COPPER)
            if (dy % 4 == 0) box(-6 - width, dy, -3 - width, -6 + width, dy, -3 + width, COPPER_GRATE)
        }
        box(-7, 20, -4, -5, 20, -2, YELLOW_CONCRETE)
        block(-6, 21, -3, SEA_LANTERN)
    }
}

internal class ApocalypseStructureLocal(private val canvas: ApocalypseChunkCanvas, private val a: ApocalypsePoint) {
    fun box(x: Int, y: Int, z: Int, x2: Int, y2: Int, z2: Int, block: ApocalypseBlock) =
        canvas.box(a.x + x, a.y + y, a.z + z, a.x + x2, a.y + y2, a.z + z2, block)
    fun block(x: Int, y: Int, z: Int, block: ApocalypseBlock) = box(x, y, z, x, y, z, block)
    fun blockRun(x: Int, y: Int, z: Int, x2: Int, block: ApocalypseBlock) = box(x, y, z, x2, y, z, block)
    fun chamber(x: Int, y: Int, z: Int, x2: Int, y2: Int, z2: Int, wall: ApocalypseBlock, floor: ApocalypseBlock) {
        box(x, y, z, x2, y2, z2, wall)
        box(x, y, z, x2, y, z2, floor)
        box(x + 1, y + 1, z + 1, x2 - 1, y2 - 1, z2 - 1, AIR)
    }
}
