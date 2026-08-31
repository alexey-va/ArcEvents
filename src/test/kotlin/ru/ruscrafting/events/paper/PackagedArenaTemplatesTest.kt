package ru.ruscrafting.events.paper

import com.google.gson.JsonParser
import com.sk89q.worldedit.LocalConfiguration
import com.sk89q.worldedit.WorldEdit
import com.sk89q.worldedit.event.platform.PlatformsRegisteredEvent
import com.sk89q.worldedit.extension.platform.Capability
import com.sk89q.worldedit.extension.platform.Platform
import com.sk89q.worldedit.extension.platform.Preference
import com.sk89q.worldedit.extent.clipboard.io.BuiltInClipboardFormat
import com.sk89q.worldedit.extent.clipboard.io.ClipboardFormats
import com.sk89q.worldedit.registry.state.BooleanProperty
import com.sk89q.worldedit.registry.state.EnumProperty
import com.sk89q.worldedit.registry.state.IntegerProperty
import com.sk89q.worldedit.registry.state.Property
import com.sk89q.worldedit.util.formatting.text.TextComponent
import com.sk89q.worldedit.util.io.WorldEditResourceLoader
import com.sk89q.worldedit.util.translation.TranslationManager
import com.sk89q.worldedit.world.DataFixer
import com.sk89q.worldedit.world.block.BlockType
import com.sk89q.worldedit.world.registry.BlockRegistry
import com.sk89q.worldedit.world.registry.Registries
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.enginehub.linbus.stream.LinBinaryIO
import org.enginehub.linbus.tree.LinCompoundTag
import org.enginehub.linbus.tree.LinListTag
import org.enginehub.linbus.tree.LinRootEntry
import org.enginehub.linbus.tree.LinStringTag
import org.enginehub.linbus.tree.LinTag
import org.enginehub.linbus.tree.LinTagType
import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Path
import java.security.MessageDigest
import java.util.OptionalInt
import java.util.zip.GZIPInputStream
import java.util.zip.InflaterInputStream

