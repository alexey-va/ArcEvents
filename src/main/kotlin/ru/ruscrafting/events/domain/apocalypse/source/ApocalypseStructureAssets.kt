package ru.ruscrafting.events.domain.apocalypse.source

import com.github.retrooper.packetevents.protocol.nbt.NBT
import com.github.retrooper.packetevents.protocol.nbt.NBTByteArray
import com.github.retrooper.packetevents.protocol.nbt.NBTCompound
import com.github.retrooper.packetevents.protocol.nbt.NBTIntArray
import com.github.retrooper.packetevents.protocol.nbt.NBTLimiter
import com.github.retrooper.packetevents.protocol.nbt.NBTList
import com.github.retrooper.packetevents.protocol.nbt.NBTLongArray
import com.github.retrooper.packetevents.protocol.nbt.NBTNumber
import com.github.retrooper.packetevents.protocol.nbt.NBTString
import com.github.retrooper.packetevents.protocol.nbt.NBTType
import com.github.retrooper.packetevents.protocol.nbt.serializer.DefaultNBTSerializer
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.Collections
import java.util.zip.GZIPInputStream
import java.util.zip.ZipFile

data class ApocalypseSourceConnector(
    val x: Int, val y: Int, val z: Int, val orientation: String,
    val name: String, val target: String, val pool: String, val finalState: String,
    val joint: String = "rollable",
)

/** Full source entity NBT is retained as SNBT; decoding never instantiates entities or executes commands. */
data class ApocalypseSourceEntity(val x: Double, val y: Double, val z: Double, val type: String, val nbt: String)

class ApocalypseSourceTemplate(
    val id: String,
    val sizeX: Int,
    val sizeY: Int,
    val sizeZ: Int,
    palette: List<String>,
    stateIndices: IntArray,
    blockNbt: Map<Int, String>,
    connectors: List<ApocalypseSourceConnector>,
    entities: List<ApocalypseSourceEntity>,
    val dataVersion: Int = 0,
    blockEntities: Map<Int, Map<String, Any?>> = emptyMap(),
    containerItems: Map<Int, List<ApocalypseSourceLootItem>> = emptyMap(),
) {
    val palette: List<String> = Collections.unmodifiableList(palette.toList())
    private val states = stateIndices.copyOf()
    private val nbt = Collections.unmodifiableMap(blockNbt.toMap())
    private val entityData = Collections.unmodifiableMap(blockEntities.mapValues { (_, data) ->
        Collections.unmodifiableMap(data.mapValues { immutableSourceData(it.value) })
    })
    val connectors: List<ApocalypseSourceConnector> = Collections.unmodifiableList(connectors.toList())
    val entities: List<ApocalypseSourceEntity> = Collections.unmodifiableList(entities.toList())
    private val inventory = containerItems.mapValues { Collections.unmodifiableList(it.value.toList()) }
    val authoredItems: List<ApocalypseSourceLootItem> = Collections.unmodifiableList(inventory.values.flatten())

    init {
        require(sizeX in 1..512 && sizeY in 1..512 && sizeZ in 1..512)
        require(sizeX.toLong() * sizeY * sizeZ <= 4_194_304 && states.size == sizeX * sizeY * sizeZ)
        require(states.all { it == -1 || it in this.palette.indices })
        require(nbt.keys.all { it in states.indices && states[it] >= 0 })
    }

    private fun index(x: Int, y: Int, z: Int): Int = (y * sizeZ + z) * sizeX + x
    fun blockState(x: Int, y: Int, z: Int): String? =
        if (x in 0 until sizeX && y in 0 until sizeY && z in 0 until sizeZ) palette.getOrNull(states[index(x, y, z)]) else null

    fun blockNbt(x: Int, y: Int, z: Int): String? =
        if (x in 0 until sizeX && y in 0 until sizeY && z in 0 until sizeZ) nbt[index(x, y, z)] else null

    fun blockEntity(x: Int, y: Int, z: Int): Map<String, Any?>? =
        if (x in 0 until sizeX && y in 0 until sizeY && z in 0 until sizeZ) entityData[index(x, y, z)] else null

    fun containerItems(x: Int, y: Int, z: Int): List<ApocalypseSourceLootItem> =
        if (x in 0 until sizeX && y in 0 until sizeY && z in 0 until sizeZ) inventory[index(x, y, z)].orEmpty() else emptyList()

    fun forEachBlockEntity(action: (x: Int, y: Int, z: Int, data: Map<String, Any?>) -> Unit) {
        entityData.forEach { (index, data) -> action(index % sizeX, index / (sizeX * sizeZ), index / sizeX % sizeZ, data) }
    }

    fun forEachBlock(action: (x: Int, y: Int, z: Int, state: String, nbt: String?) -> Unit) {
        for (y in 0 until sizeY) for (z in 0 until sizeZ) for (x in 0 until sizeX) {
            val index = index(x, y, z)
            if (states[index] >= 0) action(x, y, z, palette[states[index]], nbt[index])
        }
    }
}

