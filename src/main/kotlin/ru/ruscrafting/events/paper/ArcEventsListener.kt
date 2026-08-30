package ru.ruscrafting.events.paper

import io.papermc.paper.event.player.AsyncChatEvent
import net.kyori.adventure.text.Component
import org.bukkit.Location
import org.bukkit.entity.ArmorStand
import org.bukkit.entity.Entity
import org.bukkit.entity.Player
import org.bukkit.entity.Projectile
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.event.block.BlockBurnEvent
import org.bukkit.event.block.BlockExplodeEvent
import org.bukkit.event.block.BlockFromToEvent
import org.bukkit.event.block.BlockIgniteEvent
import org.bukkit.event.block.BlockPlaceEvent
import org.bukkit.event.block.Action
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.event.entity.EntityDamageEvent
import org.bukkit.event.entity.EntityPickupItemEvent
import org.bukkit.event.entity.EntityExplodeEvent
import org.bukkit.event.entity.FoodLevelChangeEvent
import org.bukkit.event.entity.ProjectileHitEvent
import org.bukkit.event.entity.ProjectileLaunchEvent
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryDragEvent
import org.bukkit.event.player.PlayerCommandPreprocessEvent
import org.bukkit.event.player.PlayerBucketEmptyEvent
import org.bukkit.event.player.PlayerBucketFillEvent
import org.bukkit.event.player.PlayerDropItemEvent
import org.bukkit.event.player.PlayerInteractAtEntityEvent
import org.bukkit.event.player.PlayerInteractEntityEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerMoveEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.event.player.PlayerSwapHandItemsEvent
import org.bukkit.event.player.PlayerTeleportEvent
import org.bukkit.inventory.EquipmentSlot
import ru.arc.core.Tasks
import ru.ruscrafting.events.domain.MatchPhase
import java.util.UUID

interface ArcEventsGameplayBoundary {
    fun handleJoin(player: Player)
    fun handleQuit(player: Player)
    fun withinArena(location: Location): Boolean
    fun isParticipant(playerId: UUID): Boolean
    fun shouldCancelDamage(victimId: UUID, attackerId: UUID?, projectile: Boolean, projectileMatchId: UUID?): Boolean
    fun recordAttack(victimId: UUID, attackerId: UUID?)
    fun damageMultiplier(attackerId: UUID?): Double
    fun recordDamage(victim: Player, attacker: Player?, finalDamage: Double, lethal: Boolean)
    fun phase(): MatchPhase?
    fun isAlive(playerId: UUID): Boolean
    fun eliminate(player: Player, killerId: UUID? = null)
    fun registerProjectile(projectile: Projectile): Boolean
    fun projectileMatchId(projectile: Projectile): UUID?
    fun handleProjectileHit(projectile: Projectile)
    fun handlesMatchChat(playerId: UUID): Boolean
    fun sendMatchChat(player: Player, message: Component)
    fun belongsToCurrentMatch(playerId: UUID, item: org.bukkit.inventory.ItemStack?): Boolean
    fun useSpecialItem(player: Player, kind: EventItemKind): Boolean
    fun useFirearm(player: Player): Boolean
    fun reloadFirearm(player: Player): Boolean
    fun canDropLoot(player: Player, item: org.bukkit.inventory.ItemStack?): Boolean
    fun registerDroppedLoot(item: org.bukkit.entity.Item)
    fun canPickupLoot(player: Player, item: org.bukkit.entity.Item): Boolean
    fun handleLootPickup(player: Player, item: org.bukkit.entity.Item)
    fun cancelMapSpawnReturn(playerId: UUID, notify: Boolean = true)
    fun readBodyId(stand: ArmorStand): UUID?
    fun inspectBody(player: Player, bodyId: UUID)
    fun isInternalTeleport(playerId: UUID, destination: Location?): Boolean
}

