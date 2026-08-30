package ru.ruscrafting.events.paper

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import ru.ruscrafting.events.config.EventBounds
import ru.ruscrafting.events.config.EventLocation
import java.nio.file.Files

class ArenaWeaponPointStoreTest : StringSpec({
    "add persists an exact point and rejects a duplicate" {
        val root = Files.createTempDirectory("arcevents-weapon-points-")
        try {
            val arena = arena()
            ArenaWeaponPointStore(root).use { store ->
                store.add(arena, point(1.5), maximumPoints = 30) shouldBe ArenaWeaponPointEditResult.ADDED
                store.add(arena, point(1.9), maximumPoints = 30) shouldBe ArenaWeaponPointEditResult.DUPLICATE
                store.points(arena) shouldBe listOf(point(1.5))
            }

            ArenaWeaponPointStore(root).use { reopened ->
                reopened.points(arena) shouldBe listOf(point(1.5))
            }
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    "remove selects only a nearby point and persists the result" {
        val root = Files.createTempDirectory("arcevents-weapon-points-remove-")
        try {
            val arena = arena()
            ArenaWeaponPointStore(root).use { store ->
                store.add(arena, point(1.5), maximumPoints = 30) shouldBe ArenaWeaponPointEditResult.ADDED
                store.add(arena, point(8.5), maximumPoints = 30) shouldBe ArenaWeaponPointEditResult.ADDED
                store.removeNearest(arena, point(7.0), maximumDistance = 3.0) shouldBe ArenaWeaponPointEditResult.REMOVED
                store.removeNearest(arena, point(20.0), maximumDistance = 3.0) shouldBe ArenaWeaponPointEditResult.NOT_FOUND
                store.points(arena) shouldBe listOf(point(1.5))
            }
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    "point count cannot exceed the configured weapon target" {
        val root = Files.createTempDirectory("arcevents-weapon-points-limit-")
        try {
            val arena = arena()
            ArenaWeaponPointStore(root).use { store ->
                store.add(arena, point(1.5), maximumPoints = 1) shouldBe ArenaWeaponPointEditResult.ADDED
                store.add(arena, point(2.5), maximumPoints = 1) shouldBe ArenaWeaponPointEditResult.LIMIT_REACHED
            }
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    "corrupt runtime data is retained and disables edits instead of disabling the plugin" {
        val root = Files.createTempDirectory("arcevents-weapon-points-corrupt-")
        try {
            Files.createDirectories(root.resolve("data"))
            Files.writeString(root.resolve("data/arena-weapon-points.json"), "{broken")
            var failures = 0

            ArenaWeaponPointStore(root) { failures++ }.use { store ->
                store.points(arena()) shouldBe emptyList()
                store.add(arena(), point(1.5), maximumPoints = 30) shouldBe
                    ArenaWeaponPointEditResult.STORAGE_UNAVAILABLE
            }
            failures shouldBe 1
            Files.readString(root.resolve("data/arena-weapon-points.json")) shouldBe "{broken"
        } finally {
            root.toFile().deleteRecursively()
        }
    }
}) {
    companion object {
        private fun point(x: Double) = EventLocation("arena", x, 65.1, 2.5)

        private fun arena() = ru.ruscrafting.events.config.ArenaSettings(
            id = "test",
            enabled = true,
            world = "arena",
            template = "",
            lobby = point(1.5),
            spectator = point(2.5),
            bounds = EventBounds(
                EventLocation("arena", 0.0, 60.0, 0.0),
                EventLocation("arena", 100.0, 100.0, 100.0),
            ),
            spawns = listOf(point(3.5)),
            lootSpawns = (0 until 32).map { point(it + 0.5) },
            weaponCount = 30,
        )
    }
}
