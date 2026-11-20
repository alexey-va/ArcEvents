package ru.ruscrafting.events.paper

import org.bukkit.Color
import org.bukkit.FluidCollisionMode
import org.bukkit.HeightMap
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.Particle
import org.bukkit.Sound
import org.bukkit.entity.Player
import org.bukkit.util.Vector
import java.util.UUID
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin

/** Main-thread effects for one active round. Marks never follow a target and spawn no persistent entities. */
internal class DisastersRound(round: Int, private val startedAt: Long, private val endsAt: Long) {
    private val hazard = DisasterHazard.forRound(round)
    private val secondPass = round >= DisasterHazard.entries.size
    private val pulse = DisastersPulse(DisastersFootprint.WARNING_MS, when (hazard) {
        DisasterHazard.METEORS -> if (secondPass) 750L else 1_250L
        else -> if (secondPass) 1_000L else 1_750L
    }, endsAt)
    private var volley = 0
    private var targetCursor = 0
    private var frame = 0
    private var nextFogDamage = startedAt + 1_000L
    private var marks = emptyList<Mark>()

    private class Mark(val center: Location, val shape: DisastersFootprint) {
        val outline = shape.outline.map { (dx, dz) -> ground(dx, dz) }

        fun ground(dx: Double, dz: Double): Location {
            val top = center.clone().add(dx, DisastersFootprint.VERTICAL_REACH, dz)
            if (!top.world.isChunkLoaded(top.blockX shr 4, top.blockZ shr 4)) return center.clone().add(dx, .12, dz)
            // Most of the pier is full cubes. Only partial blocks (stairs, slabs, rails) need a precise ray.
            for (y in top.blockY downTo floor(center.y - DisastersFootprint.VERTICAL_REACH).toInt()) {
                val material = top.world.getBlockAt(top.blockX, y, top.blockZ).type
                if (material.isAir) continue
                val surface = if (material.isOccluding || material == Material.WATER) y + 1.0 else {
                    val hit = top.world.rayTraceBlocks(top, DOWN, 2 * DisastersFootprint.VERTICAL_REACH, FluidCollisionMode.ALWAYS, false)
                    hit?.hitPosition?.y ?: center.y
                }
                return top.apply { this.y = surface + .12 }
            }
            return top.apply { y = center.y + .12 }
        }

        fun contains(at: Location) = at.world === center.world && shape.contains(at.x - center.x, at.y - center.y, at.z - center.z)
    }

    fun tick(now: Long, actors: Map<UUID, Location>, viewers: List<Player>, particles: Boolean, sounds: Boolean, hurt: (UUID, Double) -> Unit) {
        if (now >= endsAt || actors.isEmpty()) return
        frame++
        if (hazard == DisasterHazard.FOG) {
            fog(now, actors, viewers, particles, sounds, hurt)
            return
        }
        when (val beat = pulse.advance(now)) {
            DisastersPulse.Beat.QUIET -> return
            DisastersPulse.Beat.IMPACT -> {
                for (mark in marks) {
                    if (hazard == DisasterHazard.METEORS) meteorImpact(mark, particles, sounds) else lightningImpact(mark, particles, sounds)
                }
                actors.forEach { (id, at) ->
                    if (marks.any { it.contains(at) } && (hazard == DisasterHazard.METEORS || exposed(at))) {
                        hurt(id, if (hazard == DisasterHazard.METEORS) 12.0 else 6.0)
                    }
                }
                marks = emptyList()
            }
            DisastersPulse.Beat.MARK, DisastersPulse.Beat.CHARGE -> {
                if (beat == DisastersPulse.Beat.MARK) {
                    marks = lock(actors.values.toList())
                    if (sounds) for (mark in marks) mark.center.world.playSound(mark.center, if (hazard == DisasterHazard.METEORS)
                        Sound.ENTITY_FIREWORK_ROCKET_LAUNCH else Sound.BLOCK_RESPAWN_ANCHOR_CHARGE, .75f, if (hazard == DisasterHazard.METEORS) .7f else 1.6f)
                }
                val progress = pulse.charge(now)
                for (mark in marks) {
                    // Danger markings stay legible when optional spectacle and audio are switched off.
                    if (beat == DisastersPulse.Beat.MARK || frame % 2 == 0) for (point in mark.outline) {
                        point.world.spawnParticle(if (hazard == DisasterHazard.METEORS) Particle.FLAME else Particle.ELECTRIC_SPARK, point, 1, 0.0, 0.0, 0.0, 0.0)
                    }
                    if (hazard == DisasterHazard.METEORS) {
                        meteorCountdown(mark, progress)
                        if (particles) meteorFall(mark, progress)
                    } else lightningFuse(mark, progress)
                }
            }
        }
    }

