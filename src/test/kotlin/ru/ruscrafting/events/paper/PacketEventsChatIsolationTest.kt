package ru.ruscrafting.events.paper

import com.github.retrooper.packetevents.event.PacketSendEvent
import com.github.retrooper.packetevents.protocol.packettype.PacketType
import io.kotest.core.spec.style.StringSpec
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.util.UUID

class PacketEventsChatIsolationTest : StringSpec({
    "all chat packet variants are suppressed only for participants without reviving cancellations" {
        val playerId = UUID.randomUUID()
        val service = mockk<ArcEventsGameplayBoundary>()
        var isolated = true
        every { service.handlesMatchChat(playerId) } answers { isolated }
        val filter = PacketEventsChatIsolation(service)
        val chatTypes = listOf(PacketType.Play.Server.CHAT_MESSAGE, PacketType.Play.Server.DISGUISED_CHAT,
            PacketType.Play.Server.SYSTEM_CHAT_MESSAGE, PacketType.Play.Server.ACTION_BAR)
        for (type in chatTypes) {
            val event = mockk<PacketSendEvent>(relaxed = true)
            every { event.packetType } returns type
            every { event.user.uuid } returns playerId
            filter.onPacketSend(event)
            verify(exactly = 1) { event.isCancelled = true }
            verify(exactly = 0) { event.isCancelled = false }
        }
        isolated = false
        val outsider = mockk<PacketSendEvent>(relaxed = true)
        every { outsider.packetType } returns PacketType.Play.Server.SYSTEM_CHAT_MESSAGE
        every { outsider.user.uuid } returns playerId
        filter.onPacketSend(outsider)
        verify(exactly = 0) { outsider.isCancelled = any() }
        isolated = true
        val cancelled = mockk<PacketSendEvent>(relaxed = true)
        every { cancelled.isCancelled } returns true
        filter.onPacketSend(cancelled)
        verify(exactly = 0) { cancelled.isCancelled = any() }
        val title = mockk<PacketSendEvent>(relaxed = true)
        every { title.packetType } returns PacketType.Play.Server.SET_TITLE_TEXT
        filter.onPacketSend(title)
        verify(exactly = 0) { title.isCancelled = any() }
    }
})
