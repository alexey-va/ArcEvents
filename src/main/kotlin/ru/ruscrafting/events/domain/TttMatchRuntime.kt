package ru.ruscrafting.events.domain

import java.util.UUID

data class TttElimination(
    val match: TttMatch,
    val outcome: MatchOutcome,
    val effectiveKillerId: UUID?,
)

/**
 * Owns all authoritative, platform-neutral state for one TTT match.
 *
 * Bukkit presentation and inventory escrow stay in the Paper adapter. Match
 * transitions, phase clocks, combat history and recent-attacker attribution do
 * not leak into it, giving future games one clear runtime seam to implement.
 */
class TttMatchRuntime(
    private val engine: () -> TttMatchEngine,
    private val clock: () -> Long = System::currentTimeMillis,
) : EventGameRuntime<TttMatch> {
    @Volatile
    override var current: TttMatch? = null
        private set
    private val phaseCountdown = PhaseCountdown()
    private val recentAttacks = RecentAttackLedger(clock = clock)
    private val combatLog = mutableListOf<CombatRecord>()
    private var combatSequence = 0

    override val matchId: UUID?
        get() = current?.matchId
    override val phase: MatchPhase?
        get() = current?.phase

    fun create(
        matchId: UUID,
        players: List<QueuedPlayer>,
        allocation: RoleAllocationSettings,
        seed: Long,
        nowMs: Long = clock(),
    ): TttMatch = engine().create(matchId, players, allocation, seed, nowMs)

    fun prepare(created: TttMatch, seconds: Int): TttMatch {
        check(current == null) { "A match is already active" }
        recentAttacks.clear()
        combatLog.clear()
        combatSequence = 0
        current = engine().prepare(created)
        phaseCountdown.start(MatchPhase.PREPARING, seconds)
        return requireNotNull(current)
    }

    fun beginCountdown(seconds: Int): TttMatch {
        current = engine().countdown(requireCurrent())
        phaseCountdown.start(MatchPhase.COUNTDOWN, seconds)
        return requireNotNull(current)
    }

    fun activate(): TttMatch {
        current = engine().activate(requireCurrent(), clock())
        phaseCountdown.clear()
        return requireNotNull(current)
    }

    fun tickPhase(): PhaseCountdownTick = phaseCountdown.tick(requireNotNull(phase))

    fun tickActive(): Pair<TttMatch, MatchOutcome> {
        val result = engine().tick(requireCurrent(), clock())
        current = result.first
        return result
    }

    fun disconnect(playerId: UUID): Pair<TttMatch, MatchOutcome> {
        val result = engine().disconnect(requireCurrent(), playerId)
        current = result.first
        return result
    }

    fun recordAttack(victimId: UUID, attackerId: UUID?) {
        val match = current ?: return
        if (match.phase != MatchPhase.ACTIVE || attackerId == null) return
        if (match.participant(victimId)?.status != ParticipantStatus.ALIVE ||
            match.participant(attackerId)?.status != ParticipantStatus.ALIVE
        ) return
        recentAttacks.record(match.matchId, victimId, attackerId)
    }

    fun recordDamage(record: CombatRecord, victimId: UUID, attackerId: UUID?, finalDamage: Double): TttMatch {
        require(record.sequence == combatSequence + 1) { "Combat sequence is not monotonic" }
        val validatedRecord = record.validated()
        val changed = engine().recordDamage(requireCurrent(), victimId, attackerId, finalDamage)
        current = changed
        combatLog += validatedRecord
        combatSequence = validatedRecord.sequence
        if (combatLog.size > MAX_COMBAT_RECORDS) combatLog.removeAt(0)
        return changed
    }

    fun eliminate(victimId: UUID, killerId: UUID?): TttElimination {
        val match = requireCurrent()
        val effectiveKiller = killerId ?: recentAttacks.consume(match.matchId, victimId)
        recentAttacks.forget(victimId)
        val (changed, outcome) = engine().eliminate(match, victimId, effectiveKiller)
        val rewarded = rewardKiller(changed, victimId, effectiveKiller)
        current = rewarded
        return TttElimination(rewarded, outcome, effectiveKiller)
    }

    fun replace(updated: TttMatch): TttMatch {
        val before = requireCurrent()
        require(updated.matchId == before.matchId) { "Cannot replace a match with another id" }
        require(updated == before || updated.revision > before.revision) { "Match replacement requires a newer revision" }
        current = updated
        return updated
    }

    fun cancel(reason: MatchEndReason): TttMatch {
        current = engine().cancel(requireCurrent(), reason)
        phaseCountdown.clear()
        return requireNotNull(current)
    }

    fun beginRestoring(): TttMatch {
        current = engine().restoring(requireCurrent())
        phaseCountdown.clear()
        return requireNotNull(current)
    }

    fun markRestored(playerId: UUID): TttMatch {
        current = engine().restored(requireCurrent(), playerId)
        return requireNotNull(current)
    }

    fun markRecoveryApplied(playerId: UUID): TttMatch {
        val match = requireCurrent()
        val participant = requireNotNull(match.participant(playerId))
        if (participant.status == ParticipantStatus.RESTORED) return match
        if (match.phase == MatchPhase.RESTORING) return markRestored(playerId)
        val changed = match.copy(
            revision = match.revision + 1,
            participants = match.participants + (playerId to participant.copy(status = ParticipantStatus.RESTORED)),
        )
        current = changed
        return changed
    }

    fun phaseSecondsRemaining(): Int = phaseCountdown.remaining(phase)

    fun combatRecords(): List<CombatRecord> = combatLog.toList()

    fun combatRecordCount(): Int = combatLog.size

    fun nextCombatSequence(): Int = combatSequence + 1

    fun clearTransientState() {
        check(current == null) { "Cannot clear a live match" }
        phaseCountdown.clear()
        recentAttacks.clear()
        combatLog.clear()
        combatSequence = 0
    }

    override fun diagnostics(): EventRuntimeDiagnostics = EventRuntimeDiagnostics(
        matchId = matchId,
        phase = phase,
        phaseSecondsRemaining = phaseSecondsRemaining(),
        counters = mapOf("combat" to combatLog.size),
    )

    override fun release() {
        require(current?.phase in setOf(null, MatchPhase.RESTORING, MatchPhase.COMPLETED)) {
            "Cannot release an unfinished match"
        }
        current = null
        phaseCountdown.clear()
        recentAttacks.clear()
        combatLog.clear()
        combatSequence = 0
    }

    private fun requireCurrent(): TttMatch = checkNotNull(current) { "No match is active" }

    private fun rewardKiller(match: TttMatch, victimId: UUID, killerId: UUID?): TttMatch {
        if (killerId == null || killerId == victimId) return match
        val victim = match.participant(victimId) ?: return match
        val killer = match.participant(killerId) ?: return match
        if (killer.status != ParticipantStatus.ALIVE || killer.role.team == victim.role.team) return match
        return match.copy(
            revision = match.revision + 1,
            participants = match.participants + (killerId to killer.copy(credits = (killer.credits + 1).coerceAtMost(64))),
        )
    }

    private companion object {
        const val MAX_COMBAT_RECORDS = 512
    }
}
