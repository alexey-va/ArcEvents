package ru.ruscrafting.events.paper.apocalypse

import com.google.gson.Gson
import net.kyori.adventure.bossbar.BossBar
import org.bukkit.*
import org.bukkit.attribute.Attribute
import org.bukkit.attribute.AttributeModifier
import org.bukkit.block.Container
import org.bukkit.block.Chest
import org.bukkit.inventory.DoubleChestInventory
import org.bukkit.entity.*
import org.bukkit.event.*
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.event.entity.*
import org.bukkit.event.inventory.*
import org.bukkit.event.player.*
import org.bukkit.event.world.ChunkLoadEvent
import org.bukkit.event.world.ChunkUnloadEvent
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.Inventory
import org.bukkit.loot.LootContext
import ru.ruscrafting.events.domain.apocalypse.source.ApocalypseSourceLootDrop
import ru.ruscrafting.events.domain.apocalypse.source.ApocalypseSourceItemIdentity
import ru.ruscrafting.events.domain.apocalypse.source.ApocalypseWorkbenchRecipes
import ru.ruscrafting.events.domain.apocalypse.source.ApocalypseWorkbenchStation
import org.bukkit.persistence.PersistentDataType
import org.bukkit.plugin.java.JavaPlugin
import ru.arc.core.LifecycleTaskScope
import ru.arc.paper.network.BackendTransfer
import ru.arc.paper.display.PaperPacketDisplays
import ru.arc.paper.chunk.PaperChunkTicketRegistry
import ru.arc.paper.playerstate.PaperPlayerStateService
import ru.arc.paper.playerstate.PaperPlayerStateCodec
import ru.arc.paper.playerstate.PaperLocationSnapshot
import ru.arc.persistence.DurableRecordJournal
import ru.arc.redis.RedisOperations
import ru.ruscrafting.events.config.*
import ru.ruscrafting.events.domain.FirearmId
import ru.ruscrafting.events.domain.apocalypse.*
import ru.ruscrafting.events.network.QueueState
import ru.ruscrafting.events.paper.EventWork
import ru.ruscrafting.events.paper.TttFirearms
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.logging.Level
import kotlin.math.*
import kotlin.random.Random

private data class ApocalypseCheckpoint(
    val format: Int = 1,
    val seed: Long,
    val survival: ApocalypseSurvivalState = ApocalypseSurvivalState(),
    val hordes: Map<String, ApocalypseSurvivalState> = emptyMap(),
    val infections: Map<String, ApocalypseInfectionState> = emptyMap(),
    val switches: Set<String> = emptySet(),
    val spawners: Map<String, ApocalypseBunkerSpawnerState> = emptyMap(),
    val looted: Set<String> = emptySet(),
)

private data class PreparedApocalypseWorld(val journal: DurableRecordJournal<ApocalypseCheckpoint>, val checkpoint: ApocalypseCheckpoint)

private data class ApocalypseGuideRoutes(
    val supplies: List<GuideRoute>, val exit: GuideRoute?, val city: GuideRoute?, val weapons: GuideRoute?,
    val structure: GuideRoute?, val workbench: GuideRoute?,
)

