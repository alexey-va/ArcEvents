package ru.ruscrafting.events.paper

import com.github.retrooper.packetevents.PacketEvents
import com.github.retrooper.packetevents.protocol.player.ClientVersion
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerPlayerRotation
import org.bukkit.FluidCollisionMode
import org.bukkit.GameMode
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.Particle
import org.bukkit.Sound
import org.bukkit.World
import org.bukkit.entity.Display
import org.bukkit.entity.Entity
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Player
import org.bukkit.inventory.MainHand
import org.bukkit.util.RayTraceResult
import org.bukkit.util.Transformation
import org.bukkit.util.Vector
import org.joml.Quaternionf
import org.joml.Vector3f
import ru.arc.core.ScheduledTask
import ru.arc.core.Tasks
import ru.arc.paper.display.PacketBlockDisplay
import ru.arc.paper.display.PaperPacketDisplays
import ru.ruscrafting.events.domain.FirearmId
import ru.ruscrafting.events.domain.FirearmSpec
import ru.ruscrafting.events.domain.FirearmSpread
import ru.ruscrafting.events.domain.ShotDirection
import java.util.UUID
import java.util.logging.Level
import java.util.logging.Logger
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.min
import kotlin.random.Random

data class FirearmHit(val position: Vector, val headshot: Boolean)

