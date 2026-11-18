package ru.ruscrafting.events.paper.apocalypse

import ru.arc.core.BoundedAsyncExecutor
import ru.ruscrafting.events.domain.apocalypse.ApocalypseLayout
import ru.ruscrafting.events.domain.apocalypse.ApocalypseOriginalLayout
import ru.ruscrafting.events.domain.apocalypse.source.ApocalypseSourceArchive
import ru.ruscrafting.events.domain.apocalypse.source.ApocalypseStructureAssets
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import javax.net.ssl.HttpsURLConnection

/** Original archives are data sources only. No classes, scripts or Forge runtime are loaded. */
internal class ApocalypseContent(
    private val pluginRoot: Path,
    private val sources: List<ApocalypseSourceArchive> = SOURCES,
) : AutoCloseable {
    private val worker = BoundedAsyncExecutor("arc-events-original-content", 1, 16, true)
    // Only the shared catalog fetch uses this executor. Interrupting its virtual thread closes a blocked TLS socket.
    private val downloads = Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("arc-events-original-download").factory())
    private var catalog: CompletableFuture<ApocalypseStructureAssets>? = null
    private val layouts = mutableMapOf<Long, CompletableFuture<ApocalypseLayout>>()

    @Synchronized
    fun layout(seed: Long): CompletableFuture<ApocalypseLayout> = layouts.getOrPut(seed) {
        require(layouts.size < 16) { "Too many original apocalypse generation seeds" }
        val assets = catalog ?: worker.submit {
            val files = downloads.submit<List<Path>> {
                sources.map { source ->
                    if (downloads.isShutdown) throw CancellationException("Original archive download stopped")
                    ensureArchive(pluginRoot.resolve("content/apocalypse-original"), source)
                }
            }.get()
            ApocalypseStructureAssets.load(files[0], files[1], files[2], files.getOrNull(3), files.drop(4)).also { assets ->
                // BlockData parsing reads the native immutable block registry, as it does on Paper generation workers.
                val states = assets.templates.values.flatMap { it.palette }.toSet() + assets.city.sourceStates
                for (rotation in 0..3) for (state in states) {
                    ru.ruscrafting.events.domain.apocalypse.source.ApocalypseSourcePalette.vanillaState(state, rotation)
                        ?.let(org.bukkit.Bukkit::createBlockData)
                }
                ApocalypseLootItems.validateCatalog(assets.loot)
            }
        }.also { catalog = it }
        assets.thenCompose { source -> worker.submit<ApocalypseLayout> { ApocalypseOriginalLayout(seed, source) } }
    }

    fun closeAsync(): CompletableFuture<Unit> {
        downloads.shutdownNow()
        return worker.closeAsync()
    }

    override fun close() { closeAsync() }

    companion object {
        val SOURCES = ApocalypseStructureAssets.pinnedSources

        internal fun ensureArchive(directory: Path, source: ApocalypseSourceArchive): Path {
            require(source.maximumBytes in 1..350L * 1024 * 1024)
            require(!Files.isSymbolicLink(directory) && !Files.isSymbolicLink(directory.parent))
            Files.createDirectories(directory)
            val target = directory.resolve(source.filename)
            require(!Files.isSymbolicLink(target))
            if (Files.exists(target)) { verify(target, source); return target }
            val temporary = Files.createTempFile(directory, ".source-", ".download")
            try {
                val connection = URI(source.url).toURL().openConnection() as HttpsURLConnection
                connection.connectTimeout = 8000
                connection.readTimeout = 30000
                connection.instanceFollowRedirects = false
                try {
                    require(connection.responseCode == 200) { "Original asset download HTTP ${connection.responseCode}: ${source.filename}" }
                    require(connection.contentLengthLong <= source.maximumBytes)
                    connection.inputStream.use { input -> Files.newOutputStream(temporary).use { output ->
                        val bytes = ByteArray(32768)
                        var total = 0L
                        while (true) {
                            val count = input.read(bytes)
                            if (count < 0) break
                            total += count
                            require(total <= source.maximumBytes) { "Original archive exceeds its bound" }
                            output.write(bytes, 0, count)
                        }
                    } }
                } finally { connection.disconnect() }
                verify(temporary, source)
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE)
                return target
            } finally { Files.deleteIfExists(temporary) }
        }

        private fun verify(path: Path, source: ApocalypseSourceArchive) {
            require(Files.isRegularFile(path) && Files.size(path) in 1..source.maximumBytes)
            val digest = MessageDigest.getInstance("SHA-256")
            Files.newInputStream(path).use { input ->
                val buffer = ByteArray(32768)
                while (true) { val count = input.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) }
            }
            val hash = digest.digest().joinToString("") { "%02x".format(it) }
            require(hash == source.sha256) { "Original archive hash mismatch: ${source.filename}" }
        }
    }
}
