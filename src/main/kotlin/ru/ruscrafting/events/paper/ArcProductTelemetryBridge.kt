package ru.ruscrafting.events.paper

import java.util.UUID

/** Optional ARC product event bridge; telemetry failures never affect match cleanup. */
internal object ArcProductTelemetryBridge {
    private val recordMethod = lazy {
        Class.forName("ru.arc.metrics.ExternalProductTelemetryBridge").getMethod(
            "recordEvent", UUID::class.java, String::class.java, String::class.java, String::class.java,
        )
    }

    fun completed(playerId: UUID, operationId: String): Boolean = recordWith(
        gateway = { id, source, event, stableId ->
            recordMethod.value.invoke(null, id, source, event, stableId) as Boolean
        },
        playerId = playerId,
        operationId = operationId,
    )

    internal fun recordWith(
        gateway: (UUID, String, String, String) -> Boolean,
        playerId: UUID,
        operationId: String,
    ): Boolean = runCatching { gateway(playerId, "arcevents", "event_completed", operationId) }.getOrDefault(false)
}
