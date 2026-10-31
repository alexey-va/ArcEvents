package ru.ruscrafting.events.paper

import com.github.retrooper.packetevents.PacketEvents
import com.github.retrooper.packetevents.PacketEventsAPI
import com.github.retrooper.packetevents.event.PacketSendEvent
import com.github.retrooper.packetevents.event.UserDisconnectEvent
import com.github.retrooper.packetevents.manager.server.ServerManager
import com.github.retrooper.packetevents.manager.server.ServerVersion
import com.github.retrooper.packetevents.protocol.component.ComponentTypes
import com.github.retrooper.packetevents.protocol.item.ItemStack as PacketItemStack
import com.github.retrooper.packetevents.protocol.item.type.ItemTypes
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
import io.github.retrooper.packetevents.impl.netty.NettyManagerImpl
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.bukkit.GameMode
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.PlayerInventory
import ru.arc.core.ScheduledTask
import ru.ruscrafting.events.domain.FirearmId
import java.util.UUID
import java.util.concurrent.CompletableFuture

class TttScopesTest : StringSpec({
    "aiming restores the latest native abilities and current held item without ever replacing a helmet" {
        ScopeFixture().use { f ->
            val original = ScopeAbilities(false, false, false, false, .05f, .1f)
            f.baseline(original)
            f.scopes.toggle(f.player, f.target) shouldBe true
            f.tick(3)
            f.scopes.zoom(f.playerId) shouldBe 3f
            ScopeAbilities.read(f.packets.first() as WrapperPlayServerPlayerAbilities) shouldBe original.copy(walkingSpeed = -.3f)
            val latest = ScopeAbilities(true, true, true, false, .08f, .2f)
            val update = latest.packet()
            f.scopes.rewriteAbilities(f.playerId, f.entityId, update)
            ScopeAbilities.read(update) shouldBe latest.copy(walkingSpeed = -.6f)
            f.real = PacketItemStack.builder().type(ItemTypes.GOLDEN_HOE).build()
            f.scopes.clear(f.playerId)
            ScopeAbilities.read(f.packets[f.packets.lastIndex - 1] as WrapperPlayServerPlayerAbilities) shouldBe latest
            (f.packets.last() as WrapperPlayServerSetPlayerInventory).stack shouldBe f.real
            f.scopes.zoom(f.playerId) shouldBe null
            f.packets.filterIsInstance<WrapperPlayServerSetPlayerInventory>().any { it.slot == 39 } shouldBe false
            verify(exactly = 0) { f.player.walkSpeed = any() }
            verify(exactly = 0) { f.inventory.helmet }
            verify(exactly = 0) { f.inventory.helmet = any() }
        }
    }

    "match aiming still refuses missing baseline creative mode and old clients" {
        ScopeFixture().use { f ->
            f.scopes.toggle(f.player, f.target) shouldBe false
            f.baseline()
            f.gameMode = GameMode.CREATIVE
            f.scopes.toggle(f.player, f.target) shouldBe false
            f.packets.isEmpty() shouldBe true
        }
        ScopeFixture(protocol = ClientVersion.V_1_21_2.protocolVersion).use { f ->
            f.baseline()
            f.scopes.toggle(f.player, f.target.copy(matchId = null)) shouldBe false
        }
    }

    "creative preview works without a baseline and preserves native creative flags when abilities arrive" {
        ScopeFixture().use { f ->
            f.gameMode = GameMode.CREATIVE
            val preview = f.target.copy(matchId = null)
            f.scopes.toggle(f.player, preview) shouldBe true
            f.scopes.isPreview(f.playerId) shouldBe true
            f.tick(3)
            f.packets.any { it is WrapperPlayServerPlayerAbilities } shouldBe false
            val native = ScopeAbilities(true, true, true, true, .09f, .1f)
            val packet = native.packet()
            f.scopes.rewriteAbilities(f.playerId, f.entityId, packet)
            ScopeAbilities.read(packet) shouldBe native.copy(walkingSpeed = -.3f)
            f.scopes.clear(f.playerId)
            ScopeAbilities.read(f.packets[f.packets.lastIndex - 1] as WrapperPlayServerPlayerAbilities) shouldBe native
            verify(exactly = 0) { f.player.gameMode = any() }
            verify(exactly = 0) { f.player.walkSpeed = any() }
        }
    }

    "all twelve weapons raise in three ticks and preserve their distinct zoom profiles" {
        ScopeFixture().use { f ->
            f.baseline()
            for (id in FirearmId.entries) {
                f.packets.clear()
                f.scopes.toggle(f.player, f.target.copy(firearm = id)) shouldBe true
                f.models() shouldBe listOf("arc:event_sights/${id.name.lowercase()}_raise_1")
                f.tick(3)
                f.models() shouldBe listOf("raise_1", "raise_2", "raise_3", "aim").map { "arc:event_sights/${id.name.lowercase()}_$it" }
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

    "rapid toggles reverse the current pose and stale cancelled callbacks cannot advance a newer motion" {
        ScopeFixture().use { f ->
            f.baseline()
            f.scopes.toggle(f.player, f.target)
            f.tick(1)
            f.scopes.toggle(f.player, f.target) shouldBe true
            f.models().last().endsWith("raise_1") shouldBe true
            f.scopes.toggle(f.player, f.target) shouldBe true
            f.runCancelled()
            f.tick(2)
            f.models().map { it.substringAfter("vss_vintorez_") } shouldBe
                listOf("raise_1", "raise_2", "raise_1", "raise_2", "raise_3", "aim")
            f.scopes.zoom(f.playerId) shouldBe 3f
            f.scopes.toggle(f.player, f.target)
            f.tick(3)
            f.models().takeLast(4).map { it.substringAfter("vss_vintorez_") } shouldBe listOf("raise_3", "raise_2", "raise_1", "hip")
            f.scopes.zoom(f.playerId) shouldBe null
            f.scopes.isAiming(f.playerId) shouldBe false
            val native = ScopeAbilities(false, false, false, false, .05f, .1f).packet()
            f.scopes.rewriteAbilities(f.playerId, f.entityId, native)
            native.fovModifier shouldBe .1f
            val hip = f.filter(WrapperPlayServerSetPlayerInventory(2, f.real)) as WrapperPlayServerSetPlayerInventory
            hip.stack.getComponent(ComponentTypes.ITEM_MODEL).get().modelLocation.toString() shouldBe "arc:event_sights/vss_vintorez_hip"
            f.scopes.toggle(f.player, f.target)
            f.tick(3)
            f.models().last() shouldBe "arc:event_sights/vss_vintorez_aim"
            f.scopes.isAiming(f.playerId) shouldBe true
        }
    }

    "two immediate RMB presses return to hip and a third press cannot be cleared by the cancelled raise" {
        ScopeFixture().use { f ->
            f.baseline()
            f.scopes.toggle(f.player, f.target)
            f.scopes.toggle(f.player, f.target)
            f.scopes.zoom(f.playerId) shouldBe null
            f.models().last() shouldBe "arc:event_sights/vss_vintorez_hip"
            f.scopes.toggle(f.player, f.target)
            f.runCancelled()
            f.tick(3)
            f.scopes.zoom(f.playerId) shouldBe 3f
            f.models().last() shouldBe "arc:event_sights/vss_vintorez_aim"
        }
    }

    "native item updates keep ammo count and custom data while only the copied current-frame model changes" {
        ScopeFixture().use { f ->
            f.baseline()
            f.scopes.toggle(f.player, f.target)
            val data = NBTCompound().also { it.setTag("loaded_rounds", NBTInt(7)) }
            val native = f.real.copy().also { it.amount = 2; it.setComponent(ComponentTypes.CUSTOM_DATA, data) }
            val originalModel = native.getComponent(ComponentTypes.ITEM_MODEL)
            val update = f.filter(WrapperPlayServerSetPlayerInventory(2, native)) as WrapperPlayServerSetPlayerInventory
            update.stack.amount shouldBe 2
            update.stack.getComponent(ComponentTypes.CUSTOM_DATA).get() shouldBe data
            update.stack.getComponent(ComponentTypes.ITEM_MODEL).get().modelLocation.toString() shouldBe "arc:event_sights/vss_vintorez_raise_1"
            native.getComponent(ComponentTypes.ITEM_MODEL) shouldBe originalModel
            val slot = f.filter(WrapperPlayServerSetSlot(0, 11, 38, native)) as WrapperPlayServerSetSlot
            slot.item.getComponent(ComponentTypes.CUSTOM_DATA).get() shouldBe data
            slot.stateId shouldBe 11
            val items = List(46) { native }
            val full = f.filter(WrapperPlayServerWindowItems(0, 12, items, native)) as WrapperPlayServerWindowItems
            full.items[38].getComponent(ComponentTypes.ITEM_MODEL).get().modelLocation.toString() shouldBe "arc:event_sights/vss_vintorez_raise_1"
            full.items[5] shouldBe native
            val other = f.filter(WrapperPlayServerSetSlot(2, 13, 38, native)) as WrapperPlayServerSetSlot
            other.item shouldBe native
        }
    }

    "invalidation restores immediately and no delayed frame reaches an old slot or new scope" {
        ScopeFixture(queued = true).use { f ->
            f.baseline()
            f.scopes.toggle(f.player, f.target)
            f.runQueued()
            f.packets.clear()
            f.scopes.clear(f.playerId)
            f.scopes.toggle(f.player, f.target.copy(firearm = FirearmId.G36, heldSlot = 4))
            f.runCancelled()
            f.tick(3)
            f.runQueued()
            val slots = f.packets.filterIsInstance<WrapperPlayServerSetPlayerInventory>()
            slots.first().slot shouldBe 2
            slots.first().stack shouldBe f.real
            slots.drop(1).all { it.slot == 4 } shouldBe true
            f.models().last() shouldBe "arc:event_sights/g36_aim"
            f.scopes.zoom(f.playerId) shouldBe 1.15f
        }
    }

    "old connection packets and disconnect never replace the new baseline or clear its sight" {
        ScopeFixture().use { f ->
            f.baseline()
            f.scopes.toggle(f.player, f.target)
            f.scopes.clear(f.playerId)
            val oldId = f.entityId
            f.entityId = 8
            val current = ScopeAbilities(true, false, true, false, .08f, .2f)
            f.baseline(current)
            f.scopes.toggle(f.player, f.target.copy(firearm = FirearmId.AEK_971)) shouldBe true
            val oldPacket = ScopeAbilities(false, false, false, false, .03f, .4f).packet()
            f.scopes.rewriteAbilities(f.playerId, oldId, oldPacket)
            oldPacket.fovModifier shouldBe .4f
            val oldItem = f.filter(WrapperPlayServerSetPlayerInventory(2, f.real), oldId) as WrapperPlayServerSetPlayerInventory
            oldItem.stack.getComponent(ComponentTypes.ITEM_MODEL) shouldBe f.real.getComponent(ComponentTypes.ITEM_MODEL)
            f.scopes.onUserDisconnect(UserDisconnectEvent(mockk<User> {
                every { uuid } returns f.playerId
                every { entityId } returns oldId
            }))
            f.tick(3)
            f.scopes.zoom(f.playerId) shouldBe 1.35f
            f.packets.clear()
            f.scopes.clear(f.playerId)
            ScopeAbilities.read(f.packets.first() as WrapperPlayServerPlayerAbilities) shouldBe current
        }
    }

    "login packets and disconnects with a null UUID do not touch scope state" {
        ScopeFixture().use { f ->
            f.baseline()
            f.scopes.toggle(f.player, f.target)
            val user = mockk<User> { every { uuid } returns null }
            val event = mockk<PacketSendEvent>(relaxed = true) {
                every { this@mockk.user } returns user
                every { packetType } returns PacketType.Play.Server.PLAYER_ABILITIES
            }
            f.scopes.onPacketSend(event)
            f.scopes.onUserDisconnect(UserDisconnectEvent(user))
            f.scopes.zoom(f.playerId) shouldBe 3f
            verify(exactly = 0) { event.markForReEncode(any()) }
        }
    }

    "shutdown cancels animation and waits for accepted restore packets before completing" {
        ScopeFixture(queued = true).use { f ->
            f.baseline()
            f.scopes.toggle(f.player, f.target)
            val close = f.scopes.closeAsync()
            close.isDone shouldBe false
            f.tick(6)
            f.runCancelled()
            f.runQueued()
            close.join()
            f.packets.size shouldBe 2
            ScopeAbilities.read(f.packets.first() as WrapperPlayServerPlayerAbilities).walkingSpeed shouldBe .1f
            (f.packets.last() as WrapperPlayServerSetPlayerInventory).stack shouldBe f.real
            f.scopes.toggle(f.player, f.target) shouldBe false
        }
    }

    "shutdown owns the scheduled handle even after a network disconnect removed its active scope" {
        ScopeFixture().use { f ->
            f.baseline()
            f.scopes.toggle(f.player, f.target)
            f.pendingTimers() shouldBe 1
            f.scopes.onUserDisconnect(UserDisconnectEvent(mockk<User> {
                every { uuid } returns f.playerId
                every { entityId } returns f.entityId
            }))
            val count = f.packets.size
            f.scopes.closeAsync().join()
            f.pendingTimers() shouldBe 0
            f.runCancelled()
            f.tick(3)
            f.packets.size shouldBe count
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
    var gameMode = GameMode.ADVENTURE
    var real = PacketItemStack.builder().type(ItemTypes.NETHERITE_SHOVEL).build()
    private val heldNative = mockk<ItemStack>()
    val packets = mutableListOf<PacketWrapper<*>>()
    private val queue = mutableListOf<() -> Unit>()
    private var now = 0L
    private val timers = mutableListOf<Timer>()
    val scopes: TttScopes

    private class Timer(override val id: Int, val due: Long, val action: () -> Unit) : ScheduledTask {
        override var isCancelled = false
        var ran = false
        override fun cancel() { isCancelled = true }
    }

    init {
        every { player.uniqueId } returns playerId
        every { player.isOnline } returns true
        every { player.gameMode } answers { gameMode }
        every { player.inventory } returns inventory
        every { inventory.itemInMainHand } returns heldNative
        every { inventory.getItem(any<Int>()) } returns heldNative
        val execute: (() -> Unit) -> CompletableFuture<Unit> = { action ->
            val completion = CompletableFuture<Unit>()
            val run = { action(); completion.complete(Unit); Unit }
            if (queued) queue += run else run()
            completion
        }
        scopes = TttScopes({ ScopeConnection(entityId, protocol, packets::add, execute) }, { real }, schedule = { ticks, action ->
            ticks shouldBe 1L
            Timer(timers.size, now + ticks, action).also(timers::add)
        })
    }

    fun baseline(value: ScopeAbilities = ScopeAbilities(false, false, false, false, .05f, .1f)) =
        scopes.rewriteAbilities(playerId, entityId, value.packet())

    fun models(): List<String> = packets.filterIsInstance<WrapperPlayServerSetPlayerInventory>().mapNotNull {
        it.stack.getComponent(ComponentTypes.ITEM_MODEL).orElse(null)?.modelLocation?.toString()?.takeIf { id -> id.startsWith("arc:event_sights/") }
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

    fun tick(count: Int) = repeat(count) {
        now++
        timers.filter { !it.ran && !it.isCancelled && it.due <= now }.toList().forEach { timer -> timer.ran = true; timer.action() }
    }

    fun runCancelled() = timers.filter { it.isCancelled && !it.ran }.toList().forEach { it.ran = true; it.action() }
    fun pendingTimers(): Int = timers.count { !it.ran && !it.isCancelled }

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
