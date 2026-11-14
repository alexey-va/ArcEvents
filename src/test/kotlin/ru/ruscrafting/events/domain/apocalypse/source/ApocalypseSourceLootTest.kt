package ru.ruscrafting.events.domain.apocalypse.source

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import ru.ruscrafting.events.domain.apocalypse.ApocalypseMarkerKind
import ru.ruscrafting.events.domain.apocalypse.ApocalypseOriginalLayout
import ru.ruscrafting.events.domain.apocalypse.ApocalypsePoint
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.Random
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ApocalypseSourceLootTest : StringSpec({
    "source gun pools preserve the weighted empty outcome and source unloaded magazine NBT" {
        val loot = ApocalypseSourceLoot.decode(mapOf("test:armory" to """{
          "pools":[{"rolls":1,"entries":[
            {"type":"minecraft:item","name":"tacz:modern_kinetic_gun","weight":2,"functions":[
              {"function":"minecraft:set_nbt","tag":"{GunId:\"tacz:m1911\",GunCurrentAmmoCount:0,GunFireMode:\"SEMI\"}"}]},
            {"type":"minecraft:item","name":"minecraft:air","weight":50}]}]}"""))
        val first = object : Random(0) { override fun nextInt(bound: Int) = 0 }
        val last = object : Random(0) { override fun nextInt(bound: Int) = bound - 1 }
        loot.roll("test:armory", last) shouldBe emptyList()
        val item = (loot.roll("test:armory", first).single() as ApocalypseSourceLootDrop.Item).item
        item.itemId shouldBe "tacz:modern_kinetic_gun"
        item.gunId shouldBe "tacz:m1911"
        item.nbt!!.contains("GunCurrentAmmoCount:0") shouldBe true
        loot.referencedItems shouldBe setOf("tacz:modern_kinetic_gun")
        loot.referencedGuns shouldBe setOf("tacz:m1911")
        loot.hasFirearm("test:armory") shouldBe true
    }

    "inclusive source counts and typed native enchantment map and text directives survive decoding" {
        val loot = ApocalypseSourceLoot.decode(mapOf("test:supply" to """{
          "pools":[{"rolls":1,"entries":[{"type":"item","name":"minecraft:map","functions":[
            {"function":"set_count","count":{"min":2,"max":4}},
            {"function":"enchant_with_levels","levels":12,"treasure":true},
            {"function":"minecraft:exploration_map","destination":"test:teleporter","decoration":"banner_gray","zoom":2,"search_radius":600,"skip_existing_chunks":false},
            {"function":"minecraft:set_name","name":"Excavation"},
            {"function":"minecraft:set_lore","lore":["Source clue"],"replace":true}]}]}]}"""))
        val results = (0L..100L).map { (loot.roll("test:supply", Random(it)).single() as ApocalypseSourceLootDrop.Item).item }
        results.map { it.count }.toSet() shouldBe setOf(2, 3, 4)
        results.first().enchantLevels shouldBe 12
        results.first().enchantTreasure shouldBe true
        results.first().explorationMap shouldBe ApocalypseSourceLootMap("test:teleporter", "banner_gray", 2, 600, false)
        results.first().displayName shouldBe "Excavation"
        results.first().lore shouldBe listOf("Source clue")
        loot.hasFirearm("test:supply") shouldBe false
        loot.roll("test:supply", Random(91)) shouldBe loot.roll("test:supply", Random(91))
    }

    "nested tables delegate only known vanilla namespaces and reject cycles and missing mod tables" {
        val loot = ApocalypseSourceLoot.decode(mapOf(
            "test:outer" to """{"pools":[{"rolls":1,"entries":[{"type":"loot_table","name":"test:inner"}]}]}""",
            "test:inner" to """{"pools":[{"rolls":1,"entries":[{"type":"minecraft:loot_table","name":"minecraft:chests/stronghold_crossing"}]}]}""",
            "test:cycle" to """{"pools":[{"rolls":1,"entries":[{"type":"loot_table","name":"test:cycle"}]}]}"""))
        loot.roll("test:outer", Random(0)) shouldBe listOf(ApocalypseSourceLootDrop.VanillaTable("minecraft:chests/stronghold_crossing"))
        shouldThrow<IllegalArgumentException> { loot.roll("test:cycle", Random(0)) }
        shouldThrow<IllegalStateException> { loot.roll("missing:chests/anything", Random(0)) }.message shouldBe
            "Missing original loot table: missing:chests/anything"
    }

    "source direct PointBlank firearms are discoverable but ammunition alone is not a weapon route" {
        val loot = ApocalypseSourceLoot.decode(mapOf(
            "test:gun" to """{"pools":[{"rolls":1,"entries":[{"type":"item","name":"pointblank:ak47"}]}]}""",
            "test:ammo" to """{"pools":[{"rolls":1,"entries":[{"type":"item","name":"pointblank:ammo762"}]}]}"""))
        loot.hasFirearm("test:gun") shouldBe true
        loot.hasFirearm("test:ammo") shouldBe false
    }

    "crowbar component procedures preserve guaranteed materials independent rare drops and inclusive quantities" {
        val loot = ApocalypseSourceLoot.decode(emptyMap(), partCrates = true)
        val rejectRare = object : Random(0) {
            override fun nextInt(bound: Int) = 0
            override fun nextDouble() = .9999999999999999
        }
        val gun = loot.rollPartCrate("jeffs_cursed_walking_structures:gun_part_chest", rejectRare)
            .map { (it as ApocalypseSourceLootDrop.Item).item }.associate { it.itemId to it.count }
        gun shouldBe mapOf("mcore:steel_ingot" to 2, "mcore:steel_sheet" to 4, "minecraft:iron_ingot" to 4, "create:sturdy_sheet" to 1)
        val acceptRare = object : Random(0) {
            override fun nextInt(bound: Int) = bound - 1
            override fun nextDouble() = 0.0
        }
        val create = loot.rollPartCrate("jeffs_cursed_walking_structures:create_part_chest", acceptRare)
            .map { (it as ApocalypseSourceLootDrop.Item).item }.associate { it.itemId to it.count }
        create.size shouldBe 19
        create["create:andesite_alloy"] shouldBe 7
        create["create:mechanical_crafter"] shouldBe 2
        create["create:mechanical_arm"] shouldBe 1
        ("create:mechanical_arm" in loot.referencedItems) shouldBe true
        val id = "jeffs_cursed_walking_structures:gun_part_chest"
        loot.rollPartCrate(id, Random(19)) shouldBe loot.rollPartCrate(id, Random(19))
    }

    "original city condition ranges and part predicates select tables with explicit pinned typo alias" {
        val loot = ApocalypseSourceLoot.decode(emptyMap(), mapOf("lostcities:chestloot" to """{"values":[
          {"factor":1,"value":"test:cellar","range":"-100,-1"},
          {"factor":1,"value":"keerdm_zombie__essentials:chests/tacz_gunchest","range":"0,100","inpart":"rail_dungeon1"}]}"""))
        loot.selectCondition("chestloot", ApocalypseSourceLootContext(-1), Random(0)) shouldBe "test:cellar"
        loot.selectCondition("chestloot", ApocalypseSourceLootContext(2, "lostcities:rail_dungeon1"), Random(0)) shouldBe
            "keerdm_zombie_essentials:chests/tacz_gunchest"
        shouldThrow<IllegalArgumentException> { loot.selectCondition("chestloot", ApocalypseSourceLootContext(0, "test:other"), Random(0)) }
    }

    "missing JCWS voidmaterials uses the explicit native compatibility target without inventing another pool" {
        val loot = ApocalypseSourceLoot.decode(mapOf("the_deeper_void:chests/void_dimension" to
            """{"pools":[{"rolls":1,"entries":[{"type":"item","name":"minecraft:coal"}]}]}"""))
        loot.canonicalTable("jeffs_cursed_walking_structures:chests/voidmaterials") shouldBe "the_deeper_void:chests/void_dimension"
        loot.roll("jeffs_cursed_walking_structures:chests/voidmaterials", Random(12)) shouldBe
            loot.roll("the_deeper_void:chests/void_dimension", Random(12))
    }

    "raph overrides starter barrel NBT and gun-storage markers retain tables across rotation without geometry changes" {
        val loot = ApocalypseSourceLoot.decode(emptyMap(), processors = mapOf("test:raph" to """{"processors":[{
          "processor_type":"minecraft:rule","rules":[{
            "input_predicate":{"predicate_type":"minecraft:block_match","block":"lootr:lootr_barrel"},
            "position_predicate":{"predicate_type":"minecraft:linear_pos","min_dist":0,"max_dist":255,"min_chance":1.0,"max_chance":1.0},
            "block_entity_modifier":{"type":"minecraft:append_loot","loot_table":"test:supply"}}]}]}"""))
        val states = IntArray(6) { -1 }.also { it[0] = 0; it[2] = 1; it[4] = 2 }
        val template = ApocalypseSourceTemplate("test:room", 3, 2, 1,
            listOf("lootr:lootr_barrel", "jeffs_cursed_walking_structures:gun_storage_unit", "minecraft:ladder[facing=north]"),
            states, emptyMap(), emptyList(), emptyList(), blockEntities = mapOf(
                0 to mapOf("LootTable" to "minecraft:chests/stronghold_crossing"), 2 to mapOf("LootTable" to "test:armory")))
        for (rotation in 0..3) {
            val piece = ApocalypseSourcePiece(template, -16, 50, -16, rotation, "test:raph")
            val assembly = ApocalypseSourceAssembly(listOf(piece), 42, loot)
            fun geometry(): Map<ApocalypsePoint, String> = buildMap {
                for (cz in -2..-1) for (cx in -2..-1) assembly.render(cx, cz) { x, y, z, state -> put(ApocalypsePoint(x, y, z), state) }
            }
            val before = geometry()
            val markers = assembly.markers("starter")
            markers.size shouldBe 2
            markers.single { it.kind == ApocalypseMarkerKind.LOOT }.lootTable shouldBe "test:supply"
            val storage = markers.single { it.kind == ApocalypseMarkerKind.CORE }
            storage.lootTable shouldBe "test:armory"
            ApocalypsePoint(storage.x, storage.y, storage.z) shouldBe piece.world(2, 0, 0)
            before[ApocalypsePoint(storage.x, storage.y, storage.z)]!!.substringBefore('[') shouldBe "minecraft:barrel"
            assembly.highestLadder shouldBe piece.world(1, 1, 0)
            geometry() shouldBe before
        }
    }

    "external CW nested datapack overlays jar tables without bundling assets or treating block drops as loot" {
        val directory = Files.createTempDirectory("apocalypse-loot-archive-")
        try {
            fun archive(path: Path, entries: Map<String, ByteArray>) {
                ZipOutputStream(Files.newOutputStream(path)).use { zip -> entries.forEach { (name, bytes) ->
                    zip.putNextEntry(ZipEntry(name)); zip.write(bytes); zip.closeEntry()
                } }
            }
            val jar = directory.resolve("source.jar")
            archive(jar, mapOf(
                "data/test/loot_tables/chests/supply.json" to """{"pools":[{"rolls":1,"entries":[{"type":"item","name":"minecraft:stone"}]}]}""".toByteArray(),
                "data/test/loot_tables/blocks/unused.json" to """{"type":"minecraft:block","pools":[{"rolls":1,"entries":[{"type":"item","name":"test:unused"}]}]}""".toByteArray()))
            val nested = ByteArrayOutputStream().also { output -> ZipOutputStream(output).use { zip ->
                zip.putNextEntry(ZipEntry("data/test/loot_tables/chests/supply.json"))
                zip.write("""{"pools":[{"rolls":1,"entries":[{"type":"item","name":"minecraft:bread"}]}]}""".toByteArray()); zip.closeEntry()
            } }.toByteArray()
            val pack = directory.resolve("pack.zip")
            archive(pack, mapOf("overrides/config/openloader/data/NewLCLootV3.1.zip" to nested))
            val loot = ApocalypseSourceLoot.load(listOf(jar), pack)
            loot.referencedItems shouldBe setOf("minecraft:bread")
            (loot.roll("test:chests/supply", Random(0)).single() as ApocalypseSourceLootDrop.Item).item.itemId shouldBe "minecraft:bread"
        } finally {
            Files.walk(directory).use { files -> files.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) } }
        }
    }

    "pinned external reference starter uses processed supplies and source city loot is categorized".config(
        enabled = REFERENCE_PATHS.all { Files.isRegularFile(it) },
    ) {
        val catalog = ApocalypseStructureAssets.load(REFERENCE_PATHS[0], REFERENCE_PATHS[1], REFERENCE_PATHS[2],
            REFERENCE_PATHS[3], REFERENCE_PATHS.drop(4))
        catalog.loot.tableIds.size shouldBe 93
        catalog.loot.referencedItems.size shouldBe 320
        catalog.template("jeffs_cursed_walking_structures:collapsedradiotowerdesert")!!.authoredItems.isNotEmpty() shouldBe true
        catalog.loot.referencedGuns.size shouldBe 29
        val layout = ApocalypseOriginalLayout(68200133, catalog)
        layout.starterSupplyMarkers.isNotEmpty() shouldBe true
        layout.starterSupplyMarkers.all { it.lootTable == "jeffs_cursed_walking_structures:chests/supplychest" } shouldBe true
        layout.starterSupplyMarkers.any { catalog.loot.hasFirearm(it.lootTable!!) } shouldBe false
        (layout.starterExit.y > layout.spawn.y) shouldBe true
        catalog.loot.hasFirearm("jeffs_cursed_walking_structures:blocks/armory_chest") shouldBe true
        catalog.loot.hasFirearm("jeffs_cursed_walking_structures:chests/gun_2") shouldBe false
        val cityTables = (0..4).flatMap { cx -> (0..4).flatMap { cz ->
            catalog.city.markers(68200133, cx, cz, 71, .9, "city").mapNotNull { it.lootTable }
        } }
        cityTables.isNotEmpty() shouldBe true
        cityTables.all { it in catalog.loot.tableIds || it.startsWith("minecraft:") } shouldBe true
    }
})

private val REFERENCE_PATHS = listOf(
    "/private/tmp/cw-jcws-8502762-dzozihl4.jar", "/private/tmp/cw-keerdm-7740113.jar", "/private/tmp/cw-lostcities-7432706.jar",
    "/private/tmp/cursed-walking-study-20261114/cursed-walking-v3.3-patch.zip",
    "/private/tmp/the_deep_void-1.28-forge-1.20.1.jar", "/private/tmp/the_deeper_void-2.0.5-forge-1.20.1.jar",
).map(Path::of)
