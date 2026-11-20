package ru.ruscrafting.events.paper

import net.kyori.adventure.text.Component
import net.kyori.adventure.util.TriState
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.Particle
import org.bukkit.Sound
import org.bukkit.attribute.Attribute
import org.bukkit.entity.Entity
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Pillager
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import org.bukkit.plugin.Plugin
import ru.ruscrafting.events.config.ArenaSettings
import ru.ruscrafting.events.config.EventLocation
import ru.ruscrafting.events.domain.FirearmId
import ru.ruscrafting.events.domain.PracticeRoster
import ru.ruscrafting.events.domain.QueuedPlayer
import java.util.UUID
import kotlin.math.cos
import kotlin.math.sin

/** Physical opponents for a single private TTT or Gun Game roster. Only the tester sees them. */
internal class PracticeFighters(
    private val plugin: Plugin,
    val roster: PracticeRoster,
    private val owner: Player,
    private val arena: ArenaSettings,
    private val firearms: TttFirearms,
    private val clock: () -> Long,
) : AutoCloseable {
    private class Fighter(val mob: Pillager, var nextAttack: Long, var weapon: FirearmId?, var attacker: UUID? = null,
        var investigationUntil: Long = 0)
    private val fighters = mutableMapOf<UUID, Fighter>()
    private val world = requireNotNull(plugin.server.getWorld(arena.world))
    private var step = 0
    private var lastMeleeAt = Long.MIN_VALUE

    private val authoredSpawns = arena.spawns.map { Location(world, it.x, it.y, it.z, it.yaw, it.pitch) }
    private val waypoints = nearbyWaypoints(practiceOrigin()).ifEmpty { authoredSpawns }

    private fun practiceOrigin(): Location =
        owner.location.takeIf { it.world === world && contains(it) } ?: authoredSpawns.first()

    private fun nearbyWaypoints(origin: Location): List<Location> {
        val nearby = listOf(5.0, 9.0, 2.0).flatMap { distance ->
            (0..7).map { i -> origin.clone().add(cos(i * Math.PI / 4) * distance, 0.0, sin(i * Math.PI / 4) * distance) }
        }
        val points = arena.lootSpawns.asSequence().map { Location(world, it.x, it.y, it.z) }
            .filter { it.distanceSquared(origin) <= 32.0 * 32.0 }.take(48).toList()
        val authored = authoredSpawns.filter { it.distanceSquared(origin) in 16.0..576.0 }
        return (authored + nearby + points).mapNotNull { point ->
            listOf(0, 1, -1).firstNotNullOfOrNull { dy ->
                val at = Location(world, point.blockX + .5, (point.blockY + dy).toDouble(), point.blockZ + .5)
                at.takeIf(::safeSpawn)
            }
        }.distinct()
    }

    private fun safeSpawn(at: Location): Boolean = contains(at) &&
        world.isChunkLoaded(at.blockX shr 4, at.blockZ shr 4) && at.hasArenaFooting()

    val count: Int get() = fighters.size
    fun actor(id: UUID): LivingEntity? = if (id == owner.uniqueId) owner else fighters[id]?.mob
    fun identity(entity: Entity?): UUID? = if (entity !is Pillager) null else fighters.entries.firstOrNull { it.value.mob.uniqueId == entity.uniqueId }?.key
    fun health(id: UUID): Double? = fighters[id]?.mob?.health
    fun investigate(id: UUID, location: Location): Boolean {
        val fighter = fighters[id] ?: return false
        if (location.world !== world || !contains(location)) return false
        fighter.investigationUntil = clock() + 12_000L
        return fighter.mob.pathfinder.moveTo(location, 1.0)
    }
    fun beginMelee(): Boolean {
        val now = clock()
        if (lastMeleeAt != Long.MIN_VALUE && now - lastMeleeAt < 500) return false
        lastMeleeAt = now
        return true
    }

    fun tick(alive: (UUID) -> Boolean, weapon: (UUID) -> FirearmId?, enemy: (UUID, UUID, UUID?) -> Boolean,
        attack: (UUID, UUID, FirearmId?) -> Unit) {
        step++
        val now = clock()
        val origin by lazy(::practiceOrigin)
        val reachable by lazy { reachablePracticeSpawnCells(origin, ::safeSpawn) }
        for ((index, bot) in roster.bots.withIndex()) {
            if (!alive(bot.playerId)) { remove(bot.playerId); continue }
            val selected = weapon(bot.playerId)
            val fighter = fighters[bot.playerId] ?: spawn(bot, index, selected, now, origin, reachable) ?: continue
            if (!fighter.mob.isValid || !contains(fighter.mob.location)) {
                remove(bot.playerId)
                continue
            }
            if (fighter.weapon != selected) { fighter.weapon = selected; equip(fighter.mob, selected) }
            val targetId = (listOf(owner.uniqueId) + fighters.keys).asSequence()
                .filter { it != bot.playerId && alive(it) && enemy(bot.playerId, it, fighter.attacker) }
                .mapNotNull { id -> actor(id)?.takeIf { it.world === world && contains(it.location) }?.let { id to it } }
                .minByOrNull { (_, entity) -> entity.location.distanceSquared(fighter.mob.location) }?.first
            val target = targetId?.let(::actor)
            if (target == null) {
                if (now >= fighter.investigationUntil && (step + index) % 4 == 0) {
                    val targetPoint = waypoints[(step / 4 + index) % waypoints.size]
                    if (safeSpawn(targetPoint)) fighter.mob.pathfinder.moveTo(targetPoint, .85)
                }
                continue
            }
            val distance = fighter.mob.location.distanceSquared(target.location)
            val inRange = distance <= if (selected == null) 2.6 * 2.6 else 16.0 * 16.0
            val visible = inRange && fighter.mob.hasLineOfSight(target)
            fighter.mob.lookAt(target)
            if (!visible || distance > 7 * 7) fighter.mob.pathfinder.moveTo(target, .92) else fighter.mob.pathfinder.stopPathfinding()
            if (visible && now >= fighter.nextAttack) {
                fighter.nextAttack = now + 3000L + index * 170L
                fighter.mob.swingMainHand()
                if (selected != null) {
                    owner.playSound(fighter.mob.location, Sound.ENTITY_FIREWORK_ROCKET_BLAST, .45f, .85f)
                    owner.spawnParticle(Particle.SMOKE, fighter.mob.eyeLocation, 3, .1, .1, .1, .01)
                }
                attack(bot.playerId, requireNotNull(targetId), selected)
            }
        }
    }

    fun damage(id: UUID, attacker: UUID, amount: Double): Boolean {
        val fighter = fighters[id] ?: return false
        fighter.attacker = attacker
        val remaining = fighter.mob.health - amount
        if (remaining <= 0) { remove(id); return true }
        fighter.mob.health = remaining
        fighter.mob.playHurtAnimation(0f)
        return false
    }

    private fun spawn(
        bot: QueuedPlayer,
        index: Int,
        selected: FirearmId?,
        now: Long,
        origin: Location,
        reachable: Set<PracticeSpawnCell>,
    ): Fighter? {
        if (reachable.isEmpty()) return null
        val occupied = fighters.values.map { it.mob.location }.filter { it.world === world } + origin
        fun available(at: Location): Boolean =
            PracticeSpawnCell(at) in reachable && occupied.none { it.distanceSquared(at) < 2.25 }
        val candidates = nearbyWaypoints(origin).filter(::available).ifEmpty { authoredSpawns.filter(::available) }
            .ifEmpty { reachable.map { it.location(world) }.filter(::available) }
        if (candidates.isEmpty()) return null
        val point = candidates[(index * 3 + step / 2) % candidates.size]
        val mob = world.spawn(point, Pillager::class.java) {
            it.isPersistent = false
            it.removeWhenFarAway = false
            it.despawnInPeacefulOverride = TriState.FALSE
            it.isInvulnerable = true
            it.isSilent = true
            it.isCollidable = false
            it.getAttribute(Attribute.MAX_HEALTH)?.baseValue = 20.0
            it.health = 20.0
            it.addScoreboardTag(TAG)
            it.setVisibleByDefault(false)
            it.setCanPickupItems(false)
            it.setCanJoinRaid(false)
            it.isPatrolLeader = false
            it.lootTable = null
            it.customName(Component.text(bot.playerName))
            it.isCustomNameVisible = true
            plugin.server.mobGoals.removeAllGoals(it)
            equip(it, selected)
        }
        return Fighter(mob, now + 7000L + index * 650L, selected).also {
            fighters[bot.playerId] = it
            owner.showEntity(plugin, mob)
        }
    }

    private fun equip(mob: Pillager, firearm: FirearmId?) {
        mob.equipment.setItemInMainHand(firearm?.let { firearms.firearmItem(it, owner, roster.matchId.toString()) } ?: ItemStack(Material.IRON_SWORD))
        mob.equipment.itemInMainHandDropChance = 0f
    }

    private fun contains(at: Location) = arena.bounds?.contains(EventLocation(world.name, at.x, at.y, at.z)) == true
    private fun remove(id: UUID) { fighters.remove(id)?.mob?.remove() }
    override fun close() { fighters.keys.toList().forEach(::remove) }

    companion object { const val TAG = "arcevents_practice_fighter" }
}
