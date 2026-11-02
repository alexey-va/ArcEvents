package ru.ruscrafting.events.paper

import org.bukkit.Sound
import ru.ruscrafting.events.domain.FirearmId

/** Visual sampling only. The reload owner remains authoritative for time, ammunition and cancellation. */
internal object FirearmReloadAnimation {
    const val FRAME_COUNT = 40
    val modelKeys = FirearmId.entries.flatMap { id -> (0 until FRAME_COUNT).map { model(id, it, FRAME_COUNT) } }.toSet()

    fun frame(elapsedTicks: Int, durationTicks: Int): Int {
        require(durationTicks > 0)
        return if (durationTicks == 1) FRAME_COUNT - 1
        else (elapsedTicks.coerceIn(0, durationTicks - 1).toLong() * (FRAME_COUNT - 1) / (durationTicks - 1)).toInt()
    }

    fun model(id: FirearmId, elapsedTicks: Int, durationTicks: Int): String =
        "arc:event_sights/${id.name.lowercase()}_reload_${frame(elapsedTicks, durationTicks).toString().padStart(2, '0')}"

    data class Cue(val progress: Double, val sound: Sound, val pitch: Float)

    private fun mechanical(at: Double, pitch: Float = 1f) = Cue(at, Sound.ITEM_CROSSBOW_LOADING_MIDDLE, pitch)
    private fun close(at: Double, pitch: Float = 1f) = Cue(at, Sound.BLOCK_IRON_TRAPDOOR_CLOSE, pitch)
    private fun slide(at: Double, pitch: Float = 1f) = Cue(at, Sound.ITEM_CROSSBOW_LOADING_END, pitch)

    fun cues(id: FirearmId): List<Cue> = when (id) {
        FirearmId.FLINTLOCK -> listOf(mechanical(.24, .75f), mechanical(.51, .65f), mechanical(.65, .8f), close(.86, 1.5f))
        FirearmId.REVOLVER -> listOf(close(.20, 1.4f), mechanical(.32, 1.5f), mechanical(.63, 1.2f), close(.82, 1.6f))
        FirearmId.HAND_CANNON -> listOf(mechanical(.30, .6f), mechanical(.52, .5f), mechanical(.69, .65f), close(.88, .8f))
        FirearmId.DOUBLE_BARREL -> listOf(close(.22, .9f), mechanical(.54, 1.1f), mechanical(.63, 1.2f), close(.83, .95f))
        FirearmId.FIVE_SEVEN -> listOf(mechanical(.20, 1.6f), close(.63, 1.8f), slide(.83, 1.5f))
        FirearmId.G36 -> listOf(mechanical(.26, 1.2f), close(.65, 1.3f), slide(.86, 1.25f))
        FirearmId.AEK_971 -> listOf(mechanical(.24, .95f), close(.65, 1.05f), slide(.85, 1.1f))
        FirearmId.RPL_20 -> listOf(close(.21, .7f), mechanical(.33, .6f), close(.58, .75f), mechanical(.64, .9f), close(.77, .65f), slide(.90, .8f))
        FirearmId.VEPR_12 -> listOf(mechanical(.25, .8f), close(.68, .85f), slide(.88, .9f))
        FirearmId.M1_GARAND -> listOf(slide(.23, 1.35f), mechanical(.59, 1.5f), close(.77, 1.4f))
        FirearmId.VSS_VINTOREZ -> listOf(mechanical(.25, .85f), close(.65, .95f), slide(.84, .95f))
        FirearmId.MCMILLAN -> listOf(close(.18, .8f), slide(.28, .8f), mechanical(.39, .7f), close(.61, .85f), slide(.76, .8f), close(.87, .9f))
    }
}
