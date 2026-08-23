package ru.ruscrafting.events.paper

import org.bukkit.Material
import kotlin.math.abs

data class CitadelPoint(
    val x: Double,
    val y: Double,
    val z: Double,
    val yaw: Float = 0f,
    val pitch: Float = 0f,
)

/**
 * Deterministic, dependency-free map geometry for the first ArcEvents mode.
 *
 * The citadel deliberately offers three useful elevations: an open undercroft,
 * the four themed ground-floor wings, and the central keep's galleries. The
 * formula is stable so a production world can be audited by template version
 * without bundling or downloading an opaque schematic.
 */
object TttCitadelBlueprint {
    const val TEMPLATE = "citadel-v1"
    const val SEED = 0x41524345564E5453L
    const val MIN_X = -72
    const val MAX_X = 72
    const val MIN_Y = 4
    const val MAX_Y = 48
    const val MIN_Z = -72
    const val MAX_Z = 72
    const val PLAYABLE_MIN = -66.5
    const val PLAYABLE_MAX = 66.5

    val lobby = CitadelPoint(0.5, 42.0, 0.5, 180f)
    val spectator = CitadelPoint(0.5, 38.0, 0.5)
    val spawns = listOf(
        CitadelPoint(-12.5, 16.0, -12.5, 45f),
        CitadelPoint(12.5, 16.0, -12.5, -45f),
        CitadelPoint(-12.5, 16.0, 12.5, 135f),
        CitadelPoint(12.5, 16.0, 12.5, -135f),
        CitadelPoint(-8.5, 16.0, -39.5, 0f),
        CitadelPoint(8.5, 16.0, -47.5, 180f),
        CitadelPoint(38.5, 16.0, -8.5, 90f),
        CitadelPoint(47.5, 16.0, 8.5, -90f),
        CitadelPoint(-8.5, 16.0, 38.5, 0f),
        CitadelPoint(8.5, 16.0, 47.5, 180f),
        CitadelPoint(-38.5, 16.0, -8.5, 90f),
        CitadelPoint(-47.5, 16.0, 8.5, -90f),
        CitadelPoint(-12.5, 26.0, -7.5, 90f),
        CitadelPoint(12.5, 26.0, 7.5, -90f),
        CitadelPoint(-42.5, 7.0, 0.5, 90f),
        CitadelPoint(42.5, 7.0, 0.5, -90f),
    )
    private val pillarLines = setOf(-48, -32, 32, 48)

