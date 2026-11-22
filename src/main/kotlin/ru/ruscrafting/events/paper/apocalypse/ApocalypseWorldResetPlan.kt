package ru.ruscrafting.events.paper.apocalypse

import ru.arc.paper.playerstate.PaperLocationSnapshot
import ru.arc.paper.playerstate.PaperPlayerStateCodec
import org.bukkit.configuration.file.YamlConfiguration
import ru.ruscrafting.events.paper.EventWork
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.CompletableFuture

/** One read-only inspection. A PARKED origin is still a recovery receipt for stale native player data. */
internal class ApocalypseWorldResetPlan(
    private val current: String,
    private val worldId: UUID,
    private val seed: Long,
    private val target: String,
    private val records: Collection<ApocalypsePlayerRecord>,
) {
    private val pathProblems = mutableListOf<String>()
    private var originLocations = 0
    private var originCompasses = 0
    private var unreadableOrigins = 0
    private val affected = mutableListOf<String>()

    /** Only one origin envelope is prepared and bound at a time. Native item binding uses Paper's budgeted queue. */
    fun inspect(worlds: Path, pluginRoot: Path, work: EventWork): CompletableFuture<Unit> {
        check(targetProblem(current, target) == null)
        var inspected = work.compute { pathProblems.addAll(inspectPaths(worlds, pluginRoot)); Unit }
        val codec = PaperPlayerStateCodec()
        for (record in records) {
            val envelope = record.origin ?: continue
            inspected = inspected.thenCompose {
                work.compute { codec.prepareDecode(envelope) }.thenCompose { prepared ->
                    work.callNative {
                        val origin = codec.decode(prepared)
                        require(origin.playerId.toString() == record.playerId)
                        val location = origin.location.referencesCurrent()
                        val compass = origin.compassTarget.referencesCurrent()
                        if (location) originLocations++
                        if (compass) originCompasses++
                        if (location || compass) remember(record.playerId)
                        Unit
                    }
                }.handle { _, failure ->
                    if (failure != null) {
                        unreadableOrigins++
                        remember(record.playerId)
                    }
                    Unit
                }
            }
        }
        return inspected
    }

    fun inspectPaths(worlds: Path, pluginRoot: Path): List<String> = buildList {
        val source = worlds.resolve(current)
        val journalRoot = pluginRoot.resolve("data/apocalypse")
        if (listOf(worlds, source, pluginRoot, pluginRoot.resolve("data"), journalRoot)
                .any { !Files.isDirectory(it, NOFOLLOW_LINKS) }) {
            add("unsafe_directory")
            return@buildList
        }
        val marker = source.resolve(".arcevents-template")
        if (!Files.isRegularFile(marker, NOFOLLOW_LINKS) || Files.newInputStream(marker, NOFOLLOW_LINKS).use {
                val bytes = it.readNBytes(129)
                bytes.size > 128 || bytes.toString(Charsets.UTF_8).trim() != "${ApocalypseOriginalGenerator.TEMPLATE}:$seed"
            }) add("source_marker")
        // notExists also refuses an inaccessible path and treats dangling links as existing entries.
        if (!Files.notExists(worlds.resolve(target), NOFOLLOW_LINKS)) add("target_directory")
        if (!Files.notExists(journalRoot.resolve("world-$target"), NOFOLLOW_LINKS)) add("target_checkpoint")
        val registry = pluginRoot.resolveSibling("My_Worlds").resolve("worlds.yml")
        if (!Files.notExists(registry, NOFOLLOW_LINKS)) {
            require(Files.isRegularFile(registry, NOFOLLOW_LINKS) && Files.size(registry) <= 4 * 1024 * 1024)
            val storedWorlds = YamlConfiguration().apply { load(registry.toFile()) }
            if (storedWorlds.getKeys(false).any { it.equals(target, true) }) add("target_registered")
            if (storedWorlds.getKeys(false).any { it.equals(current, true) && storedWorlds.getBoolean("$it.loaded", true) })
                add("source_autoload")
        }
    }

    private fun PaperLocationSnapshot.referencesCurrent() = worldId == this@ApocalypseWorldResetPlan.worldId || worldName == current

    private fun remember(id: String) {
        if (affected.size < 5) affected += id
    }

    fun readout(occupants: Int, onlineActive: Int, busy: Int, checkpointPending: Boolean, targetRegistered: Boolean, changed: Boolean): Readout {
        val blockers = buildList {
            addAll(pathProblems)
            if (originLocations > 0 || originCompasses > 0) add("origin_references_world")
            if (unreadableOrigins > 0) add("unreadable_origin")
            if (occupants > 0 || onlineActive > 0) add("world_occupied")
            if (busy > 0 || checkpointPending) add("pending_work")
            if (targetRegistered) add("target_registered")
            if (changed) add("snapshot_changed")
        }
        val active = records.count { it.phase == ApocalypsePlayerPhase.ACTIVE }
        val returning = records.count { it.phase == ApocalypsePlayerPhase.RETURNING }
        val message = "$PREFIX arc_ready=${blockers.isEmpty()} current=$current target=$target seed=$seed" +
            " occupants=$occupants online_active=$onlineActive busy=$busy checkpoint_pending=$checkpointPending" +
            " active_records=$active returning_records=$returning pending_returns=${records.count { it.returnPending }}" +
            " origin_locations=$originLocations origin_compasses=$originCompasses unreadable_origins=$unreadableOrigins" +
            " affected_sample=${affected.joinToString(",").ifEmpty { "-" }} blockers=${blockers.distinct().joinToString(",").ifEmpty { "-" }}"
        return Readout(blockers.isEmpty(), message)
    }

    data class Readout(val ready: Boolean, val message: String)

    companion object {
        private const val PREFIX = "ARCEVENTS_APOCALYPSE_RESET_PLAN"
        private val WORLD_NAME = Regex("arcevents_[a-z0-9_]{1,48}")

        fun targetProblem(current: String, target: String): String? = when {
            !WORLD_NAME.matches(target) -> "invalid_world_name"
            target == current -> "current_world"
            target == "arcevents_apocalypse_v1" -> "legacy_world_alias"
            else -> null
        }

        fun blocked(reason: String) = "$PREFIX arc_ready=false blockers=$reason"

        fun blocked(reason: String, current: String, target: String) =
            "$PREFIX arc_ready=false current=$current target=$target blockers=$reason"
    }
}
