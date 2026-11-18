package ru.ruscrafting.events.paper

import net.kyori.adventure.text.Component
import org.bukkit.GameMode
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import ru.arc.paper.menu.*
import ru.ruscrafting.events.config.ArcEventsLocale
import ru.ruscrafting.events.domain.FirearmSpec
import java.util.UUID

private val BURST = PaperDialogInputId.of("rounds")
private val DAMAGE = PaperDialogInputId.of("damage")
private val SPREAD = PaperDialogInputId.of("spread")

/** Both the native form and its older-client command edit one still-held, authorized item. */
internal class FirearmSettingsDialog(
    private val runtime: PaperDialogRuntime,
    private val locale: ArcEventsLocale,
    private val firearms: TttFirearms,
    private val specFor: (Player) -> FirearmSpec?,
    private val cancelFire: (UUID) -> Unit,
    private val native: (Player) -> Boolean,
    private val escapeCloses: (Player) -> Boolean,
) {
    private data class Held(val item: ItemStack, val slot: Int, val world: UUID, val mode: GameMode, val base: FirearmSpec)

    fun open(player: Player, admin: Boolean, args: List<String>, quiet: Boolean): Boolean {
        if (admin && !player.hasPermission("arcevents.admin")) {
            player.sendEventMessage(locale.render("command.no-permission", player))
            return true
        }
        val base = specFor(player)
        if (base == null) {
            if (!quiet) tell(player, "hold")
            return !quiet
        }
        cancelFire(player.uniqueId)
        val held = Held(player.inventory.itemInMainHand.clone(), player.inventory.heldItemSlot, player.world.uid, player.gameMode, base)
        if (args.isNotEmpty()) {
            if (admin) applyTuning(player, held, args) else applyBurst(player, held, args.singleOrNull())
        } else if (native(player)) {
            runtime.beginFlow(player)
            show(player, held, admin)
        } else {
            tell(player, if (admin) "admin-command" else "burst-command", base)
        }
        return true
    }

    private fun current(player: Player, held: Held, admin: Boolean): Boolean {
        if (admin && !player.hasPermission("arcevents.admin")) {
            player.sendEventMessage(locale.render("command.no-permission", player))
            return false
        }
        if (player.isOnline && !player.isDead && player.world.uid == held.world && player.gameMode == held.mode &&
            player.inventory.heldItemSlot == held.slot && player.inventory.itemInMainHand == held.item && specFor(player) == held.base
        ) return true
        tell(player, "changed")
        return false
    }

    private fun applyBurst(player: Player, held: Held, raw: String?) {
        if (!current(player, held, false)) return
        val size = when (raw?.lowercase()) {
            "default" -> null
            "full" -> held.base.magazineSize
            else -> raw?.toIntOrNull()?.takeIf { it in 1..held.base.magazineSize }
                ?: return tell(player, "burst-command", held.base)
        }
        player.inventory.setItemInMainHand(firearms.setBurst(held.item, held.base, size))
        tell(player, "burst-saved", held.base, mapOf("rounds" to locale.text(size ?: held.base.roundsPerShot)))
    }

    private fun applyTuning(player: Player, held: Held, args: List<String>) {
        if (!current(player, held, true)) return
        val reset = args.size == 1 && args[0].equals("default", true)
        fun value(index: Int) = args.getOrNull(index)?.replace(',', '.')?.toDoubleOrNull()
        val damage = if (reset) held.base.damagePerPellet else value(0)
        val spread = if (reset) held.base.spreadDegrees else value(1)
        if ((!reset && args.size != 2) || damage == null || damage !in 0.1..40.0 || spread == null || spread !in 0.0..30.0) {
            tell(player, "admin-command")
            return
        }
        val copy = firearms.setTuning(held.item, held.base, damage, spread)
        val match = firearms.state(copy)
        if (match == null) firearms.refreshWorldLore(held.item, copy, player, held.base)
        player.inventory.setItemInMainHand(if (match == null) copy else firearms.updateLoaded(copy, player, match.loaded))
        tell(player, "tuning-saved", values = mapOf("damage" to locale.text(damage), "spread" to locale.text(spread)))
    }

    private fun show(player: Player, held: Held, admin: Boolean) {
        if (!current(player, held, admin)) return
        val base = held.base
        val tuned = firearms.tunedSpec(held.item, base)
        val values = mapOf("weapon" to (held.item.itemMeta.displayName() ?: locale.render("weapon.${base.id.name.lowercase()}-name", player)))
        fun text(key: String) = TttItems.nonItalic(locale.render("weapon.settings.$key", player, values))
        fun button(id: String, key: String, close: Boolean = true, act: (PaperDialogClickContext) -> Unit) =
            PaperDialogButton(PaperDialogActionId.of(id), text(key), width = 200,
                closeDialogBeforeAction = close, onClick = PaperDialogClickHandler(act))
        val buttons = buildList {
            if (admin) {
                add(button("save", "save") { applyTuning(it.player, held, listOf(it.text(DAMAGE).orEmpty(), it.text(SPREAD).orEmpty())) })
                add(button("reset", "default-tuning") { applyTuning(it.player, held, listOf("default")) })
            } else {
                if (base.magazineSize > 1) {
                    add(button("save", "save") { response ->
                        val value = response.number(BURST)
                        applyBurst(response.player, held, value?.takeIf { it.isFinite() && it % 1f == 0f }?.toInt()?.toString())
                    })
                    add(button("full", "full") { applyBurst(it.player, held, "full") })
                    if (firearms.selectedBurst(held.item, base) != null) add(button("reset", "default-burst") { applyBurst(it.player, held, "default") })
                }
                if (player.hasPermission("arcevents.admin")) add(button("tune", "tune", false) { show(it.player, held, true) })
            }
        }
        val description = if (admin) "tuning-help" else if (base.magazineSize == 1) "single-help" else "burst-help"
        runtime.open(player, PaperDialogScreen(
            title = text(if (admin) "tuning-title" else "burst-title"),
            body = listOf(PaperDialogBody(text("held"), 320), PaperDialogBody(text(description), 320)),
            inputs = if (admin) listOf(
                PaperDialogTextInput(DAMAGE, text("damage"), tuned.damagePerPellet.toString(), width = 200, maxLength = 22),
                PaperDialogTextInput(SPREAD, text("spread"), tuned.spreadDegrees.toString(), width = 200, maxLength = 22),
            ) else emptyList(),
            numberInputs = if (!admin && base.magazineSize > 1) listOf(PaperDialogNumberRangeInput(
                BURST, text("count"), 1f, base.magazineSize.toFloat(),
                (firearms.selectedBurst(held.item, base) ?: base.roundsPerShot).toFloat(), step = 1f, width = 200,
            )) else emptyList(),
            buttons = buttons, exitButton = PaperDialogButton(PaperDialogActionId.of("close"),
                TttItems.nonItalic(locale.render("menu.common.close-name", player)), width = 200,
                closeDialogBeforeAction = true, onClick = PaperDialogClickHandler {}),
            id = if (admin) "events.weapon.tune" else "events.weapon.burst",
        ), reopen = { show(player, held, admin) }, onDismiss = {}, closeOnEscape = escapeCloses(player))
    }

    private fun tell(player: Player, key: String, base: FirearmSpec? = null, values: Map<String, Component> = emptyMap()) {
        player.sendEventMessage(locale.render("weapon.settings.$key", player,
            if (base == null) values else values + ("capacity" to locale.text(base.magazineSize))))
    }
}