data class ApocalypseSourceArchive(val id: String, val filename: String, val url: String, val sha256: String,
    val maximumBytes: Long = 16L * 1024 * 1024)

/** Immutable parsed data from externally cached, pinned archives; no original assets are bundled. */
class ApocalypseStructureAssets private constructor(
    templates: Map<String, ApocalypseSourceTemplate>,
    pools: Map<String, ApocalypseSourcePool>,
    structures: Map<String, ApocalypseSourceStructure>,
    val city: ApocalypseLostCityAssets,
    override val loot: ApocalypseSourceLoot,
    sourceHashes: Map<String, String>,
) : ApocalypseJigsawAssets {
    val templates: Map<String, ApocalypseSourceTemplate> = Collections.unmodifiableMap(templates.toMap())
    override val pools: Map<String, ApocalypseSourcePool> = Collections.unmodifiableMap(pools.toMap())
    override val structures: Map<String, ApocalypseSourceStructure> = Collections.unmodifiableMap(structures.toMap())
    val sourceHashes: Map<String, String> = Collections.unmodifiableMap(sourceHashes.toMap())
    override fun template(id: String): ApocalypseSourceTemplate? = templates[id]

    companion object {
        val pinnedSources: List<ApocalypseSourceArchive> = Collections.unmodifiableList(listOf(
            ApocalypseSourceArchive("jcws", "jeffs_cursed_walking_structures-3.3.0-forge-1.20.1.jar",
                "https://mediafilez.forgecdn.net/files/8502/762/jeffs_cursed_walking_structures-3.3.0-forge-1.20.1.jar",
                "80da781a4c097f93d0be3f2139228508124f2af678abd036479de9f1f8b82e23"),
            ApocalypseSourceArchive("keerdm", "Keerdm Zombie Apocalypse Essentials (TACZ) 1.42.jar",
                "https://mediafilez.forgecdn.net/files/7740/113/Keerdm%20Zombie%20Apocalypse%20Essentials%20%28TACZ%29%201.42.jar",
                "1b9db7636db3a1bcfb015d19c6d44b663e454f05e12e472d128c60f2b5214d3e"),
            ApocalypseSourceArchive("lostcities", "lostcities-1.20-7.4.11.jar",
                "https://mediafilez.forgecdn.net/files/7432/706/lostcities-1.20-7.4.11.jar",
                "7a3f3c55de1b869380fdbdef4fa8865bc1daf3ae6c66fab1056ea44d6534b4fb"),
            ApocalypseSourceArchive("cwpack", "Cursed Walking-v3.3 (patch).zip",
                "https://mediafilez.forgecdn.net/files/8603/430/Cursed%20Walking-v3.3%20%28patch%29.zip",
                "9e27be83c25a78d13508134be65d2fe70efb389ce015d2a9f54f8dd0f9861901", 350L * 1024 * 1024),
            ApocalypseSourceArchive("deepvoid", "the_deep_void-1.28-forge-1.20.1.jar",
                "https://mediafilez.forgecdn.net/files/5788/605/the_deep_void-1.28-forge-1.20.1.jar",
                "05b5c6cd8b5dd2f78528e3b8d6a183afe51476e6897b493c55c2a6f0974e7d69"),
            ApocalypseSourceArchive("depths", "the_deeper_void-2.0.5-forge-1.20.1.jar",
                "https://mediafilez.forgecdn.net/files/8145/376/the_deeper_void-2.0.5-forge-1.20.1.jar",
                "d20a72131f49c91d03ab709ac7f08451d95ccf84d637389ed0a6629b0ef68f08"),
        ))

        /** Caller verifies pinned checksums and performs this bounded archive I/O away from gameplay. */
        fun load(jcws: Path, keerdm: Path, lostCities: Path, cwPack: Path? = null,
                 extraLootArchives: List<Path> = emptyList()): ApocalypseStructureAssets {
            val templates = linkedMapOf<String, ApocalypseSourceTemplate>()
            val pools = linkedMapOf<String, ApocalypseSourcePool>()
            val structures = linkedMapOf<String, ApocalypseSourceStructure>()
            ZipFile(jcws.toFile()).use { zip ->
                val entries = zip.entries()
                var count = 0
                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()
                    require(++count <= 20_000) { "Too many JCWS archive entries" }
                    if (entry.isDirectory) continue
                    val pieces = entry.name.split('/')
                    if (pieces.size < 4 || pieces[0] != "data") continue
                    if (pieces[2] == "structures" && entry.name.endsWith(".nbt")) {
                        val id = pieces[1] + ":" + pieces.drop(3).joinToString("/").removeSuffix(".nbt")
                        val bytes = zip.getInputStream(entry).use { input ->
                            GZIPInputStream(input).use { it.readNBytes(MAX_NBT_BYTES + 1) }
                        }
                        require(bytes.size <= MAX_NBT_BYTES) { "NBT too large: $id" }
                        templates[id] = decodeTemplate(id, bytes)
                    } else if (pieces[2] == "worldgen" && pieces.size >= 5 && entry.name.endsWith(".json")) {
                        val kind = pieces[3]
                        if (kind != "template_pool" && kind != "structure") continue
                        val id = pieces[1] + ":" + pieces.drop(4).joinToString("/").removeSuffix(".json")
                        val json = zip.getInputStream(entry).use { input ->
                            val bytes = input.readNBytes(MAX_JSON_BYTES + 1)
                            require(bytes.size <= MAX_JSON_BYTES) { "JSON too large: $id" }
                            JsonParser.parseString(bytes.toString(Charsets.UTF_8)).asJsonObject
                        }
                        if (kind == "template_pool") pools[id] = decodePool(json)
                        else if (json.has("start_pool")) structures[id] = decodeStructure(json)
                    }
                }
            }
            require(templates.isNotEmpty()) { "No JCWS structure templates" }
            val loot = ApocalypseSourceLoot.load(listOf(lostCities, keerdm, jcws) + extraLootArchives, cwPack,
                templates.values.flatMap { it.authoredItems })
            val hashes = linkedMapOf("jcws" to hash(jcws), "keerdm" to hash(keerdm), "lostcities" to hash(lostCities))
            cwPack?.let { hashes["cwpack"] = hash(it) }
            extraLootArchives.forEachIndexed { index, path -> hashes[pinnedSources.getOrNull(index + 4)?.id ?: "loot$index"] = hash(path) }
            return ApocalypseStructureAssets(templates, pools, structures, ApocalypseLostCityAssets.load(lostCities, keerdm, loot), loot, hashes)
        }

        private const val MAX_NBT_BYTES = 64 * 1024 * 1024
        internal const val MAX_JSON_BYTES = 2 * 1024 * 1024

        internal fun decodeTemplate(id: String, bytes: ByteArray): ApocalypseSourceTemplate {
            require(bytes.size <= MAX_NBT_BYTES)
            val input = DataInputStream(ByteArrayInputStream(bytes))
            val limiter = object : NBTLimiter {
                private var size = 0L
                private var depth = 0
                override fun increment(bytes: Int) { require(bytes >= 0); size += bytes; require(size <= MAX_NBT_BYTES * 4L) }
                override fun checkReadability(bytes: Int) { require(bytes >= 0 && bytes <= input.available()) }
                override fun enterDepth() { require(++depth <= 64) { "NBT nesting too deep: $id" } }
                override fun exitDepth() { require(depth-- > 0) }
            }
            val root = DefaultNBTSerializer.INSTANCE.deserializeTag(limiter, input) as? NBTCompound
                ?: error("Structure root is not a compound: $id")
            require(input.available() == 0) { "Trailing NBT bytes: $id" }
            val size = root.getNumberTagListTagOrThrow("size").tags.map { it.asInt }
            require(size.size == 3 && size.all { it in 1..512 } && size.fold(1L) { a, b -> a * b } <= 4_194_304)
            val paletteTags = root.getCompoundListTagOrNull("palette")
                ?: ((root.getTagOrNull("palettes") as? NBTList<*>)?.tags?.firstOrNull() as? NBTList<*>)
            val palette = requireNotNull(paletteTags) { "Missing palette: $id" }.tags.map { tag ->
                val state = tag as NBTCompound
                val properties = state.getCompoundTagOrNull("Properties")?.tags?.entries?.sortedBy { it.key }
                    ?.joinToString(",") { (key, value) -> "$key=${(value as NBTString).value}" }.orEmpty()
                state.getStringTagValueOrThrow("Name") + if (properties.isEmpty()) "" else "[$properties]"
            }
            val indices = IntArray(size[0] * size[1] * size[2]) { -1 }
            val nbt = linkedMapOf<Int, String>()
            val blockEntities = linkedMapOf<Int, Map<String, Any?>>()
            val containerItems = linkedMapOf<Int, List<ApocalypseSourceLootItem>>()
            val connectors = mutableListOf<ApocalypseSourceConnector>()
            root.getCompoundListTagOrThrow("blocks").tags.forEach { block ->
                val pos = block.getNumberTagListTagOrThrow("pos").tags.map { it.asInt }
                require(pos.size == 3 && pos.indices.all { pos[it] in 0 until size[it] }) { "Block outside template: $id" }
                val index = (pos[1] * size[2] + pos[2]) * size[0] + pos[0]
                val state = block.getNumberTagValueOrThrow("state").toInt()
                require(state in palette.indices && indices[index] == -1) { "Invalid/duplicate block: $id" }
                indices[index] = state
                block.getCompoundTagOrNull("nbt")?.let { tag ->
                    nbt[index] = snbt(tag)
                    blockEntities[index] = tag.tags.mapValues { plain(it.value) }
                    // Preserve authored contents only for normal loot inventories. Furnace/smoker/bookshelf slots
                    // have different native semantics and remain outside this finite chest-loot adapter.
                    if (palette[state].substringBefore('[') in setOf("minecraft:chest", "minecraft:trapped_chest", "minecraft:barrel",
                            "lootr:lootr_chest", "lootr:lootr_barrel", "jeffs_cursed_walking_structures:gun_storage_unit")) {
                        containerItems[index] = tag.getCompoundListTagOrNull("Items")?.tags.orEmpty().mapNotNull { item ->
                            val itemId = item.getStringTagValueOrDefault("id", "minecraft:air")
                            val count = (item.getNumberTagValueOrDefault("Count", 0) ?: 0).toInt()
                            if (itemId == "minecraft:air" || count <= 0) null else {
                                require(count <= 4096)
                                val data = item.getCompoundTagOrNull("tag")
                                fun identity(key: String) = data?.getStringTagValueOrDefault(key, "")?.takeIf(String::isNotBlank)
                                ApocalypseSourceLootItem(itemId, count, data?.let(::snbt), identity("GunId"), identity("AmmoId"), identity("AttachmentId"))
                            }
                        }
                    }
                    if (palette[state].substringBefore('[') == "minecraft:jigsaw") {
                        connectors += ApocalypseSourceConnector(pos[0], pos[1], pos[2],
                            palette[state].substringAfter("orientation=", "north_up").substringBefore(',').substringBefore(']'),
                            tag.getStringTagValueOrDefault("name", "minecraft:empty"),
                            tag.getStringTagValueOrDefault("target", "minecraft:empty"),
                            tag.getStringTagValueOrDefault("pool", "minecraft:empty"),
                            tag.getStringTagValueOrDefault("final_state", "minecraft:air"),
                            tag.getStringTagValueOrDefault("joint", "rollable"))
                    }
                }
            }
            val entities = root.getCompoundListTagOrNull("entities")?.tags.orEmpty().map { entity ->
                val pos = entity.getNumberTagListTagOrThrow("pos").tags.map { it.asDouble }
                require(pos.size == 3 && pos.all(Double::isFinite))
                val tag = entity.getCompoundTagOrThrow("nbt")
                ApocalypseSourceEntity(pos[0], pos[1], pos[2], tag.getStringTagValueOrThrow("id"), snbt(tag))
            }
            return ApocalypseSourceTemplate(id, size[0], size[1], size[2], palette, indices, nbt, connectors, entities,
                (root.getNumberTagValueOrDefault("DataVersion", 0) ?: 0).toInt(), blockEntities, containerItems)
        }

        private fun decodePool(json: JsonObject): ApocalypseSourcePool = ApocalypseSourcePool(
            json.get("fallback")?.asString ?: "minecraft:empty",
            json.getAsJsonArray("elements").map { raw ->
                val weighted = raw.asJsonObject
                val element = weighted.getAsJsonObject("element")
                val location = element.get("location")?.asString ?: "minecraft:empty"
                val processors = element.get("processors")
                ApocalypsePoolElement(location, weighted.get("weight").asInt,
                    processors?.takeIf { it.isJsonPrimitive }?.asString,
                    element.get("projection")?.asString ?: "rigid")
            },
        )

        private fun decodeStructure(json: JsonObject): ApocalypseSourceStructure {
            val height = json.get("start_height")?.takeIf { it.isJsonObject }?.asJsonObject
            val value = height?.get("value")?.takeIf { it.isJsonObject }?.asJsonObject ?: height
            return ApocalypseSourceStructure(json.get("start_pool").asString, json.get("size")?.asInt ?: 0,
                json.get("max_distance_from_center")?.asInt ?: 80, value?.get("absolute")?.asInt ?: 0,
                json.get("project_start_to_heightmap")?.asString)
        }

        private fun hash(path: Path): String {
            val digest = MessageDigest.getInstance("SHA-256")
            Files.newInputStream(path).use { input ->
                val buffer = ByteArray(16_384)
                while (true) { val count = input.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }

        /** PacketEvents.toString is diagnostic, not SNBT; retain numeric types and quote strings explicitly. */
        internal fun snbt(tag: NBT): String = when (tag) {
            is NBTCompound -> tag.tags.entries.sortedBy { it.key }.joinToString(",", "{", "}") { quote(it.key) + ":" + snbt(it.value) }
            is NBTList<*> -> tag.tags.joinToString(",", "[", "]") { snbt(it) }
            is NBTString -> quote(tag.value)
            is NBTByteArray -> tag.value.joinToString(",", "[B;", "]") { "${it}b" }
            is NBTIntArray -> tag.value.joinToString(",", "[I;", "]")
            is NBTLongArray -> tag.value.joinToString(",", "[L;", "]") { "${it}L" }
            is NBTNumber -> tag.asNumber.toString() + when (tag.type) {
                NBTType.BYTE -> "b"; NBTType.SHORT -> "s"; NBTType.LONG -> "L"; NBTType.FLOAT -> "f"; NBTType.DOUBLE -> "d"; else -> ""
            }
            else -> error("Unsupported structure NBT tag: ${tag.type}")
        }

        private fun quote(value: String): String = "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

        private fun plain(tag: NBT): Any? = when (tag) {
            is NBTCompound -> tag.tags.mapValues { plain(it.value) }
            is NBTList<*> -> tag.tags.map(::plain)
            is NBTString -> tag.value
            is NBTNumber -> tag.asNumber
            is NBTByteArray -> tag.value.toList()
            is NBTIntArray -> tag.value.toList()
            is NBTLongArray -> tag.value.toList()
            else -> null
        }
    }
}

private fun immutableSourceData(value: Any?): Any? = when (value) {
    is Map<*, *> -> Collections.unmodifiableMap(value.entries.associate { it.key.toString() to immutableSourceData(it.value) })
    is List<*> -> Collections.unmodifiableList(value.map(::immutableSourceData))
    else -> value
}
