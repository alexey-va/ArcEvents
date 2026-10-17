package ru.ruscrafting.events.paper

import org.bukkit.NamespacedKey
import org.bukkit.World
import org.bukkit.entity.Entity
import org.bukkit.persistence.PersistentDataType
import org.bukkit.util.BoundingBox
import java.util.UUID
import java.util.concurrent.CompletableFuture
import kotlin.math.floor

/** Detached native cursor. Its host-owned continuation never reads an event runtime after disable. */
internal fun cleanupFishingEntities(
    world: World,
    ownerKey: NamespacedKey,
    ownerTag: String,
    matchKey: NamespacedKey,
    matchId: String?,
    stageKey: NamespacedKey,
    stage: Int?,
    directEntities: List<Entity>,
    cancelled: () -> Boolean = { false },
    submit: ((() -> Boolean) -> CompletableFuture<Unit>),
): CompletableFuture<Unit> {
    // These are the same five boxes used by the original nearby-entity cleanup. Querying their
    // intersections with loaded chunks bounds each native capture without widening ownership.
    val boxes = FishingArenaStage.entries.map {
        BoundingBox(it.centerX + .5 - 25.0, FishingArenaGenerator.SPAWN_Y - 48.0, .5 - 32.0,
            it.centerX + .5 + 25.0, FishingArenaGenerator.SPAWN_Y + 48.0, .5 + 32.0)
    }
    val direct = directEntities.iterator()
    val seen = hashSetOf<UUID>()
    var candidates: Iterator<Entity> = emptyList<Entity>().iterator()
    var boxIndex = 0
    var chunkX = floor(boxes.first().minX).toInt() shr 4
    var chunkZ = floor(boxes.first().minZ).toInt() shr 4
    var failure: Throwable? = null
    fun record(problem: Throwable) {
        if (failure == null) failure = problem else failure!!.addSuppressed(problem)
    }
    return submit {
        if (cancelled()) throw java.util.concurrent.CancellationException("Fishing cleanup source retired")
        if (direct.hasNext()) {
            val entity = direct.next()
            try { if (seen.add(entity.uniqueId)) entity.remove() } catch (problem: Throwable) { record(problem) }
            false
        } else if (candidates.hasNext()) {
            val entity = candidates.next()
            try {
                if (seen.add(entity.uniqueId) &&
                    entity.persistentDataContainer.get(ownerKey, PersistentDataType.STRING) == ownerTag &&
                    (matchId == null || entity.persistentDataContainer.get(matchKey, PersistentDataType.STRING) == matchId) &&
                    (stage == null || entity.persistentDataContainer.get(stageKey, PersistentDataType.INTEGER) == stage)) {
                    entity.remove()
                }
            } catch (problem: Throwable) { record(problem) }
            false
        } else if (boxIndex < boxes.size) {
            val box = boxes[boxIndex]
            val x = chunkX
            val z = chunkZ
            chunkZ++
            if (chunkZ > (floor(box.maxZ).toInt() shr 4)) {
                chunkZ = floor(box.minZ).toInt() shr 4
                chunkX++
                if (chunkX > (floor(box.maxX).toInt() shr 4)) {
                    boxIndex++
                    if (boxIndex < boxes.size) {
                        chunkX = floor(boxes[boxIndex].minX).toInt() shr 4
                        chunkZ = floor(boxes[boxIndex].minZ).toInt() shr 4
                    }
                }
            }
            try {
                if (world.isChunkLoaded(x, z)) {
                    val cell = BoundingBox(maxOf(box.minX, (x shl 4).toDouble()), box.minY,
                        maxOf(box.minZ, (z shl 4).toDouble()), minOf(box.maxX, ((x + 1) shl 4).toDouble()),
                        box.maxY, minOf(box.maxZ, ((z + 1) shl 4).toDouble()))
                    candidates = world.getNearbyEntities(cell).iterator()
                }
            } catch (problem: Throwable) { record(problem) }
            false
        } else {
            failure?.let { throw it }
            true
        }
    }
}
