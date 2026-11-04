package ru.ruscrafting.events.paper

import com.github.retrooper.packetevents.PacketEvents
import com.github.retrooper.packetevents.PacketEventsAPI
import com.github.retrooper.packetevents.manager.server.ServerManager
import com.github.retrooper.packetevents.manager.server.ServerVersion
import com.google.gson.GsonBuilder
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.verify
import org.bukkit.Bukkit
import org.bukkit.FluidCollisionMode
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.Particle
import org.bukkit.Sound
import org.bukkit.SoundCategory
import org.bukkit.World
import org.bukkit.block.Block
import org.bukkit.block.data.BlockData
import org.bukkit.entity.Entity
import org.bukkit.entity.Player
import org.bukkit.inventory.MainHand
import org.bukkit.util.BoundingBox
import org.bukkit.util.RayTraceResult
import org.bukkit.util.Transformation
import org.bukkit.util.Vector
import org.joml.Vector3f
import ru.arc.core.Tasks
import ru.arc.core.TestTaskScheduler
import ru.arc.paper.display.PaperPacketDisplays
import ru.arc.paper.display.PacketBlockDisplay
import ru.ruscrafting.events.domain.FirearmProjectile
import ru.ruscrafting.events.domain.FirearmId
import ru.ruscrafting.events.domain.FirearmSoundPreset
import ru.ruscrafting.events.domain.FirearmSpec
import ru.ruscrafting.events.domain.TttFirearmCatalog
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

