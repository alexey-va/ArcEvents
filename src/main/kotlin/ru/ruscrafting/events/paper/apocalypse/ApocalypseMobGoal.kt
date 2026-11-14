package ru.ruscrafting.events.paper.apocalypse

import com.destroystokyo.paper.entity.ai.Goal
import com.destroystokyo.paper.entity.ai.GoalKey
import com.destroystokyo.paper.entity.ai.GoalType
import org.bukkit.FluidCollisionMode
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.block.Block
import org.bukkit.entity.AbstractArrow
import org.bukkit.entity.Arrow
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Zombie
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.inventory.ItemStack
import org.bukkit.plugin.Plugin
import org.bukkit.util.Vector
import ru.ruscrafting.events.domain.apocalypse.ApocalypseZombieSpawn
import ru.ruscrafting.events.domain.apocalypse.ApocalypseZombieStats
import java.util.EnumSet
import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.sqrt
import kotlin.random.Random

/** Install only on an owned apocalypse actor; its runtime owns target selection and removal. */
fun installApocalypseMobGoal(
    plugin: Plugin,
    mob: Zombie,
    spawn: ApocalypseZombieSpawn,
    stats: ApocalypseZombieStats,
    canBreak: (Block) -> Boolean,
) {
    spawn.validated()
    val goals = plugin.server.mobGoals
    val goal = ApocalypseMobGoal(plugin, mob, spawn, stats, canBreak)
    // Keys survive plugin generations. Remove every old instance before replacing MOVE behavior;
    // Paper's native WrappedGoal removal stops a running goal and clears its item/path state.
    goals.removeGoal(mob, goal.getKey())
    goals.removeAllGoals(mob, GoalType.MOVE)
    when (spawn.sourceEntityId) {
        "zombies_plus:bow_zombie" -> mob.equipment.setItemInMainHand(ItemStack.of(Material.BOW))
        "zombies_plus:crossbow_zombie" -> mob.equipment.setItemInMainHand(ItemStack.of(Material.CROSSBOW))
    }
    goals.addGoal(mob, 0, goal)
}

/**
 * Paper's entity-owned Goal API keeps these clocks with the actor, without scheduler tasks.
 * Attribute values are installed by the caller; this goal applies their separate pursuit multiplier.
 * Bow: 20-tick draw/cycle and range15. Crossbow: 25-tick charge plus15-tick recovery;
 * vanilla crossbow's randomized charge states/client pose cannot be reproduced by a zombie carrier.
 */
