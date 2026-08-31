package ru.ruscrafting.events.paper

import com.google.gson.Gson
import kotlinx.coroutines.runBlocking
import org.bukkit.plugin.java.JavaPlugin
import org.bukkit.generator.ChunkGenerator
import org.slf4j.LoggerFactory
import ru.arc.core.PaperArcRuntime
import ru.arc.core.Tasks
import ru.arc.paper.network.BungeeBackendTransfer
import ru.arc.paper.nameplate.NativePaperNameplateVisibilityPolicy
import ru.arc.paper.nameplate.PaperNameplateOptions
import ru.arc.paper.nameplate.PaperNameplateVisibilityPolicy
import ru.arc.paper.nameplate.PaperPlayerNameplates
import ru.arc.paper.nameplate.ViewAlignedPaperNameplateVisibilityPolicy
import ru.arc.paper.runtime.PaperPluginRuntime
import ru.arc.observability.RuntimeHealthContribution
import ru.arc.observability.RuntimeHealthState
import ru.arc.redis.RedisManager
import ru.arc.redis.ServerIdentity
import ru.ruscrafting.events.config.ArcEventsConfig
import ru.ruscrafting.events.config.ArcEventsLocale
import ru.ruscrafting.events.config.ArcEventsReloadPolicy
import ru.ruscrafting.events.config.ArcEventsRedisBootstrap
import ru.ruscrafting.events.domain.MatchPhase
import ru.ruscrafting.events.network.RedisEventNetworkRepository
import java.nio.file.Files
import java.util.logging.Level

class ArcEventsPlugin : JavaPlugin() {
    @Volatile
    private lateinit var settings: ArcEventsConfig
    private lateinit var locale: ArcEventsLocale
    private var redis: RedisManager? = null
    private var network: EventNetworkCoordinator? = null
    private var service: ArcEventsService? = null
    private var transfer: BungeeBackendTransfer? = null
    private var pluginRuntime: PaperPluginRuntime? = null
    private var weaponPointStore: ArenaWeaponPointStore? = null
    private var nameplates: TttNameplateRuntime? = null
    private var chatIsolation: AutoCloseable? = null

    /**
     * My_Worlds persists the owning plugin name for custom generators. Keep
     * arena border generation deterministic even when it asks Bukkit to load
     * an ArcEvents world during a later server start.
     */
    override fun getDefaultWorldGenerator(worldName: String, id: String?): ChunkGenerator? =
        ArenaWorldGeneratorRegistry.generatorFor(server.worldContainer.toPath(), worldName)

    override fun onEnable() {
        saveDefaultConfig()
        saveResourceIfMissing("lang/ru.yml")
        saveResourceIfMissing("lang/en.yml")
        saveResourceIfMissing("modules/redis.yml")
        PaperArcRuntime.installScheduling(this)
        val lifecycle = PaperPluginRuntime(this, "arc-events").also {
            pluginRuntime = it
            it.start("version" to pluginMeta.version)
        }
        try {
            val dataRoot = dataFolder.toPath()
            settings = ArcEventsConfig.load(dataRoot)
            ArcEventsLocale.mergeMissingFiles(dataRoot)
            ArcEventsLocale.validateFiles(dataRoot)
            locale = ArcEventsLocale(dataRoot) { settings }
            if (settings.arenas.any { it.template == TttCitadelBlueprint.TEMPLATE }) TttCitadelLoot.validate()
            ArenaWorldProvisioner(this).ensureLoaded(settings)
            val redisConfig = ArcEventsRedisBootstrap.load(dataRoot, settings)
            val manager = RedisManager(
                redisConfig.connection(),
                ServerIdentity { settings.serverId },
                LoggerFactory.getLogger("ArcEvents.Redis"),
            )
            lifecycle.own(manager)
            if (!manager.isConnected() || !runBlocking { manager.healthCheck() }) error("Redis connection is unavailable")
            redis = manager
            val repository = RedisEventNetworkRepository(manager, Gson())
            val debug = ArcEventsDebug({ settings.debugEnabled }, logger::info)
            val escrow = PlayerStateEscrow(RecoveryBatchStore(dataRoot, Gson()))
            val items = TttItems(this, locale) { settings }
            val firearms = TttFirearms(this, locale) { settings }
            val lootScene = TttLootScene(this, firearms) { settings }
            val storedWeaponPoints = lifecycle.own(ArenaWeaponPointStore(dataRoot) { failure ->
                logger.log(Level.WARNING, "ArcEvents mandatory weapon points are unavailable; using random map points", failure)
            }).also { weaponPointStore = it }
            val arenaInspector = ArenaRuntimeInspector(this)
            val arenaPool = ArenaPool(settings = { settings }) { arena, maximumPlayers ->
                arenaInspector.ready(arena, maximumPlayers) && mandatoryWeaponPointsReady(server, storedWeaponPoints, arena)
            }
            val weaponPointEditor = ArenaWeaponPointEditor({ settings }, arenaPool, storedWeaponPoints)
            val lootSpawner = TttLootSpawner(this, lootScene, firearms, weaponPointEditor, debug)
            lateinit var activeService: ArcEventsService
            val smokeGrenades = TttSmokeGrenades(
                plugin = this,
                settings = { settings },
                currentMatchId = {
                    activeService.currentMatch()?.takeIf { it.phase == MatchPhase.ACTIVE }?.matchId
                },
                targets = { server.onlinePlayers.filter { activeService.isAlive(it.uniqueId) } },
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
                onArrival = { activeService.onArrival(it) },
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
            val hud = TttHud(this, { settings }, locale, nameplateRuntime)
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
            )
            service = activeService
            lifecycle.own(activeService)
            chatIsolation = openConfiguredChatIsolation(settings, activeService)
            lifecycle.registerHealth("runtime") {
                val redisReady = manager.isConnected()
                RuntimeHealthContribution(
                    state = if (redisReady) RuntimeHealthState.UP else RuntimeHealthState.DEGRADED,
                    recoveryBacklog = escrow.recoveryBacklog(),
                    activeLeases = coordinator.activeLeaseCount(),
                    schemas = mapOf("player_recovery" to RecoveryBatch.FORMAT_VERSION),
                    dependencies = mapOf("redis" to redisReady),
                )
            }
            val menu = ArcEventsMenu(activeService, items, locale, { settings }, ::reloadPlugin)
            val command = ArcEventsCommand(this, activeService, menu, weaponPointEditor, locale, { settings }, ::reloadPlugin)
            requireNotNull(getCommand("arcevents")).apply {
                setExecutor(command)
                tabCompleter = command
            }
            server.pluginManager.registerEvents(ArcEventsListener(activeService, menu, items), this)
            coordinator.start()
            activeService.start()
            lifecycle.ready(
                "server" to settings.serverId,
                "mode" to settings.nodeMode,
                "arena_ready" to activeService.arenaReady(),
                "redis" to manager.isConnected(),
            )
            lifecycle.reportHealthEvery(HEALTH_REPORT_TICKS)
            logger.info(
                "ArcEvents enabled node=${settings.serverId} mode=${settings.nodeMode} host=${settings.hostServer} " +
                    "arenaReady=${activeService.arenaReady()} redisConnected=${manager.isConnected()}",
            )
        } catch (failure: Throwable) {
            runCatching { lifecycle.health.markDown(); lifecycle.emitHealth() }
            logger.log(Level.SEVERE, "ArcEvents failed closed during startup", failure)
            server.pluginManager.disablePlugin(this)
        }
    }

