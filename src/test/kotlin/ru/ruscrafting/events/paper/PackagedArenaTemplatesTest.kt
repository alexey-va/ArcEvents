package ru.ruscrafting.events.paper

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import java.security.MessageDigest

class PackagedArenaTemplatesTest : StringSpec({
    "the classic B5 arena keeps its external source and checksum" {
        val template = requireNotNull(PackagedArenaTemplates.find("ttt-minecraft-b5-v1"))
        template.format shouldBe PackagedArenaFormat.SPONGE_V2_SCHEMATIC
        template.title shouldBe "TTT Minecraft B5"
        template.source shouldBe "https://www.planetminecraft.com/project/ttt_minecraft_b5-minecraft-map/"
        template.sourceArchiveSha256 shouldBe "93f385b9b6916a81dd425cbce3bdfd7c113447cf1192c5e7045c47cb1493ed6e"
        template.author shouldBe "SukovicM (original GMod map by finniespin)"
        template.license shouldBe "Server use only; no redistribution grant"
        template.assetSource shouldBe PackagedArenaSource.DATA_FOLDER
        template.files.single().sha256 shouldBe "b072374dde6c6bbc136fd394e469c1f13d7e66a304b2371944a448bcad1ee289"
    }

    "the two redistributable Overcast arenas resolve to the exact classpath schematics" {
        PackagedArenaTemplates.ids shouldBe setOf("ttt-minecraft-b5-v1", "deadwood-v1", "riot-in-prison-v1")
        val expected = mapOf(
            "deadwood-v1" to "89ef35ad7f0e97863ecc88aad0cb355f6c335eb772b16d8c3b96a7e58c4fc9d8",
            "riot-in-prison-v1" to "db93042bd04a406cab4c9c8e020cce997e4d2f03de9288c99e0fec2bb7ffadbc",
        )
        expected.forEach { (id, checksum) ->
            val template = requireNotNull(PackagedArenaTemplates.find(id))
            template.assetSource shouldBe PackagedArenaSource.CLASSPATH
            template.license shouldBe "CC BY-SA 4.0"
            val file = template.files.single()
            file.sha256 shouldBe checksum
            val bytes = requireNotNull(javaClass.classLoader.getResourceAsStream(file.resource)).use { it.readBytes() }
            java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)) shouldBe checksum
        }
    }
})
