package ru.ruscrafting.events.paper

import com.google.gson.Gson
import org.bukkit.plugin.java.JavaPlugin
import org.bukkit.generator.ChunkGenerator
import net.luckperms.api.LuckPerms
import ru.arc.core.PaperArcRuntime
import ru.arc.core.Tasks
import ru.arc.core.LifecycleTaskScope
import ru.arc.core.BudgetedTaskQueue
import ru.arc.core.BoundedAsyncExecutor
import ru.arc.paper.api.ArcRedisService
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import ru.arc.paper.redis.PaperRedisClient
import ru.arc.paper.network.BungeeBackendTransfer
import ru.arc.paper.nameplate.NativePaperNameplateVisibilityPolicy
import ru.arc.paper.nameplate.PaperNameplateOptions
import ru.arc.paper.nameplate.PaperNameplateVisibilityPolicy
import ru.arc.paper.nameplate.PaperPlayerNameplates
import ru.arc.paper.nameplate.ViewAlignedPaperNameplateVisibilityPolicy
import ru.arc.paper.runtime.PaperPluginRuntime
import ru.arc.paper.api.ArcSidebarPriorities
import ru.arc.paper.api.ArcSidebarService
import ru.arc.observability.RuntimeHealthContribution
import ru.arc.observability.RuntimeHealthState
import ru.ruscrafting.events.config.ArcEventsConfig
import ru.ruscrafting.events.config.ArcEventsLocale
import ru.ruscrafting.events.config.ArcEventsReloadPolicy
import ru.ruscrafting.events.config.PlayerStateMode
import ru.ruscrafting.events.config.ArcEventsRedisBootstrap
import ru.ruscrafting.events.domain.MatchPhase
import ru.ruscrafting.events.network.RedisEventNetworkRepository
import java.nio.file.Files
import java.util.logging.Level

open class ArcEventsPlugin : JavaPlugin() {
    @Volatile
    private lateinit var settings: ArcEventsConfig
    private lateinit var locale: ArcEventsLocale
    private lateinit var menuLayouts: ArcEventsMenuLayouts
    private lateinit var menu: ArcEventsMenu
    private var network: EventNetworkCoordinator? = null
    private var service: ArcEventsService? = null
    private var transfer: BungeeBackendTransfer? = null
    private var pluginRuntime: PaperPluginRuntime? = null
    private var weaponPointStore: ArenaWeaponPointStore? = null
    private var nameplates: TttNameplateRuntime? = null
    private var chatIsolation: AutoCloseable? = null
    private var arenaProvisioner: ArenaWorldProvisioner? = null
    private lateinit var io: BoundedAsyncExecutor
    private lateinit var workScope: LifecycleTaskScope
    private lateinit var nativeWork: BudgetedTaskQueue
    private lateinit var work: EventWork
    private lateinit var chunkTickets: ru.arc.paper.chunk.PaperChunkTicketRegistry
    private val redisLeases = ConcurrentHashMap.newKeySet<PaperRedisClient>()
    private var shutdownDrain = CompletableFuture.completedFuture(Unit)
    private var predecessorDrain = CompletableFuture.completedFuture(Unit)
    private var enableScope: LifecycleTaskScope? = null
    private var generationOpen = false
    private var generationStarted = false
    private var preparing = false
    private var reloading = false
    private var escrow: PlayerStateEscrow? = null


    /**
     * My_Worlds persists the owning plugin name for custom generators. Keep
     * arena border generation deterministic even when it asks Bukkit to load
     * an ArcEvents world during a later server start.
     */
    override fun getDefaultWorldGenerator(worldName: String, id: String?): ChunkGenerator? =
        ArenaWorldGeneratorRegistry.generatorFor(server.worldContainer.toPath(), worldName)

