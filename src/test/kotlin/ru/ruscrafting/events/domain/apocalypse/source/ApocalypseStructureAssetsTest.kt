package ru.ruscrafting.events.domain.apocalypse.source

import com.github.retrooper.packetevents.protocol.nbt.NBT
import com.github.retrooper.packetevents.protocol.nbt.NBTCompound
import com.github.retrooper.packetevents.protocol.nbt.NBTInt
import com.github.retrooper.packetevents.protocol.nbt.NBTList
import com.github.retrooper.packetevents.protocol.nbt.NBTString
import com.github.retrooper.packetevents.protocol.nbt.NBTType
import com.github.retrooper.packetevents.protocol.nbt.serializer.DefaultNBTSerializer
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.GZIPOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ApocalypseStructureAssetsTest : StringSpec({
    "legacy red sandstone metadata keeps the pinned Mojang flattening variant" {
        ApocalypseLostCityAssets.canonicalState("minecraft:red_sandstone@2") shouldBe "minecraft:cut_red_sandstone"
        ApocalypseLostCityAssets.canonicalState("minecraft:red_sandstone") shouldBe "minecraft:red_sandstone"
        ApocalypseLostCityAssets.canonicalState("minecraft:smooth_red_sandstone") shouldBe "minecraft:smooth_red_sandstone"
        shouldThrow<IllegalArgumentException> { ApocalypseLostCityAssets.canonicalState("minecraft:red_sandstone@99") }
    }

    "typed structure decoder preserves palette connector and immutable sign metadata without native execution" {
        val source = sourceFixtureNbt()
        val decoded = ApocalypseStructureAssets.decodeTemplate("fixture:tiny", source)
        decoded.sizeX shouldBe 1
        decoded.sizeY shouldBe 1
        decoded.sizeZ shouldBe 1
        decoded.blockState(0, 0, 0) shouldBe "minecraft:jigsaw[orientation=east_up]"
        decoded.blockState(1, 0, 0) shouldBe null
        decoded.connectors.single().pool shouldBe "fixture:root"
        decoded.connectors.single().finalState shouldBe "minecraft:air"
        val data = decoded.blockEntity(0, 0, 0)!!
        data["Text1"] shouldBe "{\"text\":\"A \\\"quote\\\"\"}"
        decoded.blockNbt(0, 0, 0)!!.startsWith("{") shouldBe true
        decoded.blockNbt(0, 0, 0)!!.contains("String(") shouldBe false
        @Suppress("UNCHECKED_CAST")
        shouldThrow<UnsupportedOperationException> { (data as MutableMap<String, Any?>)["Text1"] = "changed" }
        var count = 0
        decoded.forEachBlockEntity { x, y, z, value ->
            Triple(x, y, z) shouldBe Triple(0, 0, 0)
            value shouldBe data
            count++
        }
        count shouldBe 1
        shouldThrow<Exception> { ApocalypseStructureAssets.decodeTemplate("fixture:truncated", source.copyOf(source.size - 1)) }
        shouldThrow<IllegalArgumentException> { ApocalypseStructureAssets.decodeTemplate("fixture:oversized", sourceFixtureNbt(512)) }
    }

    "archive catalog overlays source city palettes and assembles the exact fixture floor and roof" {
        val directory = Files.createTempDirectory("arcevents-source-assets-")
        try {
            val jcws = directory.resolve("jcws.jar")
            val base = directory.resolve("lostcities.jar")
            val addon = directory.resolve("keerdm.jar")
            val gzip = ByteArrayOutputStream().also { output -> GZIPOutputStream(output).use { it.write(sourceFixtureNbt()) } }.toByteArray()
            sourceFixtureZip(jcws, mapOf("data/fixture/structures/tiny.nbt" to gzip,
                "data/fixture/worldgen/template_pool/root.json" to """{"fallback":"minecraft:empty","elements":[{"weight":1,"element":{"location":"fixture:tiny","projection":"rigid","processors":[]}}]}""".toByteArray(),
                "data/fixture/worldgen/structure/site.json" to """{"start_pool":"fixture:root","size":1,"max_distance_from_center":32,"start_height":{"absolute":-8}}""".toByteArray()))
            val floor = """{"xsize":16,"zsize":16,"slices":[${List(6) { "[${List(16) { "\"################\"" }.joinToString(",")}]" }.joinToString(",")}]}"""
                .replaceFirst("################", "Q###############")
            val roof = """{"xsize":16,"zsize":16,"slices":[[${List(16) { "\"################\"" }.joinToString(",")}]]}"""
            val original = mapOf(
                "worldstyles/testworld" to """{"citystyles":[{"factor":1,"citystyle":"fixture:city"}],"multisettings":{"areasize":5,"minimum":0,"maximum":0,"attempts":0}}""",
                "citystyles/city" to """{"style":"fixture:style","selectors":{"buildings":[{"factor":1,"value":"fixture:house"}]}}""",
                "styles/style" to """{"randompalettes":[[{"factor":1,"palette":"fixture:palette"}]]}""",
                "palettes/palette" to """{"palette":[{"char":"#","block":"minecraft:stone"},{"char":"Q","block":"minecraft:red_sandstone@2"}]}""",
                "buildings/house" to """{"minfloors":1,"maxfloors":1,"maxcellars":0,"parts":[{"top":false,"part":"fixture:floor"},{"top":true,"part":"fixture:roof"}]}""",
                "parts/floor" to floor, "parts/roof" to roof,
            ).mapKeys { (key, _) ->
                if (key.startsWith("worldstyles/")) "data/keerdm_zombie_essentials/lostcities/$key.json"
                else "data/fixture/lostcities/$key.json"
            }.mapValues { it.value.toByteArray() }
            sourceFixtureZip(base, original)
            sourceFixtureZip(addon, mapOf("data/fixture/lostcities/palettes/palette.json" to
                """{"palette":[{"char":"#","blocks":[{"random":32,"block":"minecraft:stone"},{"random":1000,"block":"minecraft:deepslate"}]},{"char":"Q","block":"minecraft:red_sandstone@2"}]}""".toByteArray()))
            val catalog = ApocalypseStructureAssets.load(jcws, addon, base)
            catalog.templates.keys shouldBe setOf("fixture:tiny")
            catalog.pools.getValue("fixture:root").elements.single().template shouldBe "fixture:tiny"
            catalog.structures.getValue("fixture:site").startHeight shouldBe -8
            catalog.sourceHashes.values.all { it.length == 64 } shouldBe true
            catalog.city.sourceStates shouldBe setOf("minecraft:stone", "minecraft:deepslate", "minecraft:cut_red_sandstone")
            val template = catalog.city.part("fixture:floor", 1)
            Triple(template.sizeX, template.sizeY, template.sizeZ) shouldBe Triple(16, 6, 16)
            val contents = linkedMapOf<Triple<Int, Int, Int>, String>()
            catalog.city.renderBuilding("fixture:house", 1, 2, -3, 71, 1, 0) { x, y, z, state -> contents[Triple(x, y, z)] = state }
            contents.size shouldBe 16 * 16 * 7
            contents.keys.minOf { it.first } shouldBe 32
            contents.keys.maxOf { it.third } shouldBe -33
            contents.keys.minOf { it.second } shouldBe 71
            contents.keys.maxOf { it.second } shouldBe 77
            contents.values.all { it in setOf("minecraft:stone", "minecraft:deepslate", "minecraft:cut_red_sandstone") } shouldBe true
            contents[Triple(32, 71, -48)] shouldBe "minecraft:cut_red_sandstone"
            // The source 1000 sentinel fills remaining128 slots; it is not a normalized1000/1032 weight.
            val stoneCount = contents.values.count { it == "minecraft:stone" }
            (stoneCount in 300..600) shouldBe true

            sourceFixtureZip(addon, mapOf(
                "data/keerdm_zombie_essentials/lostcities/worldstyles/testworld.json" to
                    """{"citystyles":[{"factor":1,"citystyle":"fixture:city"}],"multisettings":{"areasize":2,"minimum":1,"maximum":1,"attempts":50}}""".toByteArray(),
                "data/fixture/lostcities/citystyles/city.json" to
                    """{"style":"fixture:style","selectors":{"buildings":[{"factor":1,"value":"fixture:house"}],"multibuildings":[{"factor":1,"value":"fixture:group"}]}}""".toByteArray(),
                "data/fixture/lostcities/multibuildings/group.json" to
                    """{"dimx":2,"dimz":2,"buildings":[["fixture:house","fixture:house"],["fixture:house","fixture:house"]]}""".toByteArray(),
                "data/fixture/lostcities/buildings/house.json" to
                    """{"minfloors":1,"maxfloors":3,"maxcellars":3,"parts":[{"top":false,"part":"fixture:floor"},{"top":true,"part":"fixture:roof"}]}""".toByteArray(),
            ))
            val multi = ApocalypseStructureAssets.load(jcws, addon, base).city
            repeat(4) { seed ->
                val envelopes = buildList {
                    for (cx in 0..1) for (cz in 0..1) {
                        var bottom = Int.MAX_VALUE
                        var top = Int.MIN_VALUE
                        multi.render(seed.toLong(), cx, cz, 71, if (cx == cz) .1 else .95) { _, y, _, _ ->
                            bottom = minOf(bottom, y); top = maxOf(top, y)
                        }
                        add(bottom to top)
                    }
                }
                envelopes.distinct().size shouldBe 1
            }
        } finally {
            Files.walk(directory).use { files -> files.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) } }
        }
    }
})