class PackagedArenaTemplatesTest : StringSpec({
    "arena registry pins reviewed classpath and external assets" {
        PackagedArenaTemplates.ids shouldContainExactlyInAnyOrder EXPECTED.keys

        EXPECTED.forEach { (id, expected) ->
            val template = requireNotNull(PackagedArenaTemplates.find(id))
            withClue(id) {
                template.format shouldBe expected.format
                template.title shouldBe expected.title
                template.source shouldBe expected.source
                template.sourceArchiveSha256 shouldBe expected.sourceArchiveSha256
                template.author shouldBe expected.author
                template.license shouldBe expected.license
                template.licenseUrl shouldBe expected.licenseUrl
                listOf(template.width, template.height, template.length) shouldBe expected.dimensions
                template.cropMinimum shouldBe expected.cropMinimum
                template.cropMaximum shouldBe expected.cropMaximum
                template.assetSource shouldBe expected.assetSource
                template.files.map { Triple(it.resource, it.destination, it.sha256) } shouldBe expected.files
            }
            if (template.assetSource == PackagedArenaSource.CLASSPATH) {
                template.files.forEach { file ->
                    withClue(file.resource) { sha256(resourceBytes(file.resource)) shouldBe file.sha256 }
                }
            } else {
                template.files.forEach { file ->
                    withClue(file.resource) {
                        PackagedArenaTemplatesTest::class.java.classLoader.getResource(file.resource) shouldBe null
                    }
                }
            }
        }

        val practice = requireNotNull(PackagedArenaTemplates.find(PRACTICE_TEMPLATE_ID))
        withClue("practice template must not package mutable world metadata") {
            practice.files.none {
                it.destination == "level.dat" || it.resource.endsWith("/level.dat")
            } shouldBe true
        }
    }

    "packaged schematics match their reviewed format crop and executable-data boundary" {
        val testPlatform = installWorldEditReaderPlatform()
        try {
            EXPECTED.filterValues { it.worldEditFormat != null }.forEach { (id, expected) ->
                val template = requireNotNull(PackagedArenaTemplates.find(id))
                val bytes = resourceBytes(template.files.single().resource)
                val expectedFormat = BuiltInClipboardFormat.valueOf(requireNotNull(expected.worldEditFormat))
                val detected = ClipboardFormats.findByInputStream { ByteArrayInputStream(bytes) }
                withClue("$id format") { detected shouldBe expectedFormat }

                val clipboard = ByteArrayInputStream(bytes).use { input ->
                    expectedFormat.getReader(input).use { it.read() }
                }
                withClue("$id dimensions") {
                    listOf(clipboard.dimensions.x(), clipboard.dimensions.y(), clipboard.dimensions.z()) shouldBe expected.dimensions
                }

                val cropMinimum = requireNotNull(expected.cropMinimum)
                val cropMaximum = requireNotNull(expected.cropMaximum)
                val sourceMinimum = clipboard.region.minimumPoint.add(cropMinimum.x, cropMinimum.y, cropMinimum.z)
                val sourceMaximum = clipboard.region.minimumPoint.add(cropMaximum.x, cropMaximum.y, cropMaximum.z)
                withClue("$id crop") {
                    clipboard.region.contains(sourceMinimum) shouldBe true
                    clipboard.region.contains(sourceMaximum) shouldBe true
                    listOf(
                        sourceMaximum.x() - sourceMinimum.x() + 1,
                        sourceMaximum.y() - sourceMinimum.y() + 1,
                        sourceMaximum.z() - sourceMinimum.z() + 1,
                    ) shouldBe expected.cropDimensions
                }

                for (x in sourceMinimum.x()..sourceMaximum.x()) {
                    for (y in sourceMinimum.y()..sourceMaximum.y()) {
                        for (z in sourceMinimum.z()..sourceMaximum.z()) {
                            val block = clipboard.getFullBlock(com.sk89q.worldedit.math.BlockVector3.at(x, y, z))
                            withClue("$id block $x,$y,$z") {
                                (block.blockType.id() in COMMAND_BLOCK_IDS) shouldBe false
                                EXECUTABLE_NBT.containsMatchIn(block.nbtReference?.value?.toString().orEmpty()) shouldBe false
                            }
                        }
                    }
                }
            }
        } finally {
            WorldEdit.getInstance().platformManager.unregister(testPlatform)
        }
        withClue("headless WorldEdit platform must not leak into later tests") {
            runCatching {
                WorldEdit.getInstance().platformManager.queryCapability(Capability.WORLD_EDITING)
            }.isFailure shouldBe true
        }
    }

    "packaged practice region has an exact valid header and no executable chunk data" {
        val template = requireNotNull(PackagedArenaTemplates.find(PRACTICE_TEMPLATE_ID))
        val file = template.files.single()
        file.destination shouldBe PRACTICE_REGION_DESTINATION

        val bytes = resourceBytes(file.resource)
        withClue("practice region byte length") { bytes.size shouldBe PRACTICE_REGION_SIZE }
        withClue("practice region header") {
            sha256(bytes.copyOfRange(0, ANVIL_HEADER_BYTES)) shouldBe PRACTICE_REGION_HEADER_SHA256
        }

        val chunks = readAnvilChunks(bytes)
        withClue("practice occupied chunk slots") { chunks.size shouldBe PRACTICE_OCCUPIED_CHUNKS }
        chunks.forEach { chunk ->
            withClue("practice chunk slot ${chunk.slot}") {
                chunk.compression shouldBe ANVIL_ZLIB_COMPRESSION
                val root = DataInputStream(ByteArrayInputStream(chunk.nbt)).use { input ->
                    LinRootEntry.readFrom(LinBinaryIO.read(input))
                }
                executableNbtEvidence(root.value()) shouldBe null
            }
        }
    }
})

private data class ExpectedTemplate(
    val format: PackagedArenaFormat,
    val title: String,
    val source: String,
    val sourceArchiveSha256: String,
    val author: String,
    val license: String,
    val licenseUrl: String,
    val dimensions: List<Int>,
    val cropMinimum: TemplateBlockPoint?,
    val cropMaximum: TemplateBlockPoint?,
    val cropDimensions: List<Int> = emptyList(),
    val worldEditFormat: String? = null,
    val files: List<Triple<String, String, String>>,
    val assetSource: PackagedArenaSource = PackagedArenaSource.CLASSPATH,
)

