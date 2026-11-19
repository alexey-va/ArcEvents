package ru.ruscrafting.events.paper

import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.World
import org.bukkit.generator.ChunkGenerator
import org.bukkit.generator.WorldInfo
import ru.ruscrafting.events.domain.ZombiesOffer
import java.util.Random
import kotlin.math.floor

/** The authored Infection: Store department store, adapted for six barricades and the supply counter. */
class ZombiesArenaGenerator : ChunkGenerator() {
    override fun generateSurface(worldInfo: WorldInfo, random: Random, chunkX: Int, chunkZ: Int, chunkData: ChunkData) {
        store.paint(chunkData, chunkX, chunkZ, MIN_X, MIN_Y, MIN_Z)
    }

    override fun getFixedSpawnLocation(world: World, random: Random) = Location(world, .5, 65.0, -18.5)
    override fun shouldGenerateNoise() = false
    override fun shouldGenerateSurface() = false
    override fun shouldGenerateCaves() = false
    override fun shouldGenerateDecorations() = false
    override fun shouldGenerateMobs() = false
    override fun shouldGenerateStructures() = false

    companion object {
        const val TEMPLATE = "zombies-store-v1"
        internal const val MIN_X = -54
        internal const val MIN_Y = 63
        internal const val MIN_Z = -52
        internal val store by lazy { AuthoredArenaVolume.load("/arena-templates/$TEMPLATE/scene.schem") }
        val STATIONS = linkedMapOf(ZombiesOffer.AMMO to -12, ZombiesOffer.MYSTERY to -4,
            ZombiesOffer.POWER to 4, ZombiesOffer.VITALITY to 12)
        val WINDOWS = listOf(
            Window(-23, -19, 1, 0), Window(23, -19, -1, 0),
            Window(-23, -4, 1, 0), Window(23, -4, -1, 0),
            Window(-4, 9, 0, -1), Window(5, 9, 0, -1),
        )
        val SPAWNS = WINDOWS.map { floor(it.spawnX).toInt() to floor(it.spawnZ).toInt() }
        val MYSTERY_CHEST_X = -5..-4

        data class Window(val x: Int, val z: Int, val inwardX: Int, val inwardZ: Int) {
            val alongX get() = inwardZ
            val alongZ get() = -inwardX
            val spawnX get() = x + .5 - inwardX * 1.5
            val spawnZ get() = z + .5 - inwardZ * 1.5
            fun along(x: Double, z: Double) = (x - this.x - .5) * alongX + (z - this.z - .5) * alongZ
            fun inward(x: Double, z: Double) = (x - this.x - .5) * inwardX + (z - this.z - .5) * inwardZ
            fun gateCells() = (-1..1).map { offset -> Triple(x + alongX * offset, 65, z + alongZ * offset) }
        }

        internal fun materialAt(x: Int, y: Int, z: Int): Material? = store.materialAt(x - MIN_X, y - MIN_Y, z - MIN_Z)
        internal fun stateAt(x: Int, y: Int, z: Int): String? = store.stateAt(x - MIN_X, y - MIN_Y, z - MIN_Z)
    }
}
