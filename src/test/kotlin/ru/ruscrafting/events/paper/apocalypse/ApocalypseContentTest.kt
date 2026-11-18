package ru.ruscrafting.events.paper.apocalypse

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import ru.ruscrafting.events.domain.apocalypse.source.ApocalypseSourceArchive
import java.net.InetAddress
import java.net.ServerSocket
import java.nio.file.Files
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

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

    "retiring the generation interrupts a stalled TLS request and drains its temporary file" {
        val root = Files.createTempDirectory("apocalypse-interrupted-")
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { server ->
            server.soTimeout = 5_000
            val source = ApocalypseSourceArchive("fixture", "fixture.jar", "https://127.0.0.1:${server.localPort}/fixture.jar", "0".repeat(64))
            ApocalypseContent(root, listOf(source)).use { content ->
                val layout = content.layout(1)
                server.accept().use { peer ->
                    peer.soTimeout = 5_000
                    peer.getInputStream().read() shouldBe 22 // TLS ClientHello record; withhold the response.
                    content.closeAsync().get(5, TimeUnit.SECONDS)
                    layout.isCompletedExceptionally shouldBe true
                    Files.list(root.resolve("content/apocalypse-original")).use { it.count() } shouldBe 0L
                }
            }
        }
    }
})
