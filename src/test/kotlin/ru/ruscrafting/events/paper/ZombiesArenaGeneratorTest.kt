package ru.ruscrafting.events.paper

import com.google.gson.GsonBuilder
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import org.bukkit.Material
import java.nio.file.Files
import java.nio.file.Path

class ZombiesArenaGeneratorTest : StringSpec({
    fun solid(x: Int, y: Int, z: Int) = ZombiesArenaGenerator.materialAt(x, y, z)?.isSolid == true

    fun walkableFromSpawn(breached: Set<Pair<Int, Int>> = emptySet(), blocked: Set<Pair<Int, Int>> = emptySet()): Set<Pair<Int, Int>> {
        val start = 0 to -19
        val reached = mutableSetOf(start)
        val queue = ArrayDeque(listOf(start))
        while (queue.isNotEmpty()) {
            val (x, z) = queue.removeFirst()
            for ((dx, dz) in listOf(1 to 0, -1 to 0, 0 to 1, 0 to -1)) {
                val next = x + dx to z + dz
                if (next in reached || next in blocked) continue
                if (solid(next.first, 64, next.second) && !solid(next.first, 66, next.second) &&
                    (!solid(next.first, 65, next.second) || next in breached)) {
                    reached.add(next)
                    queue.addLast(next)
                }
            }
        }
        return reached
    }

    "the supermarket's six windows connect only their own spawn pocket after a breach" {
        val closed = walkableFromSpawn()
        ZombiesArenaGenerator.WINDOWS.forEachIndexed { index, window ->
            val cells = window.gateCells()
            cells.forEach { (x, y, z) ->
                ZombiesArenaGenerator.materialAt(x, y, z) shouldBe Material.SPRUCE_FENCE
                solid(x, 68, z) shouldBe true // the lintel also closes the route over the intact fence
            }
            ((window.x + window.inwardX * 2 to window.z + window.inwardZ * 2) in closed) shouldBe true
            val open = walkableFromSpawn(cells.mapTo(mutableSetOf()) { it.first to it.third })
            ZombiesArenaGenerator.SPAWNS.forEachIndexed { other, point ->
                (point in closed) shouldBe false
                (point in open) shouldBe (other == index)
            }
            open.all { (x, z) -> x in -25..25 && z in -30..11 } shouldBe true
        }
    }

    "the supply counter and the original checkout lanes remain connected on both sides of the aisles" {
        val material = ZombiesArenaGenerator.Companion::materialAt
        material(0, 64, -19)?.isSolid shouldBe true
        material(0, 65, -19) shouldBe null
        material(0, 66, -19) shouldBe null
        ZombiesArenaGenerator.STATIONS.values.map { material(it, 65, -22) } shouldBe
            listOf(Material.BARREL, Material.CHEST, Material.SMITHING_TABLE, Material.BREWING_STAND)
        ZombiesArenaGenerator.MYSTERY_CHEST_X.map { x -> ZombiesArenaGenerator.stateAt(x, 65, -22) } shouldBe listOf(
            "minecraft:chest[facing=south,type=right,waterlogged=false]",
            "minecraft:chest[facing=south,type=left,waterlogged=false]",
        )
        // Occlude each half of the shelf aisles in turn. Both paths still reach the front entrance.
        for (half in listOf(-24..0, 0..24)) {
            val reachable = walkableFromSpawn(blocked = half.mapTo(mutableSetOf()) { it to -13 })
            ZombiesArenaGenerator.STATIONS.values.all { (it to -21) in reachable } shouldBe true
            listOf(-16 to 5, 0 to 8, 16 to 5).all { it in reachable } shouldBe true
        }
        material(0, 64, -19) shouldBe Material.SNOW_BLOCK // the original tiled checkout floor and parking lot
        material(0, 64, 24) shouldBe Material.SMOOTH_STONE
    }

    "export the exact authored arena and its interactions to the existing atelier renderer when requested" {
        val directory = System.getenv("ARC_EVENTS_ZOMBIES_ARENA_PREVIEW")?.let(Path::of)
        if (directory != null) {
            Files.createDirectories(directory)
            val volume = ZombiesArenaGenerator.store
            val palette = linkedMapOf("minecraft:air" to 0)
            val blocks = mutableListOf<List<Int>>()
            for (y in 0 until volume.height) for (z in 0 until volume.length) for (x in 0 until volume.width) {
                val state = volume.stateAt(x, y, z) ?: continue
                blocks += listOf(x, y, z, palette.getOrPut(state) { palette.size })
            }
            val data = mapOf("version" to 1, "size" to listOf(volume.width, volume.height, volume.length),
                "worldMin" to listOf(ZombiesArenaGenerator.MIN_X, ZombiesArenaGenerator.MIN_Y, ZombiesArenaGenerator.MIN_Z),
                "origin" to listOf(-ZombiesArenaGenerator.MIN_X, 65 - ZombiesArenaGenerator.MIN_Y, -19 - ZombiesArenaGenerator.MIN_Z),
                "materials" to palette.keys.map { mapOf("block" to it, "color" to "#808080", "name" to it) }, "blocks" to blocks)
            Files.writeString(directory.resolve("scene.blocks.json"), GsonBuilder().create().toJson(data))
            val window = ZombiesArenaGenerator.WINDOWS.first()
            Files.writeString(directory.resolve("interactions.json"), GsonBuilder().setPrettyPrinting().create().toJson(mapOf(
                "source" to ZombiesArenaGenerator.TEMPLATE, "window" to window,
                "boards" to (0 until ZombiesBarricades.MAX_BOARDS).map { ZombiesBarricades.board(window, it) },
                "case" to mapOf("x" to -4.0, "y" to 67.0, "z" to -21.2, "scale" to .6, "context" to "fixed"),
                "cardsMs" to ZombiesMysteryBox.CARD_TIMES, "rollMs" to ZombiesMysteryBox.ROLL_MS,
                "claimMs" to ZombiesMysteryBox.CLAIM_MS, "repairMs" to ZombiesBarricades.REPAIR_MS,
                "strikeMs" to ZombiesBarricades.STRIKE_MS)))
        }
    }
})
