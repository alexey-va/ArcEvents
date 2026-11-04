package ru.ruscrafting.events.paper

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.TextDecoration
import org.bukkit.entity.Player
import ru.arc.paper.menu.*
import ru.ruscrafting.events.config.ArcEventsLocale
import ru.ruscrafting.events.domain.ZombiesOffer

/** Physical station proximity and a captured match snapshot gate the one native purchase action. */
internal class ZombiesSupplyDialog(
    private val runtime: PaperDialogRuntime,
    private val service: ArcEventsService,
    private val locale: ArcEventsLocale,
) {
    fun open(player: Player, offer: ZombiesOffer) {
        val adventure = service.zombiesAdventure(player)
        if (adventure == null || !adventure.canTrade(player, offer)) {
            runtime.close(player)
            player.sendEventActionBar(locale.render("zombies.supply-nearby", player))
            return
        }
        val state = adventure.director.players.getValue(player.uniqueId)
        val price = adventure.director.price(player.uniqueId, offer)
        val values = mapOf("price" to locale.text(price), "points" to locale.text(state.points), "power" to locale.text(state.power))
        fun text(key: String): Component = locale.render(key, player, values).decoration(TextDecoration.ITALIC, false)
        val available = adventure.director.canBuy(player.uniqueId, offer)
        val capped = offer == ZombiesOffer.POWER && state.power == 3 || offer == ZombiesOffer.VITALITY && state.vitality
        runtime.open(player, PaperDialogScreen(
            title = text("zombies.offer.${offer.name.lowercase()}"),
            body = buildList {
                add(DialogTables.filledBody(listOf(listOf(text("zombies.supply-balance")), listOf(text("zombies.supply-price"))),
                    headers = null, width = 320, columnWeights = listOf(1)))
                add(PaperDialogBody(text("zombies.offer.${offer.name.lowercase()}-help"), 340))
                if (!available) add(PaperDialogBody(text(if (capped) "zombies.supply-maxed" else "zombies.supply-poor"), 340))
                add(PaperDialogBody(text("zombies.supply-session-only"), 340))
            },
            buttons = if (available) listOf(PaperDialogButton(PaperDialogActionId.of("buy"), text("zombies.supply-buy"),
                width = 240, closeDialogBeforeAction = true, onClick = PaperDialogClickHandler {
                    val changed = service.zombiesAdventure(player) === adventure && adventure.buy(player, state, offer)
                    player.sendEventActionBar(locale.render(if (changed) "zombies.supply-done" else "zombies.supply-changed", player))
                })) else emptyList(),
            exitButton = PaperDialogButton(PaperDialogActionId.of("close"), text("menu.common.close-name"),
                width = 160, closeDialogBeforeAction = true, onClick = PaperDialogClickHandler {}),
            columns = 1, id = "events.zombies.supply",
        ), reopen = { open(player, offer) }, onDismiss = {}, closeOnEscape = true)
    }
}
