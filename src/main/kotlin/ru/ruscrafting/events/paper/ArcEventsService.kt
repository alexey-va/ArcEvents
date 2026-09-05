package ru.ruscrafting.events.paper

import net.kyori.adventure.text.Component
import net.kyori.adventure.title.Title
import org.bukkit.GameMode
import org.bukkit.FluidCollisionMode
import org.bukkit.Location
import org.bukkit.NamespacedKey
import org.bukkit.Particle
import org.bukkit.Sound
import org.bukkit.attribute.Attribute
import org.bukkit.entity.Entity
import org.bukkit.entity.Item
import org.bukkit.entity.Player
import org.bukkit.entity.Projectile
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType
import org.bukkit.plugin.Plugin
import org.bukkit.util.Vector
import ru.arc.core.ScheduledTask
import ru.arc.core.Tasks
import ru.arc.paper.teleport.ScopedTeleportAuthorizer
import ru.ruscrafting.events.config.ArcEventsConfig
import ru.ruscrafting.events.config.ArcEventsLocale
import ru.ruscrafting.events.config.EventBounds
import ru.ruscrafting.events.config.EventLocation
import ru.ruscrafting.events.config.NodeMode
import ru.ruscrafting.events.config.TttSettings
import ru.ruscrafting.events.domain.MatchEndReason
import ru.ruscrafting.events.domain.MatchOutcome
import ru.ruscrafting.events.domain.MatchPhase
import ru.ruscrafting.events.domain.EventRecoveryGate
import ru.ruscrafting.events.domain.ParticipantStatus
import ru.ruscrafting.events.domain.PlayerEventStats
import ru.ruscrafting.events.domain.CombatRecord
import ru.ruscrafting.events.domain.FirearmSpread
import ru.ruscrafting.events.domain.FirearmId
import ru.ruscrafting.events.domain.RosterEntry
import ru.ruscrafting.events.domain.RosterStatus
import ru.ruscrafting.events.domain.ShotDirection
import ru.ruscrafting.events.domain.RoleAllocationSettings
import ru.ruscrafting.events.domain.QueuedPlayer
import ru.ruscrafting.events.domain.TttMatch
import ru.ruscrafting.events.domain.EventMode
import ru.ruscrafting.events.domain.ArcadeMatch
import ru.ruscrafting.events.domain.ArcadeRules
import ru.ruscrafting.events.domain.TttMatchEngine
import ru.ruscrafting.events.domain.TttMatchRuntime
import ru.ruscrafting.events.domain.TttParticipant
import ru.ruscrafting.events.domain.TttRole
import ru.ruscrafting.events.domain.TttTeam
import ru.ruscrafting.events.domain.team
import ru.ruscrafting.events.network.QueueEntry
import ru.ruscrafting.events.network.QueueState
import ru.ruscrafting.events.network.ReservationBatch
import java.time.Duration
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.logging.Level
import kotlin.math.ceil
import kotlin.math.max
import kotlin.random.Random

data class ServiceSnapshot(
    val serverId: String,
    val nodeMode: NodeMode,
    val hostServer: String,
    val redisConnected: Boolean,
    val hostAvailable: Boolean,
    val arenaReady: Boolean,
    val arenaId: String?,
    val queueSize: Int,
    val matchId: UUID?,
    val phase: MatchPhase?,
    val participants: Int,
    val alive: Int,
    val traitors: Int,
    val detectives: Int,
    val recoveryPending: Int,
    val secondsRemaining: Long,
)

enum class AdminStopResult { MATCH, RESERVATION, NO_MATCH }

enum class DebugMutationResult {
    APPLIED,
    MUTATIONS_DISABLED,
    WRONG_NODE,
    ARENA_UNAVAILABLE,
    BUSY,
    INSUFFICIENT_PLAYERS,
    NO_MATCH,
    WRONG_PHASE,
    PLAYER_NOT_FOUND,
    NOT_PARTICIPANT,
    NOT_ALIVE,
    INVALID_ARGUMENT,
    ROLE_INVARIANT,
    INVENTORY_FULL,
    BODY_NOT_FOUND,
    PRECONDITION_FAILED,
    INTERNAL_ERROR,
}

