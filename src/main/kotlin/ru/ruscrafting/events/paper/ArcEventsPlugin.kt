package ru.ruscrafting.events.paper

import com.google.gson.Gson
import kotlinx.coroutines.runBlocking
import org.bukkit.plugin.java.JavaPlugin
import org.slf4j.LoggerFactory
import ru.arc.config.ConfigManager
import ru.arc.core.PaperArcRuntime
import ru.arc.core.Tasks
import ru.arc.paper.network.BungeeBackendTransfer
import ru.arc.paper.nameplate.NativePaperNameplateVisibilityPolicy
import ru.arc.paper.nameplate.PaperNameplateOptions
import ru.arc.paper.nameplate.PaperNameplateVisibilityPolicy
import ru.arc.paper.nameplate.PaperPlayerNameplates
import ru.arc.paper.runtime.PaperPluginRuntime
import ru.arc.observability.RuntimeHealthContribution
import ru.arc.observability.RuntimeHealthState
import ru.arc.redis.RedisManager
import ru.arc.redis.ServerIdentity
import ru.ruscrafting.events.config.ArcEventsConfig
import ru.ruscrafting.events.config.ArcEventsLocale
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
            val items = TttItems(this, locale)
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
                onReservation = { activeService.onReservation(it) },
                onArrival = { activeService.onArrival(it) },
            )
            network = coordinator
            lifecycle.own(coordinator)
            val nameplateOptions = PaperNameplateOptions(
                maxDistance = 32.0,
                lineWidth = 180,
                requireLineOfSight = true,
            )
            val nativeNameplateVisibility = NativePaperNameplateVisibilityPolicy(nameplateOptions)
            val nameplateRenderer = lifecycle.own(PaperPlayerNameplates.open(
                plugin = this,
                options = nameplateOptions,
                visibility = PaperNameplateVisibilityPolicy { viewer, target ->
                    nativeNameplateVisibility.canView(viewer, target) &&
                        service?.canViewNameplate(viewer.uniqueId, target.uniqueId) == true
                },
            ))
            val nameplates = TttNameplates(
                registry = nameplateRenderer.registry,
                locale = locale,
                statistics = coordinator::stats,
                onlinePlayer = server::getPlayer,
                refresh = nameplateRenderer::refreshNow,
            )
            val hud = TttHud(this, { settings }, locale, nameplates)
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
            if (settings.packetChatIsolationEnabled) {
                val protocolLib = server.pluginManager.getPlugin("ProtocolLib")
                if (protocolLib?.isEnabled == true) {
                    runCatching { openProtocolLibChatIsolation(this, activeService) }
                        .onSuccess { isolation ->
                            lifecycle.own(isolation)
                            logger.info("ArcEvents packet chat isolation enabled through ProtocolLib ${protocolLib.pluginMeta.version}")
                        }
                        .onFailure { failure ->
                            logger.log(
                                Level.SEVERE,
                                "ArcEvents could not enable packet chat isolation; using Paper isolation only",
                                failure,
                            )
                        }
                } else {
                    logger.warning(
                        "ArcEvents packet chat isolation was requested but ProtocolLib is not enabled; using Paper isolation only",
                    )
                }
            }
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
        runCatching { pluginRuntime?.close() }.onFailure { logger.log(Level.SEVERE, "Could not close ArcEvents runtime", it) }
        pluginRuntime = null
        weaponPointStore = null
        Tasks.reset()
    }

    private fun reloadPlugin(): Result<Unit> = runCatching {
        val dataRoot = dataFolder.toPath()
        val candidate = ArcEventsConfig.inspect(dataRoot)
        val current = settings
        require(candidate.serverId == current.serverId) { "server-id requires a restart" }
        require(candidate.nodeMode == current.nodeMode) { "node-mode requires a restart" }
        require(candidate.hostServer == current.hostServer) { "host-server requires a restart" }
        require(candidate.network.enabled == current.network.enabled) { "network.enabled requires a restart" }
        require(candidate.packetChatIsolationEnabled == current.packetChatIsolationEnabled) {
            "chat.packet-isolation.enabled requires a restart"
        }
        require(candidate.arenas.map { Triple(it.id, it.world, it.template) } == current.arenas.map { Triple(it.id, it.world, it.template) }) {
            "arena ids, worlds and templates require a restart"
        }
        require(candidate.arenas.map { it.enabled } == current.arenas.map { it.enabled }) { "arena enablement requires a restart" }
        require(service?.matchState()?.first == null) { "configuration cannot reload during a reservation or match" }
        weaponPointStore?.let { store ->
            candidate.arenas.forEach { arena ->
                require(store.points(arena).size <= arena.weaponCount) {
                    "arena ${arena.id} has more mandatory weapon points than weapon-count"
                }
            }
        }
        ArcEventsLocale.validateFiles(dataRoot)
        ConfigManager.reloadAll()
        settings = ArcEventsConfig.load(dataRoot)
    }.onFailure { logger.log(Level.WARNING, "ArcEvents reload was rejected", it) }

    private fun saveResourceIfMissing(path: String) {
        if (!Files.isRegularFile(dataFolder.toPath().resolve(path))) saveResource(path, false)
    }

    private companion object {
        const val HEALTH_REPORT_TICKS = 1_200L
    }
}
