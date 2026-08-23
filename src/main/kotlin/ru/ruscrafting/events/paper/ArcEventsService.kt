package ru.ruscrafting.events.paper

import net.kyori.adventure.bossbar.BossBar
import net.kyori.adventure.text.Component
import net.kyori.adventure.title.Title
import org.bukkit.GameMode
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.Particle
import org.bukkit.Sound
import org.bukkit.attribute.Attribute
import org.bukkit.entity.ArmorStand
import org.bukkit.entity.Player
import org.bukkit.entity.Projectile
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.SkullMeta
import org.bukkit.persistence.PersistentDataType
import org.bukkit.plugin.Plugin
import org.bukkit.potion.PotionEffect
import org.bukkit.potion.PotionEffectType
import org.bukkit.util.Vector
import ru.arc.core.ScheduledTask
import ru.arc.core.Tasks
import ru.ruscrafting.events.config.ArcEventsConfig
import ru.ruscrafting.events.config.ArcEventsLocale
import ru.ruscrafting.events.config.EventBounds
import ru.ruscrafting.events.config.EventLocation
import ru.ruscrafting.events.config.NodeMode
import ru.ruscrafting.events.domain.MatchEndReason
import ru.ruscrafting.events.domain.MatchOutcome
import ru.ruscrafting.events.domain.MatchPhase
import ru.ruscrafting.events.domain.ParticipantStatus
import ru.ruscrafting.events.domain.PlayerEventStats
import ru.ruscrafting.events.domain.RoleAllocationSettings
import ru.ruscrafting.events.domain.RecentAttackLedger
import ru.ruscrafting.events.domain.TttMatch
import ru.ruscrafting.events.domain.TttMatchEngine
import ru.ruscrafting.events.domain.TttParticipant
import ru.ruscrafting.events.domain.TttRole
import ru.ruscrafting.events.domain.TttTeam
import ru.ruscrafting.events.domain.team
import ru.ruscrafting.events.network.QueueEntry
import ru.ruscrafting.events.network.ReservationBatch
import java.time.Duration
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.logging.Level
import kotlin.math.ceil
import kotlin.math.max

