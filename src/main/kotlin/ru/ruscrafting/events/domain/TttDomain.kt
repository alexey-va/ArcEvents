package ru.ruscrafting.events.domain

import ru.arc.network.BackendServerId
import ru.arc.network.NetworkPlayerName
import java.util.UUID
import kotlin.math.max
import kotlin.random.Random

enum class TttRole { INNOCENT, TRAITOR, DETECTIVE }
enum class TttTeam { INNOCENTS, TRAITORS }
enum class MatchPhase { RESERVED, PREPARING, COUNTDOWN, ACTIVE, RESOLVING, RESTORING, COMPLETED, CANCELLED }
enum class ParticipantStatus { RESERVED, ALIVE, DEAD, DISCONNECTED, RESTORED }
enum class MatchEndReason { ELIMINATION, TIMEOUT, ADMIN, SHUTDOWN, INSUFFICIENT_PLAYERS }

val TttRole.team: TttTeam
    get() = if (this == TttRole.TRAITOR) TttTeam.TRAITORS else TttTeam.INNOCENTS

data class TttParticipant(
    val playerId: UUID,
    val playerName: String,
    val originServer: String,
    val role: TttRole,
    val status: ParticipantStatus,
    val credits: Int,
    val kills: Int = 0,
    val deaths: Int = 0,
    val friendlyKills: Int = 0,
    val damageDealt: Double = 0.0,
    val friendlyDamage: Double = 0.0,
) {
    fun validated(): TttParticipant = apply {
        NetworkPlayerName.of(playerName)
        BackendServerId.of(originServer)
        require(credits in 0..64 && kills in 0..64 && deaths in 0..1 && friendlyKills in 0..64)
        require(damageDealt.isFinite() && damageDealt in 0.0..100_000.0)
        require(friendlyDamage.isFinite() && friendlyDamage in 0.0..damageDealt)
    }
}

data class TttMatch(
    val matchId: UUID,
    val revision: Long,
    val phase: MatchPhase,
    val participants: Map<UUID, TttParticipant>,
    val createdAtMs: Long,
    val activeAtMs: Long? = null,
    val deadlineMs: Long? = null,
    val winner: TttTeam? = null,
    val endReason: MatchEndReason? = null,
) {
    fun validated(minimumPlayers: Int, maximumPlayers: Int): TttMatch = apply {
        require(revision >= 0)
        require(participants.size in minimumPlayers..maximumPlayers)
        require(participants.keys == participants.values.map(TttParticipant::playerId).toSet())
        participants.values.forEach(TttParticipant::validated)
        require(participants.values.any { it.role == TttRole.TRAITOR })
        require(participants.values.any { it.role.team == TttTeam.INNOCENTS })
        if (phase == MatchPhase.ACTIVE) require(activeAtMs != null && deadlineMs != null && winner == null)
        if (phase in setOf(MatchPhase.RESOLVING, MatchPhase.RESTORING, MatchPhase.COMPLETED)) {
            require(winner != null || endReason in setOf(MatchEndReason.ADMIN, MatchEndReason.SHUTDOWN, MatchEndReason.INSUFFICIENT_PLAYERS))
        }
    }

    fun alive(): List<TttParticipant> = participants.values.filter { it.status == ParticipantStatus.ALIVE }
    fun participant(playerId: UUID): TttParticipant? = participants[playerId]
}

data class RoleAllocationSettings(
    val traitorPlayerRatio: Int,
    val detectiveMinimumPlayers: Int,
    val traitorCredits: Int,
    val detectiveCredits: Int,
)

object TttRoleAllocator {
    fun allocate(
        players: List<QueuedPlayer>,
        settings: RoleAllocationSettings,
        seed: Long,
    ): Map<UUID, TttParticipant> {
        require(players.size >= 4)
        require(players.map(QueuedPlayer::playerId).distinct().size == players.size)
        val shuffled = players.sortedBy { it.playerId.toString() }.shuffled(Random(seed))
        val traitorCount = max(1, players.size / settings.traitorPlayerRatio).coerceAtMost(players.size - 1)
        val detectiveCount = when {
            players.size < settings.detectiveMinimumPlayers -> 0
            players.size >= settings.detectiveMinimumPlayers * 2 -> 2
            else -> 1
        }.coerceAtMost(players.size - traitorCount - 1)
        val traitors = shuffled.take(traitorCount).map(QueuedPlayer::playerId).toSet()
        val detectives = shuffled.drop(traitorCount).take(detectiveCount).map(QueuedPlayer::playerId).toSet()
        return shuffled.associate { player ->
            val role = when (player.playerId) {
                in traitors -> TttRole.TRAITOR
                in detectives -> TttRole.DETECTIVE
                else -> TttRole.INNOCENT
            }
            player.playerId to TttParticipant(
                playerId = player.playerId,
                playerName = player.playerName,
                originServer = player.originServer,
                role = role,
                status = ParticipantStatus.RESERVED,
                credits = when (role) {
                    TttRole.TRAITOR -> settings.traitorCredits
                    TttRole.DETECTIVE -> settings.detectiveCredits
                    TttRole.INNOCENT -> 0
                },
            ).validated()
        }
    }
}

