package ru.ruscrafting.events.paper

import com.google.gson.JsonParser
import org.bukkit.Difficulty
import org.bukkit.GameRules
import org.bukkit.Material
import org.bukkit.World
import org.bukkit.WorldCreator
import org.bukkit.plugin.Plugin
import ru.ruscrafting.events.config.ArcEventsConfig
import ru.ruscrafting.events.config.ArenaSettings
import ru.ruscrafting.events.config.NodeMode
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.streams.asSequence

class ArenaWorldProvisioner(private val plugin: Plugin) {
    fun ensureLoaded(settings: ArcEventsConfig): List<World> = settings.arenas.filter(ArenaSettings::enabled).map { arena ->
        ensureLoaded(settings, arena)
    }

    private fun ensureLoaded(settings: ArcEventsConfig, arena: ArenaSettings): World {
        require(settings.nodeMode == NodeMode.HOST) { "Only a HOST node may provision an arena world" }
        if (arena.template.isEmpty()) {
            val world = requireNotNull(plugin.server.getWorld(arena.world)) { "Custom arena world ${arena.world} is not loaded" }
            requireArenaChunks(world, arena, generate = false)
            configure(world, arena)
            rejectCommandBlocks(world, arena)
            return world
        }
        val container = plugin.server.worldContainer.toPath().toAbsolutePath().normalize()
        val worldFolder = container.resolve(arena.world).normalize()
        require(worldFolder.parent == container) { "Arena world must be a direct child of the world container" }
        require(!Files.isSymbolicLink(worldFolder)) { "Arena world directory must not be a symbolic link" }
        val marker = worldFolder.resolve(MARKER)
        val existing = plugin.server.getWorld(arena.world)
        val builtIn = arena.template == TttCitadelBlueprint.TEMPLATE

        if (existing != null) {
            requireMarker(arena, marker)
            if (!builtIn) requireImportedWorld(worldFolder, arena)
            requireArenaChunks(existing, arena, generate = false)
            configure(existing, arena)
            rejectCommandBlocks(existing, arena)
            return existing
        }

        val folderExists = Files.exists(worldFolder)
        require(!folderExists || Files.isDirectory(worldFolder)) { "Arena path must be a directory" }
        if (folderExists) requireMarker(arena, marker)
        if (!builtIn) {
            require(folderExists) { "Imported arena world ${arena.world} is missing" }
            requireImportedWorld(worldFolder, arena)
        }

        val creator = WorldCreator.name(arena.world).environment(World.Environment.NORMAL).generateStructures(false)
        if (builtIn) creator.seed(TttCitadelBlueprint.SEED).generator(TttCitadelChunkGenerator())
        val world = requireNotNull(creator.createWorld()) { "Could not load arena world ${arena.world}" }
        requireArenaChunks(world, arena, generate = builtIn && !folderExists)
        if (builtIn && !folderExists) {
            Files.writeString(marker, arena.template + "\n", StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)
        }
        configure(world, arena)
        rejectCommandBlocks(world, arena)
        val action = if (folderExists) "Loaded" else "Provisioned"
        plugin.logger.info("$action ArcEvents arena id=${arena.id} world=${world.name} template=${arena.template}")
        return world
    }

    private fun requireMarker(arena: ArenaSettings, marker: java.nio.file.Path) {
        require(Files.isRegularFile(marker) && Files.size(marker) <= 128L && Files.readString(marker).trim() == arena.template) {
            "Refusing to adopt an unmarked arena world ${arena.world}"
        }
    }

    private fun requireImportedWorld(worldFolder: java.nio.file.Path, arena: ArenaSettings) {
        require(Files.isRegularFile(worldFolder.resolve("level.dat"))) { "Imported arena is missing level.dat" }
        val sourceManifest = worldFolder.resolve(SOURCE_MANIFEST)
        require(Files.isRegularFile(sourceManifest) && Files.size(sourceManifest) in 1..MAX_MANIFEST_BYTES) {
            "Imported arena is missing a bounded source manifest"
        }
        val source = JsonParser.parseString(Files.readString(sourceManifest)).asJsonObject
        require(source.get("format")?.asInt == 1) { "Imported arena source manifest format is unsupported" }
        require(source.get("world")?.asString == arena.world && source.get("template")?.asString == arena.template) {
            "Imported arena source identity does not match its configuration"
        }
        require(source.get("source")?.asString?.startsWith("https://") == true) { "Imported arena source URL is invalid" }
        require(source.get("source_archive_sha256")?.asString?.matches(SHA256) == true) { "Imported arena archive hash is invalid" }
        val sanitized = requireNotNull(source.getAsJsonObject("sanitized")) { "Imported arena sanitizer record is missing" }
        require(sanitized.get("command_block_entities_removed")?.asInt?.let { it >= 0 } == true) {
            "Imported arena sanitizer record is invalid"
        }
        var files = 0
        var bytes = 0L
        Files.walk(worldFolder).use { paths ->
            paths.forEach { path ->
                require(!Files.isSymbolicLink(path)) { "Imported arena contains a symbolic link" }
                files++
                require(files <= MAX_WORLD_FILES) { "Imported arena contains too many files" }
                if (Files.isRegularFile(path)) {
                    bytes += Files.size(path)
                    require(bytes <= MAX_WORLD_BYTES) { "Imported arena exceeds the size limit" }
                }
            }
        }
        val datapacks = worldFolder.resolve("datapacks")
        if (Files.isDirectory(datapacks)) {
            Files.list(datapacks).use { entries ->
                require(entries.asSequence().none()) { "Imported arena datapacks must be empty" }
            }
        }
    }

