package ru.ruscrafting.events.paper

import net.kyori.adventure.text.Component
import org.bukkit.entity.Player
import ru.arc.paper.api.ArcSidebarFrame
import ru.arc.paper.api.ArcSidebarHandle
import ru.ruscrafting.events.config.ArcEventsLocale
import java.util.UUID

class BuilderZombiesSidebar(
    private val sidebar: ArcSidebarHandle,
    private val locale: ArcEventsLocale,
) {
    fun update(player: Player, ready: Boolean, playing: Boolean) {
        val action = when {
            playing -> "leave"
            ready -> "ready"
            else -> "join"
        }
        val rows = buildList {
            if (player.hasPermission("arcevents.admin")) {
                add(locale.render("$GUIDE.admin-prefix", player))
                val commands = if (playing) "admin-playing" else "admin-start"
                addAll(locale.lore("$GUIDE.$commands", player))
                add(Component.empty())
            }
            addAll(locale.lore("$GUIDE.$action", player))
        }
        sidebar.show(player, ArcSidebarFrame(locale.render("$GUIDE.title", player), rows.take(ArcSidebarFrame.MAX_ROWS)))
    }

    fun hide(playerId: UUID) = sidebar.hide(playerId)

    private companion object {
        const val GUIDE = "zombies.builder-guide"
    }
}
