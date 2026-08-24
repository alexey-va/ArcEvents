package ru.ruscrafting.events.paper

import com.google.gson.Gson
import kotlinx.coroutines.runBlocking
import org.bukkit.plugin.java.JavaPlugin
import org.slf4j.LoggerFactory
import ru.arc.config.ConfigManager
import ru.arc.core.PaperArcRuntime
import ru.arc.core.Tasks
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

    override fun onEnable() {
        saveDefaultConfig()
        saveResourceIfMissing("lang/ru.yml")
        saveResourceIfMissing("lang/en.yml")
        saveResourceIfMissing("modules/redis.yml")
        PaperArcRuntime.installScheduling(this)
        try {
            val dataRoot = dataFolder.toPath()
            settings = ArcEventsConfig.load(dataRoot)
            ArcEventsLocale.validateFiles(dataRoot)
            locale = ArcEventsLocale(dataRoot) { settings }
            if (settings.arena.template == TttCitadelBlueprint.TEMPLATE) TttCitadelLoot.validate()
            ArenaWorldProvisioner(this).ensureLoaded(settings)
            val redisConfig = ArcEventsRedisBootstrap.load(dataRoot, settings)
            val manager = RedisManager(
                redisConfig.connection(),
                ServerIdentity { settings.serverId },
                LoggerFactory.getLogger("ArcEvents.Redis"),
            )
            if (!manager.isConnected() || !runBlocking { manager.healthCheck() }) error("Redis connection is unavailable")
            redis = manager
            val repository = RedisEventNetworkRepository(manager, Gson())
            val debug = ArcEventsDebug({ settings.debugEnabled }, logger::info)
            val escrow = PlayerStateEscrow(RecoveryBatchStore(dataRoot, Gson()))
            val items = TttItems(this, locale)
            val firearms = TttFirearms(this, locale) { settings }
            val hud = TttHud(this, { settings }, locale)
            val lootScene = TttLootScene(this) { settings }
            val arenaInspector = ArenaRuntimeInspector(this)
            lateinit var activeService: ArcEventsService
            val smokeGrenades = TttSmokeGrenades(
                plugin = this,
                settings = { settings },
                currentMatchId = {
                    activeService.currentMatch()?.takeIf { it.phase == MatchPhase.ACTIVE }?.matchId
                },
                targets = { server.onlinePlayers.filter { activeService.isAlive(it.uniqueId) } },
            )
            val coordinator = EventNetworkCoordinator(
                plugin = this,
                settings = { settings },
                locale = locale,
                repository = repository,
                redis = manager,
                transfer = BungeeBackendTransfer(this),
                debug = debug,
                matchState = { activeService.matchState() },
                arenaReady = { activeService.arenaReady() },
                onReservation = { activeService.onReservation(it) },
                onArrival = { activeService.onArrival(it) },
            )
            network = coordinator
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
                arenaInspector = arenaInspector,
            )
            service = activeService
            val menu = ArcEventsMenu(activeService, items, locale, { settings }, ::reloadPlugin)
            val command = ArcEventsCommand(this, activeService, menu, locale, { settings }, ::reloadPlugin)
            requireNotNull(getCommand("arcevents")).apply {
                setExecutor(command)
                tabCompleter = command
            }
            server.pluginManager.registerEvents(ArcEventsListener(activeService, menu, items), this)
            server.messenger.registerOutgoingPluginChannel(this, BungeeBackendTransfer.CHANNEL)
            coordinator.start()
            activeService.start()
            logger.info(
                "ArcEvents enabled node=${settings.serverId} mode=${settings.nodeMode} host=${settings.hostServer} " +
                    "arenaReady=${activeService.arenaReady()} redisConnected=${manager.isConnected()}",
            )
        } catch (failure: Throwable) {
            logger.log(Level.SEVERE, "ArcEvents failed closed during startup", failure)
            server.pluginManager.disablePlugin(this)
        }
    }

    override fun onDisable() {
        runCatching { service?.close() }.onFailure { logger.log(Level.SEVERE, "Could not close ArcEvents service", it) }
        runCatching { network?.close() }.onFailure { logger.log(Level.SEVERE, "Could not close ArcEvents network", it) }
        runCatching { redis?.close() }.onFailure { logger.log(Level.SEVERE, "Could not close ArcEvents Redis", it) }
        runCatching { server.messenger.unregisterOutgoingPluginChannel(this, BungeeBackendTransfer.CHANNEL) }
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
        require(candidate.arena.world == current.arena.world) { "arena.world requires a restart" }
        require(candidate.arena.template == current.arena.template) { "arena.template requires a restart" }
        if (!current.arena.enabled && candidate.arena.enabled && candidate.arena.template.isNotEmpty()) {
            error("enabling a provisioned arena requires a restart")
        }
        require(service?.matchState()?.first == null) { "configuration cannot reload during a reservation or match" }
        ArcEventsLocale.validateFiles(dataRoot)
        ConfigManager.reloadAll()
        settings = ArcEventsConfig.load(dataRoot)
    }.onFailure { logger.log(Level.WARNING, "ArcEvents reload was rejected", it) }

    private fun saveResourceIfMissing(path: String) {
        if (!Files.isRegularFile(dataFolder.toPath().resolve(path))) saveResource(path, false)
    }
}
