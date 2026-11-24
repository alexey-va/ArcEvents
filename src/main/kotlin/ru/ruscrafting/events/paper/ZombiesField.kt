package ru.ruscrafting.events.paper

import net.kyori.adventure.text.Component
import org.bukkit.FluidCollisionMode
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.Particle
import org.bukkit.Sound
import org.bukkit.World
import org.bukkit.entity.Display
import org.bukkit.entity.Player
import org.bukkit.entity.Zombie
import org.bukkit.potion.PotionEffect
import org.bukkit.potion.PotionEffectType
import org.bukkit.util.BoundingBox
import org.bukkit.util.Vector
import ru.arc.paper.display.PacketBlockDisplay
import ru.arc.paper.display.PaperPacketDisplays
import ru.ruscrafting.events.config.ArcEventsLocale
import ru.ruscrafting.events.domain.ZombiesDirector
import ru.ruscrafting.events.domain.ZombiesGear
import java.util.UUID

/** Temporary supplies, actors and effects inside the existing Zombies arena lease. */
internal class ZombiesField(
    private val world: World,
    private val matchId: UUID,
    private val director: ZombiesDirector,
    private val displays: PaperPacketDisplays,
    private val locale: ArcEventsLocale,
    private val standing: (Player) -> Boolean,
    private val viewers: () -> List<Player>,
    private val mobs: () -> List<Zombie>,
    private val damage: (Player, Zombie, Double, Boolean) -> Unit,
    private val claim: (Player) -> Boolean,
    private val sounds: () -> Boolean,
    private val particles: () -> Boolean,
) : AutoCloseable {
    private data class Drop(
        val location: Location,
        val visual: ZombiesAirdropVisual,
        val claimed: MutableSet<UUID> = mutableSetOf(),
        var expiresAt: Long = Long.MAX_VALUE,
        var crate: PacketBlockDisplay? = null,
    )

    private data class Placement(
        val kind: ZombiesGear,
        val location: Location,
        val visual: ZombiesGearVisual,
        var durability: Int,
        var nextUseAt: Long = 0,
    )

    private data class Charge(
        val player: Player,
        val kind: ZombiesGear,
        val origin: Location,
        val impact: Location,
        val thrownAt: Long,
        val fuseAt: Long,
    )

    private data class Smoke(val center: Location, val expiresAt: Long)

    private val drops = mutableListOf<Drop>()
    private val barriers = mutableMapOf<UUID, Placement>()
    private val turrets = mutableMapOf<UUID, Placement>()
    private val charges = mutableMapOf<UUID, Charge>()
    private val clouds = mutableListOf<Smoke>()
    private var nextSmokeFrame = 0L

    fun hasPendingDrop() = drops.isNotEmpty()
    fun status() = "airdrop=${drops.count { it.expiresAt != Long.MAX_VALUE }}/${drops.size} " +
        "barriers=${barriers.size} turrets=${turrets.size} smoke=${clouds.size}"

    fun drop(now: Long, manual: Boolean = false): Boolean {
        if (manual && drops.isNotEmpty()) return false
        val locations = DROP_Z.map { Location(world, .5, 65.0, it + .5) }.filter(::usableFloor)
        val location = locations.firstOrNull { candidate -> drops.none { it.location == candidate } }
            ?: drops.removeFirstOrNull()?.also(::remove)?.location ?: return false
        drops += Drop(location, ZombiesAirdropVisual(displays, matchId, location, now))
        viewers().forEach { it.sendEventMessage(locale.render("zombies.field.drop-incoming", it)) }
        return true
    }

    fun interact(player: Player, now: Long): Boolean {
        if (!standing(player)) return false
        val eye = player.eyeLocation
        val from = eye.toVector()
        val direction = eye.direction
        val drop = drops.asSequence().filter { now < it.expiresAt && it.expiresAt != Long.MAX_VALUE }
            .filter { it.location.distanceSquared(eye) < 36.0 }
            .mapNotNull { drop ->
                val center = drop.location.toVector()
                val hit = BoundingBox.of(center.clone().add(Vector(0.0, .4, 0.0)), .55, .5, .55)
                    .rayTrace(from, direction, 5.0)?.hitPosition ?: return@mapNotNull null
                val distance = hit.distance(from)
                if (world.rayTraceBlocks(eye, direction, distance, FluidCollisionMode.NEVER, true) != null) return@mapNotNull null
                drop to distance
            }.minByOrNull { it.second }?.first ?: return false
        if (player.uniqueId in drop.claimed) return true
        if (claim(player)) {
            drop.claimed += player.uniqueId
            drop.crate?.hideFrom(player)
            player.sendEventMessage(locale.render("zombies.field.drop-claimed", player))
        }
        return true
    }

    fun perform(player: Player, gear: ZombiesGear, now: Long): Boolean {
        if (!standing(player) || !director.canBuyGear(player.uniqueId, gear)) return false
        return when (gear) {
            ZombiesGear.BENCH -> director.buyGear(player.uniqueId, gear)
            ZombiesGear.WIRE, ZombiesGear.WALL, ZombiesGear.TURRET -> place(player, gear)
            ZombiesGear.FRAG, ZombiesGear.SMOKE -> throwCharge(player, gear, now)
            ZombiesGear.HE, ZombiesGear.AP -> fireTurret(player, gear, now)
        }
    }

    private fun place(player: Player, kind: ZombiesGear): Boolean {
        val owner = player.uniqueId
        val placements = if (kind == ZombiesGear.TURRET) turrets else barriers
        if (owner in placements) return false
        val eye = player.eyeLocation
        val hit = world.rayTraceBlocks(eye, eye.direction, 4.75, FluidCollisionMode.NEVER, true) ?: return false
        if (hit.hitBlockFace != org.bukkit.block.BlockFace.UP) return false
        val floor = hit.hitBlock ?: return false
        val point = Location(world, floor.x + .5, floor.y + 1.0, floor.z + .5, player.location.yaw, 0f)
        if (!usableFloor(point) || point.distanceSquared(player.location) < 1.0 ||
            (barriers.values + turrets.values).any { it.location.distanceSquared(point) < 2.25 } ||
            drops.any { it.location.distanceSquared(point) < 1.5 }) return false
        val visual = ZombiesGearVisual(displays, point, kind)
        if (!director.buyGear(owner, kind)) {
            visual.close()
            return false
        }
        placements[owner] = Placement(kind, point, visual, if (kind == ZombiesGear.WIRE) 12 else 24)
        visual.showTo(viewers())
        return true
    }

    private fun throwCharge(player: Player, kind: ZombiesGear, now: Long): Boolean {
        if (player.uniqueId in charges) return false
        val eye = player.eyeLocation
        val targets = mobs().mapTo(hashSetOf(), Zombie::getUniqueId)
        val hit = world.rayTrace(eye, eye.direction, 12.0, FluidCollisionMode.NEVER, true, .1) { it.uniqueId in targets }
        val impact = hit?.hitPosition?.add(eye.direction.multiply(-.12))?.toLocation(world)
            ?: eye.clone().add(eye.direction.multiply(10.0))
        // Land on a nearby floor when the throw was aimed into clear air.
        val landing = world.rayTraceBlocks(impact, Vector(0, -1, 0), 5.0, FluidCollisionMode.NEVER, true)
            ?.hitPosition?.add(Vector(0.0, .15, 0.0))?.toLocation(world) ?: impact
        if (!director.buyGear(player.uniqueId, kind)) return false
        charges[player.uniqueId] = Charge(player, kind, eye, landing, now, now + 800)
        if (sounds()) player.playSound(player.location, Sound.ENTITY_SNOWBALL_THROW, .7f, .85f)
        return true
    }

    private fun fireTurret(player: Player, kind: ZombiesGear, now: Long): Boolean {
        val turret = turrets[player.uniqueId] ?: return false
        if (turret.location.distanceSquared(player.location) > 16.0 || now < turret.nextUseAt) return false
        val muzzle = turret.location.clone().add(0.0, ZombiesGearVisual.MUZZLE_HEIGHT, 0.0)
        val targets = mobs().mapTo(hashSetOf(), Zombie::getUniqueId)
        val eye = player.eyeLocation
        val aim = world.rayTrace(eye, eye.direction, 24.0, FluidCollisionMode.NEVER, true, .1) { it.uniqueId in targets }
            ?.hitPosition ?: eye.toVector().add(eye.direction.multiply(24.0))
        val direction = aim.subtract(muzzle.toVector())
        if (direction.lengthSquared() < .25) return false
        val range = (direction.length() + .3).coerceAtMost(25.0)
        direction.normalize()
        val hit = world.rayTrace(muzzle, direction, range, FluidCollisionMode.NEVER, true, .1) { it.uniqueId in targets }
        if (!director.buyGear(player.uniqueId, kind)) return false
        turret.nextUseAt = now + 1800
        turret.visual.faceShot(direction)
        val impact = hit?.hitPosition?.add(direction.clone().multiply(-.12))?.toLocation(world)
            ?: muzzle.clone().add(direction.clone().multiply(range))
        if (particles()) {
            val distance = impact.distance(muzzle)
            for (step in 1..distance.toInt()) {
                world.spawnParticle(Particle.SMOKE, muzzle.clone().add(direction.clone().multiply(step)), 1, .04, .04, .04, 0.0)
            }
        }
        if (kind == ZombiesGear.HE) {
            blast(player, impact, 5.0, 36.0)
        } else {
            if (sounds()) world.playSound(muzzle, Sound.ENTITY_FIREWORK_ROCKET_BLAST, .8f, .7f)
            (hit?.hitEntity as? Zombie)?.let { damage(player, it, 95.0, true) }
        }
        return true
    }

    fun tick(now: Long) {
        val audience = viewers()
        drops.removeIf { drop ->
            if (drop.expiresAt == Long.MAX_VALUE && drop.visual.tick(now, audience)) {
                drop.expiresAt = now + 15_000
                drop.crate = displays.spawnBlock(drop.location.clone().add(-.5, 0.0, -.5), Material.BARREL.createBlockData()).apply {
                    brightness = Display.Brightness(15, 15)
                    isGlowing = true
                    isVisibleByDefault = false
                }
                audience.forEach { it.sendEventMessage(locale.render("zombies.field.drop-ready", it)) }
                if (sounds()) world.playSound(drop.location, Sound.BLOCK_BARREL_OPEN, .8f, .8f)
            }
            drop.crate?.let { crate -> audience.filter { it.uniqueId !in drop.claimed }.forEach(crate::showTo) }
            val expired = now >= drop.expiresAt || audience.isEmpty() || audience.all { it.uniqueId in drop.claimed }
            if (expired) remove(drop)
            expired
        }

        if (barriers.isNotEmpty() || clouds.isNotEmpty()) tickDefense(now)
        turrets.values.forEach { it.visual.showTo(audience) }
        val fired = charges.values.filter { now >= it.fuseAt }.toList()
        fired.forEach { charges.remove(it.player.uniqueId) }
        for (charge in fired) {
            if (!standing(charge.player)) continue
            if (charge.kind == ZombiesGear.FRAG) blast(charge.player, charge.impact, 3.0, 26.0)
            else clouds += Smoke(charge.impact, now + 8_000)
        }
        if (particles()) charges.values.forEach { charge ->
            val fraction = ((now - charge.thrownAt).toDouble() / (charge.fuseAt - charge.thrownAt)).coerceIn(0.0, 1.0)
            val point = charge.origin.clone().add(charge.impact.toVector().subtract(charge.origin.toVector()).multiply(fraction))
                .add(0.0, 3.0 * fraction * (1 - fraction), 0.0)
            world.spawnParticle(Particle.CRIT, point, 2, .05, .05, .05, 0.0)
        }
    }

    private fun tickDefense(now: Long) {
        val living = mobs()
        val audience = viewers()
        val smokeFrame = particles() && now >= nextSmokeFrame
        if (smokeFrame) nextSmokeFrame = now + 400
        clouds.removeIf { now >= it.expiresAt }
        clouds.forEach { cloud ->
            if (smokeFrame) world.spawnParticle(Particle.CAMPFIRE_COSY_SMOKE, cloud.center, 5, .85, .15, .85, .005)
            living.filter { it.location.distanceSquared(cloud.center) <= 9.0 }.forEach {
                it.addPotionEffect(PotionEffect(PotionEffectType.SLOWNESS, 10, 2, false, false))
            }
        }
        barriers.values.removeIf { barrier ->
            barrier.visual.showTo(audience)
            val nearby = living.filter { it.location.distanceSquared(barrier.location) <= 2.25 }
            nearby.forEach {
                it.addPotionEffect(PotionEffect(PotionEffectType.SLOWNESS, 8,
                    if (barrier.kind == ZombiesGear.WALL) 9 else 3, false, false))
            }
            if (nearby.isNotEmpty() && now >= barrier.nextUseAt) {
                barrier.nextUseAt = now + 1000
                barrier.durability--
            }
            if (barrier.durability > 0) false else {
                barrier.visual.close()
                if (sounds()) world.playSound(barrier.location, Sound.BLOCK_STONE_BREAK, .65f, .8f)
                true
            }
        }
    }

    private fun blast(player: Player, center: Location, radius: Double, maxDamage: Double) {
        if (particles()) world.spawnParticle(Particle.EXPLOSION, center, 1)
        if (sounds()) world.playSound(center, Sound.ENTITY_GENERIC_EXPLODE, .65f, 1.1f)
        for (mob in mobs()) {
            val delta = mob.eyeLocation.toVector().subtract(center.toVector())
            val distance = delta.length()
            if (distance > radius || distance > .03 &&
                world.rayTraceBlocks(center, delta.normalize(), distance, FluidCollisionMode.NEVER, true) != null) continue
            damage(player, mob, maxDamage * (1 - .6 * distance / radius), false)
        }
    }

    private fun usableFloor(point: Location): Boolean {
        if (!world.isChunkLoaded(point.blockX shr 4, point.blockZ shr 4)) return false
        val block = point.block
        return block.isPassable && block.getRelative(0, 1, 0).isPassable && block.getRelative(0, -1, 0).type.isSolid
    }

    fun disconnect(player: Player) {
        barriers.remove(player.uniqueId)?.visual?.close()
        turrets.remove(player.uniqueId)?.visual?.close()
        charges.remove(player.uniqueId)
    }

    override fun close() {
        drops.forEach(::remove)
        drops.clear()
        (barriers.values + turrets.values).forEach { it.visual.close() }
        barriers.clear()
        turrets.clear()
        charges.clear()
        clouds.clear()
    }

    private fun remove(drop: Drop) {
        drop.visual.close()
        drop.crate?.remove()
    }

    private companion object {
        val DROP_Z = listOf(-18, -17, -16, 7)
    }
}
