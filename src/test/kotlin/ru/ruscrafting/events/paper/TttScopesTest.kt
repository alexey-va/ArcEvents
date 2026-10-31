package ru.ruscrafting.events.paper

import com.github.retrooper.packetevents.PacketEvents
import com.github.retrooper.packetevents.PacketEventsAPI
import com.github.retrooper.packetevents.event.PacketSendEvent
import com.github.retrooper.packetevents.event.UserDisconnectEvent
import com.github.retrooper.packetevents.manager.server.ServerVersion
import com.github.retrooper.packetevents.manager.server.ServerManager
import com.github.retrooper.packetevents.protocol.item.ItemStack as PacketItemStack
import com.github.retrooper.packetevents.protocol.item.type.ItemTypes
import com.github.retrooper.packetevents.protocol.component.ComponentTypes
import com.github.retrooper.packetevents.protocol.nbt.NBTCompound
import com.github.retrooper.packetevents.protocol.nbt.NBTInt
import com.github.retrooper.packetevents.protocol.packettype.PacketType
import com.github.retrooper.packetevents.protocol.player.ClientVersion
import com.github.retrooper.packetevents.protocol.player.User
import com.github.retrooper.packetevents.wrapper.PacketWrapper
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerPlayerAbilities
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSetPlayerInventory
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSetSlot
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerWindowItems
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.github.retrooper.packetevents.impl.netty.NettyManagerImpl
import org.bukkit.GameMode
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.PlayerInventory
import ru.ruscrafting.events.domain.FirearmId
import java.util.UUID
import java.util.concurrent.CompletableFuture