    private fun requireArenaChunks(world: World, arena: ArenaSettings, generate: Boolean) {
        val bounds = requireNotNull(arena.bounds)
        val minChunkX = floor(bounds.minimum.x).toInt().floorDiv(16)
        val maxChunkX = ceil(bounds.maximum.x).toInt().floorDiv(16)
        val minChunkZ = floor(bounds.minimum.z).toInt().floorDiv(16)
        val maxChunkZ = ceil(bounds.maximum.z).toInt().floorDiv(16)
        for (chunkX in minChunkX..maxChunkX) {
            for (chunkZ in minChunkZ..maxChunkZ) {
                require(world.loadChunk(chunkX, chunkZ, generate)) { "Could not load arena chunk $chunkX,$chunkZ" }
                require(world.isChunkGenerated(chunkX, chunkZ)) { "Arena chunk $chunkX,$chunkZ is missing" }
            }
        }
    }

    private fun rejectCommandBlocks(world: World, arena: ArenaSettings) {
        val bounds = requireNotNull(arena.bounds)
        val minChunkX = floor(bounds.minimum.x).toInt().floorDiv(16)
        val maxChunkX = ceil(bounds.maximum.x).toInt().floorDiv(16)
        val minChunkZ = floor(bounds.minimum.z).toInt().floorDiv(16)
        val maxChunkZ = ceil(bounds.maximum.z).toInt().floorDiv(16)
        for (chunkX in minChunkX..maxChunkX) for (chunkZ in minChunkZ..maxChunkZ) {
            val unsafe = world.getChunkAt(chunkX, chunkZ).tileEntities.firstOrNull { it.type in COMMAND_BLOCKS }
            require(unsafe == null) { "Arena ${arena.id} contains a command block at ${unsafe?.location}" }
        }
    }

    private fun configure(world: World, arena: ArenaSettings) {
        val bounds = requireNotNull(arena.bounds)
        val lobby = requireNotNull(arena.lobby)
        world.difficulty = Difficulty.PEACEFUL
        world.setSpawnFlags(false, false)
        world.setGameRule(GameRules.PVP, true)
        world.setGameRule(GameRules.SPAWN_MOBS, false)
        world.setGameRule(GameRules.MOB_GRIEFING, false)
        world.setGameRule(GameRules.FIRE_SPREAD_RADIUS_AROUND_PLAYER, 0)
        world.setGameRule(GameRules.NATURAL_HEALTH_REGENERATION, false)
        world.setGameRule(GameRules.PROJECTILES_CAN_BREAK_BLOCKS, false)
        world.setGameRule(GameRules.TNT_EXPLODES, false)
        world.setGameRule(GameRules.ADVANCE_TIME, false)
        world.setGameRule(GameRules.ADVANCE_WEATHER, false)
        world.setGameRule(GameRules.KEEP_INVENTORY, true)
        world.setGameRule(GameRules.RESPAWN_RADIUS, 0)
        world.setGameRule(GameRules.COMMAND_BLOCKS_WORK, false)
        world.setGameRule(GameRules.COMMAND_BLOCK_OUTPUT, false)
        world.time = 6000L
        world.setStorm(false)
        world.isThundering = false
        world.setSpawnLocation(lobby.x.toInt(), lobby.y.toInt(), lobby.z.toInt())
        world.worldBorder.setCenter(
            (bounds.minimum.x + bounds.maximum.x) / 2.0,
            (bounds.minimum.z + bounds.maximum.z) / 2.0,
        )
        world.worldBorder.size = maxOf(bounds.maximum.x - bounds.minimum.x, bounds.maximum.z - bounds.minimum.z)
    }

    companion object {
        private const val MARKER = ".arcevents-template"
        private const val SOURCE_MANIFEST = ".arcevents-source.json"
        private const val MAX_MANIFEST_BYTES = 16_384L
        private const val MAX_WORLD_FILES = 20_000
        private const val MAX_WORLD_BYTES = 2_147_483_648L
        private val SHA256 = Regex("[a-f0-9]{64}")
        private val COMMAND_BLOCKS = setOf(Material.COMMAND_BLOCK, Material.CHAIN_COMMAND_BLOCK, Material.REPEATING_COMMAND_BLOCK)
    }
}
