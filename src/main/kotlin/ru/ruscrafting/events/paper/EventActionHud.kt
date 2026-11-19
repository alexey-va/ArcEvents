package ru.ruscrafting.events.paper

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.Color
import org.bukkit.FluidCollisionMode
import org.bukkit.GameMode
import org.bukkit.Location
import org.bukkit.entity.Display
import org.bukkit.entity.Player
import org.bukkit.entity.TextDisplay
import org.bukkit.plugin.Plugin
import org.bukkit.util.Transformation
import org.bukkit.util.Vector
import org.joml.Quaternionf
import org.joml.Vector3f
import ru.arc.paper.display.DisplayGeometry
import ru.arc.paper.display.PacketTextDisplay
import ru.arc.paper.display.PaperPacketDisplays
import ru.arc.paper.menu.DialogTextLayout
import ru.arc.staffspells.api.QuestGuidanceHudService
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.logging.Level
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.tan

internal val EVENT_ACTION_HUD_BACKGROUND: Color = Color.fromARGB(192, 11, 17, 24)

internal data class EventActionHudFrame(
    val text: Component,
    val zoom: Float = 1f,
    val alignment: TextDisplay.TextAlignment = TextDisplay.TextAlignment.CENTER,
)

/** A player's current action and magazine share one private, camera-facing native passenger. */
internal class EventActionHud(
    private val displays: PaperPacketDisplays,
    private val suppressQuest: (UUID, Boolean) -> Unit,
    private val nanoTime: () -> Long = System::nanoTime,
    private val frameProvider: (Player) -> EventActionHudFrame?,
) : AutoCloseable {
    private data class Card(
        val display: PacketTextDisplay,
        val worldId: UUID,
        var frame: EventActionHudFrame,
        var bounds: EventActionHudBounds,
        var transformation: Transformation,
        var clipFraction: Double,
    )

    private val cards = mutableMapOf<UUID, Card>()
    private var lastRenewal = nanoTime()
    private var closed = false

    fun tick(players: Iterable<Player>) {
        if (closed) return
        val now = nanoTime()
        val renew = now - lastRenewal >= TimeUnit.SECONDS.toNanos(1)
        if (renew) lastRenewal = now
        val present = HashSet<UUID>()
        for (player in players) {
            present += player.uniqueId
            refresh(player, renew)
        }
        cards.keys.filter { it !in present }.forEach(::clear)
    }

    fun refresh(player: Player, renewSuppression: Boolean = false) {
        if (closed) return
        val frame = if (player.isOnline && player.isValid && !player.isDead &&
            player.gameMode != GameMode.SPECTATOR) frameProvider(player) else null
        if (frame == null) {
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
        val text = frame.text
        val bounds = if (card?.frame?.text == text) card.bounds else eventActionHudBounds(text)
        val passengerGap = player.height - player.eyeHeight
        val pose = eventActionHudPose(bounds, frame.zoom, passengerGap)
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
        val transform = eventActionHudTransformation(pose, bounds, eye, passengerGap, fraction)
        if (card == null) {
            val display = displays.spawnText(at, text)
            try {
                display.apply {
                    isVisibleByDefault = false
                    // A bite or rescue remains time-sensitive while the carrier stands still.
                    pauseWhenNotObserved = false
                    billboard = Display.Billboard.CENTER
                    lineWidth = bounds.pixelWidth
                    alignment = frame.alignment
                    textOpacity = 0xFF.toByte()
                    isShadowed = true
                    isSeeThrough = false
                    isDefaultBackground = false
                    backgroundColor = EVENT_ACTION_HUD_BACKGROUND
                    brightness = Display.Brightness(15, 15)
                    // The camera-local card is away from the passenger anchor; disable anchor culling.
                    displayWidth = 0f
                    displayHeight = 0f
                    viewRange = 2f
                    interpolationDuration = 1
                    teleportDuration = 0
                    transformation = transform
                    attachTo(player)
                    suppressQuest(player.uniqueId, true)
                    showTo(player)
                }
                cards[player.uniqueId] = Card(display, player.world.uid, frame, bounds, transform, fraction)
            } catch (failure: Exception) {
                try { display.remove() } finally { suppressQuest(player.uniqueId, false) }
                throw failure
            }
        } else {
            if (renewSuppression) suppressQuest(player.uniqueId, true)
            if (card.frame.text != text) {
                card.display.text(text)
                card.display.lineWidth = bounds.pixelWidth
                card.bounds = bounds
            }
            if (card.frame.alignment != frame.alignment) card.display.alignment = frame.alignment
            card.frame = frame
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

    fun clear(playerId: UUID) {
        val card = cards.remove(playerId) ?: return
        try { card.display.remove() } finally { suppressQuest(playerId, false) }
    }

    override fun close() {
        if (closed) return
        closed = true
        cards.keys.toList().forEach(::clear)
        // The shared display owner belongs to ArcEventsService, not this feature.
    }
}

/** ARC owns the API. ArcDungeons supplies the optional provider and expires abandoned leases. */
internal fun eventActionQuestSuppression(plugin: Plugin): (UUID, Boolean) -> Unit {
    var compatible = true
    var failed = false
    return { playerId, visible ->
        if (compatible) {
            try {
                plugin.server.servicesManager.load(QuestGuidanceHudService::class.java)
                    ?.setSuppressed(plugin, playerId, "event-action-hud", visible)
                failed = false
            } catch (oldApi: LinkageError) {
                compatible = false
                plugin.logger.log(Level.WARNING, "Quest hint suppression is unavailable in this ARC version", oldApi)
            } catch (failure: RuntimeException) {
                if (!failed) plugin.logger.log(Level.WARNING, "Could not update quest hint suppression for the event HUD", failure)
                failed = true
            }
        }
    }
}

internal data class EventActionHudBounds(val pixelWidth: Int, val lineCount: Int) {
    val halfWidth: Double get() = (pixelWidth + 2) * .025 / 2
    val verticalRadius: Double get() = (lineCount * 10 + 2) * .025 / 2
    // Native TextDisplay grows upward from its origin, independently of passenger height.
    val originUp: Double get() = (lineCount * 10 - 1) * .025 / 2
}

internal fun eventActionHudBounds(text: Component): EventActionHudBounds {
    val lines = PlainTextComponentSerializer.plainText().serialize(text).lines()
    // Reuse the pinned shared font metrics. Unknown operator glyphs retain a conservative fallback.
    val width = DialogTextLayout.naturalWidth(text) ?: lines.maxOf { it.codePointCount(0, it.length) * 9 }
    return EventActionHudBounds(max(1, width + 2), lines.size)
}

internal data class EventActionHudPose(val forward: Double, val right: Double, val down: Double, val scale: Float)

internal fun eventActionHudPose(
    bounds: EventActionHudBounds,
    zoom: Float = 1f,
    passengerGap: Double = .18,
): EventActionHudPose {
    // The server knows its scope multiplier, not the client's FOV setting. Match the native
    // 70-degree reference exactly so scoped cards keep their apparent size and screen margin.
    val compression = tan(Math.toRadians(35.0 / zoom)) / tan(Math.toRadians(35.0))
    // At the 70-degree reference this gives a 15px glyph at 720p, instead of the old 9px.
    val scale = .46
    // One upper-center location clears either hand, raised reload parts and centered optics.
    // Retain the staff HUD's head/eye error allowance independently of camera billboarding.
    val down = -max(1.30, bounds.verticalRadius * scale + max(.5, 2 * abs(passengerGap)) + 2.5 * .14)
    return EventActionHudPose(2.5, 0.0, down * compression, (scale * compression).toFloat())
}

/** Native CENTER billboarding owns camera rotation; only the head-to-eye gap is world-vertical. */
internal fun eventActionHudTransformation(
    pose: EventActionHudPose,
    bounds: EventActionHudBounds,
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