class TttScopesTest : StringSpec({
    "scope sends only client state and restores latest native abilities and current real helmet" {
        ScopeFixture().use { f ->
            val original = ScopeAbilities(false, false, false, false, .05f, .1f)
            f.scopes.rewriteAbilities(f.playerId, f.entityId, original.packet())
            f.scopes.toggle(f.player, f.target) shouldBe true
            f.scopes.zoom(f.playerId) shouldBe 3f
            ScopeAbilities.read(f.packets[0] as WrapperPlayServerPlayerAbilities) shouldBe original.copy(walkingSpeed = -.3f)
            (f.packets[1] as WrapperPlayServerSetPlayerInventory).slot shouldBe 2
            (f.packets[2] as WrapperPlayServerSetPlayerInventory).slot shouldBe 39
            (f.packets[2] as WrapperPlayServerSetPlayerInventory).stack shouldBe f.overlay

            val latest = ScopeAbilities(true, true, true, false, .08f, .2f)
            val update = latest.packet()
            f.scopes.rewriteAbilities(f.playerId, f.entityId, update)
            ScopeAbilities.read(update) shouldBe latest.copy(walkingSpeed = -.6f)
            f.realHelmet = mockk()
            f.scopes.clear(f.playerId)

            f.scopes.zoom(f.playerId) shouldBe null
            ScopeAbilities.read(f.packets[3] as WrapperPlayServerPlayerAbilities) shouldBe latest
            (f.packets[5] as WrapperPlayServerSetPlayerInventory).stack shouldBe f.real
            f.scopes.clear(f.playerId)
            f.packets.size shouldBe 6
            verify(exactly = 0) { f.player.walkSpeed = any() }
            verify(exactly = 0) { f.inventory.helmet = any() }
        }
    }

    "scope admission fails closed without a native baseline or for creative and old clients" {
        ScopeFixture().use { f ->
            f.scopes.toggle(f.player, f.target) shouldBe false
            f.scopes.rewriteAbilities(f.playerId, f.entityId, ScopeAbilities(false, false, false, false, .05f, .1f).packet())
            f.gameMode = GameMode.CREATIVE
            f.scopes.toggle(f.player, f.target) shouldBe false
            f.gameMode = GameMode.ADVENTURE
            f.packets.size shouldBe 0
            f.scopes.toggle(f.player, f.target.copy(firearm = FirearmId.MCMILLAN)) shouldBe true
            (f.packets[0] as WrapperPlayServerPlayerAbilities).fovModifier shouldBe (.1f * 5 / -3)
            f.scopes.validate { _, _ -> false }
            f.scopes.zoom(f.playerId) shouldBe null
        }
        ScopeFixture(protocol = ClientVersion.V_1_21_2.protocolVersion).use { f ->
            f.scopes.rewriteAbilities(f.playerId, f.entityId, ScopeAbilities(false, false, false, false, .05f, .1f).packet())
            f.scopes.toggle(f.player, f.target) shouldBe false
        }
    }

    "every firearm has its aimed model and only sniper sights replace the helmet" {
        ScopeFixture().use { f ->
            f.scopes.rewriteAbilities(f.playerId, f.entityId, ScopeAbilities(false, false, false, false, .05f, .1f).packet())
            for (id in FirearmId.entries) {
                f.packets.clear()
                f.scopes.toggle(f.player, f.target.copy(firearm = id)) shouldBe true
                val slots = f.packets.filterIsInstance<WrapperPlayServerSetPlayerInventory>()
                slots.first().stack.getComponent(ComponentTypes.ITEM_MODEL).get().modelLocation.toString() shouldBe
                    "arc:event_sights/${id.name.lowercase()}_aim"
                slots.any { it.slot == 39 } shouldBe (id in setOf(FirearmId.VSS_VINTOREZ, FirearmId.MCMILLAN))
                firearmScopeZoom(id) shouldBe when (id) {
                    FirearmId.VSS_VINTOREZ -> 3f
                    FirearmId.MCMILLAN -> 5f
                    FirearmId.AEK_971, FirearmId.RPL_20, FirearmId.VEPR_12 -> 1.35f
                    else -> 1.15f
                }
                f.scopes.clear(f.playerId)
            }
        }
    }

    "native held-item updates retain ammo custom data and count while only the copied model changes" {
        ScopeFixture().use { f ->
            f.scopes.rewriteAbilities(f.playerId, f.entityId, ScopeAbilities(false, false, false, false, .05f, .1f).packet())
            f.scopes.toggle(f.player, f.target)
            val data = NBTCompound().also { it.setTag("loaded_rounds", NBTInt(7)) }
            val native = f.real.copy().also {
                it.amount = 2
                it.setComponent(ComponentTypes.CUSTOM_DATA, data)
            }
            val originalModel = native.getComponent(ComponentTypes.ITEM_MODEL)
            val update = f.filter(WrapperPlayServerSetPlayerInventory(2, native)) as WrapperPlayServerSetPlayerInventory
            update.stack.amount shouldBe 2
            update.stack.getComponent(ComponentTypes.CUSTOM_DATA).get() shouldBe data
            update.stack.getComponent(ComponentTypes.ITEM_MODEL).get().modelLocation.toString() shouldBe "arc:event_sights/vss_vintorez_aim"
            native.getComponent(ComponentTypes.ITEM_MODEL) shouldBe originalModel
            val slot = f.filter(WrapperPlayServerSetSlot(0, 11, 38, native)) as WrapperPlayServerSetSlot
            slot.item.getComponent(ComponentTypes.CUSTOM_DATA).get() shouldBe data
            slot.stateId shouldBe 11
            val other = f.filter(WrapperPlayServerSetPlayerInventory(3, native)) as WrapperPlayServerSetPlayerInventory
            other.stack shouldBe native
        }
    }

    "native helmet updates stay client scoped without modifying other inventory slots" {
        ScopeFixture().use { f ->
            f.scopes.rewriteAbilities(f.playerId, f.entityId, ScopeAbilities(false, false, false, false, .05f, .1f).packet())
            f.scopes.toggle(f.player, f.target)
            val direct = f.filter(WrapperPlayServerSetPlayerInventory(39, f.real)) as WrapperPlayServerSetPlayerInventory
            direct.stack shouldBe f.overlay
            val slot = f.filter(WrapperPlayServerSetSlot(0, 7, 5, f.real)) as WrapperPlayServerSetSlot
            slot.item shouldBe f.overlay
            slot.stateId shouldBe 7
            val unrelated = f.filter(WrapperPlayServerSetSlot(2, 8, 5, f.real)) as WrapperPlayServerSetSlot
            unrelated.item shouldBe f.real
            val items = List(46) { f.real }
            val full = f.filter(WrapperPlayServerWindowItems(0, 9, items, f.real)) as WrapperPlayServerWindowItems
            full.items[5] shouldBe f.overlay
            full.items[4] shouldBe f.real
            items[5] shouldBe f.real
            val outside = f.filter(WrapperPlayServerWindowItems(2, 9, items, f.real)) as WrapperPlayServerWindowItems
            outside.items[5] shouldBe f.real
        }
    }

    "queued stale entry cannot outlive exit and shutdown waits for the restore invocation" {
        ScopeFixture(queued = true).use { f ->
            f.scopes.rewriteAbilities(f.playerId, f.entityId, ScopeAbilities(false, false, false, false, .05f, .1f).packet())
            f.scopes.toggle(f.player, f.target)
            f.realHelmet = mockk()
            f.scopes.clear(f.playerId)
            val close = f.scopes.closeAsync()
            close.isDone shouldBe false
            f.runQueued()
            close.join()
            f.packets.size shouldBe 3
            ScopeAbilities.read(f.packets.first() as WrapperPlayServerPlayerAbilities).walkingSpeed shouldBe .1f
            (f.packets.last() as WrapperPlayServerSetPlayerInventory).stack shouldBe f.real
            f.scopes.toggle(f.player, f.target) shouldBe false
        }
    }

    "queued sniper to iron transition restores the old helmet and hotbar slot before entering the new sight" {
        ScopeFixture(queued = true).use { f ->
            f.scopes.rewriteAbilities(f.playerId, f.entityId, ScopeAbilities(false, false, false, false, .05f, .1f).packet())
            f.scopes.toggle(f.player, f.target)
            f.runQueued()
            f.packets.clear()
            f.realHelmet = mockk()
            f.scopes.clear(f.playerId)
            f.scopes.toggle(f.player, f.target.copy(firearm = FirearmId.G36, heldSlot = 4))
            f.runQueued()
            val slots = f.packets.filterIsInstance<WrapperPlayServerSetPlayerInventory>()
            slots.map { it.slot } shouldBe listOf(2, 39, 4)
            slots[0].stack shouldBe f.real
            slots[1].stack shouldBe f.real
            slots[2].stack.getComponent(ComponentTypes.ITEM_MODEL).get().modelLocation.toString() shouldBe "arc:event_sights/g36_aim"
            f.scopes.zoom(f.playerId) shouldBe 1.15f
        }
    }

    "an old connection's packets and disconnect cannot replace the new connection baseline or clear its sight" {
        ScopeFixture().use { f ->
            f.scopes.rewriteAbilities(f.playerId, f.entityId, ScopeAbilities(false, false, false, false, .05f, .1f).packet())
            f.scopes.toggle(f.player, f.target)
            f.scopes.clear(f.playerId)
            val oldId = f.entityId
            f.entityId = 8
            val current = ScopeAbilities(true, false, true, false, .08f, .2f)
            f.scopes.rewriteAbilities(f.playerId, f.entityId, current.packet())
            f.scopes.toggle(f.player, f.target.copy(firearm = FirearmId.AEK_971)) shouldBe true
            val oldPacket = ScopeAbilities(false, false, false, false, .03f, .4f).packet()
            f.scopes.rewriteAbilities(f.playerId, oldId, oldPacket)
            oldPacket.fovModifier shouldBe .4f
            val oldItem = f.filter(WrapperPlayServerSetPlayerInventory(2, f.real), oldId) as WrapperPlayServerSetPlayerInventory
            oldItem.stack.getComponent(ComponentTypes.ITEM_MODEL) shouldBe f.real.getComponent(ComponentTypes.ITEM_MODEL)
            val oldUser = mockk<User> {
                every { uuid } returns f.playerId
                every { entityId } returns oldId
            }
            f.scopes.onUserDisconnect(UserDisconnectEvent(oldUser))
            f.scopes.zoom(f.playerId) shouldBe 1.35f
            f.packets.clear()
            f.scopes.clear(f.playerId)
            ScopeAbilities.read(f.packets.first() as WrapperPlayServerPlayerAbilities) shouldBe current
        }
    }
})

