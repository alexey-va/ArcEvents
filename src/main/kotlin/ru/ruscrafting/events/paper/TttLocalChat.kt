package ru.ruscrafting.events.paper

import net.kyori.adventure.text.Component
import org.bukkit.Location
import org.bukkit.Server
import org.bukkit.entity.Player
import ru.ruscrafting.events.config.ArcEventsConfig
import ru.ruscrafting.events.config.ArcEventsLocale
import ru.ruscrafting.events.config.LocalChatSettings
import ru.ruscrafting.events.domain.ParticipantStatus
import ru.ruscrafting.events.domain.TttMatch
import ru.ruscrafting.events.domain.TttParticipant

/** Main-thread spatial delivery for the isolated TTT match chat. */
internal class TttLocalChat(
    private val server: Server,
    private val settings: () -> ArcEventsConfig,
    private val locale: ArcEventsLocale,
) {
    fun send(match: TttMatch, player: Player, message: Component) {
        val sender = match.participant(player.uniqueId) ?: return
        if (sender.status == ParticipantStatus.RESTORED) return
        val origin = player.location
        val chat = settings().localChat
        match.participants.values.asSequence()
            .filter { canHear(sender, it) }
            .mapNotNull { participant -> server.getPlayer(participant.playerId) }
            .forEach { recipient -> sendTo(recipient, player, sender, origin, message, chat) }
    }

    private fun sendTo(
        recipient: Player,
        player: Player,
        sender: TttParticipant,
        origin: Location,
        message: Component,
        chat: LocalChatSettings,
    ) {
        if (recipient.uniqueId == player.uniqueId) {
            recipient.sendEventMessage(locale.render("chat.self-message", recipient, mapOf("message" to message)))
            return
        }
        val range = audibleRange(origin, recipient.location, chat) ?: return
        val messageKey = if (sender.status == ParticipantStatus.DEAD) {
            "chat.spectator-message"
        } else {
            "chat.match-message"
        }
        recipient.sendEventMessage(locale.render(messageKey, recipient, mapOf(
            "range" to locale.render("chat.range.${range.localeKey}", recipient),
            "player" to Component.text(player.name),
            "message" to message,
        )))
    }

    private fun canHear(sender: TttParticipant, recipient: TttParticipant): Boolean =
        if (sender.status == ParticipantStatus.DEAD) {
            recipient.status == ParticipantStatus.DEAD
        } else {
            recipient.status in setOf(ParticipantStatus.RESERVED, ParticipantStatus.ALIVE)
        }
}

internal enum class LocalChatRange(val localeKey: String) {
    CLOSE("close"),
    NORMAL("normal"),
    FAR("far"),
    GLOBAL("global"),
}

internal fun audibleRange(
    origin: Location,
    recipient: Location,
    settings: LocalChatSettings,
): LocalChatRange? {
    if (!settings.enabled) return LocalChatRange.GLOBAL
    if (origin.world?.uid != recipient.world?.uid) return null
    val distanceSquared = origin.distanceSquared(recipient)
    return when {
        distanceSquared <= settings.closeDistance * settings.closeDistance -> LocalChatRange.CLOSE
        distanceSquared <= settings.normalDistance * settings.normalDistance -> LocalChatRange.NORMAL
        distanceSquared <= settings.maximumDistance * settings.maximumDistance -> LocalChatRange.FAR
        else -> null
    }
}
