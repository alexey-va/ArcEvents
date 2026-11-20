package ru.ruscrafting.events.paper.apocalypse

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import net.kyori.adventure.text.Component
import org.bukkit.GameMode
import org.bukkit.Material
import org.bukkit.World
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.event.player.PlayerCommandPreprocessEvent
import org.bukkit.event.player.PlayerTeleportEvent
import org.bukkit.event.player.PlayerTeleportEvent.TeleportCause
import org.bukkit.NamespacedKey
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType
import ru.arc.core.LifecycleTaskScope
import ru.arc.core.PaperArcRuntime
import ru.arc.core.Tasks
import ru.arc.paper.playerstate.PaperPlayerStateCodec
import ru.arc.paper.playerstate.PaperPlayerStateService
import ru.arc.paper.teleport.ScopedTeleportAuthorizer
import ru.arc.paper.testing.MockBukkitTestRuntime
import ru.arc.redis.InMemoryRedis
import ru.ruscrafting.events.config.ArcEventsConfig
import ru.ruscrafting.events.config.ArcEventsLocale
import ru.ruscrafting.events.config.NetworkSettings
import ru.ruscrafting.events.config.NodeMode
import ru.ruscrafting.events.domain.apocalypse.ApocalypseLayout
import ru.ruscrafting.events.domain.apocalypse.ApocalypsePoint
import ru.ruscrafting.events.domain.apocalypse.ApocalypseWorldLayout
import ru.ruscrafting.events.paper.EventWork
import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.CompletableFuture

