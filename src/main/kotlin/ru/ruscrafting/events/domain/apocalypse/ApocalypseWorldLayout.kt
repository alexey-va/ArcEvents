package ru.ruscrafting.events.domain.apocalypse

import kotlin.math.abs
import kotlin.math.roundToInt

data class ApocalypsePoint(val x: Int, val y: Int, val z: Int)

enum class ApocalypseSiteKind { STARTER_BUNKER, CITY, LAB, REACTOR, RUIN, OIL_RIG }

enum class ApocalypseMarkerKind(val block: ApocalypseBlock) {
    LOOT(ApocalypseBlock.BARREL), MEDICAL(ApocalypseBlock.BARREL), ARMORY(ApocalypseBlock.BARREL),
    POWER(ApocalypseBlock.LEVER), SECURITY(ApocalypseBlock.CHISELED_COPPER), CORE(ApocalypseBlock.BARREL), FORCEFIELD(ApocalypseBlock.LIGHT_BLUE_STAINED_GLASS_PANE),
    BUNKER_SPAWNER(ApocalypseBlock.COPPER_GRATE), HAZMAT_SPAWNER(ApocalypseBlock.COPPER_GRATE),
    SOLDIER_SPAWNER(ApocalypseBlock.COPPER_GRATE), MUTATED_SPAWNER(ApocalypseBlock.COPPER_GRATE),
    WAREHOUSE_SPAWNER(ApocalypseBlock.COPPER_GRATE),
}

data class ApocalypseSite(val id: String, val kind: ApocalypseSiteKind, val anchor: ApocalypsePoint, val source: String? = null) {
    internal val influenceRadius = if (kind == ApocalypseSiteKind.CITY) {
        125 + java.util.Random((anchor.z shr 4) * 100001653L + (anchor.x shr 4) * 295075153L).nextInt(100)
    } else 0
}
data class ApocalypseMarker(val siteId: String, val kind: ApocalypseMarkerKind, val x: Int, val y: Int, val z: Int,
    val lootTable: String? = null, val sourceBlock: String? = null,
    val sourceItems: List<ru.ruscrafting.events.domain.apocalypse.source.ApocalypseSourceLootItem> = emptyList())

class ApocalypseWorldLayout(override val seed: Long) : ApocalypseLayout {
    internal val terrain = ApocalypseTerrain(seed)
    override val starter = ApocalypseSite("starter", ApocalypseSiteKind.STARTER_BUNKER, ApocalypsePoint(0, 71, 0))
    override val spawn = ApocalypsePoint(0, starter.anchor.y - 11, 17)

    // The seed-independent center and radius rolls, additive distance field and threshold follow
    // McJty's Lost Cities 7.4.11 City class; the values are the CW custom2 Overworld profile.
    private fun centers(x1: Int, z1: Int, x2: Int, z2: Int): List<ApocalypseSite> = buildList {
        for (cz in Math.floorDiv(z1, 16)..Math.floorDiv(z2, 16)) {
            for (cx in Math.floorDiv(x1, 16)..Math.floorDiv(x2, 16)) {
                if (java.util.Random(cz * 797003437L + cx * 295075153L).nextDouble() < 0.0005) {
                    add(ApocalypseSite("city.$cx.$cz", ApocalypseSiteKind.CITY, ApocalypsePoint(cx * 16, 71, cz * 16)))
                }
            }
        }
    }

    internal fun cityRadius(s: ApocalypseSite): Int = s.influenceRadius

    private fun spread(rx: Int, rz: Int, spacing: Int, separation: Int, salt: Int, triangular: Boolean): Pair<Int, Int> {
        val random = java.util.Random(seed + rx * 341873128712L + rz * 132897987541L + salt)
        val bound = spacing - separation
        fun next() = if (triangular) (random.nextInt(bound) + random.nextInt(bound)) / 2 else random.nextInt(bound)
        return (rx * spacing + next()) * 16 + 8 to (rz * spacing + next()) * 16 + 8
    }

