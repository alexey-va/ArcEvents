package ru.ruscrafting.events.paper

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import org.bukkit.Material

class TttBodyVisualTest : StringSpec({
    "corpse layout forms a complete anonymous Minecraft body" {
        val parts = bodyVisualParts()

        parts.map(BodyVisualPart::kind).shouldContainExactlyInAnyOrder(
            BodyVisualKind.HEAD,
            BodyVisualKind.TORSO,
            BodyVisualKind.LEFT_ARM,
            BodyVisualKind.RIGHT_ARM,
            BodyVisualKind.LEFT_LEG,
            BodyVisualKind.RIGHT_LEG,
        )
        parts.single { it.kind == BodyVisualKind.HEAD }.material shouldBe Material.SKELETON_SKULL
    }
})