    override fun onEnable() {
        check(!generationOpen) { "Plugin generation is already open" }
        generationOpen = true
        generationStarted = false
        predecessorDrain = shutdownDrain
        shutdownDrain = CompletableFuture()
        val scope = ru.arc.core.LifecycleTaskScope(ru.arc.core.BukkitTaskScheduler(this))
        enableScope = scope
        val token = scope.token()
        predecessorDrain.whenComplete { _, failure ->
            scope.callSync(token) {
                if (enableScope !== scope || !generationOpen) return@callSync
                if (failure != null) {
                    logger.log(Level.SEVERE, "Previous plugin generation failed to drain; startup remains closed", failure)
                    server.pluginManager.disablePlugin(this)
                } else {
                    generationStarted = true
                    startGeneration()
                }
            }.whenComplete { _, startFailure ->
                if (startFailure != null) scope.callSync(token) {
                    if (enableScope === scope && generationOpen) {
                        logger.log(Level.SEVERE, "Plugin generation startup failed closed", startFailure)
                        server.pluginManager.disablePlugin(this)
                    }
                }
            }
        }
    }

    protected open fun startGeneration() {
        reloading = false
        preparing = true
        PaperArcRuntime.installScheduling(this)
        val lifecycle = PaperPluginRuntime(this, "arc-events").also {
            pluginRuntime = it
            it.start("version" to pluginMeta.version)
        }
        io = BoundedAsyncExecutor("arc-events-storage")
        workScope = LifecycleTaskScope(Tasks.scheduler)
        nativeWork = BudgetedTaskQueue(workScope)
        val cleanup = requireNotNull(server.servicesManager.load(ru.arc.paper.api.ArcNativeCleanupService::class.java)) { "ARC shared native cleanup service is unavailable" }
        work = RuntimeEventWork(io, workScope, nativeWork, cleanup, name)
        chunkTickets = ru.arc.paper.chunk.PaperChunkTicketRegistry(this)
        val activeWork = work
        val root = dataFolder.toPath()
        val redisService = requireNotNull(server.servicesManager.load(ArcRedisService::class.java)) { "ARC shared Redis service is unavailable" }
        val owner = name
        server.pluginManager.registerEvents(EventStateProtection { player ->
            preparing || escrow?.isLocked(player.uniqueId) == true ||
                (escrow?.pendingPlayers()?.contains(player.uniqueId) == true && service?.isParticipant(player.uniqueId) != true)
        }, this)
        io.submit {
            saveDefaultConfig()
            listOf("lang/ru.yml", "lang/en.yml", "modules/redis.yml").forEach(::saveResourceIfMissing)
            val candidate = ArcEventsConfig.load(root)
            val layouts = ArcEventsMenuLayouts(root)
            ArcEventsLocale.mergeMissingFiles(root)
            ArcEventsLocale.validateFiles(root)
            val translated = ArcEventsLocale(root) { settings }
            val redisConfig = ArcEventsRedisBootstrap.load(root, candidate)
            val manager = PaperRedisClient.open(redisService, owner, redisConfig.connection(), candidate.serverId).also {
                redisLeases += it
                it.retainDuringShutdown()
                it.init()
            }
            require(manager.healthCheck().get(5, java.util.concurrent.TimeUnit.SECONDS)) { "Redis connection is unavailable" }
            val recovery = RecoveryBatchStore(root, Gson()).also { if (candidate.playerStateMode == PlayerStateMode.PRESERVE) it.prepare() }
            val points = ArenaWeaponPointStore(root) { failure ->
                logger.log(Level.WARNING, "ArcEvents mandatory weapon points are unavailable; using random map points", failure)
            }
            Prepared(candidate, translated, layouts, manager, recovery, points)
        }.thenCompose { prepared -> activeWork.onMain {
            check(pluginRuntime === lifecycle)
            settings = prepared.settings
            locale = prepared.locale
            menuLayouts = prepared.layouts
            if (settings.arenas.any { it.template == TttCitadelBlueprint.TEMPLATE }) TttCitadelLoot.validate()
            val provisioner = ArenaWorldProvisioner(this, chunkTickets).also { arenaProvisioner = it }
            provisioner.ensureLoadedAsync(settings, activeWork, mandatoryPoints = prepared.points::points).thenCompose { activeWork.onMain {
                check(pluginRuntime === lifecycle)
                activate(lifecycle, prepared, activeWork)
            } }
        }.thenCompose { it } }.whenComplete { _, failure -> if (failure != null) activeWork.onMain {
            if (pluginRuntime === lifecycle) {
                logger.log(Level.SEVERE, "ArcEvents failed closed during startup", failure)
                server.pluginManager.disablePlugin(this)
            }
        } }
    }