private class ScopeFixture(private val queued: Boolean = false, protocol: Int = ClientVersion.V_1_21_11.protocolVersion) : AutoCloseable {
    private val previousApi = PacketEvents.getAPI()
    init {
        PacketEvents.setAPI(object : PacketEventsAPI<Any>() {
            private val netty = NettyManagerImpl()
            override fun getServerManager() = ServerManager { ServerVersion.V_1_21_11 }
            override fun getNettyManager() = netty
            override fun getPlugin(): Any = this
            override fun getProtocolManager(): com.github.retrooper.packetevents.manager.protocol.ProtocolManager = error("No live protocol in codec test")
            override fun getPlayerManager(): com.github.retrooper.packetevents.manager.player.PlayerManager = error("No player manager in codec test")
            override fun getInjector(): com.github.retrooper.packetevents.injector.ChannelInjector = error("No channel injection in codec test")
            override fun load() = Unit
            override fun init() = Unit
            override fun terminate() = Unit
            override fun isLoaded() = true
            override fun isInitialized() = true
            override fun isTerminated() = false
        })
    }
    val playerId = UUID.randomUUID()
    var entityId = 7
    val target = FirearmScopeTarget("match", FirearmId.VSS_VINTOREZ, 2)
    val player = mockk<Player>()
    val inventory = mockk<PlayerInventory>(relaxed = true)
    private val initialHelmet = mockk<ItemStack>()
    var realHelmet = initialHelmet
    var gameMode = GameMode.ADVENTURE
    private val initial = PacketItemStack.builder().type(ItemTypes.IRON_HELMET).build()
    val real = PacketItemStack.builder().type(ItemTypes.DIAMOND_HELMET).build()
    val overlay = PacketItemStack.builder().type(ItemTypes.PAPER).build()
    private val overlayNative = mockk<ItemStack>()
    private val heldNative = mockk<ItemStack>()
    val packets = mutableListOf<PacketWrapper<*>>()
    private val queue = mutableListOf<() -> Unit>()
    val scopes: TttScopes