class FirearmShotsTest : StringSpec({
    "launcher blasts use radial falloff and respect both target admission and cover" {
        for (covered in listOf(false, true)) withShotFixture { f ->
            val contact = Vector(0.0, 65.0, 2.0)
            val wall = mockk<Block>(relaxed = true)
            val outsider = mockk<Player>(relaxed = true)
            every { outsider.isValid } returns true
            every { f.target.boundingBox } returns BoundingBox(-.3, 64.0, 2.5, .3, 66.0, 3.1)
            every { f.world.getNearbyEntities(any<Location>(), any<Double>(), any<Double>(), any<Double>()) } returns
                listOf(f.player, outsider, f.target)
            every { f.world.rayTraceBlocks(any(), any(), any(), FluidCollisionMode.NEVER, true) } returns
                if (covered) RayTraceResult(contact, wall, null) else null
            f.ray = { _, _, _ -> RayTraceResult(contact.clone(), wall, null) }
            f.fire(TttFirearmCatalog.specs.getValue(FirearmId.HAND_CANNON).copy(projectile = FirearmProjectile.ROCKET)) shouldBe true
            f.until { f.rays.isNotEmpty() }
            f.until { if (covered) f.rays.isNotEmpty() else f.hits.isNotEmpty() }
            if (covered) f.hits.size shouldBe 0
            else {
                f.hits.size shouldBe 1
                (f.hits.single().second in 0.1..15.99) shouldBe true
                f.hitDetails.single().headshot shouldBe false
            }
            verify(exactly = 0) { outsider.boundingBox }
        }
        firearmBlastDamage(20.0, 0.0, 3.0) shouldBe 20.0
        firearmBlastDamage(20.0, 1.5, 3.0) shouldBe 12.5
        firearmBlastDamage(20.0, 3.0, 3.0) shouldBe 0.0
    }

    "blast candidates remain queued across the same per-tick work budget" {
        withShotFixture { f ->
            var examined = 0
            every { f.target.boundingBox } answers {
                examined++
                BoundingBox(-.3, 64.0, 2.5, .3, 66.0, 3.1)
            }
            every { f.world.getNearbyEntities(any<Location>(), any<Double>(), any<Double>(), any<Double>()) } returns List(250) { f.target }
            every { f.world.rayTraceBlocks(any(), any(), any(), FluidCollisionMode.NEVER, true) } returns null
            f.ray = { _, _, _ -> RayTraceResult(Vector(0.0, 65.0, 2.0), mockk<Block>(relaxed = true), null) }
            f.fire(TttFirearmCatalog.specs.getValue(FirearmId.HAND_CANNON).copy(projectile = FirearmProjectile.ROCKET)) shouldBe true
            f.until { examined > 0 }
            (examined <= 96) shouldBe true
            f.hits.size shouldBe 0
            f.until(500) { f.hits.isNotEmpty() }
            examined shouldBe 250
            f.hits.size shouldBe 1
        }
    }

    "a blast detonating on the last flight tick keeps its accepted work after expiry" {
        withShotFixture { f ->
            var contact = Vector()
            every { f.target.boundingBox } answers {
                BoundingBox(contact.x - .3, contact.y - 1, contact.z + .4,
                    contact.x + .3, contact.y + 1, contact.z + 1.0)
            }
            every { f.world.getNearbyEntities(any<Location>(), any<Double>(), any<Double>(), any<Double>()) } returns List(4) { f.target }
            every { f.world.rayTraceBlocks(any(), any(), any(), FluidCollisionMode.NEVER, true) } returns null
            f.ray = { origin, direction, _ ->
                if (f.rays.size < 39) null else {
                    contact = origin.toVector().add(direction.clone())
                    RayTraceResult(contact.clone(), mockk<Block>(relaxed = true), null)
                }
            }
            f.fire(TttFirearmCatalog.specs.getValue(FirearmId.HAND_CANNON)
                .copy(range = 160.0, spreadDegrees = 0.0, projectile = FirearmProjectile.ROCKET)) shouldBe true
            f.until { f.rays.size >= 20 }
            f.until { f.hits.isNotEmpty() }
            f.rays.last().tick shouldBe 40
            f.hits.size shouldBe 1
        }
    }

    "blast cover never queries an unloaded intervening chunk" {
        withShotFixture { f ->
            var examined = false
            f.eye.x = 15.5
            f.eye.z = 12.0
            every { f.world.isChunkLoaded(0, 1) } returns false
            every { f.target.boundingBox } answers {
                examined = true
                BoundingBox(16.2, 64.0, 17.2, 16.8, 66.0, 17.8)
            }
            every { f.world.getNearbyEntities(any<Location>(), any<Double>(), any<Double>(), any<Double>()) } returns listOf(f.target)
            f.ray = { _, _, _ -> RayTraceResult(Vector(15.5, 65.0, 15.5), mockk<Block>(relaxed = true), null) }
            f.fire(TttFirearmCatalog.specs.getValue(FirearmId.HAND_CANNON).copy(projectile = FirearmProjectile.ROCKET)) shouldBe true
            f.until { examined }
            f.hits.size shouldBe 0
            verify(exactly = 0) { f.world.rayTraceBlocks(any(), any(), any(), FluidCollisionMode.NEVER, true) }
        }
    }

    "loaded segment clipping protects negative coordinates exact endpoints and diagonal corner crossings" {
        firearmLoadedRange(Vector(15.0, 64.0, 0.0), Vector(1.0, 0.0, 0.0), 1.0) { x, _ -> x == 0 } shouldBe (.99 plusOrMinus 1e-8)
        firearmLoadedRange(Vector(-.2, 64.0, 0.0), Vector(-1.0, 0.0, 0.0), 32.0) { x, _ -> x == -1 } shouldBe (15.79 plusOrMinus 1e-8)
        firearmLoadedRange(Vector(16.0, 64.0, 0.0), Vector(-1.0, 0.0, 0.0), 32.0) { x, _ -> x == 1 } shouldBe 0.0
        firearmLoadedRange(Vector(15.9, 64.0, 15.0), Vector(1.0, 0.0, 1.0).normalize(), 32.0) { x, _ -> x == 0 } shouldBe
            ((.1 * kotlin.math.sqrt(2.0) - .01) plusOrMinus 1e-8)
        firearmLoadedRange(Vector(15.0, 64.0, 15.0), Vector(1.0, 0.0, 1.0).normalize(), 32.0) { x, z -> x == z } shouldBe
            ((kotlin.math.sqrt(2.0) - .01) plusOrMinus 1e-8)
        firearmLoadedRange(Vector(), Vector(-0.0, 1.0, 0.0), 32.0) { _, _ -> true } shouldBe 32.0
    }

    "automatic weapons fire successive rounds while shotguns keep one pellet volley and total damage" {
        firearmRoundPellets(TttFirearmCatalog.specs.getValue(FirearmId.G36)) shouldBe listOf(1, 1, 1)
        firearmRoundPellets(TttFirearmCatalog.specs.getValue(FirearmId.RPL_20)) shouldBe listOf(1, 1, 1, 1)
        firearmRoundPellets(TttFirearmCatalog.specs.getValue(FirearmId.DOUBLE_BARREL)) shouldBe listOf(10)
        firearmRoundPellets(TttFirearmCatalog.specs.getValue(FirearmId.VEPR_12)) shouldBe listOf(8)
        for (spec in TttFirearmCatalog.specs.values) firearmRoundPellets(spec).sum() shouldBe spec.pellets
    }

    "partial triggers preserve each configured round pellet allocation and every other weapon property" {
        val burst = TttFirearmCatalog.specs.getValue(FirearmId.G36)
        firearmTriggerSpec(burst, 0) shouldBe null
        firearmTriggerSpec(burst, -1) shouldBe null
        firearmTriggerSpec(burst, 30) shouldBe burst
        for (pellets in listOf(3, 4, 5, 8)) {
            val spec = burst.copy(pellets = pellets)
            val pattern = firearmRoundPellets(spec)
            for (rounds in 1..spec.roundsPerShot) {
                val partial = requireNotNull(firearmTriggerSpec(spec, rounds))
                partial shouldBe spec.copy(roundsPerShot = rounds, pellets = pattern.take(rounds).sum())
                firearmRoundPellets(partial) shouldBe pattern.take(rounds)
            }
        }
        for (id in listOf(FirearmId.DOUBLE_BARREL, FirearmId.VEPR_12)) {
            val spec = TttFirearmCatalog.specs.getValue(id)
            firearmTriggerSpec(spec, 1) shouldBe spec
        }
    }

    "partial bursts physically launch only their paid rounds and report completion after the final muzzle" {
        for (rounds in 1..2) withShotFixture { f ->
            f.enableVisuals()
            val configured = TttFirearmCatalog.specs.getValue(FirearmId.G36)
            val partial = requireNotNull(firearmTriggerSpec(configured, rounds))
            val completed = mutableListOf<Pair<Boolean, Int>>()
            f.fire(partial, afterFiring = { completed += it to f.births.size }) shouldBe true
            completed shouldBe emptyList()
            f.until { completed.isNotEmpty() }
            completed shouldBe listOf(true to rounds)
            f.births.size shouldBe rounds
            f.scheduler.tick(50)
            f.births.size shouldBe rounds
            completed.size shouldBe 1
        }
    }

    "completion follows the last delayed round and leaves accepted bullets eligible for damage" {
        withShotFixture { f ->
            f.enableVisuals()
            val burst = TttFirearmCatalog.specs.getValue(FirearmId.G36).copy(spreadDegrees = 0.0)
            val completed = mutableListOf<Pair<Boolean, Int>>()
            f.ray = { origin, direction, _ -> RayTraceResult(origin.toVector().add(direction.clone()), f.target) }
            f.fire(burst, afterFiring = {
                completed += it to f.births.size
                f.zoom = null // An ordinary reload may lower the model after the final muzzle.
            }) shouldBe true
            f.until { f.births.isNotEmpty() }
            completed shouldBe emptyList()
            f.until { completed.isNotEmpty() }
            completed shouldBe listOf(true to 3)
            f.until { f.hits.size == 3 }
            f.hits.sumOf { it.second } shouldBe (burst.damagePerPellet * 3 plusOrMinus 1e-8)
        }
    }

    "cancelled closed or unloaded bursts complete once without authorizing automatic reload" {
        val burst = TttFirearmCatalog.specs.getValue(FirearmId.G36)
        for (abort in listOf<(ShotFixture) -> Unit>(
            { it.valid = false },
            { it.shots.close() },
            { f -> every { f.world.isChunkLoaded(any<Int>(), any<Int>()) } returns false },
        )) withShotFixture { f ->
            f.enableVisuals()
            val completed = mutableListOf<Boolean>()
            f.fire(burst, afterFiring = completed::add) shouldBe true
            abort(f)
            f.scheduler.tick(50)
            completed shouldBe listOf(false)
            f.births.size shouldBe 0
        }
        withShotFixture { f ->
            f.enableVisuals()
            val completed = mutableListOf<Boolean>()
            f.fire(burst, afterFiring = completed::add) shouldBe true
            f.until { f.births.size == 1 }
            f.valid = false
            f.scheduler.tick(50)
            completed shouldBe listOf(false)
            f.births.size shouldBe 1
        }
    }

    "short tracer is centered behind its collision point in vertical backward and diagonal directions" {
        for (direction in listOf(Vector(0, 0, 1), Vector(0, 0, -1), Vector(0, 1, 0), Vector(-3, 4, -7).normalize())) {
            val transform = firearmBulletTransform(direction)
            val tip = Vector3f(.5f, .5f, 1f).mul(transform.scale).rotate(transform.leftRotation).add(transform.translation)
            tip.length().toDouble() shouldBe (0.0 plusOrMinus 1e-6)
            val tail = Vector3f(.5f, .5f, 0f).mul(transform.scale).rotate(transform.leftRotation).add(transform.translation)
            tail.length().toDouble() shouldBe (.65 plusOrMinus 1e-6)
            transform.scale.x shouldBe .024f
            transform.scale.y shouldBe .024f
        }
    }

    "point blank zeroing keeps the muzzle before contact and never reverses or produces NaN" {
        val direction = Vector(0, 0, 1)
        val offset = Vector(-.5, -.2, 2.0)
        for (distance in listOf(0.0, .01, .2, 2.0, 30.0)) {
            val clipped = firearmMuzzleBeforeContact(offset, direction, distance)
            (clipped.z <= (distance - .03).coerceAtLeast(0.0) + 1e-8) shouldBe true
            val velocity = firearmZeroingDirection(clipped, direction.clone().multiply(distance), direction)
            velocity.length() shouldBe (1.0 plusOrMinus 1e-8)
            (velocity.dot(direction) > 0.0) shouldBe true
        }
    }

    "closest points use the complete swept segment including zero length and the near miss boundary" {
        val start = Vector(0.0, 64.0, 0.0)
        val end = Vector(0.0, 64.0, 10.0)
        firearmClosestPointOnSegment(Vector(1.0, 65.0, 5.0), start, end) shouldBe Vector(0.0, 64.0, 5.0)
        firearmClosestPointOnSegment(Vector(0.0, 64.0, -1.0), start, end) shouldBe start
        firearmClosestPointOnSegment(Vector(0.0, 64.0, 11.0), start, end) shouldBe end
        firearmClosestPointOnSegment(Vector(1.0, 65.0, 5.0), start, start) shouldBe start
        for ((offset, inside) in listOf(2.0 to true, 2.0001 to false)) {
            val eye = Vector(offset, 64.0, 5.0)
            (eye.distanceSquared(firearmClosestPointOnSegment(eye, start, end)) <= 4.0) shouldBe inside
        }
        start shouldBe Vector(0.0, 64.0, 0.0)
        end shouldBe Vector(0.0, 64.0, 10.0)
    }

    "recoil packets add only their delta and recovery preserves independent mouse movement" {
        withShotFixture { f ->
            val kick = firearmRecoilPacket(.2f, -2f)
            kick.isRelativeYaw shouldBe true
            kick.isRelativePitch shouldBe true
            var currentPitch = 10f + kick.pitch
            currentPitch += 7f // a real mouse movement after the kick
            var debt = -kick.pitch * .8f
            repeat(30) {
                val recovered = firearmRecoilRecovery(debt)
                currentPitch += recovered
                debt -= recovered
            }
            currentPitch.toDouble() shouldBe (16.6 plusOrMinus 1e-5)
            debt shouldBe 0f
            f.scheduler.timerCount() shouldBe 0
        }
    }

    "vertical recoil never creates recovery debt for an angle the client cannot apply" {
        firearmRecoilKick(-90f, 3f, 0f) shouldBe 0f
        firearmRecoilKick(-89.7f, 3f, 0f).toDouble() shouldBe (-.3 plusOrMinus 1e-5)
        firearmRecoilKick(89f, 3f, 0f) shouldBe -3f
        firearmRecoilKick(0f, 3f, 8.5f) shouldBe -.5f
        firearmRecoilKick(0f, 3f, 10f) shouldBe 0f
    }

    "admission reserves the whole trigger and a full engine or invalid shooter has no partial shot" {
        withShotFixture { f ->
            f.valid = false
            f.fire() shouldBe false
            f.scheduler.timerCount() shouldBe 0
            f.valid = true
            val scatter = f.spec.copy(pellets = 16)
            repeat(12) { f.fire(scatter) shouldBe true }
            var rejectedCompletions = 0
            f.fire(scatter, afterFiring = { rejectedCompletions++ }) shouldBe false
            rejectedCompletions shouldBe 0
            f.rays.isEmpty() shouldBe true
            f.hits.isEmpty() shouldBe true
            f.shots.close()
            f.scheduler.tick(3)
            f.rays.isEmpty() shouldBe true
            f.scheduler.timerCount() shouldBe 0
            f.fire() shouldBe false
            rejectedCompletions shouldBe 0
        }
    }

    "flight sweeps each complete segment applies gravity and delivers one later entity impact" {
        withShotFixture { f ->
            f.ray = { origin, direction, _ ->
                if (origin.z >= 14.0) RayTraceResult(origin.toVector().add(direction.clone().multiply(3.0)), f.target) else null
            }
            f.fire() shouldBe true
            f.hits.isEmpty() shouldBe true
            f.until { f.hits.isNotEmpty() }
            f.rays.size shouldBe 2
            f.rays[0].distance shouldBe 15.0
            f.rays[1].origin.distance(f.rays[0].origin) shouldBe (15.0 plusOrMinus 1e-8)
            (f.rays[1].direction.y < f.rays[0].direction.y) shouldBe true
            f.hits.single().second shouldBe f.spec.damagePerPellet
            f.hits.single().first.distance(f.rays[1].origin.toVector()) shouldBe (3.0 plusOrMinus 1e-8)
            f.scheduler.tick(4)
            f.hits.size shouldBe 1
            verify(exactly = 0) { f.world.loadChunk(any<Int>(), any<Int>()) }
        }
    }

    "a wall terminates the bullet without an entity damage callback and an unloaded edge clips the ray" {
        withShotFixture { f ->
            val wall = mockk<Block>()
            f.ray = { origin, direction, _ -> RayTraceResult(origin.toVector().add(direction.clone()), wall, null) }
            f.fire() shouldBe true
            f.until { f.rays.isNotEmpty() }
            f.scheduler.tick(4)
            f.rays.size shouldBe 1
            f.hits.isEmpty() shouldBe true
        }
        withShotFixture { f ->
            f.eye = Location(f.world, 0.0, 64.0, 14.0)
            every { f.world.isChunkLoaded(any<Int>(), any<Int>()) } answers { secondArg<Int>() == 0 }
            f.fire() shouldBe true
            f.until { f.rays.isNotEmpty() }
            val ray = f.rays.single()
            val endpoint = ray.origin.toVector().add(ray.direction.clone().multiply(ray.distance))
            endpoint.z shouldBe ((16.0 - .01 * ray.direction.z) plusOrMinus 1e-8)
            f.scheduler.tick(3)
            f.rays.size shouldBe 1
            f.hits.isEmpty() shouldBe true
        }
    }

    "muzzle birth and swept flight share their origin and each spread heading without root double rotation" {
        withShotFixture { f ->
            f.enableVisuals()
            f.eye = Location(f.world, 4.0, 64.0, 5.0, 123f, -37f)
            f.fire(f.spec.copy(pellets = 2, spreadDegrees = 3.0)) shouldBe true
            f.until { f.rays.size >= 2 }
            f.births.size shouldBe 2
            f.births.forEach { (_, location) -> location.yaw shouldBe 0f; location.pitch shouldBe 0f }
            f.frames.take(2).zip(f.rays.take(2)).forEach { (frame, ray) ->
                frame.position.toVector().distance(ray.origin.toVector()) shouldBe (0.0 plusOrMinus 1e-8)
                val heading = frame.transform.leftRotation.transform(Vector3f(0f, 0f, 1f))
                heading.distance(ray.direction.toVector3f()).toDouble() shouldBe (0.0 plusOrMinus 1e-6)
            }
        }
    }

    "near crosshair contact and a blocked barrel retract the shared origin before the obstruction" {
        withShotFixture { f ->
            f.aimHit = RayTraceResult(Vector(0.0, 64.0, .2), f.target)
            f.fire() shouldBe true
            f.until { f.rays.isNotEmpty() }
            (f.rays.first().origin.z <= .17 + 1e-8) shouldBe true
            (f.rays.first().direction.z > 0.0) shouldBe true
        }
        withShotFixture { f ->
            f.barrelHit = { origin, direction, _ -> RayTraceResult(origin.toVector().add(direction.clone().multiply(.3)), mockk<Block>(), null) }
            f.fire() shouldBe true
            f.until { f.rays.isNotEmpty() }
            f.rays.first().origin.distance(f.eye) shouldBe (.27 plusOrMinus 1e-8)
        }
    }

    "flame leaves the same model muzzle as the bullet with a directed emission" {
        withShotFixture { f ->
            f.enableVisuals()
            f.fire(TttFirearmCatalog.specs.getValue(FirearmId.FIVE_SEVEN).copy(spreadDegrees = 0.0)) shouldBe true
            f.until { f.rays.isNotEmpty() }
            val birth = f.births.single().second
            val direction = f.rays.first().direction
            verify(exactly = 1) {
                f.world.spawnParticle(Particle.SMALL_FLAME, match<Location> { it.distance(birth) < 1e-8 }, 0,
                    match<Double> { kotlin.math.abs(it - direction.x) < 1e-8 },
                    match<Double> { kotlin.math.abs(it - direction.y) < 1e-8 },
                    match<Double> { kotlin.math.abs(it - direction.z) < 1e-8 }, .025)
            }
        }
    }

    "muzzle families stay compact and distinct including a flashless suppressor and a broad heavy shot" {
        val samples = mutableListOf<Map<String, Any>>()
        for (id in listOf(FirearmId.FIVE_SEVEN, FirearmId.G36, FirearmId.HAND_CANNON,
            FirearmId.FLINTLOCK, FirearmId.VSS_VINTOREZ)) withShotFixture { f ->
            f.enableVisuals()
            val spec = TttFirearmCatalog.specs.getValue(id).copy(pellets = 1, roundsPerShot = 1, spreadDegrees = 0.0)
            f.fire(spec) shouldBe true
            f.until { f.births.isNotEmpty() }
            val muzzle = f.births.single().second
            val emissions = f.particleBirths
            (emissions.sumOf { maxOf(1, it.count) } <= 8) shouldBe true
            emissions.forEach { (particle, at, _, offsets) ->
                (at.distance(muzzle) <= .14) shouldBe true
                listOf(offsets.x, offsets.y, offsets.z).all(Double::isFinite) shouldBe true
                (particle == Particle.FLASH || particle == Particle.EXPLOSION) shouldBe false
            }
            val flames = emissions.filter { it.particle == Particle.FLAME || it.particle == Particle.SMALL_FLAME }
            when (id) {
                FirearmId.VSS_VINTOREZ -> {
                    flames.size shouldBe 0
                    emissions.map { it.particle } shouldBe listOf(Particle.SMOKE)
                }
                FirearmId.HAND_CANNON -> {
                    flames.size shouldBe 3
                    val flanks = flames.drop(1).map { it.at.toVector().subtract(muzzle.toVector()) }
                    flanks[0].distance(flanks[1]) shouldBe (.18 plusOrMinus 1e-8)
                }
                FirearmId.FLINTLOCK -> emissions.single { it.particle == Particle.SMOKE }.count shouldBe 6
                FirearmId.FIVE_SEVEN -> flames.single().particle shouldBe Particle.SMALL_FLAME
                else -> flames.single().particle shouldBe Particle.FLAME
            }
            samples += mapOf("weapon" to id, "profile" to firearmFlightProfile(id),
                "muzzle" to listOf(muzzle.x, muzzle.y, muzzle.z), "emissions" to emissions.map { emission ->
                    mapOf("particle" to emission.particle.name, "position" to listOf(emission.at.x, emission.at.y, emission.at.z),
                        "count" to emission.count, "offsets" to listOf(emission.offsets.x, emission.offsets.y, emission.offsets.z),
                        "extra" to emission.extra)
                })
        }
        System.getenv("ARC_EVENTS_FIREARM_FEEL_PREVIEW")?.let { output ->
            val path = Path.of(output)
            path.parent?.let(Files::createDirectories)
            Files.writeString(path, GsonBuilder().setPrettyPrinting().create().toJson(mapOf(
                "evidence" to "Production muzzle particle calls and profiles; emission data only, not native particle playback or audio",
                "samples" to samples,
            )))
        }
    }

    "queued burst rounds use the current muzzle pose after an aim toggle" {
        withShotFixture { f ->
            f.enableVisuals()
            f.scopedViewmodel = true
            f.zoom = 3f
            every { f.world.rayTrace(any(), any(), any(), FluidCollisionMode.NEVER, true, .12, any()) } returns null
            f.fire(f.spec.copy(roundsPerShot = 2, pellets = 2), aiming = true) shouldBe true
            f.until { f.births.size == 1 }
            val first = firearmMuzzleOffset(f.spec.id, f.eye.direction, f.eye.yaw, true, false, true, 3f)
            f.births[0].second.toVector().distance(f.eye.toVector().add(first)) shouldBe (0.0 plusOrMinus 1e-8)
            f.zoom = null // RMB lowered the current scoped model before the next round launches.
            f.until { f.births.size == 2 }
            val second = firearmMuzzleOffset(f.spec.id, f.eye.direction, f.eye.yaw, false, false, true)
            f.births[1].second.toVector().distance(f.eye.toVector().add(second)) shouldBe (0.0 plusOrMinus 1e-8)
        }
    }

    "a protected living body collides with the bullet without receiving a damage callback" {
        withShotFixture { f ->
            f.soundsEnabled = true
            val protected = mockk<Player>(relaxed = true)
            every { protected.isDead } returns false
            f.ray = { origin, direction, _ -> RayTraceResult(origin.toVector().add(direction), protected) }
            f.fire() shouldBe true
            f.until { f.rays.isNotEmpty() }
            f.filters.last().test(protected) shouldBe true
            f.scheduler.tick(4)
            f.rays.size shouldBe 1
            f.hits.isEmpty() shouldBe true
            verify(exactly = 1) {
                f.world.playSound(any<Location>(), "arc:bullet_impact", SoundCategory.PLAYERS, 1f, 1f)
            }
        }
    }

    "one shotgun volley aggregates all target pellets before invoking native damage" {
        withShotFixture { f ->
            f.soundsEnabled = true
            val shotgun = TttFirearmCatalog.specs.getValue(FirearmId.DOUBLE_BARREL).copy(spreadDegrees = 0.0)
            f.ray = { origin, direction, _ -> RayTraceResult(origin.toVector().add(direction.clone()), f.target) }
            f.fire(shotgun) shouldBe true
            f.until { f.hits.isNotEmpty() }
            f.rays.size shouldBe shotgun.pellets
            f.hits.size shouldBe 1
            f.hits.single().second shouldBe ((shotgun.pellets * shotgun.damagePerPellet) plusOrMinus 1e-8)
            verify(exactly = 1) {
                f.world.playSound(any<Location>(), "arc:bullet_impact", SoundCategory.PLAYERS, 1f, 1f)
            }
        }
    }

    "VSS fires its recorded rifle sample once at the muzzle independently of particles and hits" {
        withShotFixture { f ->
            f.soundsEnabled = true
            f.fire() shouldBe true
            f.until { f.rays.isNotEmpty() }
            verify(exactly = 1) {
                f.world.playSound(any<Location>(), "arc:weapon_vss_fire", SoundCategory.PLAYERS, 4f, 1f)
            }
            verify(exactly = 0) {
                f.world.playSound(any<Location>(), Sound.ENTITY_ARROW_SHOOT, any<Float>(), any<Float>())
                f.world.playSound(any<Location>(), Sound.BLOCK_IRON_TRAPDOOR_CLOSE, any<Float>(), any<Float>())
            }
            f.hits.isEmpty() shouldBe true
        }
        withShotFixture { f ->
            f.fire() shouldBe true
            f.until { f.rays.isNotEmpty() }
            verify(exactly = 0) {
                f.world.playSound(any<Location>(), any<String>(), any<SoundCategory>(), any<Float>(), any<Float>())
            }
        }
    }

    "every firearm plays its own recorded sample once per muzzle through the players category" {
        for (spec in TttFirearmCatalog.specs.values) withShotFixture { f ->
            f.soundsEnabled = true
            var fired = false
            f.fire(spec, afterFiring = { fired = it }) shouldBe true
            f.until { fired }
            val key = if (spec.id == FirearmId.VSS_VINTOREZ) "arc:weapon_vss_fire"
                else "arc:weapon_${spec.id.name.lowercase()}_fire"
            val profile = firearmFlightProfile(spec.id)
            verify(exactly = firearmRoundPellets(spec).size) {
                f.world.playSound(any<Location>(), key, SoundCategory.PLAYERS, profile.volume, profile.pitch)
            }
            verify(exactly = 0) {
                f.world.playSound(any<Location>(), Sound.ENTITY_FIREWORK_ROCKET_BLAST, any<Float>(), any<Float>())
                f.world.playSound(any<Location>(), Sound.BLOCK_IRON_TRAPDOOR_CLOSE, any<Float>(), any<Float>())
            }
        }
    }

    "audition presets share their clip across guns and preserve muzzle position volume pitch and round count" {
        for ((preset, key) in listOf(
            FirearmSoundPreset.SHARP to "arc:weapon_preset_sharp_fire",
            FirearmSoundPreset.HEAVY to "arc:weapon_preset_heavy_fire",
            FirearmSoundPreset.ECHO to "arc:weapon_preset_echo_fire",
        )) {
            FirearmId.entries.forEach { preset.soundKey(it) shouldBe key }
            for (id in listOf(FirearmId.VSS_VINTOREZ, FirearmId.G36, FirearmId.DOUBLE_BARREL)) withShotFixture { f ->
                f.enableVisuals()
                f.soundsEnabled = true
                f.preset = preset
                val spec = TttFirearmCatalog.specs.getValue(id)
                val muzzles = mutableListOf<Location>()
                every { f.world.playSound(any<Location>(), key, SoundCategory.PLAYERS, any<Float>(), any<Float>()) } answers {
                    muzzles += firstArg<Location>().clone()
                }
                var fired = false
                f.fire(spec, afterFiring = { fired = it }) shouldBe true
                f.until { fired }
                val profile = firearmFlightProfile(id)
                verify(exactly = spec.roundsPerShot) {
                    f.world.playSound(any<Location>(), key, SoundCategory.PLAYERS, profile.volume, profile.pitch)
                }
                muzzles.size shouldBe spec.roundsPerShot
                muzzles.forEach { sound ->
                    f.births.any { (_, bullet) -> sound.distanceSquared(bullet) < 1e-16 } shouldBe true
                }
            }
        }
    }

    "the shared sound supplier applies to every shooter and follows a global change" {
        withShotFixture { f ->
            f.soundsEnabled = true
            val other = mockk<Player>(relaxed = true) {
                every { uniqueId } returns UUID.randomUUID()
                every { entityId } returns 6
                every { isOnline } returns true
                every { isDead } returns false
                every { world } returns f.world
                every { eyeLocation } answers { f.eye.clone() }
                every { mainHand } returns MainHand.RIGHT
            }
            for (preset in listOf(FirearmSoundPreset.SHARP, FirearmSoundPreset.HEAVY)) {
                f.preset = preset
                var completed = 0
                f.fire(afterFiring = { if (it) completed++ }) shouldBe true
                f.shots.fire(other, f.spec, false, { true }, { false },
                    afterFiring = { if (it) completed++ }) { _, _, _ -> } shouldBe true
                f.until { completed == 2 }
                verify(exactly = 2) {
                    f.world.playSound(any<Location>(), preset.soundKey(f.spec.id), SoundCategory.PLAYERS, any<Float>(), 1f)
                }
            }
        }
    }

    "accepted bursts keep one sound while the next trigger uses the changed global selection" {
        withShotFixture { f ->
            f.soundsEnabled = true
            f.preset = FirearmSoundPreset.SHARP
            val burst = TttFirearmCatalog.specs.getValue(FirearmId.G36)
            var fired = false
            f.fire(burst, afterFiring = { fired = it }) shouldBe true
            f.preset = FirearmSoundPreset.HEAVY
            f.until { fired }
            verify(exactly = 3) {
                f.world.playSound(any<Location>(), "arc:weapon_preset_sharp_fire", SoundCategory.PLAYERS, 2.7f, .96f)
            }
            fired = false
            f.fire(burst, afterFiring = { fired = it }) shouldBe true
            f.until { fired }
            verify(exactly = 3) {
                f.world.playSound(any<Location>(), "arc:weapon_preset_heavy_fire", SoundCategory.PLAYERS, 2.7f, .96f)
            }
        }
    }

    "audition sound remains disabled by the shared UI sound toggle" {
        withShotFixture { f ->
            f.preset = FirearmSoundPreset.SHARP
            f.fire() shouldBe true
            f.until { f.rays.isNotEmpty() }
            verify(exactly = 0) {
                f.world.playSound(any<Location>(), any<String>(), any<SoundCategory>(), any<Float>(), any<Float>())
            }
        }
    }

    "wall impact sound remains enabled when particles are disabled" {
        withShotFixture { f ->
            f.soundsEnabled = true
            val wall = mockk<Block>(relaxed = true)
            every { wall.blockData.soundGroup.hitSound } returns Sound.BLOCK_STONE_HIT
            f.ray = { origin, direction, _ -> RayTraceResult(origin.toVector().add(direction.clone()), wall, null) }
            f.fire() shouldBe true
            f.until { f.rays.isNotEmpty() }
            verify(exactly = 1) { f.world.playSound(any<Location>(), "arc:bullet_impact", SoundCategory.PLAYERS, 1f, 1f) }
            verify(exactly = 1) { f.world.playSound(any<Location>(), Sound.BLOCK_STONE_HIT, SoundCategory.PLAYERS, .65f, 1f) }
            verify(exactly = 0) { f.world.spawnParticle(any<Particle>(), any<Location>(), any<Int>(), any<Double>(), any<Double>(), any<Double>(), any<Double>()) }
            verify(exactly = 0) { f.world.spawnParticle(any<Particle>(), any<Location>(), any<Int>(), any<Double>(), any<Double>(), any<Double>(), any<Double>(), any<BlockData>()) }
        }
    }

    "empty air flybys reach only nearby eligible eyes and each projectile plays once per listener" {
        withShotFixture { f ->
            f.soundsEnabled = true
            val near = f.listener(Location(f.world, 0.0, 64.0, 8.0))
            val far = f.listener(Location(f.world, 0.0, 64.0, 8.0))
            val shooter = f.listener(Location(f.world, 0.0, 64.0, 8.0))
            every { shooter.uniqueId } returns f.player.uniqueId
            val offline = f.listener(Location(f.world, 0.0, 64.0, 8.0))
            every { offline.isOnline } returns false
            val invalid = f.listener(Location(f.world, 0.0, 64.0, 8.0))
            every { invalid.isValid } returns false
            val elsewhere = f.listener(Location(f.world, 0.0, 64.0, 8.0))
            every { elsewhere.world } returns mockk { every { uid } returns UUID.randomUUID() }
            f.ray = { origin, direction, _ ->
                val along = origin.toVector().add(direction.clone().multiply(8.0))
                val side = Vector(direction.z, 0.0, -direction.x).normalize()
                every { near.eyeLocation } returns along.clone().add(side.clone().multiply(1.99)).toLocation(f.world)
                every { far.eyeLocation } returns along.clone().add(side.clone().multiply(2.01)).toLocation(f.world)
                null
            }
            f.fire() shouldBe true
            f.until { f.rays.size >= 2 }
            verify(exactly = 1) {
                near.playSound(any<Location>(), "arc:bullet_flyby", SoundCategory.PLAYERS, .8f, 1f)
            }
            for (listener in listOf(far, shooter, offline, invalid, elsewhere)) verify(exactly = 0) {
                listener.playSound(any<Location>(), any<String>(), any<SoundCategory>(), any<Float>(), any<Float>())
            }
            verify(exactly = 0) {
                f.world.playSound(any<Location>(), "arc:bullet_flyby", any<SoundCategory>(), any<Float>(), any<Float>())
            }
            f.hits.isEmpty() shouldBe true
        }
    }

    "flybys skip the muzzle and clip at the actual wall contact" {
        withShotFixture { f ->
            f.soundsEnabled = true
            val atMuzzle = f.listener(f.eye)
            val beforeWall = f.listener(f.eye)
            val beyondWall = f.listener(f.eye)
            var contact = Vector()
            val wall = mockk<Block>(relaxed = true)
            f.ray = { origin, direction, _ ->
                every { atMuzzle.eyeLocation } returns origin.clone()
                every { beforeWall.eyeLocation } returns origin.toVector().add(direction.clone().multiply(4.0)).toLocation(f.world)
                every { beyondWall.eyeLocation } returns origin.toVector().add(direction.clone().multiply(12.0)).toLocation(f.world)
                contact = origin.toVector().add(direction.clone().multiply(8.0))
                RayTraceResult(contact.clone(), wall, null)
            }
            f.fire() shouldBe true
            f.until { f.rays.isNotEmpty() }
            verify(exactly = 1) {
                beforeWall.playSound(any<Location>(), "arc:bullet_flyby", SoundCategory.PLAYERS, .8f, 1f)
            }
            for (listener in listOf(atMuzzle, beyondWall)) verify(exactly = 0) {
                listener.playSound(any<Location>(), any<String>(), any<SoundCategory>(), any<Float>(), any<Float>())
            }
            verify(exactly = 1) {
                f.world.playSound(match<Location> { it.toVector().distanceSquared(contact) < 1e-8 },
                    "arc:bullet_impact", SoundCategory.PLAYERS, 1f, 1f)
            }
            f.hits.isEmpty() shouldBe true
        }
    }

    "flybys stop at the loaded segment and the global sound toggle disables both new cues" {
        withShotFixture { f ->
            f.soundsEnabled = true
            f.eye = Location(f.world, 0.0, 64.0, 4.0)
            every { f.world.isChunkLoaded(any<Int>(), any<Int>()) } answers { secondArg<Int>() == 0 }
            val near = f.listener(f.eye)
            val beyond = f.listener(f.eye)
            f.ray = { origin, direction, distance ->
                val end = origin.toVector().add(direction.clone().multiply(distance))
                every { near.eyeLocation } returns end.clone().subtract(direction.clone().multiply(3.0)).toLocation(f.world)
                every { beyond.eyeLocation } returns end.clone().add(direction.clone().multiply(3.0)).toLocation(f.world)
                null
            }
            f.fire() shouldBe true
            f.until { f.rays.isNotEmpty() }
            f.scheduler.tick(3)
            f.rays.size shouldBe 1
            verify(exactly = 1) { near.playSound(any<Location>(), "arc:bullet_flyby", SoundCategory.PLAYERS, .8f, 1f) }
            verify(exactly = 0) { beyond.playSound(any<Location>(), any<String>(), any<SoundCategory>(), any<Float>(), any<Float>()) }
            verify(exactly = 0) { f.world.loadChunk(any<Int>(), any<Int>()) }
        }
        withShotFixture { f ->
            val listener = f.listener(Location(f.world, 0.0, 64.0, 6.0))
            f.ray = { origin, direction, _ -> RayTraceResult(origin.toVector().add(direction.clone().multiply(8.0)), f.target) }
            f.fire() shouldBe true
            f.until { f.hits.isNotEmpty() }
            verify(exactly = 0) {
                f.world.getNearbyPlayers(any<Location>(), any<Double>(), any<Double>(), any<Double>())
                listener.playSound(any<Location>(), any<String>(), any<SoundCategory>(), any<Float>(), any<Float>())
                f.world.playSound(any<Location>(), any<String>(), any<SoundCategory>(), any<Float>(), any<Float>())
            }
        }
    }

    "shotgun pellets share one physical wall impact sound and retain its material layer" {
        withShotFixture { f ->
            f.enableVisuals()
            f.soundsEnabled = true
            val shotgun = TttFirearmCatalog.specs.getValue(FirearmId.DOUBLE_BARREL).copy(spreadDegrees = 0.0)
            val wall = mockk<Block>(relaxed = true)
            every { wall.blockData.soundGroup.hitSound } returns Sound.BLOCK_STONE_HIT
            f.ray = { origin, direction, _ -> RayTraceResult(origin.toVector().add(direction.clone().multiply(8.0)), wall, null) }
            f.fire(shotgun) shouldBe true
            f.until { f.rays.size == shotgun.pellets }
            verify(exactly = 1) { f.world.playSound(any<Location>(), "arc:bullet_impact", SoundCategory.PLAYERS, 1f, 1f) }
            verify(exactly = 1) { f.world.playSound(any<Location>(), Sound.BLOCK_STONE_HIT, SoundCategory.PLAYERS, .65f, 1f) }
            verify(exactly = 1) {
                f.world.spawnParticle(Particle.BLOCK, any<Location>(), 8, .06, .06, .06, .12, wall.blockData)
            }
            f.hits.isEmpty() shouldBe true
        }
    }

    "mixed head and body pellets retain collision-time weights when the target moves before aggregation" {
        for (headFirst in listOf(true, false)) withShotFixture { f ->
            val shotgun = f.spec.copy(pellets = 2, damagePerPellet = 3.0)
            var collision = 0
            f.ray = { _, _, _ ->
                val isHead = (collision++ == 0) == headFirst
                // Target height changes between pellet collisions; the first hit cannot be reclassified later.
                f.targetEyeY = if (collision == 1) 65.62 else 80.0
                RayTraceResult(Vector(0.0, f.targetEyeY - if (isHead) .1 else 1.0, 1.0), f.target)
            }
            f.fire(shotgun, headshotMultiplier = 2.0) shouldBe true
            f.until { f.hits.isNotEmpty() }
            f.hits.size shouldBe 1
            f.hits.single().second shouldBe 9.0
            f.hitDetails.single().headshot shouldBe true
            f.targetEyeY shouldBe 80.0
        }
    }

    "session invalidation world change and shutdown cancel in-flight damage" {
        withShotFixture { f ->
            f.fire() shouldBe true
            f.until { f.rays.isNotEmpty() }
            f.valid = false
            f.ray = { origin, _, _ -> RayTraceResult(origin.toVector(), f.target) }
            f.scheduler.tick(5)
            f.hits.isEmpty() shouldBe true
            f.rays.size shouldBe 1
        }
        withShotFixture { f ->
            f.fire() shouldBe true
            every { f.player.world } returns mockk { every { uid } returns UUID.randomUUID() }
            f.scheduler.tick(3)
            f.rays.isEmpty() shouldBe true
        }
    }

    "representative VSS flight exports the actual swept frames for optional motion review" {
        withShotFixture { f ->
            f.enableVisuals()
            f.fire(f.spec.copy(range = 76.0)) shouldBe true
            f.until { f.scheduler.timerCount() == 0 }
            f.rays.size shouldBe 6
            f.births.first().first shouldBe 1
            f.rays.first().tick shouldBe 2
            f.rays.sumOf { it.distance } shouldBe (76.0 plusOrMinus 1e-7)
            System.getenv("ARC_EVENTS_FIREARM_PREVIEW")?.let { output ->
                val transform = firearmBulletTransform(Vector(0, 0, 1))
                val export = mapOf(
                    "evidence" to "Production engine ray calls with a no-hit World double; not a native Minecraft capture",
                    "weapon" to "VSS_VINTOREZ", "tickMilliseconds" to 50,
                    "birth" to f.births.first().let { (tick, location) -> mapOf("tick" to tick, "position" to listOf(location.x, location.y, location.z)) },
                    "timing" to "Launch/muzzle birth at tick 1; first swept physics at tick 2; six flight steps end at tick 7",
                    "tracer" to mapOf("scale" to listOf(transform.scale.x, transform.scale.y, transform.scale.z), "material" to "YELLOW_CONCRETE"),
                    "frames" to f.rays.map { ray -> mapOf("tick" to ray.tick,
                        "start" to listOf(ray.origin.x, ray.origin.y, ray.origin.z),
                        "end" to ray.origin.toVector().add(ray.direction.clone().multiply(ray.distance)).let { listOf(it.x, it.y, it.z) }) },
                )
                val path = Path.of(output)
                path.parent?.let(Files::createDirectories)
                Files.writeString(path, GsonBuilder().setPrettyPrinting().create().toJson(export))
            }
        }
    }
})

