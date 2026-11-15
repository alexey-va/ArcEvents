package ru.ruscrafting.events.domain.apocalypse.source

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import ru.ruscrafting.events.domain.apocalypse.ApocalypseMarkerKind
import ru.ruscrafting.events.domain.apocalypse.ApocalypsePoint

class ApocalypseJigsawAssemblerTest : StringSpec({
    "join original socket faces after rotation and expand at the neighboring voxel across a negative chunk seam" {
        val fixture = tinyAssets()
        val assembler = ApocalypseJigsawAssembler(fixture)
        for (seed in 0L..20L) {
            val assembly = assembler.assemble("test:start", seed, -16, -16) { _, _ -> 72 }
            assembly.pieces.map { it.template.id } shouldBe listOf("test:entrance", "test:room")
            val (root, child) = assembly.pieces
            root.bounds.intersects(child.bounds) shouldBe false
            val exit = root.world(2, 1, 1)
            val entry = child.world(0, 1, 1)
            kotlin.math.abs(exit.x - entry.x) + kotlin.math.abs(exit.z - entry.z) shouldBe 1
            exit.y shouldBe entry.y
            assembly.spawn shouldBe child.world(1, 1, 1)
            val marker = assembly.markers("test").single()
            ApocalypsePoint(marker.x, marker.y, marker.z) shouldBe child.world(2, 1, 1)
            marker.kind shouldBe ApocalypseMarkerKind.HAZMAT_SPAWNER
            val generated = mutableMapOf<ApocalypsePoint, String>()
            for (cz in -2..0) for (cx in -2..0) assembly.render(cx, cz) { x, y, z, state ->
                (x shr 4) shouldBe cx
                (z shr 4) shouldBe cz
                generated.put(ApocalypsePoint(x, y, z), state) shouldBe null
            }
            generated[exit] shouldBe "minecraft:air"
            generated[entry] shouldBe "minecraft:air"
            generated[child.world(2, 1, 1)] shouldBe "minecraft:copper_grate"
            generated.size shouldBe 5
        }
    }
    "preserve source stair connections, rail curves, cardinal walls and directional properties under all four rotations" {
        val stair = "minecraft:oak_stairs[facing=north,half=top,shape=outer_left,waterlogged=true]"
        ApocalypseSourcePalette.vanillaState(stair, 1) shouldBe "minecraft:oak_stairs[facing=east,half=top,shape=outer_left,waterlogged=true]"
        ApocalypseSourcePalette.vanillaState("minecraft:rail[shape=north_east]", 1) shouldBe "minecraft:rail[shape=south_east]"
        ApocalypseSourcePalette.vanillaState("minecraft:oak_sign[rotation=14]", 1) shouldBe "minecraft:oak_sign[rotation=2]"
        ApocalypseSourcePalette.vanillaState("create:andesite_ladder[facing=east,waterlogged=false]", 2) shouldBe "minecraft:ladder[facing=west,waterlogged=false]"
        ApocalypseSourcePalette.vanillaState("immersive_weathering:iron_bars[north=false,west=true]", 1) shouldBe "minecraft:iron_bars[east=false,north=true]"
        ApocalypseSourcePalette.vanillaState("minecraft:structure_void") shouldBe null
        ApocalypseSourcePalette.vanillaState("minecraft:air") shouldBe "minecraft:air"
    }
    "dynamic copycat geometry respects the source material and state" {
        ApocalypseSourcePalette.vanillaState("copycats:copycat_stairs[facing=west,half=top,shape=straight]", 1,
            """{"Material":{"Name":"minecraft:deepslate_bricks"}}""") shouldBe
            "minecraft:deepslate_brick_stairs[facing=north,half=top,shape=straight]"
    }
    "a final source ore coordinate survives only its chunk, later pieces and entity replacements" {
        val columbite = "the_deeper_void:columbite"
        val tungsten = "the_deeper_void:tungsten_ore"
        fun piece(x: Int, states: List<String>, entities: List<ApocalypseSourceEntity> = emptyList()) =
            ApocalypseSourcePiece(ApocalypseSourceTemplate("test:rock", states.size, 1, 1, states,
                IntArray(states.size) { it }, emptyMap(), emptyList(), entities), x, 60, 0, 0, null)
        val base = piece(-17, listOf(columbite, tungsten, columbite, tungsten, "minecraft:deepslate_emerald_ore", tungsten))
        val carve = piece(-16, listOf("minecraft:air", "minecraft:structure_void", "minecraft:structure_void"), listOf(
            ApocalypseSourceEntity(2.0, 0.0, 0.0, "jeffs_cursed_walking_structures:hazmat_spawner_placer", "{}"),
        ))
        val assembly = ApocalypseSourceAssembly(listOf(base, carve), 7)
        val result = mutableMapOf<ApocalypsePoint, String>()
        assembly.sourceOres(-2, 0, result)
        result shouldBe mapOf(ApocalypsePoint(-17, 60, 0) to columbite)
        assembly.sourceOres(-1, 0, result)
        result shouldBe mapOf(ApocalypsePoint(-17, 60, 0) to columbite, ApocalypsePoint(-15, 60, 0) to columbite,
            ApocalypsePoint(-12, 60, 0) to tungsten)
    }
    "columbite mineral has veins distinct from natural polished deepslate and authored tungsten" {
        ApocalypseSourcePalette.vanillaState("the_deeper_void:columbite") shouldBe "minecraft:deepslate_emerald_ore"
        ApocalypseSourcePalette.vanillaState("the_deeper_void:tungsten_ore") shouldBe "minecraft:deepslate_iron_ore"
        ApocalypseSourcePalette.vanillaState("minecraft:polished_deepslate") shouldBe "minecraft:polished_deepslate"
    }
    "floor-only source ivy lies on its floor and wall ivy keeps its authored support under rotation" {
        val floor = "immersive_weathering:ivy[age=8,down=true,east=false,north=false,south=false,up=false,west=false]"
        for (rotation in 0..3) ApocalypseSourcePalette.vanillaState(floor, rotation) shouldBe "minecraft:moss_carpet"
        ApocalypseSourcePalette.vanillaState("immersive_weathering:ivy[down=true,north=true]", 1) shouldBe "minecraft:vine[east=true]"
        ApocalypseSourcePalette.vanillaState("immersive_weathering:ivy[up=true]", 2) shouldBe "minecraft:vine[up=true]"
        ApocalypseSourcePalette.vanillaState("immersive_weathering:ivy[down=false,up=false]", 2) shouldBe "minecraft:air"
    }
    "preserve the shapes of charred wood in the original city palettes" {
        val source = "immersive_weathering:charred_"
        ApocalypseSourcePalette.vanillaState("${source}planks[overhang=1,smoldering=false]") shouldBe "minecraft:dark_oak_planks"
        ApocalypseSourcePalette.vanillaState("${source}slab[overhang=1,smoldering=false,type=top,waterlogged=false]") shouldBe
            "minecraft:dark_oak_slab[type=top,waterlogged=false]"
        ApocalypseSourcePalette.vanillaState("${source}stairs[facing=west,half=bottom,overhang=0,shape=inner_right,smoldering=false,waterlogged=false]", 1) shouldBe
            "minecraft:dark_oak_stairs[facing=north,half=bottom,shape=inner_right,waterlogged=false]"
    }
})

