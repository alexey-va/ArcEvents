package ru.ruscrafting.events.paper

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.nio.file.Files
import java.nio.file.Path

class ArenaStagingFilesTest : FunSpec({
    test("the first generated chunk can only be written into an already owned world") {
        val container = Files.createTempDirectory("arena-first-generation")
        val store = ArenaStagingFiles(container)
        val world = "events_fishing"
        store.createGeneratedWorld(world, FishingArenaGenerator.TEMPLATE)
        Files.readString(container.resolve(world).resolve(ARENA_TEMPLATE_MARKER)).trim() shouldBe FishingArenaGenerator.TEMPLATE
        (ArenaWorldGeneratorRegistry.generatorFor(container, world) is FishingArenaGenerator) shouldBe true
        // An interruption before native generation leaves the final world intact on the next startup.
        store.recover(emptySet()).removed shouldBe 0
        Files.isDirectory(container.resolve(world)) shouldBe true

        val foreign = Files.createDirectory(container.resolve("foreign"))
        shouldThrow<java.nio.file.FileAlreadyExistsException> { store.createGeneratedWorld("foreign", FishingArenaGenerator.TEMPLATE) }
        Files.exists(foreign.resolve(ARENA_TEMPLATE_MARKER)) shouldBe false
        store.recover(emptySet()).removed shouldBe 1
    }

    test("interrupted owned stage is removed on worker recovery and unmarked legacy is retained") {
        val container = Files.createTempDirectory("arena-stage-recovery")
        val store = ArenaStagingFiles(container)
        val stage = store.create("events_b5", "ttt-minecraft-b5-v1")
        val region = Files.createDirectory(stage.resolve("region"))
        Files.write(region.resolve("r.0.0.mca"), byteArrayOf(1, 2, 3))
        val legacy = Files.createDirectory(container.resolve(".arcevents-stage-legacy"))
        val result = store.recover(emptySet())
        result.removed shouldBe 1
        result.retainedLegacy shouldBe listOf(legacy.fileName.toString())
        Files.exists(stage) shouldBe false
        Files.exists(legacy) shouldBe true
    }

    test("an interrupted Apocalypse stage can be recovered and a fresh campaign publishes its seed marker together with the directory") {
        val container = Files.createTempDirectory("apocalypse-campaign-stage")
        val relative = Path.of("").toAbsolutePath().relativize(container)
        val store = ArenaStagingFiles(relative)
        val marker = "${ru.ruscrafting.events.paper.apocalypse.ApocalypseOriginalGenerator.TEMPLATE}:68200133"
        val stage = store.create("campaign", marker)
        Files.exists(container.resolve("campaign")) shouldBe false
        store.recover(emptySet()).removed shouldBe 1
        Files.exists(stage) shouldBe false
        store.createGeneratedWorld("campaign", marker)
        Files.readString(container.resolve("campaign").resolve(ARENA_TEMPLATE_MARKER)).trim() shouldBe marker
        shouldThrow<IllegalArgumentException> { store.createGeneratedWorld("bad", "apocalypse-original-v1:broken") }
    }

    test("an owned temporary stage from before the map upgrade can still be recovered") {
        val container = Files.createTempDirectory("arena-stage-retired")
        val store = ArenaStagingFiles(container)
        val stage = store.create("arcevents_fishing_v4", "fishing-v4")
        store.recover(emptySet()).removed shouldBe 1
        Files.exists(stage) shouldBe false
        shouldThrow<IllegalArgumentException> { store.createGeneratedWorld("another_fishing", "fishing-v4") }
    }

    test("recovery never removes a loaded staging world") {
        val container = Files.createTempDirectory("arena-stage-loaded")
        val store = ArenaStagingFiles(container)
        val stage = store.create("events_b5", "ttt-minecraft-b5-v1")
        shouldThrow<IllegalStateException> { store.recover(setOf(stage.fileName.toString())) }
        Files.exists(stage.resolve(ArenaStagingFiles.INTENT)) shouldBe true
    }

    test("stage tree with a symbolic link is rejected before deleting any owned file") {
        val container = Files.createTempDirectory("arena-stage-symlink")
        val store = ArenaStagingFiles(container)
        val stage = store.create("events_b5", "ttt-minecraft-b5-v1")
        val outside = Files.createTempDirectory("arena-stage-outside")
        Files.writeString(outside.resolve("keep"), "outside")
        Files.createSymbolicLink(stage.resolve("linked"), outside)
        shouldThrow<IllegalArgumentException> { store.recover(emptySet()) }
        Files.exists(stage.resolve(ArenaStagingFiles.INTENT)) shouldBe true
        Files.readString(outside.resolve("keep")) shouldBe "outside"
    }

    test("copied ownership marker cannot authorize another folder") {
        val container = Files.createTempDirectory("arena-stage-identity")
        val store = ArenaStagingFiles(container)
        val stage = store.create("events_b5", "ttt-minecraft-b5-v1")
        val other = Files.createDirectory(container.resolve(".arcevents-stage-unrelated"))
        Files.copy(stage.resolve(ArenaStagingFiles.INTENT), other.resolve(ArenaStagingFiles.INTENT))
        shouldThrow<IllegalArgumentException> { store.delete(other) }
        Files.exists(other.resolve(ArenaStagingFiles.INTENT)) shouldBe true
    }
})
