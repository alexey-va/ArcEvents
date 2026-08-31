package ru.ruscrafting.events.paper

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.sk89q.worldedit.WorldEdit
import com.sk89q.worldedit.bukkit.BukkitAdapter
import com.sk89q.worldedit.extent.clipboard.BlockArrayClipboard
import com.sk89q.worldedit.extent.clipboard.Clipboard
import com.sk89q.worldedit.extent.clipboard.io.BuiltInClipboardFormat
import com.sk89q.worldedit.function.operation.ForwardExtentCopy
import com.sk89q.worldedit.function.operation.Operations
import com.sk89q.worldedit.math.BlockVector3
import com.sk89q.worldedit.regions.CuboidRegion
import com.sk89q.worldedit.session.ClipboardHolder
import net.kyori.adventure.util.TriState
import org.bukkit.Difficulty
import org.bukkit.GameRules
import org.bukkit.Material
import org.bukkit.World
import org.bukkit.WorldCreator
import org.bukkit.entity.Display
import org.bukkit.entity.Player
import org.bukkit.plugin.Plugin
import ru.ruscrafting.events.config.ArcEventsConfig
import ru.ruscrafting.events.config.ArenaSettings
import ru.ruscrafting.events.config.NodeMode
import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.util.Comparator
import java.util.UUID
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.streams.asSequence

class ArenaWorldProvisioner(private val plugin: Plugin) {
    fun ensureLoaded(settings: ArcEventsConfig, excludedArenaId: String? = null): List<World> = settings.arenas
        .filter(ArenaSettings::enabled)
        .filterNot { it.id == excludedArenaId }
        .map { arena -> ensureLoaded(settings, arena) }

    private fun ensureLoaded(settings: ArcEventsConfig, arena: ArenaSettings): World {
        require(settings.nodeMode == NodeMode.HOST) { "Only a HOST node may provision an arena world" }
        if (arena.template.isEmpty()) {
            val world = requireNotNull(plugin.server.getWorld(arena.world)) { "Custom arena world ${arena.world} is not loaded" }
            configure(world, arena, settings)
            requireArenaChunks(world, arena, generate = false)
            rejectCommandBlocks(world, arena)
            return world
        }

        val container = plugin.server.worldContainer.toPath().toAbsolutePath().normalize()
        val worldFolder = directChild(container, arena.world)
        require(!Files.isSymbolicLink(worldFolder)) { "Arena world directory must not be a symbolic link" }
        val marker = worldFolder.resolve(ARENA_TEMPLATE_MARKER)
        val builtIn = arena.template == TttCitadelBlueprint.TEMPLATE
        val packaged = PackagedArenaTemplates.find(arena.template)
        require(builtIn || packaged != null) { "Arena template ${arena.template} is not a reviewed packaged template" }
        if (packaged != null) requireGlobalCommandBlocksDisabled(container)

        val existing = plugin.server.getWorld(arena.world)
        if (existing != null) {
            requireMarker(arena, marker)
            if (packaged != null) requirePackagedWorld(worldFolder, arena, packaged)
            configure(existing, arena, settings)
            requireArenaChunks(existing, arena, generate = false)
            if (packaged != null) sanitizeImportedDecorations(existing, arena, settings)
            rejectCommandBlocks(existing, arena)
            return existing
        }

        val folderExisted = Files.exists(worldFolder)
        require(!folderExisted || Files.isDirectory(worldFolder)) { "Arena path must be a directory" }
        if (folderExisted) {
            requireMarker(arena, marker)
            if (packaged != null) requirePackagedWorld(worldFolder, arena, packaged)
        } else if (packaged != null) {
            provisionPackagedWorld(container, worldFolder, arena, packaged, settings)
        }

        val creator = WorldCreator.name(arena.world)
            .environment(World.Environment.NORMAL)
            .generateStructures(false)
            .keepSpawnLoaded(TriState.FALSE)
        when {
            builtIn -> creator.seed(TttCitadelBlueprint.SEED).generator(TttCitadelChunkGenerator())
            packaged != null -> creator.generator(EmptyArenaChunkGenerator())
        }
        val world = requireNotNull(creator.createWorld()) { "Could not load arena world ${arena.world}" }
        configure(world, arena, settings)
        requireArenaChunks(world, arena, generate = builtIn && !folderExisted)
        if (builtIn && !folderExisted) {
            Files.writeString(marker, arena.template + "\n", StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)
        }
        if (packaged != null) sanitizeImportedDecorations(world, arena, settings)
        rejectCommandBlocks(world, arena)
        val action = if (folderExisted) "Loaded" else "Provisioned"
        plugin.logger.info("$action ArcEvents arena id=${arena.id} world=${world.name} template=${arena.template}")
        return world
    }

