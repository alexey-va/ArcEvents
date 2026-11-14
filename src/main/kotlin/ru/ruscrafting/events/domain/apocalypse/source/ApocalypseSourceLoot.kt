package ru.ruscrafting.events.domain.apocalypse.source

import com.google.gson.JsonElement
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.ByteArrayInputStream
import java.nio.file.Path
import java.util.Collections
import java.util.Random
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream
import kotlin.math.abs
import kotlin.math.floor

data class ApocalypseSourceLootMap(
    val destination: String, val decoration: String = "mansion", val zoom: Int = 2,
    val searchRadius: Int = 50, val skipExistingChunks: Boolean = true,
)

/** Source identities/components are data; the Paper adapter owns explicit native substitutions. */
data class ApocalypseSourceLootItem(
    val itemId: String, val count: Int, val nbt: String? = null,
    val gunId: String? = null, val ammoId: String? = null, val attachmentId: String? = null,
    val enchantLevels: Int? = null, val enchantTreasure: Boolean = false,
    val explorationMap: ApocalypseSourceLootMap? = null,
    val displayName: String? = null, val lore: List<String> = emptyList(),
)

sealed interface ApocalypseSourceLootDrop {
    data class Item(val item: ApocalypseSourceLootItem) : ApocalypseSourceLootDrop
    /** Resolved by the owning native loot registry with the real container location/context. */
    data class VanillaTable(val tableId: String) : ApocalypseSourceLootDrop
}

data class ApocalypseSourceLootContext(val floor: Int = 0, val part: String? = null)

/**
 * The bounded subset used by CW's pinned container tables. No Bukkit, inventory mutation or rewards journal.
 * Weights, empty selections, inclusive uniform counts and source SNBT remain source-defined.
 * Forge's placement/enchantment RNG stream is not reproduced by the native world generator.
 */