private fun withShotFixture(action: (ShotFixture) -> Unit) {
    val scheduler = TestTaskScheduler()
    Tasks.withScheduler(scheduler) { ShotFixture(scheduler).use(action) }
}

private class ShotFixture(val scheduler: TestTaskScheduler) : AutoCloseable {
    private val previousApi = PacketEvents.getAPI()
    init {
        val api = mockk<PacketEventsAPI<Any>>(relaxed = true)
        every { api.serverManager } returns ServerManager { ServerVersion.V_1_21_11 }
        every { api.playerManager.getUser(any<Player>()) } returns null
        PacketEvents.setAPI(api)
    }
    val world = mockk<World>(relaxed = true)
    val player = mockk<Player>(relaxed = true)
    val target = mockk<Player>(relaxed = true)
    val displays = mockk<PaperPacketDisplays>(relaxed = true)
    var soundsEnabled = false
    var particlesEnabled = false
    var scopedViewmodel = false
    var zoom: Float? = null
    var preset = FirearmSoundPreset.ORIGINAL
    private var mockedBukkit = false
    val shots = FirearmShots(displays, { particlesEnabled }, { soundsEnabled }, { scopedViewmodel }, { zoom }, { preset })
    val spec: FirearmSpec = TttFirearmCatalog.specs.getValue(FirearmId.VSS_VINTOREZ).copy(spreadDegrees = 0.0)
    var eye = Location(world, 0.0, 64.0, 0.0)
    var valid = true
    var targetEyeY = 65.62
    private var tick = 0
    data class Ray(val tick: Int, val origin: Location, val direction: Vector, val distance: Double)
    val rays = mutableListOf<Ray>()
    val births = mutableListOf<Pair<Int, Location>>()
    data class Frame(val position: Location, val transform: Transformation)
    val frames = mutableListOf<Frame>()
    data class ParticleBirth(val particle: Particle, val at: Location, val count: Int, val offsets: Vector, val extra: Double)
    val particleBirths = mutableListOf<ParticleBirth>()
    val filters = mutableListOf<java.util.function.Predicate<Entity>>()
    var aimHit: RayTraceResult? = null
    var barrelHit: (Location, Vector, Double) -> RayTraceResult? = { _, _, _ -> null }
    private var launchQueries = 0
    val hits = mutableListOf<Pair<Vector, Double>>()
    val hitDetails = mutableListOf<FirearmHit>()
    val nearbyPlayers = mutableListOf<Player>()
    var ray: (Location, Vector, Double) -> RayTraceResult? = { _, _, _ -> null }

