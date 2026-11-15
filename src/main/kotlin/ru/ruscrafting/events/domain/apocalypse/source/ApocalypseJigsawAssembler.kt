package ru.ruscrafting.events.domain.apocalypse.source

import ru.ruscrafting.events.domain.apocalypse.ApocalypseMarker
import ru.ruscrafting.events.domain.apocalypse.ApocalypseMarkerKind
import ru.ruscrafting.events.domain.apocalypse.ApocalypsePoint
import java.util.Collections
import java.util.Random
import kotlin.math.floor

interface ApocalypseJigsawAssets {
    fun template(id: String): ApocalypseSourceTemplate?
    val pools: Map<String, ApocalypseSourcePool>
    val structures: Map<String, ApocalypseSourceStructure>
    val loot: ApocalypseSourceLoot? get() = null
}

data class ApocalypseSourcePool(val fallback: String, val elements: List<ApocalypsePoolElement>)
data class ApocalypsePoolElement(
    val template: String, val weight: Int, val processor: String? = null, val projection: String = "rigid",
)
data class ApocalypseSourceStructure(
    val startPool: String, val size: Int, val maxDistance: Int, val startHeight: Int = 0, val heightmap: String? = null,
)
data class ApocalypseSourceBlockEntity(val x: Int, val y: Int, val z: Int, val state: String, val data: Map<String, Any?>)

private val mineableSourceOres = setOf("the_deeper_void:tungsten_ore", "the_deeper_void:columbite")
private const val JCWS = "jeffs_cursed_walking_structures:"
private val fronts = mapOf("north" to ApocalypsePoint(0, 0, -1), "east" to ApocalypsePoint(1, 0, 0),
    "south" to ApocalypsePoint(0, 0, 1), "west" to ApocalypsePoint(-1, 0, 0),
    "up" to ApocalypsePoint(0, 1, 0), "down" to ApocalypsePoint(0, -1, 0))
private val opposite = mapOf("north" to "south", "east" to "west", "south" to "north", "west" to "east", "up" to "down", "down" to "up")

internal fun rotated(x: Int, y: Int, z: Int, rotation: Int): ApocalypsePoint = when (rotation and 3) {
    0 -> ApocalypsePoint(x, y, z)
    1 -> ApocalypsePoint(-z, y, x)
    2 -> ApocalypsePoint(-x, y, -z)
    else -> ApocalypsePoint(z, y, -x)
}

data class ApocalypseSourceBounds(val minX: Int, val minY: Int, val minZ: Int, val maxX: Int, val maxY: Int, val maxZ: Int) {
    fun contains(x: Int, y: Int, z: Int) = x in minX..maxX && y in minY..maxY && z in minZ..maxZ
    fun contains(other: ApocalypseSourceBounds) = contains(other.minX, other.minY, other.minZ) && contains(other.maxX, other.maxY, other.maxZ)
    fun intersects(other: ApocalypseSourceBounds) = minX <= other.maxX && maxX >= other.minX &&
        minY <= other.maxY && maxY >= other.minY && minZ <= other.maxZ && maxZ >= other.minZ
}

/** Its origin is the transformed template's (0,0,0), not the northwest corner of its rotated box. */
class ApocalypseSourcePiece(val template: ApocalypseSourceTemplate, val x: Int, val y: Int, val z: Int,
    val rotation: Int, val processor: String?) {
    private val end = rotated(template.sizeX - 1, template.sizeY - 1, template.sizeZ - 1, rotation)
    val bounds = ApocalypseSourceBounds(x + minOf(0, end.x), y, z + minOf(0, end.z),
        x + maxOf(0, end.x), y + end.y, z + maxOf(0, end.z))
    fun world(lx: Int, ly: Int, lz: Int): ApocalypsePoint {
        val p = rotated(lx, ly, lz, rotation)
        return ApocalypsePoint(x + p.x, y + p.y, z + p.z)
    }
    internal fun local(wx: Int, wy: Int, wz: Int) = rotated(wx - x, wy - y, wz - z, -rotation)
}

private data class JigsawSpace(val bounds: ApocalypseSourceBounds, val occupied: MutableList<ApocalypseSourceBounds> = mutableListOf())
private data class JigsawStep(val piece: ApocalypseSourcePiece, val space: JigsawSpace, val depth: Int)