    private fun provisionPackagedWorld(
        container: Path,
        worldFolder: Path,
        arena: ArenaSettings,
        template: PackagedArenaTemplate,
        settings: ArcEventsConfig,
    ) {
        val files = loadPackagedFiles(template)
        when (template.format) {
            PackagedArenaFormat.ANVIL_WORLD -> provisionAnvil(container, worldFolder, arena, template, files, settings)
            PackagedArenaFormat.SPONGE_V2_SCHEMATIC,
            PackagedArenaFormat.SPONGE_V3_SCHEMATIC,
            PackagedArenaFormat.MCEDIT_SCHEMATIC,
            -> provisionSchematic(container, worldFolder, arena, template, files, settings)
        }
    }

    private fun provisionAnvil(
        container: Path,
        worldFolder: Path,
        arena: ArenaSettings,
        template: PackagedArenaTemplate,
        files: Map<PackagedArenaFile, ByteArray>,
        settings: ArcEventsConfig,
    ) {
        val stage = stagingChild(container, arena.world)
        val stageName = stage.fileName.toString()
        var stageWorld: World? = null
        try {
            // Never adopt level.dat from a downloaded world. Paper creates clean
            // metadata with command execution disabled; only the exact reviewed
            // region payload is transplanted after the staging world is unloaded.
            stageWorld = requireNotNull(
                WorldCreator.name(stageName)
                    .environment(World.Environment.NORMAL)
                    .generateStructures(false)
                    .keepSpawnLoaded(TriState.FALSE)
                    .generator(EmptyArenaChunkGenerator())
                    .createWorld(),
            ) { "Could not create isolated Anvil arena staging world" }
            configure(stageWorld, arena, settings)
            stageWorld.save()
            require(plugin.server.unloadWorld(stageWorld, true)) { "Could not unload isolated Anvil arena staging world" }
            stageWorld = null
            clearGeneratedChunkStorage(stage)
            files.forEach { (file, bytes) -> writeNew(stage, file.destination, bytes) }
            writeOwnership(stage, arena, template, files)
            Files.move(stage, worldFolder, StandardCopyOption.ATOMIC_MOVE)
        } catch (failure: Throwable) {
            stageWorld?.let { world -> runCatching { plugin.server.unloadWorld(world, false) }.onFailure(failure::addSuppressed) }
            runCatching { deleteOwnedStage(container, stage) }.onFailure(failure::addSuppressed)
            throw failure
        }
    }

