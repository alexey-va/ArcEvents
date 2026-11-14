package ru.ruscrafting.events.domain.apocalypse

import ru.ruscrafting.events.domain.apocalypse.ApocalypseBlock.*
import ru.ruscrafting.events.domain.apocalypse.ApocalypseMarkerKind.*
import kotlin.math.abs

/** Original parcel interiors, using Lost Cities' 16-block lattice and CW custom2 building statistics. */
internal object ApocalypseStructureCity {
    private data class Lot(val x: Int, val z: Int, val floors: Int, val cellars: Int, val style: Int, val breakAt: Int)

    private fun lot(layout: ApocalypseWorldLayout, cx: Int, cz: Int, factor: Double): Lot {
        val t = layout.terrain
        val canBuild = Math.floorMod(cx, 5) != 0 && Math.floorMod(cz, 5) != 0 && t.pick(cx, cz, 301, 100) < 30
        val floorRoll = (4 + (factor + 0.1) * 2).toInt().coerceIn(1, 8)
        return Lot(cx * 16, cz * 16, if (canBuild) 1 + t.pick(cx, cz, 302, floorRoll) else 0,
            t.pick(cx, cz, 303, 4), t.pick(cx, cz, 304, 4), t.pick(cx, cz, 305, 7))
    }

    private fun owner(sites: List<ApocalypseSite>, x: Int, z: Int) = sites.asSequence()
        .filter { it.kind == ApocalypseSiteKind.CITY }
        .minByOrNull { (it.anchor.x - x).toLong() * (it.anchor.x - x) + (it.anchor.z - z).toLong() * (it.anchor.z - z) }

    fun markers(layout: ApocalypseWorldLayout, site: ApocalypseSite): List<ApocalypseMarker> {
        val a = site.anchor
        val r = layout.cityRadius(site)
        val sites = layout.environmentsFor(a.x - r, a.z - r, a.x + r, a.z + r)
        return buildList {
            for (cz in Math.floorDiv(a.z - r, 16)..Math.floorDiv(a.z + r, 16)) {
                for (cx in Math.floorDiv(a.x - r, 16)..Math.floorDiv(a.x + r, 16)) {
                    if (owner(sites, cx * 16, cz * 16) == site) addAll(markersInChunk(layout, sites, cx, cz))
                }
            }
        }
    }

    fun markersInChunk(layout: ApocalypseWorldLayout, sites: List<ApocalypseSite>, cx: Int, cz: Int): List<ApocalypseMarker> {
        val x = cx * 16
        val z = cz * 16
        if (!layout.developed(sites, x, z) || substationHere(sites, x, z)) return emptyList()
        val site = owner(sites, x, z) ?: return emptyList()
        val lot = lot(layout, cx, cz, layout.cityFactor(sites, x, z))
        if (lot.floors == 0) return if (carHere(layout, cx, cz)) {
            listOf(ApocalypseMarker(site.id, LOOT, x + 11, 72, z + 8))
        } else emptyList()
        return buildList {
            for (n in -lot.cellars until lot.floors) {
                val kind = if (n < 0 && n == -lot.cellars) ARMORY else if (lot.style == 2) MEDICAL else LOOT
                add(ApocalypseMarker(site.id, kind, x + 11, 72 + n * 6, z + 9))
            }
        }
    }

    private fun substationHere(sites: List<ApocalypseSite>, x: Int, z: Int) = sites.any {
        it.kind != ApocalypseSiteKind.CITY && abs(it.anchor.x - x - 8) <= 27 && abs(it.anchor.z - z - 8) <= 27
    }

    fun draw(layout: ApocalypseWorldLayout, c: ApocalypseChunkCanvas, sites: List<ApocalypseSite>) {
        val cx = c.chunkX
        val cz = c.chunkZ
        val x = c.minX
        val z = c.minZ
        if (!layout.developed(sites, x, z) || substationHere(sites, x, z)) return
        val b = ApocalypseStructureLocal(c, ApocalypsePoint(x, 71, z))
        val lot = lot(layout, cx, cz, layout.cityFactor(sites, x, z))
        if (lot.floors > 0) building(b, lot) else street(layout, b, cx, cz)
        for (m in markersInChunk(layout, sites, cx, cz)) c.block(m.x, m.y, m.z, m.kind.block)
    }

