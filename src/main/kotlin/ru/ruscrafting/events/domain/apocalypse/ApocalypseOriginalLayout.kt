package ru.ruscrafting.events.domain.apocalypse

import ru.ruscrafting.events.domain.apocalypse.source.*
import java.util.Random
import kotlin.math.*

/** A Paper landscape decorated with the source pack's NBT pools and Lost Cities parts. */
class ApocalypseOriginalLayout(override val seed: Long, private val catalog: ApocalypseStructureAssets) : ApocalypseLayout {
    private val landscape = ApocalypseWorldLayout(seed)
    private val ground = landscape.terrain
    private val assembler = ApocalypseJigsawAssembler(catalog)
    private val assemblies = LinkedHashMap<String, ApocalypseSourceAssembly>(80, .75f, true)
    private val markerChunks = LinkedHashMap<Long, List<ApocalypseMarker>>(512, .75f, true)
    private val blockEntityChunks = LinkedHashMap<Long, List<ApocalypseSourceBlockEntity>>(512, .75f, true)
    override val starter = ApocalypseSite("starter", ApocalypseSiteKind.STARTER_BUNKER, ApocalypsePoint(0, 71, 0), NS + "starterbunker")
    private val starterAssembly = requireNotNull(assemble(starter))
    override val spawn = requireNotNull(starterAssembly.spawn) { "Source starter did not assemble its spawnpoint_entity" }

    private val teleports: List<Pair<Int, Int>> = buildList {
        val random = Random(seed)
        var angle = random.nextDouble() * 2 * PI
        var spread = 2
        var placed = 0
        var ring = 0
        for (i in 0 until 32) {
            val radius = 4.0 * 32 + 32 * ring * 6.0 + (random.nextDouble() - .5) * 32 * 2.5
            val cx = (cos(angle) * radius).roundToInt()
            val cz = (sin(angle) * radius).roundToInt()
            add(cx to cz)
            angle += 2 * PI / spread
            if (++placed == spread) {
                ring++; placed = 0
                spread += 2 * spread / (ring + 1)
                spread = minOf(spread, 32 - i)
                angle += random.nextDouble() * 2 * PI
            }
        }
    }

    private fun centers(x1: Int, z1: Int, x2: Int, z2: Int) = buildList {
        for (cz in (z1 shr 4)..(z2 shr 4)) for (cx in (x1 shr 4)..(x2 shr 4)) {
            if (Random(cz * 797003437L + cx * 295075153L).nextDouble() < .0005)
                add(ApocalypseSite("city.$cx.$cz", ApocalypseSiteKind.CITY, ApocalypsePoint(cx * 16, 71, cz * 16)))
        }
    }

    private fun candidate(rx: Int, rz: Int, spacing: Int, separation: Int, salt: Long, triangular: Boolean): Pair<Int, Int> {
        val random = Random(seed + rx * 341873128712L + rz * 132897987541L + salt)
        fun offset(): Int {
            val first = random.nextInt(spacing - separation)
            return if (triangular) (first + random.nextInt(spacing - separation)) / 2 else first
        }
        return rx * spacing + offset() to rz * spacing + offset()
    }

    private fun featureSeed(cx: Int, cz: Int): Long {
        val random = Random(seed)
        return (cx * random.nextLong()) xor (cz * random.nextLong()) xor seed
    }

    private fun excludesSurface(cx: Int, cz: Int): Boolean {
        for (rz in Math.floorDiv(cz - 8, 46)..Math.floorDiv(cz + 8, 46)) {
            for (rx in Math.floorDiv(cx - 8, 46)..Math.floorDiv(cx + 8, 46)) {
                val (sx, sz) = candidate(rx, rz, 46, 7, 647884569, false)
                if (abs(sx - cx) <= 8 && abs(sz - cz) <= 8) return true
            }
        }
        return false
    }

    /** The source's surface sets have disjoint climate tags; use the same climate signal as the native biome provider. */
    fun surfaceBiome(x: Int, z: Int): String {
        val h = ground.land(x, z)
        val climate = ground.field(x, z, 360, 296)
        return when {
            h < 55 -> "deep_ocean"
            h <= ApocalypseTerrain.SEA -> "river"
            climate < -.42 -> "snowy_plains"
            climate > .42 && h < 92 -> "desert"
            h > 96 -> "stony_peaks"
            ground.field(x, z, 75, 232) > .22 -> "taiga"
            ground.field(x, z, 75, 232) > -.22 -> "forest"
            else -> "plains"
        }
    }

