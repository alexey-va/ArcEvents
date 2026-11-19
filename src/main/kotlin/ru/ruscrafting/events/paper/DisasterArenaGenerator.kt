package ru.ruscrafting.events.paper

import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.World
import org.bukkit.generator.ChunkGenerator
import org.bukkit.generator.WorldInfo
import java.util.Random

/** GoldenOre's Pirate Cove, adapted with a storm wharf, an open lookout and a boarding ramp. */
class DisasterArenaGenerator : ChunkGenerator() {
    override fun generateSurface(worldInfo: WorldInfo, random: Random, chunkX: Int, chunkZ: Int, chunkData: ChunkData) {
        for (x in 0..15) for (z in 0..15) {
            if (insideScene(chunkX * 16 + x, chunkZ * 16 + z)) continue
            // Continue the sea past the painted shore so players see an ocean horizon from the wharf.
            chunkData.setBlock(x, SCENE_Y, z, Material.SANDSTONE)
            chunkData.setBlock(x, SCENE_Y + 1, z, Material.SAND)
            chunkData.setRegion(x, SCENE_Y + 2, z, x + 1, SEA_SURFACE + 1, z + 1, Material.WATER)
        }
        scene.paint(chunkData, chunkX, chunkZ, SCENE_X, SCENE_Y, SCENE_Z)
    }

    override fun getFixedSpawnLocation(world: World, random: Random) = Location(world, 0.5, 65.0, 0.5, SPAWN_YAW, SPAWN_PITCH)
    override fun shouldGenerateNoise() = false
    override fun shouldGenerateSurface() = false
    override fun shouldGenerateCaves() = false
    override fun shouldGenerateDecorations() = false
    override fun shouldGenerateMobs() = false
    override fun shouldGenerateStructures() = false

    companion object {
        const val TEMPLATE = "disasters-coast-v1"
        const val SPAWN_YAW = 128f
        const val SPAWN_PITCH = -5f
        const val MIN_X = -59
        const val MIN_Y = 51
        const val MIN_Z = -49
        const val MAX_X = 39
        const val MAX_Y = 130
        const val MAX_Z = 46
        const val FOG_SAFE_Y = 68.0
        private const val SCENE_X = -71
        private const val SCENE_Y = 50
        private const val SCENE_Z = -61
        private const val SEA_SURFACE = 63
        private val scene by lazy { AuthoredArenaVolume.load("/arena-templates/$TEMPLATE/scene.schem") }

        private fun insideScene(x: Int, z: Int) = x - SCENE_X in 0 until scene.width && z - SCENE_Z in 0 until scene.length

        fun materialAt(x: Int, y: Int, z: Int): Material? = if (insideScene(x, z)) {
            scene.materialAt(x - SCENE_X, y - SCENE_Y, z - SCENE_Z)
        } else when (y) {
            SCENE_Y -> Material.SANDSTONE
            SCENE_Y + 1 -> Material.SAND
            in SCENE_Y + 2..SEA_SURFACE -> Material.WATER
            else -> null
        }
    }
}