    private fun provisionSchematic(
        container: Path,
        worldFolder: Path,
        arena: ArenaSettings,
        template: PackagedArenaTemplate,
        files: Map<PackagedArenaFile, ByteArray>,
        settings: ArcEventsConfig,
    ) {
        require(plugin.server.pluginManager.isPluginEnabled("WorldEdit")) {
            "WorldEdit is required to provision packaged schematic arenas"
        }
        val stage = stagingChild(container, arena.world)
        val stageName = stage.fileName.toString()
        var stageWorld: World? = null
        try {
            stageWorld = requireNotNull(
                WorldCreator.name(stageName)
                    .environment(World.Environment.NORMAL)
                    .generateStructures(false)
                    .keepSpawnLoaded(TriState.FALSE)
                    .generator(EmptyArenaChunkGenerator())
                    .createWorld(),
            ) { "Could not create isolated arena staging world" }
            configure(stageWorld, arena, settings)
            val source = files.values.single()
            val clipboard = readAndCropSchematic(template, source)
            rejectExecutableClipboard(template, clipboard)
            WorldEdit.getInstance().newEditSession(BukkitAdapter.adapt(stageWorld)).use { editSession ->
                val operation = ClipboardHolder(clipboard)
                    .createPaste(editSession)
                    .to(BlockVector3.at(0, SCHEMATIC_BASE_Y, 0))
                    .ignoreAirBlocks(true)
                    .copyEntities(false)
                    .copyBiomes(false)
                    .build()
                Operations.complete(operation)
            }
            requireArenaChunks(stageWorld, arena, generate = true)
            rejectCommandBlocks(stageWorld, arena)
            stageWorld.save()
            require(plugin.server.unloadWorld(stageWorld, true)) { "Could not unload isolated arena staging world" }
            stageWorld = null
            writeOwnership(stage, arena, template, files)
            Files.move(stage, worldFolder, StandardCopyOption.ATOMIC_MOVE)
        } catch (failure: Throwable) {
            stageWorld?.let { world -> runCatching { plugin.server.unloadWorld(world, false) }.onFailure(failure::addSuppressed) }
            runCatching { deleteOwnedStage(container, stage) }.onFailure(failure::addSuppressed)
            throw failure
        }
    }

    private fun readAndCropSchematic(template: PackagedArenaTemplate, source: ByteArray): Clipboard {
        val format = when (template.format) {
            PackagedArenaFormat.SPONGE_V2_SCHEMATIC -> BuiltInClipboardFormat.SPONGE_V2_SCHEMATIC
            PackagedArenaFormat.SPONGE_V3_SCHEMATIC -> BuiltInClipboardFormat.SPONGE_V3_SCHEMATIC
            PackagedArenaFormat.MCEDIT_SCHEMATIC -> BuiltInClipboardFormat.MCEDIT_SCHEMATIC
            PackagedArenaFormat.ANVIL_WORLD -> error("An Anvil world is not a schematic")
        }
        val original = ByteArrayInputStream(source).use { input -> format.getReader(input).use { it.read() } }
        val dimensions = original.dimensions
        require(dimensions.x() == template.width && dimensions.y() == template.height && dimensions.z() == template.length) {
            "Packaged arena ${template.id} dimensions do not match the reviewed artifact"
        }
        val cropMinimum = requireNotNull(template.cropMinimum)
        val cropMaximum = requireNotNull(template.cropMaximum)
        val originalMinimum = original.region.minimumPoint
        val sourceMinimum = originalMinimum.add(cropMinimum.x, cropMinimum.y, cropMinimum.z)
        val sourceMaximum = originalMinimum.add(cropMaximum.x, cropMaximum.y, cropMaximum.z)
        require(original.region.contains(sourceMinimum) && original.region.contains(sourceMaximum)) {
            "Packaged arena ${template.id} crop is outside the reviewed artifact"
        }
        val destinationMaximum = BlockVector3.at(
            cropMaximum.x - cropMinimum.x,
            cropMaximum.y - cropMinimum.y,
            cropMaximum.z - cropMinimum.z,
        )
        val cropped = BlockArrayClipboard(CuboidRegion(BlockVector3.at(0, 0, 0), destinationMaximum))
        val copy = ForwardExtentCopy(
            original,
            CuboidRegion(sourceMinimum, sourceMaximum),
            sourceMinimum,
            cropped,
            BlockVector3.at(0, 0, 0),
        )
        copy.setCopyingEntities(false)
        copy.setCopyingBiomes(false)
        Operations.complete(copy)
        cropped.origin = BlockVector3.at(0, 0, 0)
        return cropped
    }

