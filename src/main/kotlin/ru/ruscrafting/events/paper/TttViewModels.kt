package ru.ruscrafting.events.paper

import ru.ruscrafting.events.domain.CombatRecord
import ru.ruscrafting.events.domain.MatchEndReason
import ru.ruscrafting.events.domain.RosterEntry
import ru.ruscrafting.events.domain.TttRole
import ru.ruscrafting.events.domain.TttTeam
import java.util.UUID

data class BodyEvidenceView(
    val bodyId: UUID,
    val victimId: UUID,
    val victimName: String,
    val role: TttRole,
    val secondsSinceDeath: Long,
    val weaponKey: String,
    val finalDamage: Double,
    val headshot: Boolean,
    val dnaAvailable: Boolean,
    val detectiveCalled: Boolean,
)

data class RoundParticipantView(
    val playerId: UUID,
    val playerName: String,
    val role: TttRole,
    val kills: Int,
    val deaths: Int,
    val damage: Double,
    val friendlyDamage: Double,
)

data class RoundReportView(
    val matchId: UUID,
    val winner: TttTeam,
    val reason: MatchEndReason,
    val durationSeconds: Long,
    val participants: List<RoundParticipantView>,
    val combat: List<CombatRecord>,
)

data class RosterView(val entries: List<RosterEntry>)

data class PendingHitContext(val attackerId: UUID, val victimId: UUID, val weaponKey: String, val headshot: Boolean)