/** Permanent survival is independent of the finite arcade match roster, and owns its entire inventory boundary. */
class ApocalypseRuntime(
    private val plugin: JavaPlugin,
    private val config: () -> ArcEventsConfig,
    private val locale: ArcEventsLocale,
    private val firearms: TttFirearms,
    private val work: EventWork,
    redis: RedisOperations,
    transfer: BackendTransfer,
    private val occupied: (UUID) -> Boolean,
    private val queued: (UUID) -> CompletableFuture<QueueState?>,
    private val returnToOrigin: (Player, String) -> Boolean,
    private val sourceLayout: CompletableFuture<ApocalypseLayout>? = null,
    packetDisplays: PaperPacketDisplays? = null,
    chunkTickets: PaperChunkTicketRegistry,
) : Listener, AutoCloseable {
    private val options = ApocalypseSettings.load(plugin.dataFolder.resolve("apocalypse.yml"))
    private val host = config().nodeMode == NodeMode.HOST
    private val network = ApocalypseNetwork(redis, config, transfer, work)
    private val tasks = LifecycleTaskScope()
    private val players = linkedMapOf<UUID, ApocalypsePlayerRecord>()
    private val active = linkedSetOf<UUID>()
    private val busy = linkedSetOf<UUID>()
    private val moving = linkedSetOf<UUID>()
    private val playerOperations = linkedSetOf<UUID>()
    private val recoveryOperations = linkedSetOf<UUID>()
    private val returnOperations = mutableMapOf<UUID, Any>()
    private val returnReceipts = linkedSetOf<UUID>()
    private val unacknowledgedJoins = mutableSetOf<UUID>()
    private val playerSaves = mutableMapOf<UUID, CompletableFuture<Unit>>()
    private val bars = mutableMapOf<UUID, BossBar>()
    private val guideRequests = mutableSetOf<UUID>()
    private val joining = mutableSetOf<UUID>()
    private val waitingForWorld = mutableSetOf<UUID>()
    private val guideSnapshots = mutableMapOf<UUID, ApocalypseGuideSnapshot>()
    private val trackedGuides = mutableMapOf<UUID, GuideTarget>()
    private val automaticGuides = mutableSetOf<UUID>()
    private val supplySeen = NamespacedKey(plugin, "apocalypse_supply_seen_v1")
    private val guide: ApocalypseGuide = ApocalypseGuide(plugin, locale, ::openGuide, ::trackGuide, ::leave,
        dialogsEnabled = { config().ui.dialogsEnabled },
        canFly = { it.hasPermission("arcevents.admin") }, toggleFly = ::toggleAdminFlight)
    private val actors = linkedMapOf<String, UUID>()
    private val pendingChunks = ArrayDeque<Chunk>()
    private data class SourceTileJob(val chunk: Chunk, val entries: Iterator<ru.ruscrafting.events.domain.apocalypse.source.ApocalypseSourceBlockEntity>)
    private val pendingSourceTiles = ArrayDeque<SourceTileJob>()
    private val sourceTilesDone = NamespacedKey(plugin, "apocalypse_original_tiles_v1")
    private var sourceTileFailures = 0
    private val chunkMarkers = mutableMapOf<Long, List<ApocalypseMarker>>()
    private val stocking = mutableSetOf<String>()
    private val random = Random(options.seed)
    private val bunkerRules = ApocalypseBunkerRules()
    private var committedSwitches = emptySet<String>()
    private var committedSpawns = emptySet<String>()
    private val stocked = NamespacedKey(plugin, "apocalypse_stocked")
    private val originalStocked = NamespacedKey(plugin, "apocalypse_original_loot_v1")
    private val workbenchRecipes = ApocalypseWorkbenchRecipes.load()
    private val workbenchCrafting = ApocalypseWorkbenchCrafting(plugin, locale, firearms, workbenchRecipes)
    private val workbenches = ApocalypseWorkbenchStations(plugin, { world }) {
        ready && isPlaying(it.uniqueId) && !isBusy(it.uniqueId)
    }
    private val workbenchMenu = ApocalypseWorkbenchMenu(plugin, locale, workbenchRecipes, workbenchCrafting) {
        config().ui.dialogsEnabled
    }
    private val atmosphere = ApocalypseAtmosphere(plugin)
    private val sourceShapes = ApocalypseSourceShapeRepairs(plugin, work) { chunk -> !atmosphere.isPending(chunk) }
    private val workbenchDisplays = packetDisplays?.let { displays ->
        ApocalypseWorkbenchDisplays(displays, workbenches) { ready && isPlaying(it.uniqueId) }
    }
    private val sourceMining = ApocalypseSourceMining(plugin, work, chunkTickets, { world },
        active = { ready && isPlaying(it.uniqueId) && !isBusy(it.uniqueId) && !checkpointPending },
        dropFactory = { player, itemId, count -> workbenchCrafting.sourceItem(ApocalypseSourceItemIdentity(itemId), count, player) },
        claimed = { it in state.looted }, claim = ::claimOre, requiredTool = { player, source ->
            message(player, if (source == "the_deeper_void:columbite") "mining.diamond-tool" else "mining.required-tool")
        })
    private val lootItems = ApocalypseLootItems(plugin, locale, firearms, workbenchRecipes)
    private val actorId = NamespacedKey(plugin, "apocalypse_actor")
    private val actorVariant = NamespacedKey(plugin, "apocalypse_variant")
    private val actorSource = NamespacedKey(plugin, "apocalypse_source")
    private val actorProfile = NamespacedKey(plugin, "apocalypse_profile")
    private val infectionSpeed = NamespacedKey(plugin, "apocalypse_infection_speed")
    private val medicine = NamespacedKey(plugin, "apocalypse_medicine")
    private val nativeSession = NamespacedKey(plugin, "apocalypse_session")
    private val playerStates = PaperPlayerStateService()
    private val layout: ApocalypseLayout by lazy { checkNotNull(sourceLayout?.getNow(null)) }
    private var openingWorld = host
    private var worldPreparation: CompletableFuture<PreparedApocalypseWorld>? = null
    private var rules = ApocalypseSurvivalRules(ApocalypseSurvivalSettings(
        hordeEveryDays = options.hordeDays.toInt(), hordePulseTicks = options.hordePulse,
        hordePulseSize = 6, hordeAliveCap = minOf(125, options.maximumEnemies), hordeDurationTicks = options.hordeDuration,
        infectionChance = options.infectionChance,
        infectionStageTicks = options.infectionStage, vaccineImmunityTicks = options.immunity))
    private var state = ApocalypseCheckpoint(seed = options.seed)
    private var storage: ApocalypseStorage? = null
    private var journal: DurableRecordJournal<ApocalypseCheckpoint>? = null
    private var world: World? = null
    private var opened = false
    private var step = 0L
    private var checkpointPending = false
    private var checkpointQueued = false
    private val checkpointWrites = ApocalypseCheckpointWrites(work, { retry -> tasks.runLater(100) { retry() } }) { failure ->
        if (failure != null) log("world-checkpoint", failure) else plugin.logger.info("ArcEvents apocalypse world checkpoint recovered")
    }
    private var saving = CompletableFuture.completedFuture(Unit)
    var closeCompletion: CompletableFuture<Unit> = CompletableFuture.completedFuture(Unit)
        private set
    val ready: Boolean get() = opened && !checkpointWrites.failed && (!host || world != null)
    fun isPlaying(id: UUID): Boolean = id in active
    fun isBusy(id: UUID): Boolean = id in busy
    fun allowsRecoveryCommand(player: Player, command: String): Boolean {
        if (!isBusy(player.uniqueId) || player.uniqueId in joining || player.uniqueId in playerOperations || player.uniqueId in recoveryOperations) return false
        val parts = command.removePrefix("/").trim().lowercase().split(Regex("\\s+"))
        if (parts.firstOrNull() !in setOf("arcevents", "arcevents:arcevents")) return false
        return parts.drop(1) == listOf("leave") || parts.drop(1) in listOf(listOf("apocalypse", "leave"), listOf("apocalypse", "recover"))
    }

    /** Identify native inventory owners before the arcade listeners start admitting players. */
    fun start() {
        check(!opened)
        if (host) {
            requireNotNull(sourceLayout) { "Apocalypse host requires original generation content" }
            storage = ApocalypseStorage(plugin.dataFolder.toPath()).also { players.putAll(it.loadPlayers()) }
            players.filterValues { it.returnTicket != null }.keys.forEach(returnReceipts::add)
        }
        opened = true
        plugin.server.pluginManager.registerEvents(this, plugin)
        tasks.runTimer(1, 1) { tick() }
        tasks.runTimer(1, 100) {
            if (host) {
                network.advertise(ready).exceptionally { failure -> log("heartbeat", failure); null }
                plugin.server.onlinePlayers.forEach { player ->
                    players[player.uniqueId]?.takeIf { it.returnPending }?.let { attemptReturn(player, it) }
                }
                // A successful proxy transfer normally leaves the host before the origin acknowledges it.
                returnReceipts.take(16).forEach { id ->
                    returnReceipts.remove(id)
                    returnReceipts.add(id)
                    if (plugin.server.getPlayer(id) == null) players[id]?.let { reconcileReturn(id, it, null) }
                }
            } else unacknowledgedJoins.toList().mapNotNull(plugin.server::getPlayer).forEach(::acknowledgeReturn)
        }
        plugin.server.onlinePlayers.forEach(::handleJoin)
    }

    private fun prepareWorld(root: Path, folder: Path): PreparedApocalypseWorld {
        val source = layout
        require(source is ApocalypseOriginalLayout && source.seed == options.seed) { "Apocalypse source layout identity changed" }
        val gson = Gson()
        val checkpointJournal = DurableRecordJournal(root, Path.of("data/apocalypse/world-${options.world}"), 32L * 1024 * 1024,
            { s: ApocalypseCheckpoint -> gson.toJson(s).toByteArray(Charsets.UTF_8) },
            { b -> gson.fromJson(b.toString(Charsets.UTF_8), ApocalypseCheckpoint::class.java) },
            { s ->
                require(s.format == 1 && s.seed == options.seed)
                s.survival.validated()
                require(s.infections.size <= 100000 && s.switches.size <= 100000 && s.spawners.size <= 100000)
                s.infections.forEach { (id, infection) -> UUID.fromString(id); infection.validated() }
                s.hordes.forEach { (id, horde) -> UUID.fromString(id); horde.validated() }
                s.spawners.values.forEach { it.validated() }
            })
        val checkpoint = checkpointJournal.loadOrNull("world") ?: ApocalypseCheckpoint(seed = options.seed)
        val marker = folder.resolve(".arcevents-template")
        val identity = "${ApocalypseOriginalGenerator.TEMPLATE}:${options.seed}"
        if (Files.exists(folder, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            require(!Files.isSymbolicLink(folder) && Files.isRegularFile(marker) && !Files.isSymbolicLink(marker)) {
                "Apocalypse world exists without its generator ownership marker: ${options.world}"
            }
            require(Files.readString(marker).trim() == identity) { "Apocalypse world seed/generator identity changed" }
        } else {
            Files.createDirectory(folder)
            Files.writeString(marker, identity, java.nio.file.StandardOpenOption.CREATE_NEW)
        }
        return PreparedApocalypseWorld(checkpointJournal, checkpoint)
    }

    private fun openWorld(prepared: PreparedApocalypseWorld) {
        val target = plugin.server.getWorld(options.world) ?: requireNotNull(WorldCreator(options.world).seed(options.seed)
            .generator(ApocalypseOriginalGenerator(options.seed, requireNotNull(sourceLayout))).generateStructures(false).createWorld())
        require(target.generator is ApocalypseOriginalGenerator && target.seed == options.seed) { "Loaded Apocalypse world has a different generator or seed" }
        target.difficulty = Difficulty.HARD
        target.setGameRule(GameRule.DO_MOB_SPAWNING, false)
        target.setGameRule(GameRule.DO_DAYLIGHT_CYCLE, false)
        target.setGameRule(GameRule.DO_INSOMNIA, false)
        // Hordes spawn 75 blocks away; tiny lobby distances must not disable the survival world.
        target.viewDistance = maxOf(target.viewDistance, 8)
        target.simulationDistance = maxOf(target.simulationDistance, 6)
        target.setSpawnLocation(Location(target, layout.spawn.x + .5, layout.spawn.y.toDouble(), layout.spawn.z + .5, 180f, 0f))
        target.loadedChunks.forEach(pendingChunks::addLast)
        workbenches.register()
        workbenchDisplays?.register(plugin)
        sourceMining.register()
        workbenches.registerTungstenBlasting(
            workbenchCrafting.sourceItem(ApocalypseSourceItemIdentity("the_deeper_void:raw_tungsten")),
            workbenchCrafting.sourceItem(ApocalypseSourceItemIdentity("the_deeper_void:tungsten_ingot")))
        state = prepared.checkpoint
        committedSwitches = state.switches
        committedSpawns = desiredSpawns(state).keys
        journal = prepared.journal
        world = target
        plugin.logger.info("ArcEvents Apocalypse ready world=${target.name} generator=${ApocalypseOriginalGenerator.TEMPLATE}")
    }

    /** Returns true when the command belongs to this persistent mode. */
    fun command(player: Player, arguments: List<String>): Boolean {
        val action = arguments.firstOrNull()?.lowercase()
        if (action != "apocalypse" && !isPlaying(player.uniqueId) && !isBusy(player.uniqueId)) return false
        if (player.uniqueId in joining) { message(player, "pending"); return true }
        if (isBusy(player.uniqueId)) {
            val recovery = action == "leave" || action == "apocalypse" && arguments.getOrNull(1)?.lowercase() in setOf("leave", "recover")
            if (recovery && player.uniqueId !in playerOperations && player.uniqueId !in recoveryOperations) retryRecovery(player)
            else message(player, "pending")
            return true
        }
        if (action == "apocalypse" && host && world == null && !arguments.getOrNull(1).equals("qa", true)) {
            message(player, "unavailable")
            return true
        }
        if (action == "leave") { leave(player); return true }
        if (action == "status") { status(player); return true }
        if (isPlaying(player.uniqueId) && action in setOf(null, "menu", "help")) { openGuide(player); return true }
        if (action == "apocalypse") {
            when (arguments.getOrNull(1)?.lowercase()) {
                "fly" -> toggleAdminFlight(player)
                "bench" -> workbenchMenu.command(player, arguments.drop(2))
                "guide", "help" -> openGuide(player)
                "track" -> {
                    val target = arguments.getOrNull(2)?.uppercase()?.let { runCatching { GuideTarget.valueOf(it) }.getOrNull() }
                    if (target == null) openGuide(player) else trackGuide(player, target)
                }
                "qa" -> if (player.hasPermission("arcevents.qa")) player.sendMessage(qaStatus()) else message(player, "denied")
                "markers" -> if (host && player.hasPermission("arcevents.qa")) {
                    val kind = arguments.getOrNull(2)?.uppercase()
                    val x = player.location.blockX; val z = player.location.blockZ
                    work.compute {
                        layout.sitesNear(x, z).asSequence().flatMap { layout.markers(it).asSequence() }
                            .filter { marker -> when (kind) {
                                "WEAPONS" -> marker.lootTable?.let { (layout as? ApocalypseOriginalLayout)?.lootCatalog?.hasFirearm(it) } == true
                                "AMMO" -> marker.lootTable?.substringAfterLast('/') in setOf("ammo_chest", "ammo_tacz", "ammo_tacz_2", "tacz_ammochest")
                                "CRATES" -> marker.sourceBlock in setOf("jeffs_cursed_walking_structures:gun_part_chest", "jeffs_cursed_walking_structures:create_part_chest")
                                else -> kind == null || marker.kind.name == kind
                            } }.take(12).toList()
                    }.thenCompose { entries -> work.onMain {
                        if (player.isOnline) entries.forEach {
                            player.sendMessage("ARCEVENTS_APOCALYPSE_MARKER kind=${it.kind} x=${it.x} y=${it.y} z=${it.z} loot_table=${it.lootTable ?: "-"} source_block=${it.sourceBlock ?: "-"}")
                        }
                    } }.exceptionally { log("source-marker-read", it); null }
                } else message(player, "denied")
                "time" -> {
                    val ticks = arguments.getOrNull(2)?.toLongOrNull()
                    if (!host || !player.hasPermission("arcevents.debug") || !config().debugEnabled || ticks == null || ticks !in state.survival.worldTick..240000000L)
                        message(player, "denied")
                    else { state = state.copy(survival = state.survival.copy(worldTick = ticks)); checkpoint(); player.sendMessage(qaStatus()) }
                }
                "status", "info" -> status(player)
                "leave" -> leave(player)
                "locate" -> {
                    if (!player.hasPermission("arcevents.admin") || !host) message(player, "denied")
                    else {
                        val kind = arguments.getOrNull(2)?.uppercase()
                        val site = layout.sitesNear(player.location.blockX, player.location.blockZ)
                            .firstOrNull { kind == null || it.kind.name == kind }
                        message(player, "location", "location" to (site?.let { "${it.kind} ${it.anchor.x} ${it.anchor.y} ${it.anchor.z}" } ?: "—"))
                    }
                }
                "day" -> {
                    val day = arguments.getOrNull(2)?.toLongOrNull()
                    if (!host || !player.hasPermission("arcevents.admin") || day == null || day !in 1..10000) message(player, "denied")
                    else {
                        state = state.copy(survival = state.survival.copy(worldTick = maxOf(state.survival.worldTick, day * 24000)))
                        checkpoint(); status(player)
                    }
                }
                else -> if (isPlaying(player.uniqueId)) openGuide(player) else enter(player)
            }
            return true
        }
        if (player.hasPermission("arcevents.admin")) return false
        message(player, "leave-first")
        return true
    }

    private fun toggleAdminFlight(player: Player) {
        if (!host || !isPlaying(player.uniqueId) || isBusy(player.uniqueId) || player.world != world ||
            !player.hasPermission("arcevents.admin")) {
            message(player, "fly.denied")
            return
        }
        val enabled = !player.allowFlight
        player.allowFlight = enabled
        player.isFlying = enabled
        player.fallDistance = 0f
        message(player, if (enabled) "fly.enabled" else "fly.disabled")
    }

    private fun openWorkbench(player: Player, block: org.bukkit.block.Block, station: ApocalypseWorkbenchStation) {
        val point = ApocalypsePoint(block.x, block.y, block.z)
        val origin = players[player.uniqueId]?.origin ?: return
        workbenchMenu.open(player, station,
            canUse = { players[player.uniqueId]?.origin == origin && workbenches.isUsable(player, point, station) },
            onCraft = { actor, recipe -> craft(actor, point, station, recipe, origin) },
            onVanillaCraft = if (station != ApocalypseWorkbenchStation.CRAFTING) null else { actor ->
                if (players[actor.uniqueId]?.origin == origin && workbenches.isUsable(actor, point, station))
                    actor.openWorkbench(block.location, false)
            })
    }

    /** A new native session fences the accepted post-craft snapshot without another durable format. */
    private fun craft(player: Player, point: ApocalypsePoint, station: ApocalypseWorkbenchStation, recipeId: String,
        origin: ru.arc.paper.playerstate.PaperPlayerStateEnvelope) {
        val id = player.uniqueId
        val record = players[id]
        val recipe = workbenchRecipes.find(recipeId)
        if (record?.phase != ApocalypsePlayerPhase.ACTIVE || record.origin != origin ||
            !ownsNativeInventory(player, record) || !workbenches.isUsable(player, point, station) ||
            recipe == null || !workbenchRecipes.availableAt(recipeId, station) || !player.itemOnCursor.isEmpty) {
            workbenchMenu.complete(player, recipeId, WorkbenchFeedback.UNAVAILABLE)
            return
        }
        val plan = try { workbenchCrafting.plan(recipe, player.inventory.storageContents, player) }
        catch (failure: Throwable) {
            log("workbench-plan player=$id recipe=$recipeId", failure)
            workbenchMenu.complete(player, recipeId, WorkbenchFeedback.UNAVAILABLE)
            return
        }
        if (plan !is ApocalypseWorkbenchCraftResult.Ready) {
            workbenchMenu.complete(player, recipeId,
                if (plan is ApocalypseWorkbenchCraftResult.Full) WorkbenchFeedback.FULL else WorkbenchFeedback.MISSING)
            return
        }
        val session = record.nativeSession
        val after: ru.arc.paper.playerstate.PaperPlayerStateSnapshot
        val accepted: ApocalypsePlayerRecord
        try {
            after = playerStates.capture(player, System.currentTimeMillis()).copy(storage = plan.afterStorage)
            accepted = record.copy(expedition = PaperPlayerStateCodec().encode(after), nativeSession = UUID.randomUUID().toString())
        } catch (failure: Throwable) {
            log("workbench-capture player=$id recipe=$recipeId", failure)
            workbenchMenu.complete(player, recipeId, WorkbenchFeedback.UNAVAILABLE)
            return
        }
        if (!busy.add(id)) return
        playerOperations += id
        savePlayer(accepted).thenCompose { work.onMain {
            // A quick reconnect has a different native Player. Its join reconciles the committed journal.
            if (!opened || !player.isOnline || plugin.server.getPlayer(id) !== player) return@onMain
            check(players[id] == accepted && player.world == world &&
                player.persistentDataContainer.get(nativeSession, PersistentDataType.STRING) == session)
            playerStates.restoreInventoryAndVerify(player, after)
            player.persistentDataContainer.set(nativeSession, PersistentDataType.STRING, requireNotNull(accepted.nativeSession))
            player.saveData()
            busy -= id
            workbenchMenu.complete(player, recipeId, WorkbenchFeedback.CRAFTED)
        } }.whenComplete { _, failure -> work.onMain {
            if (!currentPlayer(player)) return@onMain
            playerOperations -= id
            if (failure != null) {
                log("workbench-craft player=$id recipe=$recipeId", failure)
                if (plugin.server.getPlayer(id) === player && player.isOnline) message(player, "restore-pending")
            }
        } }
    }

    private fun claimOre(id: String): CompletableFuture<Boolean> {
        if (!ready || checkpointPending || id in state.looted) return CompletableFuture.completedFuture(false)
        state = state.copy(looted = state.looted + id)
        checkpoint()
        return saving.thenApply { true }
    }

    fun qaStatus(): String {
        val spawn = world?.let { layout.spawn }
        val generation = if (spawn == null) "-" else ApocalypseOriginalGenerator.TEMPLATE
        return "ARCEVENTS_APOCALYPSE ready=$ready world=${world?.key ?: "-"} day=${rules.dayAt(state.survival.worldTick)} tick=${state.survival.worldTick} players=${active.size} busy=${busy.size} enemies=${actors.size} hordes=${state.hordes.values.count { it.horde != null }} switches=${state.switches.size} spawners=${state.spawners.size} bunker_enemies=${actors.keys.count { ":bunker:" in it }} generation=$generation spawn_x=${spawn?.x ?: "-"} spawn_y=${spawn?.y ?: "-"} spawn_z=${spawn?.z ?: "-"} tile_failures=$sourceTileFailures"
    }

    fun status(player: Player) {
        player.sendMessage(locale.render("apocalypse.status", player, mapOf(
            "day" to locale.text(rules.dayAt(state.survival.worldTick)),
            "phase" to locale.render("apocalypse.phase.${rules.phaseAt(state.survival.worldTick).name.lowercase()}", player),
            "players" to locale.text(active.size), "enemies" to locale.text(actors.size),
            "infection" to locale.render("apocalypse.infection.${(state.infections[player.uniqueId.toString()]?.stage ?: ApocalypseInfectionStage.HEALTHY).name.lowercase()}", player))))
    }

    private fun openGuide(player: Player): Unit = prepareGuide(player) { guide.show(player, it) }

    private fun trackGuide(player: Player, target: GuideTarget): Unit = prepareGuide(player) { snapshot ->
        val from = ApocalypsePoint(player.location.blockX, player.location.blockY, player.location.blockZ)
        if (snapshot.route(target, from) == null) message(player, "guide.route-unavailable")
        else {
            automaticGuides -= player.uniqueId
            trackedGuides[player.uniqueId] = target
            val tracking = snapshot.copy(tracked = target)
            guideSnapshots[player.uniqueId] = tracking
            guide.announceRoute(player, tracking)
        }
    }

    private fun unopenedSupplies(player: Player): List<ApocalypseMarker> {
        val original = layout as? ApocalypseOriginalLayout ?: return emptyList()
        val seen = player.persistentDataContainer.get(supplySeen, PersistentDataType.STRING)
            ?.takeIf { it.startsWith("${world?.uid}|") }?.substringAfter('|')?.split(',')?.toSet().orEmpty()
        return original.starterSupplyMarkers.filter { "${it.x}:${it.y}:${it.z}" !in seen }
    }

    private fun updateNavigation(player: Player) {
        val id = player.uniqueId
        if (id in trackedGuides && id !in automaticGuides) return
        val inside = isInStarter(player)
        val next = if (inside) {
            if (unopenedSupplies(player).isNotEmpty()) GuideTarget.SUPPLIES else GuideTarget.EXIT
        } else guideSnapshots[id]?.surfaceTarget() ?: GuideTarget.CITY
        if (trackedGuides[id] == next && id in guideSnapshots) return
        prepareGuide(player) { snapshot ->
            val target = if (inside) next else snapshot.surfaceTarget() ?: return@prepareGuide
            if (id in trackedGuides && id !in automaticGuides) return@prepareGuide
            automaticGuides += id
            trackedGuides[id] = target
            val tracking = snapshot.copy(tracked = target)
            guideSnapshots[id] = tracking
            guide.announceRoute(player, tracking)
        }
    }

    /** Source planning may assemble many pieces; only the final small presentation reads native player state. */
    private fun prepareGuide(player: Player, show: (ApocalypseGuideSnapshot) -> Unit) {
        if (!isPlaying(player.uniqueId) || isBusy(player.uniqueId) || player.world != world) {
            message(player, "unavailable"); return
        }
        if (!guideRequests.add(player.uniqueId)) return
        val id = player.uniqueId
        val x = player.location.blockX; val z = player.location.blockZ
        val unvisited = unopenedSupplies(player).map(::markerId).toSet()
        work.compute {
            val original = layout as? ApocalypseOriginalLayout
            val supplies = (original?.starterSupplyMarkers ?: layout.markers(layout.starter)
                .filter { it.kind.name in setOf("LOOT", "MEDICAL") }).map {
                    GuideRoute(ApocalypsePoint(it.x, it.y, it.z), markerId(it) !in unvisited)
                }
            val sites = layout.sitesNear(x, z)
            val city = sites.firstOrNull { it.kind == ApocalypseSiteKind.CITY }?.anchor?.let(::GuideRoute)
            val weapons = sites.asSequence().filter { it.kind in setOf(ApocalypseSiteKind.LAB, ApocalypseSiteKind.REACTOR, ApocalypseSiteKind.OIL_RIG) }
                .take(12).firstNotNullOfOrNull { site -> layout.markers(site).firstOrNull {
                    if (original == null) it.kind.name in setOf("ARMORY", "CORE")
                    else it.lootTable?.let(original.lootCatalog::hasFirearm) == true
                }?.let { GuideRoute(ApocalypsePoint(it.x, it.y, it.z)) } }
            val structure = sites.asSequence().filter { it.kind != ApocalypseSiteKind.STARTER_BUNKER }.take(12)
                .firstNotNullOfOrNull { site -> layout.markers(site).firstOrNull {
                    it.kind.name in setOf("LOOT", "MEDICAL", "ARMORY", "CORE")
                }?.let { GuideRoute(ApocalypsePoint(it.x, it.y, it.z)) } }
            ApocalypseGuideRoutes(supplies, original?.starterExit?.let(::GuideRoute) ?: GuideRoute(layout.starter.anchor),
                city, weapons, structure, original?.starterCraftingTable?.let(::GuideRoute))
        }.whenComplete { routes, failure -> work.onMain {
            if (!currentPlayer(player)) return@onMain
            guideRequests -= id
            if (failure != null) { log("guide-plan", failure); if (player.isOnline) message(player, "unavailable"); return@onMain }
            if (!opened || !player.isOnline || !isPlaying(id) || isBusy(id) || player.world != world) return@onMain
            val day = rules.dayAt(state.survival.worldTick)
            val horde = state.hordes[id.toString()]
            val nextHorde = horde?.horde?.day ?: horde?.nextHordeDay?.takeIf { it > 0 }
                ?: (((day / options.hordeDays) + 1) * options.hordeDays)
            val snapshot = ApocalypseGuideSnapshot(day, nextHorde,
                state.infections[id.toString()]?.stage ?: ApocalypseInfectionStage.HEALTHY,
                player.inventory.storageContents.any { item -> item != null && !item.isEmpty &&
                    item.itemMeta.persistentDataContainer.get(NamespacedKey(plugin, "world_firearm"), PersistentDataType.STRING)
                        ?.let { runCatching { FirearmId.valueOf(it) }.isSuccess } == true },
                player.inventory.storageContents.sumOf { item -> if (item != null &&
                    (firearms.isWorldAmmunition(item) || item.isSimilar(ItemStack.of(Material.IRON_NUGGET)))) item.amount else 0 },
                isInStarter(player),
                routes.supplies.map { route -> route.copy(claimed = unopenedSupplies(player).none {
                    it.x == route.point.x && it.y == route.point.y && it.z == route.point.z
                }) }, routes.exit, routes.city, routes.weapons, trackedGuides[id],
                workbench = routes.workbench, structure = routes.structure)
            guideSnapshots[id] = snapshot
            show(snapshot)
        } }
    }

    fun enter(player: Player, originServer: String = config().serverId, queueChecked: Boolean = false) {
        if (player.uniqueId in joining || isBusy(player.uniqueId)) { message(player, "pending"); return }
        if (isPlaying(player.uniqueId)) { openGuide(player); return }
        if (!ready) { message(player, "unavailable"); rejectArrival(player, originServer); return }
        if (!queueChecked) {
            if (!busy.add(player.uniqueId)) { message(player, "pending"); return }
            playerOperations += player.uniqueId
            queued(player.uniqueId).whenComplete { queueState, failure -> work.onMain {
                if (!currentPlayer(player)) return@onMain
                playerOperations -= player.uniqueId
                busy -= player.uniqueId
                if (failure != null) log("queue-check", failure)
                val reason = when {
                    failure != null -> "queue-read-failed"
                    queueState == QueueState.QUEUED -> "queued"
                    queueState != null -> "route-pending"
                    else -> null
                }
                if (reason == null) enter(player, originServer, queueChecked = true)
                else { message(player, reason); rejectArrival(player, originServer) }
            } }
            return
        }
        val denied = when {
            player.isDead -> "respawn-first"
            occupied(player.uniqueId) -> "occupied"
            host && active.size >= options.maximumPlayers -> "full"
            else -> null
        }
        if (denied != null) { message(player, denied); rejectArrival(player, originServer); return }
        if (!host) {
            busy += player.uniqueId
            network.enter(player) { currentPlayer(player) }.whenComplete { ticket, failure -> work.onMain {
                if (!currentPlayer(player)) return@onMain
                busy -= player.uniqueId
                if (failure != null) log("transfer", failure)
                if (failure != null || ticket == null) message(player, "unavailable")
                else { message(player, "pending"); retryEntry(player, ticket) }
            } }
            return
        }
        val previous: ApocalypsePlayerRecord?
        val destination: PaperLocationSnapshot?
        try {
            previous = players[player.uniqueId]?.let { record ->
                if (record.phase == ApocalypsePlayerPhase.PARKED) record.copy(expedition = normalizeExpedition(record.expedition)) else record
            }
            destination = previous?.takeIf { it.phase == ApocalypsePlayerPhase.PARKED }?.expedition?.let { PaperPlayerStateCodec().decode(it).location }
        } catch (failure: Throwable) {
            log("entry-plan player=${player.uniqueId}", failure)
            message(player, "unavailable")
            rejectArrival(player, originServer)
            return
        }
        if (previous?.returnPending == true && originServer == config().serverId) {
            players[player.uniqueId]?.let { attemptReturn(player, it) }
            message(player, "pending")
            return
        }
        if (!busy.add(player.uniqueId)) return
        playerOperations += player.uniqueId
        if (previous != null && previous.phase != ApocalypsePlayerPhase.PARKED) {
            playerOperations -= player.uniqueId; recoverOrigin(player, previous); return
        }
        val target = requireNotNull(world)
        val targetX = destination?.x?.let { floor(it).toInt() } ?: layout.spawn.x
        val targetZ = destination?.z?.let { floor(it).toInt() } ?: layout.spawn.z
        var savingOrigin = false
        work.onMain { target.getChunkAtAsync(targetX shr 4, targetZ shr 4, true) }.thenCompose { it }.thenCompose {
            work.onMain {
                check(ready && currentPlayer(player) && !player.isDead && !occupied(player.uniqueId))
                val record = ApocalypsePlayerRecord(player.uniqueId.toString(), originServer, ApocalypsePlayerPhase.ACTIVE,
                    playerStates.captureEnvelope(player, System.currentTimeMillis()), previous?.expedition,
                    nativeSession = UUID.randomUUID().toString())
                savingOrigin = true
                savePlayer(record).thenCompose { work.onMain {
                    check(ready && currentPlayer(player) && !player.isDead && !occupied(player.uniqueId))
                    moving += player.uniqueId
                    try {
                        if (record.expedition != null) playerStates.restoreAndVerify(player, record.expedition, target) { p, at -> p.teleport(at) }
                        else {
                            check(player.teleport(location(layout.spawn)))
                            player.inventory.clear(); player.inventory.armorContents = arrayOfNulls(4)
                            player.setItemOnCursor(null); player.inventory.setItemInOffHand(null)
                            player.gameMode = GameMode.SURVIVAL; player.allowFlight = false
                            player.health = player.getAttribute(Attribute.MAX_HEALTH)!!.value
                            player.foodLevel = 20; player.saturation = 5f
                            player.level = 0; player.exp = 0f; player.totalExperience = 0
                            player.activePotionEffects.forEach { player.removePotionEffect(it.type) }
                            // CW's active Starter Kit: fire axe (42 wear), bread9, vaccine6, boots, mask, binoculars.
                            val axe = ItemStack.of(Material.IRON_AXE).also { item -> item.editMeta(org.bukkit.inventory.meta.Damageable::class.java) { it.damage = 42 } }
                            player.inventory.boots = ItemStack.of(Material.IRON_BOOTS)
                            player.inventory.helmet = ItemStack.of(Material.LEATHER_HELMET)
                            player.inventory.addItem(axe, ItemStack.of(Material.BREAD, 9), vaccine().asQuantity(6), ItemStack.of(Material.SPYGLASS))
                        }
                        // Only a completely applied loadout receives a native admission marker.
                        player.persistentDataContainer.set(nativeSession, PersistentDataType.STRING, requireNotNull(record.nativeSession))
                        constrainExpeditionFlight(player)
                        player.saveData()
                        players[player.uniqueId] = record
                        active += player.uniqueId
                        busy -= player.uniqueId
                        announceArrival(player)
                    } finally { moving -= player.uniqueId }
                } }
            }.thenCompose { it }
        }.whenComplete { _, failure -> work.onMain {
            if (!currentPlayer(player)) return@onMain
            playerOperations -= player.uniqueId
            if (failure != null) {
                log("entry player=${player.uniqueId} world=${target.name} chunk=${targetX shr 4},${targetZ shr 4} originWrite=$savingOrigin", failure)
                if (!savingOrigin) {
                    busy -= player.uniqueId
                    message(player, "unavailable")
                    rejectArrival(player, originServer)
                } else if (player.uniqueId in busy) {
                    message(player, "restore-pending")
                    retryRecovery(player, originServer)
                }
            }
        } }
    }

    private fun retryEntry(player: Player, ticket: String) {
        tasks.runLater(100) {
            if (opened && currentPlayer(player)) network.retryEntry(player, ticket) { currentPlayer(player) }
                .whenComplete { pending, failure -> work.onMain {
                    if (!opened || !currentPlayer(player)) return@onMain
                    if (failure != null) { log("transfer-retry player=${player.uniqueId}", failure); message(player, "unavailable") }
                    else if (pending == true) retryEntry(player, ticket)
                } }
        }
    }

    fun leave(player: Player) {
        val record = players[player.uniqueId] ?: return
        if (record.phase == ApocalypsePlayerPhase.PARKED) {
            if (record.returnPending) attemptReturn(player, record)
            return
        }
        if (!isPlaying(player.uniqueId) || record.phase != ApocalypsePlayerPhase.ACTIVE || record.origin == null) return
        if (player.isDead || isBusy(player.uniqueId)) { message(player, "pending"); return }
        val exiting = try { record.copy(phase = ApocalypsePlayerPhase.RETURNING, expedition = captureExpedition(player)) }
        catch (failure: Throwable) { log("leave-capture player=${player.uniqueId}", failure); message(player, "unavailable"); return }
        if (!busy.add(player.uniqueId)) return
        workbenchMenu.forget(player)
        playerOperations += player.uniqueId
        savePlayer(exiting).thenCompose { work.onMain { recoverOrigin(player, exiting) } }.whenComplete { _, failure -> work.onMain {
            if (!currentPlayer(player)) return@onMain
            playerOperations -= player.uniqueId
            if (failure != null) { log("leave player=${player.uniqueId}", failure); message(player, "restore-pending") }
        } }
    }

    private fun retryRecovery(player: Player, rejectedOrigin: String? = null) {
        if (!host) { message(player, "restore-pending"); return }
        if (!recoveryOperations.add(player.uniqueId)) return
        waitingForWorld -= player.uniqueId
        val pending = playerSaves[player.uniqueId]
        (pending ?: CompletableFuture.completedFuture(Unit)).handle { _, _ -> Unit }
            .thenCompose { work.compute { storage!!.load(player.uniqueId) } }.whenComplete { record, failure -> work.onMain {
            if (!currentPlayer(player)) return@onMain
            recoveryOperations -= player.uniqueId
            if (failure != null) { log("recovery-read player=${player.uniqueId}", failure); message(player, "restore-pending") }
            else {
                if (record == null) players.remove(player.uniqueId) else players[player.uniqueId] = record
                if (pending?.isDone == true) playerSaves.remove(player.uniqueId, pending)
                when (joinRecovery(player, record)) {
                    ApocalypseJoinRecovery.NONE -> {
                        busy -= player.uniqueId
                        if (record?.returnPending == true) attemptReturn(player, record)
                        else if (rejectedOrigin != null) rejectArrival(player, rejectedOrigin)
                    }
                    ApocalypseJoinRecovery.QUARANTINE -> message(player, "restore-pending")
                    ApocalypseJoinRecovery.ORIGIN -> recoverOrigin(player, requireNotNull(record))
                    ApocalypseJoinRecovery.RESUME -> {
                        // The native expedition still exists after an unacknowledged leave write: capture it again.
                        val exiting = try { requireNotNull(record).copy(phase = ApocalypsePlayerPhase.RETURNING, expedition = captureExpedition(player)) }
                        catch (problem: Throwable) { log("recovery-capture player=${player.uniqueId}", problem); message(player, "restore-pending"); return@onMain }
                        playerOperations += player.uniqueId
                        savePlayer(exiting).thenCompose { work.onMain { recoverOrigin(player, exiting) } }.whenComplete { _, problem -> work.onMain {
                            if (!currentPlayer(player)) return@onMain
                            playerOperations -= player.uniqueId
                            if (problem != null) { log("recovery-save", problem); message(player, "restore-pending") }
                        } }
                    }
                }
            }
        } }
    }

    private fun recoverOrigin(player: Player, record: ApocalypsePlayerRecord) {
        if (!currentPlayer(player)) return
        if (!recoveryOperations.add(player.uniqueId)) return
        if (!player.isOnline || player.isDead) {
            recoveryOperations -= player.uniqueId
            return
        }
        moving += player.uniqueId
        try {
            player.getAttribute(Attribute.MOVEMENT_SPEED)?.removeModifier(infectionSpeed)
            playerStates.restoreAndVerify(player, requireNotNull(record.origin), null) { p, at -> p.teleport(at) }
            player.persistentDataContainer.remove(nativeSession)
            player.saveData()
            active -= player.uniqueId
            guideSnapshots.remove(player.uniqueId)
            trackedGuides.remove(player.uniqueId)
            automaticGuides.remove(player.uniqueId)
            bars.remove(player.uniqueId)?.let(player::hideBossBar)
            // Retain the root envelope after return: native saveData has no disk acknowledgement.
            // A stale native expedition marker on a later login can replay this exact origin.
            val parked = record.copy(phase = ApocalypsePlayerPhase.PARKED, returnTicket = null,
                returnPending = config().network.returnToOrigin && record.originServer != config().serverId)
            savePlayer(parked).thenCompose { work.onMain {
                if (!currentPlayer(player)) return@onMain
                busy -= player.uniqueId
                recoveryOperations -= player.uniqueId
                message(player, "returned")
                attemptReturn(player, parked)
            } }.whenComplete { _, failure -> if (failure != null) work.onMain {
                if (!currentPlayer(player)) return@onMain
                recoveryOperations -= player.uniqueId
                log("return-ack player=${player.uniqueId}", failure); message(player, "restore-pending")
            } }
        } catch (failure: Throwable) {
            recoveryOperations -= player.uniqueId
            log("restore player=${player.uniqueId}", failure); message(player, "restore-pending")
        } finally { moving -= player.uniqueId }
    }

    private fun reconcileJoin(player: Player) {
        if (!currentPlayer(player)) return
        network.arrival(player.uniqueId).whenComplete { origin, failure -> work.onMain {
            if (!currentPlayer(player)) return@onMain
            if (failure != null) log("arrival player=${player.uniqueId}", failure)
            if (!player.isOnline) return@onMain
            // A disconnect can race an accepted craft's worker write. Wait for that generation,
            // then read the journal: a failed/unknown write must not resume stale in-memory items.
            val pending = playerSaves[player.uniqueId] ?: CompletableFuture.completedFuture(Unit)
            pending.handle { _, _ -> Unit }.thenCompose { work.compute { requireNotNull(storage).load(player.uniqueId) } }
                .whenComplete { record, readFailure -> work.onMain join@ {
                    if (!opened || !player.isOnline || plugin.server.getPlayer(player.uniqueId) !== player) return@join
                    if (readFailure != null) {
                        joining -= player.uniqueId
                        busy += player.uniqueId
                        log("arrival-journal player=${player.uniqueId}", readFailure)
                        message(player, "restore-pending")
                        return@join
                    }
                    if (record == null) players.remove(player.uniqueId) else players[player.uniqueId] = record
                    if (pending.isDone) playerSaves.remove(player.uniqueId, pending)
                    joining -= player.uniqueId
                    // Arrival was consumed before native resume or origin replay can return early.
                    when (joinRecovery(player, record)) {
                        ApocalypseJoinRecovery.RESUME -> if (ready) resumeNative(player, requireNotNull(record)) else {
                            busy += player.uniqueId
                            if (openingWorld) waitingForWorld += player.uniqueId else retryRecovery(player)
                        }
                        ApocalypseJoinRecovery.ORIGIN -> { busy += player.uniqueId; recoverOrigin(player, requireNotNull(record)) }
                        ApocalypseJoinRecovery.QUARANTINE -> { busy += player.uniqueId; message(player, "restore-pending") }
                        ApocalypseJoinRecovery.NONE -> {
                            busy -= player.uniqueId
                            if (failure == null && origin != null) enter(player, origin)
                            else if (record?.returnPending == true) attemptReturn(player, record)
                        }
                    }
                } }
        } }
    }

    private fun ownsNativeInventory(player: Player, record: ApocalypsePlayerRecord): Boolean =
        record.nativeSession != null && player.persistentDataContainer.get(nativeSession, PersistentDataType.STRING) == record.nativeSession

    private fun pendingApocalypseInventory(player: Player): Boolean =
        players[player.uniqueId]?.phase?.let { it != ApocalypsePlayerPhase.PARKED } == true ||
            playerSaves[player.uniqueId]?.let { !it.isDone || it.isCompletedExceptionally } == true ||
            player.persistentDataContainer.has(nativeSession) || host && player.world.name == options.world

    private fun currentPlayer(player: Player): Boolean = plugin.server.getPlayer(player.uniqueId) === player && player.isOnline

    private fun joinRecovery(player: Player, record: ApocalypsePlayerRecord?) = apocalypseJoinRecovery(
        record?.phase, record?.origin != null, record?.nativeSession,
        player.persistentDataContainer.get(nativeSession, PersistentDataType.STRING), host && player.world.name == options.world)

    private fun captureExpedition(player: Player): ru.arc.paper.playerstate.PaperPlayerStateEnvelope {
        var snapshot = playerStates.capture(player, System.currentTimeMillis())
        // When the host is cold, keep the exact native inventory. normalizeExpedition repairs a
        // fallback-world location on the next admitted entry once the source world's identity is known.
        val target = world
        if (target != null && player.world != target) {
            snapshot = snapshot.copy(location = PaperLocationSnapshot(target.uid, target.name,
                layout.spawn.x + .5, layout.spawn.y.toDouble(), layout.spawn.z + .5, 180f, 0f), fallDistance = 0f)
        }
        return PaperPlayerStateCodec().encode(snapshot)
    }

    private fun isInStarter(player: Player): Boolean {
        val position = player.location
        val point = ApocalypsePoint(position.blockX, position.blockY, position.blockZ)
        val original = layout as? ApocalypseOriginalLayout
        val pieces = original?.structure(original.starter)?.pieces
        return if (pieces.isNullOrEmpty()) point == layout.spawn else
            point.y < pieces.first().bounds.minY && pieces.any { it.bounds.contains(point.x, point.y, point.z) }
    }

    private fun announceArrival(player: Player) {
        guide.entryNotice(player, isInStarter(player) && unopenedSupplies(player).isNotEmpty())
        updateNavigation(player)
    }

    private fun resumeNative(player: Player, record: ApocalypsePlayerRecord) {
        constrainExpeditionFlight(player)
        if (player.world == world) {
            busy -= player.uniqueId; active += player.uniqueId; announceArrival(player); return
        }
        busy += player.uniqueId
        playerOperations += player.uniqueId
        requireNotNull(world).getChunkAtAsync(layout.spawn.x shr 4, layout.spawn.z shr 4, true).thenCompose { work.onMain {
            check(opened && plugin.server.getPlayer(player.uniqueId) === player && player.isOnline && !player.isDead &&
                players[player.uniqueId] == record && ownsNativeInventory(player, record))
            moving += player.uniqueId
            try {
                check(player.teleport(location(layout.spawn)))
                player.fallDistance = 0f
                player.saveData()
                active += player.uniqueId; busy -= player.uniqueId; announceArrival(player)
            } finally { moving -= player.uniqueId }
        } }.whenComplete { _, failure -> work.onMain {
            if (!currentPlayer(player)) return@onMain
            playerOperations -= player.uniqueId
            if (failure != null) { log("native-resume", failure); message(player, "restore-pending") }
        } }
    }

    private fun constrainExpeditionFlight(player: Player) {
        if (!player.hasPermission("arcevents.admin") && player.gameMode == GameMode.SURVIVAL) {
            player.isFlying = false
            player.allowFlight = false
        }
    }

    private fun rejectArrival(player: Player, origin: String) {
        if (!host || origin == config().serverId || players[player.uniqueId]?.phase?.let { it != ApocalypsePlayerPhase.PARKED } == true) return
        val record = (players[player.uniqueId] ?: ApocalypsePlayerRecord(player.uniqueId.toString(), origin,
            ApocalypsePlayerPhase.PARKED, null, null)).copy(originServer = origin, returnPending = config().network.returnToOrigin, returnTicket = null)
        savePlayer(record).thenCompose { work.onMain { attemptReturn(player, record) } }.exceptionally {
            log("arrival-return", it); null
        }
    }

    private fun attemptReturn(player: Player, record: ApocalypsePlayerRecord) = reconcileReturn(player.uniqueId, record, player)

    private fun reconcileReturn(id: UUID, record: ApocalypsePlayerRecord, player: Player?) {
        fun idle(): Boolean = (if (player == null) plugin.server.getPlayer(id) == null else currentPlayer(player)) &&
            id !in joining && id !in busy && id !in active && !occupied(id)
        if (!record.returnPending || players[id] != record || !idle() || player == null && record.returnTicket == null) return
        val attempt = Any()
        if (returnOperations.putIfAbsent(id, attempt) != null) return
        val armed = if (record.returnTicket != null || !config().network.returnToOrigin) CompletableFuture.completedFuture(record)
        else network.prepareReturn(id, record.originServer).thenCompose { nonce -> work.onMain {
            check(returnOperations[id] === attempt && players[id] == record && idle())
            val next = record.copy(returnTicket = nonce)
            savePlayer(next).thenApply { next }
        }.thenCompose { it } }
        armed.thenCompose { next ->
            val receipt = next.returnTicket
            val pending = if (receipt == null || !config().network.returnToOrigin) CompletableFuture.completedFuture(false)
                else network.returnPending(id, next.originServer, receipt)
            pending.thenCompose { awaiting -> work.onMain {
                if (returnOperations[id] !== attempt || players[id] != next || !idle()) return@onMain CompletableFuture.completedFuture(Unit)
                if (awaiting) {
                    if (player != null) returnToOrigin(player, next.originServer)
                    CompletableFuture.completedFuture(Unit)
                } else savePlayer(next.copy(returnPending = false, returnTicket = null)).thenCompose {
                    if (receipt == null) CompletableFuture.completedFuture(Unit)
                    else network.clearAcknowledgedReturn(id, next.originServer, receipt).thenApply { Unit }
                }
            }.thenCompose { it } }
        }.whenComplete { _, failure -> work.onMain {
            if (returnOperations.remove(id, attempt) && failure != null) log("return-route player=$id", failure)
        } }
    }

    private fun acknowledgeReturn(player: Player) {
        val id = player.uniqueId
        val attempt = Any()
        if (returnOperations.putIfAbsent(id, attempt) != null) return
        network.acknowledgeReturn(id) { currentPlayer(player) }.whenComplete { _, failure -> work.onMain {
            if (!returnOperations.remove(id, attempt) || !currentPlayer(player)) return@onMain
            if (failure == null) unacknowledgedJoins -= id
            else if (unacknowledgedJoins.add(id)) log("return-arrival player=$id", failure)
        } }
    }

    private fun savePlayer(record: ApocalypsePlayerRecord): CompletableFuture<Unit> {
        val id = UUID.fromString(record.playerId)
        val saved = (playerSaves[id] ?: CompletableFuture.completedFuture(Unit)).handle { _, _ -> Unit }
            .thenCompose { work.compute { requireNotNull(storage).save(record); Unit } }
            .thenCompose { work.onMain {
                players[id] = record
                if (record.returnTicket == null) returnReceipts.remove(id) else returnReceipts.add(id)
                Unit
            } }
        playerSaves[id] = saved
        return saved
    }

    private fun checkpoint(after: () -> Unit = {}) {
        if (!opened) return
        if (checkpointPending) { checkpointQueued = true; return }
        checkpointPending = true
        val snapshot = state.copy(infections = state.infections.toMap(), switches = state.switches.toSet(),
            spawners = state.spawners.toMap(), hordes = state.hordes.toMap(), looted = state.looted.toSet())
        saving = checkpointWrites.commit { requireNotNull(journal).commit("world", snapshot); Unit }
        saving.whenComplete { _, failure -> work.onMain {
            checkpointPending = false
            if (opened && failure == null) {
                committedSwitches = snapshot.switches
                committedSpawns = desiredSpawns(snapshot).keys
                try { after() }
                finally { if (checkpointQueued) { checkpointQueued = false; checkpoint() } }
            }
        } }
    }

    private fun tick() {
        if (openingWorld && sourceLayout?.isDone == true) {
            val preparation = worldPreparation ?: run {
                val root = plugin.dataFolder.toPath()
                val folder = plugin.server.worldContainer.toPath().resolve(options.world)
                work.compute { prepareWorld(root, folder) }.also { worldPreparation = it }
            }
            if (!preparation.isDone) return
            openingWorld = false
            worldPreparation = null
            runCatching { openWorld(preparation.join()) }.onFailure { log("world-start world=${options.world}", it) }
            waitingForWorld.toList().forEach { id ->
                waitingForWorld.remove(id)
                val player = plugin.server.getPlayer(id) ?: return@forEach
                val record = players[id] ?: return@forEach
                if (ready && joinRecovery(player, record) == ApocalypseJoinRecovery.RESUME) resumeNative(player, record)
                else retryRecovery(player)
            }
        }
        if (!ready || !host) return
        step++
        repeat(minOf(2, pendingChunks.size)) { prepareChunk(pendingChunks.removeFirst()) }
        repeat(8) {
            val job = pendingSourceTiles.firstOrNull() ?: return@repeat
            if (!job.chunk.isLoaded) { pendingSourceTiles.removeFirst(); return@repeat }
            if (job.entries.hasNext()) runCatching { applyApocalypseSourceTile(job.chunk.world, job.entries.next()) }.onFailure {
                sourceTileFailures++; log("source-tile", it)
            }
            else {
                job.chunk.persistentDataContainer.set(sourceTilesDone, PersistentDataType.BYTE, 1)
                pendingSourceTiles.removeFirst()
            }
        }
        atmosphere.tickRepairs()
        sourceShapes.tick()
        if (step % 25L == 0L) tickStructures(active.mapNotNull(plugin.server::getPlayer).filter { it.world == world && !it.isDead })
        if (step % 20L != 0L) return
        val viewers = active.mapNotNull(plugin.server::getPlayer).filter { it.world == world && !it.isDead }
        val survivors = viewers.filter { it.uniqueId !in busy }
        workbenchDisplays?.refresh(viewers)
        actors.entries.removeIf { (_, id) -> plugin.server.getEntity(id)?.isValid != true }
        if (plugin.server.onlinePlayers.isEmpty()) return
        state = state.copy(survival = state.survival.copy(worldTick = state.survival.worldTick + 20))
        world!!.time = state.survival.worldTick % 24000
        if (survivors.isEmpty()) return
        var hordeChanged = false
        survivors.forEach { player ->
            updateNavigation(player)
            atmosphere.show(player, layout, step, unopenedSupplies(player))
            val key = player.uniqueId.toString()
            val nearby = survivors.count { other -> other != player && state.hordes[other.uniqueId.toString()]?.horde != null &&
                other.location.distanceSquared(player.location) <= 625 }
            val transition = rules.advance(state.hordes[key] ?: ApocalypseSurvivalState(), state.survival.worldTick,
                actors.keys.count { it.startsWith("$key/") }, random, nearbyHordePlayers = nearby)
            state = state.copy(hordes = state.hordes + (key to transition.state))
            if (transition.hordeStarted) message(player, "horde")
            hordeChanged = hordeChanged || transition.spawns.isNotEmpty() || transition.hordeStarted || transition.hordeFinished
        }
        if (hordeChanged) checkpoint()
        if (!checkpointPending) desiredSpawns(state).asSequence().filter { (token, _) -> token in committedSpawns && token !in actors }
            .mapNotNull { (token, spawn) ->
                if (token.contains('/')) survivors.firstOrNull { token.startsWith("${it.uniqueId}/") }
                    ?.let { Triple(spawn.copy(id = token), it, null) }
                else {
                    val marker = chunkMarkers.values.asSequence().flatten()
                        .firstOrNull { token.startsWith("${markerId(it)}:bunker:") } ?: return@mapNotNull null
                    survivors.firstOrNull { abs(it.location.x - marker.x) <= 35 && abs(it.location.y - marker.y) <= 35 && abs(it.location.z - marker.z) <= 35 }
                        ?.let { Triple(spawn.copy(id = token), it, marker) }
                }
            }.take(4).forEach { (spawn, player, marker) -> spawnEnemy(spawn, player, marker) }
        spawnNatural(survivors)
        survivors.forEach { player ->
            val key = player.uniqueId.toString()
            val infection = rules.advanceInfection(state.infections[key] ?: ApocalypseInfectionState(), state.survival.worldTick)
            state = state.copy(infections = state.infections + (key to infection))
            infectionEffects(player, infection)
            val title = locale.render("apocalypse.hud", player, mapOf("day" to locale.text(rules.dayAt(state.survival.worldTick)),
                "infection" to locale.render("apocalypse.infection.${infection.stage.name.lowercase()}", player),
                "enemies" to locale.text(actors.size)))
            val bar = bars.getOrPut(player.uniqueId) { BossBar.bossBar(title, 1f, BossBar.Color.RED, BossBar.Overlay.PROGRESS).also(player::showBossBar) }
            bar.name(title)
            guideSnapshots[player.uniqueId]?.let { snapshot ->
                guide.trackingHud(player, snapshot.copy(day = rules.dayAt(state.survival.worldTick), infection = infection.stage))?.let(bar::name)
            }
        }
        tickActors(survivors)
        if (step % 600L == 0L) checkpoint()
    }

    private fun spawnNatural(survivors: List<Player>) {
        val eligible = rules.naturalSpawnRules(state.survival.worldTick)
        survivors.forEach { player -> eligible.forEach rulesLoop@{ rule ->
            if (actors.size >= options.maximumEnemies || random.nextDouble() >= rule.perSecond) return@rulesLoop
            val existing = actors.values.mapNotNull(plugin.server::getEntity).count {
                it.persistentDataContainer.get(actorSource, PersistentDataType.STRING) in rule.entityIds
            }
            repeat(minOf(random.nextInt(rule.minimumAmount, rule.maximumAmount + 1), (rule.maximumThisType - existing).coerceAtLeast(0))) {
                val source = rule.entityIds.random(random)
                val spawn = ApocalypseZombieSpawn("ambient:${UUID.randomUUID()}", ApocalypseSurvivalReference.variant(source),
                    boss = source == "mutantmonsters:mutant_zombie", sourceEntityId = source)
                spawnEnemy(spawn, player, null, rule)
            }
        } }
    }

    private fun desiredSpawns(snapshot: ApocalypseCheckpoint): Map<String, ApocalypseZombieSpawn> = buildMap {
        snapshot.hordes.forEach { (player, survival) -> survival.horde?.active.orEmpty().forEach { put("$player/${it.id}", it) } }
        snapshot.spawners.values.forEach { spawner -> spawner.active.forEach { put(it.id, it) } }
    }

    private fun spawnEnemy(requested: ApocalypseZombieSpawn, target: Player, marker: ApocalypseMarker?, natural: ApocalypseNaturalSpawnRule? = null): Boolean {
        val profile = marker?.kind?.name?.removeSuffix("_SPAWNER")?.lowercase()?.let { id ->
            ApocalypseSurvivalReference.structureZombies.values.firstOrNull { it.id == id }
        }
        val spawn = profile?.spawn(requested.id) ?: requested
        if (actors.size >= options.maximumEnemies || spawn.id in actors) return false
        val at = safeSpawn(target, marker, natural) ?: return false
        val stats = rules.stats(spawn, rules.dayAt(state.survival.worldTick))
        val mob = world!!.spawn(at, Zombie::class.java) {
            it.persistentDataContainer.set(actorId, PersistentDataType.STRING, spawn.id)
            it.persistentDataContainer.set(actorVariant, PersistentDataType.STRING, spawn.variant.name)
            it.persistentDataContainer.set(actorSource, PersistentDataType.STRING, spawn.sourceEntityId)
            it.persistentDataContainer.set(actorProfile, PersistentDataType.STRING, spawn.profile)
            it.setAdult(); it.setShouldBurnInDay(false); it.setCanBreakDoors(true)
            it.setRemoveWhenFarAway(false); it.canPickupItems = false
            it.getAttribute(Attribute.MAX_HEALTH)?.baseValue = stats.health
            it.health = stats.initialHealth
            it.getAttribute(Attribute.ATTACK_DAMAGE)?.baseValue = stats.damage
            it.getAttribute(Attribute.MOVEMENT_SPEED)?.baseValue = stats.speed
            it.getAttribute(Attribute.FOLLOW_RANGE)?.baseValue = stats.followRange
            it.getAttribute(Attribute.ARMOR)?.baseValue = stats.armor
            it.getAttribute(Attribute.KNOCKBACK_RESISTANCE)?.baseValue = stats.knockbackResistance
            if (spawn.variant.name == "CRAWLER") { it.setBaby(); it.getAttribute(Attribute.SCALE)?.baseValue = .7 }
            if (spawn.variant.name in setOf("BRUTE", "MUTANT") || spawn.boss) it.getAttribute(Attribute.SCALE)?.baseValue = if (spawn.boss) 2.0 else 1.4
            if (profile != null) equipApocalypseStructureZombie(it, profile)
            it.target = target
        }
        installGoal(mob, spawn, stats)
        actors[spawn.id] = mob.uniqueId
        return true
    }

    private fun safeSpawn(player: Player, marker: ApocalypseMarker?, natural: ApocalypseNaturalSpawnRule? = null): Location? {
        val target = world ?: return null
        repeat(natural?.attempts ?: 32) {
            val angle = random.nextDouble(0.0, PI * 2)
            val radius = if (marker != null) random.nextInt(2, 7) else if (natural != null)
                random.nextInt(natural.minimumDistance, natural.maximumDistance + 1) else 75
            val x = (marker?.x ?: player.location.blockX) + (cos(angle) * radius).roundToInt()
            val z = (marker?.z ?: player.location.blockZ) + (sin(angle) * radius).roundToInt()
            if (!target.isChunkLoaded(x shr 4, z shr 4)) return@repeat
            val y = if (marker != null) marker.y else if (natural?.caveOnly == true)
                player.location.blockY + random.nextInt(-natural.maximumVerticalDistance, natural.maximumVerticalDistance + 1)
                else target.getHighestBlockYAt(x, z) + 1
            for (dy in listOf(0, 1, -1, 2, -2)) {
                if (y + dy !in target.minHeight + 1 until target.maxHeight - 1) continue
                val feet = target.getBlockAt(x, y + dy, z)
                if (natural != null && (abs(y + dy - player.location.blockY) > natural.maximumVerticalDistance ||
                    !natural.matches(state.survival.worldTick % 24000, feet.lightFromBlocks.toInt(),
                        target.getHighestBlockYAt(x, z) < y + dy, target.getHighestBlockYAt(x, z) >= y + dy))) continue
                if (target.getBlockAt(x, y + dy - 1, z).type.isSolid && feet.isPassable && target.getBlockAt(x, y + dy + 1, z).isPassable)
                    return Location(target, x + .5, (y + dy).toDouble(), z + .5)
            }
        }
        return null
    }

    private fun tickActors(survivors: List<Player>) {
        actors.values.toList().forEach { id ->
            val mob = plugin.server.getEntity(id) as? Zombie ?: return@forEach
            val target = survivors.minByOrNull { it.location.distanceSquared(mob.location) } ?: return@forEach
            if (target.location.distanceSquared(mob.location) > 128.0.pow(2)) {
                val token = mob.persistentDataContainer.get(actorId, PersistentDataType.STRING)
                if (token?.startsWith("ambient:") == true) { actors.remove(token); mob.remove() }
                return@forEach
            }
            mob.target = target
        }
    }

    private fun infectionEffects(player: Player, state: ApocalypseInfectionState) {
        if (state.expired) { player.health = 0.0; return }
        player.exhaustion += (rules.infectionExhaustionPerTick(state) * 20).toFloat()
        player.getAttribute(Attribute.MOVEMENT_SPEED)?.let { speed ->
            speed.removeModifier(infectionSpeed)
            val amount = rules.infectionMovementMultiplier(state) - 1.0
            if (amount != 0.0) speed.addTransientModifier(AttributeModifier(infectionSpeed, amount, AttributeModifier.Operation.ADD_SCALAR))
        }
    }

    private fun prepareChunk(chunk: Chunk) {
        if (!chunk.isLoaded || chunk.world != world) return
        val layout = layout
        if (layout is ApocalypseOriginalLayout) {
            atmosphere.prepare(chunk, layout)
            sourceShapes.prepare(chunk, layout)
            sourceMining.prepare(chunk, layout)
        }
        val markers = layout.markersInChunk(chunk.x, chunk.z)
        chunkMarkers[chunk.chunkKey] = markers
        if (layout is ApocalypseOriginalLayout && !chunk.persistentDataContainer.has(sourceTilesDone)) {
            val entries = layout.blockEntitiesInChunk(chunk.x, chunk.z)
            if (entries.isEmpty()) chunk.persistentDataContainer.set(sourceTilesDone, PersistentDataType.BYTE, 1)
            else if (pendingSourceTiles.none { it.chunk.chunkKey == chunk.chunkKey }) pendingSourceTiles.addLast(SourceTileJob(chunk, entries.iterator()))
        }
        markers.forEach { marker ->
            val block = chunk.world.getBlockAt(marker.x, marker.y, marker.z)
            if (marker.kind.name in setOf("FORCEFIELD", "SECURITY") && fieldDisabled(marker) ||
                marker.kind == ApocalypseMarkerKind.POWER && markerId(marker) in committedSwitches)
                block.setType(Material.AIR, false)
            if (marker.kind.name.endsWith("_SPAWNER") && state.spawners[markerId(marker)]?.removed == true)
                block.setType(Material.AIR, false)
        }
        chunk.entities.filterIsInstance<Zombie>().forEach { zombie ->
            val token = zombie.persistentDataContainer.get(actorId, PersistentDataType.STRING) ?: return@forEach
            if (actors[token] == zombie.uniqueId) return@forEach
            if (actors.size >= options.maximumEnemies || token in actors ||
                !token.startsWith("ambient:") && token !in desiredSpawns(state)) { zombie.remove(); return@forEach }
            val variant = zombie.persistentDataContainer.get(actorVariant, PersistentDataType.STRING)
                ?.let { runCatching { ApocalypseZombieVariant.valueOf(it) }.getOrNull() } ?: return@forEach
            val source = zombie.persistentDataContainer.get(actorSource, PersistentDataType.STRING) ?: return@forEach
            val spawn = ApocalypseZombieSpawn(token, variant, sourceEntityId = source,
                boss = source == "mutantmonsters:mutant_zombie",
                profile = zombie.persistentDataContainer.get(actorProfile, PersistentDataType.STRING) ?: "default")
            installGoal(zombie, spawn, rules.stats(spawn, rules.dayAt(state.survival.worldTick)))
            actors[token] = zombie.uniqueId
        }
    }

    private fun installGoal(mob: Zombie, spawn: ApocalypseZombieSpawn, stats: ApocalypseZombieStats) =
        installApocalypseMobGoal(plugin, mob, spawn, stats) { block ->
            block.world == world && block.type != Material.BEDROCK &&
                markerAt(block)?.kind?.name !in setOf("POWER", "SECURITY", "FORCEFIELD")
        }

    private fun markerAt(block: org.bukkit.block.Block): ApocalypseMarker? {
        if (block.world != world) return null
        // Chunk packets can arrive before the bounded decoration queue reaches this chunk.
        // Generation already caches these pure markers; interactions must not bypass finite loot meanwhile.
        val chunk = block.chunk
        val layout = layout
        val markers = chunkMarkers.getOrPut(chunk.chunkKey) { layout.markersInChunk(chunk.x, chunk.z) }
        return markers.firstOrNull { it.x == block.x && it.y == block.y && it.z == block.z &&
            (layout !is ApocalypseOriginalLayout || atmosphere.allowsMarker(chunk, layout, it)) }
    }
    private fun markerId(marker: ApocalypseMarker) = "${marker.siteId}:${marker.x}:${marker.y}:${marker.z}"
    private fun siteMarkers(marker: ApocalypseMarker): List<ApocalypseMarker> = layout.sitesNear(marker.x, marker.z)
        .firstOrNull { it.id == marker.siteId }?.let(layout::markers).orEmpty()
    private fun fieldDisabled(marker: ApocalypseMarker) = siteMarkers(marker).any {
        it.kind == ApocalypseMarkerKind.POWER && markerId(it) in committedSwitches &&
            (it.x - marker.x).toDouble().pow(2) + (it.y - marker.y).toDouble().pow(2) + (it.z - marker.z).toDouble().pow(2) <= 31.0.pow(2)
    }

    private fun tickStructures(survivors: List<Player>) {
        chunkMarkers.values.asSequence().flatten().filter { it.kind.name.endsWith("_SPAWNER") }.forEach { marker ->
            val nearby = survivors.filter { abs(it.location.x - marker.x) <= 35 && abs(it.location.y - marker.y) <= 35 && abs(it.location.z - marker.z) <= 35 }
            val target = nearby.firstOrNull() ?: return@forEach
            if (!world!!.isChunkLoaded(marker.x shr 4, marker.z shr 4)) return@forEach
            if (world!!.getBlockAt(marker.x, marker.y, marker.z).type != Material.COPPER_GRATE) return@forEach
            val id = markerId(marker)
            val spawner = state.spawners[id] ?: ApocalypseBunkerSpawnerState(id)
            if (spawner.removed || checkpointPending || actors.size > options.maximumEnemies - 2) return@forEach
            val transition = bunkerRules.randomTick(spawner, nearby.size)
            state = state.copy(spawners = state.spawners + (id to transition.state))
            if (transition.spawns.isNotEmpty()) checkpoint {
                transition.spawns.forEach { spawnEnemy(it, target, marker) }
                if (transition.state.removed) world!!.getBlockAt(marker.x, marker.y, marker.z).setType(Material.AIR, false)
            }
        }
    }

    private fun lootClaimId(marker: ApocalypseMarker): String = markerId(marker) +
        if (layout is ApocalypseOriginalLayout) ":source-loot-v1" else ""

    private fun stock(marker: ApocalypseMarker, player: Player): Boolean {
        val block = world!!.getBlockAt(marker.x, marker.y, marker.z)
        val layout = layout
        if (layout is ApocalypseOriginalLayout && !atmosphere.allowsMarker(block.chunk, layout, marker)) return false
        val container = block.state as? Container ?: return true
        val sourceLayout = layout as? ApocalypseOriginalLayout
        val stockKey = if (sourceLayout == null) stocked else originalStocked
        val double = container.inventory as? DoubleChestInventory
        val halves = double?.let { listOfNotNull(it.leftSide.holder as? Chest, it.rightSide.holder as? Chest) }.orEmpty()
        val markers = (listOf(marker) + halves.mapNotNull { markerAt(it.block) }).distinctBy(::markerId)
        if (markers.any { markerId(it) in stocking }) { message(player, "pending"); return false }
        val needed = markers.filter {
            val placed = world!!.getBlockAt(it.x, it.y, it.z).state as? Container
            placed != null && !placed.persistentDataContainer.has(stockKey) && lootClaimId(it) !in state.looted
        }
        if (needed.isEmpty()) return true
        if (sourceLayout != null && needed.any { it.sourceBlock in setOf(
                "jeffs_cursed_walking_structures:gun_part_chest", "jeffs_cursed_walking_structures:create_part_chest") } &&
            !ApocalypseLootItems.isCrowbar(player.inventory.itemInMainHand, plugin)) {
            message(player, "guide.crowbar-needed"); return false
        }
        if (checkpointPending) { message(player, "pending"); return false }
        val locks = markers.map(::markerId).toSet()
        stocking += locks
        // All source planning/rolls finish before the finite entitlement is consumed.
        work.compute {
            needed.map { source ->
                val random = java.util.Random(options.seed xor markerId(source).hashCode().toLong())
                val drops = sourceLayout?.loot(source, random)
                val mapTarget = if (drops.orEmpty().any { it is ApocalypseSourceLootDrop.Item && it.item.explorationMap != null })
                    layout.sitesNear(source.x, source.z).firstOrNull { it.source?.endsWith(":teleporter") == true }?.anchor else null
                Triple(source, drops, mapTarget)
            }
        }.whenComplete { plans, failure -> work.onMain {
            if (failure != null) {
                stocking -= locks; log("source-loot-plan", failure)
                if (player.isOnline) message(player, "unavailable")
                return@onMain
            }
            if (!opened || checkpointPending) { stocking -= locks; return@onMain }
            try {
                val prepared = plans.map { (source, drops, target) ->
                    val current = world!!.getBlockAt(source.x, source.y, source.z).state as? Container
                        ?: error("Original loot container disappeared before admission")
                    val random = java.util.Random(options.seed xor markerId(source).hashCode().toLong())
                    val contents = if (drops == null) legacySupplies(source, player, random) else drops.flatMap { drop ->
                        when (drop) {
                            is ApocalypseSourceLootDrop.Item -> lootItems.create(drop.item, player, random, target?.let(::location))
                            is ApocalypseSourceLootDrop.VanillaTable -> {
                                val key = requireNotNull(NamespacedKey.fromString(drop.tableId))
                                require(key.namespace == "minecraft")
                                requireNotNull(Bukkit.getLootTable(key)) { "Missing native source loot table $key" }
                                    .populateLoot(random, LootContext.Builder(current.location).lootedEntity(player).killer(player).build()).toList()
                            }
                        }
                    }
                    val capacity = Bukkit.createInventory(null, current.snapshotInventory.size)
                    capacity.contents = current.snapshotInventory.contents.map { it?.clone() }.toTypedArray()
                    val leftovers = capacity.addItem(*contents.map(ItemStack::clone).toTypedArray())
                    if (leftovers.isNotEmpty()) {
                        stocking -= locks
                        if (player.isOnline) {
                            message(player, "guide.container-full")
                            player.openInventory(container.inventory)
                        }
                        return@onMain
                    }
                    source to contents
                }
                state = state.copy(looted = state.looted + needed.map(::lootClaimId))
                checkpoint {
                    var success = true
                    try {
                        prepared.forEach { (source, contents) ->
                            val current = world!!.getBlockAt(source.x, source.y, source.z).state as? Container ?: return@forEach
                            if (current.persistentDataContainer.has(stockKey)) return@forEach
                            // Preserve existing player items and older provisional supplies during the one-time source migration.
                            contents.forEachIndexed { index, item ->
                                val slot = (index * 7 + 3) % current.snapshotInventory.size
                                if (current.snapshotInventory.getItem(slot) == null) current.snapshotInventory.setItem(slot, item)
                                else check(current.snapshotInventory.addItem(item).isEmpty()) { "Reserved source-loot capacity changed" }
                            }
                            current.persistentDataContainer.set(stockKey, PersistentDataType.BYTE, 1.toByte())
                            check(current.update(true, false))
                        }
                    } catch (problem: Throwable) {
                        success = false
                        log("source-loot-apply", problem)
                        if (player.isOnline) message(player, "unavailable")
                    } finally { stocking -= locks }
                    if (success && player.isOnline && isPlaying(player.uniqueId) && player.world == world && player.location.distanceSquared(block.location) <= 36)
                        player.openInventory((block.state as Container).inventory)
                }

            } catch (problem: Throwable) {
                stocking -= locks; log("source-loot-materialize", problem)
                if (player.isOnline) message(player, "unavailable")
            }
        } }
        return false
    }

    private fun legacySupplies(source: ApocalypseMarker, player: Player, loot: java.util.Random): List<ItemStack> = buildList {
        if (source.kind.name == "MEDICAL") addAll(listOf(vaccine(), ItemStack.of(Material.BREAD, 4), ItemStack.of(Material.HONEY_BOTTLE)))
        else {
            add(firearms.worldAmmunition(player, options.ammoMinimum + loot.nextInt(options.ammoMaximum - options.ammoMinimum + 1)))
            add(ItemStack.of(Material.COOKED_BEEF, 2 + loot.nextInt(4)))
            add(ItemStack.of(Material.IRON_INGOT, 1 + loot.nextInt(3)))
            if (source.kind.name in setOf("ARMORY", "CORE") || loot.nextInt(4) == 0) {
                val catalog = listOf(FirearmId.FIVE_SEVEN, FirearmId.G36, FirearmId.M1_GARAND, FirearmId.VEPR_12)
                add(firearms.worldFirearmItem(catalog[loot.nextInt(catalog.size)], player))
            }
            if (loot.nextInt(5) == 0) add(vaccine())
        }
    }

    private fun normalizeExpedition(envelope: ru.arc.paper.playerstate.PaperPlayerStateEnvelope?): ru.arc.paper.playerstate.PaperPlayerStateEnvelope? {
        if (envelope == null) return null
        val codec = PaperPlayerStateCodec()
        val snapshot = codec.decode(envelope)
        if (snapshot.location.worldName == options.world) return envelope
        return codec.encode(snapshot.copy(location = PaperLocationSnapshot(requireNotNull(world).uid, options.world,
            layout.spawn.x + .5, layout.spawn.y.toDouble(), layout.spawn.z + .5, 180f, 0f), fallDistance = 0f))
    }

    private fun vaccine() = ItemStack.of(Material.HONEY_BOTTLE).also { item -> item.editMeta {
        it.displayName(locale.render("apocalypse.vaccine"))
        it.persistentDataContainer.set(medicine, PersistentDataType.BYTE, 1.toByte())
    } }

    @EventHandler(priority = EventPriority.LOWEST)
    fun onJoin(event: PlayerJoinEvent) = handleJoin(event.player)

    private fun handleJoin(player: Player) {
        if (host) {
            joining += player.uniqueId
            if (pendingApocalypseInventory(player)) busy += player.uniqueId
            tasks.runLater(2) { if (currentPlayer(player)) reconcileJoin(player) }
        } else if (player.persistentDataContainer.has(nativeSession)) {
            // A relay has no authoritative journal with which to release an expedition inventory.
            busy += player.uniqueId
            message(player, "restore-pending")
        } else {
            tasks.runLater(1) { if (currentPlayer(player)) acknowledgeReturn(player) }
        }
    }
    @EventHandler(priority = EventPriority.LOWEST)
    fun onQuit(event: PlayerQuitEvent) {
        returnOperations.remove(event.player.uniqueId)
        unacknowledgedJoins.remove(event.player.uniqueId)
        playerOperations -= event.player.uniqueId
        recoveryOperations -= event.player.uniqueId
        waitingForWorld -= event.player.uniqueId
        joining -= event.player.uniqueId
        workbenchMenu.forget(event.player)
        busy -= event.player.uniqueId
        active -= event.player.uniqueId
        guideSnapshots.remove(event.player.uniqueId)
        guideRequests.remove(event.player.uniqueId)
        trackedGuides.remove(event.player.uniqueId)
        automaticGuides.remove(event.player.uniqueId)
        bars.remove(event.player.uniqueId)?.let(event.player::hideBossBar)
        // ACTIVE native player data stays in the expedition on disconnect; the origin journal remains intact.
    }
    @EventHandler(priority = EventPriority.MONITOR)
    fun onChunk(event: ChunkLoadEvent) {
        if (event.world != world) return
        val layout = layout
        if (event.isNewChunk && layout is ApocalypseOriginalLayout) atmosphere.generated(event.chunk, layout)
        if (layout is ApocalypseOriginalLayout) sourceMining.prepare(event.chunk, layout)
        pendingChunks.addLast(event.chunk)
    }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onUnload(event: ChunkUnloadEvent) { if (event.world == world) chunkMarkers.remove(event.chunk.chunkKey) }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onTeleport(event: PlayerTeleportEvent) {
        if (event.player.uniqueId in moving) return
        if (isBusy(event.player.uniqueId) && pendingApocalypseInventory(event.player)) {
            event.isCancelled = true
        } else if (isPlaying(event.player.uniqueId) && event.to.world != world) {
            event.isCancelled = true; message(event.player, "leave-first")
        } else if (host && event.to.world.name == options.world && !isPlaying(event.player.uniqueId)) event.isCancelled = true
    }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onCommand(event: PlayerCommandPreprocessEvent) {
        if (!isPlaying(event.player.uniqueId) && !isBusy(event.player.uniqueId)) return
        if (!isBusy(event.player.uniqueId) && event.player.hasPermission("arcevents.admin")) return
        val root = event.message.removePrefix("/").substringBefore(' ').substringAfter(':').lowercase()
        if (root !in setOf("arcevents", "ae", "msg", "tell", "r", "reply", "help", "rules", "report")) {
            event.isCancelled = true; message(event.player, "leave-first")
        }
    }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onInteract(event: PlayerInteractEvent) {
        if (!isPlaying(event.player.uniqueId) || event.hand != EquipmentSlot.HAND) return
        if (isBusy(event.player.uniqueId)) { event.isCancelled = true; return }
        if (!event.action.isRightClick) return
        val block = event.clickedBlock ?: return
        workbenches.stationAt(block)?.let { station ->
            if (station == ApocalypseWorkbenchStation.CRAFTING && event.player.isSneaking &&
                event.player.inventory.itemInMainHand.isEmpty) return
            event.isCancelled = true
            if (workbenches.isUsable(event.player, ApocalypsePoint(block.x, block.y, block.z), station))
                openWorkbench(event.player, block, station)
            return
        }
        val marker = containerMarker(block) ?: return
        when (marker.kind.name) {
            "LOOT", "MEDICAL", "ARMORY", "CORE" -> if (!stock(marker, event.player)) event.isCancelled = true
            "POWER" -> {
                event.isCancelled = true
                if (markerId(marker) in state.switches || checkpointPending) return
                state = state.copy(switches = state.switches + markerId(marker))
                checkpoint {
                    siteMarkers(marker).filter { it.kind.name in setOf("FORCEFIELD", "SECURITY") &&
                        (it.x-marker.x).toDouble().pow(2)+(it.y-marker.y).toDouble().pow(2)+(it.z-marker.z).toDouble().pow(2)<=961 }.forEach {
                        if (world!!.isChunkLoaded(it.x shr 4, it.z shr 4)) world!!.getBlockAt(it.x,it.y,it.z).setType(Material.AIR,false)
                    }
                    block.setType(Material.AIR, false)
                    world!!.playSound(block.location, Sound.BLOCK_BEACON_DEACTIVATE, 2f, .5f)
                    message(event.player, "field-off")
                }
            }
        }
    }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onBreak(event: BlockBreakEvent) {
        if (event.block.world != world) return
        if (!isPlaying(event.player.uniqueId) || isBusy(event.player.uniqueId)) { event.isCancelled = true; return }
        val layout = layout
        if (layout is ApocalypseOriginalLayout) sourceMining.prepare(event.block.chunk, layout, priority = true)
        val marker = containerMarker(event.block) ?: return
        if (marker.kind.name in setOf("POWER", "SECURITY", "FORCEFIELD")) event.isCancelled = true
        else if (marker.kind.name in setOf("LOOT", "MEDICAL", "ARMORY", "CORE") && !stock(marker, event.player)) event.isCancelled = true
        else if (marker.kind.name.endsWith("_SPAWNER")) {
            val id = markerId(marker)
            val previous = state.spawners[id] ?: ApocalypseBunkerSpawnerState(id)
            state = state.copy(spawners = state.spawners + (id to previous.copy(pulses = 6, timer = -12)))
            checkpoint()
        }
    }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onInventory(event: InventoryClickEvent) {
        if (isBusy(event.whoClicked.uniqueId) || lockedSupply(event.view.topInventory)) event.isCancelled = true
    }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onInventoryMove(event: InventoryMoveItemEvent) {
        if (lockedSupply(event.source) || lockedSupply(event.destination)) event.isCancelled = true
    }
    // Paper reports a double chest's midpoint, which can fall in a non-source player-placed half.
    private fun inventoryLocations(inventory: Inventory): List<Location> = if (inventory is DoubleChestInventory)
        listOfNotNull(inventory.leftSide.location, inventory.rightSide.location) else listOfNotNull(inventory.location)
    private fun containerMarker(block: org.bukkit.block.Block): ApocalypseMarker? = markerAt(block)
        ?: (block.state as? Container)?.inventory?.let(::inventoryLocations)?.asSequence()
            ?.filter { it.world == world }?.mapNotNull { markerAt(it.block) }?.firstOrNull()
    private fun lockedSupply(inventory: Inventory): Boolean = inventoryLocations(inventory).any { at ->
        at.world == world && markerAt(at.block)?.let(::markerId) in stocking
    }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onOpen(event: InventoryOpenEvent) {
        if (isBusy(event.player.uniqueId) || isPlaying(event.player.uniqueId) && event.inventory.type == InventoryType.ENDER_CHEST)
            event.isCancelled = true
        if (lockedSupply(event.inventory)) event.isCancelled = true
    }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onSupplyOpened(event: InventoryOpenEvent) {
        val player = event.player as? Player ?: return
        if (!isPlaying(player.uniqueId) || isBusy(player.uniqueId) || player.world != world) return
        val original = layout as? ApocalypseOriginalLayout ?: return
        val opened = inventoryLocations(event.inventory)
        val visited = original.starterSupplyMarkers.filter { marker -> opened.any {
            it.world == world && it.blockX == marker.x && it.blockY == marker.y && it.blockZ == marker.z
        } }
        if (visited.isEmpty()) return
        val old = original.starterSupplyMarkers.toSet() - unopenedSupplies(player).toSet()
        val seen = (old + visited).map { "${it.x}:${it.y}:${it.z}" }.sorted().joinToString(",")
        player.persistentDataContainer.set(supplySeen, PersistentDataType.STRING, "${world!!.uid}|$seen")
        guideSnapshots.remove(player.uniqueId)
        player.saveData()
    }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onMove(event: PlayerMoveEvent) {
        if (!isPlaying(event.player.uniqueId) || isBusy(event.player.uniqueId) || step % 10L != 0L) return
        val point = event.to
        val fields = (-1..1).flatMap { dx -> (-1..1).flatMap { dz ->
            val key = (point.chunk.x + dx).toLong() and 0xffffffffL or ((point.chunk.z + dz).toLong() shl 32)
            chunkMarkers[key].orEmpty()
        } }
        if (fields.any { it.kind.name == "FORCEFIELD" && !fieldDisabled(it) &&
            event.player.boundingBox.clone().expand(.1).overlaps(org.bukkit.util.BoundingBox(
                it.x.toDouble(), it.y.toDouble(), it.z.toDouble(), it.x + 1.0, it.y + 1.0, it.z + 1.0)) }) {
            event.player.damage(2.0)
            event.player.playSound(point, Sound.ENTITY_LIGHTNING_BOLT_IMPACT, 1f, 1.5f)
        }
    }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onDrag(event: InventoryDragEvent) {
        if (isBusy(event.whoClicked.uniqueId) || lockedSupply(event.view.topInventory)) event.isCancelled = true
    }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onDrop(event: PlayerDropItemEvent) { if (isBusy(event.player.uniqueId)) event.isCancelled = true }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onPickup(event: EntityPickupItemEvent) { if (isBusy(event.entity.uniqueId)) event.isCancelled = true }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onDamage(event: EntityDamageByEntityEvent) {
        val player = event.entity as? Player ?: return
        if (isBusy(player.uniqueId)) { event.isCancelled = true; return }
        if (!isPlaying(player.uniqueId) || event.damager !is Zombie) return
        val key = player.uniqueId.toString()
        state = state.copy(infections = state.infections + (key to rules.expose(state.infections[key] ?: ApocalypseInfectionState(), state.survival.worldTick, random)))
    }
    /** Defeat owned actors before native death callbacks can mint network job/quest rewards. */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onActorDamage(event: EntityDamageEvent) {
        val actor = event.entity as? LivingEntity ?: return
        val token = actor.persistentDataContainer.get(actorId, PersistentDataType.STRING) ?: return
        if (actor.world != world || event.finalDamage < actor.health) return
        event.isCancelled = true
        actors.remove(token)
        defeated(token)
        actor.remove()
    }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onConsume(event: PlayerItemConsumeEvent) {
        if (!isPlaying(event.player.uniqueId)) return
        val vaccine = event.item.itemMeta.persistentDataContainer.has(medicine)
        if (!vaccine && event.item.type !in setOf(Material.GOLDEN_APPLE, Material.ENCHANTED_GOLDEN_APPLE)) return
        if (isBusy(event.player.uniqueId)) { event.isCancelled = true; return }
        val id = event.player.uniqueId.toString()
        val old = state.infections[id] ?: ApocalypseInfectionState()
        state = state.copy(infections = state.infections + (id to if (vaccine) rules.immunize(old, state.survival.worldTick) else rules.cure(old, state.survival.worldTick)))
        message(event.player, "cured"); checkpoint()
    }
    @EventHandler(priority = EventPriority.MONITOR)
    fun onDeath(event: EntityDeathEvent) {
        val token = event.entity.persistentDataContainer.get(actorId, PersistentDataType.STRING) ?: return
        actors.remove(token)
        defeated(token)
        // Match actors never use this owner; native expedition drops remain in their isolated world.
    }
    @EventHandler(priority = EventPriority.HIGHEST)
    fun onPlayerDeath(event: PlayerDeathEvent) {
        if (!isPlaying(event.entity.uniqueId)) return
        state = state.copy(infections = state.infections - event.entity.uniqueId.toString())
    }
    @EventHandler(priority = EventPriority.HIGHEST)
    fun onRespawn(event: PlayerRespawnEvent) {
        if (isPlaying(event.player.uniqueId)) event.respawnLocation = location(layout.spawn)
    }

    private fun defeated(token: String) {
        val owner = token.substringBefore('/')
        state = state.copy(hordes = state.hordes.mapValues { (id, horde) ->
            if (id == owner) rules.enemyDefeated(horde, token.substringAfter('/')) else horde
        }, spawners = state.spawners.mapValues { (_, spawner) -> bunkerRules.enemyDefeated(spawner, token) })
    }

    private fun location(point: ApocalypsePoint) = Location(world, point.x + .5, point.y.toDouble(), point.z + .5, 180f, 0f)
    private fun message(player: Player, key: String, vararg values: Pair<String, Any>) {
        if (player.isOnline) player.sendMessage(locale.render("apocalypse.$key", player, values.associate { it.first to locale.text(it.second) }))
    }
    private fun log(operation: String, failure: Throwable) = plugin.logger.log(Level.SEVERE, "ArcEvents apocalypse $operation failed; preserving recovery state", failure)

    override fun close() {
        opened = false
        if (host) network.advertise(false)
        tasks.close()
        workbenchDisplays?.close()
        sourceMining.close()
        workbenchMenu.close()
        workbenches.close()
        atmosphere.close()
        sourceShapes.close()
        guide.close()
        guideRequests.clear(); guideSnapshots.clear(); trackedGuides.clear()
        bars.forEach { (id, bar) -> plugin.server.getPlayer(id)?.hideBossBar(bar) }; bars.clear()
        actors.values.mapNotNull(plugin.server::getEntity).forEach(Entity::remove); actors.clear()
        val snapshot = state
        val pendingWrite = checkpointWrites.stop()
        val openedJournal = journal
        // Admit this IO action before the runtime seals its executor. Wait for the older write only on that worker.
        closeCompletion = if (openedJournal != null) work.compute {
            pendingWrite.handle { _, _ -> Unit }.join()
            openedJournal.commit("world", snapshot); Unit
        } else CompletableFuture.completedFuture(Unit)
        HandlerList.unregisterAll(this)
        // Loaded native generator worlds require the existing coordinated restart barrier for code replacement.
    }
}