    private data class Prepared(
        val settings: ArcEventsConfig, val locale: ArcEventsLocale, val layouts: ArcEventsMenuLayouts,
        val redis: PaperRedisClient, val recovery: RecoveryBatchStore, val points: ArenaWeaponPointStore,
    )

    private fun activate(lifecycle: PaperPluginRuntime, prepared: Prepared, activeWork: EventWork) {
        try {
            val manager = prepared.redis
            val repository = RedisEventNetworkRepository(manager, Gson())
            val debug = ArcEventsDebug({ settings.debugEnabled }, logger::info)
            val escrow = PlayerStateEscrow(prepared.recovery,
                enabled = settings.playerStateMode == PlayerStateMode.PRESERVE, work = activeWork).also { this.escrow = it }
            val items = TttItems(this, locale) { settings }
            val firearms = TttFirearms(this, locale) { settings }
            val lootScene = TttLootScene(this, firearms, activeWork, chunkTickets) { settings }
            val storedWeaponPoints = lifecycle.own(prepared.points).also { weaponPointStore = it }
            val arenaInspector = ArenaRuntimeInspector(this)
            val arenaPool = ArenaPool(settings = { settings }) { arena, maximumPlayers ->
                arenaInspector.ready(arena, maximumPlayers) && mandatoryWeaponPointsReady(server, storedWeaponPoints, arena)
            }
            val weaponPointEditor = ArenaWeaponPointEditor({ settings }, arenaPool, storedWeaponPoints, activeWork,
                requireNotNull(arenaProvisioner)::refreshReadinessAsync)
            val lootSpawner = TttLootSpawner(this, lootScene, firearms, weaponPointEditor, debug, activeWork, chunkTickets)
            lateinit var activeService: ArcEventsService
            val smokeGrenades = TttSmokeGrenades(
                plugin = this,
                settings = { settings },
                currentMatchId = {
                    activeService.currentMatch()?.takeIf { it.phase == MatchPhase.ACTIVE }?.matchId
                },
                targets = { activeService.currentMatch()?.participants?.keys.orEmpty().mapNotNull(server::getPlayer)
                    .filter { activeService.isAlive(it.uniqueId) } },
                work = activeWork,
            )
            val backendTransfer = BungeeBackendTransfer(this) { failure ->
                logger.log(Level.WARNING, "ArcEvents backend transfer send failed", failure)
            }.also { transfer = it; lifecycle.own(it) }
            val coordinator = EventNetworkCoordinator(
                plugin = this,
                settings = { settings },
                locale = locale,
                repository = repository,
                redis = manager,
                transfer = backendTransfer,
                debug = debug,
                matchState = { activeService.matchState() },
                arenaReady = { activeService.arenaReady() },
                readyArenaIds = arenaPool::readyIds,
                onReservation = { activeService.onReservation(it) },
                onArrival = { error("Production arrival requires its durable future") },
                onArrivalAsync = { activeService.onArrivalAsync(it) },
                recoveryPending = { activeService.escrowPending(it) },
                activeMatchParticipant = { activeService.ownsActiveRoute(it) },
            )
            network = coordinator
            lifecycle.own(coordinator)
            val nameplateRuntime = lifecycle.own(TttNameplateRuntime(
                locale = locale,
                statistics = coordinator::stats,
                onlinePlayer = server::getPlayer,
                currentMatch = { service?.currentMatch() },
                rendererFactory = ::openNameplateRenderer,
            )).also { nameplates = it }
            nameplateRuntime.reconfigure(settings.ui.nameplates)
            val sidebar = requireNotNull(server.servicesManager.load(ArcSidebarService::class.java)) {
                "ARC shared sidebar service is unavailable"
            }.register(this, "ttt", ArcSidebarPriorities.EVENT).also(lifecycle::own)
            val hud = TttHud(this, { settings }, locale, nameplateRuntime, sidebar)
            activeService = ArcEventsService(
                plugin = this,
                settings = { settings },
                locale = locale,
                escrow = escrow,
                items = items,
                firearms = firearms,
                hud = hud,
                lootScene = lootScene,
                smokeGrenades = smokeGrenades,
                network = coordinator,
                debug = debug,
                redisConnected = manager::isConnected,
                arenaPool = arenaPool,
                weaponPoints = weaponPointEditor,
                lootSpawner = lootSpawner,
                work = activeWork,
            )
            service = activeService
            lifecycle.own(activeService)
            chatIsolation = openConfiguredChatIsolation(settings, activeService)
            lifecycle.registerHealth("runtime") {
                val redisReady = manager.isConnected()
                RuntimeHealthContribution(
                    state = if (redisReady) RuntimeHealthState.UP else RuntimeHealthState.DEGRADED,
                    recoveryBacklog = if (settings.playerStateMode == PlayerStateMode.DISPOSABLE) 0 else escrow.recoveryBacklog(),
                    activeLeases = coordinator.activeLeaseCount(),
                    schemas = mapOf("player_recovery" to RecoveryBatch.FORMAT_VERSION),
                    dependencies = mapOf("redis" to redisReady),
                )
            }
            menu = ArcEventsMenu(this, activeService, items, locale, { settings }, ::reloadPlugin, menuLayouts,
                escapeCloses = { player -> server.servicesManager.load(LuckPerms::class.java)?.userManager?.getUser(player.uniqueId)?.cachedData?.metaData?.getMetaValue("arc-menu-escape")?.equals("close", true) == true })
            lifecycle.own(menu)
            val command = ArcEventsCommand(this, activeService, menu, weaponPointEditor, locale, { settings }, ::reloadPlugin)
            requireNotNull(getCommand("arcevents")).apply {
                setExecutor(command)
                tabCompleter = command
            }
            server.pluginManager.registerEvents(ArcEventsListener(activeService, menu, items), this)
            coordinator.start()
            activeService.start()
            preparing = false
            if (settings.nodeMode == ru.ruscrafting.events.config.NodeMode.HOST) {
                lifecycle.own(ProxyEventChatBridge(this, activeService::handlesMatchChat))
            }
            lifecycle.ready(
                "server" to settings.serverId,
                "mode" to settings.nodeMode,
                "player_state" to settings.playerStateMode,
                "arena_ready" to activeService.arenaReady(),
                "redis" to manager.isConnected(),
            )
            lifecycle.reportHealthEvery(HEALTH_REPORT_TICKS)
            logger.info(
                "ArcEvents enabled node=${settings.serverId} mode=${settings.nodeMode} host=${settings.hostServer} " +
                    "playerState=${settings.playerStateMode} " +
                    "legacyRecovery=${if (settings.playerStateMode == PlayerStateMode.DISPOSABLE) "ignored" else "active"} " +
                    "arenaReady=${activeService.arenaReady()} redisConnected=${manager.isConnected()}",
            )
        } catch (failure: Throwable) {
            runCatching { lifecycle.health.markDown(); lifecycle.emitHealth() }
            logger.log(Level.SEVERE, "ArcEvents failed closed during startup", failure)
            server.pluginManager.disablePlugin(this)
        }
    }

