package ru.ruscrafting.events.paper

import org.bukkit.Location
import org.bukkit.Particle
import org.bukkit.Server
import org.bukkit.entity.Player
import ru.ruscrafting.events.config.ArcEventsConfig
import ru.ruscrafting.events.config.ArenaSettings
import ru.ruscrafting.events.config.EventLocation
import ru.ruscrafting.events.config.NodeMode

enum class ArenaWeaponPointAdminResult {
    ADDED,
    REMOVED,
    SHOWN,
    WRONG_NODE,
    OUTSIDE_ARENA,
    BUSY,
    UNSAFE,
    DUPLICATE,
    LIMIT_REACHED,
    NOT_FOUND,
    STORAGE_UNAVAILABLE,
}

data class ArenaWeaponPointFeedback(
    val result: ArenaWeaponPointAdminResult,
    val arenaId: String? = null,
    val count: Int = 0,
    val target: Int = 0,
)

/** Main-thread operator flow for exact, mandatory weapon positions in imported arenas. */
class ArenaWeaponPointEditor(
    private val settings: () -> ArcEventsConfig,
    private val arenas: ArenaPool,
    private val store: ArenaWeaponPointStore,
) {
    fun add(player: Player): ArenaWeaponPointFeedback = withEditableArena(player) { arena ->
        val exact = TttLootPlacement.exact(player.location)
            ?: return@withEditableArena feedback(ArenaWeaponPointAdminResult.UNSAFE, arena)
        val point = EventLocation(arena.world, exact.x, exact.y, exact.z)
        val result = when (store.add(arena, point, arena.weaponCount)) {
            ArenaWeaponPointEditResult.ADDED -> ArenaWeaponPointAdminResult.ADDED
            ArenaWeaponPointEditResult.DUPLICATE -> ArenaWeaponPointAdminResult.DUPLICATE
            ArenaWeaponPointEditResult.LIMIT_REACHED -> ArenaWeaponPointAdminResult.LIMIT_REACHED
            ArenaWeaponPointEditResult.STORAGE_UNAVAILABLE -> ArenaWeaponPointAdminResult.STORAGE_UNAVAILABLE
            ArenaWeaponPointEditResult.REMOVED,
            ArenaWeaponPointEditResult.NOT_FOUND,
            -> error("Unexpected add result")
        }
        feedback(result, arena)
    }

    fun remove(player: Player): ArenaWeaponPointFeedback = withEditableArena(player) { arena ->
        val location = player.location
        val point = EventLocation(arena.world, location.x, location.y, location.z)
        val result = when (store.removeNearest(arena, point, REMOVE_RADIUS)) {
            ArenaWeaponPointEditResult.REMOVED -> ArenaWeaponPointAdminResult.REMOVED
            ArenaWeaponPointEditResult.NOT_FOUND -> ArenaWeaponPointAdminResult.NOT_FOUND
            ArenaWeaponPointEditResult.STORAGE_UNAVAILABLE -> ArenaWeaponPointAdminResult.STORAGE_UNAVAILABLE
            ArenaWeaponPointEditResult.ADDED,
            ArenaWeaponPointEditResult.DUPLICATE,
            ArenaWeaponPointEditResult.LIMIT_REACHED,
            -> error("Unexpected remove result")
        }
        feedback(result, arena)
    }

    fun show(player: Player): ArenaWeaponPointFeedback = withEditableArena(player) { arena ->
        store.points(arena).forEach { point ->
            player.spawnParticle(
                Particle.END_ROD,
                Location(player.world, point.x, point.y + PREVIEW_HEIGHT, point.z),
                PREVIEW_PARTICLES,
                0.18,
                0.28,
                0.18,
                0.005,
            )
        }
        feedback(ArenaWeaponPointAdminResult.SHOWN, arena)
    }

    private fun withEditableArena(
        player: Player,
        action: (ArenaSettings) -> ArenaWeaponPointFeedback,
    ): ArenaWeaponPointFeedback {
        val current = settings()
        if (current.nodeMode != NodeMode.HOST) return ArenaWeaponPointFeedback(ArenaWeaponPointAdminResult.WRONG_NODE)
        val location = player.location
        val playerPoint = EventLocation(location.world.name, location.x, location.y, location.z)
        val arena = current.arenas.firstOrNull { candidate ->
            candidate.enabled && candidate.world == location.world.name && candidate.bounds?.contains(playerPoint) == true
        }
            ?: return ArenaWeaponPointFeedback(ArenaWeaponPointAdminResult.OUTSIDE_ARENA)
        if (arenas.inUse(arena.id)) return feedback(ArenaWeaponPointAdminResult.BUSY, arena)
        return action(arena)
    }

    private fun feedback(result: ArenaWeaponPointAdminResult, arena: ArenaSettings): ArenaWeaponPointFeedback =
        ArenaWeaponPointFeedback(result, arena.id, store.points(arena).size, arena.weaponCount)

    fun points(arena: ArenaSettings): List<EventLocation> = store.points(arena)

    private companion object {
        const val REMOVE_RADIUS = 3.0
        const val PREVIEW_HEIGHT = 0.45
        const val PREVIEW_PARTICLES = 8
    }
}

internal fun mandatoryWeaponPointsReady(
    server: Server,
    store: ArenaWeaponPointStore,
    arena: ArenaSettings,
): Boolean {
    val points = store.points(arena)
    if (points.size > arena.weaponCount) return false
    val world = server.getWorld(arena.world) ?: return points.isEmpty()
    return points.all { point -> TttLootPlacement.exact(Location(world, point.x, point.y, point.z)) != null }
}
