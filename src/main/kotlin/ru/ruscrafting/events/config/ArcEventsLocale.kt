package ru.ruscrafting.events.config

import net.kyori.adventure.text.Component
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import ru.arc.config.Config
import ru.arc.config.ConfigManager
import ru.arc.text.ConfigLocaleCatalog
import ru.arc.text.LocalizedMiniMessage
import ru.ruscrafting.events.domain.FirearmId
import ru.ruscrafting.events.domain.FirearmRarity
import java.nio.file.Path

class ArcEventsLocale(
    dataRoot: Path,
    private val settings: () -> ArcEventsConfig,
) {
    private val russian = ConfigManager.of(dataRoot, "lang/ru.yml")
    private val english = ConfigManager.of(dataRoot, "lang/en.yml")
    private val renderer = LocalizedMiniMessage(
        catalogs = mapOf("ru" to ConfigLocaleCatalog(russian), "en" to ConfigLocaleCatalog(english)),
        defaultLocale = { settings().defaultLocale },
    )

    fun render(
        path: String,
        audience: CommandSender? = null,
        values: Map<String, Component> = emptyMap(),
    ): Component = renderer.render(path, localeTag(audience), values)

    fun lore(
        path: String,
        audience: CommandSender? = null,
        values: Map<String, Component> = emptyMap(),
    ): List<Component> = renderer.renderLines(path, localeTag(audience), values)

    fun text(value: Any?): Component = renderer.literal(value)

    private fun localeTag(audience: CommandSender?): String =
        if (settings().useClientLocale && audience is Player) audience.locale().toLanguageTag()
        else settings().defaultLocale

    companion object {
        val REQUIRED_SCALARS = setOf(
            "prefix", "command.player-only", "command.no-permission", "command.help", "command.failed",
            "reason.network", "reason.contended", "reason.transfer", "reason.return-transfer",
            "state.mode-relay", "state.mode-host",
            "phase.idle", "phase.reserved", "phase.preparing", "phase.countdown", "phase.active",
            "phase.resolving", "phase.restoring", "phase.completed", "phase.cancelled",
            "menu.main.title", "menu.main.ttt-name", "menu.main.stats-name", "menu.main.help-name", "menu.main.admin-name",
            "menu.event.title", "menu.event.overview-name", "menu.event.queue-name", "menu.event.join-name",
            "menu.event.join-unavailable-name", "menu.event.leave-name", "menu.event.start-name", "menu.event.roster-name",
            "menu.event.shop-name", "menu.event.report-name", "menu.event.help-name", "menu.event.evacuate-name", "menu.event.state.queued",
            "menu.event.state.reserved", "menu.event.state.arrived", "menu.event.state.matched", "menu.event.state.return_pending",
            "menu.stats.title", "menu.stats.summary-name", "menu.help.title", "menu.admin.title", "menu.arenas.title", "menu.shop.title",
            "menu.admin.arenas-name", "menu.arenas.entry-name", "menu.arenas.auto-name",
            "menu.roster.title", "menu.body.title", "menu.report.title", "menu.combat.title",
            "queue.joined", "queue.left", "queue.leave-reserved", "queue.unavailable", "queue.reserved", "queue.returned",
            "queue.start-requested", "queue.start-insufficient", "queue.start-arena-unavailable",
            "queue.start-busy", "queue.start-recovery-pending", "queue.start-network-failed",
            "match.preparing-title", "match.preparing-subtitle", "match.preparing-guide", "match.started",
            "match.started-title", "match.started-subtitle", "match.eliminated-title",
            "match.eliminated-subtitle", "match.detectives-announced", "match.restored", "match.evacuated",
            "match.spawn-return-requested", "match.spawn-return-actionbar", "match.spawn-return-cancelled",
            "match.spawn-return-complete", "match.spawn-return-unavailable", "match.spawn-return-already",
            "role.innocent-title", "role.traitor-title", "role.detective-title", "role.actionbar",
            "role.innocent-label", "role.traitor-label", "role.detective-label",
            "hud.scoreboard-title", "hud.phase-line", "hud.time-line", "hud.alive-line", "hud.role-line",
            "hud.credits-line", "hud.objective-line", "hud.role-hidden", "hud.objective-preparing",
            "hud.objective-innocent", "hud.objective-traitor", "hud.objective-detective",
            "hud.bossbar-preparing", "hud.bossbar-countdown", "hud.bossbar-active", "hud.countdown-actionbar",
            "guide.item-name",
            "loadout.blade", "loadout.crossbow", "loadout.rations", "loadout.ammunition",
            "body.unidentified", "body.identified", "body.discovered", "body.dna", "body.unavailable",
            "weapon.ammunition-name", "weapon.ammo-actionbar", "weapon.pickup-ammo-actionbar", "weapon.reload-complete-actionbar",
            "roster.status.alive", "roster.status.missing", "roster.status.confirmed_dead", "roster.role-hidden",
            "report.item-name", "report.ready", "report.unavailable", "report.winner-innocents",
            "shop.bought", "shop.insufficient", "shop.unavailable", "team.message",
            "chat.match-message", "chat.spectator-message",
            "arena.auto.name", "arena.citadel.name", "arena.inferno.name", "arena.mirage.name", "arena.nuke.name",
            "arena.state.ready", "arena.state.active", "arena.state.next", "arena.state.unavailable",
            "admin.arena-unavailable", "admin.arena-selected", "admin.arena-selection-failed",
            "admin.start-recovery-pending", "debug.disabled", "debug.usage",
            "debug.applied", "debug.rejected", "debug.reason.mutations-disabled", "debug.reason.wrong-node",
            "debug.reason.arena-unavailable", "debug.reason.busy", "debug.reason.insufficient-players",
            "debug.reason.no-match", "debug.reason.wrong-phase", "debug.reason.player-not-found",
            "debug.reason.not-participant", "debug.reason.not-alive", "debug.reason.invalid-argument",
            "debug.reason.role-invariant", "debug.reason.inventory-full", "debug.reason.body-not-found",
            "debug.reason.precondition-failed", "debug.reason.internal-error",
        ) + FirearmId.entries.flatMap { id ->
            listOf("weapon.${id.name.lowercase()}-name", "weapon.kind.firearm-${id.name.lowercase()}")
        } + FirearmRarity.entries.map { rarity -> "weapon.rarity.${rarity.name.lowercase()}" }

        val REQUIRED_LISTS = setOf(
            "menu.main.ttt-lore", "menu.main.stats-lore", "menu.main.help-lore", "menu.main.admin-lore",
            "menu.event.overview-lore", "menu.event.queue-lore", "menu.event.join-lore", "menu.event.join-unavailable-lore",
            "menu.event.leave-lore", "menu.event.start-lore", "menu.event.roster-lore", "menu.event.shop-lore",
            "menu.event.report-lore", "menu.event.help-lore", "menu.event.evacuate-lore", "menu.stats.summary-lore", "menu.help.weapons-lore",
            "menu.help.flow-lore",
            "menu.roster.player-lore", "menu.body.victim-lore", "menu.body.cause-lore",
            "menu.report.summary-lore", "menu.report.player-lore", "menu.combat.entry-lore",
            "menu.help.innocent-lore", "menu.help.traitor-lore", "menu.help.detective-lore",
            "menu.common.back-lore", "menu.admin.status-lore", "menu.admin.arenas-lore",
            "menu.arenas.entry-lore", "menu.arenas.auto-lore", "menu.shop.credits-lore",
            "weapon.firearm-lore", "weapon.ammunition-lore", "report.item-lore", "hud.preparing-tips", "guide.item-lore",
        )

        fun validateFiles(dataRoot: Path) {
            listOf("ru", "en").forEach { language ->
                val config = Config(dataRoot, "lang/$language.yml")
                REQUIRED_SCALARS.forEach { path ->
                    require(config.stringOrNull(path)?.isNotBlank() == true) { "Locale $language is missing $path" }
                }
                REQUIRED_LISTS.forEach { path ->
                    require(config.stringListOrNull(path)?.isNotEmpty() == true) { "Locale $language is missing $path" }
                }
            }
        }
    }
}