    override fun onDisable() {
        if (!generationOpen) return
        generationOpen = false
        enableScope?.close()
        enableScope = null
        val completion = shutdownDrain
        val previous = predecessorDrain
        val retiring = if (generationStarted) runCatching { closeGeneration() }.getOrElse { CompletableFuture.failedFuture(it) }
            else CompletableFuture.completedFuture(Unit)
        generationStarted = false
        CompletableFuture.allOf(previous, retiring).whenComplete { _, failure ->
            if (failure == null) completion.complete(Unit) else completion.completeExceptionally(failure)
        }
    }

    protected open fun closeGeneration(): CompletableFuture<Unit> {
        preparing = true
        var nativeFailure: Throwable? = null
        fun detach(action: () -> Unit) {
            try { action() } catch (problem: Throwable) {
                if (nativeFailure == null) nativeFailure = problem else nativeFailure.addSuppressed(problem)
            }
        }
        detach { escrow?.close() }
        detach { chatIsolation?.close() }
        chatIsolation = null
        val retiring = pluginRuntime
        pluginRuntime = null
        detach { retiring?.close() }
        val retiredArenas = arenaProvisioner
        arenaProvisioner = null
        detach { retiredArenas?.close() }
        val nativeDrain = if (::work.isInitialized) work.cleanupDrain() else CompletableFuture.completedFuture(Unit)
        detach { if (::nativeWork.isInitialized) nativeWork.close() }
        detach { if (::workScope.isInitialized) workScope.close() }
        weaponPointStore = null
        nameplates = null
        service = null
        escrow = null
        val drain = if (::io.isInitialized) io.closeAsync {
            var failure: Throwable? = null
            redisLeases.forEach { client ->
                try { client.close() } catch (problem: Throwable) {
                    if (failure == null) failure = problem else failure.addSuppressed(problem)
                } finally { redisLeases.remove(client) }
            }
            failure?.let { throw it }
        } else CompletableFuture.completedFuture(Unit)
        Tasks.reset()
        return CompletableFuture.allOf(drain, nativeDrain, retiredArenas?.closeCompletion ?: CompletableFuture.completedFuture(Unit)).handle { _, failure ->
            nativeFailure?.let { problem -> failure?.let(problem::addSuppressed); throw problem }
            failure?.let { throw it }
            Unit
        }
    }