/** Native rigid jigsaw placement: matching target/name, front/top axes, immediate neighbor, bounding volumes and fallback pools. */
class ApocalypseJigsawAssembler(private val assets: ApocalypseJigsawAssets) {
    fun assemble(structureId: String, seed: Long, x: Int, z: Int, surface: (Int, Int) -> Int): ApocalypseSourceAssembly {
        val definition = requireNotNull(assets.structures[structureId]) { "Unknown source structure $structureId" }
        val pool = requireNotNull(assets.pools[definition.startPool])
        val random = Random(seed)
        val rotation = random.nextInt(4)
        val first = weighted(pool).let { it[random.nextInt(it.size)] }
        val template = requireNotNull(assets.template(first.template))
        val probe = ApocalypseSourcePiece(template, x, 0, z, rotation, first.processor)
        val centerX = (probe.bounds.minX + probe.bounds.maxX) / 2
        val centerZ = (probe.bounds.minZ + probe.bounds.maxZ) / 2
        // WORLD_SURFACE_WG/OCEAN_FLOOR_WG return first free Y. A SinglePoolElement's ground-level delta is one.
        val centerY = definition.startHeight + if (definition.heightmap == null) 0 else surface(centerX, centerZ) + 1
        val root = ApocalypseSourcePiece(template, x, centerY - 1, z, rotation, first.processor)
        val maximum = definition.maxDistance
        val space = JigsawSpace(ApocalypseSourceBounds(centerX - maximum, centerY - maximum, centerZ - maximum,
            centerX + maximum, centerY + maximum, centerZ + maximum), mutableListOf(root.bounds))
        val result = mutableListOf(root)
        val queue = ArrayDeque<JigsawStep>()
        if (definition.size > 0) queue.addLast(JigsawStep(root, space, 0))
        while (queue.isNotEmpty()) {
            val (parent, outer, depth) = queue.removeFirst()
            val interior = JigsawSpace(parent.bounds)
            for (joint in shuffled(parent.template.connectors, random)) {
                val position = parent.world(joint.x, joint.y, joint.z)
                val front = ApocalypseSourcePalette.direction(joint.orientation.substringBefore('_'), parent.rotation)
                val forward = requireNotNull(fronts[front])
                val next = ApocalypsePoint(position.x + forward.x, position.y + forward.y, position.z + forward.z)
                val available = if (parent.bounds.contains(next.x, next.y, next.z)) interior else outer
                val candidates = assets.pools[joint.pool] ?: continue // Original templates reference several absent optional pools.
                val fallback = assets.pools[candidates.fallback]
                val options = (if (depth < definition.size) shuffled(weighted(candidates), random) else emptyList()) +
                    (fallback?.let { shuffled(weighted(it), random) } ?: emptyList())
                val top = ApocalypseSourcePalette.direction(joint.orientation.substringAfter('_'), parent.rotation)
                val child = attach(options, next, front, top, joint, available, random) ?: continue
                result += child
                available.occupied += child.bounds
                if (depth + 1 <= definition.size) queue.addLast(JigsawStep(child, available, depth + 1))
            }
        }
        return ApocalypseSourceAssembly(result, seed, assets.loot)
    }

    private fun attach(options: List<ApocalypsePoolElement>, next: ApocalypsePoint, front: String, top: String,
        joint: ApocalypseSourceConnector, space: JigsawSpace, random: Random): ApocalypseSourcePiece? {
        // A weighted duplicate already tried in every rotation cannot fit a second time in this socket.
        val tried = hashSetOf<ApocalypsePoolElement>()
        for (option in options) {
            if (option.template == "minecraft:empty") return null
            if (!tried.add(option)) continue
            require(option.projection == "rigid") { "Unsupported source projection ${option.projection}" }
            val template = requireNotNull(assets.template(option.template)) { "Missing source pool template ${option.template}" }
            for (rotation in shuffled(listOf(0, 1, 2, 3), random)) {
                for (port in shuffled(template.connectors, random)) {
                    if (port.name != joint.target) continue
                    if (ApocalypseSourcePalette.direction(port.orientation.substringBefore('_'), rotation) != opposite[front]) continue
                    if (joint.joint != "rollable" &&
                        ApocalypseSourcePalette.direction(port.orientation.substringAfter('_'), rotation) != top) continue
                    val offset = rotated(port.x, port.y, port.z, rotation)
                    val child = ApocalypseSourcePiece(template, next.x - offset.x, next.y - offset.y, next.z - offset.z,
                        rotation, option.processor)
                    if (space.bounds.contains(child.bounds) && space.occupied.none { it.intersects(child.bounds) }) return child
                }
            }
        }
        return null
    }

    private fun weighted(pool: ApocalypseSourcePool) = buildList {
        for (element in pool.elements) repeat(element.weight) { add(element) }
    }
    private fun <T> shuffled(items: List<T>, random: Random) = items.toMutableList().also { Collections.shuffle(it, random) }
}

