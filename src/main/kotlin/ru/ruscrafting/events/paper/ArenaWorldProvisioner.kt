package ru.ruscrafting.events.paper

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
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

class ArenaWorldProvisioner(private val plugin: Plugin,
    private val tickets: ru.arc.paper.chunk.PaperChunkTicketRegistry = ru.arc.paper.chunk.PaperChunkTicketRegistry(plugin),
) : AutoCloseable {
    private val container = plugin.server.worldContainer.toPath().toAbsolutePath().normalize()
    private val staging = ArenaStagingFiles(container)
    private val libraryRoot = plugin.dataFolder.toPath().resolve("data/prepared-arena-sources").toAbsolutePath().normalize()
    private var leases = linkedSetOf<ru.arc.paper.chunk.PaperChunkTicketLease>()
    private var leaseReleaseFailure: Throwable? = null
    private var work: EventWork? = null
    var closeCompletion = java.util.concurrent.CompletableFuture.completedFuture(Unit)
        private set
    private var library: ru.arc.paper.api.ArcSchematicLibrary? = null
    private var stageWorld: World? = null
    private val readiness = mutableMapOf<String, MutableMap<Pair<Int, Int>, ru.arc.paper.chunk.PaperChunkTicketLease>>()
    private var initialPoints: Map<String, List<ru.ruscrafting.events.config.EventLocation>> = emptyMap()
    private var closed = false

    private data class PreparedArena(
        val arena: ArenaSettings, val folder: Path, val existed: Boolean, val existingWorldId: UUID?,
        val builtIn: Boolean, val packaged: PackagedArenaTemplate?, val imported: ReviewedImportedArenaTemplate?,
        val files: Map<PackagedArenaFile, ByteArray>, val chunks: List<Pair<Int, Int>>,
    )

    /** Worker filesystem preparation, then budgeted Paper activation. Arenas retain their established order. */
    fun ensureLoadedAsync(settings: ArcEventsConfig, work: EventWork, excludedArenaId: String? = null, mandatoryPoints: (ArenaSettings) -> List<ru.ruscrafting.events.config.EventLocation> = { emptyList() }): java.util.concurrent.CompletableFuture<List<World>> {
        check(this.work == null && !closed)
        this.work = work
        val enabled = settings.arenas.filter { it.enabled && it.id != excludedArenaId }
        require(enabled.isEmpty() || settings.nodeMode == NodeMode.HOST) { "Only a HOST node may provision an arena world" }
        initialPoints = enabled.associate { it.id to mandatoryPoints(it) }
        val existing = enabled.associate { it.world to plugin.server.getWorld(it.world)?.uid }
        val loadedWorldNames = plugin.server.worlds.mapTo(hashSetOf()) { it.name }
        if (enabled.any { PackagedArenaTemplates.find(it.template)?.format != PackagedArenaFormat.ANVIL_WORLD && PackagedArenaTemplates.find(it.template) != null }) {
            library = requireNotNull(plugin.server.servicesManager.load(ru.arc.paper.api.ArcSchematicService::class.java)) {
                "ARC shared schematic service is required to provision packaged schematic arenas"
            }.open(plugin.name, libraryRoot)
        }
        return work.compute {
            val recovery = staging.recover(loadedWorldNames)
            if (recovery.removed > 0) plugin.logger.info("Removed ${recovery.removed} interrupted owned arena stages")
            if (recovery.retainedLegacy.isNotEmpty()) plugin.logger.warning(
                "Retained unmarked legacy arena staging folders for inspection: ${recovery.retainedLegacy.joinToString()}")
            enabled.map { prepare(it, existing[it.world]) }
        }.thenCompose { prepared ->
            var chain = java.util.concurrent.CompletableFuture.completedFuture(mutableListOf<World>())
            prepared.forEach { arena -> chain = chain.thenCompose { worlds -> activateAsync(settings, arena, work).thenApply { worlds.apply { add(it) } } } }
            chain.thenApply { it.toList() }
        }
    }

    private fun prepare(arena: ArenaSettings, existing: UUID?): PreparedArena {
        val folder = directChild(container, arena.world)
        require(!Files.isSymbolicLink(folder)) { "Arena world directory must not be a symbolic link" }
        val existed = Files.exists(folder)
        require(!existed || Files.isDirectory(folder)) { "Arena path must be a directory" }
        val builtIn = arena.template in setOf(TttCitadelBlueprint.TEMPLATE, DisasterArenaGenerator.TEMPLATE, FishingArenaGenerator.TEMPLATE)
        val packaged = PackagedArenaTemplates.find(arena.template)
        val imported = ReviewedImportedArenaTemplates.find(arena.template)
        require(arena.template.isEmpty() || builtIn || packaged != null || imported != null) { "Arena template ${arena.template} is not reviewed" }
        if (packaged != null || imported != null) requireGlobalCommandBlocksDisabled(container)
        if (arena.template.isNotEmpty() && (existing != null || existed)) {
            requireMarker(arena, folder.resolve(ARENA_TEMPLATE_MARKER))
            if (packaged != null) requirePackagedWorld(folder, arena, packaged)
            if (imported != null) requireReviewedImportedWorld(folder, arena, imported)
        }
        if (imported != null && !existed) error("Reviewed imported arena world ${arena.world} is missing")
        val files = if (!existed && packaged != null) loadPackagedFiles(packaged) else emptyMap()
        val range = arenaChunks(arena)
        return PreparedArena(arena, folder, existed, existing, builtIn, packaged, imported, files,
            range.x.flatMap { x -> range.z.map { z -> x to z } })
    }

    private fun activateAsync(settings: ArcEventsConfig, prepared: PreparedArena, work: EventWork): java.util.concurrent.CompletableFuture<World> {
        val arena = prepared.arena
        val provision = if (!prepared.existed && prepared.packaged != null) provisionAsync(settings, prepared, work)
            else java.util.concurrent.CompletableFuture.completedFuture(Unit)
        val owned = mutableListOf<ru.arc.paper.chunk.PaperChunkTicketLease>()
        return provision.thenCompose { work.callNative {
            check(!closed)
            val existing = plugin.server.getWorld(arena.world)
            check(existing?.uid == prepared.existingWorldId) { "Arena world changed during preparation" }
            val world = existing ?: if (arena.template.isEmpty()) error("Custom arena world ${arena.world} is not loaded") else {
                val creator = WorldCreator.name(arena.world).environment(World.Environment.NORMAL).generateStructures(false).keepSpawnLoaded(TriState.FALSE)
                when {
                    arena.template == FishingArenaGenerator.TEMPLATE -> creator.seed(FishingArenaGenerator.SEED).generator(FishingArenaGenerator())
                    arena.template == DisasterArenaGenerator.TEMPLATE -> creator.seed(73491L).generator(DisasterArenaGenerator())
                    prepared.builtIn -> creator.seed(TttCitadelBlueprint.SEED).generator(TttCitadelChunkGenerator())
                    else -> creator.generator(EmptyArenaChunkGenerator())
                }
                requireNotNull(creator.createWorld()) { "Could not load arena world ${arena.world}" }
            }
            configure(world, arena, settings)
            world
        } }.thenCompose { world ->
            loadChunks(world, prepared.chunks, prepared.builtIn && !prepared.existed, owned, work)
                .thenCompose { sanitizeAndValidate(world, arena, settings, prepared.packaged != null || prepared.imported != null, prepared.chunks, work) }
                .thenCompose { if (prepared.builtIn && !prepared.existed) work.compute {
                    Files.writeString(prepared.folder.resolve(ARENA_TEMPLATE_MARKER), arena.template + "\n", StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)
                    Unit
                } else java.util.concurrent.CompletableFuture.completedFuture(Unit) }
                .thenCompose { work.onMain {
                    check(!closed && plugin.server.getWorld(world.uid) === world)
                    // Keep only the small set used by synchronous readiness checks. Loot acquires its own chunks asynchronously.
                    val keep = (listOfNotNull(arena.lobby, arena.spectator, arena.playerSpawn) + initialPoints[arena.id].orEmpty()).mapTo(mutableSetOf()) { (floor(it.x).toInt() shr 4) to (floor(it.z).toInt() shr 4) }
                    val retained = readiness.getOrPut(arena.id) { mutableMapOf() }
                    owned.filter { (it.key.x to it.key.z) in keep }.forEach { lease ->
                        retained[lease.key.x to lease.key.z] = lease
                        owned.remove(lease)
                    }
                    plugin.logger.info("${if (prepared.existed) "Loaded" else "Provisioned"} ArcEvents arena id=${arena.id} world=${world.name} template=${arena.template}")
                    world
                } }
        }.handle { world, failure -> world to failure }.thenCompose { (world, failure) -> releaseChunkLeases(owned, work).handle { _, releaseFailure ->
            if (failure != null) { releaseFailure?.let(failure::addSuppressed); throw failure }
            releaseFailure?.let { throw it }
            world
        } }
    }

    /** Called after a durable point edit, while that arena's edit admission is still held. */
    fun refreshReadinessAsync(arena: ArenaSettings, points: List<ru.ruscrafting.events.config.EventLocation>): java.util.concurrent.CompletableFuture<Unit> {
        val work = requireNotNull(work)
        val world = requireNotNull(plugin.server.getWorld(arena.world))
        val keep = (listOfNotNull(arena.lobby, arena.spectator, arena.playerSpawn) + points)
            .mapTo(linkedSetOf()) { (floor(it.x).toInt() shr 4) to (floor(it.z).toInt() shr 4) }
        val retained = readiness.getOrPut(arena.id) { mutableMapOf() }
        val acquired = mutableListOf<ru.arc.paper.chunk.PaperChunkTicketLease>()
        return loadChunks(world, keep.filterNot(retained::containsKey), false, acquired, work).thenCompose { work.onMain {
            acquired.forEach { retained[it.key.x to it.key.z] = it }
            acquired.clear()
            val obsolete = retained.keys.filterNot(keep::contains)
            obsolete.forEach { acquired += requireNotNull(retained.remove(it)) }
        } }.thenCompose { releaseChunkLeases(acquired, work) }
    }

    private fun loadChunks(world: World, chunks: List<Pair<Int, Int>>, generate: Boolean,
        owned: MutableList<ru.arc.paper.chunk.PaperChunkTicketLease>, work: EventWork): java.util.concurrent.CompletableFuture<Unit> {
        var chain = java.util.concurrent.CompletableFuture.completedFuture(Unit)
        chunks.forEach { (x, z) -> chain = chain.thenCompose { work.callNative {
            check(!closed && plugin.server.getWorld(world.uid) === world)
            world.getChunkAtAsync(x, z, generate)
        }.thenCompose { it }.thenCompose { chunk -> work.callNative {
            check(!closed && plugin.server.getWorld(world.uid) === world && chunk != null && world.isChunkLoaded(x, z)) { "Could not load arena chunk $x,$z" }
            val acquired = tickets.acquire(chunk)
            val lease = (acquired as? ru.arc.paper.chunk.PaperChunkTicketAcquireResult.Acquired)?.lease
                ?: error("Could not retain arena chunk $x,$z: $acquired")
            owned += lease
            leases += lease
            Unit
        } } } }
        return chain
    }

    internal fun releaseChunkLeases(owned: MutableList<ru.arc.paper.chunk.PaperChunkTicketLease>, work: EventWork): java.util.concurrent.CompletableFuture<Unit> = work.onMain {
        val iterator = owned.iterator()
        var failure: Throwable? = null
        work.capture({ !closed }) {
            if (iterator.hasNext()) {
                val lease = iterator.next()
                try {
                    lease.close()
                    leases.remove(lease)
                    iterator.remove()
                } catch (problem: Throwable) {
                    if (failure == null) failure = problem else failure.addSuppressed(problem)
                    // Core leases close idempotently. A second close cannot prove that a
                    // failed native ticket removal succeeded, so retain its failed drain.
                    if (leaseReleaseFailure == null) leaseReleaseFailure = problem
                }
            }
            val done = !iterator.hasNext()
            if (done) failure?.let { throw it }
            done
        }
    }.thenCompose { it }

    private fun provisionAsync(settings: ArcEventsConfig, prepared: PreparedArena, work: EventWork): java.util.concurrent.CompletableFuture<Unit> {
        val arena = prepared.arena
        val template = requireNotNull(prepared.packaged)
        return work.compute { staging.create(arena.world, arena.template) }.thenCompose { stage ->
        val owned = mutableListOf<ru.arc.paper.chunk.PaperChunkTicketLease>()
        var unloaded = false
        work.callNative {
            check(!closed)
            requireNotNull(WorldCreator.name(stage.fileName.toString()).environment(World.Environment.NORMAL)
                .generateStructures(false).keepSpawnLoaded(TriState.FALSE).generator(EmptyArenaChunkGenerator()).createWorld()) {
                "Could not create isolated arena staging world"
            }.also { world -> stageWorld = world; configure(world, arena, settings) }
        }.thenCompose { world ->
            val content = if (template.format == PackagedArenaFormat.ANVIL_WORLD) java.util.concurrent.CompletableFuture.completedFuture(Unit)
                else preparedSchematic(template, prepared.files, work).thenCompose { (snapshot, anchor) ->
                    val pasteChunks = (0..((snapshot.sizeX - 1) shr 4)).flatMap { x ->
                        (0..((snapshot.sizeZ - 1) shr 4)).map { z -> x to z }
                    }
                    loadChunks(world, (prepared.chunks + pasteChunks).distinct(), true, owned, work).thenCompose { work.onMain {
                        check(!closed)
                        requireNotNull(library).paste(snapshot, ru.arc.paper.api.ArcSchematicPasteTarget(world.uid, anchor),
                            ru.arc.paper.api.ArcSchematicPasteOptions(ignoreAir = true, ignoreBarriers = false, repairConnections = false)).completion
                    }.thenCompose { it } }.thenApply { result -> check(!result.cancelled) { "Arena paste was cancelled" }; Unit }
                }
            content.thenCompose { if (template.format == PackagedArenaFormat.ANVIL_WORLD) java.util.concurrent.CompletableFuture.completedFuture(Unit)
                else sanitizeAndValidate(world, arena, settings, false, prepared.chunks, work) }
                .thenCompose { releaseChunkLeases(owned, work) }.thenCompose { work.callNative {
                    check(!closed)
                    world.save()
                    check(plugin.server.unloadWorld(world, true)) { "Could not unload isolated arena staging world" }
                    stageWorld = null
                    unloaded = true
                } }
        }.thenCompose { work.compute {
            if (template.format == PackagedArenaFormat.ANVIL_WORLD) {
                clearGeneratedChunkStorage(stage)
                prepared.files.forEach { (file, bytes) -> writeNew(stage, file.destination, bytes) }
            }
            writeOwnership(stage, arena, template, prepared.files)
            Files.move(stage, prepared.folder, StandardCopyOption.ATOMIC_MOVE)
            Files.deleteIfExists(prepared.folder.resolve(ArenaStagingFiles.INTENT))
            Unit
        } }.handle { _, failure -> failure }.thenCompose { failure ->
            if (failure == null) java.util.concurrent.CompletableFuture.completedFuture(Unit) else {
                releaseChunkLeases(owned, work).handle { _, releaseFailure -> releaseFailure?.let(failure::addSuppressed); Unit }
                    .thenCompose { work.callNative {
                        stageWorld?.let { world -> if (world.name == stage.fileName.toString()) {
                            unloaded = plugin.server.unloadWorld(world, false)
                            if (unloaded) stageWorld = null
                            else throw IllegalStateException("Could not unload failed arena staging world")
                        } }
                    } }.thenCompose { work.compute {
                        // A loaded or retired stage keeps its validated intent for the next startup.
                        if (unloaded && Files.exists(stage)) staging.delete(stage)
                    } }.handle { _, cleanupFailure ->
                        cleanupFailure?.let(failure::addSuppressed)
                        throw failure
                    }
            }
        }
        }
    }

    private fun preparedSchematic(template: PackagedArenaTemplate, files: Map<PackagedArenaFile, ByteArray>, work: EventWork): java.util.concurrent.CompletableFuture<Pair<ru.arc.paper.api.ArcSchematicSnapshot, ru.arc.paper.api.ArcSchematicPoint>> {
        val owner = requireNotNull(library)
        val source = files.entries.single()
        val id = "${template.id}/${source.key.destination}"
        return work.compute {
            require(!Files.isSymbolicLink(libraryRoot)) { "Prepared arena source root must not be a symbolic link" }
            val target = safeChild(libraryRoot, id)
            Files.createDirectories(requireNotNull(target.parent))
            require(!Files.isSymbolicLink(target.parent) && !Files.isSymbolicLink(target)) { "Prepared arena source must not be a symbolic link" }
            if (Files.exists(target)) require(sha256(Files.readAllBytes(target)) == source.key.sha256) { "Prepared arena source changed" }
            else Files.write(target, source.value, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)
        }.thenCompose { owner.loadVerified(id, source.key.sha256, ru.arc.paper.api.ArcSchematicContentPolicy.IGNORE_ENTITIES_AND_BIOMES) }
            .thenCompose { snapshot ->
                require(snapshot.sizeX == template.width && snapshot.sizeY == template.height && snapshot.sizeZ == template.length) { "Packaged arena ${template.id} dimensions do not match the reviewed artifact" }
                val min = requireNotNull(template.cropMinimum)
                val max = requireNotNull(template.cropMaximum)
                owner.crop(snapshot,
                    ru.arc.paper.api.ArcSchematicPoint(snapshot.minimum.x + min.x, snapshot.minimum.y + min.y, snapshot.minimum.z + min.z),
                    ru.arc.paper.api.ArcSchematicPoint(snapshot.minimum.x + max.x, snapshot.minimum.y + max.y, snapshot.minimum.z + max.z))
            }.thenCompose { cropped ->
                owner.inspectBlockEntityNbt(cropped) { _, nbt ->
                    require(!EXECUTABLE_NBT.containsMatchIn(nbt)) { "Packaged arena ${template.id} contains executable block data" }
                }.thenCompose { work.compute {
                    cropped.blocks().forEach { block -> require(block.blockData.substringBefore('[') !in COMMAND_BLOCK_IDS) { "Packaged arena ${template.id} contains a command block" } }
                    cropped to ru.arc.paper.api.ArcSchematicPoint(cropped.origin.x - cropped.minimum.x,
                        SCHEMATIC_BASE_Y + cropped.origin.y - cropped.minimum.y, cropped.origin.z - cropped.minimum.z)
                } }
            }
    }

    private fun sanitizeAndValidate(world: World, arena: ArenaSettings, settings: ArcEventsConfig, sanitize: Boolean,
        chunks: List<Pair<Int, Int>>, work: EventWork): java.util.concurrent.CompletableFuture<Unit> = work.onMain {
        val chunkIterator = chunks.iterator()
        var entities: Iterator<org.bukkit.entity.Entity>? = null
        var tiles: Iterator<org.bukkit.block.BlockState>? = null
        var currentChunk: org.bukkit.Chunk? = null
        val displays = mutableListOf<Triple<UUID, Double, String>>()
        val removed = linkedMapOf<String, Int>()
        var banners = 0
        fun remove(entity: org.bukkit.entity.Entity) {
            val type = entity.type.key.asString()
            entity.remove()
            removed[type] = removed.getOrDefault(type, 0) + 1
        }
        val lobby = requireNotNull(arena.lobby)
        work.capture({ !closed && plugin.server.getWorld(world.uid) === world }) {
            when {
                entities?.hasNext() == true -> {
                    val entity = requireNotNull(entities).next()
                    if (sanitize && entity !is Player) {
                        if (entity is Display && settings.arenaRuntime.preserveImportedDisplays) {
                            val at = entity.location
                            val distance = (at.x - lobby.x) * (at.x - lobby.x) + (at.y - lobby.y) * (at.y - lobby.y) + (at.z - lobby.z) * (at.z - lobby.z)
                            displays += Triple(entity.uniqueId, distance, entity.type.key.asString())
                        } else remove(entity)
                    }
                }
                tiles?.hasNext() == true -> {
                    val block = requireNotNull(tiles).next()
                    require(block.type !in COMMAND_BLOCKS) { "Arena ${arena.id} contains a command block at ${block.location}" }
                    if (sanitize && isImportedBlockDecoration(block.type)) { block.block.setType(Material.AIR, false); banners++ }
                }
                currentChunk != null -> { tiles = requireNotNull(currentChunk).tileEntities.iterator(); currentChunk = null }
                chunkIterator.hasNext() -> {
                    val (x, z) = chunkIterator.next()
                    check(world.isChunkLoaded(x, z)) { "Arena chunk unloaded during validation" }
                    currentChunk = world.getChunkAt(x, z)
                    entities = if (sanitize) requireNotNull(currentChunk).entities.iterator() else emptyList<org.bukkit.entity.Entity>().iterator()
                    tiles = null
                }
                else -> Unit
            }
            !chunkIterator.hasNext() && currentChunk == null && entities?.hasNext() != true && tiles?.hasNext() != true
        }.thenCompose { work.compute {
            displays.sortedWith(compareBy<Triple<UUID, Double, String>>({ it.second }, { it.first.toString() }))
                .drop(settings.arenaRuntime.maxImportedDisplays).iterator()
        } }.thenCompose { excess -> work.onMain {
            work.capture({ !closed && plugin.server.getWorld(world.uid) === world }) {
                if (excess.hasNext()) {
                    val (id, _, _) = excess.next()
                    plugin.server.getEntity(id)?.takeIf { it.world === world }?.let(::remove)
                }
                !excess.hasNext()
            }
        }.thenCompose { it } }.thenCompose { work.compute {
            val summary = removed.entries.sortedByDescending(Map.Entry<String, Int>::value).joinToString { (type, count) -> "$type=$count" }
            Triple(summary, banners, minOf(displays.size, settings.arenaRuntime.maxImportedDisplays))
        } }.thenCompose { (summary, count, kept) -> work.onMain {
            if (summary.isNotEmpty()) plugin.logger.info("Removed imported arena entities id=${arena.id} world=${world.name} entities={$summary}")
            if (count > 0) plugin.logger.info("Removed imported arena banners id=${arena.id} world=${world.name} banners=$count")
            if (kept > 0) plugin.logger.info("Preserved imported arena displays id=${arena.id} world=${world.name} displays=$kept")
        } }
    }.thenCompose { it }

    override fun close() {
        if (closed) return
        closed = true
        var failure: Throwable? = leaseReleaseFailure
        fun attempt(action: () -> Unit) { runCatching(action).onFailure { problem ->
            if (failure == null) failure = problem else failure.addSuppressed(problem)
        } }
        attempt { library?.close() }
        val detachedStage = stageWorld
        val server = plugin.server
        var stageReleased = detachedStage == null
        stageWorld = null
        val detached = leases
        leases = linkedSetOf()
        val iterator = detached.iterator()
        val cleanup = if (iterator.hasNext() || !stageReleased) requireNotNull(work).cleanupNative {
            if (iterator.hasNext()) {
                val lease = iterator.next()
                attempt(lease::close)
                iterator.remove()
            } else if (!stageReleased) {
                stageReleased = true
                attempt { check(server.unloadWorld(requireNotNull(detachedStage), false)) {
                    "Owned arena stage remains loaded; its folder is retained"
                } }
            }
            if (!iterator.hasNext() && stageReleased) {
                failure?.let { throw it }
                true
            } else false
        } else java.util.concurrent.CompletableFuture.completedFuture(Unit)
        closeCompletion = cleanup.handle { _, cleanupFailure ->
            if (cleanupFailure != null) throw cleanupFailure
            failure?.let { throw it }
            Unit
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

    private fun requireReviewedImportedWorld(
        worldFolder: Path,
        arena: ArenaSettings,
        template: ReviewedImportedArenaTemplate,
    ) {
        require(Files.isRegularFile(worldFolder.resolve("level.dat"))) { "Imported arena is missing level.dat" }
        val sourceManifest = worldFolder.resolve(SOURCE_MANIFEST)
        require(Files.isRegularFile(sourceManifest) && Files.size(sourceManifest) in 1..MAX_MANIFEST_BYTES) {
            "Imported arena is missing its bounded source manifest"
        }
        val source = JsonParser.parseString(Files.readString(sourceManifest)).asJsonObject
        require(source.get("format")?.asInt == IMPORTED_SOURCE_MANIFEST_FORMAT) {
            "Imported arena source manifest format is unsupported"
        }
        require(source.get("world")?.asString == arena.world && source.get("template")?.asString == template.id) {
            "Imported arena source identity does not match its configuration"
        }
        require(source.get("source")?.asString == template.source) { "Imported arena source URL changed" }
        require(source.get("source_archive_sha256")?.asString == template.sourceArchiveSha256) {
            "Imported arena archive checksum changed"
        }
        val sanitized = requireNotNull(source.getAsJsonObject("sanitized")) {
            "Imported arena sanitizer record is missing"
        }
        require(sanitized.get("command_block_entities_removed")?.asInt?.let { it >= 0 } == true) {
            "Imported arena sanitizer record is invalid"
        }
        auditImportedWorldFiles(worldFolder, "Imported arena")
    }

    private fun auditImportedWorldFiles(worldFolder: Path, label: String) {
        var fileCount = 0
        var bytes = 0L
        Files.walk(worldFolder).use { paths ->
            paths.forEach { path ->
                require(!Files.isSymbolicLink(path)) { "$label contains a symbolic link" }
                fileCount++
                require(fileCount <= MAX_WORLD_FILES) { "$label contains too many files" }
                if (Files.isRegularFile(path)) {
                    bytes += Files.size(path)
                    require(bytes <= MAX_WORLD_BYTES) { "$label exceeds the size limit" }
                    require(!path.fileName.toString().endsWith(".mcfunction", ignoreCase = true)) {
                        "$label contains a function file"
                    }
                }
            }
        }
        val datapacks = worldFolder.resolve("datapacks")
        if (Files.isDirectory(datapacks)) {
            Files.walk(datapacks).use { entries ->
                require(entries.asSequence().none(Files::isRegularFile)) { "$label datapacks must be empty" }
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
        require(!enabled) { "Reviewed ArcEvents arenas require enable-command-block=false" }
    }

    /** Applies only reversible world performance knobs during `/arcevents reload`. */
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

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { byte -> "%02x".format(byte) }

    private data class ArenaChunks(val x: IntRange, val z: IntRange)

    companion object {
        private const val SOURCE_MANIFEST = ".arcevents-source.json"
        private const val SOURCE_ARTIFACTS = ".arcevents-source"
        private const val SOURCE_MANIFEST_FORMAT = 2
        private const val IMPORTED_SOURCE_MANIFEST_FORMAT = 1
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
