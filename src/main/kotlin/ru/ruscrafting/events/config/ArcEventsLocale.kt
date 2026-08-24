package ru.ruscrafting.events.config

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.minimessage.MiniMessage
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import ru.arc.config.Config
import ru.arc.config.ConfigManager
import java.nio.file.Path

class ArcEventsLocale(
    dataRoot: Path,
    private val settings: () -> ArcEventsConfig,
) {
    private val russian = ConfigManager.of(dataRoot, "lang/ru.yml")
    private val english = ConfigManager.of(dataRoot, "lang/en.yml")
    private val mini = MiniMessage.miniMessage()

    fun render(
        path: String,
        audience: CommandSender? = null,
        values: Map<String, Component> = emptyMap(),
    ): Component = deserialize(raw(path, audience), audience, values)

    fun lore(
        path: String,
        audience: CommandSender? = null,
        values: Map<String, Component> = emptyMap(),
    ): List<Component> {
        val selected = select(audience)
        val fallback = fallback()
        val lines = selected.stringListOrNull(path)?.takeIf { it.isNotEmpty() }
            ?: fallback.stringListOrNull(path).orEmpty()
        return lines.map { deserialize(it, audience, values) }
    }

    fun text(value: Any?): Component = Component.text(value?.toString().orEmpty())

    private fun raw(path: String, audience: CommandSender?): String {
        val selected = select(audience)
        return selected.stringOrNull(path)?.takeIf(String::isNotBlank)
            ?: fallback().stringOrNull(path)?.takeIf(String::isNotBlank)
            ?: path
    }

    private fun deserialize(
        raw: String,
        audience: CommandSender?,
        values: Map<String, Component>,
    ): Component {
        val prefixRaw = select(audience).stringOrNull("prefix")?.takeIf(String::isNotBlank)
            ?: fallback().string("prefix", "<#d75a5a>Events <#666666>•")
        val builder = TagResolver.builder().resolver(Placeholder.component("prefix", mini.deserialize(prefixRaw)))
        values.forEach { (name, value) -> builder.resolver(Placeholder.component(name, value)) }
        return mini.deserialize(raw, builder.build())
    }

    private fun fallback(): Config = if (settings().defaultLocale == "en") english else russian

    private fun select(audience: CommandSender?): Config {
        if (!settings().useClientLocale || audience !is Player) return fallback()
        return if (audience.locale().language.equals("ru", ignoreCase = true)) russian else english
    }

    companion object {
        val REQUIRED_SCALARS = setOf(
            "prefix", "command.player-only", "command.no-permission", "command.help", "command.failed",
            "reason.network", "reason.contended", "reason.transfer", "reason.return-transfer",
            "state.mode-relay", "state.mode-host",
            "phase.idle", "phase.reserved", "phase.preparing", "phase.countdown", "phase.active",
            "phase.resolving", "phase.restoring", "phase.completed", "phase.cancelled",
            "menu.main.title", "menu.help.title", "menu.admin.title", "menu.shop.title",
            "menu.main.join-unavailable-name", "menu.main.roster-name", "menu.main.report-name",
            "menu.roster.title", "menu.body.title", "menu.report.title", "menu.combat.title",
            "queue.joined", "queue.left", "queue.unavailable", "queue.reserved",
            "match.preparing-title", "match.preparing-subtitle", "match.preparing-guide", "match.started",
            "match.started-title", "match.started-subtitle", "match.eliminated-title",
            "match.eliminated-subtitle", "match.detectives-announced", "match.restored",
            "role.innocent-title", "role.traitor-title", "role.detective-title", "role.actionbar",
            "role.innocent-label", "role.traitor-label", "role.detective-label",
            "hud.scoreboard-title", "hud.phase-line", "hud.time-line", "hud.alive-line", "hud.role-line",
            "hud.credits-line", "hud.objective-line", "hud.role-hidden", "hud.objective-preparing",
            "hud.objective-innocent", "hud.objective-traitor", "hud.objective-detective",
            "hud.bossbar-preparing", "hud.bossbar-countdown", "hud.bossbar-active", "hud.countdown-actionbar",
            "guide.item-name",
            "loadout.blade", "loadout.crossbow", "loadout.rations", "loadout.ammunition",
            "body.unidentified", "body.identified", "body.discovered", "body.dna", "body.unavailable",
            "weapon.pistol-name", "weapon.smg-name", "weapon.shotgun-name", "weapon.rifle-name",
            "weapon.ammunition-name", "weapon.ammo-actionbar", "weapon.reload-complete-actionbar",
            "roster.status.alive", "roster.status.missing", "roster.status.confirmed_dead", "roster.role-hidden",
            "report.item-name", "report.ready", "report.unavailable", "report.winner-innocents",
            "shop.bought", "shop.insufficient", "shop.unavailable", "team.message",
            "chat.match-message", "chat.spectator-message",
            "admin.arena-unavailable", "admin.start-recovery-pending", "debug.disabled", "debug.usage",
            "debug.applied", "debug.rejected", "debug.reason.mutations-disabled", "debug.reason.wrong-node",
            "debug.reason.arena-unavailable", "debug.reason.busy", "debug.reason.insufficient-players",
            "debug.reason.no-match", "debug.reason.wrong-phase", "debug.reason.player-not-found",
            "debug.reason.not-participant", "debug.reason.not-alive", "debug.reason.invalid-argument",
            "debug.reason.role-invariant", "debug.reason.inventory-full", "debug.reason.body-not-found",
            "debug.reason.precondition-failed", "debug.reason.internal-error",
        )

        val REQUIRED_LISTS = setOf(
            "menu.main.ttt-lore", "menu.main.join-lore", "menu.main.join-unavailable-lore",
            "menu.main.leave-lore", "menu.main.status-lore",
            "menu.main.stats-lore", "menu.main.help-lore", "menu.main.admin-lore",
            "menu.main.roster-lore", "menu.main.report-lore", "menu.help.weapons-lore",
            "menu.help.flow-lore",
            "menu.roster.player-lore", "menu.body.victim-lore", "menu.body.cause-lore",
            "menu.report.summary-lore", "menu.report.player-lore", "menu.combat.entry-lore",
            "menu.help.innocent-lore", "menu.help.traitor-lore", "menu.help.detective-lore",
            "menu.common.back-lore", "menu.admin.status-lore", "menu.shop.credits-lore",
            "weapon.pistol-lore", "weapon.smg-lore", "weapon.shotgun-lore", "weapon.rifle-lore",
            "weapon.ammunition-lore", "report.item-lore", "hud.preparing-tips", "guide.item-lore",
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