private val EXPECTED = linkedMapOf(
    "japanese-lobby-v1" to ExpectedTemplate(
        format = PackagedArenaFormat.SPONGE_V3_SCHEMATIC,
        title = "Japanese Lobby",
        source = "https://www.planetminecraft.com/project/japanese-lobby-6691829/",
        sourceArchiveSha256 = "dc612a7a173869f4893acfbfb0064df74cf12bbea235d90dfdaf17c15ae57dba",
        author = "CedricD0812",
        license = "CC BY 4.0",
        licenseUrl = "https://creativecommons.org/licenses/by/4.0/",
        dimensions = listOf(69, 65, 60),
        cropMinimum = TemplateBlockPoint(0, 0, 0),
        cropMaximum = TemplateBlockPoint(68, 35, 59),
        cropDimensions = listOf(69, 36, 60),
        worldEditFormat = "SPONGE_V3_SCHEMATIC",
        files = listOf(
            Triple(
                "arena-templates/japanese-lobby-v1/map.schem",
                "map.schem",
                "dc612a7a173869f4893acfbfb0064df74cf12bbea235d90dfdaf17c15ae57dba",
            ),
        ),
    ),
    "edged-mansion-v1" to ExpectedTemplate(
        format = PackagedArenaFormat.MCEDIT_SCHEMATIC,
        title = "Edged Mansion",
        source = "https://www.planetminecraft.com/project/edged-mansion/",
        sourceArchiveSha256 = "b49b0c9ab8717acc94636e9ed029f2e19a2d887593520c16b4248088c78d728b",
        author = "kostahansen",
        license = "CC BY 4.0",
        licenseUrl = "https://creativecommons.org/licenses/by/4.0/",
        dimensions = listOf(38, 32, 45),
        cropMinimum = TemplateBlockPoint(3, 0, 1),
        cropMaximum = TemplateBlockPoint(30, 29, 41),
        cropDimensions = listOf(28, 30, 41),
        worldEditFormat = "MCEDIT_SCHEMATIC",
        files = listOf(
            Triple(
                "arena-templates/edged-mansion-v1/map.schematic",
                "map.schematic",
                "b49b0c9ab8717acc94636e9ed029f2e19a2d887593520c16b4248088c78d728b",
            ),
        ),
    ),
    "practice-yard-v1" to ExpectedTemplate(
        format = PackagedArenaFormat.ANVIL_WORLD,
        title = "Practice Map Build",
        source = "https://www.planetminecraft.com/project/pratice-map-build/",
        sourceArchiveSha256 = "d9e69297e8b247277346f441f9c3c7551e4d24982e4372f7e051af667e6f4e46",
        author = "Zyumie",
        license = "CC BY 4.0",
        licenseUrl = "https://creativecommons.org/licenses/by/4.0/",
        dimensions = listOf(144, 50, 160),
        cropMinimum = null,
        cropMaximum = null,
        files = listOf(
            Triple(
                "arena-templates/practice-yard-v1/region/r.-1.0.mca",
                "region/r.-1.0.mca",
                "60f4fc5ef6de4ff8741fd2cbe6d0294a1cd0d2423ad2b2ed9ae2710a8c4a6160",
            ),
        ),
    ),
    "ttt-minecraft-b5-v1" to ExpectedTemplate(
        format = PackagedArenaFormat.SPONGE_V2_SCHEMATIC,
        title = "TTT Minecraft B5",
        source = "https://www.planetminecraft.com/project/ttt_minecraft_b5-minecraft-map/",
        sourceArchiveSha256 = "93f385b9b6916a81dd425cbce3bdfd7c113447cf1192c5e7045c47cb1493ed6e",
        author = "SukovicM (original GMod map by finniespin)",
        license = "Server use only; no redistribution grant",
        licenseUrl = "https://www.planetminecraft.com/project/ttt_minecraft_b5-minecraft-map/",
        dimensions = listOf(128, 93, 128),
        cropMinimum = TemplateBlockPoint(0, 0, 0),
        cropMaximum = TemplateBlockPoint(127, 80, 127),
        cropDimensions = listOf(128, 81, 128),
        files = listOf(
            Triple(
                "arena-templates/ttt-minecraft-b5-v1/map.schem",
                "map.schem",
                "b072374dde6c6bbc136fd394e469c1f13d7e66a304b2371944a448bcad1ee289",
            ),
        ),
        assetSource = PackagedArenaSource.DATA_FOLDER,
    ),
)

