package ru.ruscrafting.events.paper

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.TextColor
import net.kyori.adventure.text.format.TextDecoration
import org.bukkit.entity.TextDisplay
import kotlin.math.max

/** A read-only presentation snapshot; ammunition and reloads stay with their gameplay owner. */
internal data class FirearmAmmoHudState(
    val loaded: Int,
    val capacity: Int,
    val reserve: Int? = null,
    val creative: Boolean = false,
    val reloading: Boolean = false,
    val zoom: Float = 1f,
    val burstSize: Int? = null,
) {
    init {
        require(capacity in 1..64 && loaded in 0..capacity)
        require(reserve == null || reserve >= 0)
        require(zoom.isFinite() && zoom >= 1f)
        require(burstSize == null || burstSize in 1..capacity)
    }
}

internal fun firearmAmmoHudText(
    state: FirearmAmmoHudState,
    localized: (String, Map<String, Component>) -> Component,
): Component {
    val accent = when {
        state.loaded == 0 -> 0xFF8B82
        state.loaded <= max(1, state.capacity / 4) -> 0xFFBE6B
        else -> 0xF4D7A1
    }
    val output = Component.text().decoration(TextDecoration.ITALIC, false)
    if (state.creative) {
        output.append(localized("weapon.ammo-hud-creative", emptyMap()))
    } else {
        output.append(localized("weapon.ammo-hud-count", mapOf(
            "loaded" to Component.text(state.loaded, TextColor.color(accent)),
            "capacity" to Component.text(state.capacity),
        )))
        state.reserve?.let { output.append(localized("weapon.ammo-hud-reserve", mapOf("reserve" to Component.text(it)))) }
        output.append(Component.newline())
        for (round in 0 until state.capacity) {
            output.append(Component.text("|", TextColor.color(if (round < state.loaded) accent else 0x526170)))
        }
    }
    output.append(Component.newline()).append(localized("weapon.ammo-hud-controls", emptyMap()))
    val reloadKey = when {
        state.reloading -> "weapon.ammo-hud-reloading"
        !state.creative && state.loaded == 0 -> "weapon.ammo-hud-empty"
        else -> "weapon.ammo-hud-reload"
    }
    output.append(Component.newline()).append(localized(reloadKey, emptyMap()))
    state.burstSize?.let { output.append(localized("weapon.settings.hud-count", mapOf("rounds" to Component.text(it)))) }
    return output.build()
}

internal fun FirearmAmmoHudState.hudFrame(localized: (String, Map<String, Component>) -> Component) =
    EventActionHudFrame(firearmAmmoHudText(this, localized), zoom, TextDisplay.TextAlignment.LEFT)
