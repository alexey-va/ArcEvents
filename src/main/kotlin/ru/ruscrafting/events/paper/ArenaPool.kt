package ru.ruscrafting.events.paper

import ru.ruscrafting.events.config.ArcEventsConfig
import ru.ruscrafting.events.config.ArenaSettings
import java.util.UUID

data class ArenaPoolEntry(
    val id: String,
    val world: String,
    val template: String,
    val ready: Boolean,
    val active: Boolean,
    val next: Boolean,
)

/** Owns the single-match arena lease and the one-shot administrator override. */
class ArenaPool(
    private val settings: () -> ArcEventsConfig,
    private val clock: () -> Long = System::currentTimeMillis,
    private val ready: (ArenaSettings, Int) -> Boolean,
) {
    private var activeMatchId: UUID? = null
    private var activeArenaId: String? = null
    private var nextArenaId: String? = null
    private var availabilitySettings: ArcEventsConfig? = null
    private var availabilityCheckedAt: Long? = null
    private var availabilityReady = false

    @Synchronized
    fun anyReady(): Boolean {
        val current = settings()
        val now = clock()
        val checkedAt = availabilityCheckedAt
        if (
            availabilitySettings === current && checkedAt != null && now >= checkedAt &&
            now - checkedAt < AVAILABILITY_CACHE_MILLIS
        ) return availabilityReady
        val maximumPlayers = current.ttt.maximumPlayers
        return current.arenas.any { ready(it, maximumPlayers) }.also { available ->
            cacheAvailability(current, now, available)
        }
    }

    @Synchronized
    fun active(): ArenaSettings? = activeArenaId?.let(::configured)

    @Synchronized
    fun entries(): List<ArenaPoolEntry> {
        val maximumPlayers = settings().ttt.maximumPlayers
        return settings().arenas.map { arena ->
            ArenaPoolEntry(
                id = arena.id,
                world = arena.world,
                template = arena.template,
                ready = ready(arena, maximumPlayers),
                active = arena.id == activeArenaId,
                next = arena.id == nextArenaId,
            )
        }
    }

    @Synchronized
    fun selectNext(id: String?): Boolean {
        if (activeMatchId != null) return false
        if (id == null || id == "auto") {
            nextArenaId = null
            return true
        }
        val arena = configured(id) ?: return false
        if (!ready(arena, settings().ttt.maximumPlayers)) return false
        nextArenaId = arena.id
        return true
    }

    @Synchronized
    fun reserve(matchId: UUID, preferredId: String? = null): ArenaSettings? {
        if (activeMatchId != null) return null
        val ready = readyArenas()
        if (ready.isEmpty()) return null
        val requested = preferredId?.takeUnless { it == "auto" } ?: nextArenaId
        val chosen = requested?.let { id -> ready.firstOrNull { it.id == id } }
            ?: ready[Math.floorMod(matchId.mostSignificantBits xor matchId.leastSignificantBits, ready.size.toLong()).toInt()]
        activeMatchId = matchId
        activeArenaId = chosen.id
        nextArenaId = null
        return chosen
    }

    @Synchronized
    fun release(matchId: UUID) {
        if (activeMatchId == matchId) {
            activeMatchId = null
            activeArenaId = null
        }
    }

    @Synchronized
    fun clear() {
        activeMatchId = null
        activeArenaId = null
        nextArenaId = null
    }

    private fun readyArenas(): List<ArenaSettings> {
        val current = settings()
        val maximumPlayers = current.ttt.maximumPlayers
        return current.arenas.filter { ready(it, maximumPlayers) }.sortedBy(ArenaSettings::id).also { arenas ->
            cacheAvailability(current, clock(), arenas.isNotEmpty())
        }
    }

    private fun configured(id: String): ArenaSettings? = settings().arenas.firstOrNull { it.id == id.lowercase() }

    private fun cacheAvailability(current: ArcEventsConfig, checkedAt: Long, available: Boolean) {
        availabilitySettings = current
        availabilityCheckedAt = checkedAt
        availabilityReady = available
    }

    private companion object {
        const val AVAILABILITY_CACHE_MILLIS = 30_000L
    }
}