    private fun sites(x1: Int, z1: Int, x2: Int, z2: Int): List<ApocalypseSite> = buildList {
        if (0 in x1..x2 && 0 in z1..z2) add(starter)
        fun addAt(cx: Int, cz: Int, name: String) {
            val x = cx * 16
            val z = cz * 16
            val kind = when (name) {
                "deepslatebunker", "nuclearbunker", "nuclearsilo", "warehouseofdoom" -> ApocalypseSiteKind.LAB
                "nuclearreactor" -> ApocalypseSiteKind.REACTOR
                "oil_rig", "underwater_lab" -> ApocalypseSiteKind.OIL_RIG
                else -> ApocalypseSiteKind.RUIN
            }
            add(ApocalypseSite("$name.$cx.$cz", kind, ApocalypsePoint(x, ground.land(x, z), z), NS + name))
        }
        fun spread(spacing: Int, separation: Int, salt: Long, names: List<String>, group: String) {
            val size = spacing * 16
            for (rz in Math.floorDiv(z1, size)..Math.floorDiv(z2, size)) for (rx in Math.floorDiv(x1, size)..Math.floorDiv(x2, size)) {
                val (cx, cz) = candidate(rx, rz, spacing, separation, salt, group == "bunkers")
                if (cx * 16 !in x1..x2 || cz * 16 !in z1..z2) continue
                val biome = surfaceBiome(cx * 16 + 8, cz * 16 + 8)
                val eligible = when (group) {
                    "desert" -> biome == "desert"
                    "snow" -> biome == "snowy_plains"
                    "water" -> biome == "deep_ocean"
                    else -> biome in LAND_BIOMES
                }
                if (!eligible) continue
                val random = Random(featureSeed(cx, cz))
                if (group == "bunkers" && (random.nextDouble() >= .9 || excludesSurface(cx, cz))) continue
                addAt(cx, cz, names[random.nextInt(names.size)])
            }
        }
        spread(75, 6, 59786254, listOf("deepslatebunker", "nuclearreactor", "warehouseofdoom",
            "nuclearbunker", "nuclearsilo", "deepslatebunker"), "bunkers")
        // The pack registers two forest candidates for the_void. Vanilla biome filtering excludes them in the Overworld.
        spread(46, 7, 647884569, listOf("radio_tower_plains","collapsed_radio_tower_plains","collapsed_comunication_tower_forest",
            "supply_house_forest","wifi_tower_forest"), "surface")
        spread(50, 8, 637884569, listOf("radio_tower_desert","collapsed_radio_tower_desert","destroyed_radio_tower_desert",
            "telecomunication_tower_desert","collapsed_comunication_tower_desert","supply_house_desert","wifi_tower_desert"), "desert")
        spread(45, 8, 627884569, listOf("radio_tower_tundra","collapsed_radio_tower_tundra","destroyed_radio_tower_tundra",
            "telecomunication_tower_tundra","collapsed_comunication_tower_tundra","supply_house_snow","wifi_tower_snow"), "snow")
        spread(82, 6, 480456315, listOf("oil_rig","oil_rig","underwater_lab"), "water")
        for ((cx, cz) in teleports) if (cx * 16 in x1..x2 && cz * 16 in z1..z2 && surfaceBiome(cx * 16, cz * 16) in LAND_BIOMES)
            addAt(cx, cz, "teleporter")
    }

    private fun assemble(site: ApocalypseSite): ApocalypseSourceAssembly? {
        val type = site.source ?: return null
        val point = site.anchor
        return assembler.assemble(type, featureSeed(point.x shr 4, point.z shr 4), point.x, point.z) { x, z ->
            if (site.kind == ApocalypseSiteKind.OIL_RIG) ground.land(x, z) else terrainHeight(x, z)
        }
    }

    fun structure(site: ApocalypseSite): ApocalypseSourceAssembly? {
        if (site.kind == ApocalypseSiteKind.STARTER_BUNKER) return starterAssembly
        if (site.source == null) return null
        synchronized(assemblies) { assemblies[site.id]?.let { return it } }
        val result = assemble(site) ?: return null
        return synchronized(assemblies) {
            assemblies[site.id] ?: result.also {
                assemblies[site.id] = it
                if (assemblies.size > 96) assemblies.entries.iterator().let { iter -> iter.next(); iter.remove() }
            }
        }
    }

    override fun terrainHeight(x: Int, z: Int): Int {
        val city = centers(x - 243, z - 243, x + 243, z + 243)
        val natural = ground.land(x, z)
        if (abs(x) <= 7 && abs(z) <= 7) return starter.anchor.y
        return if (landscape.developed(city, x, z)) 71 else if (natural in 50..130) {
            val factor = landscape.cityFactor(city, x - 7, z - 7)
            ApocalypseTerrain.mix(natural.toDouble(), 71.0, ApocalypseTerrain.smooth(((factor - .05) / .15).coerceIn(0.0, 1.0))).roundToInt()
        } else natural
    }

