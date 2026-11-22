package ru.ruscrafting.events.paper

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.TextColor
import org.bukkit.Color
import org.bukkit.entity.Display
import org.bukkit.entity.Player
import org.bukkit.util.Transformation
import org.joml.Quaternionf
import org.joml.Vector3f
import ru.arc.paper.display.PacketTextDisplay
import ru.arc.paper.display.PaperPacketDisplays
import java.util.UUID

/** One private, camera-facing mark per shooter. Another hit refreshes the same display. */
internal class FirearmHitMarkers(
    private val displays: PaperPacketDisplays,
    private val scopeZoom: (UUID) -> Float?,
) : AutoCloseable {
    private class Marker(val player: Player, val display: PacketTextDisplay, var until: Long) {
        val worldId = player.world.uid
        val entityId = player.entityId
        fun current() = player.isOnline && !player.isDead && player.world.uid == worldId && player.entityId == entityId
    }

    private val active = mutableMapOf<UUID, Marker>()

    fun show(player: Player, tick: Long) {
        val previous = active[player.uniqueId]
        if (previous != null) {
            if (previous.player === player && previous.current()) {
                previous.until = tick + VISIBLE_TICKS
                return
            }
            previous.display.remove()
            active.remove(player.uniqueId)
        }
        val display = displays.spawnText(player.location, CROSS)
        try {
            display.isVisibleByDefault = false
            display.pauseWhenNotObserved = false
            display.billboard = Display.Billboard.CENTER
            display.brightness = Display.Brightness(15, 15)
            display.isShadowed = true
            display.isSeeThrough = true
            display.isDefaultBackground = false
            display.backgroundColor = Color.fromARGB(0)
            display.transformation = transform(player)
            display.attachTo(player)
            display.showTo(player)
            active[player.uniqueId] = Marker(player, display, tick + VISIBLE_TICKS)
        } catch (failure: Exception) {
            display.remove()
            throw failure
        }
    }

    fun advance(tick: Long): Boolean {
        val iterator = active.values.iterator()
        while (iterator.hasNext()) {
            val marker = iterator.next()
            if (tick >= marker.until || !marker.current()) {
                marker.display.remove()
                iterator.remove()
            } else {
                marker.display.transformation = transform(marker.player)
                marker.display.textOpacity = if (marker.until - tick == 1L) 115 else -1
            }
        }
        return active.isNotEmpty()
    }

    private fun transform(player: Player): Transformation {
        val eye = scopeLaserCameraEyeOffset((player.eyeHeight - player.height).toFloat(), player.eyeLocation.pitch)
        val scale = 1.16f / (scopeZoom(player.uniqueId) ?: 1f)
        // At 1.21.11's .025 blocks/font pixel the 5×5 bitmap's centre sits (.5, 5.5) px above its anchor.
        val translation = eye.add(-.0125f * scale, -.1375f * scale, -4f)
        return Transformation(translation, Quaternionf(), Vector3f(scale), Quaternionf())
    }

    override fun close() {
        active.values.forEach { it.display.remove() }
        active.clear()
    }

    companion object {
        private const val VISIBLE_TICKS = 4L
        private val CROSS = Component.text("×", TextColor.color(0xFFF3DF))
    }
}