/** Main-thread flight owner. Callers retain admission, ammunition and native damage/protection rules. */
class FirearmShots(
    private val displays: PaperPacketDisplays,
    private val particles: () -> Boolean = { true },
    private val sounds: () -> Boolean = { true },
    private val scopedModel: (UUID) -> Boolean = { false },
    private val scopeZoom: (UUID) -> Float? = { null },
) : AutoCloseable {
    private class Shot(
        val shooter: Player,
        val spec: FirearmSpec,
        val aiming: Boolean,
        val valid: () -> Boolean,
        val target: (Entity) -> Boolean,
        val headshotMultiplier: Double,
        val hit: (Entity, FirearmHit, Double) -> Unit,
        val expires: Long,
    ) {
        val world: World = shooter.world
        val entityId = shooter.entityId
        val profile = firearmFlightProfile(spec.id)
        val visualFailures = mutableSetOf<String>()
        var failed = false
    }

    private class Round(val shot: Shot, val pellets: Int, val due: Long) {
        var remaining = pellets
        val impacts = linkedMapOf<UUID, Impact>()
    }
    private class Bullet(val round: Round, val position: Vector, val velocity: Vector, val born: Long) {
        var remaining = round.shot.spec.range
        var display: PacketBlockDisplay? = null
    }
    private data class Impact(val shot: Shot, val entity: Entity, val position: Vector, var headshot: Boolean, var damage: Double)
    private data class Retired(val display: PacketBlockDisplay, val due: Long)
    private class Recoil(val player: Player, val worldId: UUID, val entityId: Int) {
        var pitch = 0f
        var yaw = 0f
    }

    private val rounds = ArrayDeque<Round>()
    private val bullets = ArrayDeque<Bullet>()
    private val retired = ArrayDeque<Retired>()
    private val completed = ArrayDeque<Round>()
    private val recoils = linkedMapOf<UUID, Recoil>()
    private var reserved = 0
    private var tick = 0L
    private var task: ScheduledTask? = null
    private var closed = false
    private val logger = Logger.getLogger("Minecraft")

    /** True reserves the entire trigger before the caller debits ammunition; impacts happen on later ticks. */
    fun fire(
        shooter: Player,
        spec: FirearmSpec,
        aiming: Boolean,
        valid: () -> Boolean,
        target: (Entity) -> Boolean,
        headshotMultiplier: Double = 1.0,
        hit: (Entity, FirearmHit, Double) -> Unit,
    ): Boolean {
        if (closed || spec.pellets !in 1..16 || reserved + spec.pellets > MAX_BULLETS ||
            !headshotMultiplier.isFinite() || headshotMultiplier < 0.0 || !shooter.isOnline || shooter.isDead || !valid()
        ) return false
        val eye = shooter.eyeLocation
        if (!eye.world.isChunkLoaded(eye.blockX shr 4, eye.blockZ shr 4)) return false
        val shot = Shot(shooter, spec, aiming, valid, target, headshotMultiplier, hit, tick + MAX_LIFETIME_TICKS)
        // Start the scheduler before accepting anything, so a scheduling failure cannot debit ammo.
        if (task == null) task = Tasks.scheduler.runTimer(1L, 1L) { advance() }
        val pattern = firearmRoundPellets(spec)
        pattern.forEachIndexed { index, pellets -> rounds += Round(shot, pellets, tick + 1L + index) }
        reserved += spec.pellets
        return true
    }

    private fun live(shot: Shot): Boolean = !closed && !shot.failed && tick <= shot.expires &&
        shot.shooter.isOnline && !shot.shooter.isDead && shot.shooter.entityId == shot.entityId &&
        shot.shooter.world.uid == shot.world.uid && shot.valid()

    private fun advance() {
        if (closed) return
        tick++
        while (retired.firstOrNull()?.due?.let { it <= tick } == true) retired.removeFirst().display.remove()
        recoverRecoil()
        val deadline = System.nanoTime() + TICK_BUDGET_NANOS
        var work = 0
        repeat(rounds.size) {
            val round = rounds.removeFirst()
            if (round.due > tick || work >= MAX_STEPS_PER_TICK || System.nanoTime() >= deadline) {
                rounds += round
            } else {
                work++
                try {
                    if (live(round.shot)) launch(round) else reserved -= round.pellets
                } catch (failure: Exception) {
                    reserved -= round.pellets
                    fail(round.shot, "launch", failure)
                }
            }
        }
        val available = bullets.size
        var advanced = 0
        // One loaded-chunk ray is indivisible; the aggregate deadline is checked between native calls.
        while (advanced < available && work < MAX_STEPS_PER_TICK && System.nanoTime() < deadline) {
            val bullet = bullets.removeFirst()
            advanced++
            work++
            val keep = try { live(bullet.round.shot) && (bullet.born == tick || step(bullet)) } catch (failure: Exception) {
                fail(bullet.round.shot, "flight", failure)
                false
            }
            if (keep) bullets += bullet else retire(bullet)
        }
        while (completed.isNotEmpty()) completed.removeFirst().impacts.values.forEach { impact ->
            try {
                if (live(impact.shot) && impact.entity.isValid && !impact.entity.isDead && impact.shot.target(impact.entity)) {
                    // One volley/target produces one native damage call even when the work budget split its pellets.
                    impact.shot.hit(impact.entity, FirearmHit(impact.position.clone(), impact.headshot), impact.damage)
                }
            } catch (failure: Exception) { fail(impact.shot, "impact", failure) }
        }
        if (rounds.isEmpty() && bullets.isEmpty() && retired.isEmpty() && recoils.isEmpty()) {
            task?.cancel()
            task = null
        }
    }

    private fun launch(round: Round) {
        val shot = round.shot
        val eye = shot.shooter.eyeLocation.clone()
        if (!shot.world.isChunkLoaded(eye.blockX shr 4, eye.blockZ shr 4)) {
            reserved -= round.pellets
            return
        }
        val eyeDirection = eye.direction.normalize()
        val aimRange = firearmLoadedRange(eye.toVector(), eyeDirection, shot.spec.range, shot.world::isChunkLoaded)
        val aim = if (aimRange > .001) trace(shot, eye, eyeDirection, aimRange) else null
        // An unloaded boundary limits the query, not the weapon's zeroing distance.
        val aimPoint = aim?.hitPosition ?: eye.toVector().add(eyeDirection.clone().multiply(shot.spec.range))
        val muzzle = muzzleLocation(shot, eye, eyeDirection, eye.toVector().distance(aimPoint))
        val direction = firearmZeroingDirection(muzzle.toVector(), aimPoint, eyeDirection)
        // Build the entire round before publishing it to the active queue.
        val created = List(round.pellets) {
            val spread = FirearmSpread.apply(ShotDirection(direction.x, direction.y, direction.z),
                Random.nextDouble(-1.0, 1.0) * shot.spec.spreadDegrees,
                Random.nextDouble(-1.0, 1.0) * shot.spec.spreadDegrees)
            Bullet(round, muzzle.toVector(), Vector(spread.x, spread.y, spread.z).multiply(shot.profile.speed), tick)
        }
        bullets.addAll(created)
        // Publish a birth frame before the first swept step; otherwise the first display would appear 15–45 m away.
        cosmetic(shot, "tracer") { created.forEach { presentBullet(it, it.velocity.clone().normalize(), false) } }
        cosmetic(shot, "muzzle") { muzzle(shot, muzzle, direction) }
        cosmetic(shot, "recoil") { kick(shot) }
    }

    private fun step(bullet: Bullet): Boolean {
        val shot = bullet.round.shot
        if (bullet.position.y < shot.world.minHeight || bullet.position.y >= shot.world.maxHeight) return false
        val velocity = bullet.velocity
        val distance = min(velocity.length(), bullet.remaining)
        if (distance <= .001) return false
        val direction = velocity.clone().normalize()
        val loaded = firearmLoadedRange(bullet.position, direction, distance, shot.world::isChunkLoaded)
        if (loaded <= .001) return false
        val origin = bullet.position.toLocation(shot.world)
        val result = trace(shot, origin, direction, loaded)
        val end = result?.hitPosition ?: bullet.position.clone().add(direction.clone().multiply(loaded))
        bullet.remaining -= bullet.position.distance(end)
        bullet.position.copy(end)
        // Gravity is in blocks/tick²; retain the same swept segment at every speed, never point-test a bullet.
        velocity.y -= BULLET_GRAVITY
        cosmetic(shot, "tracer") { presentBullet(bullet, direction, result != null) }
        if (result != null) {
            cosmetic(shot, "contact") { contact(shot, result, direction) }
            result.hitEntity?.takeIf(shot.target)?.let { entity ->
                val headshot = entity is Player && end.y >= entity.eyeLocation.y - .38
                val damage = shot.spec.damagePerPellet * if (headshot) shot.headshotMultiplier else 1.0
                val previous = bullet.round.impacts[entity.uniqueId]
                if (previous == null) bullet.round.impacts[entity.uniqueId] = Impact(shot, entity, end, headshot, damage)
                else {
                    previous.damage += damage
                    previous.headshot = previous.headshot || headshot
                }
            }
            return false
        }
        return loaded >= distance && bullet.remaining > .001
    }

    private fun trace(shot: Shot, origin: Location, direction: Vector, distance: Double) =
        shot.world.rayTrace(origin, direction, distance, FluidCollisionMode.NEVER, true, .12) { entity ->
            // Protection rejects damage, never lets a bullet pass through a living body.
            entity != shot.shooter && !entity.isDead && !(entity is Player && entity.gameMode == GameMode.SPECTATOR) &&
                (entity is LivingEntity || shot.target(entity))
        }

    private fun presentBullet(bullet: Bullet, direction: Vector, contact: Boolean) {
        if (!particles()) { bullet.display?.remove(); bullet.display = null; return }
        val shot = bullet.round.shot
        // The quaternion below already contains the world heading: root yaw/pitch must stay zero.
        val position = bullet.position.toLocation(shot.world)
        val display = bullet.display ?: displays.spawnBlock(position, Material.YELLOW_CONCRETE.createBlockData()).also {
            bullet.display = it
            it.brightness = Display.Brightness(15, 15)
            it.viewRange = 1.25f
            it.shadowRadius = 0f
            it.shadowStrength = 0f
            it.pauseWhenNotObserved = false
            it.interpolationDuration = 0
        }
        // A short moving fleck, never a muzzle-to-target beam. Snap the last frame onto actual contact.
        display.teleportDuration = if (contact) 0 else 1
        display.teleport(position)
        display.transformation = firearmBulletTransform(direction)
    }

    private fun muzzleLocation(shot: Shot, eye: Location, direction: Vector, contactDistance: Double): Location {
        val zoom = scopeZoom(shot.shooter.uniqueId)
        val desired = firearmMuzzleBeforeContact(firearmMuzzleOffset(shot.spec.id, direction, eye.yaw, zoom != null,
            shot.shooter.mainHand == MainHand.LEFT, scopedModel(shot.shooter.uniqueId), zoom),
            direction, contactDistance)
        if (desired.lengthSquared() <= .000001) return eye.toVector().toLocation(shot.world)
        val barrelDirection = desired.clone().normalize()
        val safe = firearmLoadedRange(eye.toVector(), barrelDirection, desired.length(), shot.world::isChunkLoaded)
        val obstacle = if (safe > .001) trace(shot, eye, barrelDirection, safe) else null
        val distance = if (obstacle == null) safe else (eye.toVector().distance(obstacle.hitPosition) - .03).coerceAtLeast(0.0)
        return eye.toVector().add(barrelDirection.multiply(distance)).toLocation(shot.world)
    }

    private fun muzzle(shot: Shot, location: Location, direction: Vector) {
        if (particles()) {
            if (shot.spec.id != FirearmId.VSS_VINTOREZ) {
                // count=0 sends one directional particle; positive counts randomize its spawn and velocity.
                shot.world.spawnParticle(Particle.SMALL_FLAME, location, 0, direction.x, direction.y, direction.z, .015)
                shot.world.spawnParticle(Particle.ELECTRIC_SPARK, location, 0, direction.x, direction.y, direction.z, .18)
            }
            shot.world.spawnParticle(Particle.SMOKE, location, if (shot.spec.id == FirearmId.FLINTLOCK) 5 else 1, .035, .025, .035, .015)
        }
        if (sounds()) {
            val suppressed = shot.spec.id == FirearmId.VSS_VINTOREZ
            shot.world.playSound(location, if (suppressed) Sound.ENTITY_ARROW_SHOOT else Sound.ENTITY_FIREWORK_ROCKET_BLAST,
                if (suppressed) .35f else shot.profile.volume, shot.profile.pitch)
            shot.world.playSound(location, Sound.BLOCK_IRON_TRAPDOOR_CLOSE, .24f, 1.7f)
        }
    }

    private fun contact(shot: Shot, result: RayTraceResult, direction: Vector) {
        val block = result.hitBlock ?: return
        val point = result.hitPosition.subtract(direction.clone().multiply(.025)).toLocation(shot.world)
        if (particles()) shot.world.spawnParticle(Particle.BLOCK, point, 5, .045, .045, .045, .08, block.blockData)
        if (sounds()) shot.world.playSound(point, block.blockData.soundGroup.hitSound, .35f, 1.2f)
    }

    private fun kick(shot: Shot) {
        val user = PacketEvents.getAPI()?.playerManager?.getUser(shot.shooter) ?: return
        // Relative yaw/pitch arrived in 1.21.9. Never overwrite an older client's current mouse aim.
        if (user.clientVersion.protocolVersion < ClientVersion.V_1_21_9.protocolVersion) return
        val recoil = recoils[shot.shooter.uniqueId]?.takeIf { it.worldId == shot.world.uid && it.entityId == shot.entityId }
            ?: Recoil(shot.shooter, shot.world.uid, shot.entityId).also { recoils[shot.shooter.uniqueId] = it }
        val pitch = firearmRecoilKick(shot.shooter.eyeLocation.pitch,
            shot.profile.recoil * if (shot.aiming) .65f else 1f, recoil.pitch)
        if (pitch == 0f) return
        val yaw = Random.nextDouble(-.14, .14).toFloat() * abs(pitch)
        user.sendPacketSilently(firearmRecoilPacket(yaw, pitch))
        // Return 80% gently; a small remaining rise makes repeated fire require actual aim correction.
        recoil.pitch -= pitch * .8f
        recoil.yaw -= yaw * .8f
    }

    private fun recoverRecoil() {
        val iterator = recoils.values.iterator()
        while (iterator.hasNext()) {
            val recoil = iterator.next()
            if (!recoil.player.isOnline || recoil.player.isDead || recoil.player.world.uid != recoil.worldId || recoil.player.entityId != recoil.entityId) {
                iterator.remove()
                continue
            }
            val yaw = firearmRecoilRecovery(recoil.yaw)
            val pitch = firearmRecoilRecovery(recoil.pitch)
            try {
                PacketEvents.getAPI()?.playerManager?.getUser(recoil.player)?.sendPacketSilently(firearmRecoilPacket(yaw, pitch))
            } catch (failure: Exception) {
                logger.log(Level.WARNING, "ArcEvents firearm recoil recovery failed player=${recoil.player.uniqueId}", failure)
                iterator.remove()
                continue
            }
            recoil.yaw -= yaw
            recoil.pitch -= pitch
            if (abs(recoil.yaw) < .001f && abs(recoil.pitch) < .001f) iterator.remove()
        }
    }

    private fun retire(bullet: Bullet) {
        reserved--
        bullet.display?.let { retired += Retired(it, tick + 1) }
        bullet.display = null
        if (--bullet.round.remaining == 0) completed += bullet.round
    }

    private fun fail(shot: Shot, phase: String, failure: Exception) {
        if (shot.failed) return
        shot.failed = true
        logger.log(Level.WARNING, "ArcEvents firearm failed phase=$phase weapon=${shot.spec.id} player=${shot.shooter.uniqueId}", failure)
    }

    private inline fun cosmetic(shot: Shot, phase: String, action: () -> Unit) {
        if (phase in shot.visualFailures) return
        try { action() } catch (failure: Exception) {
            shot.visualFailures += phase
            logger.log(Level.WARNING, "ArcEvents firearm visual failed phase=$phase weapon=${shot.spec.id} player=${shot.shooter.uniqueId}", failure)
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        task?.cancel()
        task = null
        bullets.forEach { it.display?.remove() }
        retired.forEach { it.display.remove() }
        bullets.clear()
        rounds.clear()
        retired.clear()
        completed.clear()
        recoils.values.forEach { recoil ->
            if (recoil.player.isOnline && recoil.player.world.uid == recoil.worldId && recoil.player.entityId == recoil.entityId) {
                PacketEvents.getAPI()?.playerManager?.getUser(recoil.player)?.sendPacketSilently(firearmRecoilPacket(recoil.yaw, recoil.pitch))
            }
        }
        recoils.clear()
        reserved = 0
    }

    companion object {
        private const val MAX_BULLETS = 192
        private const val MAX_STEPS_PER_TICK = 96
        private const val TICK_BUDGET_NANOS = 2_000_000L
        private const val MAX_LIFETIME_TICKS = 40L
        private const val BULLET_GRAVITY = .025
    }
}

internal data class FirearmFlightProfile(val speed: Double, val recoil: Float, val volume: Float, val pitch: Float)

internal fun firearmFlightProfile(id: FirearmId): FirearmFlightProfile = when (id) {
    FirearmId.FLINTLOCK -> FirearmFlightProfile(14.0, 3.3f, .9f, .8f)
    FirearmId.REVOLVER -> FirearmFlightProfile(22.0, 2.4f, .82f, 1.0f)
    FirearmId.HAND_CANNON -> FirearmFlightProfile(12.0, 4.2f, 1.05f, .65f)
    FirearmId.DOUBLE_BARREL -> FirearmFlightProfile(24.0, 3.6f, .95f, .72f)
    FirearmId.FIVE_SEVEN -> FirearmFlightProfile(30.0, 1.2f, .7f, 1.2f)
    FirearmId.G36 -> FirearmFlightProfile(43.0, .85f, .8f, 1.04f)
    FirearmId.AEK_971 -> FirearmFlightProfile(44.0, .95f, .86f, .98f)
    FirearmId.RPL_20 -> FirearmFlightProfile(41.0, 1.1f, .95f, .86f)
    FirearmId.VEPR_12 -> FirearmFlightProfile(24.0, 2.7f, .82f, .86f)
    FirearmId.M1_GARAND -> FirearmFlightProfile(43.0, 2.5f, .9f, .9f)
    FirearmId.VSS_VINTOREZ -> FirearmFlightProfile(15.0, .9f, .35f, 1.35f)
    FirearmId.MCMILLAN -> FirearmFlightProfile(45.0, 3.8f, 1.0f, .72f)
}

/** Preserve configured pellet count/damage, separating multiple consumed rounds into successive ticks. */
internal fun firearmRoundPellets(spec: FirearmSpec): List<Int> {
    val rounds = min(spec.roundsPerShot, spec.pellets).coerceAtLeast(1)
    return List(rounds) { index -> spec.pellets / rounds + if (index < spec.pellets % rounds) 1 else 0 }
}

internal fun firearmRecoilPacket(yaw: Float, pitch: Float) = WrapperPlayServerPlayerRotation(yaw, true, pitch, true)
internal fun firearmRecoilRecovery(remaining: Float): Float = if (abs(remaining) < .04f) remaining else remaining * .38f
internal fun firearmRecoilKick(currentPitch: Float, strength: Float, recoveryDebt: Float): Float =
    -minOf(strength.coerceAtLeast(0f), (currentPitch + 90f).coerceAtLeast(0f), (9f - recoveryDebt).coerceAtLeast(0f))

internal fun firearmBulletTransform(direction: Vector): Transformation {
    val rotation = Quaternionf().rotationTo(Vector3f(0f, 0f, 1f), direction.toVector3f().normalize())
    return Transformation(rotation.transform(Vector3f(-.012f, -.012f, -.65f)), rotation, Vector3f(.024f, .024f, .65f), Quaternionf())
}

/** Exact chunk traversal, including negative coordinates and an endpoint exactly on a chunk edge. */
internal fun firearmLoadedRange(origin: Vector, direction: Vector, maximum: Double, loaded: (Int, Int) -> Boolean): Double {
    var x = floor(origin.x / 16).toInt()
    var z = floor(origin.z / 16).toInt()
    val dx = direction.x.compareTo(0.0).let { if (direction.x == 0.0) 0 else it }
    val dz = direction.z.compareTo(0.0).let { if (direction.z == 0.0) 0 else it }
    val stepX = if (dx == 0) Double.POSITIVE_INFINITY else 16 / abs(direction.x)
    val stepZ = if (dz == 0) Double.POSITIVE_INFINITY else 16 / abs(direction.z)
    var nextX = if (dx == 0) Double.POSITIVE_INFINITY else ((x + if (dx > 0) 1 else 0) * 16 - origin.x) / direction.x
    var nextZ = if (dz == 0) Double.POSITIVE_INFINITY else ((z + if (dz > 0) 1 else 0) * 16 - origin.z) / direction.z
    var distance = 0.0
    while (distance <= maximum) {
        if (!loaded(x, z)) return (distance - .01).coerceAtLeast(0.0)
        val next = min(nextX, nextZ)
        if (next > maximum) return maximum
        val crossX = nextX <= next
        val crossZ = nextZ <= next
        if ((crossX && !loaded(x + dx, z)) || (crossZ && !loaded(x, z + dz))) return (next - .01).coerceAtLeast(0.0)
        if (crossX) { x += dx; nextX += stepX }
        if (crossZ) { z += dz; nextZ += stepZ }
        distance = next
    }
    return maximum
}
