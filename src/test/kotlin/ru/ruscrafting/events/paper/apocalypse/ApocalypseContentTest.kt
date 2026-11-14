package ru.ruscrafting.events.paper.apocalypse

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import ru.ruscrafting.events.domain.apocalypse.source.ApocalypseSourceArchive
import java.nio.file.Files
import java.security.MessageDigest

class ApocalypseContentTest : StringSpec({
    "verified local source is reused without a network request and changed bytes are rejected" {
        val directory = Files.createTempDirectory("apocalypse-content-")
        val path = directory.resolve("fixture.jar")
        val bytes = "source archive fixture".toByteArray()
        Files.write(path, bytes)
        val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        val source = ApocalypseSourceArchive("fixture", "fixture.jar", "https://invalid.invalid/never-requested", hash)
        ApocalypseContent.ensureArchive(directory, source) shouldBe path
        Files.write(path, byteArrayOf(1, 2, 3))
        shouldThrow<IllegalArgumentException> { ApocalypseContent.ensureArchive(directory, source) }
    }
})
