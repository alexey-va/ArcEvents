package ru.ruscrafting.events.paper

import net.kyori.adventure.bossbar.BossBar
import net.kyori.adventure.text.Component
import org.bukkit.entity.Player
import org.bukkit.plugin.Plugin
import org.bukkit.scoreboard.Criteria
import org.bukkit.scoreboard.DisplaySlot
import org.bukkit.scoreboard.Scoreboard
import org.bukkit.scoreboard.Team
import ru.ruscrafting.events.config.ArcEventsConfig
import ru.ruscrafting.events.config.ArcEventsLocale
import ru.ruscrafting.events.domain.MatchPhase
import ru.ruscrafting.events.domain.ParticipantStatus
import ru.ruscrafting.events.domain.TttMatch
import ru.ruscrafting.events.domain.TttParticipant
import ru.ruscrafting.events.domain.TttRole
import java.util.UUID

/** Owns the complete per-player HUD lifecycle, including restoration of a prior scoreboard. */
class TttHud(
    private val plugin: Plugin,
    private val settings: () -> ArcEventsConfig,
    private val locale: ArcEventsLocale,
) : AutoCloseable {
    private data class Session(
        val previousScoreboard: Scoreboard,
        val scoreboard: Scoreboard?,
        val lines: List<Team>,
        val bossBar: BossBar?,
    )

    private val sessions = linkedMapOf<UUID, Session>()

    fun open(match: TttMatch) {
        close()
        match.participants.values.mapNotNull { plugin.server.getPlayer(it.playerId) }.forEach(::open)
    }

    fun update(match: TttMatch, secondsRemaining: Int, totalSeconds: Int) {
        val alive = match.participants.values.count {
            it.status in setOf(ParticipantStatus.RESERVED, ParticipantStatus.ALIVE)
        }
        match.participants.values.forEach { participant ->
            val player = plugin.server.getPlayer(participant.playerId) ?: return@forEach
            val session = sessions[player.uniqueId] ?: open(player)
            val roleVisible = match.phase != MatchPhase.PREPARING
            updateScoreboard(session, player, match, participant, roleVisible, alive, secondsRemaining)
            updateBossBar(session, player, match, participant, roleVisible, alive, secondsRemaining, totalSeconds)
            updateActionBar(player, match, participant, roleVisible, alive, secondsRemaining, totalSeconds)
        }
    }

    fun remove(playerId: UUID) {
        val session = sessions.remove(playerId) ?: return
        val player = plugin.server.getPlayer(playerId) ?: return
        session.bossBar?.let(player::hideBossBar)
        if (session.scoreboard != null && player.scoreboard === session.scoreboard) {
            player.scoreboard = session.previousScoreboard
        }
    }

    override fun close() {
        sessions.keys.toList().forEach(::remove)
        sessions.clear()
    }

    private fun open(player: Player): Session {
        val scoreboard = if (settings().ui.scoreboard) createScoreboard(player) else null
        val bossBar = if (settings().ui.bossBar) {
            BossBar.bossBar(Component.empty(), 1f, BossBar.Color.YELLOW, BossBar.Overlay.PROGRESS).also(player::showBossBar)
        } else {
            null
        }
        val session = Session(
            previousScoreboard = player.scoreboard,
            scoreboard = scoreboard?.first,
            lines = scoreboard?.second.orEmpty(),
            bossBar = bossBar,
        )
        scoreboard?.first?.let { player.scoreboard = it }
        sessions[player.uniqueId] = session
        return session
    }

    private fun createScoreboard(player: Player): Pair<Scoreboard, List<Team>> {
        val scoreboard = plugin.server.scoreboardManager.newScoreboard
        val objective = scoreboard.registerNewObjective(
            OBJECTIVE_NAME,
            Criteria.DUMMY,
            locale.render("hud.scoreboard-title", player),
        )
        objective.displaySlot = DisplaySlot.SIDEBAR
        val lines = SCOREBOARD_ENTRIES.mapIndexed { index, entry ->
            scoreboard.registerNewTeam("ae_line_$index").also { team ->
                team.addEntry(entry)
                objective.getScore(entry).score = SCOREBOARD_ENTRIES.size - index
            }
        }
        return scoreboard to lines
    }

    private fun updateScoreboard(
        session: Session,
        player: Player,
        match: TttMatch,
        participant: TttParticipant,
        roleVisible: Boolean,
        alive: Int,
        secondsRemaining: Int,
    ) {
        if (session.scoreboard == null) return
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
        val components = listOf(
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
        )
        session.lines.zip(components).forEach { (team, component) -> team.prefix(component) }
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
                    player.sendActionBar(tips[(elapsed / PREPARING_TIP_SECONDS) % tips.size])
                }
            }
            match.phase == MatchPhase.COUNTDOWN -> player.sendActionBar(locale.render(
                "hud.countdown-actionbar",
                player,
                mapOf(
                    "role" to roleLabel(participant.role, player),
                    "time" to locale.text(formatTime(secondsRemaining)),
                ),
            ))
            match.phase == MatchPhase.ACTIVE && participant.status == ParticipantStatus.ALIVE -> player.sendActionBar(locale.render(
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

    companion object {
        private const val OBJECTIVE_NAME = "arcevents_ttt"
        private const val PREPARING_TIP_SECONDS = 4
        private val SCOREBOARD_ENTRIES = listOf("§0", "§1", "§2", "§3", "§4", "§5", "§6")
    }
}