private fun tinyAssets(): ApocalypseJigsawAssets {
    val entrancePort = ApocalypseSourceConnector(2, 1, 1, "east_up", "minecraft:empty", "test:room", "test:more", "minecraft:air")
    val roomPort = ApocalypseSourceConnector(0, 1, 1, "west_up", "test:room", "minecraft:empty", "minecraft:empty", "minecraft:air")
    fun piece(id: String, port: ApocalypseSourceConnector, room: Boolean): ApocalypseSourceTemplate {
        val states = IntArray(3 * 2 * 3) { -1 }
        states[4] = 0 // one block at y0, z1, x1
        states[(port.y * 3 + port.z) * 3 + port.x] = 1
        val entities = if (room) listOf(
            ApocalypseSourceEntity(1.5, 1.0, 1.5, "jeffs_cursed_walking_structures:spawnpoint_entity", "{}"),
            ApocalypseSourceEntity(2.5, 1.0, 1.5, "jeffs_cursed_walking_structures:hazmat_spawner_placer", "{}")
        ) else emptyList()
        return ApocalypseSourceTemplate(id, 3, 2, 3, listOf("minecraft:stone", "minecraft:jigsaw"),
            states, emptyMap(), listOf(port), entities)
    }
    val pieces = listOf(piece("test:entrance", entrancePort, false), piece("test:room", roomPort, true)).associateBy { it.id }
    return object : ApocalypseJigsawAssets {
        override fun template(id: String) = pieces[id]
        override val pools = mapOf("test:first" to ApocalypseSourcePool("minecraft:empty", listOf(ApocalypsePoolElement("test:entrance",1))),
            "test:more" to ApocalypseSourcePool("minecraft:empty", listOf(ApocalypsePoolElement("test:room",1))))
        override val structures = mapOf("test:start" to ApocalypseSourceStructure("test:first",2,32,0,"WORLD_SURFACE_WG"))
    }
}