    private fun reloadPlugin(): CompletableFuture<Result<Unit>> {
        if (reloading) return CompletableFuture.completedFuture(Result.failure(IllegalStateException("ArcEvents reload is already pending")))
        val activeService = service ?: return CompletableFuture.completedFuture(Result.failure(IllegalStateException("ArcEvents service is unavailable")))
        val activeWork = work
        val lifecycle = pluginRuntime
        val current = settings
        val dataRoot = dataFolder.toPath()
        val currentLocale = locale
        val currentLayouts = menuLayouts
        reloading = true
        return activeWork.compute {
            ArcEventsConfig.mergeMissing(dataRoot)
            ArcEventsLocale.mergeMissingFiles(dataRoot)
            val candidate = ArcEventsConfig.inspect(dataRoot)
            val layouts = currentLayouts.prepare(dataRoot)
            ArcEventsLocale.validateFiles(dataRoot)
            Triple(candidate, layouts, currentLocale.prepare())
        }.thenCompose { (candidate, candidateLayouts, candidateLocale) -> activeWork.onMain {
            check(pluginRuntime === lifecycle && settings === current)
            val store = weaponPointStore
            check(store?.isEditing() != true) { "Mandatory weapon point changes are still being persisted" }
            val revision = store?.revision
            val inspector = ArenaRuntimeInspector(this)
            val validation = ArrayDeque<() -> Unit>()
            candidate.arenas.filter { it.enabled }.forEach { arena ->
                validation += {
                    require(server.getWorld(arena.world) != null) { "arena ${arena.id} world is not loaded; world/template changes require a plugin restart" }
                    require(inspector.ready(arena, candidate.ttt.maximumPlayers)) { "arena ${arena.id} is not runtime-ready with the candidate spawn settings" }
                }
                store?.points(arena)?.forEach { point -> validation += {
                    val world = requireNotNull(server.getWorld(arena.world))
                    require(TttLootPlacement.exact(org.bukkit.Location(world, point.x, point.y, point.z)) != null) {
                        "arena ${arena.id} mandatory weapon points are not runtime-ready"
                    }
                } }
            }
            activeWork.capture({ pluginRuntime === lifecycle && settings === current }) {
                validation.removeFirstOrNull()?.invoke()
                validation.isEmpty()
            }.thenCompose { activeWork.onMain {
                check(store?.revision == revision && store?.isEditing() != true) { "Mandatory weapon point state changed during reload" }
        val liveSnapshot = activeService.snapshot()
        val activeMatchId = liveSnapshot.matchId
        ArcEventsReloadPolicy.validate(
            current = current,
            candidate = candidate,
            matchOrReservationActive = activeMatchId != null,
            activeArenaId = activeService.activeArenaId(),
        )
        store?.let {
            candidate.arenas.forEach { arena ->
                require(it.points(arena).size <= arena.weaponCount) {
                    "arena ${arena.id} has more mandatory weapon points than weapon-count"
                }
            }
        }
        settings = candidate
        val previousLocale = locale.apply(candidateLocale)
        val worldRuntime = ArenaWorldProvisioner(this)
        try {
            worldRuntime.applyRuntimeTuning(candidate)
            nameplates?.reconfigure(candidate.ui.nameplates)
            activeService.reconfigureRuntime()
        } catch (failure: Throwable) {
            settings = current
            locale.apply(previousLocale)
            runCatching { worldRuntime.applyRuntimeTuning(current) }.onFailure(failure::addSuppressed)
            runCatching { nameplates?.reconfigure(current.ui.nameplates) }.onFailure(failure::addSuppressed)
            runCatching(activeService::reconfigureRuntime).onFailure(failure::addSuppressed)
            throw failure
        }
        menu.replaceMenus(candidateLayouts)
        Unit
            } }
        }.thenCompose { it } }.handle { _, failure -> failure }.thenCompose { failure -> activeWork.onMain {
            if (pluginRuntime === lifecycle) reloading = false
            if (failure == null) Result.success(Unit) else {
                logger.log(Level.WARNING, "ArcEvents reload was rejected", failure)
                Result.failure(failure)
            }
        } }
    }

