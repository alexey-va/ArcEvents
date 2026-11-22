package ru.ruscrafting.events.paper

import com.github.retrooper.packetevents.PacketEvents
import com.github.retrooper.packetevents.protocol.player.ClientVersion
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerPlayerRotation
import org.bukkit.Color
import org.bukkit.FluidCollisionMode
import org.bukkit.GameMode
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.Particle
import org.bukkit.Sound
import org.bukkit.SoundCategory
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
import ru.ruscrafting.events.domain.FirearmProjectile
import ru.ruscrafting.events.domain.FirearmSpec
import ru.ruscrafting.events.domain.FirearmSoundPreset
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

internal fun LivingEntity.takeFirearmDamage(amount: Double, shooter: Player): Boolean {
    val before = health + absorptionAmount
    damage(amount, shooter)
    return isDead || health + absorptionAmount < before
}

/** Main-thread flight owner. Callers retain admission, ammunition and native damage/protection rules. */
class FirearmShots(
    private val displays: PaperPacketDisplays,
    private val particles: () -> Boolean = { true },
    private val sounds: () -> Boolean = { true },
    private val scopedModel: (UUID) -> Boolean = { false },
    private val scopeZoom: (UUID) -> Float? = { null },
    private val soundPreset: () -> FirearmSoundPreset = { FirearmSoundPreset.ORIGINAL },
    private val eventFeedback: (Player) -> Boolean = { true },
) : AutoCloseable {
    private class Shot(
        val shooter: Player,
        val spec: FirearmSpec,
        val aiming: () -> Boolean,
        val soundKey: String,
        val eventFeedback: Boolean,
        val valid: () -> Boolean,
        val target: (Entity) -> Boolean,
        val headshotMultiplier: Double,
        val hit: (Entity, FirearmHit, Double) -> Boolean,
        val afterFiring: (Boolean) -> Unit,
        var pendingRounds: Int,
        val expires: Long,
        val burst: FirearmBurst?,
    ) {
        val world: World = shooter.world
        val entityId = shooter.entityId
        val profile = firearmFlightProfile(spec.id).let { base -> when (spec.projectile) {
            FirearmProjectile.BULLET -> base
            FirearmProjectile.GRENADE -> base.copy(speed = 1.5)
            FirearmProjectile.ROCKET -> base.copy(speed = 4.0)
        } }
        val visualFailures = mutableSetOf<String>()
        var failed = false
        var allRoundsLaunched = true
    }

    private class Round(val shot: Shot, val pellets: Int, val due: Long, val index: Int = 0) {
        var remaining = pellets
        var blastReservations = 0
        val impacts = linkedMapOf<UUID, Impact>()
        val contacts = mutableListOf<Vector>()
        val heardFlyby = mutableSetOf<UUID>()
        var confirmed = false
    }
    private class Bullet(val round: Round, val position: Vector, val velocity: Vector, val born: Long) {
        var remaining = round.shot.spec.range
        var showTracer = true
        var showTrail = round.pellets == 1
        var display: PacketBlockDisplay? = null
    }
    private data class Impact(val shot: Shot, val entity: Entity, val position: Vector, var headshot: Boolean, var damage: Double)
    private data class Retired(val display: PacketBlockDisplay, val due: Long)
    private class Blast(val round: Round, val center: Vector, val radius: Double, val targets: Iterator<Entity>)
    private class Recoil(val player: Player, val worldId: UUID, val entityId: Int) {
        var pitch = 0f
        var yaw = 0f
        var lastKick = 0L
    }

    private val rounds = ArrayDeque<Round>()
    private val inputCompletions = ArrayDeque<Shot>()
    private val bullets = ArrayDeque<Bullet>()
    private val blasts = ArrayDeque<Blast>()
    private val retired = ArrayDeque<Retired>()
    private val impactMarks = FirearmImpactMarks(displays, particles)
    private val hitMarkers = FirearmHitMarkers(displays, scopeZoom)
    private val completed = ArrayDeque<Round>()
    private val recoils = linkedMapOf<UUID, Recoil>()
    private var reserved = 0
    private var tick = 0L
    private var soundWork = 0
    private var trailParticles = 0
    private var inputWorkNanos = 0L
    private var task: ScheduledTask? = null
    private var closed = false
    private val logger = Logger.getLogger("Minecraft")

    /**
     * True reserves the entire trigger before the caller debits ammunition; impacts happen on later ticks.
     * afterFiring runs once when queued rounds resolve, true only after every muzzle launched; close/abort reports false.
     */
    fun fire(
        shooter: Player,
        spec: FirearmSpec,
        aiming: () -> Boolean,
        valid: () -> Boolean,
        target: (Entity) -> Boolean,
        headshotMultiplier: Double = 1.0,
        afterFiring: (Boolean) -> Unit = {},
        hit: (Entity, FirearmHit, Double) -> Boolean,
    ): Boolean = submit(shooter, spec, aiming, valid, target, headshotMultiplier, null, afterFiring, hit)

    internal fun fireBurst(
        shooter: Player, spec: FirearmSpec, aiming: () -> Boolean, valid: () -> Boolean, target: (Entity) -> Boolean,
        headshotMultiplier: Double, burst: FirearmBurst, afterFiring: (Boolean) -> Unit,
        hit: (Entity, FirearmHit, Double) -> Boolean,
    ): Boolean = submit(shooter, spec, aiming, valid, target, headshotMultiplier, burst, afterFiring, hit)

    private fun submit(
        shooter: Player, spec: FirearmSpec, aiming: () -> Boolean, valid: () -> Boolean, target: (Entity) -> Boolean,
        headshotMultiplier: Double, burst: FirearmBurst?, afterFiring: (Boolean) -> Unit,
        hit: (Entity, FirearmHit, Double) -> Boolean,
    ): Boolean {
        if (closed || spec.pellets !in 1..16 ||
            burst != null && (burst.size !in 1..spec.magazineSize || burst.intervalTicks !in 1..100) ||
            !headshotMultiplier.isFinite() || headshotMultiplier < 0.0 || !shooter.isOnline || shooter.isDead || !valid()
        ) return false
        val pattern = firearmRoundPellets(spec)
        val count = if (burst == null) spec.pellets else pattern.first()
        if (reserved + count > MAX_BULLETS) return false
        val eye = shooter.eyeLocation
        if (!eye.world.isChunkLoaded(eye.blockX shr 4, eye.blockZ shr 4)) return false
        val shot = Shot(shooter, spec, aiming, soundPreset().soundKey(spec.id), eventFeedback(shooter),
            valid, target, headshotMultiplier, hit, afterFiring, if (burst == null) pattern.size else 1,
            tick + MAX_LIFETIME_TICKS + (burst?.let { (it.size - 1) * it.intervalTicks } ?: 0), burst)
        // Start the scheduler before accepting anything, so a scheduling failure cannot debit ammo.
        if (task == null) task = Tasks.scheduler.runTimer(1L, 1L) { advance() }
        // Give clicks the first half of the existing tick budget. Excess input uses the normal queue.
        val immediate = inputWorkNanos < TICK_BUDGET_NANOS / 2
        val firstDue = if (immediate) tick else tick + 1
        val first = Round(shot, pattern.first(), firstDue)
        reserved += count
        if (immediate) {
            val start = System.nanoTime()
            val launched = tryLaunch(first)
            inputWorkNanos += System.nanoTime() - start
            if (!launched) {
                reserved -= count - first.pellets
                return false
            }
            finishMuzzle(first, launched = true, duringInput = true)
        } else rounds += first
        if (burst == null) {
            for (index in 1 until pattern.size) rounds += Round(shot, pattern[index], firstDue + index, index)
        }
        return true
    }

    private fun authorized(shot: Shot): Boolean = !closed && !shot.failed &&
        shot.shooter.isOnline && !shot.shooter.isDead && shot.shooter.entityId == shot.entityId &&
        shot.shooter.world.uid == shot.world.uid && shot.valid()

    private fun live(shot: Shot): Boolean = tick <= shot.expires && authorized(shot)

    private fun advance() {
        if (closed) return
        tick++
        soundWork = 0
        trailParticles = 0
        while (retired.firstOrNull()?.due?.let { it <= tick } == true) retired.removeFirst().display.remove()
        // A single-shot owner debits after admission returns. Its completion may start the reload.
        while (inputCompletions.isNotEmpty()) resolveRound(inputCompletions.removeFirst(), true)
        val deadline = System.nanoTime() + TICK_BUDGET_NANOS - minOf(inputWorkNanos, TICK_BUDGET_NANOS / 2)
        inputWorkNanos = 0L
        var work = 0
        repeat(rounds.size) {
            val round = rounds.removeFirst()
            if (round.due > tick || work >= MAX_STEPS_PER_TICK || System.nanoTime() >= deadline) {
                rounds += round
            } else {
                work++
                finishMuzzle(round, tryLaunch(round))
            }
        }
        while (blasts.isNotEmpty() && work < MAX_STEPS_PER_TICK && System.nanoTime() < deadline) {
            val blast = blasts.removeFirst()
            work++
            val keep = try { authorized(blast.round.shot) && stepBlast(blast) } catch (failure: Exception) {
                fail(blast.round.shot, "blast", failure)
                false
            }
            if (keep) blasts += blast else {
                if (--blast.round.remaining == 0) completed += blast.round
            }
        }
        val available = bullets.size
        var advanced = 0
        // One loaded-chunk ray is indivisible; the aggregate deadline is checked between native calls.
        while (advanced < available && work < MAX_STEPS_PER_TICK && System.nanoTime() < deadline) {
            val bullet = bullets.removeFirst()
            advanced++
            work++
            val keep = try { tick - bullet.born <= MAX_LIFETIME_TICKS && live(bullet.round.shot) &&
                (bullet.born == tick || step(bullet)) } catch (failure: Exception) {
                fail(bullet.round.shot, "flight", failure)
                false
            }
            if (keep) bullets += bullet else retire(bullet)
        }
        while (completed.isNotEmpty() && work < MAX_STEPS_PER_TICK && System.nanoTime() < deadline) {
            val round = completed.first()
            val iterator = round.impacts.values.iterator()
            if (!iterator.hasNext()) {
                completed.removeFirst()
                reserved -= round.blastReservations
                continue
            }
            val impact = iterator.next()
            iterator.remove()
            work++
            try {
                if (authorized(impact.shot) && impact.entity.isValid && !impact.entity.isDead && impact.shot.target(impact.entity)) {
                    // One volley/target produces one native damage call even when the work budget split its pellets.
                    if (impact.shot.hit(impact.entity, FirearmHit(impact.position.clone(), impact.headshot), impact.damage)) {
                        confirmImpact(round, impact)
                    }
                }
            } catch (failure: Exception) { fail(impact.shot, "impact", failure) }
        }
        recoverRecoil()
        val marksAlive = impactMarks.advance(tick)
        val hitMarkersAlive = hitMarkers.advance(tick)
        if (!marksAlive && !hitMarkersAlive && inputCompletions.isEmpty() && rounds.isEmpty() && bullets.isEmpty() && blasts.isEmpty() && completed.isEmpty() && retired.isEmpty() && recoils.isEmpty()) {
            task?.cancel()
            task = null
        }
    }

    private fun confirmImpact(round: Round, impact: Impact) {
        val shot = round.shot
        val player = shot.shooter
        if (!player.isOnline || player.entityId != shot.entityId || player.world.uid != shot.world.uid) return
        if (!shot.eventFeedback) {
            // Ordinary-world guns keep their existing distant hit tick.
            if (sounds() && player.eyeLocation.toVector().distanceSquared(impact.position) > 16.0 * 16.0) {
                cosmetic(shot, "distant-hit") {
                    player.playSound(player.location, Sound.BLOCK_NOTE_BLOCK_HAT, SoundCategory.PLAYERS, .32f, 1.45f)
                }
            }
            return
        }
        if (round.confirmed) return
        round.confirmed = true
        // Like the ammo HUD, this private mark stays readable with world particles disabled.
        cosmetic(shot, "hit-marker") { hitMarkers.show(player, tick) }
        if (sounds()) cosmetic(shot, "hit-sound") {
            player.playSound(player.eyeLocation, Sound.BLOCK_NOTE_BLOCK_SNARE, SoundCategory.PLAYERS,
                .64f, if (impact.headshot) 1.95f else 1.65f)
        }
    }

    private fun tryLaunch(round: Round): Boolean {
        val launched = try {
            live(round.shot) && launch(round)
        } catch (failure: Exception) {
            fail(round.shot, "launch", failure)
            false
        }
        if (!launched) reserved -= round.pellets
        return launched
    }

    private fun finishMuzzle(round: Round, launched: Boolean, duringInput: Boolean = false) {
        val shot = round.shot
        val burst = shot.burst
        val next = round.index + 1
        if (launched && burst != null && next < burst.size) {
            val pattern = firearmRoundPellets(shot.spec)
            val pellets = pattern[next % pattern.size]
            if (reserved + pellets <= MAX_BULLETS) {
                reserved += pellets
                rounds += Round(shot, pellets, tick + burst.intervalTicks, next)
            } else resolveRound(shot, false)
        } else if (duringInput) inputCompletions += shot
        else resolveRound(shot, launched)
    }

    private fun resolveRound(shot: Shot, launched: Boolean) {
        shot.allRoundsLaunched = shot.allRoundsLaunched && launched
        if (--shot.pendingRounds != 0) return
        try { shot.afterFiring(shot.allRoundsLaunched) } catch (failure: Exception) {
            logger.log(Level.WARNING, "ArcEvents firearm completion failed weapon=${shot.spec.id} player=${shot.shooter.uniqueId}", failure)
        }
    }

    private fun launch(round: Round): Boolean {
        val shot = round.shot
        val eye = shot.shooter.eyeLocation.clone()
        if (!shot.world.isChunkLoaded(eye.blockX shr 4, eye.blockZ shr 4)) return false
        val eyeDirection = eye.direction.normalize()
        val aimRange = firearmLoadedRange(eye.toVector(), eyeDirection, shot.spec.range, shot.world::isChunkLoaded)
        val aim = if (aimRange > .001) trace(shot, eye, eyeDirection, aimRange) else null
        // An unloaded boundary limits the query, not the weapon's zeroing distance.
        val aimPoint = aim?.hitPosition ?: eye.toVector().add(eyeDirection.clone().multiply(shot.spec.range))
        val muzzle = muzzleLocation(shot, eye, eyeDirection, eye.toVector().distance(aimPoint))
        val direction = firearmZeroingDirection(muzzle.toVector(), aimPoint, eyeDirection)
        // Build the entire round before publishing it to the active queue.
        val aiming = shot.aiming()
        val spreadDegrees = firearmSpreadDegrees(shot.spec, aiming)
        val barrel = ShotDirection(direction.x, direction.y, direction.z)
        val created = List(round.pellets) {
            val spread = FirearmSpread.sample(barrel, spreadDegrees)
            Bullet(round, muzzle.toVector(), Vector(spread.x, spread.y, spread.z).multiply(shot.profile.speed), tick)
        }
        if (shot.burst?.takeRound?.invoke() == false) return false
        bullets.addAll(created)
        // Publish a birth frame before the first swept step; otherwise the first display would appear 15–45 m away.
        cosmetic(shot, "tracer") {
            selectTracers(created, direction)
            created.forEach { presentBullet(it, it.velocity.clone().normalize(), false, .032) }
        }
        cosmetic(shot, "muzzle") { muzzle(shot, muzzle, direction) }
        cosmetic(shot, "recoil") { kick(shot, aiming) }
        return true
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
        cosmetic(shot, "flyby") { flyby(bullet, end, direction) }
        val travel = bullet.position.distance(end)
        if (bullet.showTrail && shot.spec.projectile == FirearmProjectile.BULLET && travel >= 3.0) {
            cosmetic(shot, "flight-trail") { flightTrail(shot, origin, direction, travel) }
        }
        bullet.remaining -= travel
        bullet.position.copy(end)
        // Gravity is in blocks/tick²; retain the same swept segment at every speed, never point-test a bullet.
        velocity.y -= when (shot.spec.projectile) {
            FirearmProjectile.BULLET -> BULLET_GRAVITY
            FirearmProjectile.GRENADE -> .045
            FirearmProjectile.ROCKET -> 0.0
        }
        cosmetic(shot, "tracer") { presentBullet(bullet, direction, result != null, travel) }
        if (result != null) {
            if (shot.spec.projectile == FirearmProjectile.BULLET && result.hitBlock != null) {
                cosmetic(shot, "impact-mark") { impactMarks.mark(result, tick) }
            }
            cosmetic(shot, "contact") { contact(bullet.round, result, direction) }
            if (shot.spec.projectile != FirearmProjectile.BULLET) {
                detonate(bullet.round, end.clone().subtract(direction.clone().multiply(.06)))
                return false
            }
            result.hitEntity?.takeIf(shot.target)?.let { entity ->
                val headshot = entity is LivingEntity && (entity is Player || PracticeFighters.TAG in entity.scoreboardTags) &&
                    end.y >= entity.eyeLocation.y - .38
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

    /** Controller-owned blast: no native explosion, terrain mutation, fire or protection bypass. */
    private fun detonate(round: Round, center: Vector) {
        val shot = round.shot
        val radius = if (shot.spec.projectile == FirearmProjectile.ROCKET) 3.0 else 2.5
        val location = center.toLocation(shot.world)
        cosmetic(shot, "blast") {
            if (particles()) shot.world.spawnParticle(Particle.EXPLOSION, location, 1)
            if (sounds() && claimSoundWork()) shot.world.playSound(location, Sound.ENTITY_GENERIC_EXPLODE, SoundCategory.PLAYERS, 1f, .85f)
        }
        // Keep accepted blast work inside the same per-tick budget, retaining the round until it finishes.
        val targets = shot.world.getNearbyEntities(location, radius, radius, radius).iterator()
        round.remaining++
        round.blastReservations++
        reserved++
        blasts += Blast(round, center, radius, targets)
    }

    private fun stepBlast(blast: Blast): Boolean {
        if (!blast.targets.hasNext()) return false
        val shot = blast.round.shot
        val entity = blast.targets.next()
        if (entity !== shot.shooter && entity.isValid && !entity.isDead && shot.target(entity)) {
            val target = entity.boundingBox.center
            val distance = blast.center.distance(target)
            val damage = firearmBlastDamage(shot.spec.damagePerPellet, distance, blast.radius)
            if (damage > 0.0) {
                val direction = target.clone().subtract(blast.center)
                if (distance <= .01 || (
                        firearmLoadedRange(blast.center, direction.multiply(1.0 / distance), distance, shot.world::isChunkLoaded) >= distance &&
                        shot.world.rayTraceBlocks(blast.center.toLocation(shot.world), direction, distance, FluidCollisionMode.NEVER, true) == null
                    )) {
                    blast.round.impacts.putIfAbsent(entity.uniqueId, Impact(shot, entity, blast.center.clone(), false, damage))
                }
            }
        }
        return blast.targets.hasNext()
    }

    private fun trace(shot: Shot, origin: Location, direction: Vector, distance: Double) =
        shot.world.rayTrace(origin, direction, distance, FluidCollisionMode.NEVER, true, .12) { entity ->
            // Protection rejects damage, never lets a bullet pass through a living body.
            entity != shot.shooter && !entity.isDead && !(entity is Player && entity.gameMode == GameMode.SPECTATOR) &&
                (entity is LivingEntity || shot.target(entity))
        }

    private fun selectTracers(pellets: List<Bullet>, direction: Vector) {
        if (pellets.size == 1) return
        val center = pellets.maxBy { it.velocity.dot(direction) }
        center.showTrail = true
        if (pellets.size <= 3) return
        // Show the center and two distinct outer paths of the actual spread, including vertical fans.
        pellets.forEach { it.showTracer = false }
        val edge = pellets.maxBy { it.velocity.distanceSquared(center.velocity) }
        val third = pellets.maxBy { minOf(it.velocity.distanceSquared(center.velocity), it.velocity.distanceSquared(edge.velocity)) }
        center.showTracer = true
        edge.showTracer = true
        third.showTracer = true
    }

    private fun presentBullet(bullet: Bullet, direction: Vector, contact: Boolean, travel: Double) {
        if (!bullet.showTracer) return
        if (!particles() || travel <= 0.0) { bullet.display?.remove(); bullet.display = null; return }
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
        }
        // Interpolate to the swept hit, including a rifle shot that reaches cover in its first step.
        // Grow the same fleck during that move; at point blank the whole short step can snap.
        val duration = if (contact && travel < .25) 0 else 1
        display.teleportDuration = duration
        display.interpolationDuration = if (bullet.born == tick) 0 else duration
        display.teleport(position)
        val length = min(travel, 2.1).toFloat()
        // Bound the apparent width when a nearby obstacle has pushed the barrel close to the eye.
        val nearWidth = .024 * position.distance(shot.shooter.eyeLocation) / (scopeZoom(shot.shooter.uniqueId) ?: 1f)
        val width = minOf(.10f, length, nearWidth.toFloat())
        display.transformation = firearmBulletTransform(direction, length, width)
    }

    private fun flightTrail(shot: Shot, start: Location, direction: Vector, travel: Double) {
        if (!particles() || trailParticles >= MAX_TRAIL_PARTICLES_PER_TICK) return
        val middle = start.clone().add(direction.clone().multiply(travel * .5))
        val viewers = shot.world.getNearbyPlayers(middle, 64.0).toList()
        if (viewers.isEmpty()) return
        // Each separated dash has a dim tail and a wider, pale tip. Leave space before contact debris.
        val gaps = (travel / 8.0).toInt().coerceAtMost(3)
        val spacing = (travel - 3.35) / gaps.coerceAtLeast(1)
        for (dash in 0..gaps) {
            for ((offset, dust) in TRAIL_DASH) {
                val distance = 1.5 + dash * spacing + offset
                if (distance >= travel - .65 || trailParticles >= MAX_TRAIL_PARTICLES_PER_TICK) return
                trailParticles++
                shot.world.spawnParticle(Particle.DUST_COLOR_TRANSITION, viewers, shot.shooter,
                    start.x + direction.x * distance, start.y + direction.y * distance, start.z + direction.z * distance,
                    1, 0.0, 0.0, 0.0, 0.0, dust, true)
            }
        }
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
            val style = shot.profile.muzzle
            if (style != FirearmMuzzleStyle.SUPPRESSED) {
                // A short, bright core follows the barrel; no screen-filling FLASH or explosion sprite.
                // count=0 sends one directional particle; positive counts randomize its spawn and velocity.
                shot.world.spawnParticle(if (style == FirearmMuzzleStyle.COMPACT) Particle.SMALL_FLAME else Particle.FLAME,
                    location, 0, direction.x, direction.y, direction.z, .025)
                shot.world.spawnParticle(Particle.ELECTRIC_SPARK, location, 0, direction.x, direction.y, direction.z, .24)
                if (style == FirearmMuzzleStyle.BLAST) {
                    // Shotguns/cannon fan sideways, while rifles retain a narrow forward silhouette.
                    val side = direction.clone().crossProduct(Vector(0, 1, 0))
                    if (side.lengthSquared() < .000001) side.setX(1.0) else side.normalize()
                    for (sign in listOf(-1, 1)) {
                        val flank = direction.clone().multiply(.10).add(side.clone().multiply(sign * .09))
                        shot.world.spawnParticle(Particle.SMALL_FLAME, location.clone().add(flank), 0,
                            flank.x, flank.y, flank.z, .35)
                    }
                }
            }
            val powder = style == FirearmMuzzleStyle.BLACK_POWDER
            shot.world.spawnParticle(Particle.SMOKE, location, if (powder) 6 else 1,
                if (powder) .08 else .025, if (powder) .05 else .02, if (powder) .08 else .025,
                if (powder) .035 else .012)
        }
        if (sounds()) {
            // Paper broadcasts within 16 * volume. Pack clips use attenuation_distance=16;
            // 1.21.11 clamps source gain before applying the player's volume slider.
            val volume = if (shot.eventFeedback) shot.profile.muzzle.eventSoundRange / 16f else shot.profile.volume
            shot.world.playSound(location, shot.soundKey, SoundCategory.PLAYERS, volume, shot.profile.pitch)
        }
    }

    private fun flyby(bullet: Bullet, end: Vector, direction: Vector) {
        if (!sounds()) return
        val travelled = bullet.round.shot.spec.range - bullet.remaining
        val skip = (MIN_FLYBY_TRAVEL - travelled).coerceAtLeast(0.0)
        if (bullet.position.distanceSquared(end) <= skip * skip) return
        val start = bullet.position.clone().add(direction.clone().multiply(skip))
        val shot = bullet.round.shot
        if (!claimSoundWork()) return
        val delta = end.clone().subtract(start)
        val center = start.clone().add(end).multiply(.5).toLocation(shot.world)
        // Native spatial lookup is one indivisible call; candidate checks and sends share the tick cap.
        for (listener in shot.world.getNearbyPlayers(center, abs(delta.x) * .5 + FLYBY_RADIUS,
            abs(delta.y) * .5 + FLYBY_RADIUS, abs(delta.z) * .5 + FLYBY_RADIUS)) {
            if (!claimSoundWork()) break
            val id = listener.uniqueId
            if (id == shot.shooter.uniqueId || id in bullet.round.heardFlyby || !listener.isOnline || !listener.isValid ||
                listener.isDead || listener.world.uid != shot.world.uid) continue
            val eye = listener.eyeLocation.toVector()
            val closest = firearmClosestPointOnSegment(eye, start, end)
            if (eye.distanceSquared(closest) > FLYBY_RADIUS * FLYBY_RADIUS) continue
            if (!claimSoundWork()) break
            listener.playSound(closest.toLocation(shot.world), "arc:bullet_flyby", SoundCategory.PLAYERS, .8f, 1f)
            bullet.round.heardFlyby += id
        }
    }

    private fun contact(round: Round, result: RayTraceResult, direction: Vector) {
        val shot = round.shot
        val block = result.hitBlock
        val contact = result.hitPosition
        if (round.contacts.any { it.distanceSquared(contact) < IMPACT_SPACING * IMPACT_SPACING }) return
        round.contacts += contact.clone()
        val blockData = block?.takeIf { particles() || sounds() }?.blockData
        val normal = result.hitBlockFace?.takeIf { blockData != null && it.isCartesian }?.direction
            ?: direction.clone().multiply(-1.0)
        val point = contact.clone().add(normal.clone().multiply(if (blockData != null) .075 else .025)).toLocation(shot.world)
        if (particles()) {
            // A shallow patch of material chips stays on the exposed face, even on a grazing hit.
            if (blockData != null) shot.world.spawnParticle(Particle.BLOCK, point, 6,
                .06 - .05 * abs(normal.x), .06 - .05 * abs(normal.y), .06 - .05 * abs(normal.z), .10, blockData)
            else shot.world.spawnParticle(Particle.CRIT, point, 3, .045, .045, .045, .10)
        }
        // Living targets play their own hurt response if the damage route accepts the hit.
        if (blockData == null || !sounds() || !claimSoundWork()) return
        val cue = firearmSurfaceSound(blockData.soundGroup.hitSound)
        shot.world.playSound(contact.toLocation(shot.world), cue.sound, SoundCategory.PLAYERS, cue.volume, cue.pitch)
    }

    private fun claimSoundWork(): Boolean {
        if (soundWork >= MAX_SOUND_WORK_PER_TICK) return false
        soundWork++
        return true
    }

    private fun kick(shot: Shot, aiming: Boolean) {
        val user = PacketEvents.getAPI()?.playerManager?.getUser(shot.shooter) ?: return
        // Relative yaw/pitch arrived in 1.21.9. Never overwrite an older client's current mouse aim.
        if (user.clientVersion.protocolVersion < ClientVersion.V_1_21_9.protocolVersion) return
        val recoil = recoils[shot.shooter.uniqueId]?.takeIf { it.worldId == shot.world.uid && it.entityId == shot.entityId }
            ?: Recoil(shot.shooter, shot.world.uid, shot.entityId).also { recoils[shot.shooter.uniqueId] = it }
        recoil.lastKick = tick
        val scale = if (aiming) .8f / firearmScopeZoom(shot.spec.id) else 1f
        val pitch = firearmRecoilKick(shot.shooter.eyeLocation.pitch, shot.profile.recoil * scale, recoil.pitch)
        if (pitch == 0f) return
        // Weight comes from a stronger vertical impulse, not extra sideways camera shake.
        val yaw = Random.nextDouble(-.07, .07).toFloat() * abs(pitch)
        user.sendPacketSilently(firearmRecoilPacket(yaw, pitch))
        recoil.pitch -= pitch
        recoil.yaw -= yaw
    }

    private fun recoverRecoil() {
        val iterator = recoils.values.iterator()
        while (iterator.hasNext()) {
            val recoil = iterator.next()
            if (!recoil.player.isOnline || recoil.player.isDead || recoil.player.world.uid != recoil.worldId || recoil.player.entityId != recoil.entityId) {
                iterator.remove()
                continue
            }
            val age = tick - recoil.lastKick
            if (age == 0L) continue
            // A short burst holds its peak. A long burst sheds excess rise between muzzles,
            // leaving room for the next visible kick without exceeding the camera limit.
            val pitch = if (age == 1L) (recoil.pitch - 4.5f).coerceAtLeast(0f) * .4f else firearmRecoilRecovery(recoil.pitch)
            if (age == 1L && pitch == 0f) continue
            val yaw = if (age == 1L) recoil.yaw * (pitch / recoil.pitch) else firearmRecoilRecovery(recoil.yaw)
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
        // Core captures the terminal frame on its own sweep; allow one client tick of motion before removal.
        bullet.display?.let { retired += Retired(it, tick + 2) }
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
        impactMarks.close()
        hitMarkers.close()
        bullets.forEach { it.display?.remove() }
        retired.forEach { it.display.remove() }
        bullets.clear()
        blasts.clear()
        inputCompletions.forEach { resolveRound(it, false) }
        inputCompletions.clear()
        rounds.forEach { resolveRound(it.shot, false) }
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
        private const val MAX_SOUND_WORK_PER_TICK = MAX_STEPS_PER_TICK / 3
        private const val MAX_TRAIL_PARTICLES_PER_TICK = 48
        private val TRAIL_DASH = listOf(
            0.0 to Particle.DustTransition(Color.fromRGB(213, 160, 94), Color.fromRGB(69, 62, 56), .90f),
            .85 to Particle.DustTransition(Color.fromRGB(255, 231, 165), Color.fromRGB(99, 85, 68), 1.45f),
        )
        private const val TICK_BUDGET_NANOS = 2_000_000L
        private const val MAX_LIFETIME_TICKS = 40L
        private const val BULLET_GRAVITY = .025
        private const val FLYBY_RADIUS = 2.0
        private const val MIN_FLYBY_TRAVEL = 3.0
        private const val IMPACT_SPACING = 1.5
    }
}

internal enum class FirearmMuzzleStyle(val eventSoundRange: Int) {
    COMPACT(96), RIFLE(128), BLAST(144), BLACK_POWDER(112), SUPPRESSED(80),
}

internal data class FirearmSurfaceSound(val sound: Sound, val volume: Float, val pitch: Float)

internal fun firearmSurfaceSound(hit: Sound): FirearmSurfaceSound = when (hit) {
    Sound.BLOCK_WOOD_HIT, Sound.BLOCK_CHERRY_WOOD_HIT, Sound.BLOCK_NETHER_WOOD_HIT,
    Sound.BLOCK_BAMBOO_WOOD_HIT -> FirearmSurfaceSound(Sound.BLOCK_BAMBOO_BREAK, .60f, 1.35f)
    Sound.BLOCK_STONE_HIT, Sound.BLOCK_DEEPSLATE_HIT, Sound.BLOCK_POLISHED_DEEPSLATE_HIT,
    Sound.BLOCK_DEEPSLATE_BRICKS_HIT, Sound.BLOCK_DEEPSLATE_TILES_HIT ->
        FirearmSurfaceSound(Sound.BLOCK_DRIPSTONE_BLOCK_BREAK, .55f, 1.40f)
    Sound.BLOCK_METAL_HIT -> FirearmSurfaceSound(Sound.BLOCK_CHAIN_HIT, .50f, 1.35f)
    Sound.BLOCK_GLASS_HIT -> FirearmSurfaceSound(Sound.BLOCK_GLASS_BREAK, .34f, 1.55f)
    else -> FirearmSurfaceSound(hit, .45f, 1.20f)
}

internal data class FirearmFlightProfile(
    val speed: Double,
    val recoil: Float,
    val volume: Float,
    val pitch: Float,
    val muzzle: FirearmMuzzleStyle,
)

internal fun firearmFlightProfile(id: FirearmId): FirearmFlightProfile = when (id) {
    FirearmId.FLINTLOCK -> FirearmFlightProfile(14.0, 4.1f, 3.2f, .90f, FirearmMuzzleStyle.BLACK_POWDER)
    FirearmId.REVOLVER -> FirearmFlightProfile(22.0, 3.1f, 2.4f, .92f, FirearmMuzzleStyle.COMPACT)
    FirearmId.HAND_CANNON -> FirearmFlightProfile(12.0, 5.4f, 4f, .82f, FirearmMuzzleStyle.BLAST)
    FirearmId.DOUBLE_BARREL -> FirearmFlightProfile(24.0, 4.5f, 3.6f, .88f, FirearmMuzzleStyle.BLAST)
    FirearmId.FIVE_SEVEN -> FirearmFlightProfile(22.0, 1.7f, 1.8f, 1f, FirearmMuzzleStyle.COMPACT)
    FirearmId.G36 -> FirearmFlightProfile(26.0, 1.25f, 2.7f, .96f, FirearmMuzzleStyle.RIFLE)
    FirearmId.AEK_971 -> FirearmFlightProfile(27.0, 1.4f, 3f, .92f, FirearmMuzzleStyle.RIFLE)
    FirearmId.RPL_20 -> FirearmFlightProfile(25.0, 1.7f, 3.4f, .88f, FirearmMuzzleStyle.RIFLE)
    FirearmId.VEPR_12 -> FirearmFlightProfile(24.0, 3.6f, 3.3f, .90f, FirearmMuzzleStyle.BLAST)
    FirearmId.M1_GARAND -> FirearmFlightProfile(28.0, 3.3f, 3.5f, .90f, FirearmMuzzleStyle.RIFLE)
    FirearmId.VSS_VINTOREZ -> FirearmFlightProfile(15.0, 1.15f, 4f, 1f, FirearmMuzzleStyle.SUPPRESSED)
    FirearmId.MCMILLAN -> FirearmFlightProfile(32.0, 4.8f, 4f, .86f, FirearmMuzzleStyle.RIFLE)
}

/** Preserve configured pellet count/damage, separating multiple consumed rounds into successive ticks. */
internal fun firearmRoundPellets(spec: FirearmSpec): List<Int> {
    val rounds = min(spec.roundsPerShot, spec.pellets).coerceAtLeast(1)
    return List(rounds) { index -> spec.pellets / rounds + if (index < spec.pellets % rounds) 1 else 0 }
}

/** A magazine tail keeps the configured first rounds, including each round's pellet count. */
internal fun firearmTriggerSpec(spec: FirearmSpec, availableRounds: Int): FirearmSpec? {
    if (availableRounds <= 0) return null
    val rounds = minOf(availableRounds, spec.roundsPerShot)
    if (rounds == spec.roundsPerShot) return spec
    return spec.copy(roundsPerShot = rounds, pellets = firearmRoundPellets(spec).take(rounds).sum())
}

internal fun firearmRecoilPacket(yaw: Float, pitch: Float) = WrapperPlayServerPlayerRotation(yaw, true, pitch, true)
internal fun firearmBlastDamage(base: Double, distance: Double, radius: Double): Double =
    if (!distance.isFinite() || distance < 0.0 || distance >= radius) 0.0 else base * (1.0 - .75 * distance / radius)
internal fun firearmSpreadDegrees(spec: FirearmSpec, aiming: Boolean): Double {
    val precise = aiming && spec.projectile == FirearmProjectile.BULLET && spec.pellets <= spec.roundsPerShot
    return spec.spreadDegrees * if (precise) .65 else 1.0
}

internal fun firearmRecoilRecovery(remaining: Float): Float = if (abs(remaining) < .08f) remaining else remaining * .42f
internal fun firearmRecoilKick(currentPitch: Float, strength: Float, recoveryDebt: Float): Float =
    -minOf(strength.coerceAtLeast(0f), (currentPitch + 90f).coerceAtLeast(0f), (9f - recoveryDebt).coerceAtLeast(0f))

internal fun firearmBulletTransform(direction: Vector, length: Float, width: Float): Transformation {
    val rotation = Quaternionf().rotationTo(Vector3f(0f, 0f, 1f), direction.toVector3f().normalize())
    return Transformation(rotation.transform(Vector3f(-width / 2f, -width / 2f, -length)),
        rotation, Vector3f(width, width, length), Quaternionf())
}

internal fun firearmClosestPointOnSegment(point: Vector, start: Vector, end: Vector): Vector {
    val delta = end.clone().subtract(start)
    val lengthSquared = delta.lengthSquared()
    if (lengthSquared == 0.0) return start.clone()
    val fraction = point.clone().subtract(start).dot(delta).div(lengthSquared).coerceIn(0.0, 1.0)
    return start.clone().add(delta.multiply(fraction))
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
