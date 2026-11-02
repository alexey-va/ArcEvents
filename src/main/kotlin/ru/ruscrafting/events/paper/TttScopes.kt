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
import com.github.retrooper.packetevents.protocol.component.builtin.item.ItemSwingAnimation
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
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSetCursorItem
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSetSlot
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerWindowItems
import io.github.retrooper.packetevents.util.SpigotConversionUtil
import org.bukkit.Bukkit
import org.bukkit.GameMode
import org.bukkit.Material
import org.bukkit.SoundCategory
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
}.toSet() + FirearmReloadAnimation.modelKeys
private val FIREARM_IDS = FirearmId.entries.map { it.name }.toSet()

/** 1.21.11 skips the local whack transform for NONE, but its separate CROSSBOW branch ignores it. */
internal fun firearmWithoutSwing(item: PacketItemStack, protocol: Int): PacketItemStack {
    if (protocol < ClientVersion.V_1_21_11.protocolVersion || item.isEmpty || item.type == ItemTypes.CROSSBOW) return item
    val data = item.getComponent(ComponentTypes.CUSTOM_DATA).orElse(null)
    val event = data?.getCompoundTagOrNull("PublicBukkitValues")
    val firearm = nativeScopePreviewItemIdentity(data) != null ||
        (event?.getStringTagValueOrNull("arcevents:event_item") == EventItemKind.FIREARM.name &&
            event.getStringTagValueOrNull("arcevents:firearm") in FIREARM_IDS) ||
        item.getComponent(ComponentTypes.ITEM_MODEL).orElse(null)?.modelLocation?.toString() in SCOPE_MODEL_KEYS
    if (!firearm) return item
    val animation = item.getComponent(ComponentTypes.SWING_ANIMATION).orElse(null)
    if (animation?.type == ItemSwingAnimation.Type.NONE && animation.duration == 6) return item
    // Keep native duration: duration=1 can become zero under Haste and produce NaN attack animation.
    return item.copy().also { it.setComponent(ComponentTypes.SWING_ANIMATION, ItemSwingAnimation(ItemSwingAnimation.Type.NONE, 6)) }
}

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
    private val laserEnabled: () -> Boolean = { false },
    private val refreshInventories: () -> Unit = {},
) : PacketListenerAbstract(PacketListenerPriority.HIGHEST) {
    private data class Client(val playerId: UUID, val entityId: Int)
    private data class Frame(val model: String, val zoom: Float?)
    private class Reload(
        val durationTicks: Int,
        val sounds: Boolean,
        val stillCurrent: () -> Boolean,
        val sourceItem: PacketItemStack,
        val cues: List<FirearmReloadAnimation.Cue>,
    ) {
        var elapsed = 0
        var nextCue = 0
        var task: ScheduledTask? = null
    }
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
        var laserFailed = false
        @Volatile var reload: Reload? = null
        @Volatile var frame = Frame(model(target.firearm, "hip"), null)
    }

    private val active = ConcurrentHashMap<UUID, Scope>()
    private val entities = ConcurrentHashMap<Int, Scope>()
    private val abilities = ConcurrentHashMap<Client, ScopeAbilities>()
    private val pending = ConcurrentHashMap.newKeySet<CompletableFuture<Unit>>()
    private val disconnected = ConcurrentLinkedQueue<Scope>()
    // Main-only; also retains a disconnected scope's next-tick callback until it reaches its identity fence.
    private val scheduled = mutableSetOf<ScheduledTask>()
    @Volatile private var closed = false
    private var closeCompletion: CompletableFuture<Unit>? = null
    private var unregister: () -> Unit = {}

    fun zoom(playerId: UUID): Float? = active[playerId]?.frame?.zoom
    fun isAiming(playerId: UUID): Boolean = active[playerId]?.let { it.aiming && it.frame.zoom != null } == true
    fun isPreview(playerId: UUID): Boolean = active[playerId]?.preview == true
    fun hasViewmodel(playerId: UUID): Boolean = active.containsKey(playerId)
    fun projectedZoom(playerId: UUID): Float? {
        val scope = active[playerId] ?: return null
        val baseline = abilities[Client(playerId, scope.connection.entityId)] ?: return null
        return scope.frame.zoom?.takeIf { (scope.preview || !baseline.creative) && baseline.walkingSpeed > 0f }
    }

    /** Cosmetic only: no inventory mutation, ammo authority or gameplay reload timer lives here. */
    internal fun animateReload(
        player: Player,
        target: FirearmScopeTarget,
        durationTicks: Int,
        sounds: Boolean = false,
        stillCurrent: () -> Boolean,
    ): AutoCloseable {
        var scope: Scope? = null
        try {
            drainDisconnected()
            if (closed || durationTicks <= 0) return AutoCloseable {}
            val client = connection(player) ?: return AutoCloseable {}
            if (client.protocol < ClientVersion.V_1_21_4.protocolVersion) return AutoCloseable {}
            clear(player.uniqueId)
            val session = Scope(player.uniqueId, player, target, client).also { scope = it; it.aiming = false }
            val held = packetItem(player.inventory.getItem(target.heldSlot) ?: ItemStack.of(Material.AIR))
            if (held.isEmpty) return AutoCloseable {}
            val reload = Reload(durationTicks, sounds, stillCurrent, held.copy(), FirearmReloadAnimation.cues(target.firearm))
            session.reload = reload
            if (!validReload(session, reload)) return AutoCloseable {}
            active[session.playerId] = session
            entities[client.entityId] = session
            refreshMovement(session)
            dispatch(client) {
                if (active[session.playerId] === session) abilities[Client(session.playerId, client.entityId)]?.let { client.send(it.packet()) }
            }
            advanceReload(session, reload)
            return AutoCloseable {
                if (active[session.playerId] === session && session.reload === reload) finishReloadPresentation(session, reload)
            }
        } catch (failure: Exception) {
            scope?.let { if (active[it.playerId] === it) runCatching { clear(it.playerId) } }
            Bukkit.getLogger().log(Level.WARNING, "ArcEvents reload presentation failed player=${player.uniqueId} firearm=${target.firearm}", failure)
            return AutoCloseable {}
        }
    }

    /** LMB may interrupt a Creative cosmetic reload without affecting a normal aiming session. */
    internal fun cancelReload(playerId: UUID) {
        active[playerId]?.takeIf { it.reload != null }?.let { clear(playerId) }
    }

    private fun validReload(scope: Scope, reload: Reload): Boolean =
        scope.player.isOnline && !scope.player.isDead && scope.player.world.uid == scope.worldId &&
            scope.player.gameMode in PREVIEW_MODES && scope.player.inventory.heldItemSlot == scope.target.heldSlot &&
            connection(scope.player)?.entityId == scope.connection.entityId && reload.stillCurrent()

    private fun advanceReload(scope: Scope, reload: Reload) {
        if (active[scope.playerId] !== scope || scope.reload !== reload) return
        try {
            if (!validReload(scope, reload)) { clear(scope.playerId); return }
            if (reload.elapsed >= reload.durationTicks) { finishReloadPresentation(scope, reload); return }
            val next = Frame(FirearmReloadAnimation.model(scope.target.firearm, reload.elapsed, reload.durationTicks), null)
            if (scope.frame != next) {
                scope.frame = next
                val held = packetItem(scope.player.inventory.getItem(scope.target.heldSlot) ?: ItemStack.of(Material.AIR))
                dispatch(scope.connection) {
                    if (active[scope.playerId] === scope && scope.reload === reload) {
                        scope.connection.send(WrapperPlayServerSetPlayerInventory(scope.target.heldSlot, aimedItem(held, scope.frame, scope.connection.protocol)))
                    }
                }
            }
            val progress = if (reload.durationTicks == 1) 1.0 else reload.elapsed.toDouble() / (reload.durationTicks - 1)
            while (reload.nextCue < reload.cues.size && reload.cues[reload.nextCue].progress <= progress) {
                val cue = reload.cues[reload.nextCue++]
                if (reload.sounds) scope.player.playSound(scope.player.location, cue.sound, SoundCategory.PLAYERS, .45f, cue.pitch)
            }
            var task: ScheduledTask? = null
            val accepted = schedule(1L) {
                task?.let(scheduled::remove)
                if (active[scope.playerId] !== scope || scope.reload !== reload) return@schedule
                reload.task = null
                reload.elapsed++
                advanceReload(scope, reload)
            }
            task = accepted
            reload.task = accepted
            scheduled += accepted
        } catch (failure: Exception) {
            runCatching { clear(scope.playerId) }
            Bukkit.getLogger().log(Level.WARNING, "ArcEvents reload presentation failed player=${scope.playerId} firearm=${scope.target.firearm}", failure)
        }
    }

    private fun stopReload(scope: Scope) {
        scope.reload?.task?.let { it.cancel(); scheduled.remove(it) }
        scope.reload = null
    }

    private fun finishReloadPresentation(scope: Scope, reload: Reload) {
        if (active[scope.playerId] !== scope || scope.reload !== reload) return
        try {
            stopReload(scope)
            if (!valid(scope) || !authorized(scope.player, scope.target)) { clear(scope.playerId); return }
            // Preserve the stable physical hip pose, cloning the current authoritative item.
            scope.frame = Frame(model(scope.target.firearm, "hip"), null)
            val held = packetItem(scope.player.inventory.getItem(scope.target.heldSlot) ?: ItemStack.of(Material.AIR))
            dispatch(scope.connection) {
                if (active[scope.playerId] === scope && scope.reload == null) {
                    scope.connection.send(WrapperPlayServerSetPlayerInventory(scope.target.heldSlot, aimedItem(held, scope.frame, scope.connection.protocol)))
                }
            }
        } catch (failure: Exception) {
            runCatching { clear(scope.playerId) }
            Bukkit.getLogger().log(Level.WARNING, "ArcEvents reload restoration failed player=${scope.playerId}", failure)
        }
    }

    internal fun toggle(player: Player, target: FirearmScopeTarget): Boolean {
        drainDisconnected()
        val playerId = player.uniqueId
        active[playerId]?.let { scope ->
            if (scope.target == target) {
                if (scope.reload != null) return false
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
        scope.laserFailed = false
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
                scope.connection.send(WrapperPlayServerSetPlayerInventory(scope.target.heldSlot, aimedItem(held, frame, scope.connection.protocol)))
            }
        }
        sendObserverEquipment(scope, held)
        settle(scope, scope.motion)
        syncLaser(scope)
    }

    private fun syncLaser(scope: Scope) {
        if (!scope.aiming || scope.target.firearm != FirearmId.VSS_VINTOREZ || !laserEnabled()) {
            stopLaser(scope)
            scope.laserFailed = false
            return
        }
        if (scope.laser == null && !scope.laserFailed && createLaser != null) {
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
                    scope.connection.send(WrapperPlayServerSetPlayerInventory(scope.target.heldSlot, aimedItem(held, scope.frame, scope.connection.protocol)))
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
        if (!laserEnabled()) { stopLaser(scope); return }
        try {
            scope.laser?.update() ?: return
        } catch (failure: Exception) {
            laserFailed(scope, failure)
            return
        }
        var task: ScheduledTask? = null
        val accepted = schedule(1L) {
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
        scope.laserFailed = true
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
            stopReload(scope)
        }
    }

    /** Invalidations restore immediately; only an explicit second RMB lowers the model first. */
    fun clear(playerId: UUID) {
        drainDisconnected()
        val scope = active.remove(playerId) ?: return
        entities.remove(scope.connection.entityId, scope)
        stopLaser(scope)
        stopSettle(scope)
        stopReload(scope)
        refreshMovement(scope)
        val realHeld = packetItem(scope.player.inventory.getItem(scope.target.heldSlot) ?: ItemStack.of(Material.AIR)).let {
            if (closed) it else firearmWithoutSwing(it, scope.connection.protocol)
        }
        dispatch(scope.connection) {
            val replacement = active[playerId]
            if (replacement == null) {
                abilities[Client(playerId, scope.connection.entityId)]?.let { scope.connection.send(it.packet()) }
            }
            // A new session in this slot owns its projection. A different held
            // slot still needs the previous slot's client-only model removed.
            if (replacement == null || (replacement.connection.entityId == scope.connection.entityId &&
                    replacement.target.heldSlot != scope.target.heldSlot)) {
                scope.connection.send(WrapperPlayServerSetPlayerInventory(scope.target.heldSlot, realHeld))
            }
        }
        val currentHand = packetItem(scope.player.inventory.itemInMainHand)
        observers(scope.player).filter { it.uniqueId != playerId && it.canSee(scope.player) }.forEach { observer ->
            val client = connection(observer) ?: return@forEach
            dispatch(client) {
                if (active[playerId] == null) client.send(WrapperPlayServerEntityEquipment(scope.connection.entityId,
                    listOf(Equipment(PacketEquipmentSlot.MAIN_HAND, currentHand))))
            }
        }
    }

    internal fun validate(authorized: (Player, FirearmScopeTarget) -> Boolean) {
        drainDisconnected()
        active.entries.toList().forEach { (playerId, scope) ->
            val reload = scope.reload
            if (reload != null) {
                // Gameplay callback and all Paper reads stay on the owning thread.
                try {
                    if (!validReload(scope, reload)) clear(playerId)
                } catch (failure: Exception) {
                    clear(playerId)
                    Bukkit.getLogger().log(Level.WARNING, "ArcEvents reload validation failed player=$playerId", failure)
                }
            } else if (!valid(scope) || !authorized(scope.player, scope.target)) clear(playerId)
            else syncLaser(scope)
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
        // Restore authoritative inventory snapshots; this listener ignores sends after close.
        refreshInventories()
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
        if (closed || event.isCancelled) return
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
        val scope = active[playerId]?.takeIf { it.connection.entityId == event.user.entityId }
        val protocol = event.clientVersion.protocolVersion
        var changed = false
        fun project(item: PacketItemStack, held: Boolean = false): PacketItemStack {
            val reload = scope?.reload
            val sameReloadItem = reload == null || (item.type == reload.sourceItem.type &&
                item.getComponent(ComponentTypes.CUSTOM_DATA) == reload.sourceItem.getComponent(ComponentTypes.CUSTOM_DATA))
            val projected = if (held && scope != null && sameReloadItem) aimedItem(item, scope.frame, protocol) else firearmWithoutSwing(item, protocol)
            changed = changed || projected !== item
            return projected
        }
        when (event.packetType) {
            PacketType.Play.Server.SET_PLAYER_INVENTORY -> {
                val packet = WrapperPlayServerSetPlayerInventory(event)
                packet.stack = project(packet.stack, packet.slot == scope?.target?.heldSlot)
            }
            PacketType.Play.Server.SET_SLOT -> {
                val packet = WrapperPlayServerSetSlot(event)
                val held = scope != null && ((packet.windowId == 0 && packet.slot == HOTBAR_CONTAINER_START + scope.target.heldSlot) ||
                    (packet.windowId == -2 && packet.slot == scope.target.heldSlot))
                packet.item = project(packet.item, held)
            }
            PacketType.Play.Server.WINDOW_ITEMS -> {
                val packet = WrapperPlayServerWindowItems(event)
                val heldSlot = scope?.target?.heldSlot?.plus(HOTBAR_CONTAINER_START)
                packet.items = packet.items.mapIndexed { index, item -> project(item, packet.windowId == 0 && index == heldSlot) }
                packet.carriedItem.ifPresent { packet.setCarriedItem(project(it)) }
            }
            PacketType.Play.Server.SET_CURSOR_ITEM -> {
                val packet = WrapperPlayServerSetCursorItem(event)
                packet.stack = project(packet.stack)
            }
            else -> return
        }
        if (changed) event.markForReEncode(true)
    }

    internal fun rewriteAbilities(playerId: UUID, entityId: Int, packet: WrapperPlayServerPlayerAbilities) {
        val baseline = ScopeAbilities.read(packet)
        abilities[Client(playerId, entityId)] = baseline
        val scope = active[playerId]?.takeIf { it.connection.entityId == entityId } ?: return
        val frame = scope.frame
        if (frame.zoom == null || (!scope.preview && baseline.creative) || baseline.walkingSpeed <= 0f) return
        packet.fovModifier = baseline.packet(frame.zoom).fovModifier
    }

    private fun aimedItem(item: PacketItemStack, frame: Frame, protocol: Int): PacketItemStack =
        if (item.isEmpty) item else firearmWithoutSwing(
            item.copy().also { it.setComponent(ComponentTypes.ITEM_MODEL, ItemModel(ResourceLocation(frame.model))) }, protocol,
        )

    /** Only remote equipment is a charged crossbow: native two-handed pose, original gun model. */
    private fun observerItem(item: PacketItemStack, frame: Frame): PacketItemStack {
        if (item.isEmpty) return item
        val displayed = item.copy().also { it.setComponent(ComponentTypes.ITEM_MODEL, ItemModel(ResourceLocation(frame.model))) }
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

        internal fun open(displays: PaperPacketDisplays, laserEnabled: () -> Boolean, authorized: (Player, FirearmScopeTarget) -> Boolean): TttScopes = TttScopes(connection = { player ->
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
        }, authorized = authorized, createLaser = { TttScopeLaser(it, displays) }, laserEnabled = laserEnabled,
            refreshInventories = { Bukkit.getOnlinePlayers().forEach(Player::updateInventory) },
        ).also { scopes ->
            PacketEvents.getAPI().eventManager.registerListener(scopes)
            scopes.unregister = { PacketEvents.getAPI().eventManager.unregisterListener(scopes) }
            // Existing raw guns receive the component before the next click, including hot-load.
            scopes.refreshInventories()
        }
    }
}