    private fun lock(actors: List<Location>): List<Mark> {
        val limit = if (hazard == DisasterHazard.METEORS) 4 else if (secondPass) 2 else 1
        val marks = ArrayList<Mark>(limit)
        var examined = 0
        while (examined < actors.size && marks.size < limit) {
            val at = actors[(targetCursor + examined++) % actors.size]
            if (marks.none { it.contains(at) }) {
                marks += Mark(at.clone(), DisastersFootprint(hazard, (volley + marks.size) % 2 == 1))
            }
        }
        targetCursor = (targetCursor + examined) % actors.size
        volley++
        return marks
    }

    private fun meteorCountdown(mark: Mark, progress: Double) {
        val radius = DisastersFootprint.METEOR_RADIUS * (1.0 - progress)
        repeat(4) { arm ->
            val angle = arm * PI / 2
            val at = mark.center
            at.world.spawnParticle(Particle.SMALL_FLAME, at.x + radius * cos(angle), at.y + .19, at.z + radius * sin(angle), 1, 0.0, 0.0, 0.0, 0.0)
        }
    }

    private fun meteorFall(mark: Mark, progress: Double) {
        val center = mark.center
        fun head(t: Double): Location {
            val altitude = DisastersFootprint.meteorAltitude(t)
            return center.clone().add(altitude * .65, altitude + .17, -altitude * .30)
        }
        val fireball = head(progress)
        center.world.spawnParticle(Particle.DUST, fireball, 3, .12, .11, .12, 0.0, CINDER)
        center.world.spawnParticle(Particle.FLAME, fireball, 8, .21, .15, .21, .018)
        for (lag in listOf(.06, .13, .21)) {
            val tail = head((progress - lag).coerceAtLeast(0.0))
            center.world.spawnParticle(Particle.SMOKE, tail, 3, .16, .1, .16, .012)
        }
    }

    private fun meteorImpact(mark: Mark, particles: Boolean, sounds: Boolean) {
        val at = mark.center
        if (particles) {
            at.world.spawnParticle(Particle.EXPLOSION, at.clone().add(0.0, .3, 0.0), 1)
            repeat(8) { i ->
                val angle = i * PI / 4
                at.world.spawnParticle(Particle.CLOUD, at.x, at.y + .18, at.z, 0, cos(angle), .14, sin(angle), .32)
            }
        }
        if (sounds) at.world.playSound(at, Sound.ENTITY_GENERIC_EXPLODE, .85f, .82f)
    }

    private fun lightningFuse(mark: Mark, progress: Double) {
        // Twin runners travel between the fixed edges. At the far post the entire marked lane discharges.
        val along = DisastersFootprint.LANE_LENGTH * (2 * progress - 1)
        for (side in listOf(-DisastersFootprint.LANE_WIDTH, DisastersFootprint.LANE_WIDTH)) {
            val (dx, dz) = mark.shape.offset(along, side)
            val at = mark.ground(dx, dz)
            at.world.spawnParticle(Particle.END_ROD, at, 3, .12, .2, .12, .025)
        }
    }

