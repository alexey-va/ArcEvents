package ru.ruscrafting.events.paper

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.bukkit.Server
import org.bukkit.entity.Player
import org.bukkit.plugin.Plugin
import ru.arc.core.PaperArcRuntime
import ru.arc.core.Tasks
import ru.arc.paper.testing.MockBukkitTestRuntime
import java.util.UUID

class ProxyEventChatBridgeTest : StringSpec({
    "proxy lease follows only participants renews and clears on leaving or shutdown" {
        MockBukkitTestRuntime.open().use { paper ->
            val schedulerPlugin = paper.createSimplePlugin("ArcEventsChatBridgeTest")
            PaperArcRuntime.installScheduling(schedulerPlugin)
            try {
                val player = mockk<Player>(relaxed = true)
                val outsider = mockk<Player>(relaxed = true)
                val id = UUID.randomUUID()
                every { player.uniqueId } returns id
                every { outsider.uniqueId } returns UUID.randomUUID()
                val server = mockk<Server>(relaxed = true)
                every { server.onlinePlayers } returns listOf(player, outsider)
                every { server.getPlayer(id) } returns player
                val plugin = mockk<Plugin>(relaxed = true)
                every { plugin.server } returns server
                val payloads = mutableListOf<List<Byte>>()
                every { player.sendPluginMessage(plugin, ProxyEventChatBridge.CHANNEL, any()) } answers {
                    payloads += thirdArg<ByteArray>().toList()
                }
                var participating = false
                ProxyEventChatBridge(plugin) { it == id && participating }.use { bridge ->
                    bridge.refresh()
                    payloads shouldBe emptyList()
                    participating = true
                    bridge.refresh()
                    payloads shouldBe listOf(listOf<Byte>(1, 1))
                    repeat(99) { bridge.refresh() }
                    payloads.size shouldBe 1
                    bridge.refresh()
                    payloads.size shouldBe 2
                    participating = false
                    bridge.refresh()
                    payloads.last() shouldBe listOf<Byte>(1, 0)
                    participating = true
                    bridge.refresh()
                }
                payloads.last() shouldBe listOf<Byte>(1, 0)
            } finally {
                Tasks.reset()
            }
        }
    }
})
