package ru.ruscrafting.events.paper

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
) {
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
            TttCitadelLoot.layout(seed).map { spawn ->
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
            )
        }
}
