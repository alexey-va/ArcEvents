package ru.ruscrafting.events.domain.apocalypse.source

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import ru.ruscrafting.events.domain.apocalypse.ApocalypseMarker
import ru.ruscrafting.events.domain.apocalypse.ApocalypseMarkerKind
import java.nio.file.Path
import java.util.Collections
import java.util.Random
import java.util.zip.ZipFile

/**
 * Original Lost Cities 7.4.11 assets overlaid by pinned Keerdm 1.42 assets.
 * Parts/palettes are read from external archives, never invented or embedded in the plugin.
 * Chunk placement uses CW's configured area budgets; it is not a Forge terrain/RNG oracle.
 */
class ApocalypseLostCityAssets private constructor(private val assets: Map<String, Map<String, JsonObject>>) {
    val partIds: Set<String> = Collections.unmodifiableSet(assets.getValue("parts").keys.toSet())
    val buildingIds: Set<String> = Collections.unmodifiableSet(assets.getValue("buildings").keys.toSet())
    val sourceStates: Set<String> = Collections.unmodifiableSet(buildSet {
        fun collect(value: JsonElement) {
            when {
                value.isJsonObject -> value.asJsonObject.entrySet().forEach { (key, child) ->
                    if ((key == "block" || key == "damaged") && child.isJsonPrimitive) add(canonicalState(child.asString))
                    else collect(child)
                }
                value.isJsonArray -> value.asJsonArray.forEach(::collect)
            }
        }
        assets.values.forEach { definitions -> definitions.values.forEach(::collect) }
    })
    val multibuildings: Map<String, List<List<String>>> = Collections.unmodifiableMap(
        assets["multibuildings"].orEmpty().mapValues { (_, value) ->
            Collections.unmodifiableList(value.getAsJsonArray("buildings").map { row ->
                Collections.unmodifiableList(row.asJsonArray.map { id(it.asString) })
            })
        },
    )
    private val world = asset("worldstyles", "keerdm_zombie_essentials:testworld")
    private val cityStyle = inheritedStyle(id(world.getAsJsonArray("citystyles").first().asJsonObject.get("citystyle").asString))
    private val style = asset("styles", id(cityStyle.get("style").asString))
    private val globalPalette = basePalette()
    private val parts = assets.getValue("parts").mapValues { (id, value) -> SourcePart(id, value) }

    private data class Entry(val states: List<String>, val alias: Char? = null, val loot: String? = null, val nbt: String? = null) {
        fun pick(random: Random): String = if (states.size == 1) states.first() else states[random.nextInt(128)]
    }
    private class SourcePart(val id: String, val json: JsonObject) {
        val width = json.get("xsize").asInt
        val depth = json.get("zsize").asInt
        val slices = json.getAsJsonArray("slices").map { slice -> slice.asJsonArray.joinToString("") { it.asString } }
        init {
            require(width in 1..128 && depth in 1..128 && slices.size in 1..512) { "Invalid source part dimensions: $id" }
            val short = slices.filter { it.length != width * depth }
            // Pinned Keerdm top_crane has exactly two 255-character layers. Preserve flat indices;
            // only their absent final cell is skipped. Never pad each short source row or invent blocks.
            require(short.isEmpty() || (id == "keerdm_zombie_essentials:top_crane" && width == 16 && depth == 16 &&
                slices.size == 23 && short.size == 2 && short.all { it.length == 255 })) { "Invalid source part rows: $id" }
        }
    }
    private data class Placement(val part: String, val y: Int, val buildingPalette: JsonObject? = null)
    private data class Lot(val placements: List<Placement>, val clearFrom: Int? = null, val clearUntil: Int? = null)
    private data class MultiBuilding(val building: String, val anchorX: Int, val anchorZ: Int, val rootBuilding: String)

    fun render(seed: Long, cx: Int, cz: Int, groundY: Int, cityFactor: Double, put: (Int, Int, Int, String) -> Unit) {
        require(cityFactor.isFinite())
        val lot = lot(seed, cx, cz, groundY, cityFactor)
        if (lot.clearFrom != null && lot.clearUntil != null) {
            for (y in lot.clearFrom until lot.clearUntil) for (z in 0..15) for (x in 0..15) put(cx * 16 + x, y, cz * 16 + z, "minecraft:air")
        }
        visit(lot, seed, cx, cz) { x, y, z, state, _ -> put(cx * 16 + x, y, cz * 16 + z, state) }
    }