class ArcEventsListener(
    private val service: ArcEventsGameplayBoundary,
    private val menu: ArcEventsMenu,
    private val itemResolver: EventItemResolver,
) : Listener {
    private val pendingEliminations = mutableSetOf<UUID>()

    @EventHandler fun onJoin(event: PlayerJoinEvent) = service.handleJoin(event.player)
    @EventHandler fun onQuit(event: PlayerQuitEvent) = service.handleQuit(event.player)

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onDamage(event: EntityDamageEvent) {
        val victim = event.entity as? Player ?: return
        service.cancelMapSpawnReturn(victim.uniqueId)
        val damager = (event as? EntityDamageByEntityEvent)?.damager
        val projectile = damager as? Projectile
        val attacker = damager?.let(::attacker)
        if (victim.bypassesEventProtection() || attacker?.bypassesEventProtection() == true) return
        if (service.withinArena(victim.location) && !service.isParticipant(victim.uniqueId)) {
            event.isCancelled = true
            return
        }
        if (victim.uniqueId in pendingEliminations) {
            event.isCancelled = true
            return
        }
        if (service.shouldCancelDamage(
                victim.uniqueId,
                attacker?.uniqueId,
                projectile = projectile != null,
                projectileMatchId = projectile?.let(service::projectileMatchId),
            )
        ) {
            event.isCancelled = true
            return
        }
        val multiplier = service.damageMultiplier(attacker?.uniqueId)
        if (attacker != null && multiplier < 1.0) event.damage *= multiplier
        service.recordAttack(victim.uniqueId, attacker?.uniqueId)
        val lethal = service.phase() == MatchPhase.ACTIVE && service.isAlive(victim.uniqueId) && event.finalDamage >= victim.health
        service.recordDamage(victim, attacker, event.finalDamage.coerceAtMost(victim.health), lethal)
        if (lethal) {
            event.isCancelled = true
            if (!pendingEliminations.add(victim.uniqueId)) return
            Tasks.scheduler.runLater(1L) {
                try {
                    if (victim.isOnline) {
                        if (attacker == null) service.eliminate(victim) else service.eliminate(victim, attacker.uniqueId)
                    }
                } finally {
                    pendingEliminations.remove(victim.uniqueId)
                }
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onProjectileLaunch(event: ProjectileLaunchEvent) {
        val shooter = event.entity.shooter as? Player ?: return
        if (shooter.bypassesEventProtection()) return
        if (service.isParticipant(shooter.uniqueId) && !service.registerProjectile(event.entity)) event.isCancelled = true
    }

    @EventHandler(ignoreCancelled = true)
    fun onProjectileHit(event: ProjectileHitEvent) = service.handleProjectileHit(event.entity)

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onChat(event: AsyncChatEvent) {
        if (!service.handlesMatchChat(event.player.uniqueId)) return
        event.isCancelled = true
        val player = event.player
        val message = event.message()
        Tasks.scheduler.runSync {
            if (player.isOnline) service.sendMatchChat(player, message)
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    fun onInteract(event: PlayerInteractEvent) {
        val player = event.player
        val kind = itemResolver.kind(event.item) ?: return
        if (!service.belongsToCurrentMatch(player.uniqueId, event.item)) {
            event.isCancelled = true
            return
        }
        when (kind) {
            EventItemKind.GUIDE -> {
                event.isCancelled = true
                if (event.hand == EquipmentSlot.HAND && event.action in setOf(Action.RIGHT_CLICK_AIR, Action.RIGHT_CLICK_BLOCK)) {
                    menu.open(player, EventsView.Help)
                }
            }
            EventItemKind.SHOP -> {
                event.isCancelled = true
                menu.open(player, EventsView.Shop)
            }
            EventItemKind.TRAITOR_RADAR, EventItemKind.TRAITOR_SMOKE, EventItemKind.DETECTIVE_MEDKIT -> {
                event.isCancelled = true
                if (event.hand == EquipmentSlot.HAND && event.action in setOf(Action.RIGHT_CLICK_AIR, Action.RIGHT_CLICK_BLOCK)) {
                    service.useSpecialItem(player, kind)
                }
            }
            EventItemKind.FIREARM -> {
                event.isCancelled = true
                if (event.hand == EquipmentSlot.HAND && event.action in setOf(Action.RIGHT_CLICK_AIR, Action.RIGHT_CLICK_BLOCK)) {
                    service.useFirearm(player)
                }
            }
            EventItemKind.ROUND_REPORT -> {
                event.isCancelled = true
                if (event.hand == EquipmentSlot.HAND) menu.open(player, EventsView.Report)
            }
            EventItemKind.AMMUNITION -> event.isCancelled = true
            else -> Unit
        }
    }

    @EventHandler(ignoreCancelled = true)
    fun onInteractEntity(event: PlayerInteractEntityEvent) = inspect(event.player, event.rightClicked) { event.isCancelled = true }

    @EventHandler(ignoreCancelled = true)
    fun onInteractAtEntity(event: PlayerInteractAtEntityEvent) = inspect(event.player, event.rightClicked) { event.isCancelled = true }

    private fun inspect(player: Player, entity: Entity, cancel: () -> Unit) {
        val stand = entity as? ArmorStand ?: return
        val bodyId = service.readBodyId(stand) ?: return
        cancel()
        service.inspectBody(player, bodyId)
        menu.open(player, EventsView.Body(bodyId))
    }

    @EventHandler fun onMenuClick(event: InventoryClickEvent) {
        if (menu.isMenu(event.view.topInventory)) return menu.onClick(event)
        val player = event.whoClicked as? Player ?: return
        if (player.bypassesEventProtection()) return
        if (event.view.topInventory.location?.let(service::withinArena) == true ||
            service.isParticipant(player.uniqueId) && service.phase() in CONTROLLED_PHASES
        ) event.isCancelled = true
    }

    @EventHandler fun onMenuDrag(event: InventoryDragEvent) {
        if (menu.isMenu(event.view.topInventory)) return menu.onDrag(event)
        val player = event.whoClicked as? Player ?: return
        if (player.bypassesEventProtection()) return
        if (event.view.topInventory.location?.let(service::withinArena) == true ||
            service.isParticipant(player.uniqueId) && service.phase() in CONTROLLED_PHASES
        ) event.isCancelled = true
    }

    @EventHandler(ignoreCancelled = true) fun onDrop(event: PlayerDropItemEvent) {
        if (event.player.bypassesEventProtection()) return
        if (service.canDropLoot(event.player, event.itemDrop.itemStack)) {
            service.registerDroppedLoot(event.itemDrop)
            return
        }
        if (service.withinArena(event.player.location) || service.isParticipant(event.player.uniqueId) && service.phase() in CONTROLLED_PHASES) {
            event.isCancelled = true
        }
    }
    @EventHandler(ignoreCancelled = true) fun onPickup(event: EntityPickupItemEvent) {
        val player = event.entity as? Player ?: return
        if (player.bypassesEventProtection()) return
        if (service.canPickupLoot(player, event.item)) return
        if (service.withinArena(player.location) || service.isParticipant(player.uniqueId) && service.phase() in CONTROLLED_PHASES) {
            event.isCancelled = true
        }
    }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true) fun onPickupCommitted(event: EntityPickupItemEvent) {
        val player = event.entity as? Player ?: return
        if (player.bypassesEventProtection() || service.canPickupLoot(player, event.item)) {
            service.handleLootPickup(player, event.item)
        }
    }
    @EventHandler(ignoreCancelled = true) fun onSwap(event: PlayerSwapHandItemsEvent) {
        if (event.player.bypassesEventProtection()) return
        if (service.isParticipant(event.player.uniqueId) && service.phase() in CONTROLLED_PHASES) {
            event.isCancelled = true
            if (service.phase() == MatchPhase.ACTIVE) service.reloadFirearm(event.player)
        }
    }
    @EventHandler(ignoreCancelled = true) fun onBreak(event: BlockBreakEvent) {
        if (event.player.bypassesEventProtection()) return
        if (service.withinArena(event.block.location) || service.isParticipant(event.player.uniqueId) && service.phase() in CONTROLLED_PHASES) {
            event.isCancelled = true
        }
    }
    @EventHandler(ignoreCancelled = true) fun onPlace(event: BlockPlaceEvent) {
        if (event.player.bypassesEventProtection()) return
        if (service.withinArena(event.block.location) || service.isParticipant(event.player.uniqueId) && service.phase() in CONTROLLED_PHASES) {
            event.isCancelled = true
        }
    }
    @EventHandler(ignoreCancelled = true) fun onBucketEmpty(event: PlayerBucketEmptyEvent) {
        if (event.player.bypassesEventProtection()) return
        if (service.withinArena(event.block.location)) event.isCancelled = true
    }
    @EventHandler(ignoreCancelled = true) fun onBucketFill(event: PlayerBucketFillEvent) {
        if (event.player.bypassesEventProtection()) return
        if (service.withinArena(event.block.location)) event.isCancelled = true
    }
    @EventHandler(ignoreCancelled = true) fun onBlockBurn(event: BlockBurnEvent) {
        if (service.withinArena(event.block.location)) event.isCancelled = true
    }
    @EventHandler(ignoreCancelled = true) fun onBlockIgnite(event: BlockIgniteEvent) {
        if ((event.ignitingEntity as? Player)?.bypassesEventProtection() == true) return
        if (service.withinArena(event.block.location)) event.isCancelled = true
    }
    @EventHandler(ignoreCancelled = true) fun onBlockFlow(event: BlockFromToEvent) {
        if (service.withinArena(event.block.location) || service.withinArena(event.toBlock.location)) event.isCancelled = true
    }
    @EventHandler(ignoreCancelled = true) fun onBlockExplode(event: BlockExplodeEvent) {
        if (service.withinArena(event.block.location) || event.blockList().any { service.withinArena(it.location) }) event.isCancelled = true
    }
    @EventHandler(ignoreCancelled = true) fun onEntityExplode(event: EntityExplodeEvent) {
        if (service.withinArena(event.location) || event.blockList().any { service.withinArena(it.location) }) event.isCancelled = true
    }
    @EventHandler(ignoreCancelled = true) fun onFood(event: FoodLevelChangeEvent) {
        val player = event.entity as? Player ?: return
        if (player.bypassesEventProtection()) return
        if (service.isParticipant(player.uniqueId) && service.phase() in CONTROLLED_PHASES) {
            event.isCancelled = true
            player.foodLevel = 20
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onMove(event: PlayerMoveEvent) {
        if (event is PlayerTeleportEvent) return
        if (event.from.x != event.to.x || event.from.y != event.to.y || event.from.z != event.to.z) {
            service.cancelMapSpawnReturn(event.player.uniqueId)
        }
        if (event.player.bypassesEventProtection()) return
        if (!service.isParticipant(event.player.uniqueId) || service.phase() !in CONTROLLED_PHASES) return
        if (!service.withinArena(event.to)) event.isCancelled = true
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onTeleport(event: PlayerTeleportEvent) {
        if (event.player.bypassesEventProtection()) return
        val participant = service.isParticipant(event.player.uniqueId)
        if (!participant && service.withinArena(event.to)) {
            event.isCancelled = true
            return
        }
        if (!participant || service.phase() !in CONTROLLED_PHASES) return
        if (service.isInternalTeleport(event.player.uniqueId, event.to)) return
        val destination = event.to
        if (!service.withinArena(destination)) event.isCancelled = true
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onCommand(event: PlayerCommandPreprocessEvent) {
        if (event.player.bypassesEventProtection()) return
        if (!service.isParticipant(event.player.uniqueId) || service.phase() !in CONTROLLED_PHASES) return
        val root = event.message.removePrefix("/").substringBefore(' ').lowercase()
        if (root !in setOf("arcevents", "events", "ae")) event.isCancelled = true
    }

    private fun attacker(entity: Entity): Player? = when (entity) {
        is Player -> entity
        is Projectile -> entity.shooter as? Player
        else -> null
    }

    private fun Player.bypassesEventProtection(): Boolean = hasPermission(ADMIN_BYPASS_PERMISSION)

    companion object {
        internal const val ADMIN_BYPASS_PERMISSION = "arcevents.admin"
        private val CONTROLLED_PHASES = setOf(
            MatchPhase.PREPARING,
            MatchPhase.COUNTDOWN,
            MatchPhase.ACTIVE,
            MatchPhase.RESOLVING,
            MatchPhase.CANCELLED,
            MatchPhase.RESTORING,
        )
    }
}