class ArcEventsService(
    private val plugin: Plugin,
    private val settings: () -> ArcEventsConfig,
    private val locale: ArcEventsLocale,
    private val escrow: PlayerStateEscrow,
    private val items: TttItems,
    private val firearms: TttFirearms,
    private val hud: TttHud,
    private val lootScene: TttLootScene,
    private val smokeGrenades: TttSmokeGrenades,
    private val network: EventNetworkCoordinator,
    private val debug: ArcEventsDebug,
    private val redisConnected: () -> Boolean,
    private val arenaPool: ArenaPool,
    private val weaponPoints: ArenaWeaponPointEditor,
    private val lootSpawner: TttLootSpawner,
    private val clock: () -> Long = System::currentTimeMillis,
) : ArcEventsGameplayBoundary, AutoCloseable {
    private val localChat = TttLocalChat(plugin.server, settings, locale)
    private data class MapSpawnReturn(
        val matchId: UUID,
        val origin: Location,
        val movementToleranceSquared: Double,
        var secondsRemaining: Int,
        var task: ScheduledTask? = null,
    )

    private val runtime = TttMatchRuntime(engine = ::engine, clock = clock)
    private val match: TttMatch?
        get() = runtime.current
    private val arcade = ArcadeSession(plugin, settings, locale, items, firearms, arenaPool, network, escrow,
        ::teleport, ::recoverPlayer, { player, recovery -> completeRecovery(player, recovery) }, ::clearWeaponState, clock)
    private var arcadeRules: ArcadeRules? = null
    private var reservation: ReservationBatch? = null
    /** Gameplay and phase clocks are immutable for one reservation and its resulting match. */
    private var matchSettings: TttSettings? = null
    private val arrivals = linkedMapOf<UUID, QueueEntry>()
    private val sessionTasks = mutableListOf<ScheduledTask>()
    private var mainTickTask: ScheduledTask? = null
    private val radarTasks = mutableMapOf<UUID, ScheduledTask>()
    private val teleportAuthorizer = ScopedTeleportAuthorizer()
    private val projectiles = mutableSetOf<UUID>()
    private val bodyRegistry = TttBodyRegistry(plugin, locale, clock)
    private val shotCooldownUntil = mutableMapOf<UUID, Long>()
    private val reloadTasks = mutableMapOf<UUID, ScheduledTask>()
    private val mapSpawnReturns = mutableMapOf<UUID, MapSpawnReturn>()
    private var pendingHit: PendingHitContext? = null
    private var roundReport: RoundReportView? = null
    private val projectileMatchKey = NamespacedKey(plugin, "projectile_match")
    private var cleanupRetryTask: ScheduledTask? = null
    private val recoveryReadFailures = mutableSetOf<UUID>()
    private var recoveryCatalogFailureLogged = false
    @Volatile
    private var started = false

    fun start() {
        check(!started)
        started = true
        plugin.server.onlinePlayers.forEach(::handleJoin)
        mainTickTask = Tasks.scheduler.runTimer(20L, 20L) { tick() }
    }

    fun snapshot(): ServiceSnapshot {
        val current = settings()
        val active = match
        val pendingReservation = reservation
        val arcadeMatch = arcade.current
        return ServiceSnapshot(
            serverId = current.serverId,
            nodeMode = current.nodeMode,
            hostServer = current.hostServer,
            redisConnected = redisConnected(),
            hostAvailable = network.hostAvailable(),
            arenaReady = network.advertisedArenaReady(),
            arenaId = arenaPool.active()?.id,
            queueSize = network.queueSize,
            matchId = active?.matchId ?: arcadeMatch?.matchId ?: pendingReservation?.matchId,
            phase = active?.phase ?: arcadeMatch?.phase ?: pendingReservation?.let { MatchPhase.RESERVED },
            participants = active?.participants?.size ?: arcadeMatch?.participants?.size ?: pendingReservation?.entries?.size ?: 0,
            alive = active?.let(::visibleAliveCount) ?: arcadeMatch?.participants?.values?.count { it.status == ParticipantStatus.ALIVE } ?: 0,
            traitors = active?.alive()?.count { it.role == TttRole.TRAITOR } ?: 0,
            detectives = active?.alive()?.count { it.role == TttRole.DETECTIVE } ?: 0,
            recoveryPending = totalPendingCount(),
            secondsRemaining = (if (arcadeMatch != null) arcade.secondsRemaining() else phaseRemainingSeconds(active)).toLong(),
        )
    }

    fun matchState(): Pair<UUID?, MatchPhase?> = match?.let { it.matchId to it.phase }
        ?: arcade.current?.let { it.matchId to it.phase }
        ?: reservation?.let { it.matchId to MatchPhase.RESERVED }
        ?: (null to null)
    fun currentMode(): EventMode? = if (match != null) EventMode.TTT else arcade.current?.mode ?: reservation?.mode
    fun arcadeSnapshot(): ArcadeMatch? = arcade.current
    fun currentMatch(): TttMatch? = match
    fun participant(playerId: UUID): TttParticipant? = match?.participant(playerId)
    fun stats(playerId: UUID): PlayerEventStats = network.stats(playerId)
    fun queueState(playerId: UUID): CompletableFuture<QueueState?> = network.queueState(playerId)
    fun queueControl(playerId: UUID): CompletableFuture<QueueControlSnapshot> = network.queueControl(playerId)
    fun selectableArenaIds(): List<String> = network.selectableArenaIds()
    fun report(): RoundReportView? = roundReport
    fun bodyId(entityId: UUID): UUID? = bodyRegistry.bodyId(entityId)
    override fun isParticipant(playerId: UUID): Boolean =
        arcade.isParticipant(playerId) || match?.participant(playerId)?.status?.let { it != ParticipantStatus.RESTORED } == true
    override fun isAlive(playerId: UUID): Boolean = arcade.isAlive(playerId) || participant(playerId)?.status == ParticipantStatus.ALIVE
    fun canViewNameplate(viewerId: UUID, targetId: UUID): Boolean {
        val current = match ?: return false
        if (current.phase !in LIVE_PHASES) return false
        val viewer = current.participant(viewerId) ?: return false
        val target = current.participant(targetId) ?: return false
        return viewer.status != ParticipantStatus.RESTORED && target.status in setOf(
            ParticipantStatus.RESERVED,
            ParticipantStatus.ALIVE,
        )
    }
    override fun matchIdentity(): UUID? = matchState().first
    override fun phase(): MatchPhase? = match?.phase ?: arcade.current?.phase
    fun activeMatchId(): String? = matchState().first?.toString()
    fun arenaReady(): Boolean = arenaPool.anyReady()
    fun activeArenaId(): String? = arenaPool.active()?.id
    fun arenaEntries(): List<ArenaPoolEntry> = arenaPool.entries()
    fun selectNextArena(id: String?): Boolean = arenaPool.selectNext(id)

    fun roster(viewerId: UUID): RosterView? {
        val current = match ?: return null
        if (current.phase != MatchPhase.ACTIVE) return null
        val viewer = current.participant(viewerId) ?: return null
        val discoveredVictims = bodyRegistry.records().filter(BodyRecord::discovered).map(BodyRecord::victimId).toSet()
        return RosterView(current.participants.values.sortedBy(TttParticipant::playerName).map { participant ->
            val status = when {
                participant.status == ParticipantStatus.DISCONNECTED -> RosterStatus.DISCONNECTED
                participant.status == ParticipantStatus.ALIVE || participant.status == ParticipantStatus.RESERVED -> RosterStatus.ALIVE
                participant.playerId in discoveredVictims -> RosterStatus.CONFIRMED_DEAD
                else -> RosterStatus.MISSING
            }
            val visibleRole = when {
                participant.playerId == viewerId -> participant.role
                participant.role == TttRole.DETECTIVE -> participant.role
                participant.playerId in discoveredVictims -> participant.role
                viewer.role == TttRole.TRAITOR && participant.role == TttRole.TRAITOR -> participant.role
                else -> null
            }
            RosterEntry(participant.playerId, participant.playerName, status, visibleRole)
        })
    }

    fun bodyEvidence(viewerId: UUID, bodyId: UUID): BodyEvidenceView? {
        val current = match ?: return null
        if (current.participant(viewerId) == null) return null
        val body = bodyRegistry.get(bodyId)?.takeIf { it.matchId == current.matchId && it.discovered } ?: return null
        return BodyEvidenceView(
            bodyId = body.bodyId,
            victimId = body.victimId,
            victimName = body.victimName,
            role = body.role,
            secondsSinceDeath = ((clock() - body.killedAtMs).coerceAtLeast(0) / 1_000L),
            weaponKey = body.weaponKey,
            finalDamage = body.finalDamage,
            headshot = body.headshot,
            dnaAvailable = body.killerId?.let { killerId ->
                clock() - body.killedAtMs <= settings().weapons.dnaSeconds * 1_000L && isAlive(killerId)
            } == true,
            detectiveCalled = body.detectiveCalled,
        )
    }

    fun onReservation(batch: ReservationBatch): Boolean {
        if (!started || settings().nodeMode != NodeMode.HOST || match != null || arcade.current != null || reservation != null) {
            return false
        }
        if (arenaPool.reserve(batch.matchId, if (batch.mode == EventMode.DISASTERS) "disasters" else batch.preferredArenaId, batch.mode) == null) return false
        matchSettings = settings().ttt
        arcadeRules = batch.mode.takeUnless { it == EventMode.TTT }?.let { settings().arcade.rules(it) }
        reservation = batch
        arrivals.clear()
        debug.event("reservation_created", "match" to batch.matchId, "players" to batch.entries.size)
        sessionTasks += Tasks.scheduler.runLater(settings().network.reservationSeconds * 20L) {
            if (reservation?.matchId == batch.matchId) startReservedRoster()
        }
        return true
    }

    fun onArrival(entry: QueueEntry): Boolean {
        val batch = reservation ?: return false
        if (entry.matchId != batch.matchId.toString() || entry.mode != batch.mode.id) return false
        val playerId = UUID.fromString(entry.playerId)
        if (batch.entries.none { it.playerId == entry.playerId }) return false
        val player = plugin.server.getPlayer(playerId)?.takeIf(Player::isOnline) ?: return false
        try {
            escrow.commitThenMutate(
                batch.matchId,
                listOf(player),
                mapOf(playerId to entry.originServer),
                clock(),
            ) {
                items.clearForEvent(player)
                player.saveData()
            }
        } catch (failure: Throwable) {
            plugin.logger.log(Level.SEVERE, "ArcEvents could not isolate arrival $playerId", failure)
            return false
        }
        arrivals[playerId] = entry
        debug.event("reservation_arrival", "match" to batch.matchId, "player" to playerId, "arrived" to arrivals.size)
        if (arrivals.size == batch.entries.size) startReservedRoster()
        return true
    }

    fun joinQueue(player: Player) {
        if (hasPendingRecovery(player.uniqueId)) {
            player.sendEventMessage(locale.render("match.restore-pending", player))
            return
        }
        network.join(player)
    }

    fun leaveQueue(player: Player) = network.leave(player)

    fun leave(player: Player) {
        if (arcade.isParticipant(player.uniqueId)) {
            arcade.disconnect(player)
            runCatching { recoverPlayer(player) }.onSuccess { recovery ->
                if (recovery != null) completeRecovery(player, recovery, "match.evacuated")
            }.onFailure { plugin.logger.log(Level.SEVERE, "ArcEvents arcade evacuation failed", it) }
            return
        }
        cancelMapSpawnReturn(player.uniqueId, notify = false)
        val current = match
        val participant = current?.participant(player.uniqueId)
        if (current == null || participant == null || participant.status == ParticipantStatus.RESTORED ||
            current.phase !in EVACUATION_PHASES
        ) {
            leaveQueue(player)
            return
        }

        var changed = current
        var outcome: MatchOutcome = MatchOutcome.Continue
        if (current.phase in LIVE_PHASES && participant.status in setOf(ParticipantStatus.RESERVED, ParticipantStatus.ALIVE)) {
            val disconnected = runtime.disconnect(player.uniqueId)
            changed = disconnected.first
            outcome = disconnected.second
            debug.event("participant_evacuated", "match" to current.matchId, "player" to player.uniqueId, "phase" to current.phase)
        }

        if (outcome is MatchOutcome.Finished) resolve(changed)
        hud.remove(player.uniqueId)
        val recovery = runCatching { recoverPlayer(player) }.getOrElse { failure ->
            plugin.logger.log(Level.SEVERE, "ArcEvents could not evacuate ${player.uniqueId}", failure)
            player.sendEventMessage(locale.render("match.restore-pending", player))
            return
        }
        if (recovery == null) {
            player.sendEventMessage(locale.render("match.restore-pending", player))
            return
        }
        completeRecovery(player, recovery, "match.evacuated")

        if (changed.phase != MatchPhase.ACTIVE && changed.phase in LIVE_PHASES && !preRoundRosterViable(changed)) {
            cancel(MatchEndReason.INSUFFICIENT_PLAYERS)
        }
    }

    fun requestMapSpawnReturn(player: Player) {
        if (arcade.isParticipant(player.uniqueId)) {
            player.sendEventMessage(locale.render("match.unavailable", player))
            return
        }
        val current = match
        val participant = current?.participant(player.uniqueId)
        val arena = arenaPool.active()
        if (current == null || current.phase !in LIVE_PHASES || participant == null ||
            participant.status in setOf(ParticipantStatus.DISCONNECTED, ParticipantStatus.RESTORED) || arena == null
        ) {
            player.sendEventMessage(locale.render("match.spawn-return-unavailable", player))
            return
        }
        if (mapSpawnReturns.containsKey(player.uniqueId)) {
            player.sendEventMessage(locale.render("match.spawn-return-already", player))
            return
        }

        val gameplay = settings().gameplay
        val request = MapSpawnReturn(
            current.matchId,
            player.location.clone(),
            gameplay.spawnReturnMovementTolerance * gameplay.spawnReturnMovementTolerance,
            gameplay.spawnReturnSeconds,
        )
        mapSpawnReturns[player.uniqueId] = request
        player.sendEventMessage(locale.render(
            "match.spawn-return-requested",
            player,
            mapOf("seconds" to locale.text(gameplay.spawnReturnSeconds)),
        ))
        request.task = Tasks.scheduler.runTimer(20L, 20L) {
            val active = match
            val latest = mapSpawnReturns[player.uniqueId]
            if (!player.isOnline || latest !== request || active?.matchId != request.matchId ||
                active.phase !in LIVE_PHASES || arenaPool.active()?.id != arena.id
            ) {
                cancelMapSpawnReturn(player.uniqueId, notify = false)
                return@runTimer
            }
            if (request.origin.world !== player.location.world ||
                request.origin.distanceSquared(player.location) > request.movementToleranceSquared
            ) {
                cancelMapSpawnReturn(player.uniqueId)
                return@runTimer
            }
            request.secondsRemaining -= 1
            if (request.secondsRemaining > 0) {
                player.sendEventActionBar(locale.render(
                    "match.spawn-return-actionbar",
                    player,
                    mapOf("seconds" to locale.text(request.secondsRemaining)),
                ))
                return@runTimer
            }

            mapSpawnReturns.remove(player.uniqueId)
            request.task?.cancel()
            runCatching { teleport(player, arena.playerSpawn) }
                .onSuccess { player.sendEventMessage(locale.render("match.spawn-return-complete", player)) }
                .onFailure { failure ->
                    plugin.logger.log(Level.WARNING, "ArcEvents could not return ${player.uniqueId} to the map spawn", failure)
                    player.sendEventMessage(locale.render("match.spawn-return-unavailable", player))
                }
        }
    }

    override fun cancelMapSpawnReturn(playerId: UUID, notify: Boolean) {
        val request = mapSpawnReturns.remove(playerId) ?: return
        request.task?.let { task -> runCatching(task::cancel) }
        if (notify) {
            plugin.server.getPlayer(playerId)?.let { player ->
                player.sendEventActionBar(locale.render("match.spawn-return-cancelled", player))
            }
        }
    }

    override fun handleJoin(player: Player) {
        Tasks.scheduler.runLater(1L) {
            if (!started || !player.isOnline) return@runLater
            val pending = hasPendingRecovery(player.uniqueId)
            if (pending) {
                val recovery = runCatching { recoverPlayer(player) }.getOrElse {
                    plugin.logger.log(Level.SEVERE, "ArcEvents could not restore ${player.uniqueId} on join", it)
                    player.sendEventMessage(locale.render("match.restore-pending", player))
                    return@runLater
                }
                if (recovery != null) {
                    completeRecovery(player, recovery)
                    return@runLater
                }
            }
            network.handleJoin(player)
        }
    }

    override fun handleQuit(player: Player) {
        if (arcade.isParticipant(player.uniqueId)) { arcade.disconnect(player); return }
        cancelMapSpawnReturn(player.uniqueId, notify = false)
        hud.remove(player.uniqueId)
        val current = match ?: return
        if (current.participant(player.uniqueId) == null || current.phase !in LIVE_PHASES) return
        val (changed, outcome) = runtime.disconnect(player.uniqueId)
        debug.event("participant_disconnected", "match" to current.matchId, "player" to player.uniqueId, "phase" to current.phase)
        if (outcome is MatchOutcome.Finished) resolve(changed)
        else if (changed.phase != MatchPhase.ACTIVE && !preRoundRosterViable(changed)) {
            cancel(MatchEndReason.INSUFFICIENT_PLAYERS)
        }
    }

    fun startFromQueue(
        requester: Player? = null,
        preferredArenaId: String? = null,
        mode: EventMode = EventMode.TTT,
    ): CompletableFuture<ReservationStartResult> {
        if (requester != null && hasPendingRecovery(requester.uniqueId)) {
            return CompletableFuture.completedFuture(ReservationStartResult.RECOVERY_PENDING)
        }
        return network.reserveNow(requester, preferredArenaId, mode)
    }

    /** Reconciles every live consumer after the plugin atomically swaps its settings snapshot. */
    fun reconfigureRuntime() {
        arenaPool.reconfigure()
        network.reconfigure()
        val current = match
        current?.participants?.values?.mapNotNull { plugin.server.getPlayer(it.playerId) }?.forEach { player ->
            runCatching {
                player.inventory.storageContents = player.inventory.storageContents.map { stack ->
                    stack?.let { firearms.refreshItem(it, player) }
                }.toTypedArray()
                player.inventory.setItemInOffHand(firearms.refreshItem(player.inventory.itemInOffHand, player))
            }.onFailure { failure ->
                plugin.logger.log(Level.WARNING, "ArcEvents could not refresh live items for ${player.uniqueId}", failure)
            }
        }
        lootScene.reconfigure()
        smokeGrenades.reconfigure()
        val visible = current?.takeIf { it.phase in LIVE_PHASES }
        val matchTtt = activeTtt()
        val totalSeconds = when (visible?.phase) {
            MatchPhase.PREPARING -> matchTtt.preparationSeconds
            MatchPhase.COUNTDOWN -> matchTtt.countdownSeconds
            MatchPhase.ACTIVE -> matchTtt.roundSeconds
            else -> 1
        }
        hud.reconfigure(visible, phaseRemainingSeconds(visible), totalSeconds)
    }

    fun stopByAdmin(): AdminStopResult {
        if (arcade.current != null) { arcade.cancel(MatchEndReason.ADMIN); return AdminStopResult.MATCH }
        reservation?.let { pending ->
            val arrivedPlayers = arrivals.keys.mapNotNull(plugin.server::getPlayer)
            restoreReservationArrivals(pending, arrivedPlayers)
            reservation = null
            matchSettings = null
            arrivals.clear()
            arenaPool.release(pending.matchId)
            network.releaseReservation(pending)
            return AdminStopResult.RESERVATION
        }
        if (match?.phase !in LIVE_PHASES) return AdminStopResult.NO_MATCH
        cancel(MatchEndReason.ADMIN)
        return AdminStopResult.MATCH
    }

    fun retryRecovery(): Int {
        var available = 0
        val pending = allPendingPlayers() ?: return 0
        plugin.server.onlinePlayers.forEach { player ->
            if (player.uniqueId !in pending) return@forEach
            available++
            runCatching { recoverPlayer(player) }
                .onSuccess { recovery ->
                    if (recovery != null) {
                        completeRecovery(player, recovery)
                    }
                }
                .onFailure { plugin.logger.log(Level.SEVERE, "ArcEvents recovery retry failed for ${player.uniqueId}", it) }
        }
        return available
    }

    fun escrowPending(playerId: UUID): Boolean = hasPendingRecovery(playerId)

    override fun recordAttack(victimId: UUID, attackerId: UUID?) {
        if (arcade.current != null) arcade.recordAttack(victimId, attackerId) else runtime.recordAttack(victimId, attackerId)
    }

    override fun damageMultiplier(attackerId: UUID?): Double {
        if (arcade.current != null) return 1.0
        val current = match ?: return 1.0
        val attacker = attackerId?.let(current::participant) ?: return 1.0
        if (current.phase != MatchPhase.ACTIVE || attacker.status != ParticipantStatus.ALIVE) return 1.0
        return network.stats(attacker.playerId).damageMultiplier()
    }

    override fun recordDamage(victim: Player, attacker: Player?, finalDamage: Double, lethal: Boolean) {
        val current = match ?: return
        if (current.phase != MatchPhase.ACTIVE || finalDamage <= 0.0 || !finalDamage.isFinite()) return
        val victimParticipant = current.participant(victim.uniqueId) ?: return
        val attackerParticipant = attacker?.uniqueId?.let(current::participant)
        val hit = pendingHit?.takeIf { it.attackerId == attacker?.uniqueId && it.victimId == victim.uniqueId }
        val weaponKey = hit?.weaponKey ?: weaponKey(attacker)
        val friendly = attackerParticipant != null && attackerParticipant.role.team == victimParticipant.role.team
        val record = CombatRecord(
            sequence = runtime.nextCombatSequence(),
            occurredAtMs = clock(),
            attackerId = attackerParticipant?.playerId,
            attackerName = attackerParticipant?.playerName,
            victimId = victimParticipant.playerId,
            victimName = victimParticipant.playerName,
            weapon = weaponKey,
            finalDamage = finalDamage.coerceAtMost(100.0),
            friendly = friendly,
            headshot = hit?.headshot == true,
            lethal = lethal,
        ).validated()
        runtime.recordDamage(record, victim.uniqueId, attackerParticipant?.playerId, finalDamage)
    }

    override fun shouldCancelDamage(victimId: UUID, attackerId: UUID?, projectile: Boolean, projectileMatchId: UUID?): Boolean {
        if (arcade.current != null) {
            if (attackerId != null && (arcade.isParticipant(victimId) || arcade.isParticipant(attackerId)) &&
                arcade.current?.mode == EventMode.GUN_GAME && pendingHit == null) {
                val attacker = plugin.server.getPlayer(attackerId)
                if (attacker == null || items.kind(attacker.inventory.itemInMainHand) != EventItemKind.ARCADE_KNIFE) return true
            }
            return arcade.shouldCancelDamage(victimId, attackerId, projectile, projectileMatchId)
        }
        val current = match
        if (projectileMatchId != null && projectileMatchId != current?.matchId) return true
        if (current == null) return false
        val victim = current.participant(victimId)
        val attacker = attackerId?.let(current::participant)
        if (victim == null && attacker == null) return false
        if (projectile && projectileMatchId != current.matchId) return true
        if (victim == null || victim.status != ParticipantStatus.ALIVE) return true
        if (attackerId != null && (attacker == null || attacker.status != ParticipantStatus.ALIVE)) return true
        return current.phase != MatchPhase.ACTIVE
    }

    override fun useTraitorBlade(attacker: Player, victim: Player): Boolean {
        if (arcade.current != null) return arcade.useKnife(attacker, victim)
        val current = match ?: return false
        val attackerState = current.participant(attacker.uniqueId) ?: return false
        val victimState = current.participant(victim.uniqueId) ?: return false
        val held = attacker.inventory.itemInMainHand
        if (current.phase != MatchPhase.ACTIVE ||
            attackerState.status != ParticipantStatus.ALIVE ||
            attackerState.role != TttRole.TRAITOR ||
            victimState.status != ParticipantStatus.ALIVE ||
            items.kind(held) != EventItemKind.TRAITOR_BLADE ||
            !items.belongsTo(held, current.matchId.toString())
        ) return false

        runtime.recordAttack(victim.uniqueId, attacker.uniqueId)
        recordDamage(victim, attacker, victim.health.coerceAtLeast(0.1), lethal = true)
        consumeMainHand(attacker)
        eliminate(victim, attacker.uniqueId)
        return true
    }

    override fun eliminate(player: Player, killerId: UUID?) {
        if (arcade.current != null) { arcade.eliminate(player, killerId); return }
        val current = match ?: return
        if (current.phase != MatchPhase.ACTIVE || current.participant(player.uniqueId)?.status != ParticipantStatus.ALIVE) return
        val elimination = runtime.eliminate(player.uniqueId, killerId)
        val lethal = runtime.combatRecords().lastOrNull { it.victimId == player.uniqueId && it.lethal }
        runCatching {
            bodyRegistry.spawn(
                current.matchId,
                player,
                requireNotNull(current.participant(player.uniqueId)),
                elimination.effectiveKillerId,
                lethal,
                activeTtt().bodyDespawnSeconds * 20L,
            )
        }.onFailure { failure ->
            plugin.logger.log(Level.SEVERE, "ArcEvents could not spawn body evidence for ${player.uniqueId}", failure)
        }
        runCatching {
            player.gameMode = GameMode.SPECTATOR
            player.inventory.clear()
            player.showTitle(Title.title(
                locale.render("match.eliminated-title", player),
                locale.render("match.eliminated-subtitle", player),
                Title.Times.times(Duration.ofMillis(150), Duration.ofSeconds(3), Duration.ofMillis(400)),
            ))
            player.sendEventMessage(locale.render("match.spectator", player))
        }.onFailure { failure ->
            plugin.logger.log(Level.SEVERE, "ArcEvents could not apply elimination presentation for ${player.uniqueId}", failure)
        }
        debug.event(
            "player_eliminated",
            "match" to current.matchId,
            "victim" to player.uniqueId,
            "killer" to elimination.effectiveKillerId,
        )
        if (elimination.outcome is MatchOutcome.Finished) resolve(elimination.match)
    }

    override fun registerProjectile(projectile: Projectile): Boolean {
        val shooter = projectile.shooter as? Player ?: return true
        val current = match ?: return false
        val participant = current.participant(shooter.uniqueId) ?: return true
        if (current.phase != MatchPhase.ACTIVE || participant.status != ParticipantStatus.ALIVE) return false
        projectile.persistentDataContainer.set(projectileMatchKey, PersistentDataType.STRING, current.matchId.toString())
        projectiles += projectile.uniqueId
        return true
    }

    override fun projectileMatchId(projectile: Projectile): UUID? = projectile.persistentDataContainer
        .get(projectileMatchKey, PersistentDataType.STRING)
        ?.let { runCatching { UUID.fromString(it) }.getOrNull() }

    override fun belongsToCurrentMatch(playerId: UUID, item: ItemStack?): Boolean {
        arcade.current?.let { return arcade.isParticipant(playerId) && items.belongsTo(item, it.matchId.toString()) }
        val current = match ?: return false
        return current.participant(playerId) != null && items.belongsTo(item, current.matchId.toString())
    }

    override fun handleProjectileHit(projectile: Projectile) {
        if (projectileMatchId(projectile) == null) return
        smokeGrenades.handleHit(projectile)
        projectiles.remove(projectile.uniqueId)
        Tasks.scheduler.runLater(1L) { if (projectile.isValid) projectile.remove() }
    }

    override fun handlesMatchChat(playerId: UUID): Boolean {
        arcade.current?.let { return it.phase in CHAT_PHASES && arcade.isParticipant(playerId) }
        val current = match ?: return false
        return current.phase in CHAT_PHASES && current.participant(playerId)?.status != ParticipantStatus.RESTORED
    }

    override fun sendMatchChat(player: Player, message: Component) {
        if (arcade.current != null) { arcade.sendChat(player, message); return }
        val current = match ?: return
        if (current.phase !in CHAT_PHASES) return
        localChat.send(current, player, message)
    }

    override fun inspectBody(player: Player, bodyId: UUID) {
        val body = bodyRegistry.get(bodyId) ?: return
        val current = match ?: return
        if (current.matchId != body.matchId || current.phase != MatchPhase.ACTIVE || !isAlive(player.uniqueId)) return
        if (!body.discovered) {
            body.discovered = true
            bodyRegistry.reveal(body.bodyId, locale.render("body.identified", player, mapOf(
                "player" to Component.text(body.victimName),
                "role" to roleName(body.role, player),
            )))
            broadcast("body.discovered", mapOf(
                "player" to Component.text(body.victimName),
                "role" to roleName(body.role, player),
            ))
        } else {
            player.sendEventMessage(locale.render("body.already", player, mapOf(
                "player" to Component.text(body.victimName),
                "role" to roleName(body.role, player),
            )))
        }
    }

    fun scanBody(player: Player, bodyId: UUID): Boolean {
        val body = bodyRegistry.get(bodyId) ?: return false
        val current = match ?: return false
        val inspector = current.participant(player.uniqueId) ?: return false
        if (body.matchId != current.matchId || !body.discovered || current.phase != MatchPhase.ACTIVE ||
            inspector.status != ParticipantStatus.ALIVE || inspector.role != TttRole.DETECTIVE
        ) {
            player.sendEventMessage(locale.render("body.scanner-detective-only", player))
            return false
        }
        val scanner = player.inventory.contents.any { item ->
            items.kind(item) == EventItemKind.DETECTIVE_SCANNER && items.belongsTo(item, current.matchId.toString())
        }
        if (!scanner) {
            player.sendEventMessage(locale.render("body.scanner-required", player))
            return false
        }
        val killer = body.killerId?.let(plugin.server::getPlayer)?.takeIf { isAlive(it.uniqueId) }
            ?.takeIf { clock() - body.killedAtMs <= settings().weapons.dnaSeconds * 1_000L }
        if (killer == null) {
            player.sendEventMessage(locale.render("body.dna-lost", player))
            return false
        }
        player.compassTarget = killer.location
        player.sendEventMessage(locale.render("body.dna", player, mapOf(
            "killer" to Component.text(killer.name),
            "distance" to locale.text(ceil(player.location.distance(killer.location)).toInt()),
        )))
        return true
    }

    fun callDetective(player: Player, bodyId: UUID): Boolean {
        val body = bodyRegistry.get(bodyId) ?: return false
        val current = match ?: return false
        if (body.matchId != current.matchId || current.phase != MatchPhase.ACTIVE ||
            current.participant(player.uniqueId)?.status != ParticipantStatus.ALIVE || !body.discovered
        ) return false
        if (body.detectiveCalled) {
            player.sendEventMessage(locale.render("body.detective-already-called", player))
            return false
        }
        val detectives = current.participants.values.filter { it.role == TttRole.DETECTIVE && it.status == ParticipantStatus.ALIVE }
            .mapNotNull { plugin.server.getPlayer(it.playerId) }
        if (detectives.isEmpty()) {
            player.sendEventMessage(locale.render("body.no-detective", player))
            return false
        }
        body.detectiveCalled = true
        detectives.forEach { detective ->
            detective.compassTarget = body.location
            detective.sendEventMessage(locale.render("body.detective-called", detective, mapOf(
                "player" to Component.text(player.name),
                "victim" to Component.text(body.victimName),
                "x" to locale.text(body.location.blockX),
                "y" to locale.text(body.location.blockY),
                "z" to locale.text(body.location.blockZ),
            )))
            if (settings().ui.sounds) detective.playSound(detective.location, Sound.BLOCK_BELL_USE, 0.8f, 1.15f)
        }
        player.sendEventMessage(locale.render("body.detective-call-sent", player))
        return true
    }

    fun buy(player: Player, offer: ShopOffer): Boolean {
        val current = match
        if (current == null) {
            player.sendEventMessage(locale.render("shop.unavailable", player))
            return false
        }
        val participant = current.participant(player.uniqueId)
        if (current.phase != MatchPhase.ACTIVE || participant?.status != ParticipantStatus.ALIVE ||
            participant.role == TttRole.INNOCENT || offer !in offers(participant.role)
        ) {
            player.sendEventMessage(locale.render("shop.unavailable", player))
            return false
        }
        if (participant.credits < offer.cost) {
            player.sendEventMessage(locale.render("shop.insufficient", player))
            return false
        }
        val purchased = items.purchasedItem(offer.kind, player, current.matchId.toString())
        if (offer.kind == EventItemKind.DETECTIVE_ARMOR) {
            player.inventory.chestplate = purchased
        } else if (player.inventory.addItem(purchased).isNotEmpty()) {
            player.sendEventMessage(locale.render("shop.inventory-full", player))
            return false
        }
        val updated = participant.copy(credits = participant.credits - offer.cost)
        runtime.replace(current.copy(
            revision = current.revision + 1,
            participants = current.participants + (player.uniqueId to updated),
        ))
        player.sendEventMessage(locale.render("shop.bought", player, mapOf(
            "item" to locale.render(offer.nameKey, player),
            "credits" to locale.text(updated.credits),
        )))
        if (settings().ui.sounds) player.playSound(player.location, Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.65f, 1.25f)
        return true
    }

    override fun useSpecialItem(player: Player, kind: EventItemKind): Boolean {
        val current = match ?: return false
        val participant = current.participant(player.uniqueId) ?: return false
        if (current.phase != MatchPhase.ACTIVE || participant.status != ParticipantStatus.ALIVE ||
            !items.belongsTo(player.inventory.itemInMainHand, current.matchId.toString())
        ) return false
        return when (kind) {
            EventItemKind.TRAITOR_RADAR -> activateRadar(player, current)
            EventItemKind.TRAITOR_SMOKE -> activateSmoke(player)
            EventItemKind.DETECTIVE_MEDKIT -> activateMedkit(player)
            EventItemKind.GUIDE, EventItemKind.SHOP, EventItemKind.FIREARM, EventItemKind.AMMUNITION, EventItemKind.ROUND_REPORT,
            EventItemKind.ARCADE_KNIFE, EventItemKind.DETECTIVE_SCANNER, EventItemKind.TRAITOR_BLADE, EventItemKind.DETECTIVE_ARMOR -> false
        }
    }

    override fun useFirearm(player: Player): Boolean {
        val matchId = activeMatchId() ?: return false
        if (!isAlive(player.uniqueId) || phase() != MatchPhase.ACTIVE || currentMode() == EventMode.DISASTERS) return false
        val held = player.inventory.itemInMainHand
        val state = firearms.state(held) ?: return false
        if (!settings().weapons.enabled || phase() != MatchPhase.ACTIVE || !isAlive(player.uniqueId) ||
            state.matchId != matchId
        ) return false
        if (reloadTasks.containsKey(player.uniqueId)) {
            player.sendEventActionBar(locale.render("weapon.reloading-actionbar", player))
            return false
        }
        val spec = firearms.spec(state.id)
        val now = clock()
        if (now < (shotCooldownUntil[player.uniqueId] ?: 0L)) return false
        if (state.loaded < spec.roundsPerShot) {
            shotCooldownUntil[player.uniqueId] = now + 350L
            player.sendEventActionBar(locale.render("weapon.empty-actionbar", player))
            if (settings().ui.sounds) player.playSound(player.location, Sound.BLOCK_LEVER_CLICK, 0.7f, 1.7f)
            return false
        }
        shotCooldownUntil[player.uniqueId] = now + spec.cooldownTicks * 50L
        val loaded = state.loaded - spec.roundsPerShot
        player.inventory.setItemInMainHand(firearms.updateLoaded(held, player, loaded))
        val eye = player.eyeLocation.clone()
        val base = eye.direction.normalize()
        val random = Random(now xor player.uniqueId.mostSignificantBits xor runtime.combatRecordCount().toLong())
        repeat(spec.pellets) {
            val yaw = (random.nextDouble() * 2.0 - 1.0) * spec.spreadDegrees
            val pitch = (random.nextDouble() * 2.0 - 1.0) * spec.spreadDegrees
            val spread = FirearmSpread.apply(ShotDirection(base.x, base.y, base.z), yaw, pitch)
            fireRay(player, state.id.name.lowercase(), eye, Vector(spread.x, spread.y, spread.z), spec.range, spec.damagePerPellet)
        }
        if (settings().ui.sounds) {
            val (sound, volume, pitch) = when (state.id) {
                FirearmId.FLINTLOCK -> Triple(Sound.ENTITY_FIREWORK_ROCKET_BLAST, 0.9f, 0.82f)
                FirearmId.REVOLVER -> Triple(Sound.ENTITY_FIREWORK_ROCKET_BLAST, 0.82f, 1.0f)
                FirearmId.FIVE_SEVEN -> Triple(Sound.ENTITY_FIREWORK_ROCKET_BLAST, 0.75f, 1.18f)
                FirearmId.G36 -> Triple(Sound.ENTITY_FIREWORK_ROCKET_LARGE_BLAST, 0.8f, 1.02f)
                FirearmId.AEK_971 -> Triple(Sound.ENTITY_FIREWORK_ROCKET_LARGE_BLAST, 0.86f, 0.92f)
                FirearmId.RPL_20 -> Triple(Sound.ENTITY_FIREWORK_ROCKET_LARGE_BLAST, 0.95f, 0.76f)
                FirearmId.DOUBLE_BARREL -> Triple(Sound.ENTITY_GENERIC_EXPLODE, 0.95f, 0.72f)
                FirearmId.VEPR_12 -> Triple(Sound.ENTITY_GENERIC_EXPLODE, 0.82f, 0.86f)
                FirearmId.HAND_CANNON -> Triple(Sound.ENTITY_GENERIC_EXPLODE, 1.05f, 0.62f)
                FirearmId.M1_GARAND -> Triple(Sound.ENTITY_FIREWORK_ROCKET_TWINKLE_FAR, 0.9f, 1.05f)
                FirearmId.VSS_VINTOREZ -> Triple(Sound.ENTITY_FIREWORK_ROCKET_TWINKLE_FAR, 0.72f, 1.28f)
                FirearmId.MCMILLAN -> Triple(Sound.ENTITY_FIREWORK_ROCKET_TWINKLE_FAR, 1.0f, 0.72f)
            }
            player.world.playSound(player.location, sound, volume, pitch)
        }
        player.sendEventActionBar(locale.render("weapon.ammo-actionbar", player, mapOf(
            "weapon" to locale.render("weapon.${state.id.name.lowercase()}-name", player),
            "loaded" to locale.text(loaded),
            "magazine" to locale.text(spec.magazineSize),
            "reserve" to locale.text(firearms.reserveAmmo(player, matchId)),
        )))
        return true
    }

    override fun reloadFirearm(player: Player): Boolean {
        val matchId = activeMatchId() ?: return false
        if (!isAlive(player.uniqueId) || phase() != MatchPhase.ACTIVE || currentMode() == EventMode.DISASTERS) return false
        val state = firearms.state(player.inventory.itemInMainHand) ?: return false
        if (phase() != MatchPhase.ACTIVE || !isAlive(player.uniqueId) || state.matchId != matchId) return false
        val spec = firearms.spec(state.id)
        if (state.loaded >= spec.magazineSize) {
            player.sendEventActionBar(locale.render("weapon.magazine-full-actionbar", player))
            return false
        }
        if (firearms.reserveAmmo(player, state.matchId) == 0) {
            player.sendEventActionBar(locale.render("weapon.no-ammo-actionbar", player))
            return false
        }
        if (reloadTasks.containsKey(player.uniqueId)) return false
        player.sendEventActionBar(locale.render("weapon.reloading-actionbar", player))
        if (settings().ui.sounds) player.playSound(player.location, Sound.ITEM_ARMOR_EQUIP_IRON, 0.5f, 1.4f)
        reloadTasks[player.uniqueId] = Tasks.scheduler.runLater(spec.reloadTicks.toLong()) {
            reloadTasks.remove(player.uniqueId)
            val liveId = activeMatchId()
            val held = player.inventory.itemInMainHand
            val latest = firearms.state(held)
            if (!player.isOnline || liveId != matchId || phase() != MatchPhase.ACTIVE || !isAlive(player.uniqueId) ||
                latest?.id != state.id || latest.matchId != state.matchId
            ) return@runLater
            val needed = spec.magazineSize - latest.loaded
            val consumed = firearms.consumeReserve(player, latest.matchId, needed)
            if (consumed <= 0) return@runLater
            player.inventory.setItemInMainHand(firearms.updateLoaded(held, player, latest.loaded + consumed))
            player.sendEventActionBar(locale.render("weapon.reload-complete-actionbar", player, mapOf(
                "loaded" to locale.text(latest.loaded + consumed),
                "magazine" to locale.text(spec.magazineSize),
                "reserve" to locale.text(firearms.reserveAmmo(player, latest.matchId)),
            )))
            if (settings().ui.sounds) player.playSound(player.location, Sound.BLOCK_IRON_TRAPDOOR_CLOSE, 0.55f, 1.5f)
        }
        return true
    }

    override fun canDropLoot(player: Player, item: ItemStack?): Boolean {
        val current = match ?: return false
        return lootAccessible(current.phase, current.participant(player.uniqueId)?.status) &&
            firearms.isLoot(item, current.matchId.toString())
    }

    override fun registerDroppedLoot(item: Item) {
        val current = match ?: return item.remove()
        if (!firearms.isLoot(item.itemStack, current.matchId.toString())) return item.remove()
        lootScene.register(item)
    }

    override fun canPickupLoot(player: Player, item: Item): Boolean {
        val current = match ?: return false
        return lootAccessible(current.phase, current.participant(player.uniqueId)?.status) &&
            firearms.isLoot(item.itemStack, current.matchId.toString())
    }

    override fun handleLootPickup(player: Player, item: Item) {
        val firearm = firearms.state(item.itemStack)
        lootScene.consume(item.uniqueId)
        val current = match ?: return
        if (firearm?.matchId != current.matchId.toString()) return
        val gameplay = settings().gameplay
        val rounds = pickupReserveRounds(
            firearms.spec(firearm.id).magazineSize,
            gameplay.pickupAmmoMagazines,
            gameplay.pickupAmmoMinimum,
            gameplay.pickupAmmoMaximum,
        )
        Tasks.scheduler.runLater(1L) {
            val active = match
            if (!player.isOnline || active?.matchId != current.matchId ||
                !lootAccessible(active.phase, active.participant(player.uniqueId)?.status)
            ) return@runLater
            val ammunition = firearms.ammunition(player, firearm.matchId, rounds)
            player.inventory.addItem(ammunition).values.forEach { remainder ->
                val dropped = player.world.dropItem(player.location, remainder)
                lootScene.register(dropped, pickupDelay = gameplay.pickupDelayTicks)
            }
            player.sendEventActionBar(locale.render(
                "weapon.pickup-ammo-actionbar",
                player,
                mapOf("rounds" to locale.text(rounds)),
            ))
        }
    }

    fun teamChat(player: Player, rawMessage: String) {
        val current = match
        val sender = current?.participant(player.uniqueId)
        if (current?.phase != MatchPhase.ACTIVE || sender?.status != ParticipantStatus.ALIVE || sender.role == TttRole.INNOCENT) {
            player.sendEventMessage(locale.render("team.unavailable", player))
            return
        }
        val message = Component.text(rawMessage.trim().take(180))
        val recipients = current.participants.values.filter { participant ->
            participant.status == ParticipantStatus.ALIVE && when (sender.role) {
                TttRole.TRAITOR -> participant.role == TttRole.TRAITOR
                TttRole.DETECTIVE -> participant.role == TttRole.DETECTIVE
                TttRole.INNOCENT -> false
            }
        }
        recipients.mapNotNull { plugin.server.getPlayer(it.playerId) }.forEach { recipient ->
            recipient.sendEventMessage(locale.render("team.message", recipient, mapOf(
                "player" to Component.text(player.name),
                "message" to message,
            )))
        }
    }

    fun status(player: Player) {
        if (arcade.current != null) { arcade.status(player); return }
        val current = match
        if (current == null || current.participant(player.uniqueId) == null) {
            player.sendEventMessage(locale.render("match.unavailable", player))
            return
        }
        player.sendEventMessage(locale.render("match.status", player, mapOf(
            "phase" to locale.render("phase.${current.phase.name.lowercase()}", player),
            "alive" to locale.text(visibleAliveCount(current)),
            "players" to locale.text(current.participants.size),
            "time" to locale.text(formatTime(phaseRemainingSeconds(current).toLong())),
        )))
    }

    fun qaStatus(): String {
        val state = snapshot()
        return ArcEventsDebug.qa(
            "server" to state.serverId,
            "mode" to state.nodeMode.name.lowercase(),
            "game" to (currentMode()?.id ?: "-"),
            "redis" to if (state.redisConnected) "up" else "down",
            "host" to if (state.hostAvailable) "ready" else "unavailable",
            "arena" to if (state.arenaReady) "ready" else "disabled",
            "arena_id" to (state.arenaId ?: "-"),
            "phase" to (state.phase?.name?.lowercase() ?: "idle"),
            "match" to (state.matchId ?: "-"),
            "queue" to state.queueSize,
            "participants" to state.participants,
            "alive" to state.alive,
            "traitors" to state.traitors,
            "detectives" to state.detectives,
            "recovery" to state.recoveryPending,
            "remaining" to state.secondsRemaining,
            "loot" to lootScene.size,
            "combat" to runtime.combatRecordCount(),
        )
    }

    fun qaPlayer(playerName: String): String {
        val player = plugin.server.getPlayerExact(playerName)
        val participant = player?.uniqueId?.let(::participant)
        val firearm = player?.let { firearms.state(it.inventory.itemInMainHand) }
        return ArcEventsDebug.qa(
            "server" to settings().serverId,
            "player" to (player?.name ?: playerName.take(16)),
            "online" to (player != null),
            "match" to (match?.matchId ?: "-"),
            "phase" to (match?.phase?.name?.lowercase() ?: "idle"),
            "role" to (participant?.role?.name?.lowercase() ?: "-"),
            "status" to (participant?.status?.name?.lowercase() ?: "none"),
            "credits" to (participant?.credits ?: 0),
            "kills" to (participant?.kills ?: 0),
            "damage" to (participant?.damageDealt?.let { "%.1f".format(it) } ?: "0.0"),
            "friendly_damage" to (participant?.friendlyDamage?.let { "%.1f".format(it) } ?: "0.0"),
            "firearm" to (firearm?.id?.name?.lowercase() ?: "-"),
            "loaded" to (firearm?.loaded ?: 0),
            "reserve" to (player?.let { activeMatchId()?.let { matchId -> firearms.reserveAmmo(it, matchId) } } ?: 0),
            "reloading" to (player?.uniqueId in reloadTasks),
            "recovery" to (player?.let { hasPendingRecovery(it.uniqueId) } ?: false),
        )
    }

    fun qaNetwork(): List<String> = network.nodeSnapshot().map { node ->
        ArcEventsDebug.qa(
            "server" to node.serverId,
            "mode" to node.mode.lowercase(),
            "available" to node.available,
            "arena" to node.arenaReady,
            "phase" to (node.phase?.name?.lowercase() ?: "idle"),
            "match" to (node.matchId ?: "-"),
            "queue" to node.queueSize,
            "capacity" to node.capacity,
            "heartbeat" to node.heartbeatAtMs,
        )
    }

    fun qaArenas(): List<String> = arenaPool.entries().map { arena ->
        val arenaSettings = settings().arenas.first { it.id == arena.id }
        ArcEventsDebug.qa(
            "server" to settings().serverId,
            "arena" to arena.id,
            "world" to arena.world,
            "template" to arena.template,
            "ready" to arena.ready,
            "active" to arena.active,
            "next" to arena.next,
            "weapons" to arenaSettings.weaponCount,
            "guaranteed" to weaponPoints.points(arenaSettings).size,
        )
    }

    fun qaRecovery(): String {
        val pending = allPendingPlayers()
        return ArcEventsDebug.qa(
            "server" to settings().serverId,
            "pending" to (pending?.size ?: -1),
            "players" to (pending?.sortedBy(UUID::toString)?.joinToString(",")?.ifEmpty { "-" } ?: "unavailable"),
        )
    }

    fun debugStartLocal(players: List<Player>, arenaId: String? = null, mode: EventMode = EventMode.TTT): DebugMutationResult {
        if (mode != EventMode.TTT) return debugStartArcade(players, arenaId, mode)
        val currentSettings = settings()
        if (!currentSettings.debugMutationsAllowed) return DebugMutationResult.MUTATIONS_DISABLED
        if (currentSettings.nodeMode != NodeMode.HOST) return DebugMutationResult.WRONG_NODE
        if (match != null || arcade.current != null || reservation != null) return DebugMutationResult.BUSY
        val online = players.filter(Player::isOnline).distinctBy(Player::getUniqueId)
        if (online.size !in currentSettings.ttt.minimumPlayers..currentSettings.ttt.maximumPlayers) {
            return DebugMutationResult.INSUFFICIENT_PLAYERS
        }
        val matchId = UUID.randomUUID()
        val arena = arenaPool.reserve(matchId, arenaId) ?: return DebugMutationResult.ARENA_UNAVAILABLE
        matchSettings = currentSettings.ttt
        val created = runtime.create(
            matchId,
            online.map { player -> QueuedPlayer(player.uniqueId, player.name, currentSettings.serverId, clock()) },
            RoleAllocationSettings(
                currentSettings.ttt.traitorPlayerRatio,
                currentSettings.ttt.detectiveMinimumPlayers,
                currentSettings.ttt.traitorCredits,
                currentSettings.ttt.detectiveCredits,
            ),
            seed = matchId.mostSignificantBits xor matchId.leastSignificantBits,
            nowMs = clock(),
        )
        var mutationAttempted = false
        return runCatching {
            runtime.clearTransientState()
            roundReport = null
            bodyRegistry.clear()
            cleanupProjectiles()
            cleanupLoot()
            val prepared = runtime.prepare(created, currentSettings.ttt.preparationSeconds)
            escrow.commitThenMutate(
                matchId,
                online,
                online.associate { it.uniqueId to currentSettings.serverId },
                clock(),
            ) {
                mutationAttempted = true
                applyEventState(online, prepared)
            }
            debug.event("debug_match_preparing", "match" to matchId, "players" to online.size, "arena" to arena.id)
            DebugMutationResult.APPLIED
        }.getOrElse { failure ->
            plugin.logger.log(Level.SEVERE, "ArcEvents debug bootstrap failed for $matchId", failure)
            if (match?.matchId == matchId) {
                if (mutationAttempted) cancel(MatchEndReason.SHUTDOWN) else abortUnmutatedPreparation(online)
            } else {
                arenaPool.release(matchId)
                matchSettings = null
            }
            DebugMutationResult.INTERNAL_ERROR
        }
    }

    fun debugAdvance(): DebugMutationResult {
        if (!settings().debugMutationsAllowed) return DebugMutationResult.MUTATIONS_DISABLED
        val current = match ?: return DebugMutationResult.NO_MATCH
        when (current.phase) {
            MatchPhase.PREPARING -> beginCountdown()
            MatchPhase.COUNTDOWN -> activateRound()
            MatchPhase.ACTIVE -> resolve(current.copy(
                revision = current.revision + 1,
                phase = MatchPhase.RESOLVING,
                winner = TttTeam.INNOCENTS,
                endReason = MatchEndReason.ADMIN,
            ))
            else -> return DebugMutationResult.WRONG_PHASE
        }
        return DebugMutationResult.APPLIED
    }

    fun debugEnd(team: TttTeam): DebugMutationResult {
        if (!settings().debugMutationsAllowed) return DebugMutationResult.MUTATIONS_DISABLED
        val current = match ?: return DebugMutationResult.NO_MATCH
        if (current.phase !in LIVE_PHASES) return DebugMutationResult.WRONG_PHASE
        resolve(current.copy(
            revision = current.revision + 1,
            phase = MatchPhase.RESOLVING,
            winner = team,
            endReason = MatchEndReason.ADMIN,
        ))
        return DebugMutationResult.APPLIED
    }

    fun debugCredit(player: Player, amount: Int): DebugMutationResult {
        if (!settings().debugMutationsAllowed) return DebugMutationResult.MUTATIONS_DISABLED
        if (amount !in -16..16) return DebugMutationResult.INVALID_ARGUMENT
        val current = match ?: return DebugMutationResult.NO_MATCH
        if (current.phase !in LIVE_PHASES) return DebugMutationResult.WRONG_PHASE
        val participant = current.participant(player.uniqueId) ?: return DebugMutationResult.NOT_PARTICIPANT
        if (participant.status !in setOf(ParticipantStatus.RESERVED, ParticipantStatus.ALIVE)) {
            return DebugMutationResult.NOT_ALIVE
        }
        runtime.replace(current.copy(
            revision = current.revision + 1,
            participants = current.participants + (player.uniqueId to participant.copy(credits = (participant.credits + amount).coerceIn(0, 64))),
        ))
        return DebugMutationResult.APPLIED
    }

    fun debugRole(player: Player, role: TttRole): DebugMutationResult {
        if (!settings().debugMutationsAllowed) return DebugMutationResult.MUTATIONS_DISABLED
        val current = match ?: return DebugMutationResult.NO_MATCH
        if (current.phase !in setOf(MatchPhase.PREPARING, MatchPhase.COUNTDOWN, MatchPhase.ACTIVE)) {
            return DebugMutationResult.WRONG_PHASE
        }
        val participant = current.participant(player.uniqueId) ?: return DebugMutationResult.NOT_PARTICIPANT
        if (participant.status !in setOf(ParticipantStatus.RESERVED, ParticipantStatus.ALIVE)) {
            return DebugMutationResult.NOT_ALIVE
        }
        val changed = current.copy(
            revision = current.revision + 1,
            participants = current.participants + (player.uniqueId to participant.copy(role = role)),
        )
        val ttt = activeTtt()
        val valid = runCatching { changed.validated(ttt.minimumPlayers, ttt.maximumPlayers) }.getOrNull()
            ?: return DebugMutationResult.ROLE_INVARIANT
        runtime.replace(valid)
        return DebugMutationResult.APPLIED
    }

    fun debugTimer(seconds: Int): DebugMutationResult {
        if (!settings().debugMutationsAllowed) return DebugMutationResult.MUTATIONS_DISABLED
        if (seconds !in 1..3600) return DebugMutationResult.INVALID_ARGUMENT
        val current = match ?: return DebugMutationResult.NO_MATCH
        if (current.phase != MatchPhase.ACTIVE) return DebugMutationResult.WRONG_PHASE
        runtime.replace(current.copy(revision = current.revision + 1, deadlineMs = clock() + seconds * 1_000L))
        return DebugMutationResult.APPLIED
    }

    fun debugHealth(player: Player, health: Double): DebugMutationResult {
        if (!settings().debugMutationsAllowed) return DebugMutationResult.MUTATIONS_DISABLED
        val current = match ?: return DebugMutationResult.NO_MATCH
        val participant = current.participant(player.uniqueId) ?: return DebugMutationResult.NOT_PARTICIPANT
        if (current.phase != MatchPhase.ACTIVE) return DebugMutationResult.WRONG_PHASE
        if (participant.status != ParticipantStatus.ALIVE) return DebugMutationResult.NOT_ALIVE
        val maximum = player.getAttribute(Attribute.MAX_HEALTH)?.value ?: 20.0
        if (!health.isFinite() || health !in 0.5..maximum) return DebugMutationResult.INVALID_ARGUMENT
        player.health = health
        return DebugMutationResult.APPLIED
    }

    fun debugWeapon(player: Player, firearm: FirearmId, loaded: Int?): DebugMutationResult {
        if (!settings().debugMutationsAllowed) return DebugMutationResult.MUTATIONS_DISABLED
        val current = match ?: return DebugMutationResult.NO_MATCH
        val participant = current.participant(player.uniqueId) ?: return DebugMutationResult.NOT_PARTICIPANT
        if (current.phase != MatchPhase.ACTIVE) return DebugMutationResult.WRONG_PHASE
        if (participant.status != ParticipantStatus.ALIVE) return DebugMutationResult.NOT_ALIVE
        val rounds = loaded ?: firearms.spec(firearm).magazineSize
        if (rounds !in 0..firearms.spec(firearm).magazineSize) return DebugMutationResult.INVALID_ARGUMENT
        val item = firearms.firearmItem(firearm, player, current.matchId.toString(), rounds)
        if (!canFullyAdd(player, item)) return DebugMutationResult.INVENTORY_FULL
        return if (player.inventory.addItem(item).isEmpty()) DebugMutationResult.APPLIED else DebugMutationResult.INTERNAL_ERROR
    }

    fun debugAmmo(player: Player, amount: Int): DebugMutationResult {
        if (!settings().debugMutationsAllowed) return DebugMutationResult.MUTATIONS_DISABLED
        if (amount !in 1..64) return DebugMutationResult.INVALID_ARGUMENT
        val current = match ?: return DebugMutationResult.NO_MATCH
        val participant = current.participant(player.uniqueId) ?: return DebugMutationResult.NOT_PARTICIPANT
        if (current.phase != MatchPhase.ACTIVE) return DebugMutationResult.WRONG_PHASE
        if (participant.status != ParticipantStatus.ALIVE) return DebugMutationResult.NOT_ALIVE
        val item = firearms.ammunition(player, current.matchId.toString(), amount)
        if (!canFullyAdd(player, item)) return DebugMutationResult.INVENTORY_FULL
        return if (player.inventory.addItem(item).isEmpty()) DebugMutationResult.APPLIED else DebugMutationResult.INTERNAL_ERROR
    }

    fun debugItem(player: Player, kind: EventItemKind): DebugMutationResult {
        if (!settings().debugMutationsAllowed) return DebugMutationResult.MUTATIONS_DISABLED
        val current = match ?: return DebugMutationResult.NO_MATCH
        val participant = current.participant(player.uniqueId) ?: return DebugMutationResult.NOT_PARTICIPANT
        if (current.phase != MatchPhase.ACTIVE) return DebugMutationResult.WRONG_PHASE
        if (participant.status != ParticipantStatus.ALIVE) return DebugMutationResult.NOT_ALIVE
        if (kind !in DEBUG_SPECIAL_ITEMS) return DebugMutationResult.INVALID_ARGUMENT
        val item = items.purchasedItem(kind, player, current.matchId.toString())
        if (kind == EventItemKind.DETECTIVE_ARMOR) {
            player.inventory.chestplate = item
            return DebugMutationResult.APPLIED
        }
        if (!canFullyAdd(player, item)) return DebugMutationResult.INVENTORY_FULL
        return if (player.inventory.addItem(item).isEmpty()) DebugMutationResult.APPLIED else DebugMutationResult.INTERNAL_ERROR
    }

    fun debugKill(victim: Player, killer: Player?): DebugMutationResult {
        if (!settings().debugMutationsAllowed) return DebugMutationResult.MUTATIONS_DISABLED
        val current = match ?: return DebugMutationResult.NO_MATCH
        val victimState = current.participant(victim.uniqueId) ?: return DebugMutationResult.NOT_PARTICIPANT
        if (current.phase != MatchPhase.ACTIVE) return DebugMutationResult.WRONG_PHASE
        if (victimState.status != ParticipantStatus.ALIVE) return DebugMutationResult.NOT_ALIVE
        if (killer != null) {
            val killerState = current.participant(killer.uniqueId) ?: return DebugMutationResult.NOT_PARTICIPANT
            if (killerState.status != ParticipantStatus.ALIVE || killer.uniqueId == victim.uniqueId) {
                return DebugMutationResult.INVALID_ARGUMENT
            }
        }
        eliminate(victim, killer?.uniqueId)
        return DebugMutationResult.APPLIED
    }

    fun debugRevive(player: Player): DebugMutationResult {
        if (!settings().debugMutationsAllowed) return DebugMutationResult.MUTATIONS_DISABLED
        val current = match ?: return DebugMutationResult.NO_MATCH
        if (current.phase != MatchPhase.ACTIVE) return DebugMutationResult.WRONG_PHASE
        val participant = current.participant(player.uniqueId) ?: return DebugMutationResult.NOT_PARTICIPANT
        if (participant.status != ParticipantStatus.DEAD) return DebugMutationResult.PRECONDITION_FAILED
        runtime.replace(current.copy(
            revision = current.revision + 1,
            participants = current.participants + (player.uniqueId to participant.copy(status = ParticipantStatus.ALIVE, deaths = 0)),
        ).let { changed ->
            val ttt = activeTtt()
            changed.validated(ttt.minimumPlayers, ttt.maximumPlayers)
        })
        bodyRegistry.removeVictim(player.uniqueId)
        player.gameMode = GameMode.ADVENTURE
        player.health = player.getAttribute(Attribute.MAX_HEALTH)?.value ?: 20.0
        player.foodLevel = 20
        items.givePreparationLoadout(player, current.matchId.toString())
        items.revealRoleLoadout(player, participant.role, current.matchId.toString())
        teleport(player, requireNotNull(arenaPool.active()).playerSpawn)
        return DebugMutationResult.APPLIED
    }

    fun debugDiscover(viewer: Player, victim: Player): DebugMutationResult {
        if (!settings().debugMutationsAllowed) return DebugMutationResult.MUTATIONS_DISABLED
        val current = match ?: return DebugMutationResult.NO_MATCH
        if (current.phase != MatchPhase.ACTIVE) return DebugMutationResult.WRONG_PHASE
        if (current.participant(viewer.uniqueId)?.status != ParticipantStatus.ALIVE) return DebugMutationResult.NOT_ALIVE
        val body = bodyRegistry.latest(current.matchId, victim.uniqueId)
            ?: return DebugMutationResult.BODY_NOT_FOUND
        inspectBody(viewer, body.bodyId)
        return DebugMutationResult.APPLIED
    }

    fun debugDna(viewer: Player, victim: Player): DebugMutationResult {
        if (!settings().debugMutationsAllowed) return DebugMutationResult.MUTATIONS_DISABLED
        val current = match ?: return DebugMutationResult.NO_MATCH
        val body = bodyRegistry.latest(current.matchId, victim.uniqueId)
            ?: return DebugMutationResult.BODY_NOT_FOUND
        return if (scanBody(viewer, body.bodyId)) DebugMutationResult.APPLIED else DebugMutationResult.PRECONDITION_FAILED
    }

    fun debugCallDetective(viewer: Player, victim: Player): DebugMutationResult {
        if (!settings().debugMutationsAllowed) return DebugMutationResult.MUTATIONS_DISABLED
        val current = match ?: return DebugMutationResult.NO_MATCH
        val body = bodyRegistry.latest(current.matchId, victim.uniqueId)
            ?: return DebugMutationResult.BODY_NOT_FOUND
        return if (callDetective(viewer, body.bodyId)) DebugMutationResult.APPLIED else DebugMutationResult.PRECONDITION_FAILED
    }

    fun debugLoot(respawn: Boolean): DebugMutationResult {
        if (!settings().debugMutationsAllowed) return DebugMutationResult.MUTATIONS_DISABLED
        val current = match ?: return DebugMutationResult.NO_MATCH
        if (current.phase !in LIVE_PHASES) return DebugMutationResult.WRONG_PHASE
        cleanupLoot()
        if (respawn) spawnLoot(current)
        return DebugMutationResult.APPLIED
    }

    fun debugCleanup(): DebugMutationResult {
        if (!settings().debugMutationsAllowed) return DebugMutationResult.MUTATIONS_DISABLED
        if (match != null || arcade.current != null || reservation != null) return DebugMutationResult.BUSY
        bodyRegistry.clear()
        cleanupProjectiles()
        cleanupLoot()
        roundReport = null
        runtime.clearTransientState()
        return DebugMutationResult.APPLIED
    }

    fun qaBodies(): List<String> = bodyRegistry.records().sortedBy(BodyRecord::killedAtMs).map { body ->
        ArcEventsDebug.qa(
            "server" to settings().serverId,
            "body" to body.bodyId,
            "victim" to body.victimName,
            "role" to body.role.name.lowercase(),
            "killer" to (body.killerId ?: "-"),
            "discovered" to body.discovered,
            "called" to body.detectiveCalled,
            "age" to ((clock() - body.killedAtMs).coerceAtLeast(0) / 1_000L),
        )
    }

    override fun isInternalTeleport(playerId: UUID, destination: Location?): Boolean =
        teleportAuthorizer.isAuthorized(playerId, destination)

    override fun withinArena(location: Location): Boolean {
        val bounds = arenaPool.active()?.bounds ?: return false
        return bounds.contains(location.eventLocation())
    }

    fun arenaBounds(): EventBounds? = arenaPool.active()?.bounds

    private fun clearWeaponState(playerId: UUID) {
        reloadTasks.remove(playerId)?.cancel()
        shotCooldownUntil.remove(playerId)
    }

    private fun startReservedArcade(batch: ReservationBatch, online: List<Player>) {
        val rules = arcadeRules ?: settings().arcade.rules(batch.mode)
        val entries = batch.entries.filter { entry -> online.any { it.uniqueId.toString() == entry.playerId } }
        if (online.size < rules.minimumPlayers) {
            restoreReservationArrivals(batch, online)
            arenaPool.release(batch.matchId)
            network.releaseReservation(batch)
            return
        }
        try {
            arcade.start(batch.matchId, batch.mode, entries, online, rules)
            network.completeReservation(batch.matchId, online.map(Player::getUniqueId)).whenComplete { _, failure ->
                Tasks.scheduler.runSync {
                    if (!started || arcade.current?.matchId != batch.matchId) return@runSync
                    if (failure != null) {
                        plugin.logger.log(Level.SEVERE, "ArcEvents could not finalize arcade roster", failure)
                        arcade.cancel(MatchEndReason.SHUTDOWN)
                        network.releaseReservation(batch)
                    } else {
                        arcade.confirmRoster()
                        if (online.size < batch.entries.size) network.releaseUnarrived(batch, online.map(Player::getUniqueId))
                        network.announceStarted(batch.matchId)
                    }
                }
            }
        } catch (failure: Throwable) {
            plugin.logger.log(Level.SEVERE, "ArcEvents could not prepare arcade match", failure)
            if (arcade.current != null) arcade.cancel(MatchEndReason.SHUTDOWN) else arenaPool.release(batch.matchId)
            network.releaseReservation(batch)
        }
    }

    private fun debugStartArcade(players: List<Player>, arenaId: String?, mode: EventMode): DebugMutationResult {
        val config = settings()
        if (!config.debugMutationsAllowed) return DebugMutationResult.MUTATIONS_DISABLED
        if (config.nodeMode != NodeMode.HOST) return DebugMutationResult.WRONG_NODE
        if (matchState().first != null) return DebugMutationResult.BUSY
        val online = players.filter(Player::isOnline).distinctBy(Player::getUniqueId)
        val rules = config.arcade.rules(mode)
        if (online.size !in rules.minimumPlayers..rules.maximumPlayers) return DebugMutationResult.INSUFFICIENT_PLAYERS
        if (online.any { hasPendingRecovery(it.uniqueId) }) return DebugMutationResult.PRECONDITION_FAILED
        val id = UUID.randomUUID()
        val arena = arenaPool.reserve(id, if (mode == EventMode.DISASTERS) "disasters" else arenaId, mode)
            ?: return DebugMutationResult.ARENA_UNAVAILABLE
        return try {
            val entries = online.map { QueueEntry(it.uniqueId.toString(), it.name, config.serverId, mode.id,
                joinedAtMs = clock(), expiresAtMs = clock() + 60_000L) }
            arcade.start(id, mode, entries, online, rules)
            arcade.confirmRoster()
            DebugMutationResult.APPLIED
        } catch (failure: Throwable) {
            plugin.logger.log(Level.SEVERE, "ArcEvents local arcade bootstrap failed on ${arena.id}", failure)
            if (arcade.current != null) arcade.cancel(MatchEndReason.SHUTDOWN) else arenaPool.release(id)
            DebugMutationResult.INTERNAL_ERROR
        }
    }

    private fun startReservedRoster() {
        val batch = reservation ?: return
        reservation = null
        val online = arrivals.values.mapNotNull { entry -> plugin.server.getPlayer(UUID.fromString(entry.playerId)) }
            .filter(Player::isOnline).distinctBy(Player::getUniqueId)
        arrivals.clear()
        val currentSettings = settings()
        val matchTtt = activeTtt()
        if (batch.mode != EventMode.TTT) { startReservedArcade(batch, online); return }
        if (online.size < matchTtt.minimumPlayers) {
            restoreReservationArrivals(batch, online)
            arenaPool.release(batch.matchId)
            matchSettings = null
            network.releaseReservation(batch)
            debug.event("reservation_cancelled", "match" to batch.matchId, "arrived" to online.size)
            return
        }
        val entries = batch.entries.filter { entry -> online.any { it.uniqueId.toString() == entry.playerId } }
        var mutationAttempted = false
        try {
            runtime.clearTransientState()
            roundReport = null
            cleanupLoot()
            val created = runtime.create(
                batch.matchId,
                entries.map(QueueEntry::queuedPlayer),
                RoleAllocationSettings(
                    matchTtt.traitorPlayerRatio,
                    matchTtt.detectiveMinimumPlayers,
                    matchTtt.traitorCredits,
                    matchTtt.detectiveCredits,
                ),
                seed = batch.matchId.mostSignificantBits xor batch.matchId.leastSignificantBits,
                nowMs = clock(),
            )
            val prepared = runtime.prepare(created, matchTtt.preparationSeconds)
            escrow.commitThenMutate(
                batch.matchId,
                online,
                entries.associate { UUID.fromString(it.playerId) to it.originServer },
                clock(),
            ) {
                mutationAttempted = true
                applyEventState(online, prepared)
            }
            network.completeReservation(batch.matchId, online.map(Player::getUniqueId)).whenComplete { _, failure ->
                Tasks.scheduler.runSync {
                    val prepared = match
                    if (!started || prepared?.matchId != batch.matchId || prepared.phase != MatchPhase.PREPARING) return@runSync
                    if (failure != null) {
                        plugin.logger.log(Level.SEVERE, "ArcEvents could not finalize roster ${batch.matchId}", failure)
                        network.releaseReservation(batch)
                        cancel(MatchEndReason.SHUTDOWN)
                        return@runSync
                    }
                    if (online.size < batch.entries.size) network.releaseUnarrived(batch, online.map(Player::getUniqueId))
                    network.announceStarted(batch.matchId)
                    debug.event("match_preparing", "match" to batch.matchId, "players" to online.size)
                }
            }
        } catch (failure: Throwable) {
            plugin.logger.log(Level.SEVERE, "ArcEvents could not prepare match ${batch.matchId}", failure)
            network.releaseReservation(batch)
            if (match?.matchId == batch.matchId) {
                if (mutationAttempted) cancel(MatchEndReason.SHUTDOWN) else abortUnmutatedPreparation(online)
            } else {
                arenaPool.release(batch.matchId)
                matchSettings = null
            }
        }
    }

    private fun abortUnmutatedPreparation(players: List<Player>) {
        val current = match ?: return
        players.filter(Player::isOnline).forEach { player ->
            val safeToRelease = runCatching { recoverPlayer(player) }.onFailure { failure ->
                plugin.logger.log(Level.SEVERE, "ArcEvents could not discard an unmutated recovery snapshot for ${player.uniqueId}", failure)
            }.isSuccess
            if (safeToRelease && current.participant(player.uniqueId) != null) {
                runCatching { runtime.markRecoveryApplied(player.uniqueId) }.onFailure { failure ->
                    plugin.logger.log(Level.SEVERE, "ArcEvents could not mark unmutated state safe for ${player.uniqueId}", failure)
                }
            }
        }
        cancel(MatchEndReason.SHUTDOWN)
    }

    private fun restoreReservationArrivals(batch: ReservationBatch, players: Collection<Player>) {
        players.filter(Player::isOnline).forEach { player ->
            runCatching { recoverPlayer(player) }.onFailure { failure ->
                plugin.logger.log(
                    Level.SEVERE,
                    "ArcEvents could not restore cancelled arrival ${player.uniqueId} for ${batch.matchId}",
                    failure,
                )
            }
        }
    }

    private fun applyEventState(players: List<Player>, current: TttMatch) {
        val arena = requireNotNull(arenaPool.active()) { "Match ${current.matchId} has no arena lease" }
        players.forEach { player ->
            player.closeInventory()
            player.activePotionEffects.forEach { player.removePotionEffect(it.type) }
            player.gameMode = GameMode.ADVENTURE
            player.allowFlight = false
            player.isFlying = false
            player.velocity = Vector()
            player.fireTicks = 0
            player.fallDistance = 0f
            player.health = requireNotNull(player.getAttribute(Attribute.MAX_HEALTH)).value
            player.absorptionAmount = 0.0
            player.foodLevel = 20
            player.saturation = 20f
            player.level = 0
            player.exp = 0f
            items.givePreparationLoadout(player, current.matchId.toString())
            teleport(player, arena.playerSpawn)
            player.showTitle(Title.title(
                locale.render("match.preparing-title", player),
                locale.render("match.preparing-subtitle", player),
                Title.Times.times(Duration.ofMillis(250), Duration.ofSeconds(3), Duration.ofMillis(500)),
            ))
            player.sendEventMessage(locale.render("match.preparing-guide", player))
            if (settings().ui.particles) {
                player.world.spawnParticle(Particle.END_ROD, player.location.add(0.0, 1.0, 0.0), 18, 0.7, 0.8, 0.7, 0.015)
            }
            player.saveData()
        }
        spawnLoot(current)
        hud.open(current)
        hud.update(current, runtime.phaseSecondsRemaining(), activeTtt().preparationSeconds)
    }

    private fun beginCountdown() {
        val current = match ?: return
        if (current.phase != MatchPhase.PREPARING) return
        if (!preRoundRosterViable(current)) {
            cancel(MatchEndReason.INSUFFICIENT_PLAYERS)
            return
        }
        val countdown = runtime.beginCountdown(activeTtt().countdownSeconds)
        revealRoles(countdown)
        hud.update(countdown, runtime.phaseSecondsRemaining(), activeTtt().countdownSeconds)
        debug.event("match_countdown", "match" to current.matchId, "seconds" to runtime.phaseSecondsRemaining())
    }

    private fun revealRoles(current: TttMatch) {
        val traitorNames = current.participants.values.filter { it.role == TttRole.TRAITOR }.map(TttParticipant::playerName)
        val detectiveNames = current.participants.values.filter { it.role == TttRole.DETECTIVE }.map(TttParticipant::playerName)
        current.participants.values.forEach { participant ->
            val player = plugin.server.getPlayer(participant.playerId) ?: return@forEach
            val (titleKey, subtitleKey) = when (participant.role) {
                TttRole.INNOCENT -> "role.innocent-title" to "role.innocent-subtitle"
                TttRole.TRAITOR -> "role.traitor-title" to "role.traitor-subtitle"
                TttRole.DETECTIVE -> "role.detective-title" to "role.detective-subtitle"
            }
            val allies = traitorNames.filterNot { it == participant.playerName }.joinToString(", ").ifEmpty { "—" }
            items.revealRoleLoadout(player, participant.role, current.matchId.toString())
            player.showTitle(Title.title(
                locale.render(titleKey, player),
                locale.render(subtitleKey, player, mapOf("allies" to Component.text(allies))),
                Title.Times.times(Duration.ofMillis(250), Duration.ofSeconds(4), Duration.ofMillis(600)),
            ))
            if (settings().ui.sounds) {
                val sound = when (participant.role) {
                    TttRole.INNOCENT -> Sound.BLOCK_AMETHYST_BLOCK_CHIME
                    TttRole.TRAITOR -> Sound.ENTITY_WITHER_AMBIENT
                    TttRole.DETECTIVE -> Sound.BLOCK_BELL_RESONATE
                }
                player.playSound(player.location, sound, 0.65f, 1.0f)
            }
            if (settings().ui.particles) {
                player.world.spawnParticle(Particle.END_ROD, player.location.add(0.0, 1.0, 0.0), 24, 0.65, 0.9, 0.65, 0.02)
            }
        }
        if (detectiveNames.isNotEmpty()) {
            broadcast("match.detectives-announced", mapOf("players" to Component.text(detectiveNames.joinToString(", "))))
        }
    }

    private fun activateRound() {
        val current = match ?: return
        if (current.phase != MatchPhase.COUNTDOWN) return
        if (!preRoundRosterViable(current)) {
            cancel(MatchEndReason.INSUFFICIENT_PLAYERS)
            return
        }
        val active = runtime.activate()
        active.participants.values.mapNotNull { plugin.server.getPlayer(it.playerId) }.forEach { player ->
            player.sendEventMessage(locale.render("match.started", player))
            player.showTitle(Title.title(
                locale.render("match.started-title", player),
                locale.render("match.started-subtitle", player),
                Title.Times.times(Duration.ofMillis(100), Duration.ofSeconds(2), Duration.ofMillis(350)),
            ))
            if (settings().ui.sounds) player.playSound(player.location, Sound.ENTITY_ENDER_DRAGON_GROWL, 0.45f, 1.35f)
            if (settings().ui.particles) {
                player.world.spawnParticle(Particle.END_ROD, player.location.add(0.0, 1.0, 0.0), 32, 0.8, 1.0, 0.8, 0.035)
            }
        }
        hud.update(active, activeTtt().roundSeconds, activeTtt().roundSeconds)
        debug.event("match_active", "match" to current.matchId, "deadline" to active.deadlineMs)
    }

    private fun tick() {
        if (arcade.current != null) {
            runCatching(arcade::tick).onFailure { failure ->
                plugin.logger.log(Level.SEVERE, "ArcEvents arcade tick failed", failure)
                arcade.cancel(MatchEndReason.SHUTDOWN)
            }
            return
        }
        val current = match ?: return
        when (current.phase) {
            MatchPhase.PREPARING -> {
                val phaseTick = runtime.tickPhase()
                if (phaseTick.elapsed) {
                    beginCountdown()
                    return
                }
                hud.update(current, phaseTick.secondsRemaining, activeTtt().preparationSeconds)
            }
            MatchPhase.COUNTDOWN -> {
                val phaseTick = runtime.tickPhase()
                if (phaseTick.elapsed) {
                    activateRound()
                    return
                }
                hud.update(current, phaseTick.secondsRemaining, activeTtt().countdownSeconds)
                current.participants.values.mapNotNull { plugin.server.getPlayer(it.playerId) }.forEach { player ->
                    player.showTitle(Title.title(
                        locale.render("match.countdown-title", player, mapOf("seconds" to locale.text(phaseTick.secondsRemaining))),
                        locale.render("match.countdown-subtitle", player),
                        Title.Times.times(Duration.ZERO, Duration.ofMillis(900), Duration.ofMillis(100)),
                    ))
                    if (settings().ui.sounds) player.playSound(player.location, Sound.BLOCK_NOTE_BLOCK_HAT, 0.45f, 1.2f)
                }
            }
            MatchPhase.ACTIVE -> {
                val (changed, outcome) = runtime.tickActive()
                hud.update(changed, remainingSeconds(changed).toInt(), activeTtt().roundSeconds)
                if (outcome is MatchOutcome.Finished) resolve(changed)
            }
            else -> Unit
        }
    }

    private fun resolve(current: TttMatch) {
        if (current.phase != MatchPhase.RESOLVING) return
        runtime.replace(current)
        cancelMatchTasks(keepMainTick = true)
        runCatching(hud::close).onFailure { failure ->
            plugin.logger.log(Level.WARNING, "ArcEvents could not close HUD for ${current.matchId}", failure)
        }
        val title = if (current.winner == TttTeam.TRAITORS) "match.winner-traitors-title" else "match.winner-innocents-title"
        val subtitle = if (current.winner == TttTeam.TRAITORS) "match.winner-traitors-subtitle" else "match.winner-innocents-subtitle"
        roundReport = RoundReportView(
            matchId = current.matchId,
            winner = requireNotNull(current.winner),
            reason = requireNotNull(current.endReason),
            durationSeconds = ((clock() - (current.activeAtMs ?: current.createdAtMs)).coerceAtLeast(0L) / 1_000L),
            participants = current.participants.values.sortedByDescending(TttParticipant::kills).map { participant ->
                RoundParticipantView(
                    participant.playerId,
                    participant.playerName,
                    participant.role,
                    participant.kills,
                    participant.deaths,
                    participant.damageDealt,
                    participant.friendlyDamage,
                )
            },
            combat = runtime.combatRecords(),
        )
        current.participants.values.forEach { participant ->
            plugin.server.getPlayer(participant.playerId)?.let { player ->
                runCatching {
                    player.showTitle(Title.title(
                        locale.render(title, player),
                        locale.render(subtitle, player),
                        Title.Times.times(Duration.ofMillis(250), Duration.ofSeconds(4), Duration.ofMillis(750)),
                    ))
                    player.inventory.setItem(4, items.roundReport(player, current.matchId.toString()))
                    player.sendEventMessage(locale.render("report.ready", player))
                }.onFailure { failure ->
                    plugin.logger.log(Level.WARNING, "ArcEvents could not show the round report to ${participant.playerId}", failure)
                }
            }
            runCatching {
                network.recordStats(participant.playerId) { it.record(current.matchId, participant, current.winner) }
            }.onFailure { failure ->
                plugin.logger.log(Level.WARNING, "ArcEvents could not submit round statistics for ${participant.playerId}", failure)
            }
        }
        runCatching { Tasks.scheduler.runLater(activeTtt().postRoundSeconds * 20L) { beginRestoration() } }
            .onSuccess(sessionTasks::add)
            .onFailure { failure ->
                plugin.logger.log(Level.SEVERE, "ArcEvents could not schedule restoration for ${current.matchId}", failure)
                beginRestoration()
            }
        runCatching { network.announceEnded(current.matchId, current.winner, requireNotNull(current.endReason)) }
            .onFailure { failure -> plugin.logger.log(Level.WARNING, "ArcEvents could not announce match end ${current.matchId}", failure) }
        debug.event("match_resolving", "match" to current.matchId, "winner" to current.winner, "reason" to current.endReason)
    }

    private fun cancel(reason: MatchEndReason) {
        val current = match ?: return
        if (current.phase in setOf(MatchPhase.COMPLETED, MatchPhase.RESTORING)) return
        val cancelled = runCatching { runtime.cancel(reason) }.getOrElse {
            current.copy(revision = current.revision + 1, phase = MatchPhase.CANCELLED, endReason = reason)
        }
        runtime.replace(cancelled)
        cancelMatchTasks(keepMainTick = true)
        runCatching(hud::close).onFailure { failure ->
            plugin.logger.log(Level.WARNING, "ArcEvents could not close HUD for ${cancelled.matchId}", failure)
        }
        cancelled.participants.values.mapNotNull { plugin.server.getPlayer(it.playerId) }.forEach { player ->
            runCatching {
                player.showTitle(Title.title(
                    locale.render("match.cancelled-title", player),
                    locale.render("match.cancelled-subtitle", player),
                    Title.Times.times(Duration.ofMillis(150), Duration.ofSeconds(2), Duration.ofMillis(400)),
                ))
            }.onFailure { failure ->
                plugin.logger.log(Level.WARNING, "ArcEvents could not show cancellation to ${player.uniqueId}", failure)
            }
        }
        runCatching { network.announceEnded(cancelled.matchId, null, reason) }.onFailure { failure ->
            plugin.logger.log(Level.WARNING, "ArcEvents could not announce cancellation ${cancelled.matchId}", failure)
        }
        beginRestoration()
    }

    private fun beginRestoration() {
        val current = match ?: return
        if (current.phase !in setOf(MatchPhase.RESOLVING, MatchPhase.CANCELLED)) return
        runtime.beginRestoring()
        bodyRegistry.clear()
        cleanupProjectiles()
        cleanupLoot()
        radarTasks.values.forEach { task -> runCatching(task::cancel) }
        radarTasks.clear()
        val restoredOnline = mutableSetOf<UUID>()
        current.participants.values.forEach { participant ->
            val player = plugin.server.getPlayer(participant.playerId) ?: return@forEach
            runCatching { recoverPlayer(player) }
                .onSuccess { recovery ->
                    if (recovery != null) {
                        restoredOnline += player.uniqueId
                        completeRecovery(player, recovery)
                    }
                }
                .onFailure {
                    plugin.logger.log(Level.SEVERE, "ArcEvents could not restore ${player.uniqueId}", it)
                    player.sendEventMessage(locale.render("match.restore-pending", player))
                }
        }
        val currentPending = pendingPlayers(current.matchId)?.size ?: -1
        debug.event(
            "match_restoration",
            "match" to current.matchId,
            "restored" to restoredOnline.size,
            "pending" to currentPending,
            "pending_total" to totalPendingCount(),
        )
        if (onlineRecoveryBlocked(requireNotNull(match))) {
            sessionTasks += Tasks.scheduler.runLater(20L) { finishCleanup() }
        } else {
            finishCleanup()
        }
    }

    private fun finishCleanup() {
        val current = match ?: return
        if (onlineRecoveryBlocked(current)) {
            cleanupRetryTask?.cancel()
            cleanupRetryTask = Tasks.scheduler.runLater(20L) { finishCleanup() }
            return
        }
        cleanupRetryTask = null
        debug.event("match_released", "match" to current.matchId, "phase" to current.phase, "recovery" to totalPendingCount())
        arenaPool.release(current.matchId)
        recoveryReadFailures.remove(current.matchId)
        runtime.release()
        matchSettings = null
    }

    private fun markRecovered(playerId: UUID, recovery: PlayerRecovery) {
        plugin.server.getPlayer(playerId)?.let { arcade.markRecovered(it, recovery) }
        val current = match ?: return
        if (current.matchId != recovery.matchId || current.participant(playerId) == null) return
        runtime.markRecoveryApplied(playerId)
    }

    private fun completeRecovery(player: Player, recovery: PlayerRecovery, messageKey: String = "match.restored") {
        runCatching { markRecovered(player.uniqueId, recovery) }.onFailure { failure ->
            plugin.logger.log(Level.SEVERE, "ArcEvents could not record recovery for ${player.uniqueId}", failure)
        }
        runCatching { player.sendEventMessage(locale.render(messageKey, player)) }
        runCatching { network.returnRecoveredPlayer(player, recovery) }.onFailure { failure ->
            plugin.logger.log(Level.SEVERE, "ArcEvents could not prepare return for ${player.uniqueId}", failure)
            runCatching { network.handleJoin(player) }
        }
    }

    private fun onlineRecoveryBlocked(current: TttMatch): Boolean {
        val pending = pendingPlayers(current.matchId) ?: return true
        val online = current.participants.keys.filterTo(mutableSetOf()) { playerId ->
            plugin.server.getPlayer(playerId)?.isOnline == true
        }
        val recovered = current.participants.values.filter { it.status == ParticipantStatus.RESTORED }
            .mapTo(mutableSetOf(), TttParticipant::playerId)
        return EventRecoveryGate.blocked(online, pending, recovered)
    }

    private fun pendingPlayers(matchId: UUID): Set<UUID>? = runCatching { escrow.pendingPlayers(matchId) }
        .onSuccess { recoveryReadFailures.remove(matchId) }
        .onFailure { failure ->
            if (recoveryReadFailures.add(matchId)) {
                plugin.logger.log(Level.SEVERE, "ArcEvents could not read recovery state for $matchId", failure)
            }
        }
        .getOrNull()

    private fun hasPendingRecovery(playerId: UUID): Boolean = allPendingPlayers()?.contains(playerId) ?: true

    private fun totalPendingCount(): Int = allPendingPlayers()?.size ?: -1

    private fun allPendingPlayers(): Set<UUID>? = runCatching { escrow.pendingPlayers() }
        .onSuccess { recoveryCatalogFailureLogged = false }
        .onFailure { failure ->
            if (!recoveryCatalogFailureLogged) {
                recoveryCatalogFailureLogged = true
                plugin.logger.log(Level.SEVERE, "ArcEvents could not read the recovery catalog", failure)
            }
        }
        .getOrNull()

    override fun readBodyId(entity: Entity): UUID? = bodyRegistry.readBodyId(entity)

    private fun cleanupProjectiles() {
        runCatching(smokeGrenades::clear).onFailure { failure ->
            plugin.logger.log(Level.WARNING, "ArcEvents could not clear smoke grenades", failure)
        }
        projectiles.forEach { entityId ->
            runCatching { plugin.server.getEntity(entityId)?.remove() }.onFailure { failure ->
                plugin.logger.log(Level.WARNING, "ArcEvents could not remove projectile $entityId", failure)
            }
        }
        projectiles.clear()
    }

    private fun spawnLoot(current: TttMatch) {
        if (!settings().weapons.enabled) return
        val arena = arenaPool.active() ?: return
        cleanupLoot()
        lootSpawner.spawn(current, arena)
    }

    private fun cleanupLoot() {
        runCatching(lootScene::clear).onFailure { failure ->
            plugin.logger.log(Level.WARNING, "ArcEvents could not clear loot presentation", failure)
        }
        reloadTasks.values.forEach { task -> runCatching(task::cancel) }
        reloadTasks.clear()
        shotCooldownUntil.clear()
    }

    private fun fireRay(
        shooter: Player,
        firearmId: String,
        origin: Location,
        direction: Vector,
        range: Double,
        baseDamage: Double,
    ) {
        val matchId = activeMatchId() ?: return
        val result = shooter.world.rayTrace(
            origin,
            direction,
            range,
            FluidCollisionMode.NEVER,
            true,
            0.12,
        ) { entity ->
            entity is Player && entity.uniqueId != shooter.uniqueId &&
                isAlive(entity.uniqueId) && activeMatchId() == matchId
        }
        val endpoint = result?.hitPosition ?: origin.toVector().add(direction.clone().multiply(range))
        if (settings().ui.particles) {
            val distance = origin.toVector().distance(endpoint)
            var travelled = 1.0
            while (travelled < distance && travelled < 90.0) {
                val point = origin.clone().add(direction.clone().multiply(travelled))
                shooter.world.spawnParticle(Particle.CRIT, point, 1, 0.0, 0.0, 0.0, 0.0)
                travelled += 1.5
            }
        }
        val target = result?.hitEntity as? Player ?: return
        val headshot = result.hitPosition.y >= target.eyeLocation.y - 0.38
        pendingHit = PendingHitContext(shooter.uniqueId, target.uniqueId, "firearm.$firearmId", headshot)
        try {
            target.damage(baseDamage * if (headshot) settings().gameplay.headshotMultiplier else 1.0, shooter)
        } finally {
            pendingHit = null
        }
        if (headshot && settings().ui.sounds) shooter.playSound(shooter.location, Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.45f, 1.8f)
    }

    private fun weaponKey(attacker: Player?): String {
        if (attacker == null) return "environment"
        firearms.state(attacker.inventory.itemInMainHand)?.let { return "firearm.${it.id.name.lowercase()}" }
        return when (items.kind(attacker.inventory.itemInMainHand)) {
            EventItemKind.TRAITOR_BLADE -> "traitor-blade"
            else -> "melee"
        }
    }

    private fun activateRadar(player: Player, current: TttMatch): Boolean {
        val actor = current.participant(player.uniqueId) ?: return false
        val target = radarTarget(player, current, actor)
        if (target == null) {
            player.sendEventMessage(locale.render("shop.radar-empty", player))
            return false
        }
        consumeMainHand(player)
        player.compassTarget = target.location
        player.sendEventMessage(locale.render("shop.radar-active", player, mapOf("target" to Component.text(target.name))))
        radarTasks.remove(player.uniqueId)?.cancel()
        radarTasks[player.uniqueId] = Tasks.scheduler.runTimer(20L, 20L) {
            val active = match
            val participant = active?.participant(player.uniqueId)
            if (!player.isOnline || active?.phase != MatchPhase.ACTIVE || participant?.status != ParticipantStatus.ALIVE) {
                radarTasks.remove(player.uniqueId)?.cancel()
                return@runTimer
            }
            radarTarget(player, active, participant)?.let { player.compassTarget = it.location }
        }
        sessionTasks += Tasks.scheduler.runLater(settings().gameplay.radarDurationSeconds * 20L) {
            radarTasks.remove(player.uniqueId)?.cancel()
        }
        return true
    }

    private fun radarTarget(player: Player, current: TttMatch, actor: TttParticipant): Player? =
        current.alive().filter { it.role.team != actor.role.team }
            .mapNotNull { plugin.server.getPlayer(it.playerId) }
            .filter { it.world == player.world }
            .minByOrNull { it.location.distanceSquared(player.location) }

    private fun activateSmoke(player: Player): Boolean {
        val current = match ?: return false
        if (smokeGrenades.launch(player, current.matchId) == null) return false
        consumeMainHand(player)
        player.sendEventMessage(locale.render("shop.smoke-used", player))
        return true
    }

    private fun activateMedkit(player: Player): Boolean {
        val maximum = requireNotNull(player.getAttribute(Attribute.MAX_HEALTH)).value
        if (player.health >= maximum) {
            player.sendEventMessage(locale.render("shop.medkit-full", player))
            return false
        }
        consumeMainHand(player)
        player.health = (player.health + settings().gameplay.medkitHealing).coerceAtMost(maximum)
        if (settings().ui.sounds) player.playSound(player.location, Sound.ENTITY_PLAYER_LEVELUP, 0.55f, 1.4f)
        return true
    }

    private fun consumeMainHand(player: Player) {
        val item = player.inventory.itemInMainHand
        if (item.amount <= 1) player.inventory.setItemInMainHand(ItemStack.empty()) else item.amount -= 1
        player.updateInventory()
    }

    private fun offers(role: TttRole): List<ShopOffer> = when (role) {
        TttRole.TRAITOR -> items.traitorOffers
        TttRole.DETECTIVE -> items.detectiveOffers
        TttRole.INNOCENT -> emptyList()
    }

    private fun canFullyAdd(player: Player, item: ItemStack): Boolean {
        var remaining = item.amount
        player.inventory.storageContents.forEach { existing ->
            if (remaining <= 0) return true
            remaining -= when {
                existing == null || existing.isEmpty -> item.maxStackSize
                existing.isSimilar(item) -> (existing.maxStackSize - existing.amount).coerceAtLeast(0)
                else -> 0
            }
        }
        return remaining <= 0
    }

    private fun teleport(player: Player, target: EventLocation) {
        val world = requireNotNull(plugin.server.getWorld(target.world))
        val destination = Location(world, target.x, target.y, target.z, target.yaw, target.pitch)
        require(authorizedTeleport(player, destination))
    }

    private fun recoverPlayer(player: Player): PlayerRecovery? = escrow.recover(player) { destination ->
        authorizedTeleport(player, destination)
    }

    private fun authorizedTeleport(player: Player, destination: Location): Boolean =
        teleportAuthorizer.authorize(player.uniqueId, destination) { player.teleport(destination) }

    private fun roleName(role: TttRole, player: Player?): Component = locale.render(
        when (role) {
            TttRole.INNOCENT -> "role.innocent-name"
            TttRole.TRAITOR -> "role.traitor-name"
            TttRole.DETECTIVE -> "role.detective-name"
        },
        player,
    )

    private fun broadcast(path: String, values: Map<String, Component>) {
        match?.participants?.values
            ?.filter { it.status != ParticipantStatus.RESTORED }
            ?.mapNotNull { plugin.server.getPlayer(it.playerId) }
            ?.forEach { player ->
            player.sendEventMessage(locale.render(path, player, values))
        }
    }

    private fun engine(): TttMatchEngine {
        val ttt = activeTtt()
        return TttMatchEngine(ttt.minimumPlayers, ttt.maximumPlayers, ttt.roundSeconds * 1_000L)
    }

    private fun activeTtt(): TttSettings = matchSettings ?: settings().ttt

    private fun remainingSeconds(current: TttMatch?): Long {
        if (current?.phase != MatchPhase.ACTIVE) return 0
        return max(0L, (requireNotNull(current.deadlineMs) - clock() + 999L) / 1_000L)
    }

    private fun phaseRemainingSeconds(current: TttMatch?): Int = when (current?.phase) {
        MatchPhase.PREPARING, MatchPhase.COUNTDOWN -> runtime.phaseSecondsRemaining()
        MatchPhase.ACTIVE -> remainingSeconds(current).toInt()
        else -> 0
    }

    private fun visibleAliveCount(current: TttMatch): Int = current.participants.values.count {
        it.status in setOf(ParticipantStatus.RESERVED, ParticipantStatus.ALIVE)
    }

    private fun formatTime(seconds: Long): String = "%d:%02d".format(seconds / 60, seconds % 60)

    private fun preRoundRosterViable(current: TttMatch): Boolean {
        val playable = current.participants.values.filter { participant ->
            participant.status in setOf(ParticipantStatus.RESERVED, ParticipantStatus.ALIVE) &&
                plugin.server.getPlayer(participant.playerId)?.isOnline == true
        }
        return playable.size >= activeTtt().minimumPlayers &&
            playable.any { it.role == TttRole.TRAITOR } &&
            playable.any { it.role.team == TttTeam.INNOCENTS }
    }

    private fun cancelMatchTasks(keepMainTick: Boolean) {
        mapSpawnReturns.keys.toList().forEach { playerId -> cancelMapSpawnReturn(playerId, notify = false) }
        sessionTasks.forEach { task -> runCatching(task::cancel) }
        sessionTasks.clear()
        if (!keepMainTick) {
            mainTickTask?.let { task -> runCatching(task::cancel) }
            mainTickTask = null
        }
    }

    override fun close() {
        if (!started) return
        started = false
        arcade.cancel(MatchEndReason.SHUTDOWN)
        reservation?.let { pending ->
            val arrivedPlayers = arrivals.keys.mapNotNull(plugin.server::getPlayer)
            restoreReservationArrivals(pending, arrivedPlayers)
            runCatching { network.releaseReservation(pending).get(2, TimeUnit.SECONDS) }
                .onFailure { plugin.logger.log(Level.SEVERE, "Could not preserve reservation returns for ${pending.matchId}", it) }
        }
        arenaPool.clear()
        reservation = null
        matchSettings = null
        arrivals.clear()
        when (match?.phase) {
            MatchPhase.PREPARING, MatchPhase.COUNTDOWN, MatchPhase.ACTIVE -> cancel(MatchEndReason.SHUTDOWN)
            MatchPhase.RESOLVING, MatchPhase.CANCELLED -> beginRestoration()
            else -> Unit
        }
        cancelMatchTasks(keepMainTick = false)
        runCatching(hud::close).onFailure { plugin.logger.log(Level.WARNING, "ArcEvents HUD close failed", it) }
        runCatching(bodyRegistry::close).onFailure { plugin.logger.log(Level.WARNING, "ArcEvents body close failed", it) }
        cleanupProjectiles()
        cleanupLoot()
        runCatching(smokeGrenades::close).onFailure { plugin.logger.log(Level.WARNING, "ArcEvents smoke close failed", it) }
        runCatching(lootScene::close).onFailure { plugin.logger.log(Level.WARNING, "ArcEvents loot close failed", it) }
        radarTasks.values.forEach { task -> runCatching(task::cancel) }
        radarTasks.clear()
        cleanupRetryTask?.cancel()
        cleanupRetryTask = null
    }

    private fun Location.eventLocation(): EventLocation = EventLocation(world.name, x, y, z, yaw, pitch)

    companion object {
        private val LIVE_PHASES = setOf(MatchPhase.PREPARING, MatchPhase.COUNTDOWN, MatchPhase.ACTIVE)
        private val EVACUATION_PHASES = LIVE_PHASES + setOf(
            MatchPhase.RESOLVING,
            MatchPhase.CANCELLED,
            MatchPhase.RESTORING,
        )
        private val DEBUG_SPECIAL_ITEMS = setOf(
            EventItemKind.TRAITOR_BLADE,
            EventItemKind.TRAITOR_RADAR,
            EventItemKind.TRAITOR_SMOKE,
            EventItemKind.DETECTIVE_SCANNER,
            EventItemKind.DETECTIVE_MEDKIT,
            EventItemKind.DETECTIVE_ARMOR,
        )
        private val CHAT_PHASES = setOf(
            MatchPhase.PREPARING,
            MatchPhase.COUNTDOWN,
            MatchPhase.ACTIVE,
            MatchPhase.RESOLVING,
            MatchPhase.CANCELLED,
            MatchPhase.RESTORING,
        )
    }
}

internal fun pickupReserveRounds(
    magazineSize: Int,
    magazines: Int = 3,
    minimum: Int = 12,
    maximum: Int = 48,
): Int = (magazineSize * magazines).coerceIn(minimum, maximum)

internal fun lootAccessible(phase: MatchPhase?, status: ParticipantStatus?): Boolean =
    phase in setOf(MatchPhase.PREPARING, MatchPhase.COUNTDOWN, MatchPhase.ACTIVE) &&
        status in setOf(ParticipantStatus.RESERVED, ParticipantStatus.ALIVE)
