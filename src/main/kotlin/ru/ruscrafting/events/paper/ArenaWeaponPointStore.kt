package ru.ruscrafting.events.paper

import com.google.gson.GsonBuilder
import ru.arc.persistence.AtomicFileStore
import ru.ruscrafting.events.config.ArenaSettings
import ru.ruscrafting.events.config.EventLocation
import java.nio.charset.StandardCharsets
import java.nio.file.Path

enum class ArenaWeaponPointEditResult {
    ADDED,
    REMOVED,
    DUPLICATE,
    LIMIT_REACHED,
    NOT_FOUND,
    STORAGE_UNAVAILABLE,
}

private data class StoredWeaponPoint(
    val x: Double,
    val y: Double,
    val z: Double,
)

private data class ArenaWeaponPointState(
    val format: Int = FORMAT,
    val arenas: Map<String, List<StoredWeaponPoint>> = emptyMap(),
) {
    companion object {
        const val FORMAT = 1
    }
}

/** Owns bounded, runtime-edited mandatory weapon positions independently from deployable arena config. */
class ArenaWeaponPointStore(
    dataRoot: Path,
    private val loadFailure: (Throwable) -> Unit = {},
) : AutoCloseable {
    private val gson = GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create()
    private val file = AtomicFileStore(
        root = dataRoot,
        relativePath = Path.of("data", "arena-weapon-points.json"),
        maxBytes = MAX_BYTES,
        encode = { state: ArenaWeaponPointState -> gson.toJson(state).toByteArray(StandardCharsets.UTF_8) },
        decode = { bytes ->
            requireNotNull(gson.fromJson(bytes.toString(StandardCharsets.UTF_8), ArenaWeaponPointState::class.java)) {
                "Arena weapon point state is empty"
            }
        },
        validate = ::validate,
    )
    private var available = true
    private var state = runCatching { file.loadOrDefault(::ArenaWeaponPointState) }
        .onFailure {
            available = false
            loadFailure(it)
        }
        .getOrDefault(ArenaWeaponPointState())

    @Synchronized
    fun points(arena: ArenaSettings): List<EventLocation> = state.arenas[arena.id].orEmpty().mapNotNull { point ->
        EventLocation(arena.world, point.x, point.y, point.z).validated()
            .takeIf { arena.bounds?.contains(it) == true }
    }

    @Synchronized
    fun add(arena: ArenaSettings, point: EventLocation, maximumPoints: Int): ArenaWeaponPointEditResult {
        if (!available) return ArenaWeaponPointEditResult.STORAGE_UNAVAILABLE
        require(maximumPoints in 1..MAX_POINTS_PER_ARENA)
        require(point.world == arena.world && arena.bounds?.contains(point) == true)
        val current = state.arenas[arena.id].orEmpty()
        if (current.any { stored -> stored.blockKey(arena.world) == point.blockKey() }) {
            return ArenaWeaponPointEditResult.DUPLICATE
        }
        if (current.size >= maximumPoints) return ArenaWeaponPointEditResult.LIMIT_REACHED
        val updated = current + StoredWeaponPoint(point.x, point.y, point.z)
        return commit(arena.id, updated, ArenaWeaponPointEditResult.ADDED)
    }

    @Synchronized
    fun removeNearest(
        arena: ArenaSettings,
        point: EventLocation,
        maximumDistance: Double,
    ): ArenaWeaponPointEditResult {
        if (!available) return ArenaWeaponPointEditResult.STORAGE_UNAVAILABLE
        require(maximumDistance.isFinite() && maximumDistance in 0.1..16.0)
        val current = state.arenas[arena.id].orEmpty()
        val nearest = current.withIndex().minByOrNull { (_, stored) -> stored.distanceSquared(point) }
            ?.takeIf { (_, stored) -> stored.distanceSquared(point) <= maximumDistance * maximumDistance }
            ?: return ArenaWeaponPointEditResult.NOT_FOUND
        val updated = current.filterIndexed { index, _ -> index != nearest.index }
        return commit(arena.id, updated, ArenaWeaponPointEditResult.REMOVED)
    }

    private fun commit(
        arenaId: String,
        points: List<StoredWeaponPoint>,
        result: ArenaWeaponPointEditResult,
    ): ArenaWeaponPointEditResult {
        val arenas = state.arenas.toMutableMap().also { updated ->
            if (points.isEmpty()) updated.remove(arenaId) else updated[arenaId] = points
        }.toSortedMap()
        return runCatching { file.write(ArenaWeaponPointState(arenas = arenas)) }
            .onSuccess { state = it }
            .fold(onSuccess = { result }, onFailure = {
                loadFailure(it)
                ArenaWeaponPointEditResult.STORAGE_UNAVAILABLE
            })
    }

    override fun close() = Unit

    private companion object {
        const val MAX_BYTES = 512L * 1024L
        const val MAX_POINTS_PER_ARENA = 128

        fun validate(state: ArenaWeaponPointState) {
            require(state.format == ArenaWeaponPointState.FORMAT) { "Unsupported arena weapon point format" }
            require(state.arenas.size <= 16) { "Arena weapon point state contains too many arenas" }
            state.arenas.forEach { (arenaId, points) ->
                require(arenaId.matches(Regex("[a-z0-9_-]{1,32}"))) { "Invalid arena id in weapon point state" }
                require(points.size <= MAX_POINTS_PER_ARENA) { "Arena $arenaId contains too many weapon points" }
                require(points.all { point ->
                    point.x.isFinite() && point.y.isFinite() && point.z.isFinite() && point.y in -2048.0..2048.0
                }) { "Arena $arenaId contains an invalid weapon point" }
                require(points.map { it.blockKey("") }.distinct().size == points.size) {
                    "Arena $arenaId contains duplicate weapon point blocks"
                }
            }
        }

        fun StoredWeaponPoint.blockKey(world: String): LootBlockKey =
            EventLocation(world, x, y, z).blockKey()

        fun StoredWeaponPoint.distanceSquared(point: EventLocation): Double =
            (x - point.x) * (x - point.x) + (y - point.y) * (y - point.y) + (z - point.z) * (z - point.z)
    }
}
