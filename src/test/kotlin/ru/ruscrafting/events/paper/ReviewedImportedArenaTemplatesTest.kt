package ru.ruscrafting.events.paper

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe

class ReviewedImportedArenaTemplatesTest : StringSpec({
    "Counter-Strike world registry pins the three reviewed imports" {
        ReviewedImportedArenaTemplates.ids.shouldContainExactlyInAnyOrder(
            "cs2-inferno-v1",
            "cs2-mirage-v1",
            "cs2-nuke-v1",
        )

        ReviewedImportedArenaTemplates.find("cs2-inferno-v1")?.sourceArchiveSha256 shouldBe
            "b4d52213e2539d790b57e66a951467ad23c6be4f58a5c3480626a5d7e1dea81a"
        ReviewedImportedArenaTemplates.find("cs2-mirage-v1")?.sourceArchiveSha256 shouldBe
            "38bcb476c0007ac2d03a7d81c6b6722012f29a1cc00634026e60fc08818f83ef"
        ReviewedImportedArenaTemplates.find("cs2-nuke-v1")?.sourceArchiveSha256 shouldBe
            "080c1d6c14e1eaeca3525c031916cdfdac176855f1c44c760f889e6446bb7851"
    }
})
