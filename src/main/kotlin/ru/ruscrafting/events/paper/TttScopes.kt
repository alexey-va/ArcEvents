package ru.ruscrafting.events.paper

import com.github.retrooper.packetevents.PacketEvents
import com.github.retrooper.packetevents.event.PacketListenerAbstract
import com.github.retrooper.packetevents.event.PacketListenerPriority
import com.github.retrooper.packetevents.event.PacketSendEvent
import com.github.retrooper.packetevents.event.UserDisconnectEvent
import com.github.retrooper.packetevents.netty.channel.ChannelHelper
import com.github.retrooper.packetevents.protocol.packettype.PacketType
import com.github.retrooper.packetevents.protocol.player.ClientVersion
import com.github.retrooper.packetevents.protocol.player.EquipmentSlot as PacketEquipmentSlot
import com.github.retrooper.packetevents.protocol.item.ItemStack as PacketItemStack
import com.github.retrooper.packetevents.protocol.component.ComponentTypes
import com.github.retrooper.packetevents.protocol.component.builtin.item.ItemModel
import com.github.retrooper.packetevents.resources.ResourceLocation
import com.github.retrooper.packetevents.wrapper.PacketWrapper
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityEquipment
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerPlayerAbilities
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSetPlayerInventory
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSetSlot
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerWindowItems
import io.github.retrooper.packetevents.util.SpigotConversionUtil
import io.papermc.paper.datacomponent.DataComponentTypes
import io.papermc.paper.datacomponent.item.Equippable
import net.kyori.adventure.key.Key
import org.bukkit.GameMode
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.inventory.ItemStack
import ru.ruscrafting.events.domain.FirearmId
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap

internal fun firearmScopeZoom(id: FirearmId): Float = when (id) {
    FirearmId.VSS_VINTOREZ -> 3f
    FirearmId.MCMILLAN -> 5f
    FirearmId.AEK_971, FirearmId.RPL_20, FirearmId.VEPR_12 -> 1.35f
    FirearmId.FLINTLOCK, FirearmId.REVOLVER, FirearmId.HAND_CANNON, FirearmId.DOUBLE_BARREL,
    FirearmId.FIVE_SEVEN, FirearmId.G36, FirearmId.M1_GARAND -> 1.15f
}

internal data class FirearmScopeTarget(val matchId: String, val firearm: FirearmId, val heldSlot: Int)

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
        // Only the client's denominator changes; its movement attribute stays authoritative.
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

