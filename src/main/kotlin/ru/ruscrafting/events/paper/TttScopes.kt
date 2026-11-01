package ru.ruscrafting.events.paper

import com.github.retrooper.packetevents.PacketEvents
import com.github.retrooper.packetevents.event.PacketListenerAbstract
import com.github.retrooper.packetevents.event.PacketListenerPriority
import com.github.retrooper.packetevents.event.PacketSendEvent
import com.github.retrooper.packetevents.event.UserDisconnectEvent
import com.github.retrooper.packetevents.netty.channel.ChannelHelper
import com.github.retrooper.packetevents.protocol.component.ComponentTypes
import com.github.retrooper.packetevents.protocol.component.builtin.item.ChargedProjectiles
import com.github.retrooper.packetevents.protocol.component.builtin.item.ItemModel
import com.github.retrooper.packetevents.protocol.item.ItemStack as PacketItemStack
import com.github.retrooper.packetevents.protocol.item.type.ItemTypes
import com.github.retrooper.packetevents.protocol.packettype.PacketType
import com.github.retrooper.packetevents.protocol.player.ClientVersion
import com.github.retrooper.packetevents.protocol.player.Equipment
import com.github.retrooper.packetevents.protocol.player.EquipmentSlot as PacketEquipmentSlot
import com.github.retrooper.packetevents.resources.ResourceLocation
import com.github.retrooper.packetevents.wrapper.PacketWrapper
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityEquipment
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerPlayerAbilities
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSetPlayerInventory
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSetSlot
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerWindowItems
import io.github.retrooper.packetevents.util.SpigotConversionUtil
import org.bukkit.Bukkit
import org.bukkit.GameMode
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import ru.arc.core.ScheduledTask
import ru.arc.core.Tasks
import ru.arc.paper.display.PaperPacketDisplays
import ru.ruscrafting.events.domain.FirearmId
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.logging.Level

internal fun firearmScopeZoom(id: FirearmId): Float = when (id) {
    FirearmId.VSS_VINTOREZ -> 3f
    FirearmId.MCMILLAN -> 5f
    FirearmId.AEK_971, FirearmId.RPL_20, FirearmId.VEPR_12 -> 1.35f
    FirearmId.FLINTLOCK, FirearmId.REVOLVER, FirearmId.HAND_CANNON, FirearmId.DOUBLE_BARREL,
    FirearmId.FIVE_SEVEN, FirearmId.G36, FirearmId.M1_GARAND -> 1.15f
}

/** A null match belongs only to the separately authorized raw-item preview. */
internal data class FirearmScopeTarget(val matchId: String?, val firearm: FirearmId, val heldSlot: Int)

private val SCOPE_MODEL_FRAMES = listOf("hip", "raise_1", "raise_2", "raise_3", "aim", "equip_hip", "equip_aim")
private val SCOPE_MODEL_KEYS = FirearmId.entries.flatMap { id ->
    SCOPE_MODEL_FRAMES.map { frame -> "arc:event_sights/${id.name.lowercase()}_$frame" }
}.toSet()

internal fun isScopeViewmodelItem(item: ItemStack?): Boolean {
    val model = item?.takeUnless(ItemStack::isEmpty)?.itemMeta?.itemModel?.toString() ?: return false
    return model in SCOPE_MODEL_KEYS
}

internal data class ScopeAbilities(
    val invulnerable: Boolean,
    val flying: Boolean,
    val flightAllowed: Boolean,
    val creative: Boolean,
    val flySpeed: Float,
    val walkingSpeed: Float,
) {
    fun packet(zoom: Float? = null) = WrapperPlayServerPlayerAbilities(
        invulnerable, flying, flightAllowed, creative, flySpeed,
        // Vanilla 1.21.11 FOV is (movement attribute / walkingSpeed + 1) / 2.
        // Only the client denominator changes; its movement attribute remains authoritative.
        if (zoom == null) walkingSpeed else walkingSpeed * zoom / (2 - zoom),
    )

    companion object {
        fun read(packet: WrapperPlayServerPlayerAbilities) = ScopeAbilities(
            packet.isInGodMode, packet.isFlying, packet.isFlightAllowed, packet.isInCreativeMode,
            packet.flySpeed, packet.fovModifier,
        )
    }
}

