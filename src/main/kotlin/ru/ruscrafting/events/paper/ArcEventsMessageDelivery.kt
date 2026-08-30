package ru.ruscrafting.events.paper

import net.kyori.adventure.text.Component
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import java.util.UUID

/** Marks ArcEvents-owned chat packets while the optional ProtocolLib boundary is active. */
internal object ArcEventsMessageDelivery {
    private val internalRecipients = ThreadLocal<MutableMap<UUID, Int>>()

    @Volatile
    private var active = false

    fun activate() {
        active = true
    }

    fun deactivate() {
        active = false
        internalRecipients.remove()
    }

    fun deliver(player: Player, delivery: () -> Unit) {
        if (!active) {
            delivery()
            return
        }
        val recipients = internalRecipients.get() ?: mutableMapOf<UUID, Int>().also(internalRecipients::set)
        val playerId = player.uniqueId
        recipients[playerId] = recipients.getOrDefault(playerId, 0) + 1
        try {
            delivery()
        } finally {
            val remaining = recipients.getValue(playerId) - 1
            if (remaining == 0) recipients.remove(playerId) else recipients[playerId] = remaining
            if (recipients.isEmpty()) internalRecipients.remove()
        }
    }

    fun isInternal(playerId: UUID): Boolean = active && internalRecipients.get()?.get(playerId)?.let { it > 0 } == true
}

internal fun CommandSender.sendEventMessage(message: Component) {
    if (this is Player) {
        ArcEventsMessageDelivery.deliver(this) { sendMessage(message) }
    } else {
        sendMessage(message)
    }
}

internal fun Player.sendEventActionBar(message: Component) {
    ArcEventsMessageDelivery.deliver(this) { sendActionBar(message) }
}
