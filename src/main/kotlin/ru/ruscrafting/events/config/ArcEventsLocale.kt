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
            "phase.idle", "phase.reserved", "phase.preparing", "phase.countdown", "phase.active",
            "phase.resolving", "phase.restoring", "phase.completed", "phase.cancelled",
            "menu.main.title", "menu.help.title", "menu.admin.title", "menu.shop.title",
            "queue.joined", "queue.left", "queue.unavailable", "queue.reserved",
            "match.preparing-title", "match.preparing-subtitle", "match.started", "match.eliminated-title",
            "match.eliminated-subtitle", "match.restored",
            "role.innocent-title", "role.traitor-title", "role.detective-title", "role.actionbar",
            "loadout.blade", "loadout.crossbow", "loadout.rations", "loadout.ammunition",
            "body.unidentified", "body.identified", "body.discovered", "body.dna",
            "shop.bought", "shop.insufficient", "shop.unavailable", "team.message",
            "admin.host-only", "admin.arena-unavailable", "debug.disabled",
        )

        val REQUIRED_LISTS = setOf(
            "menu.main.ttt-lore", "menu.main.join-lore", "menu.main.leave-lore", "menu.main.status-lore",
            "menu.main.stats-lore", "menu.main.help-lore", "menu.main.admin-lore",
            "menu.help.innocent-lore", "menu.help.traitor-lore", "menu.help.detective-lore",
            "menu.common.back-lore", "menu.admin.status-lore", "menu.shop.credits-lore",
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
