package ru.ruscrafting.events.paper

import com.github.retrooper.packetevents.protocol.nbt.NBTByteArray
import com.github.retrooper.packetevents.protocol.nbt.NBTCompound
import com.github.retrooper.packetevents.protocol.nbt.NBTLimiter
import com.github.retrooper.packetevents.protocol.nbt.NBTList
import com.github.retrooper.packetevents.protocol.nbt.serializer.DefaultNBTSerializer
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.generator.ChunkGenerator
import java.io.DataInputStream
import java.util.zip.GZIPInputStream

/** Block-only Sponge v2 cutouts bundled with generated arenas. Coordinates start at the cutout minimum. */
internal class AuthoredArenaVolume private constructor(
    val width: Int,
    val height: Int,
    val length: Int,
    private val palette: List<String>,
    private val blocks: ShortArray,
) {
    private val materials = palette.map { state ->
        val material = Material.valueOf(state.removePrefix("minecraft:").substringBefore('[').uppercase())
        require(material !in prohibited) { "Invalid arena block: $state" }
        material
    }
    private val states by lazy { palette.map(Bukkit::createBlockData) }

    private fun indexAt(x: Int, y: Int, z: Int): Int =
        if (x in 0 until width && y in 0 until height && z in 0 until length)
            blocks[x + width * (z + length * y)].toInt() else 0

    fun materialAt(x: Int, y: Int, z: Int): Material? = indexAt(x, y, z).let { if (it == 0) null else materials[it] }

    fun stateAt(x: Int, y: Int, z: Int): String? = indexAt(x, y, z).let { if (it == 0) null else palette[it] }

    fun paint(chunkData: ChunkGenerator.ChunkData, chunkX: Int, chunkZ: Int, minX: Int, minY: Int, minZ: Int) {
        val xStart = chunkX * 16 - minX
        val zStart = chunkZ * 16 - minZ
        val fromX = maxOf(0, xStart)
        val endX = minOf(width, xStart + 16)
        val fromZ = maxOf(0, zStart)
        val endZ = minOf(length, zStart + 16)
        if (fromX >= endX || fromZ >= endZ) return
        val data = states
        for (y in maxOf(0, chunkData.minHeight - minY) until minOf(height, chunkData.maxHeight - minY)) {
            for (z in fromZ until endZ) {
                val row = width * (z + length * y)
                for (x in fromX until endX) {
                    val id = blocks[row + x].toInt()
                    if (id != 0) chunkData.setBlock(x - xStart, minY + y, z - zStart, data[id])
                }
            }
        }
    }

    companion object {
        private const val MAX_NBT_BYTES = 32 * 1024 * 1024
        private val prohibited = setOf(Material.COMMAND_BLOCK, Material.CHAIN_COMMAND_BLOCK,
            Material.REPEATING_COMMAND_BLOCK, Material.STRUCTURE_BLOCK, Material.JIGSAW, Material.SPAWNER)

        fun load(resource: String): AuthoredArenaVolume {
            val inflated = requireNotNull(AuthoredArenaVolume::class.java.getResourceAsStream(resource)) {
                "Missing authored arena resource $resource"
            }.use { stream -> GZIPInputStream(stream).use { it.readNBytes(MAX_NBT_BYTES + 1) } }
            require(inflated.size <= MAX_NBT_BYTES) { "Authored arena resource too large: $resource" }
            val input = DataInputStream(inflated.inputStream())
            val limiter = object : NBTLimiter {
                private var bytes = 0L
                private var depth = 0
                override fun increment(count: Int) { require(count >= 0); bytes += count; require(bytes <= MAX_NBT_BYTES * 4L) }
                override fun checkReadability(count: Int) { require(count in 0..input.available()) }
                override fun enterDepth() { require(++depth <= 32) }
                override fun exitDepth() { require(depth-- > 0) }
            }
            val root = DefaultNBTSerializer.INSTANCE.deserializeTag(limiter, input) as NBTCompound
            require(input.available() == 0 && root.getNumberTagValueOrThrow("Version").toInt() == 2 &&
                listOf("BlockEntities", "TileEntities", "Entities").all {
                val tag = root.getTagOrNull(it)
                tag == null || (tag is NBTList<*> && tag.tags.isEmpty())
            }) { "Expected a block-only Sponge v2 schematic: $resource" }
            val width = root.getNumberTagValueOrThrow("Width").toInt()
            val height = root.getNumberTagValueOrThrow("Height").toInt()
            val length = root.getNumberTagValueOrThrow("Length").toInt()
            val count = width.toLong() * height * length
            require(width > 0 && height > 0 && length > 0 && count in 1L..16_777_216L) { "Invalid arena dimensions: $resource" }
            val entries = root.getCompoundTagOrThrow("Palette")
            val palette = entries.tags.keys.sortedBy { entries.getNumberTagValueOrThrow(it).toInt() }
            require(palette.size in 1..16_384 && palette.first() == "minecraft:air" &&
                palette.withIndex().all { (id, state) -> entries.getNumberTagValueOrThrow(state).toInt() == id }) {
                "Invalid arena palette: $resource"
            }
            val bytes = (root.getTagOrNull("BlockData") as NBTByteArray).value
            var offset = 0
            val blocks = ShortArray(count.toInt()) {
                require(offset < bytes.size) { "Incomplete arena block data: $resource" }
                val first = bytes[offset++].toInt() and 255
                val id = if (first < 128) first else {
                    require(offset < bytes.size && bytes[offset] >= 0) { "Invalid arena palette index: $resource" }
                    (first and 127) or (bytes[offset++].toInt() shl 7)
                }
                require(id < palette.size) { "Unknown arena palette index $id in $resource" }
                id.toShort()
            }
            require(offset == bytes.size) { "Trailing arena block data: $resource" }
            return AuthoredArenaVolume(width, height, length, palette, blocks)
        }
    }
}
