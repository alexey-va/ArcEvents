package ru.ruscrafting.events.paper

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.TextColor
import net.kyori.adventure.text.format.TextDecoration
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.Color
import org.bukkit.FluidCollisionMode
import org.bukkit.GameMode
import org.bukkit.Location
import org.bukkit.entity.Display
import org.bukkit.entity.Player
import org.bukkit.entity.TextDisplay
import org.bukkit.util.Transformation
import org.bukkit.util.Vector
import org.joml.Quaternionf
import org.joml.Vector3f
import ru.arc.paper.display.DisplayGeometry
import ru.arc.paper.display.PacketTextDisplay
import ru.arc.paper.display.PaperPacketDisplays
import ru.arc.paper.menu.DialogTextLayout
import ru.ruscrafting.events.config.ArcEventsLocale
import java.util.UUID
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.tan

/** A read-only presentation snapshot; ammunition and reloads stay with their gameplay owner. */
internal data class FirearmAmmoHudState(
    val loaded: Int,
    val capacity: Int,
    val reserve: Int? = null,
    val creative: Boolean = false,
    val reloading: Boolean = false,
    val zoom: Float = 1f,
) {
    init {
        require(capacity in 1..64 && loaded in 0..capacity)
        require(reserve == null || reserve >= 0)
        require(zoom.isFinite() && zoom >= 1f)
    }
}

/** One private native passenger per armed player. The service owns the tick and event cleanup. */
internal class FirearmAmmoHud(
    private val displays: PaperPacketDisplays,
    private val locale: ArcEventsLocale,
    private val frameProvider: (Player) -> FirearmAmmoHudState?,
) : AutoCloseable {
    private data class Card(
        val display: PacketTextDisplay,
        val worldId: UUID,
        var text: Component,
        var bounds: FirearmAmmoHudBounds,
        var transformation: Transformation,
        var clipFraction: Double,
    )

    private val cards = mutableMapOf<UUID, Card>()
    private var closed = false

    fun tick(players: Iterable<Player>) {
        if (closed) return
        val present = HashSet<UUID>()
        for (player in players) {
            present += player.uniqueId
            refresh(player)
        }
        cards.keys.filter { it !in present }.forEach(::clear)
    }

    fun refresh(player: Player) {
        if (closed) return
        val state = if (player.isOnline && player.isValid && !player.isDead &&
            player.gameMode != GameMode.SPECTATOR) frameProvider(player) else null
        if (state == null) {
            clear(player.uniqueId)
            return
        }
        val at = player.location
        if (!player.world.isChunkLoaded(at.blockX shr 4, at.blockZ shr 4)) {
            clear(player.uniqueId)
            return
        }
        var card = cards[player.uniqueId]
        if (card != null && (card.worldId != player.world.uid || !card.display.isValid)) {
            clear(player.uniqueId)
            card = null
        }
        val text = firearmAmmoHudText(state) { key, values -> locale.render(key, player, values) }
        val bounds = if (card?.text == text) card.bounds else firearmAmmoHudBounds(text)
        val passengerGap = player.height - player.eyeHeight
        val pose = firearmAmmoHudPose(bounds, state.zoom, passengerGap)
        val eye = player.eyeLocation
        val forward = eye.direction.normalize()
        val yaw = Math.toRadians(eye.yaw.toDouble())
        val right = Vector(-cos(yaw), 0.0, -sin(yaw))
        val down = forward.clone().crossProduct(right).normalize()
        val center = forward.clone().multiply(pose.forward)
            .add(right.clone().multiply(pose.right)).add(down.clone().multiply(pose.down))
        val fraction = DisplayGeometry.rectangleSamples(center, right, down,
            bounds.halfWidth * pose.scale, bounds.verticalRadius * pose.scale).minOf { offset ->
            val distance = offset.length()
            val direction = offset.clone().normalize()
            val loadedRange = firearmLoadedRange(eye.toVector(), direction, distance, player.world::isChunkLoaded)
            val hit = if (loadedRange > 0.0) player.world.rayTraceBlocks(eye, direction, loadedRange,
                FluidCollisionMode.NEVER, true)?.hitPosition?.distance(eye.toVector()) else null
            val obstacle = hit ?: loadedRange.takeIf { it < distance }
            if (obstacle == null) 1.0 else
                DisplayGeometry.clipDistance(distance, obstacle, .18, .12) / distance
        }
        val transform = firearmAmmoHudTransformation(pose, bounds, eye, passengerGap, fraction)
        if (card == null) {
            val display = displays.spawnText(at, text)
            try {
                display.apply {
                    isVisibleByDefault = false
                    billboard = Display.Billboard.CENTER
                    lineWidth = bounds.pixelWidth
                    alignment = TextDisplay.TextAlignment.LEFT
                    textOpacity = 0xFF.toByte()
                    isShadowed = true
                    isSeeThrough = false
                    isDefaultBackground = false
                    backgroundColor = Color.fromARGB(152, 11, 17, 24)
                    brightness = Display.Brightness(15, 15)
                    // The camera-local card is away from the passenger anchor; disable anchor culling.
                    displayWidth = 0f
                    displayHeight = 0f
                    viewRange = 2f
                    interpolationDuration = 1
                    teleportDuration = 0
                    transformation = transform
                    attachTo(player)
                    showTo(player)
                }
                cards[player.uniqueId] = Card(display, player.world.uid, text, bounds, transform, fraction)
            } catch (failure: Exception) {
                display.remove()
                throw failure
            }
        } else {
            if (card.text != text) {
                card.display.text(text)
                card.display.lineWidth = bounds.pixelWidth
                card.text = text
                card.bounds = bounds
            }
            if (card.transformation.translation.distanceSquared(transform.translation) >= .000001f ||
                abs(card.transformation.scale.x - transform.scale.x) >= .00005f) {
                // A wall pull-in snaps; pose/eye-height corrections use the same one-tick staff recipe.
                card.display.interpolationDuration = if (fraction < card.clipFraction - 1e-6) 0 else 1
                card.display.transformation = transform
                card.transformation = transform
                card.clipFraction = fraction
            }
        }
    }

    fun clear(playerId: UUID) { cards.remove(playerId)?.display?.remove() }

    override fun close() {
        if (closed) return
        closed = true
        cards.keys.toList().forEach(::clear)
        // The shared display owner belongs to ArcEventsService, not this feature.
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
            if (round > 0 && round % 5 == 0) output.append(Component.space())
            output.append(Component.text("|", TextColor.color(if (round < state.loaded) accent else 0x526170)))
        }
    }
    output.append(Component.newline()).append(localized("weapon.ammo-hud-controls", emptyMap()))
    val reloadKey = when {
        state.reloading -> "weapon.ammo-hud-reloading"
        !state.creative && state.loaded == 0 -> "weapon.ammo-hud-empty"
        else -> "weapon.ammo-hud-reload"
    }
    return output.append(Component.newline()).append(localized(reloadKey, emptyMap())).build()
}