data class QueuedPlayer(
    val playerId: UUID,
    val playerName: String,
    val originServer: String,
    val joinedAtMs: Long,
)

sealed interface MatchOutcome {
    data object Continue : MatchOutcome
    data class Finished(val winner: TttTeam, val reason: MatchEndReason) : MatchOutcome
}

class TttMatchEngine(
    private val minimumPlayers: Int,
    private val maximumPlayers: Int,
    private val roundDurationMs: Long,
) {
    fun create(matchId: UUID, players: List<QueuedPlayer>, allocation: RoleAllocationSettings, seed: Long, nowMs: Long): TttMatch =
        TttMatch(
            matchId = matchId,
            revision = 0,
            phase = MatchPhase.RESERVED,
            participants = TttRoleAllocator.allocate(players, allocation, seed),
            createdAtMs = nowMs,
        ).validated(minimumPlayers, maximumPlayers)

    fun prepare(match: TttMatch): TttMatch = transition(match, MatchPhase.RESERVED, MatchPhase.PREPARING)

    fun countdown(match: TttMatch): TttMatch {
        require(match.phase == MatchPhase.PREPARING)
        val participants = match.participants.mapValues { (_, value) ->
            if (value.status == ParticipantStatus.RESERVED) value.copy(status = ParticipantStatus.ALIVE) else value
        }
        return match.copy(revision = match.revision + 1, phase = MatchPhase.COUNTDOWN, participants = participants)
            .validated(minimumPlayers, maximumPlayers)
    }

    fun activate(match: TttMatch, nowMs: Long): TttMatch {
        require(match.phase == MatchPhase.COUNTDOWN)
        return match.copy(
            revision = match.revision + 1,
            phase = MatchPhase.ACTIVE,
            activeAtMs = nowMs,
            deadlineMs = nowMs + roundDurationMs,
        ).validated(minimumPlayers, maximumPlayers)
    }

    fun eliminate(match: TttMatch, victimId: UUID, killerId: UUID?): Pair<TttMatch, MatchOutcome> {
        require(match.phase == MatchPhase.ACTIVE)
        val victim = requireNotNull(match.participants[victimId])
        if (victim.status != ParticipantStatus.ALIVE) return match to MatchOutcome.Continue
        val participants = match.participants.toMutableMap()
        participants[victimId] = victim.copy(status = ParticipantStatus.DEAD, deaths = 1)
        if (killerId != null && killerId != victimId) {
            participants[killerId]?.takeIf { it.status == ParticipantStatus.ALIVE }?.let { killer ->
                participants[killerId] = killer.copy(
                    kills = killer.kills + 1,
                    friendlyKills = killer.friendlyKills + if (killer.role.team == victim.role.team) 1 else 0,
                )
            }
        }
        val changed = match.copy(revision = match.revision + 1, participants = participants)
        val outcome = winner(changed)
        return if (outcome is MatchOutcome.Finished) finish(changed, outcome.winner, outcome.reason) to outcome else changed to outcome
    }

    fun recordDamage(match: TttMatch, victimId: UUID, attackerId: UUID?, finalDamage: Double): TttMatch {
        require(match.phase == MatchPhase.ACTIVE)
        require(finalDamage.isFinite() && finalDamage >= 0.0)
        if (attackerId == null || attackerId == victimId || finalDamage == 0.0) return match
        val victim = match.participant(victimId) ?: return match
        val attacker = match.participant(attackerId) ?: return match
        if (victim.status != ParticipantStatus.ALIVE || attacker.status != ParticipantStatus.ALIVE) return match
        val friendly = attacker.role.team == victim.role.team
        val updated = attacker.copy(
            damageDealt = (attacker.damageDealt + finalDamage).coerceAtMost(100_000.0),
            friendlyDamage = (attacker.friendlyDamage + if (friendly) finalDamage else 0.0).coerceAtMost(100_000.0),
        )
        return match.copy(
            revision = match.revision + 1,
            participants = match.participants + (attackerId to updated),
        ).validated(minimumPlayers, maximumPlayers)
    }

    fun disconnect(match: TttMatch, playerId: UUID): Pair<TttMatch, MatchOutcome> {
        require(match.phase in setOf(MatchPhase.PREPARING, MatchPhase.COUNTDOWN, MatchPhase.ACTIVE))
        val player = requireNotNull(match.participants[playerId])
        if (player.status !in setOf(ParticipantStatus.RESERVED, ParticipantStatus.ALIVE)) return match to MatchOutcome.Continue
        val changed = match.copy(
            revision = match.revision + 1,
            participants = match.participants + (playerId to player.copy(status = ParticipantStatus.DISCONNECTED, deaths = if (match.phase == MatchPhase.ACTIVE) 1 else 0)),
        )
        if (match.phase != MatchPhase.ACTIVE) return changed to MatchOutcome.Continue
        val outcome = winner(changed)
        return if (outcome is MatchOutcome.Finished) finish(changed, outcome.winner, outcome.reason) to outcome else changed to outcome
    }

    fun tick(match: TttMatch, nowMs: Long): Pair<TttMatch, MatchOutcome> {
        if (match.phase != MatchPhase.ACTIVE || nowMs < requireNotNull(match.deadlineMs)) return match to MatchOutcome.Continue
        val outcome = MatchOutcome.Finished(TttTeam.INNOCENTS, MatchEndReason.TIMEOUT)
        return finish(match, outcome.winner, outcome.reason) to outcome
    }

    fun cancel(match: TttMatch, reason: MatchEndReason): TttMatch {
        require(reason in setOf(MatchEndReason.ADMIN, MatchEndReason.SHUTDOWN, MatchEndReason.INSUFFICIENT_PLAYERS))
        require(match.phase !in setOf(MatchPhase.COMPLETED, MatchPhase.CANCELLED))
        return match.copy(revision = match.revision + 1, phase = MatchPhase.CANCELLED, endReason = reason)
    }

    fun restoring(match: TttMatch): TttMatch {
        require(match.phase in setOf(MatchPhase.RESOLVING, MatchPhase.CANCELLED))
        return match.copy(revision = match.revision + 1, phase = MatchPhase.RESTORING)
    }

    fun restored(match: TttMatch, playerId: UUID): TttMatch {
        require(match.phase == MatchPhase.RESTORING)
        val participant = requireNotNull(match.participants[playerId])
        val participants = match.participants + (playerId to participant.copy(status = ParticipantStatus.RESTORED))
        val complete = participants.values.all { it.status == ParticipantStatus.RESTORED || it.status == ParticipantStatus.DISCONNECTED }
        return match.copy(revision = match.revision + 1, phase = if (complete) MatchPhase.COMPLETED else match.phase, participants = participants)
    }

    private fun winner(match: TttMatch): MatchOutcome {
        val alive = match.alive()
        val traitors = alive.count { it.role == TttRole.TRAITOR }
        val innocents = alive.size - traitors
        return when {
            traitors == 0 -> MatchOutcome.Finished(TttTeam.INNOCENTS, MatchEndReason.ELIMINATION)
            traitors >= innocents -> MatchOutcome.Finished(TttTeam.TRAITORS, MatchEndReason.ELIMINATION)
            else -> MatchOutcome.Continue
        }
    }

    private fun finish(match: TttMatch, winner: TttTeam, reason: MatchEndReason): TttMatch = match.copy(
        revision = match.revision + 1,
        phase = MatchPhase.RESOLVING,
        winner = winner,
        endReason = reason,
    ).validated(minimumPlayers, maximumPlayers)

    private fun transition(match: TttMatch, from: MatchPhase, to: MatchPhase): TttMatch {
        require(match.phase == from)
        return match.copy(revision = match.revision + 1, phase = to).validated(minimumPlayers, maximumPlayers)
    }
}

