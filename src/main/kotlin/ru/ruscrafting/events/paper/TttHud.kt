package ru.ruscrafting.events.paper

import net.kyori.adventure.bossbar.BossBar
import net.kyori.adventure.text.Component
import org.bukkit.entity.Player
import org.bukkit.plugin.Plugin
import ru.arc.paper.api.ArcSidebarFrame
import ru.arc.paper.api.ArcSidebarHandle
import ru.ruscrafting.events.config.ArcEventsConfig
import ru.ruscrafting.events.config.ArcEventsLocale
import ru.ruscrafting.events.domain.MatchPhase
import ru.ruscrafting.events.domain.ParticipantStatus
import ru.ruscrafting.events.domain.TttMatch
import ru.ruscrafting.events.domain.TttParticipant
import ru.ruscrafting.events.domain.TttRole
import java.util.UUID

/** Owns the event HUD while ARC Core arbitrates the shared native sidebar. */
class TttHud(
    private val plugin: Plugin,
    private val settings: () -> ArcEventsConfig,
    private val locale: ArcEventsLocale,
    private val nameplates: TttNameplateRuntime,
    private val sidebar: ArcSidebarHandle,
) : AutoCloseable {
    private data class Session(
        val bossBar: BossBar?,
    )

    private val sessions = linkedMapOf<UUID, Session>()

    fun open(match: TttMatch) {
        close()
        match.participants.values.mapNotNull { plugin.server.getPlayer(it.playerId) }.forEach { player -> open(player, match) }
    }

    fun update(match: TttMatch, secondsRemaining: Int, totalSeconds: Int) {
        nameplates.update(match)
        val alive = match.participants.values.count {
            it.status in setOf(ParticipantStatus.RESERVED, ParticipantStatus.ALIVE)
        }
        match.participants.values.forEach { participant ->
            val player = plugin.server.getPlayer(participant.playerId) ?: return@forEach
            val session = sessions[player.uniqueId] ?: open(player, match)
            val roleVisible = match.phase != MatchPhase.PREPARING
            updateScoreboard(player, match, participant, roleVisible, alive, secondsRemaining)
            updateBossBar(session, player, match, participant, roleVisible, alive, secondsRemaining, totalSeconds)
            updateActionBar(player, match, participant, roleVisible, alive, secondsRemaining, totalSeconds)
        }
    }

    /** Rebuilds scoreboard and boss-bar sessions so live UI toggles/locales take effect immediately. */
    fun reconfigure(match: TttMatch?, secondsRemaining: Int, totalSeconds: Int) {
        if (match == null) {
            close()
            return
        }
        open(match)
        update(match, secondsRemaining, totalSeconds)
    }

    fun remove(playerId: UUID) {
        nameplates.remove(playerId)
        closeSession(playerId)
    }

    private fun closeSession(playerId: UUID) {
        val session = sessions.remove(playerId) ?: return
        sidebar.hide(playerId)
        val player = plugin.server.getPlayer(playerId) ?: return
        session.bossBar?.let(player::hideBossBar)
    }

    override fun close() {
        sessions.keys.toList().forEach(::closeSession)
        sessions.clear()
        nameplates.clear()
    }

    private fun open(player: Player, match: TttMatch): Session {
        val session = createSession()
        session.bossBar?.let(player::showBossBar)
        sidebar.show(
            player,
            ArcSidebarFrame(
                title = locale.render("hud.scoreboard-title", player),
                rows = emptyList(),
                hiddenNameEntries = match.participants.values.mapTo(linkedSetOf(), TttParticipant::playerName),
            ),
        )
        sessions[player.uniqueId] = session
        return session
    }

    private fun createSession(): Session {
        val bossBar = if (settings().ui.bossBar) {
            BossBar.bossBar(Component.empty(), 1f, BossBar.Color.YELLOW, BossBar.Overlay.PROGRESS)
        } else {
            null
        }
        return Session(bossBar)
    }

    private fun updateScoreboard(
        player: Player,
        match: TttMatch,
        participant: TttParticipant,
        roleVisible: Boolean,
        alive: Int,
        secondsRemaining: Int,
    ) {
        val role = if (roleVisible) roleLabel(participant.role, player) else locale.render("hud.role-hidden", player)
        val objective = when {
            !roleVisible -> locale.render("hud.objective-preparing", player)
            participant.role == TttRole.TRAITOR -> locale.render("hud.objective-traitor", player)
            participant.role == TttRole.DETECTIVE -> locale.render("hud.objective-detective", player)
            else -> locale.render("hud.objective-innocent", player)
        }
        val credits = if (roleVisible && participant.role != TttRole.INNOCENT) {
            locale.render("hud.credits-line", player, mapOf("credits" to locale.text(participant.credits)))
        } else {
            Component.empty()
        }
        val components = if (settings().ui.scoreboard) listOf(
            locale.render("hud.phase-line", player, mapOf("phase" to phaseName(match.phase, player))),
            locale.render("hud.time-line", player, mapOf("time" to locale.text(formatTime(secondsRemaining)))),
            locale.render("hud.alive-line", player, mapOf(
                "alive" to locale.text(alive),
                "players" to locale.text(match.participants.size),
            )),
            Component.empty(),
            locale.render("hud.role-line", player, mapOf("role" to role)),
            credits,
            locale.render("hud.objective-line", player, mapOf("objective" to objective)),
        ) else emptyList()
        sidebar.show(
            player,
            ArcSidebarFrame(
                title = locale.render("hud.scoreboard-title", player),
                rows = components,
                hiddenNameEntries = match.participants.values.mapTo(linkedSetOf(), TttParticipant::playerName),
            ),
        )
    }

    private fun updateBossBar(
        session: Session,
        player: Player,
        match: TttMatch,
        participant: TttParticipant,
        roleVisible: Boolean,
        alive: Int,
        secondsRemaining: Int,
        totalSeconds: Int,
    ) {
        val bossBar = session.bossBar ?: return
        val safeTotal = totalSeconds.coerceAtLeast(1)
        bossBar.progress((secondsRemaining.toFloat() / safeTotal).coerceIn(0f, 1f))
        bossBar.color(when {
            !roleVisible -> BossBar.Color.YELLOW
            participant.role == TttRole.TRAITOR -> BossBar.Color.RED
            participant.role == TttRole.DETECTIVE -> BossBar.Color.BLUE
            else -> BossBar.Color.GREEN
        })
        bossBar.name(when (match.phase) {
            MatchPhase.PREPARING -> locale.render("hud.bossbar-preparing", player, mapOf(
                "time" to locale.text(formatTime(secondsRemaining)),
            ))
            MatchPhase.COUNTDOWN -> locale.render("hud.bossbar-countdown", player, mapOf(
                "role" to roleLabel(participant.role, player),
                "time" to locale.text(formatTime(secondsRemaining)),
            ))
            else -> locale.render("hud.bossbar-active", player, mapOf(
                "alive" to locale.text(alive),
                "time" to locale.text(formatTime(secondsRemaining)),
            ))
        })
    }

    private fun updateActionBar(
        player: Player,
        match: TttMatch,
        participant: TttParticipant,
        roleVisible: Boolean,
        alive: Int,
        secondsRemaining: Int,
        totalSeconds: Int,
    ) {
        when {
            !roleVisible -> {
                val tips = locale.lore("hud.preparing-tips", player)
                if (tips.isNotEmpty()) {
                    val elapsed = (totalSeconds - secondsRemaining).coerceAtLeast(0)
                    player.sendEventActionBar(tips[(elapsed / settings().gameplay.preparingTipSeconds) % tips.size])
                }
            }
            match.phase == MatchPhase.COUNTDOWN -> player.sendEventActionBar(locale.render(
                "hud.countdown-actionbar",
                player,
                mapOf(
                    "role" to roleLabel(participant.role, player),
                    "time" to locale.text(formatTime(secondsRemaining)),
                ),
            ))
            match.phase == MatchPhase.ACTIVE && participant.status == ParticipantStatus.ALIVE -> player.sendEventActionBar(locale.render(
                "role.actionbar",
                player,
                mapOf(
                    "role" to roleLabel(participant.role, player),
                    "alive" to locale.text(alive),
                    "time" to locale.text(formatTime(secondsRemaining)),
                ),
            ))
        }
    }

    private fun roleLabel(role: TttRole, player: Player): Component = locale.render(
        "role.${role.name.lowercase()}-label",
        player,
    )

    private fun phaseName(phase: MatchPhase, player: Player): Component = locale.render(
        "phase.${phase.name.lowercase()}",
        player,
    )

    private fun formatTime(seconds: Int): String {
        val safe = seconds.coerceAtLeast(0)
        return "%d:%02d".format(safe / 60, safe % 60)
    }

}