    fun markers(seed: Long, cx: Int, cz: Int, groundY: Int, cityFactor: Double, siteId: String): List<ApocalypseMarker> = buildList {
        visit(lot(seed, cx, cz, groundY, cityFactor), seed, cx, cz) { x, y, z, state, entry ->
            if (entry.loot != null || state.substringBefore('[') in setOf("minecraft:chest", "minecraft:trapped_chest", "minecraft:barrel")) {
                add(ApocalypseMarker(siteId, ApocalypseMarkerKind.LOOT, cx * 16 + x, y, cz * 16 + z))
            }
        }
    }.distinctBy { Triple(it.x, it.y, it.z) }

    /** Explicit original building selection for callers assembling a known multi footprint. */
    fun renderBuilding(id: String, seed: Long, cx: Int, cz: Int, groundY: Int, floors: Int, cellars: Int,
                       put: (Int, Int, Int, String) -> Unit) {
        val lot = buildingLot(Companion.id(id), seed, cx, cz, groundY, floors, cellars)
        if (lot.clearFrom != null && lot.clearUntil != null) {
            for (y in lot.clearFrom until lot.clearUntil) for (z in 0..15) for (x in 0..15) put(cx * 16 + x, y, cz * 16 + z, "minecraft:air")
        }
        visit(lot, seed, cx, cz) { x, y, z, state, _ -> put(cx * 16 + x, y, cz * 16 + z, state) }
    }

    /** Original part coordinates and local palette, useful for source fixtures and infrastructure. */
    fun part(id: String, seed: Long = 0): ApocalypseSourceTemplate {
        val source = parts.getValue(Companion.id(id))
        val palette = linkedMapOf<String, Int>()
        val indices = IntArray(source.width * source.depth * source.slices.size) { -1 }
        val nbt = linkedMapOf<Int, String>()
        visitPart(source, 0, Random(seed), null) { x, y, z, state, entry ->
            val index = (y * source.depth + z) * source.width + x
            indices[index] = palette.getOrPut(state) { palette.size }
            entry.nbt?.let { nbt[index] = it }
        }
        return ApocalypseSourceTemplate(source.id, source.width, source.slices.size, source.depth, palette.keys.toList(), indices,
            nbt, emptyList(), emptyList())
    }

    private fun lot(seed: Long, cx: Int, cz: Int, groundY: Int, factor: Double): Lot {
        val random = Random(chunkSeed(seed, cx, cz))
        val multi = multiBuilding(seed, cx, cz)
        if (multi != null) {
            // Lost Cities copies these values from the top-left chunk; individual matrix cells
            // must never roll their own heights, limits or building palette.
            val shared = Random(chunkSeed(seed, multi.anchorX, multi.anchorZ))
            val chance = (4 + (anchorCityFactor(multi.anchorX, multi.anchorZ) + .1) * 2).toInt().coerceIn(1, 8)
            return buildingLot(multi.building, seed, cx, cz, groundY, 1 + shared.nextInt(chance), shared.nextInt(4), multi.rootBuilding)
        }
        val building = if (random.nextDouble() < .3) choose("buildings", random) else null
        if (building != null) {
            val chance = (4 + (factor + .1) * 2).toInt().coerceIn(1, 8)
            return buildingLot(building, seed, cx, cz, groundY, 1 + random.nextInt(chance), random.nextInt(4))
        }
        val part = if (random.nextDouble() < .1) choose("parks", random) else when {
            Math.floorMod(cx, 5) == 0 && Math.floorMod(cz, 5) == 0 -> "lostcities:street_all"
            Math.floorMod(cx, 5) == 0 || Math.floorMod(cz, 5) == 0 -> "lostcities:street_straight"
            else -> "lostcities:street_full"
        }
        return Lot(listOf(Placement(part, groundY)))
    }