class ApocalypseColdStartTest : FunSpec({
    test("staff may visit the expedition with their own inventory and a later login still reconciles real escrow") {
        MockBukkitTestRuntime.open().use { paper ->
            val plugin = paper.createSimplePlugin("ExpeditionVisit")
            val home = paper.addSimpleWorld("world")
            val expedition = paper.addSimpleWorld("arcevents_apocalypse_v2")
            PaperArcRuntime.installScheduling(plugin)
            val work = ColdStartWork()
            try {
                val visitor = paper.addPlayer("Visitor")
                visitor.addAttachment(plugin, "arcevents.admin", true)
                visitor.gameMode = GameMode.CREATIVE
                val veteran = paper.addPlayer("Veteran")
                veteran.addAttachment(plugin, "arcevents.admin", true)
                val ordinary = paper.addPlayer("Ordinary")
                val occupied = paper.addPlayer("InOtherEvent")
                occupied.addAttachment(plugin, "arcevents.admin", true)
                val journal = ApocalypseStorage(plugin.dataFolder.toPath())
                val session = UUID.randomUUID().toString()
                veteran.inventory.setItem(0, ItemStack.of(Material.DIAMOND, 4))
                val previousOrigin = PaperPlayerStateService().captureEnvelope(veteran, 1)
                val receipt = ApocalypsePlayerRecord(veteran.uniqueId.toString(), "parkour", ApocalypsePlayerPhase.PARKED,
                    previousOrigin, null, nativeSession = session)
                journal.save(receipt)
                veteran.inventory.setItem(0, ItemStack.of(Material.EMERALD, 9))
                val marker = NamespacedKey(plugin, "apocalypse_admin_visit")
                val native = NamespacedKey(plugin, "apocalypse_session")
                val arcadeTeleport = ScopedTeleportAuthorizer()
                val settings = coldSettings(NodeMode.HOST)
                ApocalypseRuntime(plugin, { settings }, coldLocale(), mockk(), work, InMemoryRedis(), mockk(),
                    occupied = { it == occupied.uniqueId }, queued = { CompletableFuture.completedFuture(null) },
                    returnToOrigin = { _, _ -> false }, sourceLayout = testLayout(), chunkTickets = mockk(),
                    internalTeleport = arcadeTeleport::isAuthorized).use { runtime ->
                    runtime.start()
                    runtime.useTestWorld(expedition)
                    paper.awaitHostJoins(runtime)
                    ordinary.teleport(expedition.spawnLocation, TeleportCause.COMMAND) shouldBe false
                    occupied.teleport(expedition.spawnLocation, TeleportCause.COMMAND) shouldBe false
                    val captured = expedition.spawnLocation
                    arcadeTeleport.authorize(ordinary.uniqueId, captured) { ordinary.teleport(captured) } shouldBe false
                    arcadeTeleport.authorize(occupied.uniqueId, captured) {
                        occupied.persistentDataContainer.set(native, PersistentDataType.STRING, session)
                        occupied.teleport(captured) shouldBe false
                        occupied.persistentDataContainer.remove(native)
                        occupied.teleport(captured.clone().apply { yaw += 18f }) shouldBe false
                        occupied.persistentDataContainer.has(marker) shouldBe false
                        occupied.teleport(captured)
                    } shouldBe true
                    occupied.persistentDataContainer.get(marker, PersistentDataType.STRING) shouldBe expedition.uid.toString()
                    var veto = true
                    plugin.server.pluginManager.registerEvent(PlayerTeleportEvent::class.java, object : Listener {}, EventPriority.HIGHEST,
                        { _, event ->
                            val teleport = event as PlayerTeleportEvent
                            if (veto && teleport.player == visitor) teleport.isCancelled = true
                        }, plugin)
                    visitor.teleport(expedition.spawnLocation, TeleportCause.COMMAND) shouldBe false
                    visitor.persistentDataContainer.has(marker) shouldBe false
                    veto = false
                    visitor.teleport(expedition.spawnLocation, TeleportCause.COMMAND) shouldBe true
                    visitor.persistentDataContainer.get(marker, PersistentDataType.STRING) shouldBe expedition.uid.toString()
                    visitor.gameMode shouldBe GameMode.CREATIVE
                    runtime.isPlaying(visitor.uniqueId) shouldBe false
                    journal.load(visitor.uniqueId) shouldBe null
                    paper.callEvent(BlockBreakEvent(expedition.getBlockAt(0, 64, 0), visitor)).isCancelled shouldBe false
                    veteran.teleport(visitor, TeleportCause.COMMAND) shouldBe true
                    visitor.teleport(home.spawnLocation, TeleportCause.COMMAND) shouldBe true
                    visitor.persistentDataContainer.has(marker) shouldBe false
                    veteran.disconnect() shouldBe true
                    veteran.recalculatePermissions()
                    veteran.reconnect() shouldBe true
                    runtime.isBusy(veteran.uniqueId) shouldBe false
                    paper.awaitHostJoins(runtime)
                    veteran.world shouldBe expedition
                    veteran.inventory.getItem(0) shouldBe ItemStack.of(Material.EMERALD, 9)
                    journal.load(veteran.uniqueId) shouldBe receipt
                    veteran.persistentDataContainer.set(native, PersistentDataType.STRING, session)
                    veteran.disconnect() shouldBe true
                    veteran.recalculatePermissions()
                    veteran.reconnect() shouldBe true
                    runtime.isBusy(veteran.uniqueId) shouldBe true
                    paper.advanceUntil("native expedition inventory restored despite the visit marker") { !runtime.isBusy(veteran.uniqueId) }
                    veteran.world shouldBe home
                    veteran.inventory.getItem(0) shouldBe ItemStack.of(Material.DIAMOND, 4)
                    veteran.persistentDataContainer.has(marker) shouldBe false
                    veteran.persistentDataContainer.has(native) shouldBe false
                }
            } finally { work.close(); Tasks.reset() }
        }
    }

    test("an active admin teleport restores the origin before moving to the requested player and skips the proxy return") {
        MockBukkitTestRuntime.open().use { paper ->
            val plugin = paper.createSimplePlugin("ExpeditionAdminExit")
            paper.addSimpleWorld("world")
            val expedition = paper.addSimpleWorld("arcevents_apocalypse_v2")
            val destination = paper.addSimpleWorld("staff_destination")
            PaperArcRuntime.installScheduling(plugin)
            val work = ColdStartWork()
            try {
                val admin = paper.addPlayer("Staff")
                val permission = requireNotNull(admin.addAttachment(plugin, "arcevents.admin", true))
                val target = paper.addPlayer("Target")
                target.teleport(destination.spawnLocation.clone().add(9.0, 2.0, 3.0).apply { yaw = 34f; pitch = -12f }) shouldBe true
                val journal = ApocalypseStorage(plugin.dataFolder.toPath())
                admin.inventory.setItem(0, ItemStack.of(Material.DIAMOND, 3))
                val origin = PaperPlayerStateService().captureEnvelope(admin, 1)
                val session = UUID.randomUUID().toString()
                journal.save(ApocalypsePlayerRecord(admin.uniqueId.toString(), "spawn", ApocalypsePlayerPhase.ACTIVE,
                    origin, null, nativeSession = session))
                admin.teleport(expedition.spawnLocation) shouldBe true
                admin.inventory.setItem(0, ItemStack.of(Material.IRON_AXE))
                val native = NamespacedKey(plugin, "apocalypse_session")
                val visit = NamespacedKey(plugin, "apocalypse_admin_visit")
                admin.persistentDataContainer.set(native, PersistentDataType.STRING, session)
                admin.persistentDataContainer.set(visit, PersistentDataType.STRING, expedition.uid.toString())
                val settings = coldSettings(NodeMode.HOST)
                val transfers = mutableListOf<String>()
                ApocalypseRuntime(plugin, { settings }, coldLocale(), mockk(relaxed = true), work, InMemoryRedis(), mockk(),
                    occupied = { false }, queued = { CompletableFuture.completedFuture(null) },
                    returnToOrigin = { _, server -> transfers.add(server) },
                    sourceLayout = testLayout(), chunkTickets = mockk()).use { runtime ->
                    runtime.start()
                    runtime.useTestWorld(expedition)
                    admin.teleport(target, TeleportCause.COMMAND) shouldBe false
                    paper.advanceUntil("existing expedition resumed") { runtime.isPlaying(admin.uniqueId) }
                    admin.persistentDataContainer.has(visit) shouldBe false
                    permission.setPermission("arcevents.admin", false)
                    admin.teleport(target, TeleportCause.COMMAND) shouldBe false
                    runtime.isBusy(admin.uniqueId) shouldBe false
                    permission.setPermission("arcevents.admin", true)
                    val visitor = paper.addPlayer("Supervisor")
                    visitor.addAttachment(plugin, "arcevents.admin", true)
                    paper.awaitHostJoins(runtime)
                    visitor.hasPermission("arcevents.admin") shouldBe true
                    runtime.isBusy(visitor.uniqueId) shouldBe false
                    visitor.teleport(admin, TeleportCause.COMMAND) shouldBe true
                    visitor.location shouldBe admin.location
                    admin.teleport(target, TeleportCause.COMMAND) shouldBe false
                    runtime.isBusy(admin.uniqueId) shouldBe true
                    admin.inventory.getItem(0)?.type shouldBe Material.IRON_AXE
                    paper.advanceUntil("expedition parked and requested teleport completed") { admin.location == target.location }
                    admin.teleportCause shouldBe TeleportCause.COMMAND
                    admin.inventory.getItem(0) shouldBe ItemStack.of(Material.DIAMOND, 3)
                    admin.persistentDataContainer.has(native) shouldBe false
                    runtime.isPlaying(admin.uniqueId) shouldBe false
                    runtime.isBusy(admin.uniqueId) shouldBe false
                    val parked = requireNotNull(journal.load(admin.uniqueId))
                    parked.phase shouldBe ApocalypsePlayerPhase.PARKED
                    parked.origin shouldBe origin
                    PaperPlayerStateCodec().decode(requireNotNull(parked.expedition)).storage[0]?.type shouldBe Material.IRON_AXE
                    parked.returnPending shouldBe false
                    transfers shouldBe emptyList()
                }
            } finally { work.close(); Tasks.reset() }
        }
    }

    test("active administrators retain all native commands and staff routing while recovery stays locked") {
        MockBukkitTestRuntime.open().use { paper ->
            val plugin = paper.createSimplePlugin("ExpeditionCommands")
            paper.addSimpleWorld("world")
            PaperArcRuntime.installScheduling(plugin)
            val work = ColdStartWork()
            try {
                val admin = paper.addPlayer("Administrator")
                admin.addAttachment(plugin, "arcevents.admin", true)
                val ordinary = paper.addPlayer("Survivor")
                val settings = coldSettings(NodeMode.RELAY)
                ApocalypseRuntime(plugin, { settings }, coldLocale(), mockk(), work, InMemoryRedis(), mockk(),
                    occupied = { false }, queued = { CompletableFuture.completedFuture(null) },
                    returnToOrigin = { _, _ -> false }, chunkTickets = mockk()).use { runtime ->
                    @Suppress("UNCHECKED_CAST")
                    val active = ApocalypseRuntime::class.java.getDeclaredField("active").let {
                        it.isAccessible = true
                        it.get(runtime) as MutableSet<UUID>
                    }
                    @Suppress("UNCHECKED_CAST")
                    val busy = ApocalypseRuntime::class.java.getDeclaredField("busy").let {
                        it.isAccessible = true
                        it.get(runtime) as MutableSet<UUID>
                    }
                    active += listOf(admin.uniqueId, ordinary.uniqueId)
                    ApocalypseRuntime::class.java.getDeclaredField("world").also {
                        it.isAccessible = true
                        it.set(runtime, admin.world)
                    }
                    admin.inventory.setItem(0, ItemStack.of(Material.IRON_AXE))
                    runtime.giveGuide(admin, hold = true) shouldBe true
                    admin.inventory.itemInMainHand.type shouldBe Material.BOOK
                    admin.inventory.getItem(0)?.type shouldBe Material.IRON_AXE
                    runtime.giveGuide(admin, hold = true) shouldBe true
                    val guideKey = NamespacedKey(plugin, "apocalypse_guide")
                    admin.inventory.storageContents.count { it?.itemMeta?.persistentDataContainer
                        ?.has(guideKey, PersistentDataType.BYTE) == true } shouldBe 1
                    val commands = listOf("/heal", "/cmi:heal", "/cmi heal", "/cmi:cmi heal", "/feed",
                        "/god", "/gamemode creative", "/minecraft:give Administrator stone", "/fly Survivor")
                    commands.forEach { command ->
                        val allowed = PlayerCommandPreprocessEvent(admin, command)
                        runtime.onCommand(allowed)
                        allowed.isCancelled shouldBe false
                        val blocked = PlayerCommandPreprocessEvent(ordinary, command)
                        runtime.onCommand(blocked)
                        blocked.isCancelled shouldBe true
                    }
                    listOf("admin", "reload", "debug").forEach { command ->
                        runtime.command(admin, listOf(command)) shouldBe false
                        runtime.command(ordinary, listOf(command)) shouldBe true
                    }
                    listOf("/arcevents status", "/msg Administrator hello").forEach { command ->
                        val allowed = PlayerCommandPreprocessEvent(ordinary, command)
                        runtime.onCommand(allowed)
                        allowed.isCancelled shouldBe false
                    }
                    busy += admin.uniqueId
                    runtime.giveGuide(admin, hold = true) shouldBe false
                    val pending = PlayerCommandPreprocessEvent(admin, "/heal")
                    runtime.onCommand(pending)
                    pending.isCancelled shouldBe true
                    runtime.command(admin, listOf("reload")) shouldBe true
                }
            } finally { work.close(); Tasks.reset() }
        }
    }

    test("a cold host protects only expedition inventories and permits an exact origin return before the source exists") {
        MockBukkitTestRuntime.open().use { paper ->
            val plugin = paper.createSimplePlugin("ColdExpedition")
            paper.addSimpleWorld("world")
            PaperArcRuntime.installScheduling(plugin)
            val work = ColdStartWork()
            try {
                val ordinary = paper.addPlayer("ArcadePlayer")
                val survivor = paper.addPlayer("Survivor")
                val orphan = paper.addPlayer("Orphan")
                val root = plugin.dataFolder.toPath()
                val journal = ApocalypseStorage(root)
                val marker = NamespacedKey(plugin, "apocalypse_session")
                val session = UUID.randomUUID().toString()
                survivor.inventory.setItem(0, ItemStack.of(Material.DIAMOND, 3))
                val origin = PaperPlayerStateService().captureEnvelope(survivor, System.currentTimeMillis())
                journal.save(ApocalypsePlayerRecord(survivor.uniqueId.toString(), "parkour", ApocalypsePlayerPhase.ACTIVE,
                    origin, null, nativeSession = session))
                survivor.inventory.setItem(0, ItemStack.of(Material.IRON_AXE))
                survivor.persistentDataContainer.set(marker, PersistentDataType.STRING, session)
                orphan.persistentDataContainer.set(marker, PersistentDataType.STRING, UUID.randomUUID().toString())
                val source = CompletableFuture<ApocalypseLayout>()
                val settings = coldSettings(NodeMode.HOST)
                val redis = InMemoryRedis()
                ApocalypseRuntime(plugin, { settings }, coldLocale(), mockk(), work, redis, mockk(),
                    occupied = { false }, queued = { error("Cold entry reached the queue") },
                    returnToOrigin = { _, _ -> error("Unexpected transfer") }, sourceLayout = source, chunkTickets = mockk()).use { runtime ->
                    runtime.start()
                    runtime.isBusy(survivor.uniqueId) shouldBe true
                    runtime.isBusy(orphan.uniqueId) shouldBe true
                    runtime.isBusy(ordinary.uniqueId) shouldBe false
                    paper.advanceUntil("host join reconciliation") {
                        runtime.allowsRecoveryCommand(survivor, "/arcevents apocalypse recover")
                    }
                    runtime.ready shouldBe false
                    runtime.allowsRecoveryCommand(survivor, "/events leave") shouldBe true
                    runtime.allowsRecoveryCommand(survivor, "/arcevents:events apocalypse recover") shouldBe true
                    runtime.allowsRecoveryCommand(survivor, "/events menu") shouldBe false
                    runtime.allowsRecoveryCommand(survivor, "/other:events leave") shouldBe false
                    runtime.tryOpenMenu(survivor) shouldBe false
                    runtime.command(ordinary, listOf("fishing")) shouldBe false
                    runtime.enter(ordinary)
                    redis.loadMapEntries("arc:events:apocalypse:host:v1", "parkour").join().single() shouldBe "0"
                    runtime.isPlaying(survivor.uniqueId) shouldBe false
                    runtime.isBusy(survivor.uniqueId) shouldBe true
                    survivor.inventory.getItem(0)?.type shouldBe Material.IRON_AXE
                    runtime.command(survivor, listOf("apocalypse", "recover")) shouldBe true
                    paper.advanceUntil("acknowledged origin restoration") { !runtime.isBusy(survivor.uniqueId) }
                    survivor.inventory.getItem(0) shouldBe ItemStack.of(Material.DIAMOND, 3)
                    val parked = requireNotNull(journal.load(survivor.uniqueId))
                    parked.phase shouldBe ApocalypsePlayerPhase.PARKED
                    parked.origin shouldBe origin
                    PaperPlayerStateCodec().decode(requireNotNull(parked.expedition)).storage[0]?.type shouldBe Material.IRON_AXE
                    runtime.isBusy(survivor.uniqueId) shouldBe false
                    // MockBukkit leaves Server.getWorldContainer unimplemented.
                    mockkObject(paper.server) {
                        every { paper.server.worldContainer } returns root.toFile()
                        source.complete(ApocalypseWorldLayout(68200133L))
                        paper.performTicks(2)
                    }
                    runtime.ready shouldBe false
                    runtime.isBusy(orphan.uniqueId) shouldBe true
                    paper.server.getWorld("arcevents_apocalypse_v2") shouldBe null
                    Files.exists(root.resolve("arcevents_apocalypse_v2")) shouldBe false
                }
                Files.exists(root.resolve("data/apocalypse/world-arcevents_apocalypse_v2/world.json")) shouldBe false
            } finally { work.close(); Tasks.reset() }
        }
    }

    test("a relay acknowledges a returned player and retains an orphaned native inventory without a local expedition") {
        MockBukkitTestRuntime.open().use { paper ->
            val plugin = paper.createSimplePlugin("ColdRelay")
            paper.addSimpleWorld("world")
            PaperArcRuntime.installScheduling(plugin)
            val work = ColdStartWork()
            try {
                val ordinary = paper.addPlayer("Returned")
                val orphan = paper.addPlayer("Orphan")
                orphan.persistentDataContainer.set(NamespacedKey(plugin, "apocalypse_session"), PersistentDataType.STRING, UUID.randomUUID().toString())
                val redis = InMemoryRedis()
                val hostSettings = coldSettings(NodeMode.HOST)
                val host = ApocalypseNetwork(redis, { hostSettings }, mockk(), work)
                val nonce = host.prepareReturn(ordinary.uniqueId, "spawn").join()
                val settings = coldSettings(NodeMode.RELAY)
                ApocalypseRuntime(plugin, { settings }, coldLocale(), mockk(), work, redis, mockk(),
                    occupied = { false }, queued = mockk(), returnToOrigin = { _, _ -> false }, chunkTickets = mockk()).use { runtime ->
                    runtime.start()
                    paper.advanceUntil("the origin acknowledges the returned player") {
                        !host.returnPending(ordinary.uniqueId, "spawn", nonce).join()
                    }
                    runtime.ready shouldBe true
                    runtime.isBusy(ordinary.uniqueId) shouldBe false
                    host.returnPending(ordinary.uniqueId, "spawn", nonce).join() shouldBe false
                    runtime.command(orphan, listOf("apocalypse", "recover")) shouldBe true
                    runtime.isBusy(orphan.uniqueId) shouldBe true
                }
                Files.exists(plugin.dataFolder.toPath().resolve("data/apocalypse")) shouldBe false
            } finally { work.close(); Tasks.reset() }
        }
    }
})

