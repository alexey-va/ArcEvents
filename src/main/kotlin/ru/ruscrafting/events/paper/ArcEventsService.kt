package ru.ruscrafting.events.paper

import net.kyori.adventure.text.Component
import net.kyori.adventure.title.Title
import org.bukkit.GameMode
import org.bukkit.FluidCollisionMode
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.Particle
import org.bukkit.Sound
import org.bukkit.attribute.Attribute
import org.bukkit.entity.ArmorStand
import org.bukkit.entity.Item
import org.bukkit.entity.Player
import org.bukkit.entity.Projectile
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.SkullMeta
import org.bukkit.persistence.PersistentDataType
import org.bukkit.plugin.Plugin
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
import ru.ruscrafting.events.domain.CombatRecord
import ru.ruscrafting.events.domain.FirearmSpread
import ru.ruscrafting.events.domain.FirearmId
import ru.ruscrafting.events.domain.RosterEntry
import ru.ruscrafting.events.domain.RosterStatus
import ru.ruscrafting.events.domain.ShotDirection
import ru.ruscrafting.events.domain.RoleAllocationSettings
import ru.ruscrafting.events.domain.QueuedPlayer
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
    val weaponKey: String,
    val finalDamage: Double,
    val headshot: Boolean,
    var discovered: Boolean = false,
    var detectiveCalled: Boolean = false,
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
    private val clock: () -> Long = System::currentTimeMillis,
) : ArcEventsGameplayBoundary, AutoCloseable {
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
    private val combatLog = mutableListOf<CombatRecord>()
    private val shotCooldownUntil = mutableMapOf<UUID, Long>()
    private val reloadTasks = mutableMapOf<UUID, ScheduledTask>()
    private var pendingHit: PendingHitContext? = null
    private var roundReport: RoundReportView? = null
    private val bodyKey = NamespacedKey(plugin, "body_id")
    private val projectileMatchKey = NamespacedKey(plugin, "projectile_match")
    private var preparationRemaining = 0
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
            arenaId = arenaPool.active()?.id,
            queueSize = network.queueSize,
            matchId = active?.matchId ?: pendingReservation?.matchId,
            phase = active?.phase ?: pendingReservation?.let { MatchPhase.RESERVED },
            participants = active?.participants?.size ?: pendingReservation?.entries?.size ?: 0,
            alive = active?.let(::visibleAliveCount) ?: 0,
            traitors = active?.alive()?.count { it.role == TttRole.TRAITOR } ?: 0,
            detectives = active?.alive()?.count { it.role == TttRole.DETECTIVE } ?: 0,
            recoveryPending = escrow.pendingCount(),
            secondsRemaining = phaseRemainingSeconds(active).toLong(),
        )
    }

    fun matchState(): Pair<UUID?, MatchPhase?> = match?.let { it.matchId to it.phase }
        ?: reservation?.let { it.matchId to MatchPhase.RESERVED }
        ?: (null to null)
    fun currentMatch(): TttMatch? = match
    fun participant(playerId: UUID): TttParticipant? = match?.participant(playerId)
    fun stats(playerId: UUID): PlayerEventStats = network.stats(playerId)
    fun report(): RoundReportView? = roundReport
    fun bodyId(entityId: UUID): UUID? = bodies.values.firstOrNull { it.entityId == entityId }?.bodyId
    override fun isParticipant(playerId: UUID): Boolean = match?.participant(playerId) != null
    override fun isAlive(playerId: UUID): Boolean = participant(playerId)?.status == ParticipantStatus.ALIVE
    override fun phase(): MatchPhase? = match?.phase
    fun activeMatchId(): String? = match?.matchId?.toString()
    fun arenaReady(): Boolean = arenaPool.anyReady()
    fun activeArenaId(): String? = arenaPool.active()?.id
    fun arenaEntries(): List<ArenaPoolEntry> = arenaPool.entries()
    fun selectNextArena(id: String?): Boolean = arenaPool.selectNext(id)

    fun roster(viewerId: UUID): RosterView? {
        val current = match ?: return null
        if (current.phase != MatchPhase.ACTIVE) return null
        val viewer = current.participant(viewerId) ?: return null
        val discoveredVictims = bodies.values.filter(BodyRecord::discovered).map(BodyRecord::victimId).toSet()
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
        val body = bodies[bodyId]?.takeIf { it.matchId == current.matchId && it.discovered } ?: return null
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
        if (!started || match != null || reservation != null) {
            return false
        }
        if (arenaPool.reserve(batch.matchId) == null) return false
        reservation = batch
        arrivals.clear()
        debug.event("reservation_created", "match" to batch.matchId, "players" to batch.entries.size)
        tasks += Tasks.scheduler.runLater(settings().network.reservationSeconds * 20L) {
            if (reservation?.matchId == batch.matchId) startReservedRoster()
        }
        return true
    }

    fun onArrival(entry: QueueEntry): Boolean {
        val batch = reservation ?: return false
        if (entry.matchId != batch.matchId.toString()) return false
        val playerId = UUID.fromString(entry.playerId)
        if (batch.entries.none { it.playerId == entry.playerId }) return false
        arrivals[playerId] = entry
        debug.event("reservation_arrival", "match" to batch.matchId, "player" to playerId, "arrived" to arrivals.size)
        if (arrivals.size == batch.entries.size) startReservedRoster()
        return true
    }

    fun joinQueue(player: Player) {
        if (escrow.pendingFor(player.uniqueId)) {
            player.sendMessage(locale.render("match.restore-pending", player))
            return
        }
        network.join(player)
    }

    fun leaveQueue(player: Player) = network.leave(player)

    override fun handleJoin(player: Player) {
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
                    network.returnRecoveredPlayer(player, recovery)
                    return@runLater
                }
            }
            network.handleJoin(player)
        }
    }

    override fun handleQuit(player: Player) {
        hud.remove(player.uniqueId)
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

    fun startFromQueue(requester: Player? = null): CompletableFuture<ReservationStartResult> {
        if (requester != null && escrow.pendingFor(requester.uniqueId)) {
            return CompletableFuture.completedFuture(ReservationStartResult.RECOVERY_PENDING)
        }
        return network.reserveNow(requester)
    }

    fun stopByAdmin(): AdminStopResult {
        reservation?.let { pending ->
            reservation = null
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
        plugin.server.onlinePlayers.forEach { player ->
            if (!escrow.pendingFor(player.uniqueId)) return@forEach
            available++
            runCatching { recoverPlayer(player) }
                .onSuccess { recovery ->
                    if (recovery != null) {
                        player.sendMessage(locale.render("match.restored", player))
                        network.returnRecoveredPlayer(player, recovery)
                    }
                }
                .onFailure { plugin.logger.log(Level.SEVERE, "ArcEvents recovery retry failed for ${player.uniqueId}", it) }
        }
        return available
    }

    fun escrowPending(playerId: UUID): Boolean = escrow.pendingFor(playerId)

    override fun recordAttack(victimId: UUID, attackerId: UUID?) {
        val current = match ?: return
        if (current.phase != MatchPhase.ACTIVE || attackerId == null) return
        if (current.participant(victimId)?.status != ParticipantStatus.ALIVE ||
            current.participant(attackerId)?.status != ParticipantStatus.ALIVE
        ) return
        recentAttacks.record(current.matchId, victimId, attackerId)
    }

    override fun damageMultiplier(attackerId: UUID?): Double {
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
            sequence = combatLog.size + 1,
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
        combatLog += record
        if (combatLog.size > 512) combatLog.removeAt(0)
        match = engine().recordDamage(current, victim.uniqueId, attackerParticipant?.playerId, finalDamage)
    }

    override fun shouldCancelDamage(victimId: UUID, attackerId: UUID?, projectile: Boolean, projectileMatchId: UUID?): Boolean {
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

    override fun eliminate(player: Player, killerId: UUID?) {
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
        val current = match ?: return false
        return current.phase in CHAT_PHASES && current.participant(playerId) != null
    }

    override fun sendMatchChat(player: Player, message: Component) {
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

    override fun inspectBody(player: Player, bodyId: UUID) {
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
    }

    fun scanBody(player: Player, bodyId: UUID): Boolean {
        val body = bodies[bodyId] ?: return false
        val current = match ?: return false
        val inspector = current.participant(player.uniqueId) ?: return false
        if (body.matchId != current.matchId || !body.discovered || current.phase != MatchPhase.ACTIVE ||
            inspector.status != ParticipantStatus.ALIVE || inspector.role != TttRole.DETECTIVE
        ) {
            player.sendMessage(locale.render("body.scanner-detective-only", player))
            return false
        }
        val scanner = player.inventory.contents.any { item ->
            items.kind(item) == EventItemKind.DETECTIVE_SCANNER && items.belongsTo(item, current.matchId.toString())
        }
        if (!scanner) {
            player.sendMessage(locale.render("body.scanner-required", player))
            return false
        }
        val killer = body.killerId?.let(plugin.server::getPlayer)?.takeIf { isAlive(it.uniqueId) }
            ?.takeIf { clock() - body.killedAtMs <= settings().weapons.dnaSeconds * 1_000L }
        if (killer == null) {
            player.sendMessage(locale.render("body.dna-lost", player))
            return false
        }
        player.compassTarget = killer.location
        player.sendMessage(locale.render("body.dna", player, mapOf(
            "killer" to Component.text(killer.name),
            "distance" to locale.text(ceil(player.location.distance(killer.location)).toInt()),
        )))
        return true
    }

    fun callDetective(player: Player, bodyId: UUID): Boolean {
        val body = bodies[bodyId] ?: return false
        val current = match ?: return false
        if (body.matchId != current.matchId || current.phase != MatchPhase.ACTIVE ||
            current.participant(player.uniqueId)?.status != ParticipantStatus.ALIVE || !body.discovered
        ) return false
        if (body.detectiveCalled) {
            player.sendMessage(locale.render("body.detective-already-called", player))
            return false
        }
        val detectives = current.participants.values.filter { it.role == TttRole.DETECTIVE && it.status == ParticipantStatus.ALIVE }
            .mapNotNull { plugin.server.getPlayer(it.playerId) }
        if (detectives.isEmpty()) {
            player.sendMessage(locale.render("body.no-detective", player))
            return false
        }
        body.detectiveCalled = true
        detectives.forEach { detective ->
            detective.compassTarget = body.location
            detective.sendMessage(locale.render("body.detective-called", detective, mapOf(
                "player" to Component.text(player.name),
                "victim" to Component.text(body.victimName),
                "x" to locale.text(body.location.blockX),
                "y" to locale.text(body.location.blockY),
                "z" to locale.text(body.location.blockZ),
            )))
            if (settings().ui.sounds) detective.playSound(detective.location, Sound.BLOCK_BELL_USE, 0.8f, 1.15f)
        }
        player.sendMessage(locale.render("body.detective-call-sent", player))
        return true
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
            EventItemKind.DETECTIVE_SCANNER, EventItemKind.TRAITOR_BLADE, EventItemKind.DETECTIVE_ARMOR -> false
        }
    }

    override fun useFirearm(player: Player): Boolean {
        val current = match ?: return false
        val participant = current.participant(player.uniqueId) ?: return false
        val held = player.inventory.itemInMainHand
        val state = firearms.state(held) ?: return false
        if (!settings().weapons.enabled || current.phase != MatchPhase.ACTIVE || participant.status != ParticipantStatus.ALIVE ||
            state.matchId != current.matchId.toString()
        ) return false
        if (reloadTasks.containsKey(player.uniqueId)) {
            player.sendActionBar(locale.render("weapon.reloading-actionbar", player))
            return false
        }
        val spec = firearms.spec(state.id)
        val now = clock()
        if (now < (shotCooldownUntil[player.uniqueId] ?: 0L)) return false
        if (state.loaded < spec.roundsPerShot) {
            shotCooldownUntil[player.uniqueId] = now + 350L
            player.sendActionBar(locale.render("weapon.empty-actionbar", player))
            if (settings().ui.sounds) player.playSound(player.location, Sound.BLOCK_LEVER_CLICK, 0.7f, 1.7f)
            return false
        }
        shotCooldownUntil[player.uniqueId] = now + spec.cooldownTicks * 50L
        val loaded = state.loaded - spec.roundsPerShot
        player.inventory.setItemInMainHand(firearms.updateLoaded(held, player, loaded))
        val eye = player.eyeLocation.clone()
        val base = eye.direction.normalize()
        val random = Random(now xor player.uniqueId.mostSignificantBits xor combatLog.size.toLong())
        repeat(spec.pellets) {
            val yaw = (random.nextDouble() * 2.0 - 1.0) * spec.spreadDegrees
            val pitch = (random.nextDouble() * 2.0 - 1.0) * spec.spreadDegrees
            val spread = FirearmSpread.apply(ShotDirection(base.x, base.y, base.z), yaw, pitch)
            fireRay(player, state.id.name.lowercase(), eye, Vector(spread.x, spread.y, spread.z), spec.range, spec.damagePerPellet)
        }
        if (settings().ui.sounds) {
            val sound = when (state.id) {
                ru.ruscrafting.events.domain.FirearmId.PISTOL -> Sound.ENTITY_FIREWORK_ROCKET_BLAST
                ru.ruscrafting.events.domain.FirearmId.SMG -> Sound.ENTITY_FIREWORK_ROCKET_LARGE_BLAST
                ru.ruscrafting.events.domain.FirearmId.SHOTGUN -> Sound.ENTITY_GENERIC_EXPLODE
                ru.ruscrafting.events.domain.FirearmId.RIFLE -> Sound.ENTITY_FIREWORK_ROCKET_TWINKLE_FAR
            }
            player.world.playSound(player.location, sound, 0.85f, when (state.id) {
                ru.ruscrafting.events.domain.FirearmId.SHOTGUN -> 0.75f
                ru.ruscrafting.events.domain.FirearmId.RIFLE -> 1.3f
                else -> 1.05f
            })
        }
        player.sendActionBar(locale.render("weapon.ammo-actionbar", player, mapOf(
            "weapon" to locale.render("weapon.${state.id.name.lowercase()}-name", player),
            "loaded" to locale.text(loaded),
            "magazine" to locale.text(spec.magazineSize),
            "reserve" to locale.text(firearms.reserveAmmo(player, current.matchId.toString())),
        )))
        return true
    }

    override fun reloadFirearm(player: Player): Boolean {
        val current = match ?: return false
        val participant = current.participant(player.uniqueId) ?: return false
        val state = firearms.state(player.inventory.itemInMainHand) ?: return false
        if (current.phase != MatchPhase.ACTIVE || participant.status != ParticipantStatus.ALIVE || state.matchId != current.matchId.toString()) return false
        val spec = firearms.spec(state.id)
        if (state.loaded >= spec.magazineSize) {
            player.sendActionBar(locale.render("weapon.magazine-full-actionbar", player))
            return false
        }
        if (firearms.reserveAmmo(player, state.matchId) == 0) {
            player.sendActionBar(locale.render("weapon.no-ammo-actionbar", player))
            return false
        }
        if (reloadTasks.containsKey(player.uniqueId)) return false
        player.sendActionBar(locale.render("weapon.reloading-actionbar", player))
        if (settings().ui.sounds) player.playSound(player.location, Sound.ITEM_ARMOR_EQUIP_IRON, 0.5f, 1.4f)
        reloadTasks[player.uniqueId] = Tasks.scheduler.runLater(spec.reloadTicks.toLong()) {
            reloadTasks.remove(player.uniqueId)
            val live = match
            val held = player.inventory.itemInMainHand
            val latest = firearms.state(held)
            if (!player.isOnline || live?.matchId != current.matchId || live.phase != MatchPhase.ACTIVE ||
                latest?.id != state.id || latest.matchId != state.matchId
            ) return@runLater
            val needed = spec.magazineSize - latest.loaded
            val consumed = firearms.consumeReserve(player, latest.matchId, needed)
            if (consumed <= 0) return@runLater
            player.inventory.setItemInMainHand(firearms.updateLoaded(held, player, latest.loaded + consumed))
            player.sendActionBar(locale.render("weapon.reload-complete-actionbar", player, mapOf(
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

    override fun handleLootPickup(item: Item) = lootScene.consume(item.uniqueId)

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
            "combat" to combatLog.size,
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

    fun qaArenas(): List<String> = arenaPool.entries().map { arena ->
        ArcEventsDebug.qa(
            "server" to settings().serverId,
            "arena" to arena.id,
            "world" to arena.world,
            "template" to arena.template,
            "ready" to arena.ready,
            "active" to arena.active,
            "next" to arena.next,
        )
    }

    fun qaRecovery(): String = ArcEventsDebug.qa(
        "server" to settings().serverId,
        "pending" to escrow.pendingCount(),
        "players" to escrow.pendingPlayers().sortedBy(UUID::toString).joinToString(",").ifEmpty { "-" },
    )

    fun debugStartLocal(players: List<Player>, arenaId: String? = null): DebugMutationResult {
        val currentSettings = settings()
        if (!currentSettings.debugMutationsAllowed) return DebugMutationResult.MUTATIONS_DISABLED
        if (currentSettings.nodeMode != NodeMode.HOST) return DebugMutationResult.WRONG_NODE
        if (match != null || reservation != null) return DebugMutationResult.BUSY
        val online = players.filter(Player::isOnline).distinctBy(Player::getUniqueId)
        if (online.size !in currentSettings.ttt.minimumPlayers..currentSettings.ttt.maximumPlayers) {
            return DebugMutationResult.INSUFFICIENT_PLAYERS
        }
        val matchId = UUID.randomUUID()
        val engine = engine()
        val created = engine.create(
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
        val arena = arenaPool.reserve(matchId, arenaId) ?: return DebugMutationResult.ARENA_UNAVAILABLE
        return runCatching {
            recentAttacks.clear()
            combatLog.clear()
            roundReport = null
            cleanupBodies()
            cleanupProjectiles()
            cleanupLoot()
            escrow.prepare(matchId, online, online.associate { it.uniqueId to currentSettings.serverId }, clock())
            match = engine.prepare(created)
            preparationRemaining = currentSettings.ttt.preparationSeconds
            applyEventState(online, requireNotNull(match))
            debug.event("debug_match_preparing", "match" to matchId, "players" to online.size, "arena" to arena.id)
            DebugMutationResult.APPLIED
        }.getOrElse { failure ->
            plugin.logger.log(Level.SEVERE, "ArcEvents debug bootstrap failed for $matchId", failure)
            match = created
            cancel(MatchEndReason.SHUTDOWN)
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
        match = current.copy(
            revision = current.revision + 1,
            participants = current.participants + (player.uniqueId to participant.copy(credits = (participant.credits + amount).coerceIn(0, 64))),
        )
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
        val valid = runCatching { changed.validated(settings().ttt.minimumPlayers, settings().ttt.maximumPlayers) }.getOrNull()
            ?: return DebugMutationResult.ROLE_INVARIANT
        match = valid
        return DebugMutationResult.APPLIED
    }

    fun debugTimer(seconds: Int): DebugMutationResult {
        if (!settings().debugMutationsAllowed) return DebugMutationResult.MUTATIONS_DISABLED
        if (seconds !in 1..3600) return DebugMutationResult.INVALID_ARGUMENT
        val current = match ?: return DebugMutationResult.NO_MATCH
        if (current.phase != MatchPhase.ACTIVE) return DebugMutationResult.WRONG_PHASE
        match = current.copy(revision = current.revision + 1, deadlineMs = clock() + seconds * 1_000L)
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
        match = current.copy(
            revision = current.revision + 1,
            participants = current.participants + (player.uniqueId to participant.copy(status = ParticipantStatus.ALIVE, deaths = 0)),
        ).validated(settings().ttt.minimumPlayers, settings().ttt.maximumPlayers)
        bodies.values.filter { it.victimId == player.uniqueId }.toList().forEach { body ->
            plugin.server.getEntity(body.entityId)?.remove()
            bodies.remove(body.bodyId)
        }
        player.gameMode = GameMode.ADVENTURE
        player.health = player.getAttribute(Attribute.MAX_HEALTH)?.value ?: 20.0
        player.foodLevel = 20
        items.givePreparationLoadout(player, current.matchId.toString())
        items.revealRoleLoadout(player, participant.role, current.matchId.toString())
        val index = current.participants.keys.indexOf(player.uniqueId).coerceAtLeast(0)
        teleport(player, requireNotNull(arenaPool.active()).spawns[index])
        return DebugMutationResult.APPLIED
    }

    fun debugDiscover(viewer: Player, victim: Player): DebugMutationResult {
        if (!settings().debugMutationsAllowed) return DebugMutationResult.MUTATIONS_DISABLED
        val current = match ?: return DebugMutationResult.NO_MATCH
        if (current.phase != MatchPhase.ACTIVE) return DebugMutationResult.WRONG_PHASE
        if (current.participant(viewer.uniqueId)?.status != ParticipantStatus.ALIVE) return DebugMutationResult.NOT_ALIVE
        val body = bodies.values.lastOrNull { it.matchId == current.matchId && it.victimId == victim.uniqueId }
            ?: return DebugMutationResult.BODY_NOT_FOUND
        inspectBody(viewer, body.bodyId)
        return DebugMutationResult.APPLIED
    }

    fun debugDna(viewer: Player, victim: Player): DebugMutationResult {
        if (!settings().debugMutationsAllowed) return DebugMutationResult.MUTATIONS_DISABLED
        val current = match ?: return DebugMutationResult.NO_MATCH
        val body = bodies.values.lastOrNull { it.matchId == current.matchId && it.victimId == victim.uniqueId }
            ?: return DebugMutationResult.BODY_NOT_FOUND
        return if (scanBody(viewer, body.bodyId)) DebugMutationResult.APPLIED else DebugMutationResult.PRECONDITION_FAILED
    }

    fun debugCallDetective(viewer: Player, victim: Player): DebugMutationResult {
        if (!settings().debugMutationsAllowed) return DebugMutationResult.MUTATIONS_DISABLED
        val current = match ?: return DebugMutationResult.NO_MATCH
        val body = bodies.values.lastOrNull { it.matchId == current.matchId && it.victimId == victim.uniqueId }
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
        if (match != null || reservation != null) return DebugMutationResult.BUSY
        cleanupBodies()
        cleanupProjectiles()
        cleanupLoot()
        combatLog.clear()
        roundReport = null
        recentAttacks.clear()
        return DebugMutationResult.APPLIED
    }

    fun qaBodies(): List<String> = bodies.values.sortedBy(BodyRecord::killedAtMs).map { body ->
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

    private fun startReservedRoster() {
        val batch = reservation ?: return
        reservation = null
        val online = arrivals.values.mapNotNull { entry -> plugin.server.getPlayer(UUID.fromString(entry.playerId)) }
            .filter(Player::isOnline).distinctBy(Player::getUniqueId)
        arrivals.clear()
        val currentSettings = settings()
        if (online.size < currentSettings.ttt.minimumPlayers) {
            arenaPool.release(batch.matchId)
            network.releaseReservation(batch)
            debug.event("reservation_cancelled", "match" to batch.matchId, "arrived" to online.size)
            return
        }
        val entries = batch.entries.filter { entry -> online.any { it.uniqueId.toString() == entry.playerId } }
        val engine = engine()
        recentAttacks.clear()
        combatLog.clear()
        roundReport = null
        cleanupLoot()
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
            preparationRemaining = currentSettings.ttt.preparationSeconds
            applyEventState(online, requireNotNull(match))
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
            match = created
            cancel(MatchEndReason.SHUTDOWN)
        }
    }

    private fun applyEventState(players: List<Player>, current: TttMatch) {
        val arena = requireNotNull(arenaPool.active()) { "Match ${current.matchId} has no arena lease" }
        players.forEachIndexed { index, player ->
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
            teleport(player, arena.spawns[index])
            player.showTitle(Title.title(
                locale.render("match.preparing-title", player),
                locale.render("match.preparing-subtitle", player),
                Title.Times.times(Duration.ofMillis(250), Duration.ofSeconds(3), Duration.ofMillis(500)),
            ))
            player.sendMessage(locale.render("match.preparing-guide", player))
            if (settings().ui.particles) {
                player.world.spawnParticle(Particle.END_ROD, player.location.add(0.0, 1.0, 0.0), 18, 0.7, 0.8, 0.7, 0.015)
            }
            player.saveData()
        }
        spawnLoot(current)
        hud.open(current)
        hud.update(current, preparationRemaining, settings().ttt.preparationSeconds)
    }

    private fun beginCountdown() {
        val current = match ?: return
        if (current.phase != MatchPhase.PREPARING) return
        match = engine().countdown(current)
        preparationRemaining = 0
        countdownRemaining = settings().ttt.countdownSeconds
        revealRoles(requireNotNull(match))
        hud.update(requireNotNull(match), countdownRemaining, settings().ttt.countdownSeconds)
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
        match = engine().activate(current, clock())
        countdownRemaining = 0
        requireNotNull(match).participants.values.mapNotNull { plugin.server.getPlayer(it.playerId) }.forEach { player ->
            player.sendMessage(locale.render("match.started", player))
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
        hud.update(requireNotNull(match), settings().ttt.roundSeconds, settings().ttt.roundSeconds)
        debug.event("match_active", "match" to current.matchId, "deadline" to match?.deadlineMs)
    }

    private fun tick() {
        val current = match ?: return
        when (current.phase) {
            MatchPhase.PREPARING -> {
                if (preparationRemaining <= 0) {
                    beginCountdown()
                    return
                }
                hud.update(current, preparationRemaining, settings().ttt.preparationSeconds)
                preparationRemaining--
            }
            MatchPhase.COUNTDOWN -> {
                if (countdownRemaining <= 0) {
                    activateRound()
                    return
                }
                hud.update(current, countdownRemaining, settings().ttt.countdownSeconds)
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
                hud.update(changed, remainingSeconds(changed).toInt(), settings().ttt.roundSeconds)
                if (outcome is MatchOutcome.Finished) resolve(changed)
            }
            else -> Unit
        }
    }

    private fun resolve(current: TttMatch) {
        if (current.phase != MatchPhase.RESOLVING) return
        match = current
        cancelMatchTasks(keepMainTick = true)
        hud.close()
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
            combat = combatLog.toList(),
        )
        current.participants.values.forEach { participant ->
            plugin.server.getPlayer(participant.playerId)?.let { player ->
                player.showTitle(Title.title(
                    locale.render(title, player),
                    locale.render(subtitle, player),
                    Title.Times.times(Duration.ofMillis(250), Duration.ofSeconds(4), Duration.ofMillis(750)),
                ))
                player.inventory.setItem(4, items.roundReport(player, current.matchId.toString()))
                player.sendMessage(locale.render("report.ready", player))
            }
            network.recordStats(participant.playerId) { it.record(current.matchId, participant, current.winner) }
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
        hud.close()
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
        cleanupLoot()
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
                        network.returnRecoveredPlayer(player, recovery)
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
        arenaPool.release(current.matchId)
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
            armorStand.customName(locale.render("body.unidentified", values = mapOf("player" to Component.text("???"))))
            armorStand.equipment.helmet = head
            armorStand.persistentDataContainer.set(bodyKey, PersistentDataType.STRING, bodyId.toString())
        }
        val lethal = combatLog.lastOrNull { it.victimId == participant.playerId && it.lethal }
        bodies[bodyId] = BodyRecord(
            bodyId, requireNotNull(match).matchId, participant.playerId, participant.playerName,
            participant.role, killerId, clock(), location, stand.uniqueId,
            weaponKey = lethal?.weapon ?: "environment",
            finalDamage = lethal?.finalDamage ?: 0.0,
            headshot = lethal?.headshot == true,
        )
        tasks += Tasks.scheduler.runLater(settings().ttt.bodyDespawnSeconds * 20L) {
            bodies.remove(bodyId)?.let { plugin.server.getEntity(it.entityId)?.remove() }
        }
    }

    override fun readBodyId(stand: ArmorStand): UUID? = stand.persistentDataContainer.get(bodyKey, PersistentDataType.STRING)
        ?.let { runCatching { UUID.fromString(it) }.getOrNull() }

    private fun cleanupBodies() {
        bodies.values.forEach { plugin.server.getEntity(it.entityId)?.remove() }
        bodies.clear()
    }

    private fun cleanupProjectiles() {
        smokeGrenades.clear()
        projectiles.forEach { plugin.server.getEntity(it)?.remove() }
        projectiles.clear()
    }

    private fun spawnLoot(current: TttMatch) {
        if (!settings().weapons.enabled) return
        val arena = arenaPool.active() ?: return
        cleanupLoot()
        val world = requireNotNull(plugin.server.getWorld(arena.world))
        val seed = current.matchId.mostSignificantBits xor current.matchId.leastSignificantBits
        val layout = if (arena.template == TttCitadelBlueprint.TEMPLATE) {
            TttCitadelLoot.layout(seed).map { spawn ->
                Triple(spawn.point.x, spawn.point.y, spawn.point.z) to (spawn.firearm to spawn.ammunition)
            }
        } else {
            importedLoot(arena.lootSpawns, seed)
        }
        layout.forEach { (coordinates, reward) ->
            val (firearm, ammunition) = reward
            val stack = firearm?.let { firearms.firearmItem(it, null, current.matchId.toString()) }
                ?: firearms.ammunition(null, current.matchId.toString(), ammunition)
            val location = Location(world, coordinates.first, coordinates.second, coordinates.third)
            lootScene.spawn(location, stack)
        }
        debug.event("loot_spawned", "match" to current.matchId, "arena" to arena.id, "entities" to lootScene.size)
    }

    private fun importedLoot(points: List<EventLocation>, seed: Long): List<Pair<Triple<Double, Double, Double>, Pair<FirearmId?, Int>>> {
        val shuffled = points.shuffled(Random(seed))
        val firearms = listOf(FirearmId.PISTOL, FirearmId.SMG, FirearmId.SHOTGUN, FirearmId.RIFLE)
        return shuffled.mapIndexed { index, point ->
            val reward = if (index % 3 == 2) null to 12 else firearms[(index / 2) % firearms.size] to 0
            Triple(point.x, point.y, point.z) to reward
        }
    }

    private fun cleanupLoot() {
        lootScene.clear()
        reloadTasks.values.forEach(ScheduledTask::cancel)
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
        val current = match ?: return
        val result = shooter.world.rayTrace(
            origin,
            direction,
            range,
            FluidCollisionMode.NEVER,
            true,
            0.12,
        ) { entity ->
            entity is Player && entity.uniqueId != shooter.uniqueId &&
                current.participant(entity.uniqueId)?.status == ParticipantStatus.ALIVE
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
            target.damage(baseDamage * if (headshot) 1.5 else 1.0, shooter)
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
        val current = match ?: return false
        if (smokeGrenades.launch(player, current.matchId) == null) return false
        consumeMainHand(player)
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

    private fun phaseRemainingSeconds(current: TttMatch?): Int = when (current?.phase) {
        MatchPhase.PREPARING -> preparationRemaining
        MatchPhase.COUNTDOWN -> countdownRemaining
        MatchPhase.ACTIVE -> remainingSeconds(current).toInt()
        else -> 0
    }

    private fun visibleAliveCount(current: TttMatch): Int = current.participants.values.count {
        it.status in setOf(ParticipantStatus.RESERVED, ParticipantStatus.ALIVE)
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
        reservation?.let { pending ->
            runCatching { network.releaseReservation(pending).get(2, TimeUnit.SECONDS) }
                .onFailure { plugin.logger.log(Level.SEVERE, "Could not preserve reservation returns for ${pending.matchId}", it) }
        }
        arenaPool.clear()
        reservation = null
        arrivals.clear()
        when (match?.phase) {
            MatchPhase.PREPARING, MatchPhase.COUNTDOWN, MatchPhase.ACTIVE -> cancel(MatchEndReason.SHUTDOWN)
            MatchPhase.RESOLVING, MatchPhase.CANCELLED -> beginRestoration()
            else -> Unit
        }
        cancelMatchTasks(keepMainTick = false)
        hud.close()
        cleanupBodies()
        cleanupProjectiles()
        cleanupLoot()
        smokeGrenades.close()
        lootScene.close()
        radarTasks.values.forEach(ScheduledTask::cancel)
        radarTasks.clear()
        cleanupRetryTask?.cancel()
        cleanupRetryTask = null
    }

    private fun Location.eventLocation(): EventLocation = EventLocation(world.name, x, y, z, yaw, pitch)

    companion object {
        private val LIVE_PHASES = setOf(MatchPhase.PREPARING, MatchPhase.COUNTDOWN, MatchPhase.ACTIVE)
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

private fun PlayerStateEscrow.pendingFor(playerId: UUID): Boolean = playerId in pendingPlayers()

internal fun lootAccessible(phase: MatchPhase?, status: ParticipantStatus?): Boolean =
    phase in setOf(MatchPhase.PREPARING, MatchPhase.COUNTDOWN, MatchPhase.ACTIVE) &&
        status in setOf(ParticipantStatus.RESERVED, ParticipantStatus.ALIVE)
