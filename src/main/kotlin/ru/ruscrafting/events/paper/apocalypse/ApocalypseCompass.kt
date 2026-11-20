package ru.ruscrafting.events.paper.apocalypse

import net.kyori.adventure.bossbar.BossBar
import net.kyori.adventure.text.Component
import org.bukkit.Location
import org.bukkit.entity.Player
import ru.arc.dungeons.api.CompassStrip
import ru.ruscrafting.events.domain.apocalypse.ApocalypsePoint
import kotlin.math.atan2

/** One main-thread compass per expedition participant, using the same strip as the dungeon HUD. */
internal class ApocalypseCompass(private val player: Player) : AutoCloseable {
    private val bar = BossBar.bossBar(Component.empty(), 1f, BossBar.Color.WHITE, BossBar.Overlay.PROGRESS)
    private var previousLocation: Location? = null
    private var previousGuide: ApocalypseGuideSnapshot? = null

    fun refresh(guide: ApocalypseGuideSnapshot?) {
        val location = player.location
        if (location == previousLocation && guide === previousGuide) return
        val target = guide?.tracked?.let {
            guide.route(it, ApocalypsePoint(location.blockX, location.blockY, location.blockZ))?.point
        }
        bar.name(strip(location, target))
        if (previousLocation == null) player.showBossBar(bar)
        previousLocation = location
        previousGuide = guide
    }

    private fun strip(location: Location, target: ApocalypsePoint?): Component {
        val yaw = location.yaw
        if (target == null) return CompassStrip.render(yaw, emptyList())
        val dx = target.x + 0.5 - location.x
        val dz = target.z + 0.5 - location.z
        val nearby = dx * dx + dz * dz <= 4.0
        val glyph = when {
            nearby && target.y > location.blockY + 1 -> "↑"
            nearby && target.y < location.blockY - 1 -> "↓"
            else -> "◇"
        }
        val bearing = if (nearby) yaw.toDouble() else Math.toDegrees(atan2(-dx, dz))
        return CompassStrip.render(yaw, bearing, glyph, if (glyph == "◇") 0x9bd48d else 0xffcf70)
    }

    override fun close() = player.hideBossBar(bar)
}
