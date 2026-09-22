package ru.ruscrafting.events.paper

import net.kyori.adventure.text.Component
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player

/** The packet boundary owns this transport; no thread-local permission can leak across sends. */
internal object ArcEventsMessageDelivery {
    @Volatile
    private var transport: ((Player, Component, Boolean) -> Unit)? = null

    fun activate(delivery: (Player, Component, Boolean) -> Unit) {
        transport = delivery
    }

    fun deactivate() {
        transport = null
    }

    fun send(player: Player, message: Component, actionBar: Boolean = false) {
        val delivery = transport
        if (delivery != null) delivery(player, message, actionBar)
        else if (actionBar) player.sendActionBar(message)
        else player.sendMessage(message)
    }
}

internal fun CommandSender.sendEventMessage(message: Component) {
    if (this is Player) ArcEventsMessageDelivery.send(this, message) else sendMessage(message)
}

internal fun Player.sendEventActionBar(message: Component) {
    ArcEventsMessageDelivery.send(this, message, actionBar = true)
}