    private val facades = listOf(BRICKS, LIGHT_GRAY_TERRACOTTA, WHITE_CONCRETE, CYAN_TERRACOTTA)
    private fun building(b: ApocalypseStructureLocal, lot: Lot) = with(b) {
        val wall = facades[lot.style]
        box(0, -1, 0, 15, 0, 15, POLISHED_ANDESITE)
        for (n in -lot.cellars until lot.floors) {
            val y = n * 6
            chamber(1, y, 1, 14, y + 6, 14, if (n < 0) STONE_BRICKS else wall, if (n < 0) POLISHED_DEEPSLATE else SMOOTH_STONE)
            if (n >= 0) {
                // Paired window bays framed by continuous pilasters and projecting floor belts.
                for (p in listOf(3, 7, 11)) {
                    for (edge in listOf(1, 14)) {
                        box(p, y + 2, edge, p + 1, y + 4, edge, GRAY_STAINED_GLASS)
                        box(edge, y + 2, p, edge, y + 4, p + 1, if (lot.style % 2 == 0) LIGHT_BLUE_STAINED_GLASS else GLASS)
                    }
                }
                box(1, y + 5, 1, 14, y + 5, 14, POLISHED_ANDESITE)
                box(2, y + 5, 2, 13, y + 5, 13, AIR)
                if (n == lot.floors - 1 || (n + lot.breakAt) % 3 == 0) {
                    val breach = 3 + lot.breakAt
                    box(breach, y + 3, 14, breach + 2, y + 5, 14, AIR)
                    block(breach, y + 2, 14, CRACKED_STONE_BRICKS)
                    block(breach + 1, y + 2, 13, IRON_BARS_EW)
                }
            }
            // Utility spine, reception and independent rooms leave a clear circulation loop on every floor.
            for (z in 3..9) if (z !in 6..7) box(8, y + 1, z, 8, y + 4, z, if (n < 0) DEEPSLATE_BRICKS else LIGHT_GRAY_CONCRETE)
            box(9, y + 1, 12, 12, y + 1, 12, if (lot.style == 2) SMOOTH_STONE else SPRUCE_PLANKS)
            block(12, y + 2, 12, if (n < 0) BLAST_FURNACE else if (lot.style == 2) CAULDRON else BOOKSHELF)
            if (n == 0) {
                box(6, 1, 1, 7, 3, 1, AIR)
                box(5, 4, 0, 8, 4, 1, if (lot.style % 2 == 0) EXPOSED_CUT_COPPER else YELLOW_TERRACOTTA)
                box(14, 1, 7, 14, 3, 8, AIR)
            }
        }
        // Spalled facade, exposed reinforcement, and a single overgrown seam form continuous weathering.
        val diagonal = lot.breakAt % 3
        for (n in 0 until lot.floors) {
            val y = n * 6
            val x = 3 + (lot.breakAt + n) % 8
            block(x, y + 5, 14, CRACKED_STONE_BRICKS)
            if (n == lot.floors - 1 || lot.style == 3 && n % 2 == 0) {
                box(14, y + 3, 6 + diagonal, 14, y + 5, 8 + diagonal, AIR)
                block(14, y + 2, 7 + diagonal, IRON_BARS_NS)
                block(13, y + 1, 7 + diagonal, COBWEB)
            }
            if (n == 0) {
                box(1, 1, 9, 2, 2, 11, MOSSY_STONE_BRICKS)
                block(1, 3, 11, OAK_LEAVES)
                box(12, 1, 14, 13, 1, 14, MOSSY_COBBLESTONE)
            }
            if (lot.style == 1 && n > 0) {
                box(9, y, 14, 13, y, 15, EXPOSED_CUT_COPPER)
                blockRun(9, y + 1, 15, 13, IRON_BARS_EW)
                box(10, y + 1, 14, 11, y + 3, 14, AIR)
            }
            if (lot.style == 2) {
                box(1, y + 1, 10, 1, y + 4, 12, LIGHT_BLUE_STAINED_GLASS)
                box(2, y + 1, 14, 2, y + 4, 14, ORANGE_TERRACOTTA)
            }
        }
        val top = lot.floors * 6
        box(3, top + 1, 1, 14, top + 1, 1, STONE_BRICK_SLAB)
        box(1, top + 1, 1, 1, top + 1, 13, STONE_BRICK_SLAB)
        chamber(9, top, 3, 13, top + 3, 7, GRAY_CONCRETE, POLISHED_ANDESITE)
        box(9, top + 1, 3, 9, top + 2, 5, COPPER_GRATE)
        if (lot.style == 1 || lot.style == 3) {
            box(11, top + 4, 5, 11, top + 8, 5, IRON_BARS)
            block(11, top + 9, 5, RED_TERRACOTTA)
        }
        // Open stairs are applied after all floor plates, so adjacent stories cannot re-seal the stairwell.
        for (n in -lot.cellars until lot.floors) {
            val y = n * 6
            val south = Math.floorMod(n, 2) == 0
            // Consecutive flights occupy separate lanes, with a shared three-block-deep turn on each landing.
            val left = if (south) 2 else 5
            for (k in 0..5) {
                val z = if (south) 5 + k else 10 - k
                box(left, y + 1, z, left + 1, y + 6, z, AIR)
                blockRun(left, y + k + 1, z, left + 1, if (south) STAIR_SOUTH else STAIR_NORTH)
            }
            val landing = if (south) 11 else 4
            box(2, y + 6, landing, 6, y + 6, landing, SMOOTH_STONE)
        }
    }

