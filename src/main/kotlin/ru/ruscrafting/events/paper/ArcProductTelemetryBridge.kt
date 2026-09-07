package ru.ruscrafting.events.paper

import java.util.UUID

/** Optional ARC product event bridge; telemetry failures never affect match cleanup. */
internal object ArcProductTelemetryBridge {
    // ArcEvents must precede My_Worlds for arena generators; ARC loads after it.
    // Resolve the optional API from ARC's own loader when an event completes.
    fun completed(playerId: UUID, operationId: String): Boolean =
        completedWithLoader(playerId, operationId) {
            org.bukkit.Bukkit.getPluginManager().getPlugin("ARC")
                ?.takeIf { it.isEnabled }?.javaClass?.classLoader
        }

    internal fun completedWithLoader(
        playerId: UUID,
        operationId: String,
        classLoader: () -> ClassLoader?,
    ): Boolean = recordWith(
        gateway = { id, source, event, stableId ->
            val loader = classLoader()
            if (loader == null) false else Class.forName(
                "ru.arc.metrics.ExternalProductTelemetryBridge", true, loader,
            ).getMethod(
                "recordEvent", UUID::class.java, String::class.java, String::class.java, String::class.java,
            ).invoke(null, id, source, event, stableId) as Boolean
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
