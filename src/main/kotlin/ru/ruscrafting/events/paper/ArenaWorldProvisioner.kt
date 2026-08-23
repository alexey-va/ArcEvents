package ru.ruscrafting.events.paper

import org.bukkit.Difficulty
import org.bukkit.GameRules
import org.bukkit.World
import org.bukkit.WorldCreator
import org.bukkit.plugin.Plugin
import ru.ruscrafting.events.config.ArcEventsConfig
import ru.ruscrafting.events.config.NodeMode
import java.nio.file.Files
import java.nio.file.StandardOpenOption

class ArenaWorldProvisioner(private val plugin: Plugin) {
    fun ensureLoaded(settings: ArcEventsConfig): World? {
        val arena = settings.arena
        if (!arena.enabled || arena.template.isEmpty()) return plugin.server.getWorld(arena.world)
        require(settings.nodeMode == NodeMode.HOST) { "Only a HOST node may provision an arena world" }
        require(arena.template == TttCitadelBlueprint.TEMPLATE) { "Unsupported arena template ${arena.template}" }

        val container = plugin.server.worldContainer.toPath().toAbsolutePath().normalize()
        val worldFolder = container.resolve(arena.world).normalize()
        require(worldFolder.parent == container) { "Arena world must be a direct child of the world container" }
        require(!Files.isSymbolicLink(worldFolder)) { "Arena world directory must not be a symbolic link" }
        val marker = worldFolder.resolve(MARKER)
        val existing = plugin.server.getWorld(arena.world)
        if (existing != null) {
            require(Files.isRegularFile(marker) && Files.readString(marker).trim() == arena.template) {
                "Refusing to adopt an unmarked existing world ${arena.world}"
            }
            requireArenaChunks(existing, generate = false)
            configure(existing)
            return existing
        }
        val markedExistingWorld = Files.exists(worldFolder)
        require(!markedExistingWorld || Files.isRegularFile(marker) && Files.readString(marker).trim() == arena.template) {
            "Refusing to overwrite an existing unmarked world ${arena.world}"
        }

        val world = requireNotNull(
            WorldCreator.name(arena.world)
                .seed(TttCitadelBlueprint.SEED)
                .environment(World.Environment.NORMAL)
                .generator(TttCitadelChunkGenerator())
                .generateStructures(false)
                .createWorld(),
        ) { "Could not create arena world ${arena.world}" }
        requireArenaChunks(world, generate = !markedExistingWorld)
        if (!markedExistingWorld) {
            Files.writeString(
                marker,
                arena.template + "\n",
                StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE,
            )
        }
        configure(world)
        val action = if (markedExistingWorld) "Loaded" else "Provisioned"
        plugin.logger.info("$action ArcEvents arena world=${world.name} template=${arena.template}")
        return world
    }

    private fun requireArenaChunks(world: World, generate: Boolean) {
        val minChunkX = TttCitadelBlueprint.MIN_X.floorDiv(16)
        val maxChunkX = TttCitadelBlueprint.MAX_X.floorDiv(16)
        val minChunkZ = TttCitadelBlueprint.MIN_Z.floorDiv(16)
        val maxChunkZ = TttCitadelBlueprint.MAX_Z.floorDiv(16)
        for (chunkX in minChunkX..maxChunkX) {
            for (chunkZ in minChunkZ..maxChunkZ) {
                if (generate) require(world.loadChunk(chunkX, chunkZ, true)) { "Could not generate arena chunk $chunkX,$chunkZ" }
                require(world.isChunkGenerated(chunkX, chunkZ)) { "Arena chunk $chunkX,$chunkZ is missing" }
            }
        }
    }

    private fun configure(world: World) {
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
        world.time = 6000L
        world.setStorm(false)
        world.isThundering = false
        world.setSpawnLocation(TttCitadelBlueprint.lobby.x.toInt(), TttCitadelBlueprint.lobby.y.toInt(), TttCitadelBlueprint.lobby.z.toInt())
        world.worldBorder.setCenter(0.0, 0.0)
        world.worldBorder.size = TttCitadelBlueprint.PLAYABLE_MAX - TttCitadelBlueprint.PLAYABLE_MIN
    }

    companion object {
        private const val MARKER = ".arcevents-template"
    }
}