    private fun multiBuilding(seed: Long, cx: Int, cz: Int): MultiBuilding? {
        val settings = world.getAsJsonObject("multisettings") ?: return null
        val area = settings.get("areasize").asInt.coerceIn(1, 16)
        val rx = Math.floorDiv(cx, area)
        val rz = Math.floorDiv(cz, area)
        val random = Random(chunkSeed(seed xor 0x4D554C5449L, rx, rz))
        val minimum = settings.get("minimum").asInt.coerceIn(0, 16)
        val maximum = settings.get("maximum").asInt.coerceIn(minimum, 16)
        val wanted = minimum + random.nextInt(maximum - minimum + 1)
        val occupied = BooleanArray(area * area)
        var placed = 0
        repeat(settings.get("attempts").asInt.coerceIn(0, 256)) {
            if (placed >= wanted) return null
            val name = choose("multibuildings", random)
            val grid = multibuildings[name] ?: error("Missing original multi building: $name")
            val width = grid.size
            val depth = grid.firstOrNull()?.size ?: return@repeat
            if (width > area || depth > area) return@repeat
            val x = random.nextInt(area - width + 1)
            val z = random.nextInt(area - depth + 1)
            if ((0 until width).any { dx -> (0 until depth).any { dz -> occupied[(z + dz) * area + x + dx] } }) return@repeat
            for (dx in 0 until width) for (dz in 0 until depth) occupied[(z + dz) * area + x + dx] = true
            placed++
            val localX = cx - rx * area - x
            val localZ = cz - rz * area - z
            if (localX in 0 until width && localZ in 0 until depth) {
                return MultiBuilding(grid[localX][localZ], rx * area + x, rz * area + z, grid[0][0])
            }
        }
        return null
    }

    private fun buildingLot(name: String, seed: Long, cx: Int, cz: Int, groundY: Int, requestedFloors: Int, requestedCellars: Int,
                            sharedDefinition: String? = null): Lot {
        val building = asset("buildings", name)
        val settings = if (sharedDefinition != null) asset("buildings", sharedDefinition) else building
        val minimum = settings.get("minfloors")?.asInt?.takeIf { it >= 0 } ?: 1
        val maximum = settings.get("maxfloors")?.asInt?.takeIf { it >= 0 } ?: 8
        val floors = requestedFloors.coerceIn(minimum, maximum.coerceAtLeast(minimum))
        val cellars = requestedCellars.coerceIn(settings.get("mincellars")?.asInt?.coerceAtLeast(0) ?: 0,
            settings.get("maxcellars")?.asInt?.takeIf { it >= 0 } ?: 3)
        val random = Random(chunkSeed(seed, cx, cz) xor name.hashCode().toLong())
        val local = settings.getAsJsonObject("palette")
        val placements = buildList {
            for (floor in -cellars..floors) for (key in listOf("parts", "parts2")) {
                val candidates = building.getAsJsonArray(key)?.filter { matches(it.asJsonObject, floor, floors) }.orEmpty()
                if (candidates.isEmpty()) {
                    require(key != "parts") { "No matching original floor: $name / $floor of $floors" }
                    continue
                }
                val selected = id(candidates[random.nextInt(candidates.size)].asJsonObject.get("part").asString)
                require(selected in parts) { "Missing original part: $selected" }
                add(Placement(selected, groundY + floor * 6, local))
            }
        }
        return Lot(placements, groundY - cellars * 6,
            placements.maxOfOrNull { it.y + parts.getValue(it.part).slices.size } ?: groundY)
    }

    /** CW custom2/Lost Cities center and radius rolls are seed-independent. Bounded 31×31 scan. */
    private fun anchorCityFactor(cx: Int, cz: Int): Double {
        var factor = 0.0
        for (z in cz - 15..cz + 15) for (x in cx - 15..cx + 15) {
            if (Random(z * 797003437L + x * 295075153L).nextDouble() >= .0005) continue
            val radius = 125 + Random(z * 100001653L + x * 295075153L).nextInt(100)
            val distance = kotlin.math.hypot((cx - x) * 16.0, (cz - z) * 16.0)
            factor += (1.0 - distance / radius).coerceAtLeast(0.0)
        }
        return factor
    }