    private fun outposts(x1: Int, z1: Int, x2: Int, z2: Int): List<ApocalypseSite> = buildList {
        if (x1 <= 35 && x2 >= -35 && z1 <= 35 && z2 >= -35) add(starter)
        fun candidates(spacing: Int, separation: Int, salt: Int, triangular: Boolean, kind: ApocalypseSiteKind) {
            val size = spacing * 16
            for (rz in Math.floorDiv(z1, size)..Math.floorDiv(z2, size)) {
                for (rx in Math.floorDiv(x1, size)..Math.floorDiv(x2, size)) {
                    val (x, z) = spread(rx, rz, spacing, separation, salt, triangular)
                    if (x !in x1..x2 || z !in z1..z2 || (abs(x) < 85 && abs(z) < 85)) continue
                    val height = terrain.land(x, z)
                    if (kind == ApocalypseSiteKind.OIL_RIG && height > ApocalypseTerrain.SEA - 4) continue
                    if (kind != ApocalypseSiteKind.OIL_RIG && height < ApocalypseTerrain.SEA + 1) continue
                    var type = kind
                    if (kind == ApocalypseSiteKind.LAB) {
                        if (terrain.pick(rx, rz, 211, 10) == 0) continue
                        val (surfaceX, surfaceZ) = spread(Math.floorDiv(x, 46 * 16), Math.floorDiv(z, 46 * 16), 46, 7, 647884569, false)
                        if (abs(surfaceX - x) <= 8 * 16 && abs(surfaceZ - z) <= 8 * 16) continue
                        type = if (terrain.pick(rx, rz, 212, 3) == 0) ApocalypseSiteKind.REACTOR else ApocalypseSiteKind.LAB
                    }
                    val y = if (type == ApocalypseSiteKind.OIL_RIG) ApocalypseTerrain.SEA + 7 else height
                    add(ApocalypseSite("${type.name.lowercase()}.$rx.$rz", type, ApocalypsePoint(x, y, z)))
                }
            }
        }
        candidates(75, 6, 59786254, true, ApocalypseSiteKind.LAB)
        candidates(46, 7, 647884569, false, ApocalypseSiteKind.RUIN)
        candidates(82, 6, 480456315, false, ApocalypseSiteKind.OIL_RIG)
    }

    override fun sitesNear(x: Int, z: Int): List<ApocalypseSite> {
        val surrounding = centers(x - 1250, z - 1250, x + 1250, z + 1250) +
            outposts(x - 2050, z - 2050, x + 2050, z + 2050)
        return surrounding.distinctBy { it.id }.sortedBy {
            val dx = (it.anchor.x - x).toLong()
            val dz = (it.anchor.z - z).toLong()
            dx * dx + dz * dz
        }
    }

    internal fun environmentsFor(x1: Int, z1: Int, x2: Int, z2: Int): List<ApocalypseSite> =
        outposts(x1 - 63, z1 - 63, x2 + 63, z2 + 63) + centers(x1 - 243, z1 - 243, x2 + 243, z2 + 243)

    private fun radius(kind: ApocalypseSiteKind) = when (kind) {
        ApocalypseSiteKind.CITY -> error("City influence uses the per-center radius")
        ApocalypseSiteKind.STARTER_BUNKER -> 7
        ApocalypseSiteKind.RUIN -> 11
        ApocalypseSiteKind.OIL_RIG -> 18
        else -> 13
    }

    internal fun cityFactor(sites: List<ApocalypseSite>, x: Int, z: Int): Double {
        var factor = 0.0
        for (s in sites) {
            if (s.kind != ApocalypseSiteKind.CITY) continue
            val d = kotlin.math.hypot((s.anchor.x - x).toDouble(), (s.anchor.z - z).toDouble())
            factor += (1.0 - d / cityRadius(s)).coerceAtLeast(0.0)
        }
        return factor
    }

    internal fun developed(sites: List<ApocalypseSite>, x: Int, z: Int): Boolean {
        val cx = Math.floorDiv(x, 16) * 16
        val cz = Math.floorDiv(z, 16) * 16
        return terrain.land(cx + 8, cz + 8) in 50..130 && cityFactor(sites, cx, cz) >= 0.2
    }

    private fun terrainHeight(x: Int, z: Int, sites: List<ApocalypseSite>): Int {
        val natural = terrain.land(x, z)
        var h = natural.toDouble()
        // Two blocks around streets remain level; use a gradual shoulder on the exterior.
        if (developed(sites, x, z)) h = 71.0
        else if (natural in 50..130) {
            val inf = cityFactor(sites, x - 7, z - 7)
            h = ApocalypseTerrain.mix(h, 71.0, ApocalypseTerrain.smooth(((inf - 0.05) / 0.15).coerceIn(0.0, 1.0)))
        }
        for (site in sites) {
            if (site.kind == ApocalypseSiteKind.CITY || site.kind == ApocalypseSiteKind.OIL_RIG) continue
            val a = site.anchor
            val d = maxOf(abs(x - a.x), abs(z - a.z)) - radius(site.kind)
            if (d <= 0) return a.y
            if (d < 12) h = ApocalypseTerrain.mix(a.y.toDouble(), h, ApocalypseTerrain.smooth(d / 12.0))
        }
        return h.roundToInt()
    }

