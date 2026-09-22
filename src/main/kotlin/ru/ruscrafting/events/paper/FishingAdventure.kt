package ru.ruscrafting.events.paper

import net.kyori.adventure.text.Component
import org.bukkit.GameMode
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.Particle
import org.bukkit.Sound
import org.bukkit.entity.Drowned
import org.bukkit.entity.ElderGuardian
import org.bukkit.entity.Entity
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Mob
import org.bukkit.entity.Player
import org.bukkit.entity.TextDisplay
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.event.entity.EntityDamageEvent
import org.bukkit.event.player.PlayerFishEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.event.block.Action
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.persistence.PersistentDataType
import org.bukkit.plugin.Plugin
import ru.ruscrafting.events.config.ArcEventsLocale
import ru.ruscrafting.events.config.EventLocation
import ru.ruscrafting.events.domain.FishingPhase
import ru.ruscrafting.events.domain.FishingProgress
import ru.ruscrafting.events.domain.FishingRules
import java.util.UUID
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * Owns the temporary world entities and Paper event edge for one fishing run.
 * The match/session still owns the clock, escrow, result, and player recovery.
 */
class FishingAdventure(
    private val plugin: Plugin,
    private val locale: ArcEventsLocale,
    private val items: TttItems,
    private val rules: FishingRules,
    private val matchId: UUID,
    private val player: Player,
    private val world: org.bukkit.World,
    private val teleport: (Player, EventLocation) -> Unit,
    private val hurt: (Player, Double) -> Unit,
    private val complete: () -> Unit,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private data class Telegraph(
        val points: List<Location>,
        val impactAtMs: Long,
        val radius: Double,
        val damage: Double,
    )

    private val ownerKey = NamespacedKey(plugin, "fishing_owner")
    private val matchKey = NamespacedKey(plugin, "fishing_match")
    private val stageKey = NamespacedKey(plugin, "fishing_stage")
    private val bossKey = NamespacedKey(plugin, "fishing_boss")
    private val ownerTag = "arcevents-fishing"
    private var progress = FishingProgress(rules)
    private val creatures = linkedMapOf<UUID, LivingEntity>()
    private var marker: TextDisplay? = null
    private var hookId: UUID? = null
    private var telegraph: Telegraph? = null
    private var nextAttackAtMs = 0L
    private var nextHitAcceptedAtMs = 0L
    private var started = false
    private var closed = false
    private var completionSignalled = false

    fun start() {
        if (started || closed) return
        cleanupStaleEntities()
        started = true
        player.inventory.setItem(0, items.fishingRod(player, matchId.toString()))
        player.inventory.setItem(1, items.fishingWeapon(player, matchId.toString(), 0))
        player.inventory.heldItemSlot = 0
        player.updateInventory()
        teleportTo(FishingArenaStage.CAMP)
        message("fishing.start")
    }

    fun tick() {
        if (closed || !started) return
        if (!player.isOnline || player.world.uid != world.uid || player.gameMode == GameMode.SPECTATOR) {
            close()
            return
        }
        enforceStageBounds()
        when (progress.phase) {
            FishingPhase.TRAVEL -> {
                showTravelMarker()
                if (near(FishingArenaGenerator.exit(stage()), rules.stageTravelRadius)) transitionStage()
            }
            FishingPhase.CREATURE, FishingPhase.BOSS -> tickCreature()
            FishingPhase.COMPLETE -> signalCompletion()
            FishingPhase.CASTING, FishingPhase.BITE -> showFishingSpot()
            else -> Unit
        }
    }

    fun close() {
        if (closed) return
        closed = true
        progress = progress.close()
        nextHitAcceptedAtMs = 0L
        hookId?.let { plugin.server.getEntity(it)?.remove() }
        hookId = null
        marker?.remove()
        marker = null
        creatures.values.forEach { runCatching { it.remove() } }
        creatures.clear()
        telegraph = null
    }

    fun handleFish(event: PlayerFishEvent) {
        if (!started || event.player.uniqueId != player.uniqueId || event.player.world.uid != world.uid) return
        val hook = event.hook
        val ownedHook = hookId == hook.uniqueId
        if (closed) {
            cancelFishEvent(event)
            return
        }
        if (event.isCancelled) {
            cancelFishEvent(event)
            if (ownedHook) progress = progress.reelIn()
            return
        }
        if (event.state == PlayerFishEvent.State.FISHING) {
            val rod = when (event.hand) {
                EquipmentSlot.OFF_HAND -> player.inventory.itemInOffHand
                else -> player.inventory.itemInMainHand
            }
            if (items.kind(rod) != EventItemKind.FISHING_ROD || !items.belongsTo(rod, matchId.toString())) {
                cancelFishEvent(event)
                return
            }
            if (progress.phase != FishingPhase.CASTING || hookId != null || !inFishingZone(player.location)) {
                cancelFishEvent(event)
                return
            }
            hookId = hook.uniqueId
            hook.minWaitTime = rules.minBiteTicks
            hook.maxWaitTime = rules.maxBiteTicks
            hook.waitTime = rules.minBiteTicks
            progress = progress.beginCast()
            return
        }
        if (!ownedHook) {
            // A hook can finish after a timeout, close, or a duplicate reel-in.
            // An active expedition must never fall back to vanilla drops, even if
            // the player switched away from the tagged rod before reeling in.
            cancelFishEvent(event)
            return
        }
        when (event.state) {
            PlayerFishEvent.State.BITE -> Unit
            PlayerFishEvent.State.CAUGHT_ENTITY -> {
                cancelFishEvent(event)
                progress = progress.reelIn()
                sendActionBar("fishing.invalid-catch")
            }
            PlayerFishEvent.State.CAUGHT_FISH -> {
                val validWater = inCurrentFishingWater(hook.location)
                cancelFishEvent(event)
                if (!validWater) {
                    progress = progress.reelIn()
                    sendActionBar("fishing.invalid-catch")
                    return
                }
                val next = progress.recordCatch()
                if (next == progress) return
                progress = next
                message(
                    "fishing.catch",
                    mapOf("stage" to locale.text(progress.stage + 1), "catches" to locale.text(progress.totalCatches)),
                )
                if (progress.phase == FishingPhase.BOSS) spawnCreature(boss = true)
                else spawnCreature(boss = false)
            }
            PlayerFishEvent.State.REEL_IN,
            PlayerFishEvent.State.FAILED_ATTEMPT,
            PlayerFishEvent.State.IN_GROUND -> {
                cancelFishEvent(event)
                progress = progress.reelIn()
            }
            else -> Unit
        }
    }

    /** Returns true for every owned entity damage event, including rejected/stale hits. */
    fun handleDamage(event: EntityDamageEvent): Boolean {
        val entity = event.entity
        if (!isOwnedEntity(entity)) return false
        val wasCancelled = event.isCancelled
        event.isCancelled = true
        if (wasCancelled) return true
        val target = entity as? LivingEntity ?: return true
        val current = creatures[target.uniqueId] ?: return true
        if (closed || progress.phase !in setOf(FishingPhase.CREATURE, FishingPhase.BOSS) || target.world.uid != world.uid) return true
        if (target.persistentDataContainer.get(matchKey, PersistentDataType.STRING) != matchId.toString() ||
            target.persistentDataContainer.get(stageKey, PersistentDataType.INTEGER) != progress.stage) return true
        val damageEvent = event as? EntityDamageByEntityEvent ?: return true
        val attacker = damageEvent.damager as? Player ?: return true
        if (attacker.uniqueId != player.uniqueId || !attacker.isOnline || attacker.world.uid != world.uid) return true
        val weapon = attacker.inventory.itemInMainHand
        if (items.kind(weapon) != EventItemKind.FISHING_WEAPON || !items.belongsTo(weapon, matchId.toString())) return true
        val damage = event.finalDamage
        if (!damage.isFinite() || damage <= 0.0) return true
        val now = clock()
        if (now < nextHitAcceptedAtMs) return true
        nextHitAcceptedAtMs = now + 500L
        val next = progress.damageCreature(damage)
        if (next == progress) return true
        progress = next
        if (progress.phase == FishingPhase.COMPLETE || progress.phase == FishingPhase.TRAVEL || progress.phase == FishingPhase.CASTING) {
            creatures.remove(current.uniqueId)
            runCatching { current.remove() }
            telegraph = null
            nextHitAcceptedAtMs = 0L
            if (progress.phase == FishingPhase.COMPLETE) {
                message("fishing.complete")
                signalCompletion()
            } else if (progress.phase == FishingPhase.TRAVEL) {
                message("fishing.travel-unlocked")
                showTravelMarker()
            } else {
                message("fishing.creature-defeated")
            }
        } else {
            current.health = progress.creatureHealth.coerceIn(0.0, maxHealth(current))
            sendActionBar("fishing.creature-hit", mapOf("health" to locale.text("%.1f".format(progress.creatureHealth))))
        }
        return true
    }

    fun handleInteract(event: PlayerInteractEvent): Boolean {
        if (closed || !started || event.player.uniqueId != player.uniqueId || event.player.world.uid != world.uid) return false
        if (progress.phase != FishingPhase.TRAVEL || event.action !in setOf(Action.RIGHT_CLICK_AIR, Action.RIGHT_CLICK_BLOCK)) return false
        if (!near(FishingArenaGenerator.exit(stage()), rules.stageTravelRadius)) return false
        event.isCancelled = true
        transitionStage()
        return true
    }

    fun hud(): Component = localized(
        "fishing.hud",
        mapOf(
            "stage" to locale.text(progress.stage + 1),
            "total" to locale.text(rules.islands),
            "catches" to locale.text(progress.catchesOnStage),
            "needed" to locale.text(rules.catchesPerIsland),
            "health" to locale.text("%.1f".format(progress.creatureHealth)),
            "phase" to locale.render("fishing.phase.${progress.phase.name.lowercase()}", player),
        ),
    )

    private fun spawnCreature(boss: Boolean, initialHealth: Double? = null, announce: Boolean = true) {
        val at = point(FishingArenaGenerator.fightCenter(stage()))
        val entity = if (boss) world.spawn(at, ElderGuardian::class.java) else world.spawn(at, Drowned::class.java)
        entity.isPersistent = false
        entity.removeWhenFarAway = false
        entity.isSilent = true
        entity.isCustomNameVisible = true
        entity.customName(
            localized(
                if (boss) "fishing.boss-name" else "fishing.creature-name",
                mapOf("stage" to locale.text(progress.stage + 1)),
            ),
        )
        (entity as? Mob)?.apply {
            setAI(false)
            isAware = false
            canPickupItems = false
        }
        entity.setGravity(false)
        entity.persistentDataContainer.set(ownerKey, PersistentDataType.STRING, ownerTag)
        entity.persistentDataContainer.set(matchKey, PersistentDataType.STRING, matchId.toString())
        entity.persistentDataContainer.set(stageKey, PersistentDataType.INTEGER, progress.stage)
        entity.persistentDataContainer.set(bossKey, PersistentDataType.BYTE, (if (boss) 1 else 0).toByte())
        val maximumHealth = if (boss) rules.bossHealth else rules.creatureHealth
        entity.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH)?.baseValue = maximumHealth
        entity.health = (initialHealth ?: maximumHealth).coerceIn(1.0, maximumHealth)
        creatures[entity.uniqueId] = entity
        nextAttackAtMs = clock() + rules.attackIntervalTicks * 50L
        nextHitAcceptedAtMs = 0L
        telegraph = null
        if (announce) message(if (boss) "fishing.boss-summoned" else "fishing.creature-summoned")
    }

    private fun tickCreature() {
        val target = creatures.values.firstOrNull()
        if (target == null || !target.isValid) {
            creatures.clear()
            telegraph = null
            spawnCreature(progress.phase == FishingPhase.BOSS, progress.creatureHealth, announce = false)
            sendActionBar("fishing.encounter-restored")
            return
        }
        val now = clock()
        val active = telegraph
        if (active == null && now >= nextAttackAtMs) {
            val boss = progress.phase == FishingPhase.BOSS
            val points = if (boss) listOf(
                player.location.clone(),
                player.location.clone().add(2.0, 0.0, 0.0),
                player.location.clone().add(-2.0, 0.0, 0.0),
            ) else if (stage() == FishingArenaStage.REEF) listOf(
                player.location.clone(),
                player.location.clone().add(1.75, 0.0, 0.0),
                player.location.clone().add(-1.75, 0.0, 0.0),
            ) else listOf(player.location.clone())
            telegraph = Telegraph(
                points = points,
                impactAtMs = now + rules.telegraphTicks * 50L,
                radius = if (boss) 2.2 else if (stage() == FishingArenaStage.REEF) 1.6 else 1.8,
                damage = if (boss) 4.0 else if (stage() == FishingArenaStage.REEF) 2.5 else 2.0,
            )
            sendActionBar("fishing.telegraph", mapOf("radius" to locale.text("%.1f".format(telegraph!!.radius))))
            world.playSound(player.location, Sound.BLOCK_NOTE_BLOCK_HAT, 0.7f, if (boss) 0.7f else 1.5f)
        }
        val warning = telegraph ?: return
        warning.points.forEach { point ->
            if (point.world.uid == world.uid) {
                repeat(16) { index ->
                    val angle = index * Math.PI / 8.0
                    world.spawnParticle(
                        Particle.END_ROD,
                        point.clone().add(cos(angle) * warning.radius, 0.12, sin(angle) * warning.radius),
                        1,
                        0.0,
                        0.0,
                        0.0,
                        0.0,
                    )
                }
            }
        }
        if (now < warning.impactAtMs) return
        warning.points.forEach { point ->
            if (point.world.uid == world.uid) world.spawnParticle(Particle.EXPLOSION, point, 1, 0.0, 0.0, 0.0, 0.0)
        }
        world.playSound(player.location, Sound.ENTITY_GENERIC_EXPLODE, 0.65f, 1.1f)
        if (player.world.uid == world.uid && warning.points.any { it.distanceSquared(player.location) <= warning.radius * warning.radius }) {
            hurt(player, warning.damage)
        }
        telegraph = null
        nextAttackAtMs = now + rules.attackIntervalTicks * 50L
    }

    private fun transitionStage() {
        if (progress.phase != FishingPhase.TRAVEL || progress.isFinalStage) return
        val next = progress.travelToNextStage()
        if (next == progress) return
        progress = next
        marker?.remove()
        marker = null
        telegraph = null
        nextAttackAtMs = 0L
        nextHitAcceptedAtMs = 0L
        player.inventory.setItem(1, items.fishingWeapon(player, matchId.toString(), progress.stage))
        player.inventory.heldItemSlot = 0
        player.updateInventory()
        teleportTo(stage())
        message("fishing.travel-started", mapOf("stage" to locale.text(progress.stage + 1)))
    }

    private fun showFishingSpot() {
        val zone = FishingArenaGenerator.fishingZone(stage())
        for (x in listOf(zone.minX + 0.5, zone.maxX + 0.5)) {
            for (z in listOf(zone.minZ + 0.5, zone.maxZ + 0.5)) {
                world.spawnParticle(Particle.END_ROD,
                    Location(world, x, zone.waterSurfaceY + 1.1, z), 1, 0.0, 0.0, 0.0, 0.0)
            }
        }
    }

    private fun showTravelMarker() {
        if (marker?.isValid == true) return
        val location = point(FishingArenaGenerator.exit(stage())).add(0.0, 1.0, 0.0)
        marker = world.spawn(location, TextDisplay::class.java) { display ->
            display.text(localized("fishing.travel-marker"))
            display.isPersistent = false
            runCatching { display.billboard = org.bukkit.entity.Display.Billboard.CENTER }
            runCatching { display.isShadowed = true }
            display.persistentDataContainer.set(ownerKey, PersistentDataType.STRING, ownerTag)
            display.persistentDataContainer.set(matchKey, PersistentDataType.STRING, matchId.toString())
            display.persistentDataContainer.set(stageKey, PersistentDataType.INTEGER, progress.stage)
        }
    }

    private fun enforceStageBounds() {
        val stage = stage()
        val location = player.location
        val xMin = stage.centerX - 19.0
        val xMax = stage.centerX + 19.0
        if (location.x !in xMin..xMax || location.z !in -14.0..21.0 || location.y !in 53.0..101.0) {
            teleportTo(stage)
        }
    }

    private fun inFishingZone(location: Location): Boolean {
        val zone = FishingArenaGenerator.fishingZone(stage())
        return location.x in (zone.minX - rules.fishingRadius)..(zone.maxX + rules.fishingRadius) &&
            location.z in (zone.minZ - rules.fishingRadius)..(zone.maxZ + rules.fishingRadius) &&
            abs(location.y - zone.waterSurfaceY) <= rules.fishingRadius + 4.0
    }

    private fun inCurrentFishingWater(location: Location): Boolean {
        val zone = FishingArenaGenerator.fishingZone(stage())
        if (location.world.uid != world.uid) return false
        if (location.x !in zone.minX.toDouble()..(zone.maxX + 1).toDouble() ||
            location.z !in zone.minZ.toDouble()..(zone.maxZ + 1).toDouble()) return false
        if (location.blockY !in (zone.waterSurfaceY - zone.minimumDepth)..(zone.waterSurfaceY + 1)) return false
        return location.block.type == Material.WATER || location.block.getRelative(0, -1, 0).type == Material.WATER
    }

    private fun near(point: FishingArenaPoint, radius: Double): Boolean {
        val target = point(point)
        return player.location.distanceSquared(target) <= radius * radius
    }

    private fun isOwnedEntity(entity: Entity): Boolean =
        entity.persistentDataContainer.get(ownerKey, PersistentDataType.STRING) == ownerTag

    private fun cleanupStaleEntities() {
        val center = Location(world, 48.0, FishingArenaGenerator.SPAWN_Y.toDouble(), 0.0)
        world.getNearbyEntities(center, 76.0, 48.0, 40.0).filter(::isOwnedEntity).forEach { it.remove() }
    }

    private fun stage(): FishingArenaStage = FishingArenaStage.entries[progress.stage]

    private fun point(point: FishingArenaPoint): Location =
        Location(world, point.x, point.y, point.z, point.yaw, point.pitch)

    private fun teleportTo(stage: FishingArenaStage) {
        val destination = FishingArenaGenerator.spawn(stage)
        teleport(player, EventLocation(world.name, destination.x, destination.y, destination.z, destination.yaw, destination.pitch))
    }

    private fun maxHealth(entity: LivingEntity): Double =
        entity.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH)?.value ?: 20.0

    private fun signalCompletion() {
        if (completionSignalled || closed) return
        completionSignalled = true
        complete()
    }

    private fun localized(key: String, values: Map<String, Component> = emptyMap()): Component =
        locale.render(key, player, values)

    private fun message(key: String, values: Map<String, Component> = emptyMap()) {
        if (player.isOnline) player.sendEventMessage(localized(key, values))
    }

    private fun sendActionBar(key: String, values: Map<String, Component> = emptyMap()) {
        if (player.isOnline) player.sendEventActionBar(localized(key, values))
    }

    private fun cancelFishEvent(event: PlayerFishEvent) {
        event.isCancelled = true
        event.expToDrop = 0
        if (event.state == PlayerFishEvent.State.CAUGHT_FISH || event.state == PlayerFishEvent.State.CAUGHT_ENTITY) {
            (event.caught as? org.bukkit.entity.Item)?.remove()
        }
        event.hook.remove()
        if (hookId == event.hook.uniqueId) hookId = null
    }
}
