package ru.ruscrafting.events.paper

import net.kyori.adventure.text.Component
import org.bukkit.attribute.Attribute
import org.bukkit.entity.Player
import ru.arc.nameplate.NameplateLayer
import ru.arc.nameplate.NameplateLayerKey
import ru.arc.nameplate.NameplateUpsertResult
import ru.arc.nameplate.PlayerNameplateRegistry
import ru.ruscrafting.events.config.ArcEventsLocale
import ru.ruscrafting.events.domain.MatchPhase
import ru.ruscrafting.events.domain.ParticipantStatus
import ru.ruscrafting.events.domain.PlayerEventStats
import ru.ruscrafting.events.domain.TttMatch
import java.util.UUID
import kotlin.math.ceil

/** Owns ArcEvents' public, non-role-revealing rows in the shared Paper nameplate renderer. */
class TttNameplates(
    private val registry: PlayerNameplateRegistry,
    private val locale: ArcEventsLocale,
    private val statistics: (UUID) -> PlayerEventStats,
    private val onlinePlayer: (UUID) -> Player?,
    private val refresh: () -> Unit,
    healthPriority: Int = 200,
    summaryPriority: Int = 100,
) : AutoCloseable {
    private val layers = listOf(
        NameplateLayer(NameplateLayerKey(OWNER, "health"), healthPriority, Component.empty()),
        NameplateLayer(NameplateLayerKey(OWNER, "summary"), summaryPriority, Component.empty()),
    )
    private val visibleTargets = linkedSetOf<UUID>()

    fun update(match: TttMatch) {
        if (match.phase !in VISIBLE_PHASES) {
            close()
            return
        }
        val currentTargets = match.participants.values
            .filter { it.status in VISIBLE_STATUSES }
            .mapNotNull { participant -> onlinePlayer(participant.playerId) }
            .associateBy(Player::getUniqueId)

        (visibleTargets - currentTargets.keys).forEach(::removeRows)
        currentTargets.values.forEach(::updateRows)
        visibleTargets.clear()
        visibleTargets += currentTargets.keys
        refresh()
    }

    fun remove(playerId: UUID) {
        removeRows(playerId)
        visibleTargets -= playerId
        refresh()
    }

    override fun close() {
        visibleTargets.clear()
        registry.clearOwner(OWNER)
        runCatching(refresh)
    }

    private fun updateRows(player: Player) {
        val stats = statistics(player.uniqueId)
        val maximumHealth = player.getAttribute(Attribute.MAX_HEALTH)?.value ?: player.health.coerceAtLeast(20.0)
        val values = mapOf(
            "health" to locale.text(ceil(player.health.coerceAtLeast(0.0)).toInt()),
            "max_health" to locale.text(ceil(maximumHealth.coerceAtLeast(1.0)).toInt()),
            "karma" to locale.text(stats.karma),
            "wins" to locale.text(stats.wins),
            "matches" to locale.text(stats.matches),
        )
        val lines = locale.lore(NAMEPLATE_LINES, values = values)
        layers.forEach { layer -> registry.remove(player.uniqueId, layer.key) }
        layers.zip(lines.take(layers.size)).forEach { (layer, content) ->
            val result = registry.upsert(player.uniqueId, layer.copy(content = content))
            if (result is NameplateUpsertResult.Rejected) {
                error("ArcEvents nameplate row ${layer.key.value} was rejected: ${result.reason}")
            }
        }
    }

    private fun removeRows(playerId: UUID) {
        layers.forEach { layer -> registry.remove(playerId, layer.key) }
    }

    private companion object {
        const val OWNER = "arcevents"
        const val NAMEPLATE_LINES = "nameplate.lines"
        val VISIBLE_PHASES = setOf(MatchPhase.PREPARING, MatchPhase.COUNTDOWN, MatchPhase.ACTIVE)
        val VISIBLE_STATUSES = setOf(ParticipantStatus.RESERVED, ParticipantStatus.ALIVE)
    }
}
