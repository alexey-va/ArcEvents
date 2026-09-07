package ru.arc.metrics

import java.util.UUID

/** Test-only external API, deliberately absent from the production JAR. */
object ExternalProductTelemetryBridge {
    val calls = mutableListOf<List<Any>>()

    @JvmStatic
    fun recordEvent(player: UUID, source: String, event: String, operation: String): Boolean {
        calls += listOf(player, source, event, operation)
        return true
    }
}
