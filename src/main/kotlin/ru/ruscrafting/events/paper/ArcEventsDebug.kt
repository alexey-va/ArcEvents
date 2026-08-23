package ru.ruscrafting.events.paper

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.entity.Player

class ArcEventsDebug(
    private val enabled: () -> Boolean,
    private val sink: (String) -> Unit,
) {
    private val plain = PlainTextComponentSerializer.plainText()

    fun event(name: String, vararg fields: Pair<String, Any?>) {
        if (!enabled()) return
        sink(line("ARCEVENTS_DEBUG", listOf("event" to name) + fields))
    }

    fun message(surface: String, key: String, player: Player, component: Component) = event(
        "message",
        "surface" to surface,
        "key" to key,
        "player" to player.name,
        "text" to plain.serialize(component),
    )

    companion object {
        fun qa(vararg fields: Pair<String, Any?>): String = line("ARCEVENTS_QA", fields.toList())

        private fun line(prefix: String, fields: List<Pair<String, Any?>>): String = buildString {
            append(prefix)
            fields.forEach { (key, value) -> append(' ').append(token(key)).append('=').append(encoded(value)) }
        }

        private fun encoded(value: Any?): String {
            val raw = value?.toString().orEmpty().replace('\n', ' ').replace('\r', ' ').take(MAX_VALUE_CHARS)
            if (raw.matches(SAFE_VALUE)) return raw.ifEmpty { "-" }
            return "\"${raw.replace("\\", "\\\\").replace("\"", "\\\"")}\""
        }

        private fun token(value: String): String = value.lowercase().replace(TOKEN_INVALID, "_").take(MAX_TOKEN_CHARS)

        private val SAFE_VALUE = Regex("[A-Za-z0-9_.:/@+,-]{1,240}")
        private val TOKEN_INVALID = Regex("[^a-z0-9_.-]")
        private const val MAX_VALUE_CHARS = 240
        private const val MAX_TOKEN_CHARS = 64
    }
}