    override fun onDisable() {
        runCatching { chatIsolation?.close() }.onFailure { logger.log(Level.WARNING, "Could not close ArcEvents chat isolation", it) }
        chatIsolation = null
        runCatching { pluginRuntime?.close() }.onFailure { logger.log(Level.SEVERE, "Could not close ArcEvents runtime", it) }
        pluginRuntime = null
        weaponPointStore = null
        nameplates = null
        Tasks.reset()
    }

    private fun reloadPlugin(): Result<Unit> = runCatching {
        val dataRoot = dataFolder.toPath()
        ArcEventsConfig.mergeMissing(dataRoot)
        ArcEventsLocale.mergeMissingFiles(dataRoot)
        val candidate = ArcEventsConfig.inspect(dataRoot)
        val current = settings
        val activeService = requireNotNull(service) { "ArcEvents service is unavailable" }
        val liveSnapshot = activeService.snapshot()
        val activeMatchId = liveSnapshot.matchId
        ArcEventsReloadPolicy.validate(
            current = current,
            candidate = candidate,
            matchOrReservationActive = activeMatchId != null,
            activeArenaId = activeService.activeArenaId(),
        )
        val store = weaponPointStore
        store?.let {
            candidate.arenas.forEach { arena ->
                require(it.points(arena).size <= arena.weaponCount) {
                    "arena ${arena.id} has more mandatory weapon points than weapon-count"
                }
            }
        }
        val arenaInspector = ArenaRuntimeInspector(this)
        candidate.arenas.filter { it.enabled }.forEach { arena ->
            require(server.getWorld(arena.world) != null) {
                "arena ${arena.id} world is not loaded; world/template changes require a plugin restart"
            }
            require(arenaInspector.ready(arena, candidate.ttt.maximumPlayers)) {
                "arena ${arena.id} is not runtime-ready with the candidate spawn settings"
            }
            if (store != null) require(mandatoryWeaponPointsReady(server, store, arena)) {
                "arena ${arena.id} mandatory weapon points are not runtime-ready"
            }
        }
        ArcEventsLocale.validateFiles(dataRoot)
        val candidateLocale = locale.prepare()
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
        Unit
    }.onFailure { logger.log(Level.WARNING, "ArcEvents reload was rejected", it) }

    private fun openConfiguredChatIsolation(
        candidate: ArcEventsConfig,
        activeService: ArcEventsGameplayBoundary,
    ): AutoCloseable? {
        if (!candidate.packetChatIsolationEnabled) return null
        val protocolLib = server.pluginManager.getPlugin("ProtocolLib")
        if (protocolLib?.isEnabled != true) {
            logger.warning("ArcEvents packet chat isolation was requested but ProtocolLib is not enabled; using Paper isolation only")
            return null
        }
        return runCatching { openProtocolLibChatIsolation(this, activeService) }
            .onSuccess {
                logger.info("ArcEvents packet chat isolation enabled through ProtocolLib ${protocolLib.pluginMeta.version}")
            }
            .onFailure { failure ->
                logger.log(Level.SEVERE, "ArcEvents could not enable packet chat isolation; using Paper isolation only", failure)
            }
            .getOrNull()
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
