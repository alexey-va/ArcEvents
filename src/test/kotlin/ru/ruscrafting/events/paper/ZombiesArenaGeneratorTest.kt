package ru.ruscrafting.events.paper

import com.google.gson.GsonBuilder
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import org.bukkit.Material
import java.nio.file.Files
import java.nio.file.Path
import kotlin.math.abs
import ru.arc.paper.testing.MockBukkitTestRuntime

class ZombiesArenaGeneratorTest : StringSpec({
    fun solid(x: Int, y: Int, z: Int) = ZombiesArenaGenerator.materialAt(x, y, z)?.isSolid == true
    fun walkable(x: Int, z: Int) = solid(x, 64, z) && !solid(x, 65, z) && !solid(x, 66, z)

    "intact windows seal every spawn alcove and breached windows connect to the contained player circuit" {
        val start = 0 to -19
        walkable(start.first, start.second) shouldBe true
        val reached = mutableSetOf(start)
        val pending = ArrayDeque(listOf(start))
        while (pending.isNotEmpty()) {
            val (x, z) = pending.removeFirst()
            for ((dx, dz) in listOf(1 to 0, -1 to 0, 0 to 1, 0 to -1)) {
                val next = x + dx to z + dz
                if (walkable(next.first, next.second) && reached.add(next)) pending.addLast(next)
            }
        }
        ZombiesArenaGenerator.SPAWNS.forEach { (it in reached) shouldBe false }
        ZombiesArenaGenerator.WINDOWS.forEach { window ->
            ((window.x + window.inwardX * 2 to window.z + window.inwardZ * 2) in reached) shouldBe true
        }
        ZombiesArenaGenerator.STATIONS.values.forEach { ((it to -21) in reached) shouldBe true }
        for (point in listOf(0 to 7, 0 to -7, 7 to 0, -7 to 0, 24 to 0, -24 to 0)) (point in reached) shouldBe true
        reached.none { abs(it.first) >= 28 || abs(it.second) >= 28 } shouldBe true
        for (edge in -28..28) for (height in 65..73) {
            solid(edge, height, -28) shouldBe true
            solid(edge, height, 28) shouldBe true
            solid(-28, height, edge) shouldBe true
            solid(28, height, edge) shouldBe true
        }
        val gates = ZombiesArenaGenerator.WINDOWS.flatMap { it.gateCells() }.mapTo(mutableSetOf()) { it.first to it.third }
        val breached = mutableSetOf(start)
        pending.addLast(start)
        while (pending.isNotEmpty()) {
            val (x, z) = pending.removeFirst()
            for ((dx, dz) in listOf(1 to 0, -1 to 0, 0 to 1, 0 to -1)) {
                val next = x + dx to z + dz
                if ((walkable(next.first, next.second) || next in gates) && breached.add(next)) pending.addLast(next)
            }
        }
        ZombiesArenaGenerator.SPAWNS.forEach { (it in breached) shouldBe true }
    }

    "station contracts and cargo geometry stay fixed for the owned interaction and pathfinding" {
        ZombiesArenaGenerator.STATIONS.values.map { ZombiesArenaGenerator.materialAt(it, 65, -22) } shouldBe
            listOf(Material.BARREL, Material.CHEST, Material.SMITHING_TABLE, Material.BREWING_STAND)
        for (x in listOf(-15, 15)) for (z in listOf(-9, 9)) {
            (65..68).all { solid(x, it, z) } shouldBe true
            ZombiesArenaGenerator.materialAt(x, 69, z) shouldBe null
        }
        ZombiesArenaGenerator.materialAt(0, 70, 0) shouldBe Material.SEA_LANTERN
        ZombiesArenaGenerator.materialAt(4, 70, 0) shouldBe Material.CYAN_STAINED_GLASS
        ZombiesArenaGenerator.materialAt(29, 64, 0) shouldBe null
    }

    "wall pillars follow the along-wall coordinate and leave the warning stripe and gate visible" {
        ZombiesArenaGenerator.materialAt(28, 68, 1) shouldBe Material.RED_CONCRETE
        ZombiesArenaGenerator.materialAt(-28, 68, -1) shouldBe Material.RED_CONCRETE
        ZombiesArenaGenerator.materialAt(1, 68, -28) shouldBe Material.RED_CONCRETE
        ZombiesArenaGenerator.materialAt(28, 68, 7) shouldBe Material.POLISHED_DEEPSLATE
        ZombiesArenaGenerator.materialAt(28, 69, 1) shouldBe Material.DEEPSLATE_TILES
        ZombiesArenaGenerator.materialAt(28, 70, 1) shouldBe Material.SEA_LANTERN
        ZombiesArenaGenerator.materialAt(2, 68, 28) shouldBe Material.IRON_BLOCK
        ZombiesArenaGenerator.materialAt(3, 68, 28) shouldBe Material.BLACK_CONCRETE
    }

    "export the exact generator block volume for the existing atelier renderer when requested" {
        val directory = System.getenv("ARC_EVENTS_ZOMBIES_ARENA_PREVIEW")?.let(Path::of)
        if (directory != null) {
            Files.createDirectories(directory)
            val palette = linkedMapOf("minecraft:air" to 0)
            val blocks = mutableListOf<List<Int>>()
            MockBukkitTestRuntime.open().use {
                for (y in 60..77) for (z in -28..28) for (x in -28..28) {
                    val material = ZombiesArenaGenerator.materialAt(x, y, z) ?: continue
                    val name = when (material) {
                        Material.CHEST -> ZombiesArenaGenerator.chestData(x).asString
                        Material.SPRUCE_FENCE -> ZombiesArenaGenerator.fenceData(x, z).asString
                        else -> "minecraft:${material.name.lowercase()}"
                    }
                    val index = palette.getOrPut(name) { palette.size }
                    blocks += listOf(x + 28, y - 60, z + 28, index)
                }
            }
            val data = mapOf("version" to 1, "size" to listOf(57, 18, 57), "origin" to listOf(28, 5, 9),
                "materials" to palette.keys.map { mapOf("block" to it, "color" to "#808080", "name" to it) }, "blocks" to blocks)
            Files.writeString(directory.resolve("scene.blocks.json"), GsonBuilder().create().toJson(data))
            val window = ZombiesArenaGenerator.WINDOWS.first()
            Files.writeString(directory.resolve("interactions.json"), GsonBuilder().setPrettyPrinting().create().toJson(mapOf(
                "source" to "ZombiesArenaGenerator v2; ZombiesBarricades.board; ZombiesMysteryBox.CARD_TIMES",
                "window" to window,
                "boards" to (0 until ZombiesBarricades.MAX_BOARDS).map { ZombiesBarricades.board(window, it) },
                "case" to mapOf("x" to -4.0, "y" to 67.0, "z" to -21.2, "scale" to .6, "context" to "fixed"),
                "cardsMs" to ZombiesMysteryBox.CARD_TIMES, "rollMs" to ZombiesMysteryBox.ROLL_MS,
                "claimMs" to ZombiesMysteryBox.CLAIM_MS, "repairMs" to ZombiesBarricades.REPAIR_MS,
                "strikeMs" to ZombiesBarricades.STRIKE_MS)))
        }
    }
})