data class PlayerEventStats(
    val revision: Long = 0,
    val lastMatchId: String? = null,
    val matches: Int = 0,
    val wins: Int = 0,
    val traitorWins: Int = 0,
    val innocentWins: Int = 0,
    val kills: Int = 0,
    val deaths: Int = 0,
    val karma: Int = 1000,
) {
    fun validated(): PlayerEventStats = apply {
        require(revision >= 0)
        lastMatchId?.let { require(UUID.fromString(it).toString() == it) }
        require(listOf(matches, wins, traitorWins, innocentWins, kills, deaths).all { it in 0..1_000_000 })
        require(wins <= matches && traitorWins + innocentWins <= wins)
        require(karma in 0..2000)
    }

    fun record(matchId: UUID, participant: TttParticipant, winner: TttTeam?): PlayerEventStats {
        if (lastMatchId == matchId.toString()) return this
        val won = winner != null && participant.role.team == winner
        val friendlyPenalty = participant.friendlyKills * 75 + kotlin.math.ceil(participant.friendlyDamage * 2.0).toInt()
        val cleanRoundRecovery = if (participant.friendlyDamage < 0.5 && participant.friendlyKills == 0) 20 else 0
        return copy(
            revision = revision + 1,
            lastMatchId = matchId.toString(),
            matches = matches + 1,
            wins = wins + if (won) 1 else 0,
            traitorWins = traitorWins + if (won && participant.role == TttRole.TRAITOR) 1 else 0,
            innocentWins = innocentWins + if (won && participant.role != TttRole.TRAITOR) 1 else 0,
            kills = kills + participant.kills,
            deaths = deaths + participant.deaths,
            karma = (karma + cleanRoundRecovery - friendlyPenalty).coerceIn(100, 1000),
        ).validated()
    }

    fun damageMultiplier(): Double = (0.5 + karma.coerceIn(0, 1000) / 2_000.0).coerceIn(0.5, 1.0)
}