    fun materialAt(x: Int, y: Int, z: Int): Material? {
        if (x !in MIN_X..MAX_X || y !in MIN_Y..MAX_Y || z !in MIN_Z..MAX_Z) return null

        var material: Material? = null
        val ax = abs(x)
        val az = abs(z)

        // Void-safe foundation, undercroft floor, and the main courtyard deck.
        if (ax <= 66 && az <= 66 && y == 5) material = Material.BEDROCK
        if (ax <= 65 && az <= 65 && y == 6) material = checker(x, z, Material.POLISHED_DEEPSLATE, Material.DEEPSLATE_TILES)
        if (ax <= 64 && az <= 64 && y == 15) material = courtyardFloor(x, z)

        // Perimeter rampart. Four broad gates preserve long sight lines.
        if (y in 7..31 && (ax == 64 || az == 64)) {
            val gate = (ax == 64 && abs(z) <= 4 || az == 64 && abs(x) <= 4) && y <= 20
            if (!gate) material = if (y in 18..21 && (x + z) % 7 == 0) Material.IRON_BARS else Material.DEEPSLATE_BRICKS
        }
        if (y == 32 && (ax in 62..64 || az in 62..64) && ax <= 64 && az <= 64) material = Material.POLISHED_DEEPSLATE

        // Central three-storey keep with an open atrium and four entrances.
        shell(x, y, z, -18, 18, 15, 36, -18, 18, Material.STONE_BRICKS, Material.POLISHED_ANDESITE)?.let { material = it }
        if (y == 25 && ax < 18 && az < 18 && !(ax <= 5 && az <= 5)) material = Material.SMOOTH_STONE
        if (y == 35 && ax < 18 && az < 18 && !(ax <= 4 && az <= 4)) material = Material.POLISHED_ANDESITE
        if (centralDoor(x, y, z)) material = null
        if ((ax == 18 || az == 18) && y in 20..22 && (x + z) % 6 == 0) material = Material.TINTED_GLASS

        // Archive, foundry, conservatory, and tribunal wings.
        shell(x, y, z, -15, 15, 15, 27, -55, -25, Material.BRICKS, Material.DARK_OAK_PLANKS)?.let { material = it }
        shell(x, y, z, 24, 55, 15, 28, -15, 15, Material.TUFF_BRICKS, Material.CUT_COPPER)?.let { material = it }
        shell(x, y, z, -15, 15, 15, 29, 24, 55, Material.MOSSY_STONE_BRICKS, Material.OXIDIZED_COPPER)?.let { material = it }
        shell(x, y, z, -55, -24, 15, 30, -15, 15, Material.DEEPSLATE_BRICKS, Material.POLISHED_BLACKSTONE)?.let { material = it }
        if (wingDoor(x, y, z)) material = null

        // Covered links make every wing reachable without crossing the plaza.
        corridor(x, y, z, -24, -19, -4, 4)?.let { material = it }
        corridor(x, y, z, 19, 24, -4, 4)?.let { material = it }
        corridor(x, y, z, -4, 4, -25, -19)?.let { material = it }
        corridor(x, y, z, -4, 4, 19, 24)?.let { material = it }

        // The undercroft uses a cross, a ring, cells, and cover rather than one empty basement.
        if (y in 7..13 && undercroftWall(x, z)) material = Material.DEEPSLATE_BRICKS
        if (y == 13 && (ax <= 7 || az <= 7 || ax in 38..44 || az in 38..44) && ax <= 58 && az <= 58) {
            material = Material.DEEPSLATE_TILE_SLAB
        }
        if (y == 7 && sewerChannel(x, z)) material = Material.DARK_PRISMARINE

        // Four full-block stairways connect the undercroft and courtyard.
        stairMaterial(x, y, z)?.let { material = it }
        if (surfaceStairOpening(x, z) && y == 15) material = null

        // Two broad staircases connect the keep's gallery levels.
        keepStairMaterial(x, y, z)?.let { material = it }

        // Cover, landmarks, and lighting give rooms readable identities.
        propMaterial(x, y, z)?.let { material = it }

        // A protected observation dais supplies valid lobby and spectator anchors.
        if (y == 37 && ax <= 5 && az <= 5) material = Material.TINTED_GLASS
        if (y == 41 && ax <= 4 && az <= 4) material = Material.SMOOTH_QUARTZ
        if (y in 42..44 && (ax == 4 || az == 4) && ax <= 4 && az <= 4) material = Material.GLASS_PANE

        return material
    }

    private fun courtyardFloor(x: Int, z: Int): Material = when {
        abs(x) <= 6 || abs(z) <= 6 -> checker(x, z, Material.POLISHED_ANDESITE, Material.SMOOTH_STONE)
        x < -20 && z < -20 -> Material.MUD_BRICKS
        x > 20 && z < -20 -> Material.TUFF_BRICKS
        x > 20 && z > 20 -> Material.MOSS_BLOCK
        x < -20 && z > 20 -> Material.POLISHED_BLACKSTONE_BRICKS
        else -> checker(x, z, Material.STONE_BRICKS, Material.CRACKED_STONE_BRICKS)
    }

    private fun shell(
        x: Int,
        y: Int,
        z: Int,
        minX: Int,
        maxX: Int,
        minY: Int,
        maxY: Int,
        minZ: Int,
        maxZ: Int,
        wall: Material,
        roof: Material,
    ): Material? {
        if (x !in minX..maxX || y !in minY..maxY || z !in minZ..maxZ) return null
        if (y == minY) return Material.SMOOTH_STONE
        if (y == maxY) return roof
        if (x == minX || x == maxX || z == minZ || z == maxZ) {
            return if (y in (minY + 4)..(minY + 6) && (x + z) % 5 == 0) Material.GLASS_PANE else wall
        }
        return null
    }

    private fun centralDoor(x: Int, y: Int, z: Int): Boolean = y in 16..20 && (
        (abs(x) == 18 && abs(z) <= 3) || (abs(z) == 18 && abs(x) <= 3)
    )

