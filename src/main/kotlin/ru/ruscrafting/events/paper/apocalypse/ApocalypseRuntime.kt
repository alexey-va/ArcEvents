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
import org.bukkit.persistence.PersistentDataType
import org.bukkit.plugin.java.JavaPlugin
import ru.arc.core.LifecycleTaskScope
import ru.arc.paper.network.BackendTransfer
import ru.arc.paper.playerstate.PaperPlayerStateService
import ru.arc.paper.playerstate.PaperPlayerStateCodec
import ru.arc.paper.playerstate.PaperLocationSnapshot
import ru.arc.persistence.DurableRecordJournal
import ru.arc.redis.RedisOperations
import ru.ruscrafting.events.config.*
import ru.ruscrafting.events.domain.FirearmId
import ru.ruscrafting.events.domain.apocalypse.*
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
    private val queued: (UUID) -> CompletableFuture<Boolean>,
    private val returnToOrigin: (Player, String) -> Boolean,
    private val sourceLayout: ApocalypseLayout? = null,
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
    private val playerSaves = mutableMapOf<UUID, CompletableFuture<Unit>>()
    private val bars = mutableMapOf<UUID, BossBar>()
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
    private val actorId = NamespacedKey(plugin, "apocalypse_actor")
    private val actorVariant = NamespacedKey(plugin, "apocalypse_variant")
    private val actorSource = NamespacedKey(plugin, "apocalypse_source")
    private val actorProfile = NamespacedKey(plugin, "apocalypse_profile")
    private val infectionSpeed = NamespacedKey(plugin, "apocalypse_infection_speed")
    private val medicine = NamespacedKey(plugin, "apocalypse_medicine")
    private val nativeSession = NamespacedKey(plugin, "apocalypse_session")
    private val playerStates = PaperPlayerStateService()
    private val layout: ApocalypseLayout = sourceLayout ?: ApocalypseWorldLayout(options.seed)
    private val template = if (sourceLayout != null) ApocalypseOriginalGenerator.TEMPLATE else ApocalypseChunkGenerator.TEMPLATE
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
    private var saving = CompletableFuture.completedFuture(Unit)
    var closeCompletion: CompletableFuture<Unit> = CompletableFuture.completedFuture(Unit)
        private set
    val ready: Boolean get() = opened && (!host || world != null)
    fun isPlaying(id: UUID): Boolean = id in active
    fun isBusy(id: UUID): Boolean = id in busy
    fun allowsRecoveryCommand(player: Player, command: String): Boolean {
        if (!isBusy(player.uniqueId) || player.uniqueId in playerOperations || player.uniqueId in recoveryOperations) return false
        val parts = command.removePrefix("/").trim().lowercase().split(Regex("\\s+"))
        if (parts.firstOrNull() !in setOf("arcevents", "arcevents:arcevents")) return false
        return parts.drop(1) == listOf("leave") || parts.drop(1) in listOf(listOf("apocalypse", "leave"), listOf("apocalypse", "recover"))
    }

    /** Startup/maintenance only: the generator and journal identity must be checked before admitting players. */
    fun start() {
        check(!opened)
        if (host) {
            val gson = Gson()
            storage = ApocalypseStorage(plugin.dataFolder.toPath()).also { players.putAll(it.loadPlayers()) }
            journal = DurableRecordJournal(plugin.dataFolder.toPath(), Path.of("data/apocalypse/world-${options.world}"), 32L * 1024 * 1024,
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
            state = journal!!.loadOrNull("world") ?: state
            committedSwitches = state.switches
            committedSpawns = desiredSpawns(state).keys
            val folder = plugin.server.worldContainer.toPath().resolve(options.world)
            val marker = folder.resolve(".arcevents-template")
            val identity = "$template:${options.seed}"
            if (Files.exists(folder)) {
                require(!Files.isSymbolicLink(folder) && Files.isRegularFile(marker) && !Files.isSymbolicLink(marker)) {
                    "Apocalypse world exists without its generator ownership marker: ${options.world}"
                }
                require(Files.readString(marker).trim() == identity) { "Apocalypse world seed/generator identity changed" }
            } else {
                Files.createDirectories(folder)
                Files.writeString(marker, identity)
            }
            world = plugin.server.getWorld(options.world) ?: WorldCreator(options.world).seed(options.seed)
                .generator(if (sourceLayout != null) ApocalypseOriginalGenerator(options.seed, CompletableFuture.completedFuture(layout))
                    else ApocalypseChunkGenerator(layout as ApocalypseWorldLayout)).generateStructures(false).createWorld()
            val target = requireNotNull(world)
            require((target.generator is ApocalypseChunkGenerator || target.generator is ApocalypseOriginalGenerator) && target.seed == options.seed)
            target.difficulty = Difficulty.HARD
            target.setGameRule(GameRule.DO_MOB_SPAWNING, false)
            target.setGameRule(GameRule.DO_DAYLIGHT_CYCLE, false)
            target.setGameRule(GameRule.DO_INSOMNIA, false)
            // Hordes spawn 75 blocks away; tiny lobby distances must not disable the survival world.
            target.viewDistance = maxOf(target.viewDistance, 8)
            target.simulationDistance = maxOf(target.simulationDistance, 6)
            target.setSpawnLocation(location(layout.spawn))
            target.loadedChunks.forEach(pendingChunks::addLast)
        }
        opened = true
        plugin.server.pluginManager.registerEvents(this, plugin)
        tasks.runTimer(1, 1) { tick() }
        tasks.runTimer(1, 100) { if (host) network.advertise(ready).exceptionally { failure -> log("heartbeat", failure); null } }
        if (host) plugin.server.onlinePlayers.forEach { player ->
            if (players[player.uniqueId]?.phase?.let { it != ApocalypsePlayerPhase.PARKED } == true) busy += player.uniqueId
            reconcileJoin(player)
        }
    }

    /** Returns true when the command belongs to this persistent mode. */
    fun command(player: Player, arguments: List<String>): Boolean {
        val action = arguments.firstOrNull()?.lowercase()
        if (action != "apocalypse" && !isPlaying(player.uniqueId) && !isBusy(player.uniqueId)) return false
        if (isBusy(player.uniqueId)) {
            val recovery = action == "leave" || action == "apocalypse" && arguments.getOrNull(1)?.lowercase() in setOf("leave", "recover")
            if (recovery && player.uniqueId !in playerOperations && player.uniqueId !in recoveryOperations) retryRecovery(player)
            else message(player, "pending")
            return true
        }
        if (action == "leave") { leave(player); return true }
        if (action == "status") { status(player); return true }
        if (action == "apocalypse") {
            when (arguments.getOrNull(1)?.lowercase()) {
                "qa" -> if (player.hasPermission("arcevents.qa")) player.sendMessage(qaStatus()) else message(player, "denied")
                "markers" -> if (host && player.hasPermission("arcevents.qa")) {
                    val kind = arguments.getOrNull(2)?.uppercase()
                    val x = player.location.blockX; val z = player.location.blockZ
                    work.compute {
                        layout.sitesNear(x, z).asSequence().flatMap { layout.markers(it).asSequence() }
                            .filter { kind == null || it.kind.name == kind }.take(12).toList()
                    }.thenCompose { entries -> work.onMain {
                        if (player.isOnline) entries.forEach {
                            player.sendMessage("ARCEVENTS_APOCALYPSE_MARKER kind=${it.kind} x=${it.x} y=${it.y} z=${it.z}")
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
                else -> if (isPlaying(player.uniqueId)) status(player) else enter(player)
            }
            return true
        }
        message(player, "leave-first")
        return true
    }

    fun qaStatus(): String = "ARCEVENTS_APOCALYPSE ready=$ready world=${world?.key ?: "-"} day=${rules.dayAt(state.survival.worldTick)} tick=${state.survival.worldTick} players=${active.size} busy=${busy.size} enemies=${actors.size} hordes=${state.hordes.values.count { it.horde != null }} switches=${state.switches.size} spawners=${state.spawners.size} bunker_enemies=${actors.keys.count { ":bunker:" in it }} generation=$template spawn_x=${layout.spawn.x} spawn_y=${layout.spawn.y} spawn_z=${layout.spawn.z} tile_failures=$sourceTileFailures"

    fun status(player: Player) {
        player.sendMessage(locale.render("apocalypse.status", player, mapOf(
            "day" to locale.text(rules.dayAt(state.survival.worldTick)),
            "phase" to locale.render("apocalypse.phase.${rules.phaseAt(state.survival.worldTick).name.lowercase()}", player),
            "players" to locale.text(active.size), "enemies" to locale.text(actors.size),
            "infection" to locale.render("apocalypse.infection.${(state.infections[player.uniqueId.toString()]?.stage ?: ApocalypseInfectionStage.HEALTHY).name.lowercase()}", player))))
    }

    fun enter(player: Player, originServer: String = config().serverId, queueChecked: Boolean = false) {
        if (!queueChecked) {
            if (!busy.add(player.uniqueId)) { message(player, "pending"); return }
            playerOperations += player.uniqueId
            queued(player.uniqueId).whenComplete { inQueue, failure -> work.onMain {
                playerOperations -= player.uniqueId
                busy -= player.uniqueId
                if (!player.isOnline) return@onMain
                if (failure != null) { log("queue-check", failure); message(player, "unavailable") }
                else if (inQueue) { message(player, "occupied"); rejectArrival(player, originServer) }
                else enter(player, originServer, queueChecked = true)
            } }
            return
        }
        if (!ready) { message(player, "unavailable"); return }
        if (occupied(player.uniqueId) || player.isDead || active.size >= options.maximumPlayers) {
            message(player, "occupied"); rejectArrival(player, originServer); return
        }
        if (!host) {
            busy += player.uniqueId
            network.enter(player).whenComplete { sent, failure -> work.onMain {
                busy -= player.uniqueId
                if (failure != null) log("transfer", failure)
                if (failure != null || sent != true) message(player, "unavailable")
            } }
            return
        }
        if (isPlaying(player.uniqueId) || !busy.add(player.uniqueId)) return
        playerOperations += player.uniqueId
        val previous = players[player.uniqueId]?.let { record -> record.copy(expedition = normalizeExpedition(record.expedition)) }
        if (previous != null && previous.phase != ApocalypsePlayerPhase.PARKED) {
            playerOperations -= player.uniqueId; recoverOrigin(player, previous); return
        }
        val target = requireNotNull(world)
        val destination = previous?.expedition?.let { PaperPlayerStateCodec().decode(it).location }
        val targetX = destination?.x?.let { floor(it).toInt() } ?: layout.spawn.x
        val targetZ = destination?.z?.let { floor(it).toInt() } ?: layout.spawn.z
        target.getChunkAtAsync(targetX shr 4, targetZ shr 4, true).thenCompose {
            work.onMain {
                check(opened && player.isOnline && !player.isDead && !occupied(player.uniqueId))
                val record = ApocalypsePlayerRecord(player.uniqueId.toString(), originServer, ApocalypsePlayerPhase.ACTIVE,
                    playerStates.captureEnvelope(player, System.currentTimeMillis()), previous?.expedition,
                    nativeSession = UUID.randomUUID().toString())
                savePlayer(record).thenCompose { work.onMain {
                    check(opened && player.isOnline && !player.isDead && !occupied(player.uniqueId))
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
                        player.saveData()
                        players[player.uniqueId] = record
                        active += player.uniqueId
                        busy -= player.uniqueId
                        message(player, "entered")
                    } finally { moving -= player.uniqueId }
                } }
            }.thenCompose { it }
        }.whenComplete { _, failure -> work.onMain {
            playerOperations -= player.uniqueId
            if (failure != null) {
                // A committed origin or partially applied native restore must stay frozen until replay.
                log("entry player=${player.uniqueId}", failure)
                message(player, "restore-pending")
            }
        } }
    }

    fun leave(player: Player) {
        val record = players[player.uniqueId] ?: return
        if (record.phase == ApocalypsePlayerPhase.PARKED) {
            if (record.returnPending) attemptReturn(player, record)
            return
        }
        if (!isPlaying(player.uniqueId) || record.phase != ApocalypsePlayerPhase.ACTIVE || record.origin == null) return
        if (player.isDead || !busy.add(player.uniqueId)) { message(player, "pending"); return }
        playerOperations += player.uniqueId
        val exiting = record.copy(phase = ApocalypsePlayerPhase.RETURNING,
            expedition = captureExpedition(player))
        savePlayer(exiting).thenCompose { work.onMain { recoverOrigin(player, exiting) } }.whenComplete { _, failure -> work.onMain {
            playerOperations -= player.uniqueId
            if (failure != null) { log("leave player=${player.uniqueId}", failure); message(player, "restore-pending") }
        } }
    }

    private fun retryRecovery(player: Player) {
        if (!recoveryOperations.add(player.uniqueId)) return
        work.compute { storage!!.load(player.uniqueId) }.whenComplete { record, failure -> work.onMain {
            recoveryOperations -= player.uniqueId
            if (failure != null) { log("recovery-read player=${player.uniqueId}", failure); message(player, "restore-pending") }
            else if (record == null || record.phase == ApocalypsePlayerPhase.PARKED) busy -= player.uniqueId
            else if (record.phase == ApocalypsePlayerPhase.ACTIVE && ownsNativeInventory(player, record)) {
                // The native expedition still exists after an unacknowledged leave write: capture it again.
                val exiting = record.copy(phase = ApocalypsePlayerPhase.RETURNING, expedition = captureExpedition(player))
                playerOperations += player.uniqueId
                savePlayer(exiting).thenCompose { work.onMain { recoverOrigin(player, exiting) } }.whenComplete { _, problem -> work.onMain {
                    playerOperations -= player.uniqueId
                    if (problem != null) { log("recovery-save", problem); message(player, "restore-pending") }
                } }
            } else { players[player.uniqueId] = record; recoverOrigin(player, record) }
        } }
    }

    private fun recoverOrigin(player: Player, record: ApocalypsePlayerRecord) {
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
            bars.remove(player.uniqueId)?.let(player::hideBossBar)
            val parked = record.copy(phase = ApocalypsePlayerPhase.PARKED, origin = null,
                returnPending = record.originServer != config().serverId)
            savePlayer(parked).thenCompose { work.onMain {
                busy -= player.uniqueId
                recoveryOperations -= player.uniqueId
                message(player, "returned")
                attemptReturn(player, parked)
            } }.whenComplete { _, failure -> if (failure != null) work.onMain {
                recoveryOperations -= player.uniqueId
                log("return-ack player=${player.uniqueId}", failure); message(player, "restore-pending")
            } }
        } catch (failure: Throwable) {
            recoveryOperations -= player.uniqueId
            log("restore player=${player.uniqueId}", failure); message(player, "restore-pending")
        } finally { moving -= player.uniqueId }
    }

    private fun reconcileJoin(player: Player) {
        val record = players[player.uniqueId]
        if (record?.phase == ApocalypsePlayerPhase.ACTIVE && ownsNativeInventory(player, record)) {
            resumeNative(player, record); return
        }
        if (record != null && record.phase != ApocalypsePlayerPhase.PARKED) {
            busy += player.uniqueId; recoverOrigin(player, record); return
        }
        network.arrival(player.uniqueId).whenComplete { origin, failure -> work.onMain {
            if (failure != null) log("arrival player=${player.uniqueId}", failure)
            else if (origin != null && player.isOnline) enter(player, origin)
            else if (record?.returnPending == true && player.isOnline) attemptReturn(player, record)
        } }
    }

    private fun ownsNativeInventory(player: Player, record: ApocalypsePlayerRecord): Boolean =
        record.nativeSession != null && player.persistentDataContainer.get(nativeSession, PersistentDataType.STRING) == record.nativeSession

    private fun captureExpedition(player: Player): ru.arc.paper.playerstate.PaperPlayerStateEnvelope {
        var snapshot = playerStates.capture(player, System.currentTimeMillis())
        if (player.world != world) {
            val target = requireNotNull(world)
            snapshot = snapshot.copy(location = PaperLocationSnapshot(target.uid, target.name,
                layout.spawn.x + .5, layout.spawn.y.toDouble(), layout.spawn.z + .5, 180f, 0f), fallDistance = 0f)
        }
        return PaperPlayerStateCodec().encode(snapshot)
    }

    private fun resumeNative(player: Player, record: ApocalypsePlayerRecord) {
        if (player.world == world) {
            busy -= player.uniqueId; active += player.uniqueId; message(player, "resumed"); return
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
                active += player.uniqueId; busy -= player.uniqueId; message(player, "resumed")
            } finally { moving -= player.uniqueId }
        } }.whenComplete { _, failure -> work.onMain {
            playerOperations -= player.uniqueId
            if (failure != null) { log("native-resume", failure); message(player, "restore-pending") }
        } }
    }

    private fun rejectArrival(player: Player, origin: String) {
        if (!host || origin == config().serverId || players[player.uniqueId]?.phase?.let { it != ApocalypsePlayerPhase.PARKED } == true) return
        val record = (players[player.uniqueId] ?: ApocalypsePlayerRecord(player.uniqueId.toString(), origin,
            ApocalypsePlayerPhase.PARKED, null, null)).copy(originServer = origin, returnPending = true)
        savePlayer(record).thenCompose { work.onMain { attemptReturn(player, record) } }.exceptionally {
            log("arrival-return", it); null
        }
    }

    private fun attemptReturn(player: Player, record: ApocalypsePlayerRecord) {
        if (returnToOrigin(player, record.originServer) && record.returnPending && players[player.uniqueId] == record)
            savePlayer(record.copy(returnPending = false)).exceptionally { log("return-route-ack", it); null }
    }

    private fun savePlayer(record: ApocalypsePlayerRecord): CompletableFuture<Unit> {
        val id = UUID.fromString(record.playerId)
        val saved = (playerSaves[id] ?: CompletableFuture.completedFuture(Unit)).handle { _, _ -> Unit }
            .thenCompose { work.compute { requireNotNull(storage).save(record); Unit } }
            .thenCompose { work.onMain { players[id] = record; Unit } }
        playerSaves[id] = saved
        return saved
    }

    private fun checkpoint(after: () -> Unit = {}) {
        if (!opened) return
        if (checkpointPending) { checkpointQueued = true; return }
        checkpointPending = true
        val snapshot = state.copy(infections = state.infections.toMap(), switches = state.switches.toSet(),
            spawners = state.spawners.toMap(), hordes = state.hordes.toMap(), looted = state.looted.toSet())
        saving = work.compute { requireNotNull(journal).commit("world", snapshot); Unit }
        saving.whenComplete { _, failure -> work.onMain {
            checkpointPending = false
            if (failure != null) { log("world-checkpoint", failure); opened = false }
            else {
                committedSwitches = snapshot.switches
                committedSpawns = desiredSpawns(snapshot).keys
                after()
                if (checkpointQueued) { checkpointQueued = false; checkpoint() }
            }
        } }
    }

    private fun tick() {
        if (!opened || !host) return
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
        if (step % 25L == 0L) tickStructures(active.mapNotNull(plugin.server::getPlayer).filter { it.world == world && !it.isDead })
        if (step % 20L != 0L) return
        val survivors = active.mapNotNull(plugin.server::getPlayer).filter { it.world == world && !it.isDead && it.uniqueId !in busy }
        actors.entries.removeIf { (_, id) -> plugin.server.getEntity(id)?.isValid != true }
        if (plugin.server.onlinePlayers.isEmpty()) return
        state = state.copy(survival = state.survival.copy(worldTick = state.survival.worldTick + 20))
        world!!.time = state.survival.worldTick % 24000
        if (survivors.isEmpty()) return
        var hordeChanged = false
        survivors.forEach { player ->
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
            // Mob Control's climbing applies to nearby mobs; retain native pathing rather than teleporting actors.
            if (mob.isOnGround && target.location.distanceSquared(mob.location) > 4) {
                val direction = target.location.toVector().subtract(mob.location.toVector()).setY(0)
                if (direction.lengthSquared() < 1.0e-9) return@forEach
                direction.normalize()
                val ahead = mob.location.clone().add(direction.multiply(.8))
                if (ahead.block.type.isSolid || mob.getNearbyEntities(.7, .5, .7).any { it is Zombie })
                    mob.velocity = mob.velocity.setY(.38)
            }
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

    private fun markerAt(block: org.bukkit.block.Block): ApocalypseMarker? = chunkMarkers[block.chunk.chunkKey].orEmpty()
        .firstOrNull { it.x == block.x && it.y == block.y && it.z == block.z }
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

    private fun stock(marker: ApocalypseMarker, player: Player): Boolean {
        val block = world!!.getBlockAt(marker.x, marker.y, marker.z)
        val container = block.state as? Container ?: return true
        // Stock both original chest halves in one grant before opening their combined inventory.
        val double = container.inventory as? DoubleChestInventory
        val halves = double?.let { listOfNotNull(it.leftSide.holder as? Chest, it.rightSide.holder as? Chest) }.orEmpty()
        val markers = (listOf(marker) + halves.mapNotNull { markerAt(it.block) }).distinctBy(::markerId)
        if (markers.any { markerId(it) in stocking }) { message(player, "pending"); return false }
        val needed = markers.filter {
            val placed = world!!.getBlockAt(it.x, it.y, it.z).state as? Container
            placed != null && !placed.persistentDataContainer.has(stocked) && markerId(it) !in state.looted
        }
        if (needed.isEmpty()) return true
        if (checkpointPending) { message(player, "pending"); return false }
        val ids = needed.map(::markerId).toSet()
        state = state.copy(looted = state.looted + ids)
        stocking += ids
        checkpoint {
            needed.forEach { source ->
                val current = world!!.getBlockAt(source.x, source.y, source.z).state as? Container ?: return@forEach
                val id = markerId(source)
                if (current.persistentDataContainer.has(stocked)) return@forEach
                val loot = java.util.Random(options.seed xor id.hashCode().toLong())
                val contents = mutableListOf<ItemStack>()
                if (source.kind.name == "MEDICAL") contents += listOf(vaccine(), ItemStack.of(Material.BREAD, 4), ItemStack.of(Material.HONEY_BOTTLE))
                else {
                    contents += firearms.worldAmmunition(player, options.ammoMinimum + loot.nextInt(options.ammoMaximum - options.ammoMinimum + 1))
                    contents += ItemStack.of(Material.COOKED_BEEF, 2 + loot.nextInt(4))
                    contents += ItemStack.of(Material.IRON_INGOT, 1 + loot.nextInt(3))
                    if (source.kind.name in setOf("ARMORY", "CORE") || loot.nextInt(4) == 0) {
                        val catalog = listOf(FirearmId.FIVE_SEVEN, FirearmId.G36, FirearmId.M1_GARAND, FirearmId.VEPR_12)
                        contents += firearms.worldFirearmItem(catalog[loot.nextInt(catalog.size)], player)
                    }
                    if (loot.nextInt(5) == 0) contents += vaccine()
                }
                // Preserve items placed through any native inventory route while the first entitlement was pending.
                contents.forEachIndexed { i, item ->
                    val slot = (i * 7 + 3) % current.snapshotInventory.size
                    if (current.snapshotInventory.getItem(slot) == null) current.snapshotInventory.setItem(slot, item)
                    else current.snapshotInventory.addItem(item)
                }
                current.persistentDataContainer.set(stocked, PersistentDataType.BYTE, 1.toByte())
                check(current.update(true, false))
            }
            stocking -= ids
            if (player.isOnline && isPlaying(player.uniqueId) && player.world == world && player.location.distanceSquared(block.location) <= 36)
                player.openInventory((block.state as Container).inventory)
        }
        return false
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
    fun onJoin(event: PlayerJoinEvent) {
        if (!host) return
        if (players[event.player.uniqueId]?.phase?.let { it != ApocalypsePlayerPhase.PARKED } == true) busy += event.player.uniqueId
        tasks.runLater(2) { if (event.player.isOnline) reconcileJoin(event.player) }
    }
    @EventHandler(priority = EventPriority.LOWEST)
    fun onQuit(event: PlayerQuitEvent) {
        busy -= event.player.uniqueId
        active -= event.player.uniqueId
        bars.remove(event.player.uniqueId)?.let(event.player::hideBossBar)
        // ACTIVE native player data stays in the expedition on disconnect; the origin journal remains intact.
    }
    @EventHandler(priority = EventPriority.MONITOR)
    fun onChunk(event: ChunkLoadEvent) { if (event.world == world) pendingChunks.addLast(event.chunk) }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onUnload(event: ChunkUnloadEvent) { if (event.world == world) chunkMarkers.remove(event.chunk.chunkKey) }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onTeleport(event: PlayerTeleportEvent) {
        if (event.player.uniqueId in moving) return
        if (isPlaying(event.player.uniqueId) && event.to.world != world) {
            event.isCancelled = true; message(event.player, "leave-first")
        } else if (event.to.world == world && !isPlaying(event.player.uniqueId)) event.isCancelled = true
    }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onCommand(event: PlayerCommandPreprocessEvent) {
        if (!isPlaying(event.player.uniqueId) && !isBusy(event.player.uniqueId)) return
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
        val marker = markerAt(block) ?: return
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
        val marker = markerAt(event.block) ?: return
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
    fun onInventory(event: InventoryClickEvent) { if (isBusy(event.whoClicked.uniqueId)) event.isCancelled = true }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onOpen(event: InventoryOpenEvent) {
        if (isBusy(event.player.uniqueId) || isPlaying(event.player.uniqueId) && event.inventory.type == InventoryType.ENDER_CHEST)
            event.isCancelled = true
        val at = event.inventory.location
        if (at != null && at.world == world && markerAt(at.block)?.let(::markerId) in stocking) event.isCancelled = true
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
    fun onDrag(event: InventoryDragEvent) { if (isBusy(event.whoClicked.uniqueId)) event.isCancelled = true }
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
        bars.forEach { (id, bar) -> plugin.server.getPlayer(id)?.hideBossBar(bar) }; bars.clear()
        actors.values.mapNotNull(plugin.server::getEntity).forEach(Entity::remove); actors.clear()
        val snapshot = state
        val pendingWrite = saving
        // Admit this IO action before the runtime seals its executor. Wait for the older write only on that worker.
        closeCompletion = if (host) work.compute {
            pendingWrite.handle { _, _ -> Unit }.join()
            journal!!.commit("world", snapshot); Unit
        } else CompletableFuture.completedFuture(Unit)
        HandlerList.unregisterAll(this)
        // Loaded native generator worlds require the existing coordinated restart barrier for code replacement.
    }
}