internal class ScopeConnection(
    val entityId: Int,
    val protocol: Int,
    val send: (PacketWrapper<*>) -> Unit,
    val execute: (() -> Unit) -> CompletableFuture<Unit>,
)

/** Paper owns admission and animation. Netty only reads detached frames and native abilities. */
class TttScopes internal constructor(
    private val connection: (Player) -> ScopeConnection?,
    private val packetItem: (ItemStack) -> PacketItemStack = SpigotConversionUtil::fromBukkitItemStack,
    private val authorized: (Player, FirearmScopeTarget) -> Boolean = { _, _ -> true },
    private val schedule: (Long, () -> Unit) -> ScheduledTask = { ticks, action ->
        Tasks.scheduler.runLater(ticks) { action() }
    },
    private val observers: (Player) -> Collection<Player> = { it.trackedBy },
    private val createLaser: ((Player) -> TttScopeLaser)? = null,
) : PacketListenerAbstract(PacketListenerPriority.HIGHEST) {
    private data class Client(val playerId: UUID, val entityId: Int)
    private data class Frame(val model: String, val zoom: Float?)
    private class Scope(
        val playerId: UUID,
        val player: Player,
        val target: FirearmScopeTarget,
        val connection: ScopeConnection,
    ) {
        val zoom = firearmScopeZoom(target.firearm)
        val preview = target.matchId == null
        val worldId = player.world.uid
        var aiming = true
        var motion = 0L
        var task: ScheduledTask? = null
        var settleTask: ScheduledTask? = null
        var laser: TttScopeLaser? = null
        @Volatile var frame = Frame(model(target.firearm, "hip"), null)
    }

    private val active = ConcurrentHashMap<UUID, Scope>()
    private val entities = ConcurrentHashMap<Int, Scope>()
    private val abilities = ConcurrentHashMap<Client, ScopeAbilities>()
    private val pending = ConcurrentHashMap.newKeySet<CompletableFuture<Unit>>()
    private val disconnected = ConcurrentLinkedQueue<Scope>()
    // Main-only; also retains a disconnected scope's next-tick callback until it reaches its identity fence.
    private val scheduled = mutableSetOf<ScheduledTask>()
    private var closed = false
    private var closeCompletion: CompletableFuture<Unit>? = null
    private var unregister: () -> Unit = {}

    fun zoom(playerId: UUID): Float? = active[playerId]?.frame?.zoom
    fun isAiming(playerId: UUID): Boolean = active[playerId]?.let { it.aiming && it.frame.zoom != null } == true
    fun isPreview(playerId: UUID): Boolean = active[playerId]?.preview == true

    internal fun toggle(player: Player, target: FirearmScopeTarget): Boolean {
        drainDisconnected()
        val playerId = player.uniqueId
        active[playerId]?.let { scope ->
            if (scope.target == target) {
                if (!valid(scope) || !authorized(player, target)) { clear(playerId); return false }
                scope.aiming = !scope.aiming
                present(scope)
                return true
            }
            clear(playerId)
        }
        if (closed) return false
        val client = connection(player) ?: return false
        if (client.protocol < ClientVersion.V_1_21_4.protocolVersion) return false
        val scope = Scope(playerId, player, target, client)
        if (!valid(scope) || !authorized(player, target)) return false
        active[playerId] = scope
        entities[client.entityId] = scope
        present(scope)
        return true
    }

    private fun valid(scope: Scope): Boolean {
        if (!scope.player.isOnline || scope.player.world.uid != scope.worldId || scope.player.gameMode !in allowedModes(scope.preview) ||
            connection(scope.player)?.entityId != scope.connection.entityId
        ) return false
        val baseline = abilities[Client(scope.playerId, scope.connection.entityId)] ?: return scope.preview
        return baseline.walkingSpeed > 0f && (scope.preview || !baseline.creative)
    }

    private fun present(scope: Scope) {
        ++scope.motion
        stopLaser(scope)
        stopSettle(scope)
        if (active[scope.playerId] !== scope) return
        // Transition aliases enable vanilla's render-interpolated equip motion between the two poses.
        // Swapping intermediate model transforms is a hard cut, even with one packet every tick.
        scope.frame = Frame(model(scope.target.firearm, if (scope.aiming) "equip_aim" else "equip_hip"), scope.zoom.takeIf { scope.aiming })
        refreshMovement(scope)
        val held = packetItem(scope.player.inventory.getItem(scope.target.heldSlot) ?: ItemStack.of(Material.AIR))
        dispatch(scope.connection) {
            if (active[scope.playerId] === scope) {
                val frame = scope.frame
                abilities[Client(scope.playerId, scope.connection.entityId)]?.let {
                    scope.connection.send(it.packet(frame.zoom))
                }
                scope.connection.send(WrapperPlayServerSetPlayerInventory(scope.target.heldSlot, aimedItem(held, frame)))
            }
        }
        sendObserverEquipment(scope, held)
        settle(scope, scope.motion)
        if (scope.aiming && scope.target.firearm == FirearmId.VSS_VINTOREZ && createLaser != null) {
            try {
                scope.laser = createLaser.invoke(scope.player)
                updateLaser(scope, scope.motion)
            } catch (failure: Exception) {
                laserFailed(scope, failure)
            }
        }
    }

    private fun settle(scope: Scope, motion: Long) {
        var task: ScheduledTask? = null
        val accepted = schedule(6L) {
            task?.let(scheduled::remove)
            drainDisconnected()
            if (active[scope.playerId] !== scope || scope.motion != motion) return@schedule
            scope.settleTask = null
            if (!valid(scope) || !authorized(scope.player, scope.target)) { clear(scope.playerId); return@schedule }
            // Same geometry, swap animation disabled: ammo/lore updates must not lower the gun again.
            scope.frame = Frame(model(scope.target.firearm, if (scope.aiming) "aim" else "hip"), scope.zoom.takeIf { scope.aiming })
            val held = packetItem(scope.player.inventory.getItem(scope.target.heldSlot) ?: ItemStack.of(Material.AIR))
            dispatch(scope.connection) {
                if (active[scope.playerId] === scope && scope.motion == motion) {
                    scope.connection.send(WrapperPlayServerSetPlayerInventory(scope.target.heldSlot, aimedItem(held, scope.frame)))
                }
            }
        }
        task = accepted
        scope.settleTask = accepted
        scheduled += accepted
    }

    private fun stopSettle(scope: Scope) {
        scope.settleTask?.let { it.cancel(); scheduled.remove(it) }
        scope.settleTask = null
    }

    /** A client flight toggle need not produce a server abilities packet. Read its accepted state on Main. */
    private fun refreshMovement(scope: Scope) {
        abilities.computeIfPresent(Client(scope.playerId, scope.connection.entityId)) { _, baseline ->
            baseline.copy(
                flying = scope.player.isFlying,
                flightAllowed = scope.player.allowFlight,
                // CraftPlayer's public speeds are twice the native abilities values.
                flySpeed = scope.player.flySpeed / 2f,
                walkingSpeed = scope.player.walkSpeed / 2f,
            )
        }
    }

    private fun sendObserverEquipment(scope: Scope, held: PacketItemStack) {
        val frame = scope.frame
        val motion = scope.motion
        val item = if (frame.zoom == null) held else observerItem(held, frame)
        observers(scope.player).filter { it.uniqueId != scope.playerId && it.canSee(scope.player) }.forEach { observer ->
            val client = connection(observer) ?: return@forEach
            dispatch(client) {
                // A later toggle/clear owns its own restoration; obsolete queued poses must not win.
                if (active[scope.playerId] === scope && scope.motion == motion) {
                    client.send(WrapperPlayServerEntityEquipment(scope.connection.entityId, listOf(Equipment(PacketEquipmentSlot.MAIN_HAND, item))))
                }
            }
        }
    }

    private fun updateLaser(scope: Scope, motion: Long) {
        try {
            scope.laser?.update() ?: return
        } catch (failure: Exception) {
            laserFailed(scope, failure)
            return
        }
        var task: ScheduledTask? = null
        val accepted = schedule(2L) {
            task?.let(scheduled::remove)
            drainDisconnected()
            if (active[scope.playerId] !== scope || scope.motion != motion) return@schedule
            scope.task = null
            if (!valid(scope) || !authorized(scope.player, scope.target)) clear(scope.playerId)
            else updateLaser(scope, motion)
        }
        task = accepted
        scope.task = accepted
        scheduled += accepted
    }

    private fun laserFailed(scope: Scope, failure: Exception) {
        stopLaser(scope)
        Bukkit.getLogger().log(Level.WARNING, "ArcEvents scope laser failed for player=${scope.playerId}", failure)
    }

    private fun stopLaser(scope: Scope) {
        scope.task?.let { it.cancel(); scheduled.remove(it) }
        scope.task = null
        scope.laser?.close()
        scope.laser = null
    }

    private fun drainDisconnected() {
        while (true) {
            val scope = disconnected.poll() ?: return
            stopLaser(scope)
            stopSettle(scope)
        }
    }

    /** Invalidations restore immediately; only an explicit second RMB lowers the model first. */
    fun clear(playerId: UUID) {
        drainDisconnected()
        val scope = active.remove(playerId) ?: return
        entities.remove(scope.connection.entityId, scope)
        stopLaser(scope)
        stopSettle(scope)
        refreshMovement(scope)
        val realHeld = packetItem(scope.player.inventory.getItem(scope.target.heldSlot) ?: ItemStack.of(Material.AIR))
        dispatch(scope.connection) {
            abilities[Client(playerId, scope.connection.entityId)]?.let { scope.connection.send(it.packet()) }
            scope.connection.send(WrapperPlayServerSetPlayerInventory(scope.target.heldSlot, realHeld))
        }
        val currentHand = packetItem(scope.player.inventory.itemInMainHand)
        observers(scope.player).filter { it.uniqueId != playerId && it.canSee(scope.player) }.forEach { observer ->
            val client = connection(observer) ?: return@forEach
            dispatch(client) {
                client.send(WrapperPlayServerEntityEquipment(scope.connection.entityId,
                    listOf(Equipment(PacketEquipmentSlot.MAIN_HAND, currentHand))))
            }
        }
    }

    internal fun validate(authorized: (Player, FirearmScopeTarget) -> Boolean) {
        drainDisconnected()
        active.entries.toList().forEach { (playerId, scope) ->
            if (!valid(scope) || !authorized(scope.player, scope.target)) clear(playerId)
        }
    }

    fun clearAll() {
        drainDisconnected()
        active.keys.toList().forEach(::clear)
    }

    fun closeAsync(): CompletableFuture<Unit> {
        closeCompletion?.let { return it }
        closed = true
        clearAll()
        scheduled.forEach(ScheduledTask::cancel)
        scheduled.clear()
        return CompletableFuture.allOf(*pending.toTypedArray()).whenComplete { _, _ ->
            abilities.clear()
            unregister()
        }.thenApply { Unit }.also { closeCompletion = it }
    }

    private fun dispatch(client: ScopeConnection, action: () -> Unit) {
        val completion = client.execute(action)
        pending += completion
        completion.whenComplete { _, _ -> pending.remove(completion) }
    }

    override fun onUserDisconnect(event: UserDisconnectEvent) {
        val playerId = event.user.uuid ?: return
        val entityId = event.user.entityId
        active[playerId]?.takeIf { it.connection.entityId == entityId }?.let {
            if (active.remove(playerId, it)) {
                entities.remove(entityId, it)
                disconnected.add(it)
            }
        }
        abilities.remove(Client(playerId, entityId))
    }

    override fun onPacketSend(event: PacketSendEvent) {
        if (event.isCancelled) return
        val playerId = event.user.uuid ?: return
        if (event.packetType == PacketType.Play.Server.PLAYER_ABILITIES) {
            rewriteAbilities(playerId, event.user.entityId, WrapperPlayServerPlayerAbilities(event))
            event.markForReEncode(true)
            return
        }
        if (event.packetType == PacketType.Play.Server.ENTITY_EQUIPMENT) {
            val packet = WrapperPlayServerEntityEquipment(event)
            val source = entities[packet.entityId] ?: return
            if (source.playerId == playerId) return
            val frame = source.frame
            if (frame.zoom == null) return
            packet.equipment = packet.equipment.map {
                if (it.slot == PacketEquipmentSlot.MAIN_HAND) Equipment(it.slot, observerItem(it.item, frame)) else it
            }
            event.markForReEncode(true)
            return
        }
        val scope = active[playerId]?.takeIf { it.connection.entityId == event.user.entityId } ?: return
        val frame = scope.frame
        when (event.packetType) {
            PacketType.Play.Server.SET_PLAYER_INVENTORY -> {
                val packet = WrapperPlayServerSetPlayerInventory(event)
                if (packet.slot == scope.target.heldSlot) packet.stack = aimedItem(packet.stack, frame)
            }
            PacketType.Play.Server.SET_SLOT -> {
                val packet = WrapperPlayServerSetSlot(event)
                if ((packet.windowId == 0 && packet.slot == HOTBAR_CONTAINER_START + scope.target.heldSlot) ||
                    (packet.windowId == -2 && packet.slot == scope.target.heldSlot)
                ) packet.item = aimedItem(packet.item, frame)
            }
            PacketType.Play.Server.WINDOW_ITEMS -> {
                val packet = WrapperPlayServerWindowItems(event)
                val heldSlot = HOTBAR_CONTAINER_START + scope.target.heldSlot
                if (packet.windowId == 0 && packet.items.size > heldSlot) {
                    packet.items = packet.items.toMutableList().also { it[heldSlot] = aimedItem(it[heldSlot], frame) }
                }
            }
            else -> return
        }
        event.markForReEncode(true)
    }

    internal fun rewriteAbilities(playerId: UUID, entityId: Int, packet: WrapperPlayServerPlayerAbilities) {
        val baseline = ScopeAbilities.read(packet)
        abilities[Client(playerId, entityId)] = baseline
        val scope = active[playerId]?.takeIf { it.connection.entityId == entityId } ?: return
        val frame = scope.frame
        if (frame.zoom == null || (!scope.preview && baseline.creative) || baseline.walkingSpeed <= 0f) return
        packet.fovModifier = baseline.packet(frame.zoom).fovModifier
    }

    private fun aimedItem(item: PacketItemStack, frame: Frame): PacketItemStack =
        if (item.isEmpty) item else item.copy().also { it.setComponent(ComponentTypes.ITEM_MODEL, ItemModel(ResourceLocation(frame.model))) }

    /** Only remote equipment is a charged crossbow: native two-handed pose, original gun model. */
    private fun observerItem(item: PacketItemStack, frame: Frame): PacketItemStack {
        if (item.isEmpty) return item
        val displayed = aimedItem(item, frame)
        return PacketItemStack.builder().type(ItemTypes.CROSSBOW).amount(displayed.amount)
            .components(displayed.components).version(displayed.version).registryHolder(displayed.registryHolder).build().also {
                it.setComponent(ComponentTypes.CHARGED_PROJECTILES, ChargedProjectiles(listOf(PacketItemStack.builder().type(ItemTypes.ARROW).build())))
            }
    }

    companion object {
        private const val HOTBAR_CONTAINER_START = 36
        private fun allowedModes(preview: Boolean) = if (preview) PREVIEW_MODES else MATCH_MODES
        private val MATCH_MODES = setOf(GameMode.SURVIVAL, GameMode.ADVENTURE)
        private val PREVIEW_MODES = MATCH_MODES + GameMode.CREATIVE

        private fun model(id: FirearmId, frame: String) = "arc:event_sights/${id.name.lowercase()}_$frame"

        internal fun open(displays: PaperPacketDisplays, authorized: (Player, FirearmScopeTarget) -> Boolean): TttScopes = TttScopes(connection = { player ->
            PacketEvents.getAPI().playerManager.getUser(player)?.let { user ->
                ScopeConnection(user.entityId, user.clientVersion.protocolVersion, user::sendPacketSilently) { action ->
                    val completion = CompletableFuture<Unit>()
                    ChannelHelper.runInEventLoop(user.channel) {
                        runCatching { if (ChannelHelper.isOpen(user.channel)) action() }
                            .fold({ completion.complete(Unit) }, completion::completeExceptionally)
                    }
                    completion
                }
            }
        }, authorized = authorized, createLaser = { TttScopeLaser(it, displays) }).also { scopes ->
            PacketEvents.getAPI().eventManager.registerListener(scopes)
            scopes.unregister = { PacketEvents.getAPI().eventManager.unregisterListener(scopes) }
        }
    }
}