    private fun rejectExecutableClipboard(template: PackagedArenaTemplate, clipboard: Clipboard) {
        require(clipboard.entities.isEmpty()) { "Packaged arena ${template.id} contains copied entities" }
        clipboard.region.forEach { position ->
            val block = clipboard.getFullBlock(position)
            @Suppress("DEPRECATION")
            val blockId = block.blockType.id
            require(blockId !in COMMAND_BLOCK_IDS) { "Packaged arena ${template.id} contains a command block" }
            val nbt = block.nbtReference?.value?.toString().orEmpty()
            require(!EXECUTABLE_NBT.containsMatchIn(nbt)) { "Packaged arena ${template.id} contains executable block data" }
        }
    }

    private fun loadPackagedFiles(template: PackagedArenaTemplate): Map<PackagedArenaFile, ByteArray> {
        var total = 0L
        return template.files.associateWith { file ->
            val bytes = when (template.assetSource) {
                PackagedArenaSource.CLASSPATH -> requireNotNull(plugin.getResource(file.resource)) {
                    "Packaged arena resource ${file.resource} is missing"
                }.use { input -> input.readNBytes(MAX_RESOURCE_BYTES + 1) }

                PackagedArenaSource.DATA_FOLDER -> {
                    val dataRoot = plugin.dataFolder.toPath().toAbsolutePath().normalize()
                    val asset = safeChild(dataRoot, file.resource)
                    require(Files.isRegularFile(asset) && !Files.isSymbolicLink(asset)) {
                        "External arena asset ${file.resource} is missing or unsafe"
                    }
                    Files.newInputStream(asset).use { input -> input.readNBytes(MAX_RESOURCE_BYTES + 1) }
                }
            }
            require(bytes.size in 1..MAX_RESOURCE_BYTES) { "Packaged arena resource ${file.resource} is too large" }
            total += bytes.size
            require(total <= MAX_WORLD_BYTES) { "Packaged arena resources exceed the size limit" }
            require(sha256(bytes) == file.sha256) { "Packaged arena resource ${file.resource} failed its checksum" }
            bytes
        }
    }

    private fun writeOwnership(
        stage: Path,
        arena: ArenaSettings,
        template: PackagedArenaTemplate,
        files: Map<PackagedArenaFile, ByteArray>,
    ) {
        Files.writeString(stage.resolve(ARENA_TEMPLATE_MARKER), arena.template + "\n", StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)
        files.forEach { (file, bytes) -> writeNew(stage.resolve(SOURCE_ARTIFACTS), file.destination, bytes) }
        val manifestFiles = JsonArray()
        template.files.forEach { file ->
            manifestFiles.add(JsonObject().apply {
                addProperty("path", file.destination)
                addProperty("sha256", file.sha256)
            })
        }
        val manifest = JsonObject().apply {
            addProperty("format", SOURCE_MANIFEST_FORMAT)
            addProperty("world", arena.world)
            addProperty("template", template.id)
            addProperty("title", template.title)
            addProperty("source", template.source)
            addProperty("source_archive_sha256", template.sourceArchiveSha256)
            addProperty("author", template.author)
            addProperty("license", template.license)
            addProperty("license_url", template.licenseUrl)
            add("files", manifestFiles)
        }
        Files.writeString(
            stage.resolve(SOURCE_MANIFEST),
            manifest.toString() + "\n",
            StandardOpenOption.CREATE_NEW,
            StandardOpenOption.WRITE,
        )
    }

    private fun requireMarker(arena: ArenaSettings, marker: Path) {
        require(Files.isRegularFile(marker) && Files.size(marker) <= 128L && Files.readString(marker).trim() == arena.template) {
            "Refusing to adopt an unmarked arena world ${arena.world}"
        }
    }

