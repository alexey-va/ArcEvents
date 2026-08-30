package ru.ruscrafting.events.paper

import com.comphenix.protocol.PacketType
import com.comphenix.protocol.ProtocolLibrary
import com.comphenix.protocol.events.ListenerPriority
import com.comphenix.protocol.events.PacketAdapter
import com.comphenix.protocol.events.PacketEvent
import org.bukkit.plugin.java.JavaPlugin

/**
 * Optional packet boundary for messages that do not pass through Paper chat or
 * broadcast events. ArcEvents-owned messages are sent inside a synchronous,
 * recipient-bound scope and remain visible to the match participant.
 */
internal class ProtocolLibChatIsolation(
    owner: JavaPlugin,
    private val service: ArcEventsGameplayBoundary,
) : PacketAdapter(
    owner,
    ListenerPriority.HIGHEST,
    PacketType.Play.Server.CHAT,
    PacketType.Play.Server.DISGUISED_CHAT,
    PacketType.Play.Server.SYSTEM_CHAT,
), AutoCloseable {
    override fun onPacketSending(event: PacketEvent) {
        val player = event.player
        event.isCancelled = shouldSuppressChatPacket(
            isolatedRecipient = service.handlesMatchChat(player.uniqueId),
            internalMessage = ArcEventsMessageDelivery.isInternal(player.uniqueId),
        )
    }

    override fun close() {
        try {
            ProtocolLibrary.getProtocolManager().removePacketListener(this)
        } finally {
            ArcEventsMessageDelivery.deactivate()
        }
    }
}

internal fun openProtocolLibChatIsolation(
    plugin: JavaPlugin,
    service: ArcEventsGameplayBoundary,
): AutoCloseable {
    val isolation = ProtocolLibChatIsolation(plugin, service)
    try {
        ProtocolLibrary.getProtocolManager().addPacketListener(isolation)
        ArcEventsMessageDelivery.activate()
        return isolation
    } catch (failure: Throwable) {
        runCatching { ProtocolLibrary.getProtocolManager().removePacketListener(isolation) }
        ArcEventsMessageDelivery.deactivate()
        throw failure
    }
}

internal fun shouldSuppressChatPacket(isolatedRecipient: Boolean, internalMessage: Boolean): Boolean =
    isolatedRecipient && !internalMessage