    private fun openConfiguredChatIsolation(
        candidate: ArcEventsConfig,
        activeService: ArcEventsGameplayBoundary,
    ): AutoCloseable? {
        if (!candidate.packetChatIsolationEnabled) return null
        return PacketEventsChatIsolation.open(activeService).also {
            logger.info("ArcEvents participant chat isolation enabled through PacketEvents")
        }
    }

    private fun openNameplateRenderer(
        options: PaperNameplateOptions,
        minimumViewAlignment: Double,
    ): PaperPlayerNameplates {
        val nativeVisibility = ViewAlignedPaperNameplateVisibilityPolicy(
            NativePaperNameplateVisibilityPolicy(options),
            minimumViewAlignment,
        )
        return PaperPlayerNameplates.open(
            plugin = this,
            options = options,
            visibility = PaperNameplateVisibilityPolicy { viewer, target ->
                nativeVisibility.canView(viewer, target) &&
                    service?.canViewNameplate(viewer.uniqueId, target.uniqueId) == true
            },
        )
    }

    private fun saveResourceIfMissing(path: String) {
        if (!Files.isRegularFile(dataFolder.toPath().resolve(path))) saveResource(path, false)
    }

    private companion object {
        const val HEALTH_REPORT_TICKS = 1_200L
    }
}
