package ru.ruscrafting.events.paper

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe

class PackagedArenaTemplatesTest : StringSpec({
    "only the classic B5 arena remains in the packaged registry" {
        PackagedArenaTemplates.ids.shouldContainExactly("ttt-minecraft-b5-v1")

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
})