private fun sourceFixtureNbt(size: Int = 1): ByteArray {
    fun compound(vararg values: Pair<String, NBT>) = NBTCompound().apply { values.forEach { (key, value) -> setTag(key, value) } }
    fun ints(vararg values: Int) = NBTList(NBTType.INT, values.map(::NBTInt))
    val block = compound("pos" to ints(0, 0, 0), "state" to NBTInt(0), "nbt" to compound(
        "name" to NBTString("fixture:in"), "target" to NBTString("fixture:out"), "pool" to NBTString("fixture:root"),
        "final_state" to NBTString("minecraft:air"), "Text1" to NBTString("{\"text\":\"A \\\"quote\\\"\"}")))
    val root = compound("size" to ints(size, size, size),
        "palette" to NBTList(NBTType.COMPOUND, listOf(compound("Name" to NBTString("minecraft:jigsaw"),
            "Properties" to compound("orientation" to NBTString("east_up"))))),
        "blocks" to NBTList(NBTType.COMPOUND, listOf(block)))
    return ByteArrayOutputStream().also { output ->
        DataOutputStream(output).use { DefaultNBTSerializer.INSTANCE.serializeTag(it, root, true) }
    }.toByteArray()
}

private fun sourceFixtureZip(path: Path, entries: Map<String, ByteArray>) {
    ZipOutputStream(Files.newOutputStream(path)).use { zip ->
        entries.forEach { (name, bytes) -> zip.putNextEntry(ZipEntry(name)); zip.write(bytes); zip.closeEntry() }
    }
}
