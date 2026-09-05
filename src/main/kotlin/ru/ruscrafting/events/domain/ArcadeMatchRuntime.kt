package ru.ruscrafting.events.domain

import java.util.UUID
import ru.arc.network.BackendServerId
import ru.arc.network.NetworkPlayerName
import kotlin.math.ceil

enum class EventMode(val id: String) {
    TTT("ttt"),
    GUN_GAME("gungame"),
    DISASTERS("disasters");

    companion object {
        fun fromId(id: String): EventMode? = entries.firstOrNull { it.id == id.lowercase() }
    }
}

data class ArcadeRules(
    val minimumPlayers: Int = 3,
    val maximumPlayers: Int = 16,
    val preparationSeconds: Int = 15,
    val countdownSeconds: Int = 5,
    val roundSeconds: Int = 360,
    val postRoundSeconds: Int = 8,
    val respawnSeconds: Int = 3,
    val spawnProtectionSeconds: Int = 2,
    val disasterSeconds: Int = 25,
    val intermissionSeconds: Int = 5,
    val disasterRounds: Int = 6,
) {
    fun validated(): ArcadeRules = apply {
        require(minimumPlayers in 2..maximumPlayers && maximumPlayers in 2..16)
        require(preparationSeconds in 0..60 && countdownSeconds in 0..60)
        require(roundSeconds in 30..1800 && postRoundSeconds in 0..60)
        require(respawnSeconds in 1..15 && spawnProtectionSeconds in 0..5)
        require(disasterSeconds in 5..120 && intermissionSeconds in 3..30)
        require(disasterRounds in 1..20)
    }
}

data class ArcadePlayer(
    val playerId: UUID,
    val playerName: String,
    val originServer: String,
    val status: ParticipantStatus = ParticipantStatus.RESERVED,
    val kills: Int = 0,
    val deaths: Int = 0,
    val score: Int = 0,
    val stage: Int = 0,
    val respawnAtMs: Long? = null,
    val protectedUntilMs: Long? = null,
)

data class ArcadeMatch(
    val matchId: UUID,
    val revision: Long,
    val mode: EventMode,
    val phase: MatchPhase,
    val participants: Map<UUID, ArcadePlayer>,
    val rules: ArcadeRules,
    val createdAtMs: Long,
    val activeAtMs: Long? = null,
    val deadlineMs: Long? = null,
    val winners: Set<UUID> = emptySet(),
    val endReason: MatchEndReason? = null,
    val disasterRound: Int = 0,
    val disasterActive: Boolean = false,
)

