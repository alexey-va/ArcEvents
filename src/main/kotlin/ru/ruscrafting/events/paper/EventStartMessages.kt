package ru.ruscrafting.events.paper

internal enum class StartMessageAudience { PLAYER, ADMIN, DEBUG }

internal fun reservationStartMessage(
    result: ReservationStartResult,
    audience: StartMessageAudience,
): String = when (result) {
    ReservationStartResult.STARTED -> when (audience) {
        StartMessageAudience.PLAYER -> "queue.start-requested"
        StartMessageAudience.ADMIN -> "admin.started"
        StartMessageAudience.DEBUG -> "debug.applied"
    }
    ReservationStartResult.ARENA_UNAVAILABLE -> when (audience) {
        StartMessageAudience.PLAYER -> "queue.start-arena-unavailable"
        else -> "admin.arena-unavailable"
    }
    ReservationStartResult.BUSY -> when (audience) {
        StartMessageAudience.PLAYER -> "queue.start-busy"
        else -> "admin.busy"
    }
    ReservationStartResult.INSUFFICIENT_PLAYERS -> when (audience) {
        StartMessageAudience.PLAYER -> "queue.start-insufficient"
        else -> "admin.start-failed"
    }
    ReservationStartResult.RECOVERY_PENDING -> when (audience) {
        StartMessageAudience.PLAYER -> "queue.start-recovery-pending"
        else -> "admin.start-recovery-pending"
    }
    ReservationStartResult.NETWORK_FAILURE -> when (audience) {
        StartMessageAudience.PLAYER -> "queue.start-network-failed"
        else -> "admin.network-failed"
    }
}