class ApocalypseSourceAssembly(val pieces: List<ApocalypseSourcePiece>, private val seed: Long,
    private val loot: ApocalypseSourceLoot? = null) {
    private val connectors = pieces.associateWith { piece -> piece.template.connectors.associateBy { (it.y * piece.template.sizeZ + it.z) * piece.template.sizeX + it.x } }
    private val originalMarkers: List<ApocalypseMarker> by lazy {
        buildList {
            for (piece in pieces) {
                piece.template.entities.forEach { entity ->
                    val kind = ApocalypseSourcePalette.entityMarker(entity.type) ?: return@forEach
                    val p = piece.world(floor(entity.x).toInt(), floor(entity.y).toInt(), floor(entity.z).toInt())
                    add(ApocalypseMarker("", kind, p.x, p.y, p.z))
                }
                for ((point, kind) in ApocalypseTemplateMarkers.of(piece.template)) {
                    val p = piece.world(point.x, point.y, point.z)
                    val state = requireNotNull(piece.template.blockState(point.x, point.y, point.z))
                    val source = piece.template.blockEntity(point.x, point.y, point.z)?.get("LootTable") as? String
                    val table = loot?.processedTable(piece.processor, state, source, p.x, p.y, p.z, point.x, point.y, point.z) ?: source
                    val blockId = ApocalypseSourcePalette.process(state, piece.processor, p.x, p.y, p.z, seed).substringBefore('[')
                    add(ApocalypseMarker("", kind, p.x, p.y, p.z, table, blockId,
                        piece.template.containerItems(point.x, point.y, point.z)))
                }
            }
        }.distinctBy { Triple(it.x, it.y, it.z) }
    }
    val spawn: ApocalypsePoint? = pieces.firstNotNullOfOrNull { p -> p.template.entities.firstOrNull {
        it.type == JCWS + "spawnpoint_entity" }?.let { p.world(floor(it.x).toInt(), floor(it.y).toInt(), floor(it.z).toInt()) } }

    fun markers(siteId: String): List<ApocalypseMarker> = originalMarkers.map { it.copy(siteId = siteId) }

    /** Positions where the former translator placed an upward vine over an authored floor. */
    internal fun floorIvy(): List<ApocalypsePoint> = buildList {
        for (piece in pieces) {
            val floor = piece.template.palette.filterTo(hashSetOf()) {
                it.startsWith("immersive_weathering:ivy[") && ApocalypseSourcePalette.vanillaState(it) == "minecraft:moss_carpet"
            }
            if (floor.isEmpty()) continue
            piece.template.forEachBlock { x, y, z, state, _ ->
                if (state in floor) {
                    val p = piece.world(x, y, z)
                    if (ApocalypseSourcePalette.process(state, piece.processor, p.x, p.y, p.z, seed) == state) add(p)
                }
            }
        }
    }

    val highestLadder: ApocalypsePoint? by lazy {
        var highest: ApocalypsePoint? = null
        for (piece in pieces) {
            val ladders = piece.template.palette.filterTo(hashSetOf()) {
                ApocalypseSourcePalette.vanillaState(it, piece.rotation)?.substringBefore('[') == "minecraft:ladder"
            }
            if (ladders.isNotEmpty()) piece.template.forEachBlock { x, y, z, state, _ ->
                if (state in ladders) {
                    val point = piece.world(x, y, z)
                    if (highest == null || point.y > highest!!.y) highest = point
                }
            }
        }
        highest
    }

    /** Original sign text, banners and decorative heads. Procedural blocks/loot remain owned by their gameplay handlers. */
    fun blockEntities(chunkX: Int, chunkZ: Int): List<ApocalypseSourceBlockEntity> = buildList {
        val x0 = chunkX * 16
        val z0 = chunkZ * 16
        for (piece in pieces) {
            val box = piece.bounds
            for (z in maxOf(z0, box.minZ)..minOf(z0 + 15, box.maxZ)) for (x in maxOf(x0, box.minX)..minOf(x0 + 15, box.maxX)) {
                val local = piece.local(x, piece.y, z)
                for (ly in 0 until piece.template.sizeY) {
                    val state = piece.template.blockState(local.x, ly, local.z) ?: continue
                    val id = state.substringBefore('[').removePrefix("minecraft:")
                    if (!(id.endsWith("_sign") || id.endsWith("_banner") || id.endsWith("_head") || id.endsWith("_skull"))) continue
                    val data = piece.template.blockEntity(local.x, ly, local.z) ?: continue
                    val native = ApocalypseSourcePalette.vanillaState(state, piece.rotation) ?: continue
                    add(ApocalypseSourceBlockEntity(x, piece.y + ly, z, native, data))
                }
            }
        }
    }

    internal fun sourceOres(cx: Int, cz: Int, result: MutableMap<ApocalypsePoint, String>) {
        val x0 = cx * 16
        val z0 = cz * 16
        for (piece in pieces) {
            val box = piece.bounds
            if (box.maxX < x0 || box.minX > x0 + 15 || box.maxZ < z0 || box.minZ > z0 + 15) continue
            // A later nested jigsaw piece may carve or cover an earlier piece at the same coordinate.
            result.keys.removeIf { p ->
                if (p.x shr 4 != cx || p.z shr 4 != cz || !box.contains(p.x, p.y, p.z)) false else {
                    val local = piece.local(p.x, p.y, p.z)
                    piece.template.blockState(local.x, local.y, local.z)?.substringBefore('[')?.let {
                        it != "minecraft:structure_void" && it != "minecraft:structure_block"
                    } == true
                }
            }
            val ores = piece.template.palette.filterTo(hashSetOf()) { it.substringBefore('[') in mineableSourceOres }
            if (ores.isNotEmpty()) for (z in maxOf(z0, box.minZ)..minOf(z0 + 15, box.maxZ)) for (x in maxOf(x0, box.minX)..minOf(x0 + 15, box.maxX)) {
                val local = piece.local(x, piece.y, z)
                for (ly in 0 until piece.template.sizeY) {
                    val state = piece.template.blockState(local.x, ly, local.z) ?: continue
                    if (state in ores) {
                        val y = piece.y + ly
                        val id = ApocalypseSourcePalette.process(state, piece.processor, x, y, z, seed).substringBefore('[')
                        if (id in mineableSourceOres) result[ApocalypsePoint(x, y, z)] = id
                    }
                }
            }
            if (result.isNotEmpty()) for (entity in piece.template.entities) {
                if (ApocalypseSourcePalette.entityMarker(entity.type)?.name?.endsWith("_SPAWNER") == true) {
                    val p = piece.world(floor(entity.x).toInt(), floor(entity.y).toInt(), floor(entity.z).toInt())
                    if (p.x shr 4 == cx && p.z shr 4 == cz) result.remove(p)
                }
            }
        }
    }

    fun render(chunkX: Int, chunkZ: Int, put: (Int, Int, Int, String) -> Unit) {
        val x0 = chunkX * 16
        val z0 = chunkZ * 16
        for (piece in pieces) {
            val box = piece.bounds
            for (z in maxOf(z0, box.minZ)..minOf(z0 + 15, box.maxZ)) for (x in maxOf(x0, box.minX)..minOf(x0 + 15, box.maxX)) {
                val local = piece.local(x, piece.y, z)
                for (ly in 0 until piece.template.sizeY) {
                    var state = piece.template.blockState(local.x, ly, local.z) ?: continue
                    val y = piece.y + ly
                    if (state.startsWith("minecraft:jigsaw")) {
                        val index = (ly * piece.template.sizeZ + local.z) * piece.template.sizeX + local.x
                        state = connectors.getValue(piece)[index]?.finalState ?: "minecraft:air"
                    }
                    state = ApocalypseSourcePalette.process(state, piece.processor, x, y, z, seed)
                    val nbt = if (state.startsWith("copycats:") || state.startsWith("create:copycat_"))
                        piece.template.blockNbt(local.x, ly, local.z) else null
                    val native = ApocalypseSourcePalette.vanillaState(state, piece.rotation, nbt) ?: continue
                    put(x, y, z, native)
                }
            }
            piece.template.entities.forEach { entity ->
                val kind = ApocalypseSourcePalette.entityMarker(entity.type) ?: return@forEach
                val at = piece.world(floor(entity.x).toInt(), floor(entity.y).toInt(), floor(entity.z).toInt())
                if (at.x in x0..x0 + 15 && at.z in z0..z0 + 15 && kind.name.endsWith("_SPAWNER")) {
                    put(at.x, at.y, at.z, "minecraft:copper_grate")
                }
            }
        }
    }
}

private object ApocalypseTemplateMarkers {
    private val byTemplate = java.util.WeakHashMap<ApocalypseSourceTemplate, List<Pair<ApocalypsePoint, ApocalypseMarkerKind>>>()
    @Synchronized fun of(template: ApocalypseSourceTemplate): List<Pair<ApocalypsePoint, ApocalypseMarkerKind>> =
        byTemplate.getOrPut(template) {
            buildList {
                fun marker(state: String) = ApocalypseSourcePalette.blockMarker(state) ?: when (state.substringBefore('[')) {
                    "minecraft:chest", "minecraft:trapped_chest", "minecraft:barrel" -> ApocalypseMarkerKind.LOOT
                    else -> null
                }
                if (template.palette.any { marker(it) != null }) {
                    template.forEachBlock { x, y, z, state, _ ->
                        marker(state)?.let { add(ApocalypsePoint(x, y, z) to it) }
                    }
                }
            }
        }
}