    private fun lightningImpact(mark: Mark, particles: Boolean, sounds: Boolean) {
        val at = mark.center
        val roofY = roof(at)
        val contact = at.clone().apply { if (roofY != null) y = roofY + 1.0 }
        // A vanilla visual bolt supplies its own thunder. Keep the silent and low-particle settings independent.
        if (particles && sounds) at.world.strikeLightningEffect(contact)
        else if (sounds) at.world.playSound(contact, Sound.ENTITY_LIGHTNING_BOLT_THUNDER, .9f, 1.1f)
        if (particles) {
            if (!sounds) {
                for (i in 0..22) {
                    val step = i / 22.0
                    val kink = sin(i * 1.05) * (if (i % 4 == 0) .65 else .28) * step
                    at.world.spawnParticle(Particle.END_ROD, contact.x + 1.8 * step + kink, contact.y + 10 * step, contact.z + kink, 2, .03, .03, .03, 0.0)
                }
            }
            for (step in -6..6) {
                val (dx, dz) = mark.shape.offset(step * DisastersFootprint.LANE_LENGTH / 6, .65 * sin(step * 1.7))
                val spark = mark.ground(dx, dz)
                at.world.spawnParticle(Particle.ELECTRIC_SPARK, spark, 5, .24, .42, .24, .08)
            }
        }
    }

    private fun roof(at: Location): Int? {
        if (!at.world.isChunkLoaded(at.blockX shr 4, at.blockZ shr 4)) return null
        return at.world.getHighestBlockYAt(at.blockX, at.blockZ, HeightMap.OCEAN_FLOOR).takeIf { it >= at.blockY + 2 }
    }

    private fun exposed(at: Location) = roof(at) == null

    private fun fog(now: Long, actors: Map<UUID, Location>, viewers: List<Player>, particles: Boolean, sounds: Boolean, hurt: (UUID, Double) -> Unit) {
        val progress = (now - startedAt).toDouble() / (endsAt - startedAt)
        val surface = DisastersFootprint.fogSurface(progress, DisasterArenaGenerator.FOG_SAFE_Y, secondPass)
        val pulse = now >= nextFogDamage
        if (pulse) nextFogDamage = now + 1_000L
        // Each observer sees the same moving sea-level bands sampled on the world grid near them.
        // Viewers sharing a pier do not multiply one another's fog packets or fill each other's cameras.
        for (viewer in viewers) {
            val eye = viewer.location
            val originX = floor(eye.x / 5.0) * 5.0
            val originZ = floor(eye.z / 6.0) * 6.0
            val wind = ((now - startedAt) % 3_000L) / 3_000.0 * 6.0
            for (row in -1..1) for (column in -1..1) {
                val x = originX + 5 * column
                val z = originZ + 6 * row + wind + .6 * sin(x * .45)
                if ((x - eye.x) * (x - eye.x) + (z - eye.z) * (z - eye.z) < 2.0) continue
                viewer.spawnParticle(Particle.CLOUD, x, surface - .14, z, if (particles) 4 else 1, 1.2, .09, .26, .006)
                if (particles && column == 0) viewer.spawnParticle(Particle.DUST, x, surface - .07, z, 4, 1.1, .07, .22, 0.0, SEA_MIST)
            }
            if (sounds && (frame == 1 || frame % 20 == 0)) viewer.playSound(eye, Sound.BLOCK_BUBBLE_COLUMN_WHIRLPOOL_AMBIENT, .6f, .65f)
        }
        if (pulse) actors.forEach { (id, at) -> if (at.y < surface) hurt(id, 3.0) }
    }

    private companion object {
        val DOWN = Vector(0.0, -1.0, 0.0)
        val CINDER = Particle.DustOptions(Color.fromRGB(109, 51, 32), 2.3f)
        val SEA_MIST = Particle.DustOptions(Color.fromRGB(150, 208, 200), 1.75f)
    }
}
