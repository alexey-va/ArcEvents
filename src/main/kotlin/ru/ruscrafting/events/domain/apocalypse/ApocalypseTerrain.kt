package ru.ruscrafting.events.domain.apocalypse

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.roundToInt

/** A seeded, continuous value field sampled in world coordinates on both sides of chunk boundaries. */
internal class ApocalypseTerrain(private val seed: Long) {
    fun bits(x: Int, z: Int, salt: Int): Long {
        var value = seed xor (x * -7046029254386353131L) xor (z * -4658895280553007687L) xor (salt * -7723592293110705685L)
        value = (value xor (value ushr 30)) * -4658895280553007687L
        value = (value xor (value ushr 27)) * -7723592293110705685L
        return value xor (value ushr 31)
    }

    fun pick(x: Int, z: Int, salt: Int, bound: Int): Int = ((bits(x, z, salt) ushr 1) % bound).toInt()

    fun field(x: Int, z: Int, scale: Int, salt: Int): Double {
        val gx = Math.floorDiv(x, scale)
        val gz = Math.floorDiv(z, scale)
        val tx = smooth(Math.floorMod(x, scale).toDouble() / scale)
        val tz = smooth(Math.floorMod(z, scale).toDouble() / scale)
        fun value(dx: Int, dz: Int) = (bits(gx + dx, gz + dz, salt) ushr 11) / 4503599627370496.0 - 1.0
        return mix(mix(value(0, 0), value(1, 0), tx), mix(value(0, 1), value(1, 1), tx), tz)
    }

    fun land(x: Int, z: Int): Int {
        val continent = field(x, z, 276, 1)
        val hill = field(x, z, 87, 2)
        val fine = field(x, z, 30, 3)
        val ridge = (1.0 - abs(field(x, z, 118, 4))).let { it * it * it }
        val mountain = (continent + 0.18).coerceIn(0.0, 1.0)
        val base = 71.0 + continent * 28 + hill * 11 + fine * 3 + ridge * mountain * 36
        val river = abs(field(x, z, 146, 5) + field(x, z, 53, 6) * 0.18)
        val valley = smooth((river / 0.19).coerceIn(0.0, 1.0))
        return mix(minOf(base, 55.0 + fine * 2), base, valley).roundToInt()
    }

    fun drawColumn(out: ApocalypseChunkCanvas, x: Int, z: Int, height: Int) {
        val seabed = height < SEA
        val slope = height > 97
        val patch = field(x, z, 17, 38)
        val surface = when {
            seabed && patch > 0.32 -> ApocalypseBlock.CLAY
            height <= SEA + 1 -> if (patch < -0.1) ApocalypseBlock.GRAVEL else ApocalypseBlock.SAND
            slope && patch > -0.2 -> ApocalypseBlock.ANDESITE
            patch < -0.47 -> ApocalypseBlock.COARSE_DIRT
            patch > 0.60 -> ApocalypseBlock.PODZOL
            else -> ApocalypseBlock.GRASS_BLOCK
        }
        fun run(a: Int, b: Int, block: ApocalypseBlock) = out.box(x, a, z, x, b, z, block)
        run(out.minY, out.minY + pick(x, z, 22, 3), ApocalypseBlock.BEDROCK)
        run(out.minY + 3, 1, ApocalypseBlock.DEEPSLATE)
        // Keep a solid transition above the variable top of bedrock.
        if (out.minY + 2 < 1) run(out.minY + 1 + pick(x, z, 22, 3), out.minY + 2, ApocalypseBlock.DEEPSLATE)
        run(2, height - 4, ApocalypseBlock.STONE)
        run(height - 3, height - 1, if (seabed) ApocalypseBlock.GRAVEL else ApocalypseBlock.DIRT)
        run(height, height, surface)
        if (seabed) run(height + 1, SEA, ApocalypseBlock.WATER)
        // Two meandering tunnel systems with independent depth and width fields.
        for (layer in 0..1) {
            val trace = abs(field(x, z, 37 + 17 * layer, 42 + layer))
            if (trace > 0.18) continue
            val center = if (layer == 0) {
                30 + (field(x, z, 63, 46) * 36).roundToInt()
            } else {
                -29 + (field(x, z, 75, 47) * 14).roundToInt()
            }
            val radius = 2 + floor((1.0 - trace / 0.18) * 4).toInt()
            val top = if (height <= SEA + 1) minOf(height - 5, center + radius) else minOf(height, center + radius)
            run(center - radius, top, ApocalypseBlock.AIR)
        }
        for (layer in 0..3) {
            val vein = bits(x shr 2, z shr 2, 100 + layer)
            if ((vein and 3L) != 0L || pick(x, z, 106 + layer, 4) == 0) continue
            val y = -51 + layer * 28 + ((vein ushr 8) % 22).toInt()
            val ore = when (layer) {
                0 -> if ((vein and 28L) == 4L) ApocalypseBlock.DEEPSLATE_DIAMOND_ORE else ApocalypseBlock.DEEPSLATE_REDSTONE_ORE
                1 -> ApocalypseBlock.DEEPSLATE_GOLD_ORE
                2 -> if ((vein and 16L) == 0L) ApocalypseBlock.COPPER_ORE else ApocalypseBlock.IRON_ORE
                else -> ApocalypseBlock.COAL_ORE
            }
            if (y + 4 < height) run(y, y + 1, ore)
        }
    }

    companion object {
        const val SEA = 61
        fun smooth(t: Double) = t * t * (3 - 2 * t)
        fun mix(a: Double, b: Double, t: Double) = a + (b - a) * t
    }
}
