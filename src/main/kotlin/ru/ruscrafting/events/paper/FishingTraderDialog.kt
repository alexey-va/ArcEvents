package ru.ruscrafting.events.paper

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.JoinConfiguration
import net.kyori.adventure.text.format.TextColor
import net.kyori.adventure.text.format.TextDecoration
import org.bukkit.entity.Player
import ru.arc.paper.menu.*
import ru.ruscrafting.events.config.ArcEventsConfig
import ru.ruscrafting.events.config.ArcEventsLocale
import ru.ruscrafting.events.domain.*

/** The shared dialog runtime owns navigation; the live expedition owns every transaction. */
internal class FishingTraderDialog(
    private val runtime: PaperDialogRuntime,
    private val service: ArcEventsService,
    private val locale: ArcEventsLocale,
    private val settings: () -> ArcEventsConfig,
    private val escapeCloses: (Player) -> Boolean,
) {
    fun open(player: Player, armory: Boolean = false, feedback: String? = null) {
        val adventure = service.fishingAdventure(player)
        if (adventure == null || !adventure.canTrade(player)) {
            runtime.close(player)
            player.sendEventActionBar(locale.render("fishing.trader.nearby", player))
            return
        }
        if (!settings().ui.dialogsEnabled || ClientProtocolResolver().resolve(player) < MIN_DIALOG_PROTOCOL) {
            player.sendEventActionBar(locale.render("fishing.trader.client-required", player))
            return
        }
        val state = adventure.snapshot()
        runtime.open(player, model(player, state, armory, feedback) { action ->
            // A reopened or expired screen cannot spend against a different run or snapshot.
            if (service.fishingAdventure(player) !== adventure || !adventure.canTrade(player)) {
                runtime.close(player)
                return@model
            }
            if (action == "armory") {
                open(player, true)
            } else {
                val changed = adventure.trade(player, state, action)
                if (adventure.canTrade(player)) open(player, armory, if (changed) "done" else "changed")
                else runtime.close(player)
            }
        }, reopen = { open(player, armory) }, onDismiss = {}, closeOnEscape = escapeCloses(player))
    }

    internal fun model(
        player: Player,
        state: FishingProgress,
        armory: Boolean,
        feedback: String? = null,
        action: (String) -> Unit,
    ): PaperDialogScreen {
        val values = mapOf(
            "coins" to locale.text(state.coins),
            "bag" to locale.text(state.bag.size),
            "value" to locale.text(state.bag.firstOrNull()?.value ?: 0),
            "stage" to locale.text(state.stage + 1),
            "catches" to locale.text(state.catchesOnStage),
            "needed" to locale.text(state.rules.catchesPerIsland),
            "rod" to locale.text(state.rodLevel + 1),
        )
        fun text(key: String, extra: Map<String, Component> = emptyMap()) =
            clean(locale.render("fishing.trader.$key", player, values + extra), BODY)
        fun button(id: String, label: Component, tooltip: Component, available: Boolean, selected: Boolean = false, choice: Boolean = false) =
            PaperDialogButton(
                PaperDialogActionId.of(id.replace(':', '_').lowercase()),
                Component.empty().append(when {
                    selected -> Component.text("✔ ", GREEN)
                    !available -> clean(locale.render("menu.common.unavailable-prefix", player), WHITE)
                    choice -> Component.text("○ ", WHITE)
                    else -> Component.empty()
                }).append(clean(label, if (selected || available && !choice) GREEN else WHITE)).decoration(TextDecoration.ITALIC, false),
                tooltip = clean(tooltip, BODY), width = 230,
                onClick = PaperDialogClickHandler { if (available && !selected) action(id) },
            )
        val buttons = if (armory) FishingGear.entries.map { gear ->
            val owned = gear in state.ownedGear
            val selected = state.equippedGear == gear
            val offer = FishingOffer.entries.firstOrNull { it.gear == gear }
            val enabled = gear.firearmId == null || settings().weapons.enabled
            val available = enabled && (owned || offer != null && state.buy(offer) != state)
            val price = offer?.let { state.rules.prices.getValue(it) } ?: 0
            val name = locale.render("fishing.gear.${gear.name.lowercase()}-name", player)
            val label = if (owned) name else text("purchase", mapOf("item" to name, "price" to locale.text(price)))
            val details = mapOf("island" to locale.text(gear.unlockStage + 1), "price" to locale.text(price))
            button(if (owned) "equip:${gear.name}" else "buy:${offer!!.name}", label,
                join(listOf(text(if (owned) "equip-help" else "gear-help", details),
                    locale.render("fishing.gear.${gear.name.lowercase()}-description", player))), available, selected, owned)
        } else buildList {
            add(button("feed", text("feed"), text("feed-help"), state.feedCatch() != state))
            add(button("eat", text("eat"), text("eat-help"), state.bag.isNotEmpty() &&
                (player.health < (player.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH)?.value ?: 20.0) || player.foodLevel < 20)))
            val trophy = state.phase == FishingPhase.TROPHY
            add(button(if (trophy) "trophy" else "bait", text(if (trophy) "trophy" else "bait"),
                text(if (trophy) "trophy-help" else "bait-help"),
                if (trophy) state.handInTrophy() != state else state.claimBossBait() != state))
            add(PaperDialogButton(PaperDialogActionId.of("armory"), clean(text("armory"), GOLD),
                text("armory-help"), 230, onClick = PaperDialogClickHandler { action("armory") }))
            listOf(FishingOffer.AMMO, FishingOffer.DYNAMITE, FishingOffer.ROD_ONE, FishingOffer.ROD_TWO).forEach { offer ->
                val key = offer.name.lowercase()
                val details = mapOf("price" to locale.text(state.rules.prices.getValue(offer)))
                add(button("buy:${offer.name}", text(key, details), text("$key-help", details),
                    state.buy(offer) != state && (offer != FishingOffer.AMMO || settings().weapons.enabled)))
            }
        }
        val quest = when {
            state.phase == FishingPhase.TROPHY -> "quest-trophy"
            state.bossBait -> "quest-cast"
            state.catchesOnStage >= state.rules.catchesPerIsland -> "quest-ready"
            else -> "quest-catches"
        }
        return PaperDialogScreen(
            title = clean(text(if (armory) "armory-title" else "title"), GOLD),
            body = buildList {
                add(PaperDialogBody(clean(locale.render("fishing.island.${FishingArenaStage.entries[state.stage].name.lowercase()}", player), GOLD), 468))
                add(PaperDialogBody(text("status"), 468))
                add(PaperDialogBody(text(quest), 468))
                add(PaperDialogBody(text("session-only"), 468))
                feedback?.let { add(PaperDialogBody(text(it), 468)) }
            },
            buttons = buttons,
            exitButton = PaperDialogButton(PaperDialogActionId.of("back"), clean(locale.render("menu.common.back-name", player), WHITE),
                width = 200, onClick = PaperDialogClickHandler {}),
            columns = 2,
            id = if (armory) "events.fishing.armory" else "events.fishing.trader",
        )
    }

    private fun join(lines: List<Component>) = Component.join(JoinConfiguration.newlines(), listOf(Component.empty()) + lines + Component.empty())
    private fun clean(value: Component, color: TextColor): Component = value.color(color)
        .decoration(TextDecoration.ITALIC, false).children(value.children().map { clean(it, color) })

    private companion object {
        val WHITE = TextColor.fromHexString("#ffffff")!!
        val GREEN = TextColor.fromHexString("#9bd48d")!!
        val GOLD = TextColor.fromHexString("#f4d87a")!!
        val BODY = TextColor.fromHexString("#e8dfd2")!!
    }
}
