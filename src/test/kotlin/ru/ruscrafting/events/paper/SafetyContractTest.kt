package ru.ruscrafting.events.paper

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.bukkit.Location
import org.bukkit.World
import org.bukkit.potion.PotionEffect
import java.util.UUID

class SafetyContractTest : StringSpec({
    "internal teleport authorization is player and destination bound" {
        val playerId = UUID.randomUUID()
        val world = mockk<World>()
        every { world.uid } returns UUID.randomUUID()
        val expected = Location(world, 12.5, 70.0, -4.5, 90f, 10f)
        val authorizer = InternalTeleportAuthorizer()

        authorizer.authorize(playerId, expected) {
            authorizer.isAuthorized(playerId, expected.clone()) shouldBe true
            authorizer.isAuthorized(playerId, expected.clone().add(0.25, 0.0, 0.0)) shouldBe false
            authorizer.isAuthorized(UUID.randomUUID(), expected) shouldBe false
        }

        authorizer.isAuthorized(playerId, expected) shouldBe false
    }

    "recovery accepts Paper infinite potion duration" {
        PotionSnapshot(
            type = "minecraft:speed",
            duration = PotionEffect.INFINITE_DURATION,
            amplifier = 0,
            ambient = false,
            particles = true,
            icon = true,
        ).validated()
    }
})
