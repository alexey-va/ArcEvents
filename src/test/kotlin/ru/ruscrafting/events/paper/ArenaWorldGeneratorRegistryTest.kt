package ru.ruscrafting.events.paper

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import java.nio.file.Files

class ArenaWorldGeneratorRegistryTest : StringSpec({
    "packaged arena keeps an empty border generator across server restarts" {
        val container = Files.createTempDirectory("arcevents-generator-")
        val arena = Files.createDirectory(container.resolve("events_b5"))
        Files.writeString(arena.resolve(ARENA_TEMPLATE_MARKER), "ttt-minecraft-b5-v1\n")

        val firstLoad = ArenaWorldGeneratorRegistry.generatorFor(container, arena.fileName.toString())
        val secondLoad = ArenaWorldGeneratorRegistry.generatorFor(container, arena.fileName.toString())

        (firstLoad is EmptyArenaChunkGenerator) shouldBe true
        (secondLoad is EmptyArenaChunkGenerator) shouldBe true
    }

    "reviewed Counter-Strike world keeps an empty border generator across server restarts" {
        val container = Files.createTempDirectory("arcevents-cs2-generator-")
        val arena = Files.createDirectory(container.resolve("events_inferno"))
        Files.writeString(arena.resolve(ARENA_TEMPLATE_MARKER), "cs2-inferno-v1\n")

        (ArenaWorldGeneratorRegistry.generatorFor(container, arena.fileName.toString()) is EmptyArenaChunkGenerator) shouldBe true
    }

    "built-in arena restores its deterministic generator" {
        val container = Files.createTempDirectory("arcevents-citadel-generator-")
        val arena = Files.createDirectory(container.resolve("events_citadel"))
        Files.writeString(arena.resolve(ARENA_TEMPLATE_MARKER), TttCitadelBlueprint.TEMPLATE + "\n")

        (ArenaWorldGeneratorRegistry.generatorFor(container, arena.fileName.toString()) is TttCitadelChunkGenerator) shouldBe true
    }

    "unowned worlds get no generator and corrupt owned markers fail closed" {
        val container = Files.createTempDirectory("arcevents-generator-boundary-")
        Files.createDirectory(container.resolve("ordinary_world"))
        ArenaWorldGeneratorRegistry.generatorFor(container, "ordinary_world") shouldBe null
        ArenaWorldGeneratorRegistry.generatorFor(container, "../ordinary_world") shouldBe null

        val arena = Files.createDirectory(container.resolve("events_unknown"))
        Files.writeString(arena.resolve(ARENA_TEMPLATE_MARKER), "unreviewed-template\n")
        shouldThrow<IllegalStateException> {
            ArenaWorldGeneratorRegistry.generatorFor(container, arena.fileName.toString())
        }
    }
})