    init {
        every { player.uniqueId } returns playerId
        every { player.isOnline } returns true
        every { player.gameMode } answers { gameMode }
        every { player.inventory } returns inventory
        every { inventory.helmet } answers { realHelmet }
        every { inventory.itemInMainHand } returns heldNative
        every { inventory.getItem(any<Int>()) } returns heldNative
        val execute: (() -> Unit) -> CompletableFuture<Unit> = { action ->
            val completion = CompletableFuture<Unit>()
            val run = { action(); completion.complete(Unit); Unit }
            if (queued) queue += run else run()
            completion
        }
        scopes = TttScopes({ ScopeConnection(entityId, protocol, packets::add, execute) }, { when (it) {
            overlayNative -> overlay
            initialHelmet -> initial
            else -> real
        } }, { overlayNative })
    }

    fun filter(packet: PacketWrapper<*>, sourceEntityId: Int = entityId): PacketWrapper<*> {
        var current = packet
        val event = mockk<PacketSendEvent>(relaxed = true)
        every { event.packetType } returns requireNotNull(packet.packetTypeData.packetType)
        every { event.user.uuid } returns playerId
        every { event.user.entityId } returns sourceEntityId
        every { event.clientVersion } returns ClientVersion.V_1_21_11
        every { event.serverVersion } returns ServerVersion.V_1_21_11
        every { event.lastUsedWrapper } answers { current }
        every { event.lastUsedWrapper = any() } answers { current = firstArg() }
        scopes.onPacketSend(event)
        return current
    }

    fun runQueued() {
        while (queue.isNotEmpty()) queue.removeAt(0).invoke()
    }

    override fun close() {
        val close = scopes.closeAsync()
        runQueued()
        close.join()
        PacketEvents.setAPI(previousApi)
    }
}
