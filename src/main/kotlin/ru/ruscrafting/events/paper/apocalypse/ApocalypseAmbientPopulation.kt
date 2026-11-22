package ru.ruscrafting.events.paper.apocalypse

import org.bukkit.Location
import org.bukkit.entity.Player
import org.bukkit.entity.Zombie
import ru.ruscrafting.events.domain.apocalypse.ApocalypseNaturalSpawnRule
import kotlin.math.abs
import kotlin.random.Random

internal const val APOCALYPSE_ACTOR_RANGE = 128.0
private const val ACTOR_RANGE_SQUARED = APOCALYPSE_ACTOR_RANGE * APOCALYPSE_ACTOR_RANGE

internal class ApocalypseSpawnActor(val token: String, val source: String, val mob: Zombie) {
    var at: Location = mob.location
}

/** Legacy ambient zombies may still be on disk. Keep the ones already in a live encounter. */
internal fun retainLoadedAmbient(zombie: Zombie, registered: Boolean): Boolean {
    zombie.isPersistent = false
    if (registered || zombie.trackedBy.isNotEmpty()) return true
    zombie.remove()
    return false
}

/** One snapshot for this second. Nearby teammates see the same local population. */
internal class ApocalypseAmbientPopulation(
    survivors: List<Player>,
    private val actors: MutableList<ApocalypseSpawnActor>,
    private val random: Random,
) {
    private val centers = survivors.shuffled(random).associateWith { it.location }
    private val nearby = centers.mapValues { (_, center) -> actors.count { center.distanceSquared(it.at) <= ACTOR_RANGE_SQUARED } }.toMutableMap()
    private var relocated = false

    fun spawn(
        rule: ApocalypseNaturalSpawnRule,
        maximumEnemies: Int,
        findGround: (Player, (Location) -> Int?) -> Location?,
        create: (String, Player, Location) -> ApocalypseSpawnActor?,
    ) {
        val candidates = centers.keys.toMutableList()
        var matching = actors.count { it.source in rule.entityIds }
        repeat(centers.size) {
            if (candidates.isEmpty() || random.nextDouble() >= rule.perSecond) return@repeat
            for (i in 0 until random.nextInt(rule.minimumAmount, rule.maximumAmount + 1)) {
                val player = candidates.minByOrNull { nearby.getValue(it) } ?: break
                val full = actors.size >= maximumEnemies || matching >= rule.maximumThisType
                val spare = if (full && !relocated) actors.firstOrNull { canSpare(it, player, rule) } else null
                if (full && spare == null) break

                val at = findGround(player) { placementCost(it, player, rule.minimumDistance) }
                if (at == null) {
                    candidates.remove(player)
                    continue
                }
                if (spare == null) {
                    val actor = create(rule.entityIds.random(random), player, at) ?: break
                    actors.add(actor)
                    matching++
                    countAt(actor.at, 1)
                } else {
                    if (!spare.mob.teleport(at)) break
                    spare.mob.target = player
                    countAt(spare.at, -1)
                    spare.at = spare.mob.location
                    countAt(spare.at, 1)
                    relocated = true
                }
            }
        }
    }

    private fun countAt(at: Location, delta: Int) {
        for ((player, center) in centers) {
            if (center.distanceSquared(at) <= ACTOR_RANGE_SQUARED) nearby[player] = nearby.getValue(player) + delta
        }
    }

    private fun canSpare(actor: ApocalypseSpawnActor, recipient: Player, rule: ApocalypseNaturalSpawnRule): Boolean {
        if (!actor.token.startsWith("ambient:") || actor.source !in rule.entityIds ||
            centers.getValue(recipient).distanceSquared(actor.at) <= ACTOR_RANGE_SQUARED) return false
        val nearest = centers.minBy { (_, center) -> center.distanceSquared(actor.at) }
        val distance = nearest.value.distanceSquared(actor.at)
        return distance >= rule.minimumDistance * rule.minimumDistance &&
            (distance > ACTOR_RANGE_SQUARED || nearby.getValue(nearest.key) > nearby.getValue(recipient) + 1) &&
            actor.mob.trackedBy.isEmpty() && actor.mob.noDamageTicks == 0
    }

    /** Prefer the survivor's floor and vacant approaches. A teammate or occupied spawn cell vetoes the point. */
    internal fun placementCost(at: Location, forPlayer: Player, minimumDistance: Int): Int? {
        if (centers.any { (player, center) -> player != forPlayer && center.distanceSquared(at) < minimumDistance * minimumDistance }) return null
        var cost = (abs(at.y - centers.getValue(forPlayer).y) / 4).toInt()
        for (actor in actors) {
            val distance = actor.at.distanceSquared(at)
            if (distance < 4) return null
            if (distance < 24 * 24) cost += 2
        }
        return cost
    }
}
