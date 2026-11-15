package ru.ruscrafting.events.domain.apocalypse

import java.util.concurrent.ConcurrentHashMap

internal data class ApocalypseShapeCell(val position: ApocalypsePoint, val original: String, val fitTorch: Boolean)

/** Only source writes enter this index. Later air, replacement structures and scenery overwrite earlier cells. */
internal class ApocalypseShapeCollector {
    private val entries = mutableMapOf<Int, ApocalypseShapeCell>()

    fun put(x: Int, y: Int, z: Int, state: String, fitTorch: Boolean) {
        val index = y * 256 + (z and 15) * 16 + (x and 15)
        if (shapeStates.computeIfAbsent(state) {
                val id = it.substringBefore('[')
                id.endsWith("_fence") || id.endsWith("_pane") || id.endsWith("_bars") || id.endsWith("_wall") ||
                    id == "minecraft:torch" || id.endsWith("_torch")
            }) entries[index] = ApocalypseShapeCell(ApocalypsePoint(x, y, z), state, fitTorch)
        else if (entries.isNotEmpty()) entries.remove(index)
    }

    fun cells() = entries.values.sortedByDescending { it.position.y }

    private companion object {
        val shapeStates = ConcurrentHashMap<String, Boolean>()
    }
}

/** Sparse legacy readback. Fill calls still see the original world height and only write requested coordinates. */
internal class ApocalypseShapeSamples(cx: Int, cz: Int, points: List<ApocalypsePoint>) :
    ApocalypseChunkCanvas(cx, cz, -64, 319) {
    val result = points.associateWith { "minecraft:air" }.toMutableMap()
    private val byColumn = points.groupBy { (it.z and 15) * 16 + (it.x and 15) }
    private val byIndex = points.associateBy { it.y * 256 + (it.z and 15) * 16 + (it.x and 15) }

    override fun fill(x1: Int, y1: Int, z1: Int, x2: Int, y2: Int, z2: Int, block: ApocalypseBlock) {
        for (z in z1..z2) for (x in x1..x2) {
            val column = byColumn[z * 16 + x] ?: continue
            for (p in column) if (p.y in y1..y2) result[p] = block.blockState
        }
    }

    override fun fillState(x: Int, y: Int, z: Int, state: String) {
        byIndex[y * 256 + z * 16 + x]?.let { result[it] = state }
    }
}