data class ServiceSnapshot(
    val serverId: String,
    val nodeMode: NodeMode,
    val hostServer: String,
    val redisConnected: Boolean,
    val hostAvailable: Boolean,
    val arenaReady: Boolean,
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

data class BodyRecord(
    val bodyId: UUID,
    val matchId: UUID,
    val victimId: UUID,
    val victimName: String,
    val role: TttRole,
    val killerId: UUID?,
    val killedAtMs: Long,
    val location: Location,
    val entityId: UUID,
    var discovered: Boolean = false,
)

enum class AdminStopResult { MATCH, RESERVATION, NO_MATCH }

class ArcEventsService(
    private val plugin: Plugin,
    private val settings: () -> ArcEventsConfig,
    private val locale: ArcEventsLocale,
    private val escrow: PlayerStateEscrow,
    private val items: TttItems,
    private val network: EventNetworkCoordinator,
    private val debug: ArcEventsDebug,
    private val redisConnected: () -> Boolean,
    private val arenaInspector: ArenaRuntimeInspector,
    private val clock: () -> Long = System::currentTimeMillis,
) : AutoCloseable {
    @Volatile
    private var match: TttMatch? = null
    private var reservation: ReservationBatch? = null
    private val arrivals = linkedMapOf<UUID, QueueEntry>()
    private val tasks = mutableListOf<ScheduledTask>()
    private val radarTasks = mutableMapOf<UUID, ScheduledTask>()
    private val teleportAuthorizer = InternalTeleportAuthorizer()
    private val recentAttacks = RecentAttackLedger(clock = clock)
    private val projectiles = mutableSetOf<UUID>()
    private val bodies = linkedMapOf<UUID, BodyRecord>()
    private val bodyKey = NamespacedKey(plugin, "body_id")
    private val projectileMatchKey = NamespacedKey(plugin, "projectile_match")
    private var bossBar: BossBar? = null
    private var countdownRemaining = 0
    private var cleanupRetryTask: ScheduledTask? = null
    @Volatile
    private var started = false

    fun start() {
        check(!started)
        started = true
        plugin.server.onlinePlayers.forEach(::handleJoin)
        tasks += Tasks.scheduler.runTimer(20L, 20L) { tick() }
    }

    fun snapshot(): ServiceSnapshot {
        val current = settings()
        val active = match
        val pendingReservation = reservation
        return ServiceSnapshot(
            serverId = current.serverId,
            nodeMode = current.nodeMode,
            hostServer = current.hostServer,
            redisConnected = redisConnected(),
            hostAvailable = network.hostAvailable(),
            arenaReady = arenaReady(),
            queueSize = network.queueSize,
            matchId = active?.matchId ?: pendingReservation?.matchId,
            phase = active?.phase ?: pendingReservation?.let { MatchPhase.RESERVED },
            participants = active?.participants?.size ?: pendingReservation?.entries?.size ?: 0,
            alive = active?.alive()?.size ?: 0,
            traitors = active?.alive()?.count { it.role == TttRole.TRAITOR } ?: 0,
            detectives = active?.alive()?.count { it.role == TttRole.DETECTIVE } ?: 0,
            recoveryPending = escrow.pendingCount(),
            secondsRemaining = remainingSeconds(active),
        )
    }

    fun matchState(): Pair<UUID?, MatchPhase?> = match?.let { it.matchId to it.phase }
        ?: reservation?.let { it.matchId to MatchPhase.RESERVED }
        ?: (null to null)
    fun currentMatch(): TttMatch? = match
    fun participant(playerId: UUID): TttParticipant? = match?.participant(playerId)
    fun stats(playerId: UUID): PlayerEventStats = network.stats(playerId)
    fun bodyId(entityId: UUID): UUID? = bodies.values.firstOrNull { it.entityId == entityId }?.bodyId
    fun isParticipant(playerId: UUID): Boolean = match?.participant(playerId) != null
    fun isAlive(playerId: UUID): Boolean = participant(playerId)?.status == ParticipantStatus.ALIVE
    fun phase(): MatchPhase? = match?.phase
    fun activeMatchId(): String? = match?.matchId?.toString()
    fun arenaReady(): Boolean = settings().let { arenaInspector.ready(it.arena, it.ttt.maximumPlayers) }

    fun onReservation(batch: ReservationBatch): Boolean {
        if (!started || match != null || reservation != null) {
            return false
        }
        reservation = batch
        arrivals.clear()
        debug.event("reservation_created", "match" to batch.matchId, "players" to batch.entries.size)
        tasks += Tasks.scheduler.runLater(settings().network.reservationSeconds * 20L) {
            if (reservation?.matchId == batch.matchId) startReservedRoster()
        }
        return true
    }

    fun onArrival(entry: QueueEntry) {
        val batch = reservation ?: return
        if (entry.matchId != batch.matchId.toString()) return
        val playerId = UUID.fromString(entry.playerId)
        if (batch.entries.none { it.playerId == entry.playerId }) return
        arrivals[playerId] = entry
        debug.event("reservation_arrival", "match" to batch.matchId, "player" to playerId, "arrived" to arrivals.size)
        if (arrivals.size == batch.entries.size) startReservedRoster()
    }

    fun joinQueue(player: Player) {
        if (escrow.pendingFor(player.uniqueId)) {
            player.sendMessage(locale.render("match.restore-pending", player))
            return
        }
        network.join(player)
    }

    fun leaveQueue(player: Player) = network.leave(player)

    fun handleJoin(player: Player) {
        Tasks.scheduler.runLater(1L) {
            if (!started || !player.isOnline) return@runLater
            val pending = escrow.pendingFor(player.uniqueId)
            if (pending) {
                val recovery = runCatching { recoverPlayer(player) }.getOrElse {
                    plugin.logger.log(Level.SEVERE, "ArcEvents could not restore ${player.uniqueId} on join", it)
                    player.sendMessage(locale.render("match.restore-pending", player))
                    return@runLater
                }
                if (recovery != null) {
                    player.sendMessage(locale.render("match.restored", player))
                    network.returnPlayer(player, recovery.returnServer)
                    return@runLater
                }
            }
            network.handleJoin(player)
        }
    }

    fun handleQuit(player: Player) {
        val current = match ?: return
        if (current.participant(player.uniqueId) == null || current.phase !in LIVE_PHASES) return
        val engine = engine()
        val (changed, outcome) = engine.disconnect(current, player.uniqueId)
        match = changed
        debug.event("participant_disconnected", "match" to current.matchId, "player" to player.uniqueId, "phase" to current.phase)
        if (outcome is MatchOutcome.Finished) resolve(changed)
        else if (changed.phase != MatchPhase.ACTIVE && onlineRoster(changed).size < settings().ttt.minimumPlayers) {
            cancel(MatchEndReason.INSUFFICIENT_PLAYERS)
        }
    }

    fun startFromQueue(): CompletableFuture<ReservationStartResult> = network.reserveNow()

    fun stopByAdmin(): AdminStopResult {
        reservation?.let { pending ->
            reservation = null
            arrivals.clear()
            network.releaseReservation(pending.matchId)
            return AdminStopResult.RESERVATION
        }
        if (match?.phase !in LIVE_PHASES) return AdminStopResult.NO_MATCH
        cancel(MatchEndReason.ADMIN)
        return AdminStopResult.MATCH
    }

    fun retryRecovery(): Int {
        var available = 0
        plugin.server.onlinePlayers.forEach { player ->
            if (!escrow.pendingFor(player.uniqueId)) return@forEach
            available++
            runCatching { recoverPlayer(player) }
                .onSuccess { recovery ->
                    if (recovery != null) {
                        player.sendMessage(locale.render("match.restored", player))
                        network.returnPlayer(player, recovery.returnServer)
                    }
                }
                .onFailure { plugin.logger.log(Level.SEVERE, "ArcEvents recovery retry failed for ${player.uniqueId}", it) }
        }
        return available
    }

    fun escrowPending(playerId: UUID): Boolean = escrow.pendingFor(playerId)

    fun recordAttack(victimId: UUID, attackerId: UUID?) {
        val current = match ?: return
        if (current.phase != MatchPhase.ACTIVE || attackerId == null) return
        if (current.participant(victimId)?.status != ParticipantStatus.ALIVE ||
            current.participant(attackerId)?.status != ParticipantStatus.ALIVE
        ) return
        recentAttacks.record(current.matchId, victimId, attackerId)
    }

    fun shouldCancelDamage(victimId: UUID, attackerId: UUID?, projectile: Boolean = false, projectileMatchId: UUID? = null): Boolean {
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

    fun eliminate(player: Player, killerId: UUID? = null) {
        val current = match ?: return
        if (current.phase != MatchPhase.ACTIVE || current.participant(player.uniqueId)?.status != ParticipantStatus.ALIVE) return
        val effectiveKiller = killerId ?: recentAttacks.consume(current.matchId, player.uniqueId)
        recentAttacks.forget(player.uniqueId)
        val (changed, outcome) = engine().eliminate(current, player.uniqueId, effectiveKiller)
        match = rewardKiller(changed, player.uniqueId, effectiveKiller)
        spawnBody(player, current.participant(player.uniqueId)!!, effectiveKiller)
        player.gameMode = GameMode.SPECTATOR
        player.inventory.clear()
        player.showTitle(Title.title(
            locale.render("match.eliminated-title", player),
            locale.render("match.eliminated-subtitle", player),
            Title.Times.times(Duration.ofMillis(150), Duration.ofSeconds(3), Duration.ofMillis(400)),
        ))
        player.sendMessage(locale.render("match.spectator", player))
        broadcast("match.eliminated", mapOf("player" to Component.text(player.name)))
        debug.event("player_eliminated", "match" to current.matchId, "victim" to player.uniqueId, "killer" to effectiveKiller)
        if (outcome is MatchOutcome.Finished) resolve(requireNotNull(match))
    }

    fun registerProjectile(projectile: Projectile): Boolean {
        val shooter = projectile.shooter as? Player ?: return true
        val current = match ?: return false
        val participant = current.participant(shooter.uniqueId) ?: return true
        if (current.phase != MatchPhase.ACTIVE || participant.status != ParticipantStatus.ALIVE) return false
        projectile.persistentDataContainer.set(projectileMatchKey, PersistentDataType.STRING, current.matchId.toString())
        projectiles += projectile.uniqueId
        return true
    }

    fun projectileMatchId(projectile: Projectile): UUID? = projectile.persistentDataContainer
        .get(projectileMatchKey, PersistentDataType.STRING)
        ?.let { runCatching { UUID.fromString(it) }.getOrNull() }

    fun belongsToCurrentMatch(playerId: UUID, item: ItemStack?): Boolean {
        val current = match ?: return false
        return current.participant(playerId) != null && items.belongsTo(item, current.matchId.toString())
    }

    fun handleProjectileHit(projectile: Projectile) {
        if (projectileMatchId(projectile) == null) return
        projectiles.remove(projectile.uniqueId)
        Tasks.scheduler.runLater(1L) { if (projectile.isValid) projectile.remove() }
    }

    fun handlesMatchChat(playerId: UUID): Boolean {
        val current = match ?: return false
        return current.phase in CHAT_PHASES && current.participant(playerId) != null
    }

    fun sendMatchChat(player: Player, message: Component) {
        val current = match ?: return
        if (current.phase !in CHAT_PHASES) return
        val sender = current.participant(player.uniqueId) ?: return
        val spectator = sender.status == ParticipantStatus.DEAD
        val recipients = current.participants.values.filter { participant ->
            if (spectator) participant.status == ParticipantStatus.DEAD
            else participant.status in setOf(ParticipantStatus.RESERVED, ParticipantStatus.ALIVE)
        }
        val key = if (spectator) "chat.spectator-message" else "chat.match-message"
        recipients.mapNotNull { plugin.server.getPlayer(it.playerId) }.forEach { recipient ->
            recipient.sendMessage(locale.render(key, recipient, mapOf(
                "player" to Component.text(player.name),
                "message" to message,
            )))
        }
    }

    fun inspectBody(player: Player, bodyId: UUID) {
        val body = bodies[bodyId] ?: return
        val current = match ?: return
        if (current.matchId != body.matchId || current.phase != MatchPhase.ACTIVE || !isAlive(player.uniqueId)) return
        if (!body.discovered) {
            body.discovered = true
            plugin.server.getEntity(body.entityId)?.customName(locale.render("body.identified", player, mapOf(
                "player" to Component.text(body.victimName),
                "role" to roleName(body.role, player),
            )))
            broadcast("body.discovered", mapOf(
                "player" to Component.text(body.victimName),
                "role" to roleName(body.role, player),
            ))
        } else {
            player.sendMessage(locale.render("body.already", player, mapOf(
                "player" to Component.text(body.victimName),
                "role" to roleName(body.role, player),
            )))
        }
        val inspector = current.participant(player.uniqueId) ?: return
        if (inspector.role == TttRole.DETECTIVE &&
            items.kind(player.inventory.itemInMainHand) == EventItemKind.DETECTIVE_SCANNER &&
            belongsToCurrentMatch(player.uniqueId, player.inventory.itemInMainHand)
        ) {
            val killer = body.killerId?.let(plugin.server::getPlayer)?.takeIf { isAlive(it.uniqueId) }
            if (killer == null) {
                player.sendMessage(locale.render("body.dna-lost", player))
            } else {
                player.compassTarget = killer.location
                player.sendMessage(locale.render("body.dna", player, mapOf(
                    "killer" to Component.text(killer.name),
                    "distance" to locale.text(ceil(player.location.distance(killer.location)).toInt()),
                )))
            }
        }
    }

    fun buy(player: Player, offer: ShopOffer): Boolean {
        val current = match
        if (current == null) {
            player.sendMessage(locale.render("shop.unavailable", player))
            return false
        }
        val participant = current.participant(player.uniqueId)
        if (current.phase != MatchPhase.ACTIVE || participant?.status != ParticipantStatus.ALIVE ||
            participant.role == TttRole.INNOCENT || offer !in offers(participant.role)
        ) {
            player.sendMessage(locale.render("shop.unavailable", player))
            return false
        }
        if (participant.credits < offer.cost) {
            player.sendMessage(locale.render("shop.insufficient", player))
            return false
        }
        val purchased = items.purchasedItem(offer.kind, player, current.matchId.toString())
        if (offer.kind == EventItemKind.DETECTIVE_ARMOR) {
            player.inventory.chestplate = purchased
        } else if (player.inventory.addItem(purchased).isNotEmpty()) {
            player.sendMessage(locale.render("shop.inventory-full", player))
            return false
        }
        val updated = participant.copy(credits = participant.credits - offer.cost)
        match = current.copy(revision = current.revision + 1, participants = current.participants + (player.uniqueId to updated))
        player.sendMessage(locale.render("shop.bought", player, mapOf(
            "item" to locale.render(offer.nameKey, player),
            "credits" to locale.text(updated.credits),
        )))
        if (settings().ui.sounds) player.playSound(player.location, Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.65f, 1.25f)
        return true
    }

    fun useSpecialItem(player: Player, kind: EventItemKind): Boolean {
        val current = match ?: return false
        val participant = current.participant(player.uniqueId) ?: return false
        if (current.phase != MatchPhase.ACTIVE || participant.status != ParticipantStatus.ALIVE ||
            !items.belongsTo(player.inventory.itemInMainHand, current.matchId.toString())
        ) return false
        return when (kind) {
            EventItemKind.TRAITOR_RADAR -> activateRadar(player, current)
            EventItemKind.TRAITOR_SMOKE -> activateSmoke(player)
            EventItemKind.DETECTIVE_MEDKIT -> activateMedkit(player)
            EventItemKind.SHOP, EventItemKind.DETECTIVE_SCANNER,
            EventItemKind.TRAITOR_BLADE, EventItemKind.DETECTIVE_ARMOR -> false
        }
    }

    fun teamChat(player: Player, rawMessage: String) {
        val current = match
        val sender = current?.participant(player.uniqueId)
        if (current?.phase != MatchPhase.ACTIVE || sender?.status != ParticipantStatus.ALIVE || sender.role == TttRole.INNOCENT) {
            player.sendMessage(locale.render("team.unavailable", player))
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
            recipient.sendMessage(locale.render("team.message", recipient, mapOf(
                "role" to roleName(sender.role, recipient),
                "player" to Component.text(player.name),
                "message" to message,
            )))
        }
    }

    fun status(player: Player) {
        val current = match
        if (current == null || current.participant(player.uniqueId) == null) {
            player.sendMessage(locale.render("match.unavailable", player))
            return
        }
        player.sendMessage(locale.render("match.status", player, mapOf(
            "phase" to locale.render("phase.${current.phase.name.lowercase()}", player),
            "alive" to locale.text(current.alive().size),
            "players" to locale.text(current.participants.size),
            "time" to locale.text(formatTime(remainingSeconds(current))),
        )))
    }

    fun qaStatus(): String {
        val state = snapshot()
        return ArcEventsDebug.qa(
            "server" to state.serverId,
            "mode" to state.nodeMode.name.lowercase(),
            "redis" to if (state.redisConnected) "up" else "down",
            "host" to if (state.hostAvailable) "ready" else "unavailable",
            "arena" to if (state.arenaReady) "ready" else "disabled",
            "phase" to (state.phase?.name?.lowercase() ?: "idle"),
            "match" to (state.matchId ?: "-"),
            "queue" to state.queueSize,
            "participants" to state.participants,
            "alive" to state.alive,
            "traitors" to state.traitors,
            "detectives" to state.detectives,
            "recovery" to state.recoveryPending,
            "remaining" to state.secondsRemaining,
        )
    }

    fun qaPlayer(playerName: String): String {
        val player = plugin.server.getPlayerExact(playerName)
        val participant = player?.uniqueId?.let(::participant)
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
            "recovery" to (player?.let { escrow.pendingFor(it.uniqueId) } ?: false),
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

    fun qaRecovery(): String = ArcEventsDebug.qa(
        "server" to settings().serverId,
        "pending" to escrow.pendingCount(),
        "players" to escrow.pendingPlayers().sortedBy(UUID::toString).joinToString(",").ifEmpty { "-" },
    )

    fun debugAdvance(): Boolean {
        if (!settings().debugEnabled) return false
        val current = match ?: return false
        when (current.phase) {
            MatchPhase.PREPARING -> beginCountdown()
            MatchPhase.COUNTDOWN -> activateRound()
            MatchPhase.ACTIVE -> resolve(current.copy(
                revision = current.revision + 1,
                phase = MatchPhase.RESOLVING,
                winner = TttTeam.INNOCENTS,
                endReason = MatchEndReason.ADMIN,
            ))
            else -> return false
        }
        return true
    }

    fun debugEnd(team: TttTeam): Boolean {
        if (!settings().debugEnabled) return false
        val current = match ?: return false
        if (current.phase !in LIVE_PHASES) return false
        resolve(current.copy(
            revision = current.revision + 1,
            phase = MatchPhase.RESOLVING,
            winner = team,
            endReason = MatchEndReason.ADMIN,
        ))
        return true
    }

    fun debugCredit(player: Player, amount: Int): Boolean {
        if (!settings().debugEnabled || amount !in -16..16) return false
        val current = match ?: return false
        val participant = current.participant(player.uniqueId) ?: return false
        match = current.copy(
            revision = current.revision + 1,
            participants = current.participants + (player.uniqueId to participant.copy(credits = (participant.credits + amount).coerceIn(0, 64))),
        )
        return true
    }

    fun isInternalTeleport(playerId: UUID, destination: Location?): Boolean =
        teleportAuthorizer.isAuthorized(playerId, destination)

    fun withinArena(location: Location): Boolean {
        val bounds = settings().arena.bounds ?: return false
        return bounds.contains(location.eventLocation())
    }

    fun arenaBounds(): EventBounds? = settings().arena.bounds

    private fun startReservedRoster() {
        val batch = reservation ?: return
        reservation = null
        val online = arrivals.values.mapNotNull { entry -> plugin.server.getPlayer(UUID.fromString(entry.playerId)) }
            .filter(Player::isOnline).distinctBy(Player::getUniqueId)
        arrivals.clear()
        val currentSettings = settings()
        if (online.size < currentSettings.ttt.minimumPlayers) {
            network.releaseReservation(batch.matchId)
            online.forEach { it.sendMessage(locale.render("queue.reservation-expired", it)) }
            debug.event("reservation_cancelled", "match" to batch.matchId, "arrived" to online.size)
            return
        }
        val entries = batch.entries.filter { entry -> online.any { it.uniqueId.toString() == entry.playerId } }
        val engine = engine()
        recentAttacks.clear()
        val created = engine.create(
            batch.matchId,
            entries.map(QueueEntry::queuedPlayer),
            RoleAllocationSettings(
                currentSettings.ttt.traitorPlayerRatio,
                currentSettings.ttt.detectiveMinimumPlayers,
                currentSettings.ttt.traitorCredits,
                currentSettings.ttt.detectiveCredits,
            ),
            seed = batch.matchId.mostSignificantBits xor batch.matchId.leastSignificantBits,
            nowMs = clock(),
        )
        try {
            escrow.prepare(
                batch.matchId,
                online,
                entries.associate { UUID.fromString(it.playerId) to it.originServer },
                clock(),
            )
            match = engine.prepare(created)
            applyEventState(online, requireNotNull(match))
            network.announceStarted(batch.matchId)
            debug.event("match_preparing", "match" to batch.matchId, "players" to online.size)
            tasks += Tasks.scheduler.runLater(currentSettings.ttt.preparationSeconds * 20L) { beginCountdown() }
        } catch (failure: Throwable) {
            plugin.logger.log(Level.SEVERE, "ArcEvents could not prepare match ${batch.matchId}", failure)
            match = created
            cancel(MatchEndReason.SHUTDOWN)
        }
    }

    private fun applyEventState(players: List<Player>, current: TttMatch) {
        val arena = settings().arena
        require(arenaReady())
        players.forEachIndexed { index, player ->
            val participant = requireNotNull(current.participant(player.uniqueId))
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
            items.giveBaseLoadout(player, participant.role, current.matchId.toString())
            teleport(player, arena.spawns[index])
            player.showTitle(Title.title(
                locale.render("match.preparing-title", player),
                locale.render("match.preparing-subtitle", player),
                Title.Times.times(Duration.ofMillis(250), Duration.ofSeconds(3), Duration.ofMillis(500)),
            ))
            player.saveData()
        }
    }

    private fun beginCountdown() {
        val current = match ?: return
        if (current.phase != MatchPhase.PREPARING) return
        match = engine().countdown(current)
        countdownRemaining = settings().ttt.countdownSeconds
        revealRoles(requireNotNull(match))
        debug.event("match_countdown", "match" to current.matchId, "seconds" to countdownRemaining)
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
        }
        if (detectiveNames.isNotEmpty()) {
            broadcast("match.detectives-announced", mapOf("players" to Component.text(detectiveNames.joinToString(", "))))
        }
    }

    private fun activateRound() {
        val current = match ?: return
        if (current.phase != MatchPhase.COUNTDOWN) return
        match = engine().activate(current, clock())
        countdownRemaining = 0
        createBossBar()
        requireNotNull(match).participants.values.mapNotNull { plugin.server.getPlayer(it.playerId) }.forEach { player ->
            player.sendMessage(locale.render("match.started", player))
            if (settings().ui.sounds) player.playSound(player.location, Sound.ENTITY_ENDER_DRAGON_GROWL, 0.45f, 1.35f)
        }
        debug.event("match_active", "match" to current.matchId, "deadline" to match?.deadlineMs)
    }

    private fun tick() {
        val current = match ?: return
        when (current.phase) {
            MatchPhase.COUNTDOWN -> {
                if (countdownRemaining <= 0) {
                    activateRound()
                    return
                }
                current.participants.values.mapNotNull { plugin.server.getPlayer(it.playerId) }.forEach { player ->
                    player.showTitle(Title.title(
                        locale.render("match.countdown-title", player, mapOf("seconds" to locale.text(countdownRemaining))),
                        locale.render("match.countdown-subtitle", player),
                        Title.Times.times(Duration.ZERO, Duration.ofMillis(900), Duration.ofMillis(100)),
                    ))
                    if (settings().ui.sounds) player.playSound(player.location, Sound.BLOCK_NOTE_BLOCK_HAT, 0.45f, 1.2f)
                }
                countdownRemaining--
            }
            MatchPhase.ACTIVE -> {
                val (changed, outcome) = engine().tick(current, clock())
                match = changed
                updateHud(changed)
                if (outcome is MatchOutcome.Finished) resolve(changed)
            }
            else -> Unit
        }
    }

    private fun updateHud(current: TttMatch) {
        val remaining = remainingSeconds(current)
        bossBar?.apply {
            progress((remaining.toFloat() / settings().ttt.roundSeconds).coerceIn(0f, 1f))
            name(locale.render("match.time-title", values = mapOf("time" to locale.text(formatTime(remaining)))))
        }
        current.participants.values.filter { it.status == ParticipantStatus.ALIVE }.forEach { participant ->
            plugin.server.getPlayer(participant.playerId)?.sendActionBar(locale.render("role.actionbar", plugin.server.getPlayer(participant.playerId), mapOf(
                "role" to roleName(participant.role, plugin.server.getPlayer(participant.playerId)),
                "alive" to locale.text(current.alive().size),
                "time" to locale.text(formatTime(remaining)),
            )))
        }
    }

    private fun resolve(current: TttMatch) {
        if (current.phase != MatchPhase.RESOLVING) return
        match = current
        cancelMatchTasks(keepMainTick = true)
        hideBossBar()
        val title = if (current.winner == TttTeam.TRAITORS) "match.winner-traitors-title" else "match.winner-innocents-title"
        val subtitle = if (current.winner == TttTeam.TRAITORS) "match.winner-traitors-subtitle" else "match.winner-innocents-subtitle"
        current.participants.values.forEach { participant ->
            plugin.server.getPlayer(participant.playerId)?.let { player ->
                player.showTitle(Title.title(
                    locale.render(title, player),
                    locale.render(subtitle, player),
                    Title.Times.times(Duration.ofMillis(250), Duration.ofSeconds(4), Duration.ofMillis(750)),
                ))
            }
            network.recordStats(participant.playerId) { it.record(participant, current.winner) }
        }
        network.announceEnded(current.matchId, current.winner, requireNotNull(current.endReason))
        tasks += Tasks.scheduler.runLater(settings().ttt.postRoundSeconds * 20L) { beginRestoration() }
        debug.event("match_resolving", "match" to current.matchId, "winner" to current.winner, "reason" to current.endReason)
    }

    private fun cancel(reason: MatchEndReason) {
        val current = match ?: return
        if (current.phase in setOf(MatchPhase.COMPLETED, MatchPhase.RESTORING)) return
        val cancelled = runCatching { engine().cancel(current, reason) }.getOrElse {
            current.copy(revision = current.revision + 1, phase = MatchPhase.CANCELLED, endReason = reason)
        }
        match = cancelled
        cancelMatchTasks(keepMainTick = true)
        hideBossBar()
        cancelled.participants.values.mapNotNull { plugin.server.getPlayer(it.playerId) }.forEach { player ->
            player.showTitle(Title.title(
                locale.render("match.cancelled-title", player),
                locale.render("match.cancelled-subtitle", player),
                Title.Times.times(Duration.ofMillis(150), Duration.ofSeconds(2), Duration.ofMillis(400)),
            ))
        }
        network.announceEnded(cancelled.matchId, null, reason)
        beginRestoration()
    }

    private fun beginRestoration() {
        val current = match ?: return
        if (current.phase !in setOf(MatchPhase.RESOLVING, MatchPhase.CANCELLED)) return
        match = engine().restoring(current)
        cleanupBodies()
        cleanupProjectiles()
        radarTasks.values.forEach(ScheduledTask::cancel)
        radarTasks.clear()
        val restoredOnline = mutableSetOf<UUID>()
        current.participants.values.forEach { participant ->
            val player = plugin.server.getPlayer(participant.playerId) ?: return@forEach
            runCatching { recoverPlayer(player) }
                .onSuccess { recovery ->
                    if (recovery != null) {
                        restoredOnline += player.uniqueId
                        player.sendMessage(locale.render("match.restored", player))
                        network.returnPlayer(player, recovery.returnServer)
                    }
                }
                .onFailure {
                    plugin.logger.log(Level.SEVERE, "ArcEvents could not restore ${player.uniqueId}", it)
                    player.sendMessage(locale.render("match.restore-pending", player))
                }
        }
        var updated = requireNotNull(match)
        restoredOnline.forEach { playerId -> updated = engine().restored(updated, playerId) }
        match = if (escrow.pendingCount() == 0) updated.copy(phase = MatchPhase.COMPLETED) else updated
        debug.event("match_restoration", "match" to current.matchId, "restored" to restoredOnline.size, "pending" to escrow.pendingCount())
        if (escrow.pendingCount() == 0) finishCleanup()
        else tasks += Tasks.scheduler.runLater(20L) { finishCleanup() }
    }

    private fun finishCleanup() {
        val current = match ?: return
        if (plugin.server.onlinePlayers.any { escrow.pendingFor(it.uniqueId) }) {
            cleanupRetryTask?.cancel()
            cleanupRetryTask = Tasks.scheduler.runLater(20L) { finishCleanup() }
            return
        }
        cleanupRetryTask = null
        debug.event("match_released", "match" to current.matchId, "phase" to current.phase, "recovery" to escrow.pendingCount())
        recentAttacks.clear()
        match = null
    }

    private fun spawnBody(player: Player, participant: TttParticipant, killerId: UUID?) {
        val location = player.location.clone()
        val head = ItemStack.of(Material.PLAYER_HEAD)
        head.editMeta(SkullMeta::class.java) { it.owningPlayer = plugin.server.getOfflinePlayer(player.uniqueId) }
        val bodyId = UUID.randomUUID()
        val stand = location.world.spawn(location, ArmorStand::class.java) { armorStand ->
            armorStand.isVisible = false
            armorStand.setGravity(false)
            armorStand.isSmall = true
            armorStand.isSilent = true
            armorStand.isInvulnerable = true
            armorStand.isCollidable = false
            armorStand.isCustomNameVisible = true
            armorStand.customName(locale.render("body.unidentified", values = mapOf("player" to Component.text(player.name))))
            armorStand.equipment.helmet = head
            armorStand.persistentDataContainer.set(bodyKey, PersistentDataType.STRING, bodyId.toString())
        }
        bodies[bodyId] = BodyRecord(
            bodyId, requireNotNull(match).matchId, participant.playerId, participant.playerName,
            participant.role, killerId, clock(), location, stand.uniqueId,
        )
        tasks += Tasks.scheduler.runLater(settings().ttt.bodyDespawnSeconds * 20L) {
            bodies.remove(bodyId)?.let { plugin.server.getEntity(it.entityId)?.remove() }
        }
    }

    fun readBodyId(stand: ArmorStand): UUID? = stand.persistentDataContainer.get(bodyKey, PersistentDataType.STRING)
        ?.let { runCatching { UUID.fromString(it) }.getOrNull() }

    private fun cleanupBodies() {
        bodies.values.forEach { plugin.server.getEntity(it.entityId)?.remove() }
        bodies.clear()
    }

    private fun cleanupProjectiles() {
        projectiles.forEach { plugin.server.getEntity(it)?.remove() }
        projectiles.clear()
    }

    private fun rewardKiller(current: TttMatch, victimId: UUID, killerId: UUID?): TttMatch {
        if (killerId == null || killerId == victimId) return current
        val victim = current.participant(victimId) ?: return current
        val killer = current.participant(killerId) ?: return current
        if (killer.status != ParticipantStatus.ALIVE || killer.role.team == victim.role.team) return current
        return current.copy(
            revision = current.revision + 1,
            participants = current.participants + (killerId to killer.copy(credits = (killer.credits + 1).coerceAtMost(64))),
        )
    }

    private fun activateRadar(player: Player, current: TttMatch): Boolean {
        val actor = current.participant(player.uniqueId) ?: return false
        val target = radarTarget(player, current, actor)
        if (target == null) {
            player.sendMessage(locale.render("shop.radar-empty", player))
            return false
        }
        consumeMainHand(player)
        player.compassTarget = target.location
        player.sendMessage(locale.render("shop.radar-active", player, mapOf("target" to Component.text(target.name))))
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
        tasks += Tasks.scheduler.runLater(30L * 20L) { radarTasks.remove(player.uniqueId)?.cancel() }
        return true
    }

    private fun radarTarget(player: Player, current: TttMatch, actor: TttParticipant): Player? =
        current.alive().filter { it.role.team != actor.role.team }
            .mapNotNull { plugin.server.getPlayer(it.playerId) }
            .filter { it.world == player.world }
            .minByOrNull { it.location.distanceSquared(player.location) }

    private fun activateSmoke(player: Player): Boolean {
        consumeMainHand(player)
        val current = match ?: return false
        current.alive().mapNotNull { plugin.server.getPlayer(it.playerId) }
            .filter { it.uniqueId != player.uniqueId && it.world == player.world && it.location.distanceSquared(player.location) <= 49.0 }
            .forEach { it.addPotionEffect(PotionEffect(PotionEffectType.BLINDNESS, 5 * 20, 0, false, true, true)) }
        if (settings().ui.particles) {
            player.world.spawnParticle(Particle.LARGE_SMOKE, player.location.add(0.0, 1.0, 0.0), 80, 3.5, 1.5, 3.5, 0.03)
        }
        player.sendMessage(locale.render("shop.smoke-used", player))
        return true
    }

    private fun activateMedkit(player: Player): Boolean {
        val maximum = requireNotNull(player.getAttribute(Attribute.MAX_HEALTH)).value
        if (player.health >= maximum) {
            player.sendMessage(locale.render("shop.medkit-full", player))
            return false
        }
        consumeMainHand(player)
        player.health = (player.health + 8.0).coerceAtMost(maximum)
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

    private fun createBossBar() {
        if (!settings().ui.bossBar) return
        val bar = BossBar.bossBar(Component.empty(), 1f, BossBar.Color.YELLOW, BossBar.Overlay.PROGRESS)
        bossBar = bar
        match?.participants?.values?.mapNotNull { plugin.server.getPlayer(it.playerId) }?.forEach { it.showBossBar(bar) }
    }

    private fun hideBossBar() {
        val bar = bossBar ?: return
        plugin.server.onlinePlayers.forEach { it.hideBossBar(bar) }
        bossBar = null
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
        match?.participants?.values?.mapNotNull { plugin.server.getPlayer(it.playerId) }?.forEach { player ->
            player.sendMessage(locale.render(path, player, values))
        }
    }

    private fun engine(): TttMatchEngine {
        val ttt = settings().ttt
        return TttMatchEngine(ttt.minimumPlayers, ttt.maximumPlayers, ttt.roundSeconds * 1_000L)
    }

    private fun remainingSeconds(current: TttMatch?): Long {
        if (current?.phase != MatchPhase.ACTIVE) return 0
        return max(0L, (requireNotNull(current.deadlineMs) - clock() + 999L) / 1_000L)
    }

    private fun formatTime(seconds: Long): String = "%d:%02d".format(seconds / 60, seconds % 60)

    private fun onlineRoster(current: TttMatch): List<Player> = current.participants.keys.mapNotNull(plugin.server::getPlayer)

    private fun cancelMatchTasks(keepMainTick: Boolean) {
        val retained = if (keepMainTick) tasks.firstOrNull() else null
        tasks.filterNot { it === retained }.forEach(ScheduledTask::cancel)
        tasks.clear()
        retained?.let(tasks::add)
    }

    override fun close() {
        if (!started) return
        started = false
        reservation?.let { network.releaseReservation(it.matchId) }
        reservation = null
        arrivals.clear()
        when (match?.phase) {
            MatchPhase.PREPARING, MatchPhase.COUNTDOWN, MatchPhase.ACTIVE -> cancel(MatchEndReason.SHUTDOWN)
            MatchPhase.RESOLVING, MatchPhase.CANCELLED -> beginRestoration()
            else -> Unit
        }
        cancelMatchTasks(keepMainTick = false)
        hideBossBar()
        cleanupBodies()
        cleanupProjectiles()
        radarTasks.values.forEach(ScheduledTask::cancel)
        radarTasks.clear()
        cleanupRetryTask?.cancel()
        cleanupRetryTask = null
    }

    private fun Location.eventLocation(): EventLocation = EventLocation(world.name, x, y, z, yaw, pitch)

    companion object {
        private val LIVE_PHASES = setOf(MatchPhase.PREPARING, MatchPhase.COUNTDOWN, MatchPhase.ACTIVE)
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

private fun PlayerStateEscrow.pendingFor(playerId: UUID): Boolean = playerId in pendingPlayers()