private val COMMAND_BLOCK_IDS = setOf(
    "minecraft:command_block",
    "minecraft:chain_command_block",
    "minecraft:repeating_command_block",
)
private val EXECUTABLE_NBT =
    Regex("(?i)(run_command|suggest_command|minecraft:(?:command_block|chain_command_block|repeating_command_block)|(?:^|[,{ ])command[=:])")

private fun resourceBytes(path: String): ByteArray =
    requireNotNull(PackagedArenaTemplatesTest::class.java.classLoader.getResourceAsStream(path)) {
        "Missing packaged arena resource $path"
    }.use { it.readBytes() }

private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
    .digest(bytes)
    .joinToString("") { byte -> "%02x".format(byte) }

private fun installWorldEditReaderPlatform(): Platform {
    val properties = registerHeadlessBlockTypes()
    val manager = WorldEdit.getInstance().platformManager
    check(runCatching { manager.queryCapability(Capability.WORLD_EDITING) }.isFailure) {
        "Packaged arena reader test requires exclusive WorldEdit platform state"
    }

    val blockRegistry = mockk<BlockRegistry>(relaxed = true)
    every { blockRegistry.getProperties(any()) } answers {
        properties[firstArg<BlockType>().id()].orEmpty()
    }
    every { blockRegistry.getInternalBlockStateId(any()) } returns OptionalInt.empty()
    every { blockRegistry.getRichName(any()) } answers { TextComponent.of(firstArg<BlockType>().id()) }

    val registries = mockk<Registries>(relaxed = true)
    every { registries.blockRegistry } returns blockRegistry

    val platform = mockk<Platform>(relaxed = true)
    val configuration = HeadlessWorldEditConfiguration()
    val translationManager = mockk<TranslationManager>()
    every { translationManager.convertText(any(), any()) } answers { firstArg() }
    every { platform.version } returns "7.3.18-test"
    every { platform.capabilities } returns mapOf(
        Capability.GAME_HOOKS to Preference.PREFERRED,
        Capability.CONFIGURATION to Preference.PREFERRED,
        Capability.WORLD_EDITING to Preference.PREFERRED,
    )
    every { platform.configuration } returns configuration
    every { platform.translationManager } returns translationManager
    every { platform.resourceLoader } returns WorldEditResourceLoader(WorldEdit.getInstance())
    every { platform.dataVersion } returns 3953
    every { platform.dataFixer } returns PassthroughDataFixer
    every { platform.registries } returns registries
    manager.register(platform)
    manager.handlePlatformsRegistered(PlatformsRegisteredEvent())
    return platform
}

private class HeadlessWorldEditConfiguration : LocalConfiguration() {
    init {
        saveDir = "schematics"
    }

    override fun load() = Unit

    override fun getWorkingDirectoryPath(): Path = Path.of("build/tmp/worldedit-reader")
}

private fun registerHeadlessBlockTypes(): Map<String, Map<String, Property<*>>> {
    val legacy = JsonParser.parseString(resourceText(WORLDEDIT_LEGACY_RESOURCE))
        .asJsonObject
        .getAsJsonObject("blocks")
        .entrySet()
        .map { it.value.asString }
    val sponge = spongePalette(resourceBytes(JAPANESE_SCHEMATIC_RESOURCE))
    val states = legacy + sponge
    val properties = propertiesByBlock(states)

    val currentBlockIds = JsonParser.parseString(resourceText(WORLDEDIT_BLOCKS_RESOURCE))
        .asJsonArray
        .map { it.asJsonObject.get("id").asString }
    val ids = (currentBlockIds + states.map(::parseBlockState).map { it.first }).toSortedSet()
    ids.forEach { id ->
        if (id !in BlockType.REGISTRY.keySet()) {
            BlockType.REGISTRY.register(id, BlockType(id))
        }
    }
    check("minecraft:air" in BlockType.REGISTRY.keySet()) { "Headless WorldEdit registry did not register air" }
    return properties
}