    private fun wingDoor(x: Int, y: Int, z: Int): Boolean {
        if (y !in 16..20) return false
        return (z == -25 && abs(x) <= 3) ||
            (x == 24 && abs(z) <= 3) ||
            (z == 24 && abs(x) <= 3) ||
            (x == -24 && abs(z) <= 3)
    }

    private fun corridor(x: Int, y: Int, z: Int, minX: Int, maxX: Int, minZ: Int, maxZ: Int): Material? {
        if (x !in minX..maxX || z !in minZ..maxZ || y !in 15..22) return null
        if (y == 15) return Material.POLISHED_ANDESITE
        if (y == 22) return Material.DEEPSLATE_TILE_SLAB
        val horizontal = maxX - minX > maxZ - minZ
        if (horizontal && (z == minZ || z == maxZ) || !horizontal && (x == minX || x == maxX)) return Material.IRON_BARS
        return null
    }

    private fun undercroftWall(x: Int, z: Int): Boolean {
        val ax = abs(x)
        val az = abs(z)
        val outerRing = (ax == 44 && az <= 44) || (az == 44 && ax <= 44)
        val crossRails = (ax == 7 && az <= 58) || (az == 7 && ax <= 58)
        val cellDividers = (x % 16 == 0 && az in 12..36) || (z % 16 == 0 && ax in 12..36)
        val passage = abs(x % 16) <= 2 || abs(z % 16) <= 2
        return (outerRing || crossRails || cellDividers) && !passage
    }

    private fun sewerChannel(x: Int, z: Int): Boolean =
        (abs(x) in 2..3 && abs(z) <= 58) || (abs(z) in 2..3 && abs(x) <= 58)

    private fun surfaceStairOpening(x: Int, z: Int): Boolean =
        (x in -58..-50 && abs(z) <= 2) || (x in 50..58 && abs(z) <= 2) ||
            (z in -58..-50 && abs(x) <= 2) || (z in 50..58 && abs(x) <= 2)

    private fun stairMaterial(x: Int, y: Int, z: Int): Material? {
        val west = x in -58..-50 && abs(z) <= 2 && y == 7 + (x + 58)
        val east = x in 50..58 && abs(z) <= 2 && y == 15 - (x - 50)
        val north = z in -58..-50 && abs(x) <= 2 && y == 7 + (z + 58)
        val south = z in 50..58 && abs(x) <= 2 && y == 15 - (z - 50)
        return if (west || east || north || south) Material.POLISHED_DEEPSLATE else null
    }

    private fun keepStairMaterial(x: Int, y: Int, z: Int): Material? {
        val first = x in -15..-6 && z in -14..-11 && y == 16 + (x + 15)
        val second = x in 6..15 && z in 11..14 && y == 26 + (15 - x)
        return if (first || second) Material.SMOOTH_STONE else null
    }

    private fun propMaterial(x: Int, y: Int, z: Int): Material? {
        val crate = (z == -50 && (x == -12 && y in 16..17 || x == -11 && y == 16)) ||
            (z == -12 && (x == 49 && y in 16..17 || x == 48 && y == 16)) ||
            (z == 49 && y == 16 && x in 11..12) ||
            (x == -49 && y == 16 && z == 12)
        if (crate) return Material.BARREL
        if (y == 16 && (x in pillarLines && z % 16 == 0 || z in pillarLines && x % 16 == 0)) {
            return Material.CHISELED_DEEPSLATE
        }
        if (y == 20 && ((abs(x) == 12 && abs(z) == 12) || (abs(x) == 36 && abs(z) == 36))) return Material.SEA_LANTERN
        if (y in 16..19 && x == 0 && z == 39) return Material.OAK_LEAVES
        if (y == 20 && x == 0 && z == 39) return Material.GLOWSTONE
        if (y == 16 && x in -10..10 && z in -45..-43 && x % 4 == 0) return Material.BOOKSHELF
        if (y == 16 && x in 43..45 && z in -10..10 && z % 4 == 0) return Material.BLAST_FURNACE
        return null
    }

    private fun checker(x: Int, z: Int, first: Material, second: Material): Material =
        if ((x.floorDiv(2) + z.floorDiv(2)) % 2 == 0) first else second
}
