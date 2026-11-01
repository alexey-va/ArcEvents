package ru.ruscrafting.events.paper

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.bukkit.FluidCollisionMode
import org.bukkit.Location
import org.bukkit.World
import org.bukkit.entity.Display
import org.bukkit.entity.Player
import org.bukkit.util.Transformation
import org.bukkit.util.Vector
import org.joml.Quaternionf
import org.joml.Vector3f
import ru.arc.paper.display.PacketBlockDisplay
import ru.arc.paper.display.PacketDisplayAudienceGroup
import ru.arc.paper.display.PaperPacketDisplays
import ru.arc.paper.testing.MockBukkitTestRuntime
import java.util.UUID

class TttScopeLaserTest : StringSpec({
    "laser ray stops before unloaded chunks including negative coordinates and brief corner crossings" {
        val visited = mutableListOf<Pair<Int, Int>>()
        scopeLaserLoadedRange(Vector(15.9, 64.0, 15.0), Vector(1.0, 0.0, 1.0).normalize(), 32.0) { x, z ->
            visited += x to z
            x == 0
        } shouldBe ((.1 * kotlin.math.sqrt(2.0) - .01) plusOrMinus 1e-8)
        visited shouldBe listOf(0 to 0, 1 to 0)
        scopeLaserLoadedRange(Vector(-.2, 64.0, 0.0), Vector(-1.0, 0.0, 0.0), 32.0) { x, _ -> x == -1 } shouldBe (15.79 plusOrMinus 1e-8)
        scopeLaserLoadedRange(Vector(16.0, 64.0, 0.0), Vector(-1.0, 0.0, 0.0), 32.0) { x, _ -> x == 1 } shouldBe 0.0
    }

    "vertical rays and loaded rays keep their full bounded range without inventing an impact" {
        var checks = 0
        scopeLaserLoadedRange(Vector(1.0, 64.0, 1.0), Vector(-0.0, 1.0, 0.0), 32.0) { _, _ -> checks++; true } shouldBe 32.0
        checks shouldBe 1
        scopeLaserLoadedRange(Vector(0.0, 64.0, 0.0), Vector(1.0, 0.0, 0.0), 32.0) { _, _ -> true } shouldBe 32.0
        scopeLaserLoadedRange(Vector(), Vector(0.0, 0.0, 1.0), 32.0) { _, _ -> false } shouldBe 0.0
    }

    "beam centers join the muzzle and ray endpoint for vertical backward and diagonal aim" {
        for (delta in listOf(Vector(0.0, 0.0, 12.0), Vector(0.0, 0.0, -12.0), Vector(0.0, 12.0, 0.0), Vector(-3.0, 4.0, -7.0))) {
            val transform = scopeLaserBeamTransform(delta, .012f)
            fun point(z: Float): Vector3f = Vector3f(.5f, .5f, z).mul(transform.scale)
                .rotate(transform.leftRotation).add(transform.translation)
            point(0f).length().toDouble() shouldBe (0.0 plusOrMinus 1e-6)
            val end = point(1f)
            end.x.toDouble() shouldBe (delta.x plusOrMinus 1e-5)
            end.y.toDouble() shouldBe (delta.y plusOrMinus 1e-5)
            end.z.toDouble() shouldBe (delta.z plusOrMinus 1e-5)
            transform.scale.x shouldBe .012f
            transform.scale.y shouldBe .012f
        }
    }

    "native CENTER minus Z follows current camera yaw and pitch even with a stale server look sample" {
        val eyeOffset = -.18f
        val serverPitch = 22f
        val origin = scopeLaserCameraEyeOffset(eyeOffset, serverPitch).add(0f, 0f, -.7f)
        val transform = scopeLaserBeamTransform(Vector(0.0, 0.0, -19.3), .012f, origin)
        for (yaw in listOf(0f, 95f, -170f)) for (pitch in listOf(-90f, -37f, serverPitch, 63f, 90f)) {
            // Exact 1.21.11 DisplayRenderer.calculateOrientation(CENTER), not a server aim rotation.
            val camera = Quaternionf().rotationYXZ(Math.toRadians((180f - yaw).toDouble()).toFloat(),
                -Math.toRadians(pitch.toDouble()).toFloat(), 0f)
            val start = laserPoint(transform, 0f).rotate(camera)
            val end = laserPoint(transform, 1f).rotate(camera)
            val direction = Vector3f(end).sub(start).normalize()
            val expected = Location(null, 0.0, 0.0, 0.0, yaw, pitch).direction.toVector3f()
            direction.distance(expected).toDouble() shouldBe (0.0 plusOrMinus 1e-6)
            // Stale pitch can shift only the short eye/head compensation, not the beam's direction.
            val eye = Vector3f(start).sub(Vector3f(expected).mul(.7f))
            (eye.distance(Vector3f(0f, eyeOffset, 0f)) <= 2 * kotlin.math.abs(eyeOffset) + 1e-5f) shouldBe true
        }
    }

    "camera compensation and fixed observer transforms share the same carrier-relative ray at the sampled pitch" {
        for (height in listOf(-.18f, -.23f)) for (pitch in listOf(-90f, -40f, 0f, 55f, 90f)) {
            val yaw = -73f
            val direction = Location(null, 0.0, 0.0, 0.0, yaw, pitch).direction
            val rotation = Quaternionf().rotationYXZ(Math.toRadians((180f - yaw).toDouble()).toFloat(),
                -Math.toRadians(pitch.toDouble()).toFloat(), 0f)
            val cameraOrigin = scopeLaserCameraEyeOffset(height, pitch).add(0f, 0f, -.7f)
            val owner = scopeLaserBeamTransform(Vector(0.0, 0.0, -11.3), .012f, cameraOrigin)
            val fixedOrigin = direction.toVector3f().mul(.7f).add(0f, height, 0f)
            val observer = scopeLaserBeamTransform(direction.clone().multiply(11.3), .012f, fixedOrigin)
            for (z in listOf(0f, 1f)) {
                laserPoint(owner, z).rotate(rotation).distance(laserPoint(observer, z)).toDouble() shouldBe (0.0 plusOrMinus 1e-5)
                val carrier = Vector3f(23f, 65.8f, -19f)
                val expected = direction.toVector3f().mul(if (z == 0f) .7f else 12f).add(0f, height, 0f).add(carrier)
                laserPoint(observer, z).add(carrier).distance(expected).toDouble() shouldBe (0.0 plusOrMinus 1e-5)
            }
        }
    }

    "laser mounts four stable parts with disjoint owner and observer audiences and never teleports them" {
        MockBukkitTestRuntime.open().use {
            val world = mockk<World>(relaxed = true)
            val shooter = mockk<Player>(relaxed = true)
            val observer = mockk<Player>(relaxed = true)
            val displays = mockk<PaperPacketDisplays>(relaxed = true)
            val parts = List(4) { mockk<PacketBlockDisplay>(relaxed = true) }
            val groups = List(2) { mockk<PacketDisplayAudienceGroup>(relaxed = true) }
            val predicates = mutableListOf<(Player) -> Boolean>()
            val births = mutableListOf<Location>()
            every { world.uid } returns UUID.randomUUID()
            every { world.isChunkLoaded(any<Int>(), any<Int>()) } returns true
            every { world.rayTrace(any(), any(), any(), FluidCollisionMode.NEVER, true, 0.0, any()) } returns null
            every { shooter.world } returns world
            every { shooter.uniqueId } returns UUID.randomUUID()
            every { observer.uniqueId } returns UUID.randomUUID()
            every { observer.canSee(shooter) } returns true
            every { shooter.isOnline } returns true
            every { shooter.eyeLocation } answers { Location(world, 3.0, 65.62, -4.0, 43f, -37f) }
            every { shooter.eyeHeight } returns 1.62
            every { shooter.height } returns 1.8
            every { displays.createAudienceGroup(any()) } answers {
                predicates += firstArg<(Player) -> Boolean>()
                groups[predicates.lastIndex]
            }
            every { displays.spawnBlock(any(), any()) } answers {
                births += firstArg<Location>().clone()
                parts[births.lastIndex]
            }
            val laser = TttScopeLaser(shooter, displays)
            try {
                predicates.size shouldBe 2
                predicates[0](shooter) shouldBe true
                predicates[0](observer) shouldBe false
                predicates[1](shooter) shouldBe false
                predicates[1](observer) shouldBe true
                every { observer.canSee(shooter) } returns false
                predicates[1](observer) shouldBe false
                births.size shouldBe 4
                births.all { it.yaw == 0f && it.pitch == 0f } shouldBe true
                parts.forEach { part -> verify(exactly = 1) { part.attachTo(shooter) } }
                for (index in parts.indices) verify {
                    parts[index].billboard = if (index < 2) Display.Billboard.CENTER else Display.Billboard.FIXED
                    parts[index].audienceGroup = groups[index / 2]
                }
                repeat(3) { laser.update() }
                parts.forEach { part -> verify(exactly = 0) { part.teleport(any()) } }
                verify(exactly = 3) { parts[0].isVisibleByDefault = true; parts[2].isVisibleByDefault = true }
                verify(exactly = 0) { parts[1].isVisibleByDefault = true; parts[3].isVisibleByDefault = true }
                every { world.isChunkLoaded(any<Int>(), any<Int>()) } returns false
                laser.update()
                parts.forEach { part -> verify { part.isVisibleByDefault = false } }
            } finally {
                laser.close()
                laser.close()
            }
            parts.forEach { part -> verify(exactly = 1) { part.remove() } }
            groups.forEach { group -> verify(exactly = 1) { group.close() } }
        }
    }
})

private fun laserPoint(transform: Transformation, z: Float): Vector3f = Vector3f(.5f, .5f, z)
    .mul(transform.scale).rotate(transform.leftRotation).add(transform.translation)