private fun propertiesByBlock(states: List<String>): Map<String, Map<String, Property<*>>> {
    val values = linkedMapOf<String, MutableMap<String, MutableSet<String>>>()
    states.forEach { state ->
        val (id, stateValues) = parseBlockState(state)
        stateValues.forEach { (name, value) ->
            values.getOrPut(id, ::linkedMapOf).getOrPut(name, ::linkedSetOf).add(value)
        }
    }
    return values.mapValues { (_, blockValues) ->
        blockValues.toSortedMap().mapValues { (name, rawValues) ->
            property(name, rawValues)
        }
    }
}

private fun property(name: String, rawValues: Set<String>): Property<*> {
    val sorted = rawValues.sorted()
    if (sorted.all { it == "false" || it == "true" }) {
        return BooleanProperty(name, sorted.map(String::toBoolean))
    }
    val integers = sorted.map(String::toIntOrNull)
    if (integers.all { it != null }) {
        return IntegerProperty(name, integers.filterNotNull().sorted())
    }
    return EnumProperty(name, sorted)
}

private fun parseBlockState(value: String): Pair<String, Map<String, String>> {
    val bracket = value.indexOf('[')
    if (bracket < 0) return value to emptyMap()
    require(value.endsWith(']')) { "Malformed block state $value" }
    val properties = value.substring(bracket + 1, value.lastIndex)
        .split(',')
        .associate { entry ->
            val parts = entry.split('=', limit = 2)
            require(parts.size == 2) { "Malformed block property $entry in $value" }
            parts[0] to parts[1]
        }
    return value.substring(0, bracket) to properties
}

private fun spongePalette(bytes: ByteArray): Set<String> {
    val root = DataInputStream(GZIPInputStream(ByteArrayInputStream(bytes))).use { input ->
        LinRootEntry.readFrom(LinBinaryIO.read(input))
    }
    val schematic = root.value().getTag("Schematic", LinTagType.compoundTag())
    val blocks = schematic.getTag("Blocks", LinTagType.compoundTag())
    return blocks.getTag("Palette", LinTagType.compoundTag()).value().keys
}

private fun resourceText(path: String): String = resourceBytes(path).toString(Charsets.UTF_8)

private data class AnvilChunk(
    val slot: Int,
    val compression: Int,
    val nbt: ByteArray,
)

private fun readAnvilChunks(bytes: ByteArray): List<AnvilChunk> {
    require(bytes.size >= ANVIL_HEADER_BYTES && bytes.size % ANVIL_SECTOR_BYTES == 0) {
        "Invalid Anvil region length ${bytes.size}"
    }

    val header = ByteBuffer.wrap(bytes, 0, ANVIL_HEADER_BYTES).order(ByteOrder.BIG_ENDIAN)
    val claimedSectors = mutableSetOf(0, 1)
    return buildList {
        repeat(ANVIL_LOCATION_ENTRIES) { slot ->
            val location = header.getInt(slot * Int.SIZE_BYTES)
            val sectorOffset = location ushr 8
            val sectorCount = location and 0xff
            if (sectorOffset == 0 && sectorCount == 0) return@repeat

            require(sectorOffset >= 2) { "Chunk slot $slot points into the Anvil header" }
            require(sectorCount > 0) { "Chunk slot $slot has an offset without sectors" }
            val byteOffset = sectorOffset.toLong() * ANVIL_SECTOR_BYTES
            val byteLimit = (sectorOffset.toLong() + sectorCount) * ANVIL_SECTOR_BYTES
            require(byteLimit <= bytes.size) { "Chunk slot $slot points past end of region" }
            repeat(sectorCount) { index ->
                require(claimedSectors.add(sectorOffset + index)) {
                    "Chunk slot $slot overlaps sector ${sectorOffset + index}"
                }
            }

            val record = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
            record.position(byteOffset.toInt())
            val recordLength = record.int
            require(recordLength in 2..(sectorCount * ANVIL_SECTOR_BYTES - Int.SIZE_BYTES)) {
                "Chunk slot $slot has invalid record length $recordLength"
            }
            val compressionFlag = record.get().toInt() and 0xff
            require(compressionFlag and ANVIL_EXTERNAL_STREAM_FLAG == 0) {
                "Chunk slot $slot references a missing external .mcc stream"
            }
            val compression = compressionFlag and ANVIL_COMPRESSION_MASK
            val compressed = ByteArray(recordLength - 1)
            record.get(compressed)
            val nbt = decompressAnvilChunk(slot, compression, compressed)
            add(AnvilChunk(slot, compression, nbt))
        }
    }
}

