package ru.ruscrafting.events.domain

import java.util.UUID

/**
 * Small common contract between the Paper host and one game implementation.
 *
 * A new game owns its authoritative match model behind this boundary instead of
 * adding another set of mutable fields to the plugin service.
 */
interface EventGameRuntime<M> {
    val current: M?
    val matchId: UUID?
    val phase: MatchPhase?

    fun diagnostics(): EventRuntimeDiagnostics
    fun release()
}

data class EventRuntimeDiagnostics(
    val matchId: UUID?,
    val phase: MatchPhase?,
    val phaseSecondsRemaining: Int,
    val counters: Map<String, Int> = emptyMap(),
)

data class PhaseCountdownTick(
    val secondsRemaining: Int,
    val elapsed: Boolean,
)

/** Pure fail-closed recovery decision shared by game runtimes. */
object EventRecoveryGate {
    fun blocked(
        onlinePlayers: Set<UUID>,
        pendingPlayers: Set<UUID>,
        recoveredPlayers: Set<UUID>,
    ): Boolean = onlinePlayers.any { it in pendingPlayers || it !in recoveredPlayers }
}

/** Exact whole-second countdown shared by pre-round phases. */
internal class PhaseCountdown {
    private var owner: MatchPhase? = null
    private var remaining = 0

    fun start(phase: MatchPhase, seconds: Int) {
        require(seconds >= 0)
        owner = phase
        remaining = seconds
    }

    fun remaining(phase: MatchPhase?): Int = if (phase == owner) remaining else 0

    fun tick(phase: MatchPhase): PhaseCountdownTick {
        check(owner == phase) { "No countdown is active for $phase" }
        if (remaining > 0) remaining--
        return PhaseCountdownTick(remaining, remaining == 0)
    }

    fun clear() {
        owner = null
        remaining = 0
    }
}
