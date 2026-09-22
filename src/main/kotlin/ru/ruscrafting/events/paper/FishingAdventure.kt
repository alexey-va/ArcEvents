package ru.ruscrafting.events.paper

import net.kyori.adventure.text.Component
import net.kyori.adventure.util.TriState
import org.bukkit.GameMode
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.Particle
import org.bukkit.Sound
import org.bukkit.attribute.Attribute
import org.bukkit.entity.Cod
import org.bukkit.entity.Drowned
import org.bukkit.entity.ElderGuardian
import org.bukkit.entity.Entity
import org.bukkit.entity.Guardian
import org.bukkit.entity.Item
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Mob
import org.bukkit.entity.Phantom
import org.bukkit.entity.Player
import org.bukkit.entity.PufferFish
import org.bukkit.entity.Salmon
import org.bukkit.entity.Silverfish
import org.bukkit.entity.Spider
import org.bukkit.entity.TextDisplay
import org.bukkit.entity.TropicalFish
import org.bukkit.entity.Villager
import org.bukkit.event.block.Action
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.event.entity.EntityDamageEvent
import org.bukkit.event.player.PlayerFishEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType
import org.bukkit.plugin.Plugin
import org.bukkit.util.Vector
import ru.arc.core.ScheduledTask
import ru.arc.core.Tasks
import ru.ruscrafting.events.config.ArcEventsLocale
import ru.ruscrafting.events.config.EventLocation
import ru.ruscrafting.events.domain.FishingCatch
import ru.ruscrafting.events.domain.FishingGear
import ru.ruscrafting.events.domain.FishingOffer
import ru.ruscrafting.events.domain.FishingOfferKind
import ru.ruscrafting.events.domain.FishingPhase
import ru.ruscrafting.events.domain.FishingProgress
import ru.ruscrafting.events.domain.FishingRules
import java.util.EnumMap
import java.util.UUID
import java.util.concurrent.ThreadLocalRandom
import kotlin.math.ceil
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/** Owns the temporary entities and Paper event edge for one personal fishing run. */
class FishingAdventure(
    private val plugin: Plugin,
    private val locale: ArcEventsLocale,
    private val items: TttItems,
    private val firearms: TttFirearms,
    private val rules: FishingRules,
    private val matchId: UUID,
    private val player: Player,
    private val world: org.bukkit.World,
    private val teleport: (Player, EventLocation) -> Unit,
    private val hurt: (Player, Double) -> Unit,
    private val complete: () -> Unit,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private enum class Pattern { CHARGE, LEAP, POISON_RINGS, DIVE, MULTI_RING }
    private data class Telegraph(
        val pattern: Pattern,
        val points: List<Location>,
        val impactAtMs: Long,
        val radius: Double,
        val damage: Double,
    )
    private data class Fuse(val id: UUID, val stage: Int, val detonatesAtMs: Long)

    private val ownerKey = NamespacedKey(plugin, "fishing_owner")
    private val matchKey = NamespacedKey(plugin, "fishing_match")
    private val stageKey = NamespacedKey(plugin, "fishing_stage")
    private val bossKey = NamespacedKey(plugin, "fishing_boss")
    private val ownerTag = "arcevents-fishing"
    private val matchIdString = matchId.toString()
    private var progress = FishingProgress(rules)
    private val creatures = linkedMapOf<UUID, LivingEntity>()
    private val fuses = linkedMapOf<UUID, Fuse>()
    private val firearmItems = EnumMap<FishingGear, ItemStack>(FishingGear::class.java)
    private var appliedGear: FishingGear? = null
    private var travelMarker: TextDisplay? = null
    private var traderMarker: TextDisplay? = null
    private var traderNpc: Villager? = null
    private var hookId: UUID? = null
    private var submergedSinceMs = 0L
    private var telegraph: Telegraph? = null
    private var combatTask: ScheduledTask? = null
    private var nextAttackAtMs = 0L
    private var stunnedUntilMs = 0L
    private var movementEndsAtMs = 0L
    private var nextHitAcceptedAtMs = 0L
    private var started = false
    private var closed = false
    private var completionSignalled = false

    fun snapshot(): FishingProgress = progress

    fun belongsTo(candidate: Player): Boolean =
        started && !closed && candidate.uniqueId == player.uniqueId && candidate.world.uid == world.uid

    /** The exact state and location gate shared by the ledger and native trader dialog. */
    fun canTrade(candidate: Player): Boolean {
        if (!belongsTo(candidate) || !candidate.isOnline || candidate.gameMode == GameMode.SPECTATOR) return false
        if (candidate.world.uid != world.uid || progress.phase !in setOf(FishingPhase.CASTING, FishingPhase.TROPHY)) return false
        return candidate.location.distanceSquared(point(FishingArenaGenerator.trader(stage()))) <= 25.0
    }

    fun start() {
        if (started || closed) return
        cleanupStaleEntities()
        started = true
        syncLoadout()
        player.inventory.heldItemSlot = 0
        player.updateInventory()
        teleportTo(FishingArenaStage.CAMP)
        combatTask = Tasks.scheduler.runTimer(1L, 1L) { tickCombat() }
        message("fishing.start")
    }

    /** Session lifecycle/bounds tick; combat and fuses use the owned 1-tick task. */
    fun tick() {
        if (closed || !started) return
        if (!player.isOnline || player.world.uid != world.uid || player.gameMode == GameMode.SPECTATOR) {
            close()
            return
        }
        enforceStageBounds()
        showTraderNpc()
        if (!firearms.enabled && progress.equippedGear.firearmId != null && player.inventory.getItem(2)?.isEmpty == false) {
            syncLoadout()
        }
        when (progress.phase) {
            FishingPhase.TRAVEL -> {
                traderMarker?.remove()
                traderMarker = null
                showTravelMarker()
                if (near(FishingArenaGenerator.exit(stage()), rules.stageTravelRadius)) transitionStage()
            }
            FishingPhase.TROPHY, FishingPhase.CASTING -> {
                travelMarker?.remove()
                travelMarker = null
                showTraderMarker()
                showFishingSpot()
            }
            else -> {
                travelMarker?.remove()
                travelMarker = null
                traderMarker?.remove()
                traderMarker = null
            }
        }
        if (progress.phase in setOf(FishingPhase.CREATURE, FishingPhase.BOSS)) ensureCreature()
        if (progress.phase == FishingPhase.COMPLETE) signalCompletion()
    }

    fun close() {
        if (closed) return
        closed = true
        combatTask?.cancel()
        combatTask = null
        progress = progress.close()
        nextHitAcceptedAtMs = 0L
        hookId?.let { plugin.server.getEntity(it)?.remove() }
        hookId = null
        cleanupMatchEntities()
        creatures.clear()
        fuses.clear()
        telegraph = null
        travelMarker = null
        traderMarker = null
        traderNpc = null
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
            val rod = if (event.hand == EquipmentSlot.OFF_HAND) player.inventory.itemInOffHand else player.inventory.itemInMainHand
            if (items.kind(rod) != EventItemKind.FISHING_ROD || !items.belongsTo(rod, matchIdString) ||
                progress.phase != FishingPhase.CASTING || progress.bag.size >= FishingRules.MAX_BAG ||
                hookId != null || !inFishingZone(player.location)
            ) {
                cancelFishEvent(event)
                if (progress.bag.size >= FishingRules.MAX_BAG) sendActionBar("fishing.bag-full")
                return
            }
            hookId = hook.uniqueId
            val waitScale = when (progress.rodLevel) { 0 -> 1.0; 1 -> 0.8; else -> 0.6 }
            val minWait = ceil(rules.minBiteTicks * waitScale).toInt().coerceAtLeast(1)
            val maxWait = ceil(rules.maxBiteTicks * waitScale).toInt().coerceAtLeast(minWait)
            hook.minWaitTime = minWait
            hook.maxWaitTime = maxWait
            hook.waitTime = ThreadLocalRandom.current().nextInt(minWait, maxWait + 1)
            progress = progress.beginCast()
            return
        }
        if (!ownedHook) {
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
                landCatch()
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

    /** Cancels all owned-creature damage, then applies catalog damage and cooldowns only. */
    fun handleDamage(event: EntityDamageEvent): Boolean {
        val target = event.entity
        if (!isOwnedEntity(target)) return false
        val wasCancelled = event.isCancelled
        event.isCancelled = true
        if (closed || wasCancelled) return true
        val creature = target as? LivingEntity ?: return true
        if (creatures[creature.uniqueId] !== creature || !isCurrentEntity(creature) ||
            progress.phase !in setOf(FishingPhase.CREATURE, FishingPhase.BOSS)
        ) return true
        val hit = event as? EntityDamageByEntityEvent ?: return true
        val attacker = hit.damager as? Player ?: return true
        if (!owns(attacker) || attacker.inventory.heldItemSlot != 1) return true
        val item = attacker.inventory.getItem(1)
        if (items.kind(item) != EventItemKind.FISHING_WEAPON || !items.belongsTo(item, matchIdString)) return true
        val gear = meleeGear(item) ?: return true
        val expectedGear = if (progress.equippedGear.firearmId == null) progress.equippedGear else bestMelee()
        if (gear != expectedGear || gear !in progress.ownedGear) return true
        val now = clock()
        if (now < nextHitAcceptedAtMs) return true
        nextHitAcceptedAtMs = now + gear.cooldownMillis
        damageEncounter(gear.meleeDamage, creature)
        return true
    }

    /** Firearm ray bridge: current owner/match/stage/entity and equipped slot only. */
    fun isFirearmTarget(shooter: Player, entity: Entity, expectedMatchId: String): Boolean {
        if (!firearms.enabled || expectedMatchId != matchIdString || !owns(shooter) ||
            shooter.inventory.heldItemSlot != 2 || progress.phase !in setOf(FishingPhase.CREATURE, FishingPhase.BOSS)
        ) return false
        val gear = progress.equippedGear
        val firearmId = gear.firearmId ?: return false
        val state = firearms.state(shooter.inventory.getItem(2)) ?: return false
        return state.id == firearmId && state.matchId == matchIdString &&
            creatures[entity.uniqueId] === entity && isCurrentEntity(entity)
    }

    /** Applies shared firearm damage without the melee click cooldown. */
    fun hitByFirearm(shooter: Player, target: Entity, damage: Double, expectedMatchId: String): Boolean {
        if (!isFirearmTarget(shooter, target, expectedMatchId) || !damage.isFinite() || damage <= 0.0) return false
        val creature = target as? LivingEntity ?: return false
        val before = progress
        damageEncounter(damage, creature)
        return progress !== before
    }

    fun handleInteract(event: PlayerInteractEvent): Boolean {
        if (closed || !started || event.player.uniqueId != player.uniqueId || event.player.world.uid != world.uid) return false
        if (event.hand != EquipmentSlot.HAND || event.action !in setOf(Action.RIGHT_CLICK_AIR, Action.RIGHT_CLICK_BLOCK)) return false
        if (progress.phase == FishingPhase.TRAVEL && near(FishingArenaGenerator.exit(stage()), rules.stageTravelRadius)) {
            event.isCancelled = true
            transitionStage()
            return true
        }
        val stack = player.inventory.getItem(3)
        if (player.inventory.heldItemSlot != 3 || items.kind(stack) != EventItemKind.FISHING_DYNAMITE) return false
        event.isCancelled = true
        if (!owns(player) || !dynamiteInventoryConsistent() || progress.dynamite <= 0) {
            sendActionBar("fishing.dynamite-empty")
            return true
        }
        if (fuses.size >= 2) {
            sendActionBar("fishing.dynamite-busy")
            return true
        }
        val next = progress.consumeDynamite()
        if (next === progress) {
            sendActionBar("fishing.dynamite-empty")
            return true
        }
        val location = player.eyeLocation.clone().add(player.location.direction.normalize().multiply(0.6))
        if (!inStageBounds(location)) return true
        progress = next
        syncLoadout()
        val dropped = world.dropItem(location, items.fishingDynamite(player, matchIdString, 1))
        dropped.setCanPlayerPickup(false)
        dropped.setCanMobPickup(false)
        dropped.owner = player.uniqueId
        dropped.setPickupDelay(Int.MAX_VALUE)
        dropped.isPersistent = false
        dropped.velocity = player.location.direction.normalize().multiply(0.85).add(Vector(0.0, 0.2, 0.0))
        tag(dropped)
        fuses[dropped.uniqueId] = Fuse(dropped.uniqueId, progress.stage, clock() + 2_500L)
        message("fishing.dynamite-thrown")
        return true
    }

    fun isTraderNpc(entity: Entity): Boolean =
        traderNpc === entity && isCurrentEntity(entity)

    fun feedHeldCatch(candidate: Player, entity: Entity): Boolean {
        if (!isTraderNpc(entity) || !canTrade(candidate) || candidate.inventory.heldItemSlot != 4) return false
        val item = candidate.inventory.getItem(4)
        if (items.kind(item) != EventItemKind.FISHING_CATCH_BAG || !items.belongsTo(item, matchIdString) ||
            item?.amount != progress.bag.size
        ) return false
        return trade(candidate, progress, "feed")
    }

    /** Snapshot-checked atomic transactions used by the native merchant dialog. */
    fun trade(candidate: Player, expected: FishingProgress, action: String): Boolean {
        if (!canTrade(candidate) || progress !== expected) return false
        val before = progress
        val next: FishingProgress
        when {
            action == "feed" -> next = before.feedCatch()
            action == "eat" -> {
                val maxHealth = player.getAttribute(Attribute.MAX_HEALTH)?.value ?: 20.0
                if (player.health >= maxHealth && player.foodLevel >= 20) {
                    sendActionBar("fishing.food-full")
                    return false
                }
                next = before.eatCatch()
                if (next === before) return false
                player.health = (player.health + 4.0).coerceAtMost(maxHealth)
                player.foodLevel = (player.foodLevel + 4).coerceAtMost(20)
            }
            action == "bait" -> next = before.claimBossBait()
            action == "trophy" -> next = before.handInTrophy()
            action.startsWith("equip:") -> {
                val gear = action.substringAfter(':').let { runCatching { FishingGear.valueOf(it) }.getOrNull() } ?: return false
                if (gear.firearmId != null && !firearms.enabled) return false
                next = before.equip(gear)
            }
            action.startsWith("buy:") -> {
                val offer = action.substringAfter(':').let { runCatching { FishingOffer.valueOf(it) }.getOrNull() } ?: return false
                if (offer.gear?.firearmId != null && !firearms.enabled) return false
                if (offer == FishingOffer.AMMO && !firearms.enabled) return false
                if (offer == FishingOffer.AMMO && ammoDestination() == null) {
                    sendActionBar("fishing.inventory-full")
                    return false
                }
                if (offer == FishingOffer.DYNAMITE && !dynamiteInventoryConsistent()) return false
                next = before.buy(offer)
                if (next === before) return false
                if (offer.kind == FishingOfferKind.GEAR) {
                    val bought = requireNotNull(offer.gear)
                    if (bought.firearmId != null && bought !in firearmItems) {
                        firearmItems[bought] = firearms.firearmItem(bought.firearmId, player, matchIdString).clone()
                    }
                }
                if (offer == FishingOffer.AMMO) {
                    val slot = requireNotNull(ammoDestination())
                    player.inventory.setItem(slot, firearms.ammunition(player, matchIdString, FishingRules.AMMO_PER_PURCHASE))
                }
            }
            else -> return false
        }
        if (next === before) return false
        progress = next
        syncLoadout()
        if (action == "feed") sendActionBar("fishing.fed", mapOf("value" to locale.text(next.coins - before.coins)))
        if (action == "bait") sendActionBar("fishing.bait-ready")
        if (action == "trophy") {
            if (progress.phase == FishingPhase.COMPLETE) {
                message("fishing.complete")
                signalCompletion()
            } else if (progress.phase == FishingPhase.TRAVEL) {
                message("fishing.travel-unlocked")
                showTravelMarker()
            }
        }
        return true
    }

    fun hud(): Component = localized(
        "fishing.hud",
        mapOf(
            "stage" to locale.text(progress.stage + 1),
            "total" to locale.text(rules.islands),
            "catches" to locale.text(progress.catchesOnStage),
            "needed" to locale.text(rules.catchesPerIsland),
            "coins" to locale.text(progress.coins),
            "bag" to locale.text(progress.bag.size),
            "phase" to locale.render("fishing.phase.${progress.phase.name.lowercase()}", player),
        ),
    )

    private fun landCatch() {
        val next = progress.recordCatch()
        if (next === progress) {
            sendActionBar(if (progress.bag.size >= FishingRules.MAX_BAG) "fishing.bag-full" else "fishing.invalid-catch")
            return
        }
        progress = next
        val catch = requireNotNull(progress.encounter)
        val name = localized("fishing.species.${catch.species}")
        val shownName = if (catch.rare) Component.empty().append(localized("fishing.rare-prefix")).append(name) else name
        sendActionBar("fishing.catch", mapOf("catches" to shownName))
        syncLoadout()
        spawnCreature(catch, announce = false)
        if (catch.boss) message("fishing.boss-summoned")
    }

    private fun spawnCreature(catch: FishingCatch, announce: Boolean = true) {
        val spawn = point(FishingArenaGenerator.catchLanding(stage()))
        val type: Class<out Mob> = when (catch.species) {
            "clam", "rockfish" -> Silverfish::class.java
            "ash_carp" -> Cod::class.java
            "shore_crab", "spider_crab" -> Spider::class.java
            "shrimp", "tuna", "lava_salmon" -> Salmon::class.java
            "reef_perch", "needlefish", "mackerel" -> Cod::class.java
            "reef_eel" -> Drowned::class.java
            "reef_piranha", "bowlfish", "pufferfish" -> PufferFish::class.java
            "seahorse", "ember_trout" -> TropicalFish::class.java
            "giant_piranha" -> Guardian::class.java
            "albatross" -> Phantom::class.java
            "lava_whale" -> ElderGuardian::class.java
            else -> Cod::class.java
        }
        val entity = world.spawn(spawn, type) { mob ->
            // Arena worlds stay peaceful; only this explicitly spawned encounter may persist.
            mob.setDespawnInPeacefulOverride(TriState.FALSE)
        }
        entity.isPersistent = true
        entity.isSilent = true
        entity.isCustomNameVisible = true
        entity.isGlowing = true
        val speciesName = localized("fishing.species.${catch.species}")
        entity.customName(
            if (catch.rare) Component.empty().append(localized("fishing.rare-prefix")).append(speciesName)
            else speciesName,
        )
        entity.apply { setAI(false); isAware = false }
        entity.setGravity(false)
        entity.persistentDataContainer.set(ownerKey, PersistentDataType.STRING, ownerTag)
        entity.persistentDataContainer.set(matchKey, PersistentDataType.STRING, matchIdString)
        entity.persistentDataContainer.set(stageKey, PersistentDataType.INTEGER, progress.stage)
        entity.persistentDataContainer.set(bossKey, PersistentDataType.BYTE, (if (catch.boss) 1 else 0).toByte())
        val maximum = catch.maxHealth
        entity.getAttribute(Attribute.MAX_HEALTH)?.baseValue = maximum.coerceIn(1.0, 1_024.0)
        // The native attribute has a server-defined cap; logical encounter health remains authoritative.
        val visibleMaximum = entity.getAttribute(Attribute.MAX_HEALTH)?.value ?: 20.0
        entity.health = (progress.creatureHealth.takeIf { it > 0.0 } ?: maximum).coerceIn(1.0, visibleMaximum)
        creatures.clear()
        creatures[entity.uniqueId] = entity
        telegraph = null
        nextAttackAtMs = clock() + rules.attackIntervalTicks * 50L
        stunnedUntilMs = 0L
        movementEndsAtMs = 0L
        nextHitAcceptedAtMs = 0L
        if (announce) {
            if (catch.boss) message("fishing.boss-summoned") else sendActionBar("fishing.creature-summoned")
        }
    }

    private fun ensureCreature() {
        val current = creatures.values.firstOrNull()
        if (current != null && current.isValid && isCurrentEntity(current)) return
        creatures.clear()
        telegraph = null
        val catch = progress.encounter ?: return
        spawnCreature(catch, announce = false)
        // Restoration is automatic. Repeating a notice every tick hid the combat instructions.
    }

    private fun tickCombat() {
        if (closed || !started || !player.isOnline || player.world.uid != world.uid) return
        val now = clock()
        resolveFuses(now)
        if (progress.phase !in setOf(FishingPhase.CREATURE, FishingPhase.BOSS)) return
        ensureCreature()
        val creature = creatures.values.firstOrNull() ?: return
        if (!inStageBounds(creature.location) ||
            creature.location.y !in (FishingArenaGenerator.WATER_SURFACE_Y - 1.0)..(FishingArenaGenerator.SPAWN_Y + 6.0) ||
            movementEndsAtMs > 0L && now >= movementEndsAtMs
        ) settleCreature(creature)
        if (now < stunnedUntilMs) return
        val active = telegraph
        if (active == null && now >= nextAttackAtMs) {
            telegraph = buildTelegraph(creature, now)
        }
        val warning = telegraph ?: return
        drawTelegraph(warning)
        if (now < warning.impactAtMs) return
        resolveAttack(warning, creature)
        telegraph = null
        val delay = if (warning.pattern == Pattern.CHARGE && progress.phase == FishingPhase.BOSS) 1_400L else rules.attackIntervalTicks * 50L
        nextAttackAtMs = now + delay
        if (warning.pattern == Pattern.CHARGE && progress.phase == FishingPhase.BOSS) {
            stunnedUntilMs = now + 1_200L
            creature.isGlowing = true
            sendActionBar("fishing.braced")
        }
    }

    private fun buildTelegraph(creature: LivingEntity, now: Long): Telegraph {
        val target = grounded(player.location)
        val species = progress.encounter?.species.orEmpty()
        val pattern = when (species) {
            "spider_crab", "shore_crab" -> Pattern.CHARGE
            "reef_piranha", "giant_piranha" -> Pattern.LEAP
            "bowlfish", "pufferfish" -> Pattern.POISON_RINGS
            "albatross" -> Pattern.DIVE
            "lava_whale" -> Pattern.MULTI_RING
            else -> Pattern.CHARGE
        }
        val lead = player.velocity.clone().setY(0.0).multiply(5.0)
        val center = grounded(target.clone().add(lead))
        val points = when (pattern) {
            Pattern.POISON_RINGS, Pattern.MULTI_RING -> listOf(
                center,
                grounded(center.clone().add(2.7, 0.0, 0.0)),
                grounded(center.clone().add(-2.7, 0.0, 0.0)),
            )
            else -> listOf(center)
        }
        val radius = when (pattern) {
            Pattern.POISON_RINGS, Pattern.MULTI_RING -> 1.55
            Pattern.LEAP, Pattern.DIVE -> 1.85
            Pattern.CHARGE -> 1.45
        }
        val damage = if (progress.phase == FishingPhase.BOSS) 4.0 else 2.0
        if (pattern == Pattern.DIVE) creature.teleport(creature.location.clone().add(0.0, 4.0, 0.0))
        sendActionBar("fishing.telegraph", mapOf("radius" to locale.text("%.1f".format(radius))))
        world.playSound(player.location, Sound.BLOCK_NOTE_BLOCK_HAT, 0.7f, if (progress.phase == FishingPhase.BOSS) 0.7f else 1.4f)
        return Telegraph(pattern, points, now + rules.telegraphTicks * 50L, radius, damage)
    }

    private fun drawTelegraph(warning: Telegraph) {
        val particle = if (progress.phase == FishingPhase.BOSS) Particle.SOUL_FIRE_FLAME else Particle.END_ROD
        warning.points.forEach { center ->
            repeat(20) { index ->
                val angle = index * Math.PI / 10.0
                world.spawnParticle(particle, center.clone().add(cos(angle) * warning.radius, 0.12, sin(angle) * warning.radius),
                    1, 0.0, 0.0, 0.0, 0.0)
            }
        }
    }

    private fun resolveAttack(warning: Telegraph, creature: LivingEntity) {
        warning.points.forEach { point -> world.spawnParticle(Particle.EXPLOSION, point, 1, 0.0, 0.0, 0.0, 0.0) }
        world.playSound(player.location, Sound.ENTITY_GENERIC_EXPLODE, 0.55f, 1.15f)
        if (playerInCurrentStage() && warning.points.any { it.distanceSquared(player.location) <= warning.radius * warning.radius }) {
            hurt(player, warning.damage)
        }
        val destination = warning.points.first()
        val direction = destination.toVector().subtract(creature.location.toVector()).setY(0.0)
        when (warning.pattern) {
            Pattern.CHARGE -> if (direction.lengthSquared() > 0.01) {
                moveCreature(creature, direction.normalize().multiply(0.5), 650L, gravity = false)
            } else settleCreature(creature)
            Pattern.LEAP -> if (direction.lengthSquared() > 0.01) {
                moveCreature(creature, direction.normalize().multiply(0.35).setY(0.35), 900L, gravity = true)
            } else settleCreature(creature)
            Pattern.DIVE -> {
                val dive = destination.toVector().subtract(creature.location.toVector())
                val velocity = if (dive.lengthSquared() > 0.01) dive.normalize().multiply(0.9) else Vector(0.0, -0.9, 0.0)
                moveCreature(creature, velocity, 1_100L, gravity = true)
            }
            Pattern.POISON_RINGS, Pattern.MULTI_RING -> Unit
        }
    }

    private fun moveCreature(creature: LivingEntity, velocity: Vector, durationMs: Long, gravity: Boolean) {
        creature.setGravity(gravity)
        creature.velocity = velocity
        movementEndsAtMs = clock() + durationMs
    }

    /** Landing is bounded to the player's nearby stage floor so leap/dive bosses remain hittable. */
    private fun settleCreature(creature: LivingEntity) {
        val nearPlayer = grounded(player.location)
        val offset = creature.location.toVector().subtract(nearPlayer.toVector()).setY(0.0)
        if (offset.lengthSquared() > 6.25) offset.normalize().multiply(2.5)
        var landing = grounded(nearPlayer.clone().add(offset))
        if (landing.distanceSquared(player.location) > 16.0) landing = grounded(player.location)
        creature.velocity = Vector()
        creature.setGravity(false)
        creature.teleport(landing)
        movementEndsAtMs = 0L
    }

    private fun resolveFuses(now: Long) {
        fuses.values.toList().forEach { fuse ->
            val item = plugin.server.getEntity(fuse.id) as? Item
            if (item == null || !item.isValid || fuse.stage != progress.stage || now < fuse.detonatesAtMs) return@forEach
            fuses.remove(fuse.id)
            val location = item.location.clone()
            item.remove()
            if (!inStageBounds(location) || !isTaggedForThisMatch(item)) return@forEach
            world.spawnParticle(Particle.EXPLOSION, location, 1, 0.0, 0.0, 0.0, 0.0)
            world.spawnParticle(Particle.FLAME, location, 14, 0.35, 0.25, 0.35, 0.02)
            world.playSound(location, Sound.ENTITY_GENERIC_EXPLODE, 0.8f, 0.9f)
            val radiusSquared = 16.0
            if (playerInCurrentStage() && player.location.distanceSquared(location) <= radiusSquared) hurt(player, 6.0)
            if (progress.phase == FishingPhase.CASTING && inCurrentFishingWater(location)) {
                val bite = progress.beginCast().recordCatch()
                if (bite !== progress) {
                    progress = bite
                    val catch = requireNotNull(progress.encounter)
                    sendActionBar("fishing.catch", mapOf("catches" to localized("fishing.species.${catch.species}")))
                    syncLoadout()
                    spawnCreature(catch, announce = false)
                    if (catch.boss) message("fishing.boss-summoned")
                    creatures.values.firstOrNull()?.let { damageEncounter(rules.dynamiteDamage, it) }
                }
            }
            val target = creatures.values.firstOrNull()
            if (target != null && progress.phase in setOf(FishingPhase.CREATURE, FishingPhase.BOSS) &&
                target.location.distanceSquared(location) <= radiusSquared
            ) damageEncounter(rules.dynamiteDamage, target)
        }
        fuses.entries.removeIf { plugin.server.getEntity(it.key)?.isValid != true }
    }

    private fun damageEncounter(amount: Double, target: LivingEntity) {
        if (creatures[target.uniqueId] !== target || !isCurrentEntity(target) ||
            progress.phase !in setOf(FishingPhase.CREATURE, FishingPhase.BOSS)
        ) return
        val next = progress.damageCreature(amount)
        if (next === progress) return
        val previousPhase = progress.phase
        progress = next
        syncLoadout()
        if (progress.phase == FishingPhase.CASTING || progress.phase == FishingPhase.TROPHY ||
            progress.phase == FishingPhase.TRAVEL || progress.phase == FishingPhase.COMPLETE
        ) {
            creatures.remove(target.uniqueId)
            target.remove()
            telegraph = null
            nextHitAcceptedAtMs = 0L
            if (progress.phase == FishingPhase.TROPHY) {
                message("fishing.boss-defeated")
                showTraderMarker()
            } else {
                sendActionBar(if (progress.catchesOnStage >= rules.catchesPerIsland) "fishing.quest-ready" else "fishing.creature-defeated")
            }
            if (progress.phase == FishingPhase.COMPLETE) signalCompletion()
        } else {
            val visibleMax = target.getAttribute(Attribute.MAX_HEALTH)?.value ?: 20.0
            target.health = progress.creatureHealth.coerceIn(1.0, visibleMax)
            sendActionBar("fishing.creature-hit", mapOf("health" to locale.text("%.1f".format(progress.creatureHealth))))
        }
        if (previousPhase == FishingPhase.BOSS && progress.phase == FishingPhase.TROPHY) {
            telegraph = null
            nextAttackAtMs = 0L
        }
    }

    private fun transitionStage() {
        if (progress.phase != FishingPhase.TRAVEL || progress.isFinalStage) return
        val next = progress.travelToNextStage()
        if (next === progress) return
        progress = next
        cleanupMatchEntities()
        creatures.clear()
        fuses.clear()
        travelMarker = null
        traderMarker = null
        traderNpc = null
        telegraph = null
        nextAttackAtMs = 0L
        stunnedUntilMs = 0L
        movementEndsAtMs = 0L
        nextHitAcceptedAtMs = 0L
        syncLoadout()
        player.inventory.heldItemSlot = 0
        player.updateInventory()
        teleportTo(stage())
        message("fishing.travel-started", mapOf("stage" to locale.text(progress.stage + 1)))
    }

    private fun syncLoadout() {
        if (!started && closed) return
        val previous = appliedGear
        if (previous?.firearmId != null) {
            val live = firearms.state(player.inventory.getItem(2))
            if (live?.id == previous.firearmId && live.matchId == matchIdString) {
                firearmItems[previous] = player.inventory.getItem(2)!!.clone()
            }
        }
        player.inventory.setItem(0, items.fishingRod(player, matchIdString, progress.rodLevel))
        val melee = if (progress.equippedGear.firearmId == null) progress.equippedGear else bestMelee()
        player.inventory.setItem(1, items.fishingWeapon(player, matchIdString, melee))
        val equipped = progress.equippedGear
        if (equipped.firearmId == null) {
            player.inventory.setItem(2, ItemStack.empty())
        } else if (!firearms.enabled) {
            player.inventory.setItem(2, ItemStack.empty())
        } else {
            val existing = firearms.state(player.inventory.getItem(2))
            if (existing?.id != equipped.firearmId || existing.matchId != matchIdString) {
                val cached = firearmItems[equipped]
                player.inventory.setItem(2, cached?.clone() ?: firearms.firearmItem(equipped.firearmId, player, matchIdString, 0))
            }
        }
        appliedGear = equipped
        player.inventory.setItem(3, if (progress.dynamite > 0) items.fishingDynamite(player, matchIdString, progress.dynamite) else ItemStack.empty())
        player.inventory.setItem(4, when {
            progress.encounter != null && progress.phase in setOf(FishingPhase.CREATURE, FishingPhase.BOSS) ->
                items.fishingLiveCatch(player, matchIdString, localized("fishing.species.${progress.encounter!!.species}"))
            progress.bag.isNotEmpty() ->
                items.fishingCatchBag(player, matchIdString, progress.bag.size,
                    localized("fishing.species.${progress.bag.first().species}"), progress.bag.first().value)
            else -> ItemStack.empty()
        })
        player.inventory.setItem(8, items.fishingLedger(player, matchIdString))
        player.updateInventory()
    }

    private fun bestMelee(): FishingGear = progress.ownedGear
        .filter { it.firearmId == null }
        .maxByOrNull(FishingGear::meleeDamage) ?: FishingGear.KNUCKLES

    private fun meleeGear(item: ItemStack?): FishingGear? = when (item?.type) {
        Material.FLINT -> FishingGear.KNUCKLES
        Material.STONE_SWORD -> FishingGear.KNIFE
        Material.IRON_SWORD -> FishingGear.MACHETE
        else -> null
    }

    private fun dynamiteInventoryConsistent(): Boolean {
        val storage = player.inventory.storageContents
        val tagged = storage.indices.filter { slot ->
            val stack = storage[slot]
            items.kind(stack) == EventItemKind.FISHING_DYNAMITE && items.belongsTo(stack, matchIdString)
        }
        if (tagged.any { it != 3 }) return false
        val stack = storage.getOrNull(3)
        return if (progress.dynamite == 0) tagged.isEmpty() else
            tagged == listOf(3) && stack?.amount == progress.dynamite
    }

    private fun ammoDestination(): Int? = (9 until player.inventory.storageContents.size).firstOrNull { slot ->
        player.inventory.storageContents[slot]?.isEmpty != false
    }

    private fun showFishingSpot() {
        val zone = FishingArenaGenerator.fishingZone(stage())
        for (x in listOf(zone.minX + 0.5, zone.maxX + 0.5)) for (z in listOf(zone.minZ + 0.5, zone.maxZ + 0.5)) {
            world.spawnParticle(Particle.END_ROD, Location(world, x, zone.waterSurfaceY + 1.1, z), 1, 0.0, 0.0, 0.0, 0.0)
        }
    }

    private fun showTravelMarker() {
        if (travelMarker?.isValid == true) return
        val location = point(FishingArenaGenerator.exit(stage())).add(0.0, 1.0, 0.0)
        travelMarker = spawnMarker(location, "fishing.travel-marker")
    }

    private fun showTraderMarker() {
        if (traderMarker?.isValid == true || progress.phase !in setOf(FishingPhase.CASTING, FishingPhase.TROPHY)) return
        val location = point(FishingArenaGenerator.trader(stage())).add(0.0, 1.4, 0.0)
        traderMarker = spawnMarker(location, "fishing.trader-marker")
    }

    private fun showTraderNpc() {
        if (traderNpc?.isValid == true) return
        traderNpc = world.spawn(point(FishingArenaGenerator.trader(stage())).add(0.0, 0.0, -2.0), Villager::class.java).also { npc ->
            npc.isPersistent = false
            npc.setAI(false)
            npc.isAware = false
            npc.isCollidable = false
            npc.customName(localized("fishing.trader-npc"))
            npc.isCustomNameVisible = true
            tag(npc)
        }
    }

    private fun spawnMarker(location: Location, key: String): TextDisplay = world.spawn(location, TextDisplay::class.java) { display ->
        display.text(localized(key))
        display.isPersistent = false
        display.billboard = org.bukkit.entity.Display.Billboard.CENTER
        display.isShadowed = true
        tag(display)
    }

    private fun enforceStageBounds() {
        if (!inStageBounds(player.location)) {
            submergedSinceMs = 0L
            teleportTo(stage())
            return
        }
        val feet = player.location.block
        if (feet.type != Material.WATER && feet.getRelative(0, -1, 0).type != Material.WATER) {
            submergedSinceMs = 0L
            return
        }
        if (submergedSinceMs == 0L) submergedSinceMs = clock()
        if (clock() - submergedSinceMs >= 4_000L) {
            submergedSinceMs = 0L
            teleportTo(stage())
            sendActionBar("fishing.water-rescue")
        }
    }

    private fun inStageBounds(location: Location): Boolean =
        location.world.uid == world.uid && location.x in (stage().centerX - 29.0)..(stage().centerX + 29.0) &&
            location.z in -24.0..30.0 && location.y in 53.0..101.0

    private fun playerInCurrentStage(): Boolean = player.world.uid == world.uid && inStageBounds(player.location)

    private fun inFishingZone(location: Location): Boolean {
        val zone = FishingArenaGenerator.fishingZone(stage())
        return location.world.uid == world.uid && location.x in (zone.minX - rules.fishingRadius)..(zone.maxX + rules.fishingRadius) &&
            location.z in (zone.minZ - rules.fishingRadius)..(zone.maxZ + rules.fishingRadius) &&
            abs(location.y - zone.waterSurfaceY) <= rules.fishingRadius + 4.0
    }

    private fun inCurrentFishingWater(location: Location): Boolean {
        val zone = FishingArenaGenerator.fishingZone(stage())
        if (location.world.uid != world.uid || location.x !in zone.minX.toDouble()..(zone.maxX + 1).toDouble() ||
            location.z !in zone.minZ.toDouble()..(zone.maxZ + 1).toDouble() ||
            location.blockY !in (zone.waterSurfaceY - zone.minimumDepth)..(zone.waterSurfaceY + 1)
        ) return false
        return location.block.type == Material.WATER || location.block.getRelative(0, -1, 0).type == Material.WATER
    }

    private fun grounded(location: Location): Location = Location(
        world,
        location.x.coerceIn(stage().centerX - 14.0, stage().centerX + 14.0),
        FishingArenaGenerator.SPAWN_Y.toDouble(),
        location.z.coerceIn(-5.0, 12.0),
    )

    private fun near(point: FishingArenaPoint, radius: Double): Boolean =
        player.location.distanceSquared(point(point)) <= radius * radius

    private fun isOwnedEntity(entity: Entity): Boolean =
        entity.persistentDataContainer.get(ownerKey, PersistentDataType.STRING) == ownerTag

    private fun isCurrentEntity(entity: Entity): Boolean =
        entity.isValid && entity.world.uid == world.uid &&
            entity.persistentDataContainer.get(ownerKey, PersistentDataType.STRING) == ownerTag &&
            entity.persistentDataContainer.get(matchKey, PersistentDataType.STRING) == matchIdString &&
            entity.persistentDataContainer.get(stageKey, PersistentDataType.INTEGER) == progress.stage

    private fun isTaggedForThisMatch(entity: Entity): Boolean =
        entity.persistentDataContainer.get(ownerKey, PersistentDataType.STRING) == ownerTag &&
            entity.persistentDataContainer.get(matchKey, PersistentDataType.STRING) == matchIdString &&
            entity.persistentDataContainer.get(stageKey, PersistentDataType.INTEGER) == progress.stage

    private fun tag(entity: Entity) {
        entity.persistentDataContainer.set(ownerKey, PersistentDataType.STRING, ownerTag)
        entity.persistentDataContainer.set(matchKey, PersistentDataType.STRING, matchIdString)
        entity.persistentDataContainer.set(stageKey, PersistentDataType.INTEGER, progress.stage)
    }

    private fun cleanupStaleEntities() {
        FishingArenaStage.entries.forEach { arenaStage ->
            val center = Location(world, arenaStage.centerX + 0.5, FishingArenaGenerator.SPAWN_Y.toDouble(), 0.5)
            world.getNearbyEntities(center, 25.0, 48.0, 32.0).filter(::isOwnedEntity).forEach(Entity::remove)
        }
    }

    private fun cleanupMatchEntities() {
        hookId?.let { plugin.server.getEntity(it)?.remove() }
        hookId = null
        fuses.keys.toList().forEach { plugin.server.getEntity(it)?.remove() }
        travelMarker?.remove()
        traderMarker?.remove()
        traderNpc?.remove()
        creatures.values.toList().forEach { it.remove() }
        FishingArenaStage.entries.forEach { arenaStage ->
            val center = Location(world, arenaStage.centerX + 0.5, FishingArenaGenerator.SPAWN_Y.toDouble(), 0.5)
            world.getNearbyEntities(center, 25.0, 48.0, 32.0).filter(::isTaggedForThisMatch).forEach(Entity::remove)
        }
    }

    private fun stage(): FishingArenaStage = FishingArenaStage.entries[progress.stage]
    private fun owns(candidate: Player): Boolean =
        candidate.uniqueId == player.uniqueId && candidate.world.uid == world.uid && candidate.isOnline
    private fun point(point: FishingArenaPoint): Location = Location(world, point.x, point.y, point.z, point.yaw, point.pitch)

    private fun teleportTo(stage: FishingArenaStage) {
        val destination = FishingArenaGenerator.spawn(stage)
        teleport(player, EventLocation(world.name, destination.x, destination.y, destination.z, destination.yaw, destination.pitch))
    }

    private fun signalCompletion() {
        if (completionSignalled || closed) return
        completionSignalled = true
        complete()
    }

    private fun localized(key: String, values: Map<String, Component> = emptyMap()): Component = locale.render(key, player, values)

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
            (event.caught as? Item)?.remove()
        }
        event.hook.remove()
        if (hookId == event.hook.uniqueId) hookId = null
    }
}