class ArcadeMatchRuntime(
    private val clock: () -> Long = System::currentTimeMillis,
) : EventGameRuntime<ArcadeMatch> {
    @Volatile override var current: ArcadeMatch? = null
        private set
    override val matchId: UUID? get() = current?.matchId
    override val phase: MatchPhase? get() = current?.phase

    fun start(matchId: UUID, mode: EventMode, players: List<QueuedPlayer>, rules: ArcadeRules = ArcadeRules()): ArcadeMatch {
        check(current == null) { "A match is already active" }
        require(mode != EventMode.TTT) { "Arcade runtime does not own TTT" }
        rules.validated()
        require(players.size in rules.minimumPlayers..rules.maximumPlayers)
        require(players.map { it.playerId }.distinct().size == players.size)
        players.forEach {
            NetworkPlayerName.of(it.playerName)
            BackendServerId.of(it.originServer)
        }
        val now = clock()
        return ArcadeMatch(
            matchId, 0, mode, MatchPhase.PREPARING,
            players.associate { it.playerId to ArcadePlayer(it.playerId, it.playerName, it.originServer) },
            rules, now, deadlineMs = now + rules.preparationSeconds * 1000L,
        ).also { current = it }
    }

    fun tick(): ArcadeMatch {
        var match = requireCurrent()
        val now = clock()
        if (match.phase == MatchPhase.PREPARING && now >= requireNotNull(match.deadlineMs)) {
            match = change(match, phase = MatchPhase.COUNTDOWN, deadlineMs = now + match.rules.countdownSeconds * 1000L)
        }
        if (match.phase == MatchPhase.COUNTDOWN && now >= requireNotNull(match.deadlineMs)) {
            val participants = match.participants.mapValues { (_, p) ->
                if (p.status == ParticipantStatus.RESERVED) p.copy(status = ParticipantStatus.ALIVE, protectedUntilMs = if (match.mode == EventMode.GUN_GAME) now + match.rules.spawnProtectionSeconds * 1000L else null) else p
            }
            match = change(match, phase = MatchPhase.ACTIVE, participants = participants,
                activeAtMs = now, deadlineMs = now + match.rules.roundSeconds * 1000L)
        }
        if (match.phase == MatchPhase.ACTIVE && match.mode == EventMode.GUN_GAME && now >= requireNotNull(match.deadlineMs)) {
            match = resolveGunGameTimeout(match)
        }
        current = match
        return match
    }

    fun eliminate(victimId: UUID, killerId: UUID?, knifeKill: Boolean = false): ArcadeMatch {
        val match = requireCurrent()
        require(match.phase == MatchPhase.ACTIVE)
        require(match.mode in setOf(EventMode.GUN_GAME, EventMode.DISASTERS))
        val victim = requireNotNull(match.participants[victimId])
        if (victim.status != ParticipantStatus.ALIVE) return match
        if (victim.protectedUntilMs?.let { clock() < it } == true) return match
        val now = clock()
        val players = match.participants.toMutableMap()
        players[victimId] = victim.copy(status = ParticipantStatus.DEAD, deaths = victim.deaths + 1, respawnAtMs = now + match.rules.respawnSeconds * 1000L, protectedUntilMs = null)
        if (match.mode == EventMode.DISASTERS) return update(match, players)
        if (killerId != null && killerId != victimId) {
            val killer = players[killerId]
            if (killer?.status == ParticipantStatus.ALIVE) {
                val validAttack = (!knifeKill && killer.stage < FirearmId.entries.size) ||
                    (knifeKill && killer.stage == FirearmId.entries.size)
                if (validAttack) {
                    val nextStage = if (!knifeKill) killer.stage + 1 else killer.stage
                    players[killerId] = killer.copy(kills = killer.kills + 1, stage = nextStage)
                    if (knifeKill) return finish(match, players, setOf(killerId), MatchEndReason.ELIMINATION)
                }
            }
        }
        return update(match, players)
    }

    fun respawn(playerId: UUID): ArcadeMatch {
        val match = requireCurrent()
        require(match.phase == MatchPhase.ACTIVE)
        val player = requireNotNull(match.participants[playerId])
        require(player.status == ParticipantStatus.DEAD)
        require(player.respawnAtMs?.let { clock() >= it } == true) { "Respawn is not due" }
        val until = clock() + match.rules.spawnProtectionSeconds * 1000L
        return update(match, match.participants + (playerId to player.copy(status = ParticipantStatus.ALIVE, respawnAtMs = null, protectedUntilMs = until)))
    }

    fun disconnect(playerId: UUID): ArcadeMatch {
        val match = requireCurrent()
        if (match.phase !in setOf(MatchPhase.PREPARING, MatchPhase.COUNTDOWN, MatchPhase.ACTIVE)) return match
        val player = requireNotNull(match.participants[playerId])
        if (player.status !in setOf(ParticipantStatus.RESERVED, ParticipantStatus.ALIVE, ParticipantStatus.DEAD)) return match
        val changed = update(match, match.participants + (playerId to player.copy(status = ParticipantStatus.DISCONNECTED)))
        return if (changed.phase in setOf(MatchPhase.PREPARING, MatchPhase.COUNTDOWN) && connected(changed) < changed.rules.minimumPlayers)
            cancel(MatchEndReason.INSUFFICIENT_PLAYERS) else if (changed.phase == MatchPhase.ACTIVE && connected(changed) < 2)
            cancel(MatchEndReason.INSUFFICIENT_PLAYERS) else changed
    }

    fun beginDisaster(): ArcadeMatch {
        val match = requireCurrent()
        require(match.mode == EventMode.DISASTERS && match.phase == MatchPhase.ACTIVE)
        require(!match.disasterActive && match.disasterRound < match.rules.disasterRounds)
        return change(match, disasterActive = true, participants = match.participants.mapValues { (_, p) ->
            if (p.status == ParticipantStatus.RESERVED) p.copy(status = ParticipantStatus.ALIVE) else p
        })
    }

    fun completeDisaster(): ArcadeMatch {
        val match = requireCurrent()
        require(match.mode == EventMode.DISASTERS && match.phase == MatchPhase.ACTIVE && match.disasterActive)
        val nextRound = match.disasterRound + 1
        val players = match.participants.mapValues { (_, p) ->
            when (p.status) {
                ParticipantStatus.ALIVE -> p.copy(score = p.score + 1, status = ParticipantStatus.RESERVED)
                ParticipantStatus.DEAD -> p.copy(status = ParticipantStatus.RESERVED)
                else -> p
            }
        }
        val changed = change(match, participants = players, disasterRound = nextRound, disasterActive = false)
        return if (nextRound >= match.rules.disasterRounds) resolveDisasters(changed) else changed
    }

    fun cancel(reason: MatchEndReason): ArcadeMatch {
        val match = requireCurrent()
        require(reason in setOf(MatchEndReason.ADMIN, MatchEndReason.SHUTDOWN, MatchEndReason.INSUFFICIENT_PLAYERS))
        require(match.phase in setOf(MatchPhase.PREPARING, MatchPhase.COUNTDOWN, MatchPhase.ACTIVE))
        return change(match, phase = MatchPhase.CANCELLED, deadlineMs = null, endReason = reason)
    }
    fun beginRestoring(): ArcadeMatch {
        val match = requireCurrent()
        require(match.phase in setOf(MatchPhase.RESOLVING, MatchPhase.CANCELLED))
        return change(match, phase = MatchPhase.RESTORING, deadlineMs = null)
    }
    fun markRecoveryApplied(playerId: UUID): ArcadeMatch {
        val match = requireCurrent()
        val player = requireNotNull(match.participants[playerId])
        if (player.status == ParticipantStatus.RESTORED) return match
        require(match.phase in setOf(MatchPhase.PREPARING, MatchPhase.COUNTDOWN, MatchPhase.ACTIVE, MatchPhase.RESOLVING, MatchPhase.CANCELLED, MatchPhase.RESTORING))
        val players = match.participants + (playerId to player.copy(status = ParticipantStatus.RESTORED))
        val complete = match.phase == MatchPhase.RESTORING && players.values.all { it.status in setOf(ParticipantStatus.RESTORED, ParticipantStatus.DISCONNECTED) }
        return change(match, participants = players, phase = if (complete) MatchPhase.COMPLETED else match.phase)
    }

    fun phaseSecondsRemaining(): Int = current?.takeIf { it.phase in setOf(MatchPhase.PREPARING, MatchPhase.COUNTDOWN, MatchPhase.ACTIVE) }?.let {
        maxOf(0, ceil(((it.deadlineMs ?: return@let 0) - clock()) / 1000.0).toInt())
    } ?: 0
    override fun diagnostics() = EventRuntimeDiagnostics(matchId, phase, phaseSecondsRemaining(), mapOf("players" to (current?.participants?.size ?: 0), "round" to (current?.disasterRound ?: 0)))
    override fun release() {
        require(current?.phase in setOf(null, MatchPhase.RESTORING, MatchPhase.COMPLETED))
        current = null
    }

    private fun resolveGunGameTimeout(match: ArcadeMatch): ArcadeMatch {
        val eligible = match.participants.values.filter { it.status !in setOf(ParticipantStatus.DISCONNECTED, ParticipantStatus.RESTORED) }
        val best = eligible.maxWithOrNull(compareBy<ArcadePlayer> { it.stage }.thenBy { it.kills })
        val winners = if (best == null) emptySet() else eligible.filter { it.stage == best.stage && it.kills == best.kills }.map { it.playerId }.toSet()
        return finish(match, match.participants, winners, MatchEndReason.TIMEOUT)
    }
    private fun resolveDisasters(match: ArcadeMatch): ArcadeMatch {
        val eligible = match.participants.values.filter { it.status !in setOf(ParticipantStatus.DISCONNECTED, ParticipantStatus.RESTORED) }
        val top = eligible.maxOfOrNull { it.score } ?: 0
        return finish(match, match.participants, eligible.filter { it.score == top }.map { it.playerId }.toSet(), MatchEndReason.ELIMINATION)
    }
    private fun finish(match: ArcadeMatch, players: Map<UUID, ArcadePlayer>, winners: Set<UUID>, reason: MatchEndReason) = change(match, phase = MatchPhase.RESOLVING, participants = players, deadlineMs = null, winners = winners, endReason = reason)
    private fun connected(match: ArcadeMatch) = match.participants.values.count { it.status != ParticipantStatus.DISCONNECTED && it.status != ParticipantStatus.RESTORED }
    private fun update(match: ArcadeMatch, participants: Map<UUID, ArcadePlayer>) = change(match, participants = participants)
    private fun change(match: ArcadeMatch, phase: MatchPhase = match.phase, participants: Map<UUID, ArcadePlayer> = match.participants, activeAtMs: Long? = match.activeAtMs, deadlineMs: Long? = match.deadlineMs, winners: Set<UUID> = match.winners, endReason: MatchEndReason? = match.endReason, disasterRound: Int = match.disasterRound, disasterActive: Boolean = match.disasterActive) = match.copy(revision = match.revision + 1, phase = phase, participants = participants, activeAtMs = activeAtMs, deadlineMs = deadlineMs, winners = winners, endReason = endReason, disasterRound = disasterRound, disasterActive = disasterActive).also { current = it }
    private fun requireCurrent() = checkNotNull(current) { "No match is active" }
}
