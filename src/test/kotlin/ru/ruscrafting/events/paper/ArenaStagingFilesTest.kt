package ru.ruscrafting.events.paper

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.nio.file.Files

class ArenaStagingFilesTest : FunSpec({
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