class ApocalypseSourceLoot private constructor(
    tables: Map<String, JsonObject>, conditions: Map<String, JsonObject>, processors: Map<String, JsonObject>,
    private val partCrates: Boolean = false,
    storedItems: Collection<ApocalypseSourceLootItem> = emptyList(),
) {
    private val tables = tables.mapValues { it.value.deepCopy() }
    private val conditions = conditions.mapValues { it.value.deepCopy() }
    private val processors = processors.mapValues { it.value.deepCopy() }
    val tableIds: Set<String> = immutableSet(tables.keys)
    val conditionIds: Set<String> = immutableSet(conditions.keys)
    val referencedItems: Set<String>
    val referencedGuns: Set<String>
    val referencedAmmo: Set<String>
    val referencedAttachments: Set<String>

    init {
        val items = linkedSetOf<String>(); val guns = linkedSetOf<String>()
        val ammo = linkedSetOf<String>(); val attachments = linkedSetOf<String>()
        fun inspect(value: JsonElement) {
            if (value.isJsonArray) value.asJsonArray.forEach(::inspect)
            else if (value.isJsonObject) {
                val obj = value.asJsonObject
                if (obj.get("type")?.asString?.substringAfter(':') == "item") {
                    obj.get("name")?.asString?.let { if (namespace(it) != "minecraft:air") items += namespace(it) }
                }
                if (obj.get("function")?.asString?.substringAfter(':') == "set_nbt") {
                    val tag = obj.get("tag").asString
                    tagString(tag, "GunId")?.let(guns::add)
                    tagString(tag, "AmmoId")?.let(ammo::add)
                    tagString(tag, "AttachmentId")?.let(attachments::add)
                }
                obj.entrySet().forEach { inspect(it.value) }
            }
        }
        this.tables.values.forEach(::inspect)
        if (partCrates) CRATE_DROPS.values.forEach { entries -> entries.forEach { items += it.itemId } }
        storedItems.forEach { item ->
            items += item.itemId
            item.gunId?.let(guns::add); item.ammoId?.let(ammo::add); item.attachmentId?.let(attachments::add)
        }
        referencedItems = immutableSet(items); referencedGuns = immutableSet(guns)
        referencedAmmo = immutableSet(ammo); referencedAttachments = immutableSet(attachments)
    }

    /** Pinned spelling repairs and the documented native voidmaterials substitute; no generic fallback. */
    fun canonicalTable(id: String): String = when (val qualified = namespace(id)) {
        "keerdm_zombie__essentials:chests/tacz_ammochest" -> "keerdm_zombie_essentials:chests/tacz_ammochest"
        "keerdm_zombie__essentials:chests/tacz_gunchest" -> "keerdm_zombie_essentials:chests/tacz_gunchest"
        "jeffs_cursed_walking_structures:chests/resouce_chest",
        "jeffs_cursed_walking_structures:resource_chest" -> "jeffs_cursed_walking_structures:chests/resource_chest"
        "jeffs_cursed_walking_structures:chests/supply_chest" -> "jeffs_cursed_walking_structures:chests/supplychest"
        // Native compatibility for a missing source table; this does not establish the author's intended target.
        "jeffs_cursed_walking_structures:chests/voidmaterials" -> "the_deeper_void:chests/void_dimension"
        else -> qualified
    }

    fun hasFirearm(tableId: String): Boolean {
        fun visit(id: String, seen: Set<String>): Boolean {
            val canonical = canonicalTable(id)
            if (canonical in seen) return false
            val table = tables[canonical] ?: return false
            fun entry(value: JsonElement): Boolean {
                if (value.isJsonArray) return value.asJsonArray.any(::entry)
                if (!value.isJsonObject) return false
                val obj = value.asJsonObject
                val itemId = obj.get("name")?.asString
                if (itemId == "tacz:modern_kinetic_gun" || itemId?.startsWith("pointblank:") == true && itemId !in POINTBLANK_AMMO) return true
                if (obj.get("type")?.asString?.substringAfter(':') == "loot_table" &&
                    visit(obj.get("name").asString, seen + canonical)) return true
                return obj.entrySet().any { entry(it.value) }
            }
            return entry(table)
        }
        return visit(tableId, emptySet())
    }

    fun roll(tableId: String, random: Random): List<ApocalypseSourceLootDrop> = roll(tableId, random, emptySet())

    /** JCWS8502762 right-click procedures: caller proves source crowbar, unopened state and durable once-only claim. */
    fun rollPartCrate(sourceBlock: String, random: Random): List<ApocalypseSourceLootDrop> {
        require(partCrates) { "JCWS procedural crate data is unavailable" }
        return requireNotNull(CRATE_DROPS[sourceBlock]) { "Unknown source component crate: $sourceBlock" }.mapNotNull { entry ->
            if (entry.chance < 1 && random.nextDouble() >= entry.chance) null else {
                val count = if (entry.min == entry.max) entry.min else entry.min + random.nextInt(entry.max - entry.min + 1)
                ApocalypseSourceLootDrop.Item(ApocalypseSourceLootItem(entry.itemId, count))
            }
        }
    }

    private fun roll(tableId: String, random: Random, parents: Set<String>): List<ApocalypseSourceLootDrop> {
        val id = canonicalTable(tableId)
        require(id !in parents && parents.size < 16) { "Cyclic/too deep original loot table: $id" }
        val table = tables[id] ?: if (id.startsWith("minecraft:")) return listOf(ApocalypseSourceLootDrop.VanillaTable(id))
            else error("Missing original loot table: $id")
        return buildList<ApocalypseSourceLootDrop> {
            for (raw in table.getAsJsonArray("pools").orEmpty()) {
                val pool = raw.asJsonObject
                if (!allowed(pool, random)) continue
                repeat(number(pool.get("rolls"), random, 1).also { require(it in 0..4096) }) {
                    val entries = pool.getAsJsonArray("entries").filter { allowed(it.asJsonObject, random) }.map { it.asJsonObject }
                    val weights = entries.map { (it.get("weight")?.asInt ?: 1).also { n -> require(n in 0..1_000_000) } }
                    val total = weights.sumOf(Int::toLong).also { require(it <= Int.MAX_VALUE) }.toInt()
                    if (total == 0) return@repeat
                    var choice = random.nextInt(total)
                    val selected = entries.indices.first { choice -= weights[it]; choice < 0 }.let(entries::get)
                    val result = when (selected.get("type")?.asString?.substringAfter(':') ?: "item") {
                        "empty" -> emptyList()
                        "item" -> {
                            val item = namespace(selected.get("name").asString)
                            if (item == "minecraft:air") emptyList() else listOf(ApocalypseSourceLootDrop.Item(ApocalypseSourceLootItem(item, 1)))
                        }
                        "loot_table" -> roll(selected.get("name").asString, random, parents + id)
                        else -> error("Unsupported original loot entry in $id: ${selected.get("type")}")
                    }
                    val modified = functions(result, selected, random).let { functions(it, pool, random) }
                    addAll(modified)
                    require(size <= 4096) { "Original loot result exceeds bound: $id" }
                }
            }
        }.let { functions(it, table, random) }
    }

    private fun functions(drops: List<ApocalypseSourceLootDrop>, owner: JsonObject, random: Random): List<ApocalypseSourceLootDrop> {
        var result = drops
        for (raw in owner.getAsJsonArray("functions").orEmpty()) {
            val function = raw.asJsonObject
            if (!allowed(function, random)) continue
            result = result.mapNotNull { drop ->
                require(drop is ApocalypseSourceLootDrop.Item) { "Functions on delegated vanilla loot require native resolution" }
                val item = drop.item
                val next = when (function.get("function").asString.substringAfter(':')) {
                    "set_count" -> item.copy(count = (if (function.get("add")?.asBoolean == true) item.count else 0) + number(function.get("count"), random, 1))
                    "set_nbt" -> {
                        val tag = mergeNbt(item.nbt, function.get("tag").asString)
                        item.copy(nbt = tag, gunId = tagString(tag, "GunId"), ammoId = tagString(tag, "AmmoId"), attachmentId = tagString(tag, "AttachmentId"))
                    }
                    "enchant_with_levels" -> item.copy(enchantLevels = number(function.get("levels"), random, 0),
                        enchantTreasure = function.get("treasure")?.asBoolean ?: false)
                    "exploration_map" -> item.copy(explorationMap = ApocalypseSourceLootMap(
                        function.get("destination").asString, function.get("decoration")?.asString ?: "mansion",
                        function.get("zoom")?.asInt ?: 2, function.get("search_radius")?.asInt ?: 50,
                        function.get("skip_existing_chunks")?.asBoolean ?: true))
                    "set_name" -> item.copy(displayName = component(function.get("name")))
                    "set_lore" -> item.copy(lore = immutableList((if (function.get("replace")?.asBoolean == true) emptyList() else item.lore) +
                        function.getAsJsonArray("lore").map(::component)))
                    else -> error("Unsupported original loot function: ${function.get("function")}")
                }
                require(next.count <= 4096) { "Original loot stack exceeds bound" }
                if (next.count <= 0) null else ApocalypseSourceLootDrop.Item(next)
            }
        }
        return result
    }

    /** Source LC conditions choose a table, not an arbitrary replacement bundle. */
    fun selectCondition(id: String, context: ApocalypseSourceLootContext, random: Random): String {
        val qualified = if (':' in id) id else "lostcities:$id"
        val condition = conditions[qualified] ?: error("Missing original city loot condition: $qualified")
        val candidates = condition.getAsJsonArray("values").map { it.asJsonObject }.filter { value ->
            require(value.keySet().all { it in setOf("factor", "value", "range", "inpart") }) { "Unsupported city loot condition: $qualified" }
            val range = value.get("range")?.asString?.split(',')?.map(String::toInt)
            (range == null || range.size == 2 && context.floor in range[0]..range[1]) &&
                (value.get("inpart")?.asString?.let { it == context.part || namespace(it, "lostcities") == context.part } ?: true)
        }
        val sum = candidates.sumOf { it.get("factor")?.asDouble ?: 1.0 }
        require(sum.isFinite() && sum > 0) { "No matching original city loot condition: $qualified / $context" }
        var choice = random.nextDouble() * sum
        return canonicalTable((candidates.firstOrNull { choice -= it.get("factor")?.asDouble ?: 1.0; choice < 0 } ?: candidates.last()).get("value").asString)
    }

    /** append_loot replaces LootTable in the source block entity; it does not concatenate two loot rolls. */
    fun processedTable(processor: String?, state: String, sourceTable: String?, x: Int, y: Int, z: Int,
                       localX: Int, localY: Int, localZ: Int): String? {
        val definition = processor?.let(processors::get) ?: return sourceTable?.let(::canonicalTable)
        // MC1.20.1 RuleProcessor: LegacyRandomSource(Mth.getSeed(transformedBlock.pos)).
        var positionSeed = (x * 3129871).toLong() xor (z.toLong() * 116129781L) xor y.toLong()
        positionSeed = positionSeed * positionSeed * 42317861L + positionSeed * 11L
        val random = Random(positionSeed shr 16)
        for (raw in definition.getAsJsonArray("processors").orEmpty()) {
            val value = raw.asJsonObject
            if (value.get("processor_type")?.asString != "minecraft:rule") continue
            for (entry in value.getAsJsonArray("rules").orEmpty()) {
                val rule = entry.asJsonObject
                val modifier = rule.getAsJsonObject("block_entity_modifier") ?: continue
                require(modifier.get("type").asString == "minecraft:append_loot")
                val input = rule.getAsJsonObject("input_predicate")
                require(input.get("predicate_type").asString == "minecraft:block_match")
                if (input.get("block").asString != state.substringBefore('[')) continue
                require(rule.getAsJsonObject("location_predicate")?.get("predicate_type")?.asString.let {
                    it == null || it == "minecraft:always_true"
                }) { "Unsupported source processor location predicate" }
                val pos = rule.getAsJsonObject("position_predicate")
                val chance = if (pos == null || pos.get("predicate_type")?.asString == "minecraft:always_true") 1.0f else {
                    require(pos.get("predicate_type").asString == "minecraft:linear_pos")
                    val minimum = pos.get("min_dist").asInt; val maximum = pos.get("max_dist").asInt
                    require(maximum > minimum)
                    val progress = ((abs(localX) + abs(localY) + abs(localZ) - minimum).toFloat() / (maximum - minimum)).coerceIn(0.0f, 1.0f)
                    pos.get("min_chance").asFloat + progress * (pos.get("max_chance").asFloat - pos.get("min_chance").asFloat)
                }
                if (random.nextFloat() <= chance) return canonicalTable(modifier.get("loot_table").asString)
            }
        }
        return sourceTable?.let(::canonicalTable)
    }

    companion object {
        private const val MAX_JSON = 2 * 1024 * 1024
        private const val MAX_NESTED_ZIP = 16 * 1024 * 1024
        // In these pinned container tables the other PointBlank item identities are firearms.
        private val POINTBLANK_AMMO = setOf("ammo12gauge", "ammo338lapua", "ammo357", "ammo45acp", "ammo46", "ammo50ae",
            "ammo50bmg", "ammo545", "ammo556", "ammo57", "ammo68", "ammo762", "ammo762x51", "ammo9mm",
            "at4_rocket", "javelin_rocket", "smaw_rocket", "grenade20mm", "grenade40mm").mapTo(hashSetOf()) { "pointblank:$it" }
        private data class CrateDrop(val itemId: String, val min: Int, val max: Int = min, val chance: Double = 1.0)
        private val CRATE_DROPS = mapOf(
            "jeffs_cursed_walking_structures:gun_part_chest" to listOf(
                CrateDrop("mcore:steel_ingot", 2, 3), CrateDrop("mcore:steel_sheet", 4, 7), CrateDrop("minecraft:iron_ingot", 4, 6),
                CrateDrop("mcore:titanium_ingot", 1, 2, .75), CrateDrop("createdeco:industrial_iron_ingot", 2, 3, .6),
                CrateDrop("create:sturdy_sheet", 1, 2), CrateDrop("minecraft:blaze_rod", 1, chance = .4),
                CrateDrop("minecraft:diamond", 1, chance = .25), CrateDrop("createdeco:netherite_sheet", 1, chance = .025)),
            "jeffs_cursed_walking_structures:create_part_chest" to listOf(
                CrateDrop("create:brass_ingot", 2, 3), CrateDrop("create:andesite_alloy", 4, 7), CrateDrop("create:zinc_ingot", 3, 5),
                CrateDrop("create:rose_quartz", 2, 3, .5), CrateDrop("create:blaze_cake", 1, chance = .2),
                CrateDrop("create:super_glue", 1, chance = .25), CrateDrop("create:sturdy_sheet", 2, 3, .5),
                CrateDrop("create:item_vault", 1, 2, .4), CrateDrop("create:mechanical_saw", 1, 2, .33),
                CrateDrop("create:mechanical_harvester", 1, 2, .3), CrateDrop("create:mechanical_press", 1, 2, .25),
                CrateDrop("create:mechanical_mixer", 1, 2, .25), CrateDrop("create:mechanical_drill", 1, 2, .2),
                CrateDrop("create:mechanical_crafter", 1, 2, .2), CrateDrop("create:crushing_wheel", 1, chance = .05),
                CrateDrop("create:precision_mechanism", 1, chance = .08), CrateDrop("create:smart_chute", 1, chance = .15),
                CrateDrop("create:mechanical_arm", 1, chance = .04), CrateDrop("create:empty_blaze_burner", 1, chance = .08)),
        )

        fun load(archives: List<Path>, cwPack: Path? = null,
                 storedItems: Collection<ApocalypseSourceLootItem> = emptyList()): ApocalypseSourceLoot {
            val tables = linkedMapOf<String, JsonObject>(); val conditions = linkedMapOf<String, JsonObject>()
            val processors = linkedMapOf<String, JsonObject>()
            var partCrates = false
            fun accept(name: String, bytes: () -> ByteArray) {
                val pieces = name.split('/')
                if (pieces.size < 4 || pieces[0] != "data" || !name.endsWith(".json")) return
                val target: MutableMap<String, JsonObject>; val start: Int
                when {
                    pieces[2] == "loot_tables" -> { target = tables; start = 3 }
                    pieces[2] == "lostcities" && pieces.getOrNull(3) == "conditions" -> { target = conditions; start = 4 }
                    pieces[2] == "conditions" -> { target = conditions; start = 3 }
                    pieces[2] == "worldgen" && pieces.getOrNull(3) == "processor_list" -> { target = processors; start = 4 }
                    else -> return
                }
                val content = bytes(); require(content.size <= MAX_JSON) { "Original loot JSON exceeds bound: $name" }
                val json = JsonParser.parseString(content.toString(Charsets.UTF_8)).asJsonObject
                // Block-break drops are not source container contents or native item-mapping obligations.
                if (target === tables && json.get("type")?.asString?.substringAfter(':') in setOf("block", "entity")) return
                target[pieces[1] + ":" + pieces.drop(start).joinToString("/").removeSuffix(".json")] = json
            }
            for (path in archives) ZipFile(path.toFile()).use { zip ->
                partCrates = partCrates || zip.getEntry("net/mcreator/jeffscursedwalkingstructures/procedures/GunPartChestOnBlockRightClickedProcedure.class") != null
                val entries = zip.entries(); var count = 0
                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement(); require(++count <= 40_000)
                    if (!entry.isDirectory) accept(entry.name) { zip.getInputStream(entry).use { it.readNBytes(MAX_JSON + 1) } }
                }
            }
            if (cwPack != null) ZipFile(cwPack.toFile()).use { zip ->
                val entry = requireNotNull(zip.getEntry("overrides/config/openloader/data/NewLCLootV3.1.zip")) { "Pinned CW pack has no NewLCLootV3.1.zip" }
                val bytes = zip.getInputStream(entry).use { it.readNBytes(MAX_NESTED_ZIP + 1) }
                require(bytes.size <= MAX_NESTED_ZIP)
                ZipInputStream(ByteArrayInputStream(bytes)).use { nested ->
                    var count = 0
                    while (true) {
                        val item = nested.nextEntry ?: break; require(++count <= 2000)
                        if (!item.isDirectory) accept(item.name) { nested.readNBytes(MAX_JSON + 1) }
                    }
                }
            }
            return ApocalypseSourceLoot(tables, conditions, processors, partCrates, storedItems)
        }

        internal fun decode(tables: Map<String, String>, conditions: Map<String, String> = emptyMap(),
                            processors: Map<String, String> = emptyMap(), partCrates: Boolean = false): ApocalypseSourceLoot = ApocalypseSourceLoot(
            tables.mapValues { JsonParser.parseString(it.value).asJsonObject },
            conditions.mapValues { JsonParser.parseString(it.value).asJsonObject },
            processors.mapValues { JsonParser.parseString(it.value).asJsonObject }, partCrates)

        private fun allowed(owner: JsonObject, random: Random): Boolean = owner.getAsJsonArray("conditions").orEmpty().all {
            val condition = it.asJsonObject
            when (condition.get("condition").asString.substringAfter(':')) {
                "survives_explosion" -> true // Container-opening context has no explosion radius.
                "random_chance" -> random.nextFloat() < condition.get("chance").asFloat
                else -> error("Unsupported original loot condition: ${condition.get("condition")}")
            }
        }

        private fun number(value: JsonElement?, random: Random, default: Int): Int {
            if (value == null) return default
            if (value.isJsonPrimitive) return floor(value.asDouble).toInt()
            val obj = value.asJsonObject
            val type = obj.get("type")?.asString?.substringAfter(':')
            require(type == null || type == "uniform" || type == "constant") { "Unsupported original number provider: $type" }
            if (type == "constant") return floor(obj.get("value").asDouble).toInt()
            val min = floor(obj.get("min").asDouble).toInt(); val max = floor(obj.get("max").asDouble).toInt()
            require(min in -4096..4096 && max in min..4096)
            return if (min == max) min else min + random.nextInt(max - min + 1)
        }

        private fun mergeNbt(previous: String?, next: String): String {
            require(next.trim().startsWith('{') && next.trim().endsWith('}')) { "Original set_nbt must be a compound" }
            if (previous == null) return next
            return listOf(previous.trim().removePrefix("{").removeSuffix("}"), next.trim().removePrefix("{").removeSuffix("}"))
                .filter(String::isNotBlank).joinToString(",", "{", "}")
        }

        // A formal SNBT string-field token, not a heuristic for user text. Raw source SNBT is retained intact.
        private fun tagString(tag: String, key: String): String? = Regex("(?:^|[,{])\\s*(?:\"$key\"|$key)\\s*:\\s*(\"(?:\\\\.|[^\"\\\\])*\")")
            .findAll(tag).lastOrNull()?.groupValues?.get(1)?.let { JsonParser.parseString(it).asString }
        private fun namespace(id: String, default: String = "minecraft") = if (':' in id) id else "$default:$id"
        private fun component(value: JsonElement): String = if (value.isJsonPrimitive) value.asString else value.toString()
        private fun <T> immutableSet(values: Collection<T>): Set<T> = Collections.unmodifiableSet(values.toSet())
        private fun <T> immutableList(values: List<T>): List<T> = Collections.unmodifiableList(values.toList())
    }
}

private fun JsonArray?.orEmpty(): List<JsonElement> = this?.toList().orEmpty()
