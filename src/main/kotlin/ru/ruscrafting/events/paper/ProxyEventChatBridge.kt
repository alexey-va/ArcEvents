package ru.ruscrafting.events.paper

import org.bukkit.entity.Player
import org.bukkit.plugin.Plugin
import ru.arc.core.ScheduledTask
import ru.arc.core.Tasks
import java.util.UUID
import java.util.logging.Level

/** A renewable HOST lease; ProxyARC owns the configured backend allowlist and 15-second expiry. */
internal class ProxyEventChatBridge(
    private val plugin: Plugin,
    private val isolated: (UUID) -> Boolean,
) : AutoCloseable {
    private val renewedAt = mutableMapOf<UUID, Long>()
    private var ticks = 0L
    private var closed = false
    private val task: ScheduledTask

    init {
        plugin.server.messenger.registerOutgoingPluginChannel(plugin, CHANNEL)
        // Observe state transitions on the next tick; send only changes and five-second renewals.
        task = Tasks.scheduler.runTimer(1L, 1L) { refresh() }
    }

    internal fun refresh() {
        if (closed) return
        ticks++
        val online = plugin.server.onlinePlayers
        val onlineIds = online.mapTo(mutableSetOf(), Player::getUniqueId)
        renewedAt.keys.retainAll(onlineIds)
        online.forEach { player ->
            val previous = renewedAt[player.uniqueId]
            if (isolated(player.uniqueId)) {
                if ((previous == null || ticks - previous >= RENEW_TICKS) && send(player, true)) {
                    renewedAt[player.uniqueId] = ticks
                }
            } else if (previous != null && send(player, false)) {
                renewedAt.remove(player.uniqueId)
            }
        }
    }

    private fun send(player: Player, active: Boolean): Boolean = try {
        player.sendPluginMessage(plugin, CHANNEL, byteArrayOf(1, if (active) 1 else 0))
        true
    } catch (failure: RuntimeException) {
        // Lease expiry remains the fallback; one warning per renewal interval bounds retries.
        if (ticks % RENEW_TICKS == 0L) plugin.logger.log(Level.WARNING, "ArcEvents proxy chat lease send failed player=${player.uniqueId}", failure)
        false
    }

    override fun close() {
        if (closed) return
        closed = true
        task.cancel()
        try {
            renewedAt.keys.forEach { id -> plugin.server.getPlayer(id)?.let { send(it, false) } }
        } finally {
            renewedAt.clear()
            plugin.server.messenger.unregisterOutgoingPluginChannel(plugin, CHANNEL)
        }
    }

    companion object {
        const val CHANNEL = "arc:events_chat"
        private const val RENEW_TICKS = 100L
    }
}