    override fun terrainHeight(x: Int, z: Int): Int = terrainHeight(x, z, environmentsFor(x, z, x, z))

    override fun markers(site: ApocalypseSite): List<ApocalypseMarker> = ApocalypseStructureGeometry.markers(this, site)

    override fun markersInChunk(chunkX: Int, chunkZ: Int): List<ApocalypseMarker> {
        val x = chunkX * 16
        val z = chunkZ * 16
        val sites = environmentsFor(x, z, x + 15, z + 15)
        val result = sites.filter { it.kind != ApocalypseSiteKind.CITY }.flatMap { markers(it) } +
            ApocalypseStructureCity.markersInChunk(this, sites, chunkX, chunkZ)
        return result
            .filter { it.x in x..x + 15 && it.z in z..z + 15 }
    }

    override fun generate(out: ApocalypseChunkCanvas) {
        val sites = environmentsFor(out.minX - 5, out.minZ - 5, out.maxX + 5, out.maxZ + 5)
        generateLandscape(out, sites)
        ApocalypseStructureCity.draw(this, out, sites)
        for (site in sites) {
            if (site.kind == ApocalypseSiteKind.CITY) continue
            ApocalypseStructureGeometry.draw(this, out, site)
            for (marker in markers(site)) out.block(marker.x, marker.y, marker.z, marker.kind.block)
        }
    }

    internal fun generateLandscape(out: ApocalypseChunkCanvas, sites: List<ApocalypseSite>) {
        for (z in out.minZ..out.maxZ) for (x in out.minX..out.maxX) {
            terrain.drawColumn(out, x, z, terrainHeight(x, z, sites))
        }
        vegetation(out, sites)
    }

    private fun vegetation(out: ApocalypseChunkCanvas, sites: List<ApocalypseSite>) {
        fun developed(x: Int, z: Int, margin: Int) = developed(sites, x, z) || sites.any {
            it.kind != ApocalypseSiteKind.CITY && maxOf(abs(x - it.anchor.x), abs(z - it.anchor.z)) < radius(it.kind) + margin
        }
        for (gz in Math.floorDiv(out.minZ - 4, 8)..Math.floorDiv(out.maxZ + 4, 8)) {
            for (gx in Math.floorDiv(out.minX - 4, 8)..Math.floorDiv(out.maxX + 4, 8)) {
                val x = gx * 8 + terrain.pick(gx, gz, 230, 8)
                val z = gz * 8 + terrain.pick(gx, gz, 231, 8)
                if (developed(x, z, 4)) continue
                val y = terrainHeight(x, z, sites)
                if (y <= ApocalypseTerrain.SEA + 1) continue
                val moist = terrain.field(x, z, 75, 232)
                val roll = terrain.pick(gx, gz, 233, 12)
                if (roll < 3 + (moist * 5).roundToInt() && y < 105) {
                    val pine = moist > 0.12
                    val h = 5 + terrain.pick(gx, gz, 234, 4)
                    val log = if (pine) ApocalypseBlock.SPRUCE_LOG else ApocalypseBlock.OAK_LOG
                    if (roll != 0 || moist > 0.25) {
                        val leaf = if (pine) ApocalypseBlock.SPRUCE_LEAVES else ApocalypseBlock.OAK_LEAVES
                        for (dy in h - 4..h + 1) {
                            val width = if (pine) (h + 2 - dy) / 2 else if (dy in h - 3..h - 1) 3 else 1
                            for (dz in -width..width) {
                                val half = width - (abs(dz) / 2)
                                out.box(x - half, y + dy, z + dz, x + half, y + dy, z + dz, leaf)
                            }
                        }
                    } else {
                        out.box(x + 1, y + h - 2, z, x + 2, y + h - 2, z, log)
                        out.block(x + 2, y + h - 1, z, log)
                    }
                    out.box(x, y, z, x, y + h, z, log)
                } else if (y < 94 && roll < 9) {
                    out.block(x, y + 1, z, if (moist > 0) ApocalypseBlock.FERN else ApocalypseBlock.SHORT_GRASS)
                }
            }
        }
    }


}
