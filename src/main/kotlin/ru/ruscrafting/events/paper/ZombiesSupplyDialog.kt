package ru.ruscrafting.events.paper

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.JoinConfiguration
import net.kyori.adventure.text.format.TextDecoration
import org.bukkit.entity.Player
import ru.arc.paper.menu.*
import ru.ruscrafting.events.config.ArcEventsLocale
import ru.ruscrafting.events.domain.ZombiesGear
import ru.ruscrafting.events.domain.ZombiesOffer
import ru.ruscrafting.events.domain.ZombiesSurvivor

/** Physical station proximity and a captured match snapshot gate the one native purchase action. */
internal class ZombiesSupplyDialog(
    private val runtime: PaperDialogRuntime,
    private val service: ArcEventsService,
    private val locale: ArcEventsLocale,
) {
    fun open(player: Player, offer: ZombiesOffer) {
        if (offer == ZombiesOffer.MYSTERY) {
            player.sendEventActionBar(locale.render("zombies.box-nearby", player))
            return
        }
        val adventure = service.zombiesAdventure(player)
        if (adventure == null || !adventure.canTrade(player, offer)) {
            runtime.close(player)
            player.sendEventActionBar(locale.render("zombies.supply-nearby", player))
            return
        }
        val state = adventure.director.players.getValue(player.uniqueId)
        val price = adventure.director.price(player.uniqueId, offer)
        val available = adventure.director.canBuy(player.uniqueId, offer)
        runtime.open(player, model(player, offer, state, price, available) {
            val changed = service.zombiesAdventure(player) === adventure && adventure.buy(player, state, offer)
            player.sendEventActionBar(locale.render(if (changed) "zombies.supply-done" else "zombies.supply-changed", player))
        }, reopen = { open(player, offer) }, onDismiss = {}, closeOnEscape = true)
    }

    fun openFieldKit(player: Player) {
        val adventure = service.zombiesAdventure(player)?.takeIf { it.canOpenFieldKit(player) } ?: return
        val state = adventure.director.players.getValue(player.uniqueId)
        val keys = "zombies.field."
        fun text(key: String, values: Map<String, Component> = emptyMap()) =
            locale.render(keys + key, player, values).decoration(TextDecoration.ITALIC, false)
        val values = mapOf("points" to locale.text(state.points), "copper" to locale.text(state.copper),
            "powder" to locale.text(state.powder), "iron" to locale.text(state.iron), "bench" to locale.text(state.bench))
        val actions = ZombiesGear.entries.filter { it.requiredBench <= state.bench && (it != ZombiesGear.BENCH || state.bench < 2) }
            .mapNotNull { gear ->
                if (!adventure.director.canBuyGear(player.uniqueId, gear)) return@mapNotNull null
                val id = gear.name.lowercase()
                val costs = mapOf("cost" to locale.text(gear.pointCost(state.bench)), "copper" to locale.text(gear.copper),
                    "powder" to locale.text(gear.powder), "iron" to locale.text(gear.iron))
                PaperDialogButton(PaperDialogActionId.of(id), text("$id-action", costs),
                    width = 156, closeDialogBeforeAction = true, onClick = PaperDialogClickHandler {
                        val current = service.zombiesAdventure(player)
                        val freshPrice = gear != ZombiesGear.BENCH || current?.director?.players?.get(player.uniqueId)?.bench == state.bench
                        val applied = current === adventure && freshPrice && adventure.fieldGear(player, gear)
                        player.sendEventActionBar(text(if (applied) "done" else "unavailable"))
                    })
            }
        val body = Component.join(JoinConfiguration.newlines(), text("balance", values), text("instructions"),
            if (state.bench < 2) text("recipes") else Component.empty())
        runtime.open(player, PaperDialogScreen(
            title = text("kit"),
            body = listOf(DialogTables.filledBody(listOf(listOf(body)), null, 328, listOf(1), DialogTables.PanelSkin.NAVY)),
            buttons = actions,
            exitButton = PaperDialogButton(PaperDialogActionId.of("close"), locale.render("menu.common.close-name", player),
                width = 156, closeDialogBeforeAction = true, onClick = PaperDialogClickHandler {}),
            columns = 2, id = "events.zombies.field",
        ), reopen = { openFieldKit(player) }, onDismiss = {}, closeOnEscape = true)
    }

    internal fun model(player: Player, offer: ZombiesOffer, state: ZombiesSurvivor, price: Int,
                       available: Boolean, buy: () -> Unit): PaperDialogScreen {
        require(offer != ZombiesOffer.MYSTERY) { "The mystery box is opened in the world" }
        val values = mapOf("price" to locale.text(price), "points" to locale.text(state.points), "power" to locale.text(state.power))
        fun text(key: String): Component = locale.render(key, player, values).decoration(TextDecoration.ITALIC, false)
        val capped = offer == ZombiesOffer.POWER && state.power == 3 || offer == ZombiesOffer.VITALITY && state.vitality
        val description = Component.join(JoinConfiguration.newlines(), buildList {
            add(text("zombies.offer.${offer.name.lowercase()}-help"))
            add(Component.empty())
            add(text("zombies.supply-session-only"))
            if (!available) add(text(if (capped) "zombies.supply-maxed" else "zombies.supply-poor"))
        })
        return PaperDialogScreen(
            title = text("zombies.offer.${offer.name.lowercase()}"),
            body = listOf(
                DialogTables.filledBody(listOf(listOf(description)), null, 320, listOf(1), DialogTables.PanelSkin.NAVY),
                DialogTables.factsBody(listOf(text("zombies.supply-balance-label") to locale.text(state.points),
                    text("zombies.supply-price-label") to text("zombies.supply-price-value")),
                    DialogTables.FactsLayout.METRICS, DialogTables.PanelSpec(DialogTables.PanelSkin.NAVY, 320, 2, false)),
            ),
            buttons = if (available) listOf(PaperDialogButton(PaperDialogActionId.of("buy"), text("zombies.supply-buy"),
                width = 240, closeDialogBeforeAction = true, onClick = PaperDialogClickHandler { buy() })) else emptyList(),
            exitButton = PaperDialogButton(PaperDialogActionId.of("close"), text("menu.common.close-name"),
                width = 160, closeDialogBeforeAction = true, onClick = PaperDialogClickHandler {}),
            columns = 1, id = "events.zombies.supply",
        )

    }
}