/** Paper owns admission/lifecycle. Netty only sees detached packet items, identities and abilities. */
class TttScopes internal constructor(
    private val connection: (Player) -> ScopeConnection?,
    private val packetItem: (ItemStack) -> PacketItemStack = SpigotConversionUtil::fromBukkitItemStack,
    private val overlayItem: (ItemStack?) -> ItemStack = ::scopeHelmet,
) : PacketListenerAbstract(PacketListenerPriority.HIGHEST) {
    private data class Client(val playerId: UUID, val entityId: Int)
    private data class Scope(
        val player: Player,
        val target: FirearmScopeTarget,
        val zoom: Float,
        val connection: ScopeConnection,
        val model: ItemModel,
        val overlay: PacketItemStack?,
    )

    private val active = ConcurrentHashMap<UUID, Scope>()
    private val abilities = ConcurrentHashMap<Client, ScopeAbilities>()
    private val pending = ConcurrentHashMap.newKeySet<CompletableFuture<Unit>>()
    private var closed = false
    private var closeCompletion: CompletableFuture<Unit>? = null
    private var unregister: () -> Unit = {}

    fun zoom(playerId: UUID): Float? = active[playerId]?.zoom

    internal fun toggle(player: Player, target: FirearmScopeTarget): Boolean {
        val playerId = player.uniqueId
        if (active.containsKey(playerId)) {
            clear(playerId)
            return true
        }
        val zoom = firearmScopeZoom(target.firearm)
        if (closed || !player.isOnline || player.gameMode !in setOf(GameMode.SURVIVAL, GameMode.ADVENTURE)) return false
        val client = connection(player) ?: return false
        val clientId = Client(playerId, client.entityId)
        val baseline = abilities[clientId] ?: return false
        if (client.protocol < ClientVersion.V_1_21_4.protocolVersion || baseline.creative || baseline.walkingSpeed <= 0f) return false
        val held = packetItem(player.inventory.itemInMainHand)
        val model = ItemModel(ResourceLocation("arc:event_sights/${target.firearm.name.lowercase()}_aim"))
        val scope = Scope(player, target, zoom, client, model,
            if (zoom >= 3f) packetItem(overlayItem(player.inventory.helmet)) else null)
        active[playerId] = scope
        dispatch(client) {
            if (active[playerId] !== scope) return@dispatch
            val latest = abilities[clientId] ?: return@dispatch
            client.send(latest.packet(zoom))
            client.send(WrapperPlayServerSetPlayerInventory(target.heldSlot, aimedItem(held, scope)))
            scope.overlay?.let { client.send(WrapperPlayServerSetPlayerInventory(HELMET_INVENTORY_SLOT, it)) }
        }
        return true
    }

    fun clear(playerId: UUID) {
        val scope = active.remove(playerId) ?: return
        val realHeld = packetItem(scope.player.inventory.getItem(scope.target.heldSlot) ?: ItemStack.of(Material.AIR))
        val realHelmet = if (scope.overlay == null) null else packetItem(scope.player.inventory.helmet ?: ItemStack.of(Material.AIR))
        dispatch(scope.connection) {
            // Connection ordering restores every old slot before a queued newer entry claims its own slots.
            abilities[Client(playerId, scope.connection.entityId)]?.let { scope.connection.send(it.packet()) }
            scope.connection.send(WrapperPlayServerSetPlayerInventory(scope.target.heldSlot, realHeld))
            realHelmet?.let { scope.connection.send(WrapperPlayServerSetPlayerInventory(HELMET_INVENTORY_SLOT, it)) }
        }
    }

    internal fun validate(authorized: (Player, FirearmScopeTarget) -> Boolean) {
        active.entries.toList().forEach { (playerId, scope) ->
            if (!scope.player.isOnline || scope.player.gameMode !in setOf(GameMode.SURVIVAL, GameMode.ADVENTURE) ||
                abilities[Client(playerId, scope.connection.entityId)]?.let { it.creative || it.walkingSpeed <= 0f } != false ||
                !authorized(scope.player, scope.target)
            ) clear(playerId)
        }
    }

    fun clearAll() = active.keys.toList().forEach(::clear)

    fun closeAsync(): CompletableFuture<Unit> {
        closeCompletion?.let { return it }
        closed = true
        clearAll()
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
        val playerId = event.user.uuid
        val entityId = event.user.entityId
        active[playerId]?.takeIf { it.connection.entityId == entityId }?.let { active.remove(playerId, it) }
        abilities.remove(Client(playerId, entityId))
    }

    override fun onPacketSend(event: PacketSendEvent) {
        if (event.isCancelled) return
        val playerId = event.user.uuid
        if (event.packetType == PacketType.Play.Server.PLAYER_ABILITIES) {
            rewriteAbilities(playerId, event.user.entityId, WrapperPlayServerPlayerAbilities(event))
            event.markForReEncode(true)
            return
        }
        val scope = active[playerId]?.takeIf { it.connection.entityId == event.user.entityId } ?: return
        when (event.packetType) {
            PacketType.Play.Server.SET_PLAYER_INVENTORY -> {
                val packet = WrapperPlayServerSetPlayerInventory(event)
                if (packet.slot == scope.target.heldSlot) packet.stack = aimedItem(packet.stack, scope)
                else if (packet.slot == HELMET_INVENTORY_SLOT && scope.overlay != null) packet.stack = scope.overlay
            }
            PacketType.Play.Server.SET_SLOT -> {
                val packet = WrapperPlayServerSetSlot(event)
                if ((packet.windowId == 0 && packet.slot == HELMET_CONTAINER_SLOT) ||
                    (packet.windowId == -2 && packet.slot == HELMET_INVENTORY_SLOT)
                ) scope.overlay?.let { packet.item = it }
                if ((packet.windowId == 0 && packet.slot == HOTBAR_CONTAINER_START + scope.target.heldSlot) ||
                    (packet.windowId == -2 && packet.slot == scope.target.heldSlot)
                ) packet.item = aimedItem(packet.item, scope)
            }
            PacketType.Play.Server.WINDOW_ITEMS -> {
                val packet = WrapperPlayServerWindowItems(event)
                val heldSlot = HOTBAR_CONTAINER_START + scope.target.heldSlot
                if (packet.windowId == 0 && packet.items.size > heldSlot) {
                    packet.items = packet.items.toMutableList().also {
                        it[heldSlot] = aimedItem(it[heldSlot], scope)
                        scope.overlay?.let { overlay -> it[HELMET_CONTAINER_SLOT] = overlay }
                    }
                }
            }
            PacketType.Play.Server.ENTITY_EQUIPMENT -> {
                val packet = WrapperPlayServerEntityEquipment(event)
                if (packet.entityId == scope.connection.entityId) {
                    packet.equipment = packet.equipment.map { equipment ->
                        val replacement = when (equipment.slot) {
                            PacketEquipmentSlot.MAIN_HAND -> aimedItem(equipment.item, scope)
                            PacketEquipmentSlot.HELMET -> scope.overlay
                            else -> null
                        }
                        if (replacement == null) equipment else com.github.retrooper.packetevents.protocol.player.Equipment(equipment.slot, replacement)
                    }
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
        // A native creative switch must stop overriding abilities immediately, before Paper's safety tick.
        if (baseline.creative || baseline.walkingSpeed <= 0f) return
        packet.fovModifier = baseline.packet(scope.zoom).fovModifier
    }

    private fun aimedItem(item: PacketItemStack, scope: Scope): PacketItemStack =
        if (item.isEmpty) item else item.copy().also { it.setComponent(ComponentTypes.ITEM_MODEL, scope.model) }

    companion object {
        private const val HELMET_INVENTORY_SLOT = 39
        private const val HELMET_CONTAINER_SLOT = 5
        private const val HOTBAR_CONTAINER_START = 36

        fun open(): TttScopes = TttScopes(connection = { player ->
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
        }).also { scopes ->
            PacketEvents.getAPI().eventManager.registerListener(scopes)
            scopes.unregister = { PacketEvents.getAPI().eventManager.unregisterListener(scopes) }
        }
    }
}

private fun scopeHelmet(helmet: ItemStack?): ItemStack = (helmet?.clone() ?: ItemStack.of(Material.PAPER)).also { item ->
    val equipped = item.getData(DataComponentTypes.EQUIPPABLE)?.takeIf { it.slot() == EquipmentSlot.HEAD }
        ?.toBuilder() ?: Equippable.equippable(EquipmentSlot.HEAD)
    item.setData(DataComponentTypes.EQUIPPABLE, equipped.cameraOverlay(Key.key("arc:misc/event_scope")).build())
}