    private fun requirePackagedWorld(worldFolder: Path, arena: ArenaSettings, template: PackagedArenaTemplate) {
        require(Files.isRegularFile(worldFolder.resolve("level.dat"))) { "Packaged arena is missing level.dat" }
        val sourceManifest = worldFolder.resolve(SOURCE_MANIFEST)
        require(Files.isRegularFile(sourceManifest) && Files.size(sourceManifest) in 1..MAX_MANIFEST_BYTES) {
            "Packaged arena is missing its bounded source manifest"
        }
        val source = JsonParser.parseString(Files.readString(sourceManifest)).asJsonObject
        require(source.get("format")?.asInt == SOURCE_MANIFEST_FORMAT) { "Packaged arena source manifest format is unsupported" }
        require(source.get("world")?.asString == arena.world && source.get("template")?.asString == template.id) {
            "Packaged arena source identity does not match its configuration"
        }
        require(source.get("source")?.asString == template.source) { "Packaged arena source URL changed" }
        require(source.get("title")?.asString == template.title) { "Packaged arena title changed" }
        require(source.get("source_archive_sha256")?.asString == template.sourceArchiveSha256) {
            "Packaged arena archive checksum changed"
        }
        require(
            source.get("author")?.asString == template.author &&
                source.get("license")?.asString == template.license &&
                source.get("license_url")?.asString == template.licenseUrl
        ) {
            "Packaged arena attribution changed"
        }
        val manifestFiles = source.getAsJsonArray("files")?.associate { entry ->
            val item = entry.asJsonObject
            item.get("path").asString to item.get("sha256").asString
        }.orEmpty()
        require(manifestFiles == template.files.associate { it.destination to it.sha256 }) {
            "Packaged arena file manifest changed"
        }
        template.files.forEach { file ->
            val artifact = safeChild(worldFolder.resolve(SOURCE_ARTIFACTS), file.destination)
            require(Files.isRegularFile(artifact) && Files.size(artifact) in 1..MAX_RESOURCE_BYTES.toLong()) {
                "Packaged arena source artifact ${file.destination} is missing"
            }
            require(sha256(Files.readAllBytes(artifact)) == file.sha256) {
                "Packaged arena source artifact ${file.destination} failed its checksum"
            }
        }
        var fileCount = 0
        var bytes = 0L
        Files.walk(worldFolder).use { paths ->
            paths.forEach { path ->
                require(!Files.isSymbolicLink(path)) { "Packaged arena contains a symbolic link" }
                fileCount++
                require(fileCount <= MAX_WORLD_FILES) { "Packaged arena contains too many files" }
                if (Files.isRegularFile(path)) {
                    bytes += Files.size(path)
                    require(bytes <= MAX_WORLD_BYTES) { "Packaged arena exceeds the size limit" }
                    require(!path.fileName.toString().endsWith(".mcfunction", ignoreCase = true)) {
                        "Packaged arena contains a function file"
                    }
                }
            }
        }
        val datapacks = worldFolder.resolve("datapacks")
        if (Files.isDirectory(datapacks)) {
            Files.walk(datapacks).use { entries ->
                require(entries.asSequence().none(Files::isRegularFile)) { "Packaged arena datapacks must be empty" }
            }
        }
    }

    private fun requireGlobalCommandBlocksDisabled(container: Path) {
        val properties = container.resolve("server.properties")
        if (!Files.isRegularFile(properties)) return
        val enabled = Files.readAllLines(properties)
            .asSequence()
            .map(String::trim)
            .filterNot { it.isEmpty() || it.startsWith('#') }
            .mapNotNull { line -> line.substringAfter('=', "").takeIf { line.substringBefore('=') == "enable-command-block" } }
            .lastOrNull()
            ?.equals("true", ignoreCase = true) == true
        require(!enabled) { "Packaged ArcEvents arenas require enable-command-block=false" }
    }

    private fun requireArenaChunks(world: World, arena: ArenaSettings, generate: Boolean) {
        val chunks = arenaChunks(arena)
        for (chunkX in chunks.x) for (chunkZ in chunks.z) {
            require(world.loadChunk(chunkX, chunkZ, generate)) { "Could not load arena chunk $chunkX,$chunkZ" }
            require(world.isChunkGenerated(chunkX, chunkZ)) { "Arena chunk $chunkX,$chunkZ is missing" }
        }
    }

