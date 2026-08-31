package ru.ruscrafting.events.paper

import org.bukkit.generator.ChunkGenerator
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path

internal const val ARENA_TEMPLATE_MARKER = ".arcevents-template"

/** Resolves only ArcEvents-owned, marker-bound world generators at server load time. */
internal object ArenaWorldGeneratorRegistry {
    fun generatorFor(worldContainer: Path, worldName: String): ChunkGenerator? {
        val container = worldContainer.toAbsolutePath().normalize()
        val worldFolder = container.resolve(worldName).normalize()
        if (worldFolder.parent != container) return null
        require(!Files.isSymbolicLink(worldFolder)) { "ArcEvents arena world must not be a symbolic link" }

        val marker = worldFolder.resolve(ARENA_TEMPLATE_MARKER)
        if (!Files.exists(marker, LinkOption.NOFOLLOW_LINKS)) return null
        require(!Files.isSymbolicLink(marker) && Files.isRegularFile(marker, LinkOption.NOFOLLOW_LINKS)) {
            "ArcEvents arena template marker must be a regular file"
        }
        require(Files.size(marker) in 1..MAX_MARKER_BYTES) { "ArcEvents arena template marker is invalid" }
        val template = Files.readString(marker, StandardCharsets.UTF_8).trim()
        return when {
            template == TttCitadelBlueprint.TEMPLATE -> TttCitadelChunkGenerator()
            PackagedArenaTemplates.find(template) != null -> EmptyArenaChunkGenerator()
            ReviewedImportedArenaTemplates.find(template) != null -> EmptyArenaChunkGenerator()
            else -> error("ArcEvents arena template marker is not reviewed: $template")
        }
    }

    private const val MAX_MARKER_BYTES = 128L
}
