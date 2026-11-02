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
import org.bukkit.util.RayTraceResult
import org.bukkit.util.Transformation
import org.bukkit.util.Vector
import org.joml.Vector3f
import ru.arc.core.Tasks
import ru.arc.core.TestTaskScheduler
import ru.arc.paper.display.PaperPacketDisplays
import ru.arc.paper.display.PacketBlockDisplay
import ru.ruscrafting.events.domain.FirearmId
import ru.ruscrafting.events.domain.FirearmSpec
import ru.ruscrafting.events.domain.TttFirearmCatalog
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

class FirearmShotsTest : StringSpec({
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
            f.fire(scatter) shouldBe false
            f.rays.isEmpty() shouldBe true
            f.hits.isEmpty() shouldBe true
            f.shots.close()
            f.scheduler.tick(3)
            f.rays.isEmpty() shouldBe true
            f.scheduler.timerCount() shouldBe 0
            f.fire() shouldBe false
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
                    match<Double> { kotlin.math.abs(it - direction.z) < 1e-8 }, .015)
            }
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
            val protected = mockk<Player>(relaxed = true)
            every { protected.isDead } returns false
            f.ray = { origin, direction, _ -> RayTraceResult(origin.toVector().add(direction), protected) }
            f.fire() shouldBe true
            f.until { f.rays.isNotEmpty() }
            f.filters.last().test(protected) shouldBe true
            f.scheduler.tick(4)
            f.rays.size shouldBe 1
            f.hits.isEmpty() shouldBe true
        }
    }

    "one shotgun volley aggregates all target pellets before invoking native damage" {
        withShotFixture { f ->
            val shotgun = TttFirearmCatalog.specs.getValue(FirearmId.DOUBLE_BARREL).copy(spreadDegrees = 0.0)
            f.ray = { origin, direction, _ -> RayTraceResult(origin.toVector().add(direction.clone()), f.target) }
            f.fire(shotgun) shouldBe true
            f.until { f.hits.isNotEmpty() }
            f.rays.size shouldBe shotgun.pellets
            f.hits.size shouldBe 1
            f.hits.single().second shouldBe ((shotgun.pellets * shotgun.damagePerPellet) plusOrMinus 1e-8)
        }
    }

    "VSS fires its recorded rifle sample once at the muzzle independently of particles and hits" {
        withShotFixture { f ->
            f.soundsEnabled = true
            f.fire() shouldBe true
            f.until { f.rays.isNotEmpty() }
            verify(exactly = 1) {
                f.world.playSound(any<Location>(), "arc:weapon_vss_fire", SoundCategory.PLAYERS, 1f, 1f)
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

    "other rifle sounds retain their original vanilla layers" {
        withShotFixture { f ->
            f.soundsEnabled = true
            f.fire(TttFirearmCatalog.specs.getValue(FirearmId.MCMILLAN)) shouldBe true
            f.until { f.rays.isNotEmpty() }
            verify(exactly = 1) {
                f.world.playSound(any<Location>(), Sound.ENTITY_FIREWORK_ROCKET_BLAST, 1f, .72f)
                f.world.playSound(any<Location>(), Sound.BLOCK_IRON_TRAPDOOR_CLOSE, .24f, 1.7f)
            }
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
            verify(exactly = 1) { f.world.playSound(any<Location>(), Sound.BLOCK_STONE_HIT, .35f, 1.2f) }
            verify(exactly = 0) { f.world.spawnParticle(any<Particle>(), any<Location>(), any<Int>(), any<Double>(), any<Double>(), any<Double>(), any<Double>()) }
            verify(exactly = 0) { f.world.spawnParticle(any<Particle>(), any<Location>(), any<Int>(), any<Double>(), any<Double>(), any<Double>(), any<Double>(), any<BlockData>()) }
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
    private var mockedBukkit = false
    val shots = FirearmShots(displays, { particlesEnabled }, { soundsEnabled }, { scopedViewmodel }, { zoom })
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
    val filters = mutableListOf<java.util.function.Predicate<Entity>>()
    var aimHit: RayTraceResult? = null
    var barrelHit: (Location, Vector, Double) -> RayTraceResult? = { _, _, _ -> null }
    private var launchQueries = 0
    val hits = mutableListOf<Pair<Vector, Double>>()
    val hitDetails = mutableListOf<FirearmHit>()
    var ray: (Location, Vector, Double) -> RayTraceResult? = { _, _, _ -> null }

    init {
        every { world.uid } returns UUID.randomUUID()
        every { world.minHeight } returns -64
        every { world.maxHeight } returns 320
        every { world.isChunkLoaded(any<Int>(), any<Int>()) } returns true
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

    fun enableVisuals() {
        mockkStatic(Bukkit::class)
        mockedBukkit = true
        every { Bukkit.createBlockData(Material.YELLOW_CONCRETE) } returns mockk(relaxed = true)
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

    fun fire(spec: FirearmSpec = this.spec, headshotMultiplier: Double = 1.0, aiming: Boolean = false): Boolean =
        shots.fire(player, spec, aiming, { valid }, { it === target }, headshotMultiplier) { _, details, damage ->
        hits += details.position.clone() to damage
        hitDetails += details
    }

    fun until(done: () -> Boolean) {
        repeat(35) {
            if (done()) return
            tick++
            scheduler.tick()
        }
        error("Shot did not reach the expected state in 35 ticks: rays=${rays.size}, hits=${hits.size}")
    }

    override fun close() {
        shots.close()
        if (mockedBukkit) unmockkStatic(Bukkit::class)
        PacketEvents.setAPI(previousApi)
    }
}