    private fun rejectCommandBlocks(world: World, arena: ArenaSettings) {
        val chunks = arenaChunks(arena)
        for (chunkX in chunks.x) for (chunkZ in chunks.z) {
            val unsafe = world.getChunkAt(chunkX, chunkZ).tileEntities.firstOrNull { it.type in COMMAND_BLOCKS }
            require(unsafe == null) { "Arena ${arena.id} contains a command block at ${unsafe?.location}" }
        }
    }

    private fun sanitizeImportedDecorations(world: World, arena: ArenaSettings, settings: ArcEventsConfig) {
        val chunks = arenaChunks(arena)
        val removed = linkedMapOf<String, Int>()
        val importedDisplays = mutableListOf<Display>()
        var removedBanners = 0
        for (chunkX in chunks.x) for (chunkZ in chunks.z) {
            val chunk = world.getChunkAt(chunkX, chunkZ)
            chunk.entities.filterNot { it is Player }.forEach { entity ->
                if (entity is Display && settings.arenaRuntime.preserveImportedDisplays) {
                    importedDisplays += entity
                    return@forEach
                }
                val type = entity.type.key.asString()
                entity.remove()
                removed[type] = removed.getOrDefault(type, 0) + 1
            }
            chunk.tileEntities.filter { isImportedBlockDecoration(it.type) }.forEach { banner ->
                banner.block.setType(Material.AIR, false)
                removedBanners++
            }
        }
        importedDisplays
            .sortedWith(compareBy<Display>(
                { it.location.distanceSquared(requireNotNull(arena.lobby).let { lobby -> org.bukkit.Location(world, lobby.x, lobby.y, lobby.z) }) },
                { it.uniqueId.toString() },
            ))
            .drop(settings.arenaRuntime.maxImportedDisplays)
            .forEach { display ->
                val type = display.type.key.asString()
                display.remove()
                removed[type] = removed.getOrDefault(type, 0) + 1
            }
        if (removed.isNotEmpty()) {
            val summary = removed.entries.sortedByDescending(Map.Entry<String, Int>::value)
                .joinToString { (type, count) -> "$type=$count" }
            plugin.logger.info("Removed imported arena entities id=${arena.id} world=${world.name} entities={$summary}")
        }
        if (removedBanners > 0) plugin.logger.info(
            "Removed imported arena banners id=${arena.id} world=${world.name} banners=$removedBanners",
        )
        val keptDisplays = minOf(importedDisplays.size, settings.arenaRuntime.maxImportedDisplays)
        if (keptDisplays > 0) plugin.logger.info(
            "Preserved imported arena displays id=${arena.id} world=${world.name} displays=$keptDisplays",
        )
    }

    /** Applies only reversible world performance knobs during `/events reload`. */
    fun applyRuntimeTuning(settings: ArcEventsConfig) {
        settings.arenas.filter(ArenaSettings::enabled).forEach { arena ->
            val world = requireNotNull(plugin.server.getWorld(arena.world)) { "Arena world ${arena.world} is not loaded" }
            applyRuntimeTuning(world, settings)
        }
    }

