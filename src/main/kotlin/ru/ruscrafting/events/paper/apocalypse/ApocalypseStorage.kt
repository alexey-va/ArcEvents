package ru.ruscrafting.events.paper.apocalypse

import com.google.gson.Gson
import ru.arc.paper.playerstate.PaperPlayerStateEnvelope
import ru.arc.paper.playerstate.PaperPlayerStateCodec
import ru.arc.persistence.DurableRecordJournal
import ru.ruscrafting.events.paper.EventWork
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture

/** Origin and expedition are separate inventories. RETURNING is replayed before another admission. */
enum class ApocalypsePlayerPhase { ACTIVE, RETURNING, PARKED }

data class ApocalypsePlayerRecord(
    val playerId: String,
    val originServer: String,
    val phase: ApocalypsePlayerPhase,
    val origin: PaperPlayerStateEnvelope?,
    val expedition: PaperPlayerStateEnvelope?,
    val returnPending: Boolean = false,
    val nativeSession: String? = null,
    val returnTicket: String? = null,
) {
    fun validated() {
        UUID.fromString(playerId)
        nativeSession?.let(UUID::fromString)
        returnTicket?.let {
            require(phase == ApocalypsePlayerPhase.PARKED && returnPending && UUID.fromString(it).toString() == it)
        }
        ru.arc.network.BackendServerId.of(originServer)
        require(phase == ApocalypsePlayerPhase.PARKED || origin != null)
        val codec = PaperPlayerStateCodec()
        listOfNotNull(origin, expedition).forEach { require(codec.validateEnvelope(it).toString() == playerId) }
    }
}

/** The existing core journal owns atomic replacement, bounds, fsync and readback. Worker-only. */
class ApocalypseStorage(root: Path) {
    private val gson = Gson()
    private val players = DurableRecordJournal(root, Path.of("data/apocalypse/players"), 8L * 1024 * 1024,
        { value: ApocalypsePlayerRecord -> gson.toJson(value).toByteArray(Charsets.UTF_8) },
        { bytes -> gson.fromJson(bytes.toString(Charsets.UTF_8), ApocalypsePlayerRecord::class.java) },
        ApocalypsePlayerRecord::validated)

    fun loadPlayers(): Map<UUID, ApocalypsePlayerRecord> = players.loadAll().associate {
        UUID.fromString(it.value.playerId) to it.value
    }
    fun save(record: ApocalypsePlayerRecord) = players.commit(record.playerId, record)
    fun load(playerId: UUID): ApocalypsePlayerRecord? = players.loadOrNull(playerId.toString())
}

/** Retries the same immutable journal replacement. Native effects wait on one completion. */
internal class ApocalypseCheckpointWrites(
    private val work: EventWork,
    private val retryLater: (() -> Unit) -> Unit,
    private val healthChanged: (Throwable?) -> Unit,
) {
    var failed = false
        private set
    private var logical: CompletableFuture<Unit>? = null
    private var writing = CompletableFuture.completedFuture(Unit)
    private var closed = false

    fun commit(write: () -> Unit): CompletableFuture<Unit> {
        check(!closed && logical == null)
        val completion = CompletableFuture<Unit>()
        logical = completion
        fun attempt() {
            if (closed || logical !== completion) return
            writing = work.compute(write)
            writing.whenComplete { _, failure -> work.onMain {
                if (closed || logical !== completion) return@onMain
                if (failure != null) {
                    if (!failed) healthChanged(failure)
                    failed = true
                    retryLater(::attempt)
                } else {
                    if (failed) healthChanged(null)
                    failed = false
                    logical = null
                    completion.complete(Unit)
                }
            } }
        }
        attempt()
        return completion
    }

    /** Shutdown waits only for admitted I/O. A scheduled retry will never need a stopped main thread. */
    fun stop(): CompletableFuture<Unit> {
        closed = true
        logical?.completeExceptionally(CancellationException("Apocalypse checkpoint stopped"))
        logical = null
        return writing
    }
}