    init {
        every { world.uid } returns UUID.randomUUID()
        every { world.minHeight } returns -64
        every { world.maxHeight } returns 320
        every { world.isChunkLoaded(any<Int>(), any<Int>()) } returns true
        every { world.getNearbyPlayers(any<Location>(), any<Double>(), any<Double>(), any<Double>()) } answers { nearbyPlayers.toList() }
        every { player.uniqueId } returns UUID.randomUUID()
        every { player.entityId } returns 5
        every { player.isOnline } returns true
        every { player.isDead } returns false
        every { player.world } returns world
        every { player.eyeLocation } answers { eye.clone() }
        every { player.mainHand } returns MainHand.RIGHT
        every { target.uniqueId } returns UUID.randomUUID()
        every { target.isValid } returns true
        every { target.isDead } returns false
        every { target.eyeLocation } answers { Location(world, 0.0, targetEyeY, 1.0) }
        every { world.rayTrace(any(), any(), any(), FluidCollisionMode.NEVER, true, .12, any()) } answers {
            val origin = firstArg<Location>()
            val direction = secondArg<Vector>()
            val distance = thirdArg<Double>()
            filters += arg<java.util.function.Predicate<Entity>>(6)
            when (launchQueries++) {
                0 -> aimHit
                1 -> barrelHit(origin, direction, distance)
                else -> {
                    rays += Ray(tick, origin.clone(), direction.clone(), distance)
                    ray(origin, direction, distance)
                }
            }
        }
    }