    private fun configure(world: World, arena: ArenaSettings, settings: ArcEventsConfig) {
        val bounds = requireNotNull(arena.bounds)
        val lobby = requireNotNull(arena.lobby)
        applyRuntimeTuning(world, settings)
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

    private fun applyRuntimeTuning(world: World, settings: ArcEventsConfig) {
        world.viewDistance = settings.arenaRuntime.viewDistance
        world.sendViewDistance = settings.arenaRuntime.viewDistance
        world.simulationDistance = settings.arenaRuntime.simulationDistance
    }

    private fun arenaChunks(arena: ArenaSettings): ArenaChunks {
        val bounds = requireNotNull(arena.bounds)
        val x = floor(bounds.minimum.x).toInt().floorDiv(16)..ceil(bounds.maximum.x).toInt().floorDiv(16)
        val z = floor(bounds.minimum.z).toInt().floorDiv(16)..ceil(bounds.maximum.z).toInt().floorDiv(16)
        val count = x.count().toLong() * z.count().toLong()
        require(count in 1..MAX_ARENA_CHUNKS) { "Arena ${arena.id} spans $count chunks; limit is $MAX_ARENA_CHUNKS" }
        return ArenaChunks(x, z)
    }

    private fun directChild(parent: Path, child: String): Path {
        val resolved = parent.resolve(child).normalize()
        require(resolved.parent == parent) { "Arena world must be a direct child of the world container" }
        return resolved
    }

    private fun stagingChild(container: Path, world: String): Path = directChild(
        container,
        "${STAGE_PREFIX}${world.take(24)}_${UUID.randomUUID().toString().replace("-", "").take(12)}",
    )

    private fun safeChild(parent: Path, relative: String): Path {
        val candidate = Path.of(relative)
        require(!candidate.isAbsolute && candidate.none { it.toString() == ".." }) { "Invalid packaged arena path" }
        val resolved = parent.resolve(candidate).normalize()
        require(resolved.startsWith(parent.normalize())) { "Packaged arena path escapes its owner" }
        return resolved
    }

    private fun writeNew(parent: Path, relative: String, bytes: ByteArray) {
        val target = safeChild(parent, relative)
        Files.createDirectories(requireNotNull(target.parent))
        Files.write(target, bytes, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)
    }

    private fun clearGeneratedChunkStorage(stage: Path) {
        require(stage.fileName.toString().startsWith(STAGE_PREFIX)) {
            "Refusing to clear a world outside the ArcEvents staging namespace"
        }
        GENERATED_CHUNK_DIRECTORIES.forEach { name ->
            val directory = safeChild(stage, name)
            if (Files.exists(directory)) {
                Files.walk(directory).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
            }
        }
    }

    private fun deleteOwnedStage(container: Path, stage: Path) {
        require(stage.parent == container && stage.fileName.toString().startsWith(STAGE_PREFIX)) {
            "Refusing to remove a path outside the ArcEvents staging namespace"
        }
        if (!Files.exists(stage)) return
        Files.walk(stage).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { byte -> "%02x".format(byte) }

    private data class ArenaChunks(val x: IntRange, val z: IntRange)

    companion object {
        private const val SOURCE_MANIFEST = ".arcevents-source.json"
        private const val SOURCE_ARTIFACTS = ".arcevents-source"
        private const val SOURCE_MANIFEST_FORMAT = 2
        private const val STAGE_PREFIX = ".arcevents-stage-"
        private const val SCHEMATIC_BASE_Y = 64
        private const val MAX_MANIFEST_BYTES = 16_384L
        private const val MAX_RESOURCE_BYTES = 64 * 1024 * 1024
        private const val MAX_WORLD_FILES = 20_000
        private const val MAX_WORLD_BYTES = 2_147_483_648L
        private const val MAX_ARENA_CHUNKS = 1_024L
        private val GENERATED_CHUNK_DIRECTORIES = setOf("region", "entities", "poi")
        private val EXECUTABLE_NBT = Regex("(?i)(run_command|suggest_command|minecraft:(?:command_block|chain_command_block|repeating_command_block)|(?:^|[,{ ])command[=:])")
        private val COMMAND_BLOCK_IDS = setOf(
            "minecraft:command_block",
            "minecraft:chain_command_block",
            "minecraft:repeating_command_block",
        )
        private val COMMAND_BLOCKS = setOf(Material.COMMAND_BLOCK, Material.CHAIN_COMMAND_BLOCK, Material.REPEATING_COMMAND_BLOCK)
    }
}

internal fun isImportedBlockDecoration(material: Material): Boolean = material.name.endsWith("_BANNER")