internal data class FirearmAmmoHudBounds(val pixelWidth: Int, val lineCount: Int) {
    val halfWidth: Double get() = (pixelWidth + 2) * .025 / 2
    val verticalRadius: Double get() = (lineCount * 10 + 2) * .025 / 2
    // Native TextDisplay grows upward from its origin, independently of passenger height.
    val originUp: Double get() = (lineCount * 10 - 1) * .025 / 2
}

internal fun firearmAmmoHudBounds(text: Component): FirearmAmmoHudBounds {
    val lines = PlainTextComponentSerializer.plainText().serialize(text).lines()
    // Reuse the pinned shared font metrics. Unknown operator glyphs retain a conservative fallback.
    val width = DialogTextLayout.naturalWidth(text) ?: lines.maxOf { it.codePointCount(0, it.length) * 9 }
    return FirearmAmmoHudBounds(max(1, width + 2), lines.size)
}

internal data class FirearmAmmoHudPose(val forward: Double, val right: Double, val down: Double, val scale: Float)

internal fun firearmAmmoHudPose(
    bounds: FirearmAmmoHudBounds,
    zoom: Float = 1f,
    passengerGap: Double = .18,
): FirearmAmmoHudPose {
    // The server knows its scope multiplier, not the client's FOV setting. Match the native
    // 70-degree reference exactly so scoped cards keep their apparent size and screen margin.
    val compression = tan(Math.toRadians(35.0 / zoom)) / tan(Math.toRadians(35.0))
    val scale = .28
    // One upper-center location clears either hand, raised reload parts and centered optics.
    // Retain the staff HUD's head/eye error allowance independently of camera billboarding.
    val down = -max(1.30, bounds.verticalRadius * scale + max(.5, 2 * abs(passengerGap)) + 2.5 * .14)
    return FirearmAmmoHudPose(2.5, 0.0, down * compression, (scale * compression).toFloat())
}

/** Native CENTER billboarding owns camera rotation; only the head-to-eye gap is world-vertical. */
internal fun firearmAmmoHudTransformation(
    pose: FirearmAmmoHudPose,
    bounds: FirearmAmmoHudBounds,
    eye: Location,
    passengerGap: Double,
    clipFraction: Double = 1.0,
): Transformation {
    val forward = eye.direction.normalize()
    val yaw = Math.toRadians(eye.yaw.toDouble())
    val right = Vector(-cos(yaw), 0.0, -sin(yaw))
    val down = forward.clone().crossProduct(right).normalize()
    val correction = Vector(0.0, -passengerGap, 0.0)
    val scale = (pose.scale * clipFraction).toFloat()
    return Transformation(Vector3f(
        (pose.right * clipFraction + correction.dot(right)).toFloat(),
        (-pose.down * clipFraction - correction.dot(down) - bounds.originUp * scale).toFloat(),
        (-pose.forward * clipFraction - correction.dot(forward)).toFloat(),
    ), Quaternionf(), Vector3f(scale), Quaternionf())
}
