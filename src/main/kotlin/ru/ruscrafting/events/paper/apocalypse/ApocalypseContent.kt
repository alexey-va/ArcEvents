package ru.ruscrafting.events.paper.apocalypse

import ru.arc.core.BoundedAsyncExecutor
import ru.ruscrafting.events.domain.apocalypse.ApocalypseLayout
import ru.ruscrafting.events.domain.apocalypse.ApocalypseOriginalLayout
import ru.ruscrafting.events.domain.apocalypse.source.ApocalypseStructureAssets
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.concurrent.CompletableFuture
import javax.net.ssl.HttpsURLConnection

/** Original archives are data sources only. No classes, scripts or Forge runtime are loaded. */
internal class ApocalypseContent(private val pluginRoot: Path) : AutoCloseable {
    private val worker = BoundedAsyncExecutor("arc-events-original-content", 1, 16, true)
    private var catalog: CompletableFuture<ApocalypseStructureAssets>? = null
    private val layouts = mutableMapOf<Long, CompletableFuture<ApocalypseLayout>>()

    @Synchronized
    fun layout(seed: Long): CompletableFuture<ApocalypseLayout> = layouts.getOrPut(seed) {
        require(layouts.size < 16) { "Too many original apocalypse generation seeds" }
        val assets = catalog ?: worker.submit {
            val files = SOURCES.map { ensureArchive(pluginRoot.resolve("content/apocalypse-original"), it) }
            ApocalypseStructureAssets.load(files[0], files[1], files[2]).also { assets ->
                // BlockData parsing reads the native immutable block registry, as it does on Paper generation workers.
                val states = assets.templates.values.flatMap { it.palette }.toSet() + assets.city.sourceStates
                for (rotation in 0..3) for (state in states) {
                    ru.ruscrafting.events.domain.apocalypse.source.ApocalypseSourcePalette.vanillaState(state, rotation)
                        ?.let(org.bukkit.Bukkit::createBlockData)
                }
            }
        }.also { catalog = it }
        assets.thenCompose { source -> worker.submit<ApocalypseLayout> { ApocalypseOriginalLayout(seed, source) } }
    }

    val closeCompletion: CompletableFuture<Unit> get() = worker.closeAsync()
    override fun close() = worker.close()

    companion object {
        val SOURCES = ApocalypseStructureAssets.pinnedSources
        private const val MAX_ARCHIVE_BYTES = 16L * 1024 * 1024

        internal fun ensureArchive(directory: Path, source: ru.ruscrafting.events.domain.apocalypse.source.ApocalypseSourceArchive): Path {
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
                    require(connection.contentLengthLong <= MAX_ARCHIVE_BYTES)
                    connection.inputStream.use { input -> Files.newOutputStream(temporary).use { output ->
                        val bytes = ByteArray(32768)
                        var total = 0L
                        while (true) {
                            val count = input.read(bytes)
                            if (count < 0) break
                            total += count
                            require(total <= MAX_ARCHIVE_BYTES) { "Original archive exceeds its bound" }
                            output.write(bytes, 0, count)
                        }
                    } }
                } finally { connection.disconnect() }
                verify(temporary, source)
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE)
                return target
            } finally { Files.deleteIfExists(temporary) }
        }

        private fun verify(path: Path, source: ru.ruscrafting.events.domain.apocalypse.source.ApocalypseSourceArchive) {
            require(Files.isRegularFile(path) && Files.size(path) in 1..MAX_ARCHIVE_BYTES)
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