    private fun carHere(layout: ApocalypseWorldLayout, cx: Int, cz: Int) =
        Math.floorMod(cz, 5) == 0 && layout.terrain.pick(cx, cz, 311, 4) == 0

    private fun street(layout: ApocalypseWorldLayout, b: ApocalypseStructureLocal, cx: Int, cz: Int) = with(b) {
        val avenueX = Math.floorMod(cx, 5) == 0
        val avenueZ = Math.floorMod(cz, 5) == 0
        val park = !avenueX && !avenueZ && layout.terrain.pick(cx, cz, 313, 5) == 0
        box(0, -1, 0, 15, 0, 15, if (park) GRASS_BLOCK else POLISHED_ANDESITE)
        if (park) {
            box(0, 0, 7, 15, 0, 8, GRAVEL)
            box(7, 0, 0, 8, 0, 15, GRAVEL)
            box(11, 1, 2, 11, 5, 2, OAK_LOG)
            box(9, 5, 0, 13, 7, 4, OAK_LEAVES)
            box(2, 1, 4, 4, 1, 4, DARK_OAK_PLANKS)
        } else {
            // Edges follow the same global lattice in adjacent chunks, including junction markings.
            box(0, 0, 4, 15, 0, 11, GRAY_CONCRETE_POWDER)
            box(4, 0, 0, 11, 0, 15, GRAY_CONCRETE_POWDER)
            if (avenueZ) {
                box(0, 0, 7, 3, 0, 7, YELLOW_TERRACOTTA)
                box(12, 0, 7, 15, 0, 7, YELLOW_TERRACOTTA)
            }
            if (avenueX) box(7, 0, 1, 7, 0, 3, LIGHT_GRAY_CONCRETE)
            if (carHere(layout, cx, cz)) {
                for (x in listOf(9, 13)) for (z in listOf(7, 9)) block(x, 1, z, BLACK_CONCRETE)
                box(9, 2, 7, 13, 2, 9, if ((cx and 1) == 0) RED_TERRACOTTA else WAXED_OXIDIZED_CUT_COPPER)
                box(10, 3, 7, 11, 3, 9, GRAY_STAINED_GLASS)
                block(9, 2, 7, OCHRE_FROGLIGHT)
                // The trunk opens from above.
                block(11, 2, 8, AIR)
                block(11, 3, 8, AIR)
            } else if (layout.terrain.pick(cx, cz, 314, 6) == 0) {
                box(2, 1, 2, 2, 5, 2, POLISHED_DEEPSLATE)
                box(2, 5, 2, 5, 5, 2, IRON_BARS_EW)
                block(5, 4, 2, OCHRE_FROGLIGHT)
            }
            if (!avenueX && !avenueZ) {
                // Broken surfaces recolonize in patches; keep the centre of the crossroads walkable.
                box(1, 0, 11, 3, 0, 14, COARSE_DIRT)
                box(1, 0, 12, 2, 0, 13, MOSS_BLOCK)
                block(2, 1, 13, FERN)
                block(3, 1, 12, STONE_BRICK_SLAB)
                if (layout.terrain.pick(cx, cz, 316, 3) == 0) {
                    box(11, 0, 3, 13, 0, 4, CRACKED_STONE_BRICKS)
                    box(12, 0, 2, 14, 0, 3, COARSE_DIRT)
                    block(13, 1, 3, SHORT_GRASS)
                    block(12, 1, 4, IRON_BARS_EW)
                }
            }
        }
        // Transport corridors below two out of every eight city streets.
        if (Math.floorMod(cx, 8) == 0 || Math.floorMod(cz, 8) == 0) {
            if (Math.floorMod(cx, 8) == 0) {
                box(5, -8, 0, 10, -3, 15, STONE_BRICKS)
                box(6, -7, 0, 9, -4, 15, AIR)
                box(7, -8, 0, 7, -8, 15, POLISHED_DEEPSLATE)
            }
            if (Math.floorMod(cz, 8) == 0) {
                box(0, -8, 5, 15, -3, 10, STONE_BRICKS)
                box(0, -7, 6, 15, -4, 9, AIR)
                box(0, -8, 7, 15, -8, 7, POLISHED_DEEPSLATE)
            }
            if (!park && Math.floorMod(cx, 8) == 0 && Math.floorMod(cz, 4) == 0) {
                // Open access from a sidewalk down to the tunnel.
                for (n in 0..7) {
                    box(6, -n, n, 8, 2, n, AIR)
                    blockRun(6, -n, n, 8, STAIR_NORTH)
                }
            }
        }
    }
}
