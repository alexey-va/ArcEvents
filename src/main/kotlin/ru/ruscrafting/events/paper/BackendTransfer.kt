package ru.ruscrafting.events.paper

import org.bukkit.entity.Player
import org.bukkit.plugin.Plugin
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream

fun interface BackendTransfer {
    fun connect(player: Player, serverId: String): Boolean
}

class BungeeBackendTransfer(private val plugin: Plugin) : BackendTransfer {
    override fun connect(player: Player, serverId: String): Boolean = runCatching {
        player.sendPluginMessage(plugin, CHANNEL, encodeConnect(serverId))
    }.isSuccess

    companion object {
        const val CHANNEL = "BungeeCord"

        internal fun encodeConnect(serverId: String): ByteArray {
            require(serverId.matches(Regex("[a-z0-9_-]{1,32}")))
            return ByteArrayOutputStream().use { bytes ->
                DataOutputStream(bytes).use { output ->
                    output.writeUTF("Connect")
                    output.writeUTF(serverId)
                }
                bytes.toByteArray()
            }
        }
    }
}