    private fun matches(rule: JsonObject, floor: Int, top: Int): Boolean {
        if (rule.has("top") && rule.get("top").asBoolean != (floor == top)) return false
        if (rule.has("ground") && rule.get("ground").asBoolean != (floor == 0)) return false
        if (rule.has("cellar") && rule.get("cellar").asBoolean != (floor < 0)) return false
        if (rule.has("floor") && rule.get("floor").asInt != floor) return false
        if (rule.has("range")) {
            val range = rule.get("range").asString.split(',').map(String::toInt)
            if (range.size != 2 || floor !in range[0]..range[1]) return false
        }
        return true
    }

    private fun visit(lot: Lot, seed: Long, cx: Int, cz: Int, put: (Int, Int, Int, String, Entry) -> Unit) {
        lot.placements.forEachIndexed { index, placed ->
            visitPart(parts.getValue(placed.part), placed.y, Random(chunkSeed(seed, cx, cz) xor placed.part.hashCode().toLong() xor index.toLong()),
                placed.buildingPalette, put)
        }
    }

    private fun visitPart(part: SourcePart, baseY: Int, random: Random, buildingPalette: JsonObject?, put: (Int, Int, Int, String, Entry) -> Unit) {
        val palette = LinkedHashMap(globalPalette)
        buildingPalette?.let { overlay(palette, it) }
        part.json.get("refpalette")?.asString?.let { overlay(palette, asset("palettes", id(it))) }
        part.json.getAsJsonObject("palette")?.let { overlay(palette, it) }
        resolveAliases(palette)
        part.slices.forEachIndexed { y, slice -> for (z in 0 until part.depth) for (x in 0 until part.width) {
            val character = slice.getOrNull(z * part.width + x) ?: continue
            val entry = palette[character] ?: error("Undefined original palette character ${character.code} in ${part.id}")
            val state = entry.pick(random)
            // Lost Cities air leaves the prepared volume unchanged; structure_void is hard air.
            if (state != "minecraft:air") put(x, baseY + y, z, if (state == "minecraft:structure_void") "minecraft:air" else state, entry)
        } }
    }

    private fun basePalette(): Map<Char, Entry> {
        val palette = linkedMapOf<Char, Entry>()
        val random = Random(0)
        style.getAsJsonArray("randompalettes").forEach { group ->
            val selected = weighted(group.asJsonArray, random, "factor").get("palette").asString
            overlay(palette, asset("palettes", id(selected)))
        }
        resolveAliases(palette)
        return palette.toMap()
    }

    private fun overlay(target: MutableMap<Char, Entry>, json: JsonObject) {
        json.getAsJsonArray("palette").forEach { element ->
            val value = element.asJsonObject
            val character = value.get("char").asString.single()
            val loot = value.get("loot")?.asString
            val tag = value.get("tag")?.toString()
            when {
                value.has("frompalette") -> target[character] = Entry(emptyList(), value.get("frompalette").asString.single(), loot, tag)
                value.has("block") -> target[character] = Entry(listOf(canonicalState(value.get("block").asString)), loot = loot, nbt = tag)
                else -> {
                    val choices = if (value.has("variant")) asset("variants", id(value.get("variant").asString)).getAsJsonArray("blocks")
                        else value.getAsJsonArray("blocks")
                    val slots = ArrayList<String>(128)
                    for (raw in choices) {
                        val choice = raw.asJsonObject
                        repeat(choice.get("random").asInt.coerceIn(0, 128 - slots.size)) { slots += canonicalState(choice.get("block").asString) }
                        if (slots.size == 128) break
                    }
                    require(slots.size == 128) { "Source random palette has fewer than 128 slots: $character" }
                    target[character] = Entry(slots.toList(), loot = loot, nbt = tag)
                }
            }
        }
    }