internal class ApocalypseMobGoal(
    private val plugin: Plugin,
    private val mob: Zombie,
    private val spawn: ApocalypseZombieSpawn,
    private val stats: ApocalypseZombieStats,
    private val canBreak: (Block) -> Boolean,
    private val random: Random = Random(mob.uniqueId.mostSignificantBits xor mob.uniqueId.leastSignificantBits),
) : Goal<Zombie> {
    private val key = GoalKey.of(Zombie::class.java, NamespacedKey(plugin, "apocalypse_combat"))
    private val world = mob.world
    private val crossbow = spawn.sourceEntityId == "zombies_plus:crossbow_zombie"
    private val ranged = stats.shootRadius > 0
    private var lastTick = world.gameTime - 1
    private var nextPathAt = 0L
    private var nextMeleeAt = 0L
    private var nextChargeAt = 0L
    private var chargeStartedAt: Long? = null
    private var leaping = false

    override fun getKey(): GoalKey<Zombie> = key
    override fun getTypes(): EnumSet<GoalType> = EnumSet.of(GoalType.MOVE, GoalType.LOOK)
    override fun shouldActivate(): Boolean = target() != null
    override fun shouldStayActive(): Boolean = shouldActivate()

    override fun start() {
        lastTick = world.gameTime - 1
        nextPathAt = 0
        mob.isAggressive = true
    }

    override fun stop() {
        mob.pathfinder.stopPathfinding()
        stopCharging()
        leaping = false
        mob.isAggressive = false
    }

    override fun tick() {
        val target = target() ?: return
        val now = world.gameTime
        if (now <= lastTick) return
        // PaperCustomGoal may be ticked every other native tick. Never replay a long inactive gap.
        val elapsed = (now - lastTick).coerceIn(1, 2).toInt()
        lastTick = now
        mob.lookAt(target, 30f, 30f)
        if (spawn.profile == "raged" && random.nextDouble() < 1 - .97.pow(elapsed)) breakObstruction(target)

        val distance = mob.location.distanceSquared(target.location)
        if (leaping) {
            if (!mob.isOnGround) return
            leaping = false
        }
        if (stats.leapVelocity > 0 && mob.isOnGround && mob.passengers.isEmpty() && distance in 4.0..16.0 &&
            (0 until elapsed).any { random.nextInt(5) == 0 }) {
            mob.pathfinder.stopPathfinding()
            mob.velocity = apocalypseLeapVelocity(target.location.toVector().subtract(mob.location.toVector()), mob.velocity, stats.leapVelocity)
            leaping = true
            return
        }

        val visible = mob.hasLineOfSight(target)
        if (ranged && visible && distance <= stats.shootRadius * stats.shootRadius) {
            mob.pathfinder.stopPathfinding()
            rangedAttack(target, now)
            return
        }
        stopCharging()
        if (now >= nextPathAt) {
            mob.pathfinder.moveTo(target, stats.pursuitSpeed)
            nextPathAt = now + 10
        }
        val reach = mob.width * 2
        if (!ranged && visible && distance <= reach * reach + target.width && now >= nextMeleeAt) {
            nextMeleeAt = now + 20
            mob.swingMainHand()
            mob.attack(target) // Native damage/event pipeline, including the owning runtime's infection listener.
        }
    }

    private fun target(): LivingEntity? {
        if (!plugin.isEnabled || !mob.isValid || mob.isDead || mob.world.uid != world.uid) return null
        val target = mob.target ?: return null
        if (!target.isValid || target.isDead || target.world.uid != world.uid) return null
        val at = target.location
        return target.takeIf { world.isChunkLoaded(at.blockX shr 4, at.blockZ shr 4) }
    }

    private fun rangedAttack(target: LivingEntity, now: Long) {
        if (now < nextChargeAt) return
        val chargeTicks = if (crossbow) 25 else stats.shootInterval.coerceAtLeast(1)
        if (chargeStartedAt == null) {
            chargeStartedAt = now
            mob.startUsingItem(EquipmentSlot.HAND)
        }
        if (now - requireNotNull(chargeStartedAt) < chargeTicks) return
        val origin = mob.eyeLocation
        val destination = target.location.add(0.0, target.height / 3, 0.0)
        val direction = destination.toVector().subtract(origin.toVector())
        val horizontal = sqrt(direction.x * direction.x + direction.z * direction.z)
        direction.y += horizontal * .20000000298023224
        if (direction.lengthSquared() < 1e-7) return
        // Native Arrow retains collision, gravity, damage attribution and platform projectile events.
        val arrow = mob.launchProjectile(Arrow::class.java, direction.normalize().multiply(1.6)) {
            it.pickupStatus = AbstractArrow.PickupStatus.DISALLOWED
            it.isShotFromCrossbow = crossbow
            it.setWeapon(mob.equipment.itemInMainHand.clone())
            it.isPersistent = false
        }
        arrow.shooter = mob
        mob.clearActiveItem()
        chargeStartedAt = null
        nextChargeAt = now + if (crossbow) 15 else 0
        if (!crossbow) {
            chargeStartedAt = now
            mob.startUsingItem(EquipmentSlot.HAND)
        }
    }

    private fun stopCharging() {
        if (chargeStartedAt != null) mob.clearActiveItem()
        chargeStartedAt = null
    }

    private fun breakObstruction(target: LivingEntity) {
        val origin = mob.eyeLocation
        val direction = target.eyeLocation.toVector().subtract(origin.toVector())
        if (direction.lengthSquared() < 1e-7) return
        direction.normalize()
        val end = origin.clone().add(direction.clone().multiply(4))
        // rayTraceBlocks can otherwise load chunks. Its complete four-block bounding box must be loaded.
        for (x in (floor(minOf(origin.x, end.x)).toInt() shr 4)..(floor(maxOf(origin.x, end.x)).toInt() shr 4)) {
            for (z in (floor(minOf(origin.z, end.z)).toInt() shr 4)..(floor(maxOf(origin.z, end.z)).toInt() shr 4)) {
                if (!world.isChunkLoaded(x, z)) return
            }
        }
        val block = world.rayTraceBlocks(origin, direction, 4.0, FluidCollisionMode.NEVER, true)?.hitBlock ?: return
        if (block.world.uid == world.uid && !block.type.isAir && canBreak(block)) {
            // Bounded substitute for JCWS's blockbreak projectile; no drops or neighbour physics.
            block.setType(Material.AIR, false)
            mob.swingMainHand()
        }
    }
}

internal fun apocalypseLeapVelocity(delta: Vector, velocity: Vector, vertical: Double): Vector {
    val horizontal = Vector(delta.x, 0.0, delta.z)
    if (horizontal.lengthSquared() > 1e-7) horizontal.normalize().multiply(.4).add(velocity.clone().multiply(.2))
    return Vector(horizontal.x * 2, vertical, horizontal.z * 2)
}