    fun listener(eye: Location): Player = mockk<Player>(relaxed = true).also { listener ->
        every { listener.uniqueId } returns UUID.randomUUID()
        every { listener.isOnline } returns true
        every { listener.isValid } returns true
        every { listener.isDead } returns false
        every { listener.world } returns world
        every { listener.eyeLocation } answers { eye.clone() }
        nearbyPlayers += listener
    }

    fun enableVisuals() {
        mockkStatic(Bukkit::class)
        mockedBukkit = true
        every { Bukkit.createBlockData(Material.YELLOW_CONCRETE) } returns mockk(relaxed = true)
        every { world.spawnParticle(any<Particle>(), any<Location>(), any<Int>(), any<Double>(), any<Double>(), any<Double>(), any<Double>()) } answers {
            particleBirths += ParticleBirth(firstArg(), secondArg<Location>().clone(), thirdArg(),
                Vector(arg<Double>(3), arg<Double>(4), arg<Double>(5)), arg(6))
        }
        every { displays.spawnBlock(any(), any()) } answers {
            var position = firstArg<Location>().clone()
            births += tick to position.clone()
            mockk<PacketBlockDisplay>(relaxed = true).also { display ->
                every { display.teleport(any()) } answers { position = firstArg<Location>().clone() }
                every { display.transformation = any() } answers { frames += Frame(position.clone(), firstArg()) }
            }
        }
        particlesEnabled = true
    }

    fun fire(spec: FirearmSpec = this.spec, headshotMultiplier: Double = 1.0, aiming: Boolean = false,
             afterFiring: (Boolean) -> Unit = {}): Boolean =
        shots.fire(player, spec, aiming, { valid }, { it === target }, headshotMultiplier, afterFiring) { _, details, damage ->
        hits += details.position.clone() to damage
        hitDetails += details
    }

    fun until(maximumTicks: Int = 35, done: () -> Boolean) {
        repeat(maximumTicks) {
            if (done()) return
            tick++
            scheduler.tick()
        }
        error("Shot did not reach the expected state in $maximumTicks ticks: rays=${rays.size}, hits=${hits.size}")
    }

    override fun close() {
        shots.close()
        if (mockedBukkit) unmockkStatic(Bukkit::class)
        PacketEvents.setAPI(previousApi)
    }
}