    private fun resolveAliases(palette: MutableMap<Char, Entry>) {
        repeat(palette.size) {
            var changed = false
            palette.toMap().forEach { (character, entry) ->
                val alias = entry.alias ?: return@forEach
                val original = palette[alias]
                if (original != null && original.alias == null) {
                    palette[character] = original.copy(loot = entry.loot ?: original.loot, nbt = entry.nbt ?: original.nbt)
                    changed = true
                }
            }
            if (!changed) {
                require(palette.values.none { it.alias != null }) { "Unresolved source palette alias" }
                return
            }
        }
        require(palette.values.none { it.alias != null }) { "Cyclic source palette aliases" }
    }

    private fun choose(selector: String, random: Random): String =
        id(weighted(cityStyle.getAsJsonObject("selectors").getAsJsonArray(selector), random, "factor").get("value").asString)

    private fun weighted(array: JsonArray, random: Random, key: String): JsonObject {
        require(array.size() in 1..4096)
        val sum = array.sumOf { it.asJsonObject.get(key)?.asDouble ?: 1.0 }
        require(sum.isFinite() && sum > 0)
        var roll = random.nextDouble() * sum
        for (value in array) {
            val objectValue = value.asJsonObject
            roll -= objectValue.get(key)?.asDouble ?: 1.0
            if (roll < 0) return objectValue
        }
        return array.last().asJsonObject
    }

    private fun inheritedStyle(name: String, seen: Set<String> = emptySet()): JsonObject {
        require(name !in seen && seen.size < 16) { "Cyclic city style: $name" }
        val value = asset("citystyles", name)
        val parent = value.get("inherit")?.asString ?: return value
        return merge(inheritedStyle(id(parent), seen + name), value)
    }

    private fun merge(parent: JsonObject, child: JsonObject): JsonObject = parent.deepCopy().also { result ->
        child.entrySet().forEach { (key, value) ->
            result.add(key, if (value.isJsonObject && result.get(key)?.isJsonObject == true) merge(result.getAsJsonObject(key), value.asJsonObject) else value.deepCopy())
        }
    }

    private fun asset(kind: String, id: String): JsonObject = assets[kind]?.get(id) ?: error("Missing original Lost Cities $kind asset: $id")

    companion object {
        /**
         * LC7.4.11 Tools.stringToState delegates legacy names to Mojang BlockStateData.
         * The pinned archives contain this single metadata form (two palette entries).
         * Official1.20.1 flattening entry2866=(179<<4)|2 is cut_red_sandstone;
         * smooth_red_sandstone is the distinct legacy slab entry2904.
         */
        internal fun canonicalState(value: String): String {
            if (value == "minecraft:red_sandstone@2") return "minecraft:cut_red_sandstone"
            require('@' !in value) { "Unmapped legacy source block state: $value" }
            return value
        }

        internal fun id(value: String): String = if (':' in value) value else "lostcities:$value"
        private fun chunkSeed(seed: Long, x: Int, z: Int): Long = seed + x * 341873128712L + z * 132897987541L

        fun load(lostCities: Path, keerdm: Path): ApocalypseLostCityAssets {
            val assets = linkedMapOf<String, MutableMap<String, JsonObject>>()
            for (path in listOf(lostCities, keerdm)) ZipFile(path.toFile()).use { zip ->
                val entries = zip.entries()
                var count = 0
                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()
                    require(++count <= 20_000)
                    val pieces = entry.name.split('/')
                    if (entry.isDirectory || pieces.size < 5 || pieces[0] != "data" || pieces[2] != "lostcities" || !entry.name.endsWith(".json")) continue
                    val json = zip.getInputStream(entry).use { input ->
                        val bytes = input.readNBytes(ApocalypseStructureAssets.MAX_JSON_BYTES + 1)
                        require(bytes.size <= ApocalypseStructureAssets.MAX_JSON_BYTES) { "Source city JSON too large: ${entry.name}" }
                        JsonParser.parseString(bytes.toString(Charsets.UTF_8)).asJsonObject
                    }
                    val id = pieces[1] + ":" + pieces.drop(4).joinToString("/").removeSuffix(".json")
                    assets.getOrPut(pieces[3]) { linkedMapOf() }[id] = json
                }
            }
            return ApocalypseLostCityAssets(assets.mapValues { it.value.toMap() })
        }
    }
}
