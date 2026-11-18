package ru.ruscrafting.events.domain.apocalypse

import ru.ruscrafting.events.domain.apocalypse.source.ApocalypseLostCityAssets
import ru.ruscrafting.events.domain.apocalypse.source.ApocalypseSourcePalette
import kotlin.math.roundToInt

/** One of the pack's radio masts overlooks a pier, an access bridge and three Lost Cities frontages. */
internal class ApocalypseWaterfront(
    seed: Long,
    city: ApocalypseLostCityAssets,
    land: (Int, Int) -> Int,
    original: (ApocalypseChunkCanvas) -> Unit,
) {
    private val groundY = maxOf(64, (-128..-81 step 4).maxOf { x -> (-16..31 step 4).maxOf { z -> land(x, z) } })
    val site = ApocalypseSite("city.waterfront", ApocalypseSiteKind.CITY, ApocalypsePoint(-100, groundY + 1, 6))
    val vent = ApocalypsePoint(-85, groundY + 4, -5)
    val lots: Map<Pair<Int, Int>, List<ApocalypseSceneryCell>>
    val loot: Map<Pair<Int, Int>, List<ApocalypseMarker>>
    val fittedTorches = mutableSetOf<ApocalypsePoint>()

    init {
        val blocks = mutableMapOf<Pair<Int, Int>, MutableMap<ApocalypsePoint, String>>()
        fun put(x: Int, y: Int, z: Int, state: String, fitTorch: Boolean = false) {
            val p = ApocalypsePoint(x, y, z)
            blocks.getOrPut(x shr 4 to (z shr 4)) { linkedMapOf() }[p] = state
            if (fitTorch) fittedTorches.add(p) else fittedTorches.remove(p)
        }
        fun material(x: Int, y: Int, z: Int, block: String) = put(x, y, z, "minecraft:$block")
        fun pier(x: Int, z: Int, top: Int) {
            for (y in land(x, z) + 1..top) material(x, y, z, "polished_basalt")
        }
        fun lamp(x: Int, y: Int, z: Int) {
            material(x, y, z, "deepslate_tile_wall[up=true]")
            material(x, y + 1, z, "exposed_copper_bulb[lit=true]")
        }

        for (x in -128..-81) for (z in -16..31) {
            val edge = x == -128 || x == -81 || z == -16 || z == 31
            material(x, groundY - 1, z, if (edge) "polished_deepslate" else "tuff_bricks")
            val street = z in 3..9
            material(x, groundY, z, when {
                z == 6 && Math.floorMod(x, 10) in 2..5 -> "yellow_terracotta"
                street -> "gray_concrete"
                edge -> "cut_copper"
                else -> "polished_andesite"
            })
            if (Math.floorMod(x, 8) in 0..1 && Math.floorMod(z, 8) in 0..1 || edge && x % 8 == 0 ||
                (x == -81 || x == -128) && z % 8 == 0) pier(x, z, groundY - 2)
        }

        val markers = mutableMapOf<Pair<Int, Int>, List<ApocalypseMarker>>()
        fun frontage(cx: Int, cz: Int, name: String, floors: Int) {
            city.renderBuildingCells(name, seed, cx, cz, groundY, floors, 0) { x, y, z, state, fitTorch ->
                ApocalypseSourcePalette.vanillaState(state)?.let { put(x, y, z, it, fitTorch) }
            }
            markers[cx to cz] = city.buildingMarkers(name, seed, cx, cz, groundY, floors, site.id)
        }
        frontage(-8, -1, "lostcities:building4", 5)
        frontage(-7, -1, "lostcities:building2", 3)
        frontage(-6, 1, "lostcities:building3", 2)
        val tower = city.part("lostcities:radiotower", seed)
        tower.forEachBlock { x, y, z, source, _ ->
            // Lost Cities' bare iron bars have no connected faces in a raw Paper chunk. Keep the source
            // lattice coordinates and its fine upper antenna; riveted grating gives the long girders a silhouette.
            val mapped = ApocalypseSourcePalette.vanillaState(source) ?: return@forEachBlock
            val native = if (mapped == "minecraft:iron_bars" && y < 91) {
                if (y % 24 in 17..19) "minecraft:waxed_cut_copper" else "minecraft:waxed_exposed_copper_grate"
            } else mapped
            put(-128 + x, groundY + y, 16 + z, native)
        }
        // A pair of riveted pressure vessels occupies the open service yard. The fractured outlet faces the road.
        for ((center, height) in listOf(-91 to 6, -84 to 4)) {
            for (dx in -2..2) for (dz in -2..2) {
                if (kotlin.math.abs(dx) + kotlin.math.abs(dz) == 4) continue
                for (dy in 1..height) {
                    val ring = dy == 1 || dy == height
                    material(center + dx, groundY + dy, -10 + dz, when {
                        ring -> "polished_deepslate"
                        center == -91 && dy == 4 -> "exposed_cut_copper"
                        else -> "weathered_cut_copper"
                    })
                }
            }
        }
        for (z in -8..-5) material(vent.x, vent.y - 1, z, "waxed_exposed_cut_copper")
        material(vent.x, vent.y, vent.z, "weathered_copper_grate")
        // Concrete launch apron stays open in front of the buildings. End lamps frame the street at night.
        for (x in -124..-82 step 14) for (z in listOf(1, 11)) lamp(x, groundY + 1, z)

        // The existing bunker plateau ends at x=-7. A graded five-wide deck reaches its west bank.
        for (x in -80..-7) {
            val deck = (groundY + (71 - groundY) * (x + 80) / 73.0).roundToInt()
            for (z in 4..8) {
                val edge = z == 4 || z == 8
                material(x, deck, z, if (edge) "exposed_cut_copper" else "polished_andesite")
                // Two free blocks above the tread keep the existing terrain/vegetation from interrupting the route.
                if (!edge) {
                    material(x, deck + 1, z, "air")
                    material(x, deck + 2, z, "air")
                }
                if (edge) {
                    material(x, deck + 1, z, "iron_bars[east=true,west=true]")
                    if (x % 8 == 0) {
                        pier(x, z, deck - 1)
                        material(x, deck + 1, z, "polished_basalt")
                        if (Math.floorMod(x, 32) == 0 && z == 4 || Math.floorMod(x, 32) == 16 && z == 8) material(x, deck + 2, z, "exposed_copper_bulb[lit=true]")
                    }
                }
            }
        }
        ApocalypseFreightPier.draw(groundY, land, ::material)
        loot = markers
        // Prepared on the content worker. The running server only compares actual blocks against these two versions.
        var pierOverlapsSource = false
        val prepared = blocks.mapValues { (chunk, target) ->
            oldAndNew(chunk.first, chunk.second, target, original) { pierOverlapsSource = true }
        }
        // Other configured seeds may put a source city or bunker here. Keep its entire footprint intact.
        lots = if (pierOverlapsSource) prepared.filterKeys { it.first > (ApocalypseFreightPier.EAST_X shr 4) } else prepared
    }

    private fun oldAndNew(
        cx: Int, cz: Int, target: Map<ApocalypsePoint, String>, original: (ApocalypseChunkCanvas) -> Unit,
        sourceConflict: () -> Unit,
    ): List<ApocalypseSceneryCell> {
        val low = target.keys.minOf { it.y }
        val high = target.keys.maxOf { it.y }
        val baseline = Array((high - low + 1) * 256) { "minecraft:air" }
        fun index(x: Int, y: Int, z: Int) = (y - low) * 256 + z * 16 + x
        original(object : ApocalypseChunkCanvas(cx, cz, -64, 319) {
            override fun fill(x1: Int, y1: Int, z1: Int, x2: Int, y2: Int, z2: Int, block: ApocalypseBlock) {
                for (y in maxOf(y1, low)..minOf(y2, high)) for (z in z1..z2)
                    baseline.fill(block.blockState, index(x1, y, z), index(x2, y, z) + 1)
            }
            override fun fillState(x: Int, y: Int, z: Int, state: String) {
                if (y in low..high) {
                    baseline[index(x, y, z)] = state
                    // Only imported city/NBT pieces use fillState; natural terrain uses fill.
                    if (cx <= (ApocalypseFreightPier.EAST_X shr 4) &&
                        ApocalypsePoint(cx * 16 + x, y, cz * 16 + z) in target) sourceConflict()
                }
            }
        })
        return target.map { (p, state) ->
            ApocalypseSceneryCell(p, baseline[index(p.x and 15, p.y, p.z and 15)], state)
        }.sortedBy { it.position.y }
    }
}

internal data class ApocalypseSceneryCell(val position: ApocalypsePoint, val previous: String, val state: String)
