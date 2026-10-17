package ru.ruscrafting.events.paper

import java.util.concurrent.CompletableFuture
import org.bukkit.Location
import org.bukkit.plugin.Plugin
import ru.ruscrafting.events.config.ArenaSettings
import ru.ruscrafting.events.config.EventLocation
import ru.ruscrafting.events.domain.TttMatch

/** Owns the bounded conversion from one arena loot plan to live pickup entities. */
class TttLootSpawner(
    private val plugin: Plugin,
    private val scene: TttLootScene,
    private val firearms: TttFirearms,
    private val weaponPoints: ArenaWeaponPointEditor,
    private val debug: ArcEventsDebug,
    private val work: EventWork? = null,
    private val tickets: ru.arc.paper.chunk.PaperChunkTicketRegistry = ru.arc.paper.chunk.PaperChunkTicketRegistry(plugin),
) {
    private var generation = 0L
    private class LootLeaseBatch {
        val leases = mutableListOf<ru.arc.paper.chunk.PaperChunkTicketLease>()
        var release: CompletableFuture<Unit>? = null
    }
    private val pendingLeases = mutableSetOf<LootLeaseBatch>()
    fun cancel() { generation++ }

    /** Native placement probes and entity creation share the runtime queue with escrow and arena work. */
    fun spawnAsync(match: TttMatch, arena: ArenaSettings, isCurrent: () -> Boolean): CompletableFuture<Unit> {
        val tasks = work ?: return runCatching { spawn(match, arena) }.fold(CompletableFuture<Unit>::completedFuture, CompletableFuture<Unit>::failedFuture)
        val request = ++generation
        val world = requireNotNull(plugin.server.getWorld(arena.world))
        val seed = match.matchId.mostSignificantBits xor match.matchId.leastSignificantBits
        val catalog = firearms.catalog()
        val mandatory = weaponPoints.points(arena)
        val owned = LootLeaseBatch()
        pendingLeases += owned
        fun current() = tasks.isCurrent() && generation == request && isCurrent() && plugin.server.getWorld(world.uid) === world
        return tasks.compute {
            val plan = if (arena.template == TttCitadelBlueprint.TEMPLATE) {
                TttCitadelLoot.layout(seed, catalog).map { spawn -> TttLootSpawn(
                    EventLocation(arena.world, spawn.point.x, spawn.point.y, spawn.point.z), spawn.firearm, spawn.ammunition, false) }
            } else TttLootLayoutPlanner.imported(arena.lootSpawns, mandatory, arena.weaponCount, seed, catalog)
            val candidates = plan.map { spawn -> if (spawn.exact) listOf(spawn.point) else nearbyLootCandidates(spawn.point) }
            val chunks = candidates.asSequence().flatten().map(EventLocation::blockKey).map { (it.x shr 4) to (it.z shr 4) }.distinct().toList()
            Triple(plan, candidates, chunks)
        }.thenCompose { (plan, candidates, chunks) ->
            var loaded = CompletableFuture.completedFuture(Unit)
            chunks.forEach { (x, z) -> loaded = loaded.thenCompose { tasks.callNative {
                check(current()) { "Loot preparation superseded" }
                world.getChunkAtAsync(x, z, true)
            }.thenCompose { it }.thenCompose { chunk -> tasks.callNative {
                check(current() && world.isChunkLoaded(x, z)) { "Loot chunk became unavailable" }
                val lease = (tickets.acquire(chunk) as? ru.arc.paper.chunk.PaperChunkTicketAcquireResult.Acquired)?.lease
                    ?: error("Could not retain loot chunk $x,$z")
                owned.leases += lease
                Unit
            } } } }
            loaded.thenCompose { tasks.onMain {
                var index = 0
                var probe = 0
                var resolved: Location? = null
                var skipped = 0
                tasks.capture(::current) {
                    if (index < plan.size) {
                        val spawn = plan[index]
                        val location = resolved
                        if (location != null) {
                            val stack = spawn.firearm?.let { firearms.firearmItem(it, null, match.matchId.toString()) }
                                ?: firearms.ammunition(null, match.matchId.toString(), spawn.ammunition)
                            val item = scene.spawnExact(location, stack)
                            if (item == null && spawn.exact) error("Guaranteed weapon point ${spawn.point.blockKey()} became unsafe in arena ${arena.id}")
                            if (item == null) skipped++
                            index++; probe = 0; resolved = null
                        } else if (probe < candidates[index].size) {
                            val point = candidates[index][probe++]
                            resolved = TttLootPlacement.exact(Location(world, point.x, point.y, point.z))
                        } else {
                            if (spawn.exact) error("Guaranteed weapon point ${spawn.point.blockKey()} became unsafe in arena ${arena.id}")
                            skipped++; index++; probe = 0
                        }
                    }
                    index == plan.size
                }.thenCompose { tasks.onMain {
                    check(scene.size > 0) { "Arena ${arena.id} has no safe loot spawn points" }
                    if (skipped > 0) plugin.logger.warning("ArcEvents skipped $skipped unsafe loot points in arena ${arena.id}; match preparation continues")
                    debug.event("loot_spawned", "match" to match.matchId, "arena" to arena.id, "entities" to scene.size)
                } }
            }.thenCompose { it } }
        }.handle { _, failure -> failure }.thenCompose { failure -> tasks.onMain {
            release(owned).handle { _, cleanupFailure ->
                if (failure != null) { cleanupFailure?.let(failure::addSuppressed); throw failure }
                cleanupFailure?.let { throw it }
                Unit
            }
        }.thenCompose { it } }
    }

    private fun release(owned: LootLeaseBatch): CompletableFuture<Unit> {
        owned.release?.let { return it }
        pendingLeases.remove(owned)
        val iterator = owned.leases.iterator()
        var failure: Throwable? = null
        return requireNotNull(work).cleanupNative {
            if (iterator.hasNext()) runCatching { iterator.next().close() }.onFailure { problem ->
                if (failure == null) failure = problem else failure.addSuppressed(problem)
            }
            if (!iterator.hasNext()) { failure?.let { throw it }; true } else false
        }.also { owned.release = it }
    }

    /** Detach accepted temporary leases before the consumer scope closes. */
    fun close() {
        cancel()
        pendingLeases.toList().forEach(::release)
    }

    fun spawn(current: TttMatch, arena: ArenaSettings) {
        val world = requireNotNull(plugin.server.getWorld(arena.world))
        val seed = current.matchId.mostSignificantBits xor current.matchId.leastSignificantBits
        val layout = layout(arena, seed)
        var skipped = 0
        layout.forEach { spawn ->
            val stack = spawn.firearm?.let { firearms.firearmItem(it, null, current.matchId.toString()) }
                ?: firearms.ammunition(null, current.matchId.toString(), spawn.ammunition)
            val location = Location(world, spawn.point.x, spawn.point.y, spawn.point.z)
            val item = if (spawn.exact) scene.spawnExact(location, stack) else scene.spawn(location, stack)
            if (item == null && spawn.exact) {
                error("Guaranteed weapon point ${spawn.point.blockKey()} became unsafe in arena ${arena.id}")
            }
            if (item == null) skipped += 1
        }
        check(scene.size > 0) { "Arena ${arena.id} has no safe loot spawn points" }
        if (skipped > 0) {
            plugin.logger.warning("ArcEvents skipped $skipped unsafe loot points in arena ${arena.id}; match preparation continues")
        }
        debug.event("loot_spawned", "match" to current.matchId, "arena" to arena.id, "entities" to scene.size)
    }

    private fun layout(arena: ArenaSettings, seed: Long): List<TttLootSpawn> =
        if (arena.template == TttCitadelBlueprint.TEMPLATE) {
            TttCitadelLoot.layout(seed, firearms.catalog()).map { spawn ->
                TttLootSpawn(
                    point = EventLocation(arena.world, spawn.point.x, spawn.point.y, spawn.point.z),
                    firearm = spawn.firearm,
                    ammunition = spawn.ammunition,
                    exact = false,
                )
            }
        } else {
            TttLootLayoutPlanner.imported(
                points = arena.lootSpawns,
                guaranteedWeaponPoints = weaponPoints.points(arena),
                weaponCount = arena.weaponCount,
                seed = seed,
                catalog = firearms.catalog(),
            )
        }
}

/** Exact former nearby-probe order, detached from native blocks and registries. */
internal fun nearbyLootCandidates(origin: EventLocation): List<EventLocation> {
    val key = origin.blockKey()
    return buildList {
        for (radius in 0..4) for (y in intArrayOf(0, -1, 1, -2, 2, -3, 3)) {
            for (x in -radius..radius) for (z in -radius..radius) {
                if (kotlin.math.abs(x) + kotlin.math.abs(z) == radius) {
                    add(EventLocation(origin.world, key.x + x + 0.5, key.y + y + 0.1, key.z + z + 0.5, origin.yaw, origin.pitch))
                }
            }
        }
    }
}