    override fun sitesNear(x: Int, z: Int): List<ApocalypseSite> =
        (centers(x - 1250, z - 1250, x + 1250, z + 1250) + sites(x - 2050, z - 2050, x + 2050, z + 2050)).sortedBy {
            val dx = it.anchor.x.toLong() - x
            val dz = it.anchor.z.toLong() - z
            dx * dx + dz * dz
        }
    override fun markers(site: ApocalypseSite): List<ApocalypseMarker> = structure(site)?.markers(site.id).orEmpty()

    private fun inventorySites(x: Int, z: Int): List<ApocalypseSite> = sites(x - 151, z - 151, x + 166, z + 166)
    private fun markerKey(cx: Int, cz: Int) = (cx.toLong() shl 32) xor (cz.toLong() and 0xffffffffL)
    private fun remember(cx: Int, cz: Int, markers: List<ApocalypseMarker>): List<ApocalypseMarker> = synchronized(markerChunks) {
        markerChunks[markerKey(cx, cz)] = markers
        if (markerChunks.size > 4096) markerChunks.entries.iterator().let { iter -> iter.next(); iter.remove() }
        markers
    }

    override fun markersInChunk(chunkX: Int, chunkZ: Int): List<ApocalypseMarker> {
        synchronized(markerChunks) { markerChunks[markerKey(chunkX, chunkZ)]?.let { return it } }
        val x = chunkX * 16
        val z = chunkZ * 16
        val city = centers(x - 243, z - 243, x + 258, z + 258)
        return remember(chunkX, chunkZ, chunkMarkers(chunkX, chunkZ, city, inventorySites(x, z)))
    }
    fun blockEntitiesInChunk(cx: Int, cz: Int): List<ApocalypseSourceBlockEntity> {
        synchronized(blockEntityChunks) { blockEntityChunks[markerKey(cx, cz)]?.let { return it } }
        return rememberBlockEntities(cx, cz, inventorySites(cx * 16, cz * 16))
    }
    private fun rememberBlockEntities(cx: Int, cz: Int, sites: List<ApocalypseSite>): List<ApocalypseSourceBlockEntity> {
        val entries = sites.flatMap { structure(it)?.blockEntities(cx, cz).orEmpty() }
        return synchronized(blockEntityChunks) {
            blockEntityChunks[markerKey(cx, cz)] = entries
            if (blockEntityChunks.size > 4096) blockEntityChunks.entries.iterator().let { iter -> iter.next(); iter.remove() }
            entries
        }
    }
    private fun chunkMarkers(cx: Int, cz: Int, city: List<ApocalypseSite>, structures: List<ApocalypseSite>): List<ApocalypseMarker> {
        val x = cx * 16
        val z = cz * 16
        val owner = city.maxByOrNull { landscape.cityFactor(listOf(it), x, z) }
        val cityMarkers = if (owner != null && landscape.developed(city, x, z))
            catalog.city.markers(seed, cx, cz, 71, landscape.cityFactor(city, x, z), owner.id) else emptyList()
        return (cityMarkers + structures.flatMap { markers(it) }).filter { it.x in x..x+15 && it.z in z..z+15 }.distinctBy { Triple(it.x, it.y, it.z) }
    }

    override fun generate(out: ApocalypseChunkCanvas) {
        val city = centers(out.minX - 250, out.minZ - 250, out.maxX + 250, out.maxZ + 250)
        val structures = inventorySites(out.minX, out.minZ)
        landscape.generateLandscape(out, city + structures)
        if (landscape.developed(city, out.minX, out.minZ)) {
            catalog.city.render(seed, out.chunkX, out.chunkZ, 71, landscape.cityFactor(city, out.minX, out.minZ)) { x, y, z, state ->
                ApocalypseSourcePalette.vanillaState(state)?.let { out.blockState(x, y, z, it) }
            }
        }
        for (site in structures) structure(site)?.render(out.chunkX, out.chunkZ, out::blockState)
        remember(out.chunkX, out.chunkZ, chunkMarkers(out.chunkX, out.chunkZ, city, structures))
        rememberBlockEntities(out.chunkX, out.chunkZ, structures)
    }

    companion object {
        private const val NS = "jeffs_cursed_walking_structures:"
        private val LAND_BIOMES = setOf("forest","taiga","plains","stony_peaks")
    }
}
