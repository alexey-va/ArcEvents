package ru.ruscrafting.events.paper.apocalypse

internal enum class ApocalypseJoinRecovery { NONE, RESUME, ORIGIN, QUARANTINE }

/** A returned record may retain its root as a receipt for a failed native playerdata save. */
internal fun apocalypseJoinRecovery(phase: ApocalypsePlayerPhase?, hasOrigin: Boolean,
    savedSession: String?, nativeSession: String?): ApocalypseJoinRecovery {
    if (phase == ApocalypsePlayerPhase.ACTIVE && savedSession != null && savedSession == nativeSession)
        return ApocalypseJoinRecovery.RESUME
    if (phase != null && phase != ApocalypsePlayerPhase.PARKED || nativeSession != null)
        return if (hasOrigin) ApocalypseJoinRecovery.ORIGIN else ApocalypseJoinRecovery.QUARANTINE
    return ApocalypseJoinRecovery.NONE
}
