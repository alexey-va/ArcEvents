package ru.ruscrafting.events.paper

import com.google.gson.Gson
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.Comparator
import java.util.UUID
import kotlin.io.path.name

/** Worker-only ownership proof for temporary arena worlds. Unmarked legacy folders are retained. */
internal class ArenaStagingFiles(private val container: Path) {
    private data class Intent(val format: Int, val stage: String, val world: String, val template: String)
    data class Recovery(val removed: Int, val retainedLegacy: List<String>)
    private val gson = Gson()

    fun create(world: String, template: String): Path {
        require(container.resolve(world).normalize().parent == container && world.isNotBlank())
        val name = "$PREFIX${world.take(24)}_${UUID.randomUUID().toString().replace("-", "").take(12)}"
        val stage = container.resolve(name)
        Files.createDirectory(stage)
        Files.writeString(stage.resolve(INTENT), gson.toJson(Intent(1, name, world, template)),
            StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)
        return stage
    }

    /** Publish a marked directory before Paper can write even the first chunk. Worker-only. */
    fun createGeneratedWorld(world: String, template: String) {
        require(isGeneratedArenaTemplate(template))
        val destination = container.resolve(world)
        val stage = create(world, template)
        Files.writeString(stage.resolve(ARENA_TEMPLATE_MARKER), "$template\n",
            StandardOpenOption.CREATE_NEW, StandardOpenOption.SYNC)
        FileChannel.open(stage, StandardOpenOption.READ).use { it.force(true) }
        // Same filesystem. The ordinary move rejects an existing target, even an empty foreign folder.
        Files.move(stage, destination)
        FileChannel.open(container, StandardOpenOption.READ).use { it.force(true) }
        Files.delete(destination.resolve(INTENT))
    }

    fun recover(loadedWorldNames: Set<String>): Recovery {
        var removed = 0
        val retained = mutableListOf<String>()
        Files.list(container).use { children -> children.filter { it.name.startsWith(PREFIX) }.forEach { stage ->
            if (!Files.exists(stage.resolve(INTENT), LinkOption.NOFOLLOW_LINKS)) {
                retained += stage.name
            } else {
                validate(stage)
                check(stage.name !in loadedWorldNames) { "Owned arena stage ${stage.name} is still loaded; recovery retained it" }
                delete(stage)
                removed++
            }
        } }
        return Recovery(removed, retained)
    }

    fun delete(stage: Path) {
        validate(stage)
        // Validate the entire bounded tree before deleting anything. Never follow links in a staged world.
        val paths = Files.walk(stage).use { stream ->
            val result = ArrayList<Path>()
            var bytes = 0L
            stream.forEach { path ->
                require(!Files.isSymbolicLink(path)) { "Arena stage contains a symbolic link: ${path.name}" }
                require(result.size < 20_000) { "Arena stage contains too many files" }
                if (Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) bytes = Math.addExact(bytes, Files.size(path))
                require(bytes <= 2_147_483_648L) { "Arena stage is too large" }
                result.add(path)
            }
            result
        }
        paths.sortWith(Comparator.reverseOrder())
        paths.forEach(Files::deleteIfExists)
    }

    private fun validate(stage: Path) {
        require(stage.parent == container && stage.name.startsWith(PREFIX) && !Files.isSymbolicLink(stage) &&
            Files.isDirectory(stage, LinkOption.NOFOLLOW_LINKS)) { "Invalid arena staging directory" }
        val marker = stage.resolve(INTENT)
        require(!Files.isSymbolicLink(marker) && Files.isRegularFile(marker, LinkOption.NOFOLLOW_LINKS) &&
            Files.size(marker) in 1..2_048) { "Invalid arena staging ownership marker" }
        val intent = gson.fromJson(Files.readString(marker), Intent::class.java)
        require(intent.format == 1 && intent.stage == stage.name && intent.world.isNotBlank() &&
            container.resolve(intent.world).normalize().parent == container &&
            (PackagedArenaTemplates.find(intent.template) != null || isGeneratedArenaTemplate(intent.template) ||
                intent.template in retiredArenaTemplates)) {
            "Arena staging ownership does not match its path"
        }
    }

    companion object {
        const val PREFIX = ".arcevents-stage-"
        const val INTENT = ".arcevents-staging.json"
    }
}

internal val retiredArenaTemplates = setOf("fishing-v1", "fishing-v2", "fishing-v3", "fishing-v4", "fishing-archipelago-v1",
    "zombies-zero-v1", "zombies-zero-v2", "zombies-zero-v3", "disasters-v1")

internal fun isGeneratedArenaTemplate(template: String): Boolean = template == TttCitadelBlueprint.TEMPLATE ||
    template == DisasterArenaGenerator.TEMPLATE || template == FishingArenaGenerator.TEMPLATE ||
    template == ZombiesArenaGenerator.TEMPLATE
