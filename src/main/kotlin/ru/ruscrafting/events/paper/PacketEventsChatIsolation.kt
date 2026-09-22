package ru.ruscrafting.events.paper

import com.github.retrooper.packetevents.PacketEvents
import com.github.retrooper.packetevents.event.PacketListenerAbstract
import com.github.retrooper.packetevents.event.PacketListenerPriority
import com.github.retrooper.packetevents.event.PacketSendEvent
import com.github.retrooper.packetevents.protocol.packettype.PacketType
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSystemChatMessage
import java.util.concurrent.atomic.AtomicBoolean

/** Covers direct plugin sends, vanilla player chat and the dedicated action-bar packet. */
internal class PacketEventsChatIsolation(
    private val service: ArcEventsGameplayBoundary,
) : PacketListenerAbstract(PacketListenerPriority.HIGHEST), AutoCloseable {
    private val closed = AtomicBoolean(false)

    override fun onPacketSend(event: PacketSendEvent) {
        if (event.isCancelled || event.packetType !in CHAT_PACKETS) return
        if (service.handlesMatchChat(event.user.uuid)) event.isCancelled = true
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        try {
            PacketEvents.getAPI().eventManager.unregisterListener(this)
        } finally {
            ArcEventsMessageDelivery.deactivate()
        }
    }

    companion object {
        private val CHAT_PACKETS = setOf(
            PacketType.Play.Server.CHAT_MESSAGE,
            PacketType.Play.Server.DISGUISED_CHAT,
            PacketType.Play.Server.SYSTEM_CHAT_MESSAGE,
            PacketType.Play.Server.ACTION_BAR,
        )

        fun open(service: ArcEventsGameplayBoundary): PacketEventsChatIsolation {
            val isolation = PacketEventsChatIsolation(service)
            PacketEvents.getAPI().eventManager.registerListener(isolation)
            // Player messages are encoded later on Netty, after a caller's thread-local
            // scope can end. The documented silent transport admits only our own packets.
            ArcEventsMessageDelivery.activate { player, message, actionBar ->
                PacketEvents.getAPI().playerManager.sendPacketSilently(
                    player, WrapperPlayServerSystemChatMessage(actionBar, message),
                )
            }
            return isolation
        }
    }
}