private fun decompressAnvilChunk(slot: Int, compression: Int, bytes: ByteArray): ByteArray {
    val stream: InputStream = when (compression) {
        ANVIL_GZIP_COMPRESSION -> GZIPInputStream(ByteArrayInputStream(bytes))
        ANVIL_ZLIB_COMPRESSION -> InflaterInputStream(ByteArrayInputStream(bytes))
        ANVIL_UNCOMPRESSED -> ByteArrayInputStream(bytes)
        else -> error("Chunk slot $slot uses unsupported Anvil compression $compression")
    }
    return stream.use { input ->
        input.readNBytes(MAX_CHUNK_NBT_BYTES + 1).also { decompressed ->
            require(decompressed.size <= MAX_CHUNK_NBT_BYTES) {
                "Chunk slot $slot expands beyond the $MAX_CHUNK_NBT_BYTES-byte audit limit"
            }
        }
    }
}

private fun executableNbtEvidence(tag: LinTag<*>, path: String = "root"): String? = when (tag) {
    is LinCompoundTag -> tag.value().entries.firstNotNullOfOrNull { (name, child) ->
        val childPath = "$path.$name"
        if (name.equals("Command", ignoreCase = true)) childPath else executableNbtEvidence(child, childPath)
    }

    is LinListTag<*> -> tag.value().withIndex().firstNotNullOfOrNull { (index, child) ->
        executableNbtEvidence(child, "$path[$index]")
    }

    is LinStringTag -> {
        val value = tag.value()
        when {
            value.lowercase() in EXECUTABLE_BLOCK_ENTITY_IDS -> "$path=$value"
            value.contains("run_command", ignoreCase = true) -> "$path=$value"
            else -> null
        }
    }

    else -> null
}

private const val JAPANESE_SCHEMATIC_RESOURCE = "arena-templates/japanese-lobby-v1/map.schem"
private const val WORLDEDIT_BLOCKS_RESOURCE = "com/sk89q/worldedit/world/registry/blocks.121.json"
private const val WORLDEDIT_LEGACY_RESOURCE = "com/sk89q/worldedit/world/registry/legacy.json"
private const val PRACTICE_TEMPLATE_ID = "practice-yard-v1"
private const val PRACTICE_REGION_DESTINATION = "region/r.-1.0.mca"
private const val PRACTICE_REGION_SIZE = 393_216
private const val PRACTICE_REGION_HEADER_SHA256 = "71d8bff08f19eb47e921dbccdc77c81f65b4f16cb380c1eac7e85b15918f7957"
private const val PRACTICE_OCCUPIED_CHUNKS = 90
private const val ANVIL_SECTOR_BYTES = 4_096
private const val ANVIL_HEADER_BYTES = ANVIL_SECTOR_BYTES * 2
private const val ANVIL_LOCATION_ENTRIES = 1_024
private const val ANVIL_EXTERNAL_STREAM_FLAG = 0x80
private const val ANVIL_COMPRESSION_MASK = 0x7f
private const val ANVIL_GZIP_COMPRESSION = 1
private const val ANVIL_ZLIB_COMPRESSION = 2
private const val ANVIL_UNCOMPRESSED = 3
private const val MAX_CHUNK_NBT_BYTES = 16 * 1_024 * 1_024
private val EXECUTABLE_BLOCK_ENTITY_IDS = COMMAND_BLOCK_IDS + "minecraft:command_block_minecart"

private object PassthroughDataFixer : DataFixer {
    override fun <T : Any?> fixUp(type: DataFixer.FixType<T>, input: T, version: Int): T = input
}
