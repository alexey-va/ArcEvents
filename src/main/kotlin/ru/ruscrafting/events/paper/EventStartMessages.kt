package ru.ruscrafting.events.paper

import net.kyori.adventure.text.Component
import net.kyori.adventure.title.Title
import org.bukkit.entity.Player
import java.time.Duration

internal fun Player.showEventOpening(title: Component, objective: Component) {
    showTitle(Title.title(title, objective, Title.Times.times(
        Duration.ofMillis(150), Duration.ofSeconds(4), Duration.ofMillis(350),
    )))
}

internal enum class StartMessageAudience { PLAYER, ADMIN, DEBUG }

internal fun reservationStartMessage(
    result: ReservationStartResult,
    audience: StartMessageAudience,
): String? = when (result) {
    ReservationStartResult.CLIENT_UNSUPPORTED -> "queue.start-client-unsupported"
    ReservationStartResult.STARTED -> when (audience) {
        // The authoritative reservation notice already reaches every roster member.
        StartMessageAudience.PLAYER -> null
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
    ReservationStartResult.NOT_OWNER -> when (audience) {
        StartMessageAudience.PLAYER -> "queue.start-not-owner"
        else -> "admin.start-failed"
    }
}