// MockBukkit supplies the loaded world; generator preparation is independent of the registered event and journal paths.
private fun testLayout() = CompletableFuture.completedFuture(mockk<ApocalypseLayout>(relaxed = true) {
    every { spawn } returns ApocalypsePoint(0, 64, 0)
    every { sitesNear(any(), any()) } returns emptyList()
    every { markers(any()) } returns emptyList()
    every { markersInChunk(any(), any()) } returns emptyList()
})

private fun ApocalypseRuntime.useTestWorld(world: World) {
    javaClass.getDeclaredField("openingWorld").also { it.isAccessible = true; it.setBoolean(this, false) }
    javaClass.getDeclaredField("world").also { it.isAccessible = true; it.set(this, world) }
}

private fun MockBukkitTestRuntime.awaitHostJoins(runtime: ApocalypseRuntime) {
    // InMemoryRedis completes arrival reads on a worker; advancing a fixed number of native ticks can outrun it.
    val pending = runtime.javaClass.getDeclaredField("joining").also { it.isAccessible = true }.get(runtime) as Set<*>
    advanceUntil("the host reconciles joining players") { pending.isEmpty() }
}

private fun coldSettings(mode: NodeMode) = mockk<ArcEventsConfig> {
    every { nodeMode } returns mode
    every { serverId } returns if (mode == NodeMode.HOST) "parkour" else "spawn"
    every { hostServer } returns "parkour"
    every { network } returns NetworkSettings(true, setOf("parkour", "spawn"), 300, 45, 5, 20, true, true)
}

private fun coldLocale() = mockk<ArcEventsLocale> {
    every { render(any(), any(), any()) } answers { Component.text(firstArg<String>()) }
    every { lore(any(), any(), any()) } returns emptyList()
}

private class ColdStartWork : EventWork, AutoCloseable {
    private val main = LifecycleTaskScope()
    override fun <T> onMain(action: () -> T) = main.callSync(action)
    override fun <T> compute(action: () -> T): CompletableFuture<T> =
        runCatching(action).fold(CompletableFuture<T>::completedFuture, CompletableFuture<T>::failedFuture)
    override fun capture(isCurrent: () -> Boolean, step: () -> Boolean): CompletableFuture<Unit> = error("Unexpected native capture")
    override fun isCurrent() = true
    override fun close() = main.close()
}

private fun MockBukkitTestRuntime.advanceUntil(operation: String, done: () -> Boolean) {
    val deadline = System.nanoTime() + 5_000_000_000L
    while (!done()) {
        check(System.nanoTime() < deadline) { "Timed out waiting for $operation" }
        performTicks(1)
        Thread.sleep(1)
    }
}
