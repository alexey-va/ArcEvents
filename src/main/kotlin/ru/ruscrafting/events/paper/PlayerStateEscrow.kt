package ru.ruscrafting.events.paper

import com.google.gson.Gson
import org.bukkit.Location
import org.bukkit.entity.Player
import ru.arc.network.BackendServerId
import ru.arc.paper.playerstate.PaperPlayerStateCodec
import ru.arc.paper.playerstate.PaperPlayerStateEnvelope
import ru.arc.paper.playerstate.PaperPlayerStateService
import ru.arc.persistence.DurableAcknowledgementOutcome
import ru.arc.persistence.DurableMutationReceipt
import ru.arc.persistence.DurableRecoveryCompletion
import ru.arc.persistence.DurableRecoveryWorkflow
import ru.arc.persistence.DurableRecordJournal
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException

data class CorePlayerState(
    val playerId: String,
    val returnServer: String,
    val envelope: PaperPlayerStateEnvelope,
) {
    fun validated(codec: PaperPlayerStateCodec): CorePlayerState = apply {
        val playerUuid = UUID.fromString(playerId)
        require(playerUuid.toString() == playerId) { "Recovery player id is not canonical" }
        BackendServerId.of(returnServer)
        val decoded = codec.decode(requireNotNull(envelope))
        require(decoded.playerId == playerUuid) { "Recovery envelope belongs to a different player" }
    }
}

data class RecoveryBatch(
    val formatVersion: Int = FORMAT_VERSION,
    val matchId: String,
    val createdAtMs: Long,
    val states: List<CorePlayerState>,
    val restoredPlayerIds: Set<String> = emptySet(),
) {
    fun validated(codec: PaperPlayerStateCodec): RecoveryBatch = apply {
        require(formatVersion == FORMAT_VERSION) { "Unsupported recovery batch format" }
        require(UUID.fromString(matchId).toString() == matchId)
        require(createdAtMs > 0)
        require(states.size in 1..MAX_PLAYERS)
        states.forEach { it.validated(codec) }
        require(states.map(CorePlayerState::playerId).distinct().size == states.size)
        require(restoredPlayerIds.all { restored -> states.any { it.playerId == restored } })
    }

    fun pending(): List<CorePlayerState> = states.filter { it.playerId !in restoredPlayerIds }

    fun sameContent(other: RecoveryBatch): Boolean = this == other

    companion object {
        const val FORMAT_VERSION = 2
        const val MAX_PLAYERS = 32
    }
}

class RecoveryBatchStore(
    dataRoot: Path,
    private val gson: Gson = Gson(),
    private val stateCodec: PaperPlayerStateCodec = PaperPlayerStateCodec(),
) {
    private val journal = DurableRecordJournal(
        root = dataRoot,
        relativeDirectory = Path.of("data/recovery"),
        maxRecordBytes = MAX_BATCH_BYTES,
        encode = { batch: RecoveryBatch -> (gson.toJson(batch) + "\n").toByteArray(StandardCharsets.UTF_8) },
        decode = { bytes -> requireNotNull(gson.fromJson(bytes.toString(StandardCharsets.UTF_8), RecoveryBatch::class.java)) },
        validate = { batch -> batch.validated(stateCodec) },
    )

    @Synchronized
    fun commit(batch: RecoveryBatch): RecoveryBatch =
        journal.commit(batch.matchId, batch.validated(stateCodec))

    @Synchronized
    fun acknowledgeExactly(expected: RecoveryBatch, playerId: UUID): DurableAcknowledgementOutcome {
        val current = journal.loadOrNull(expected.matchId)
            ?: return DurableAcknowledgementOutcome.ALREADY_ACKNOWLEDGED
        if (!expected.sameContent(current)) return DurableAcknowledgementOutcome.CONTENT_MISMATCH
        require(current.states.any { it.playerId == playerId.toString() })
        val changed = current.copy(restoredPlayerIds = current.restoredPlayerIds + playerId.toString()).validated(stateCodec)
        if (changed.pending().isEmpty()) {
            return journal.acknowledgeExactly(current.matchId, current, RecoveryBatch::sameContent)
        }
        journal.commit(current.matchId, changed)
        return DurableAcknowledgementOutcome.ACKNOWLEDGED
    }

    @Synchronized
    fun loadAll(): List<RecoveryBatch> = journal.loadAll().map { stored ->
        stored.value.also { batch ->
            require(stored.recordId == batch.matchId) { "Recovery batch filename does not match its match id" }
        }
    }.sortedBy(RecoveryBatch::createdAtMs)

    fun pendingFor(playerId: UUID): Pair<RecoveryBatch, CorePlayerState>? = loadAll().firstNotNullOfOrNull { batch ->
        batch.pending().firstOrNull { it.playerId == playerId.toString() }?.let { batch to it }
    }

    fun pendingPlayers(matchId: UUID): Set<UUID> {
        return journal.loadOrNull(matchId.toString())?.pending().orEmpty()
            .map { UUID.fromString(it.playerId) }.toSet()
    }

    companion object {
        private const val MAX_BATCH_BYTES = 768L * 1024L * 1024L
    }
}

class PlayerStateEscrow(
    private val store: RecoveryBatchStore,
    private val playerStates: PaperPlayerStateService = PaperPlayerStateService(),
) {
    private val workflow = DurableRecoveryWorkflow<RecoveryBatch, PlayerRestoreReceipt>(
        commit = { candidate -> completed { store.commit(candidate) } },
        sameContent = RecoveryBatch::sameContent,
        acknowledge = { committed, receipt -> completed { store.acknowledgeExactly(committed, receipt.playerId) } },
    )

    /** Captures and commits every state before [mutation] may touch a player. */
    fun <M : Any> commitThenMutate(
        matchId: UUID,
        players: List<Player>,
        returnServers: Map<UUID, String>,
        nowMs: Long,
        mutation: (RecoveryBatch) -> M,
    ): DurableMutationReceipt<RecoveryBatch, M> {
        require(players.isNotEmpty() && players.size <= RecoveryBatch.MAX_PLAYERS)
        require(players.map(Player::getUniqueId).distinct().size == players.size)
        require(players.all { it.uniqueId in returnServers })
        val states = players.map { player ->
            CorePlayerState(
                playerId = player.uniqueId.toString(),
                returnServer = requireNotNull(returnServers[player.uniqueId]).also(BackendServerId::of),
                envelope = playerStates.captureEnvelope(player, nowMs),
            )
        }
        val candidate = RecoveryBatch(matchId = matchId.toString(), createdAtMs = nowMs, states = states)
        return workflow.commitThenMutate(candidate) { committed -> completed { mutation(committed) } }.awaitUnwrapped()
    }

    fun pendingCount(): Int = store.loadAll().sumOf { it.pending().size }

    fun pendingCount(matchId: UUID): Int = store.pendingPlayers(matchId).size

    fun recover(player: Player, teleport: (Location) -> Boolean = player::teleport): PlayerRecovery? {
        val (batch, state) = store.pendingFor(player.uniqueId) ?: return null
        val completion = workflow.restoreThenAcknowledge(batch) {
            completed {
                playerStates.restoreAndVerify(player, state.envelope) { _, location -> teleport(location) }
                PlayerRestoreReceipt(player.uniqueId, PlayerRecovery(UUID.fromString(batch.matchId), state.returnServer))
            }
        }.awaitUnwrapped()
        if (completion is DurableRecoveryCompletion.ContentMismatch) {
            error("Recovery batch changed before exact acknowledgement for ${player.uniqueId}")
        }
        return completion.restoreReceipt.recovery
    }

    fun pendingPlayers(): Set<UUID> = store.loadAll().flatMap(RecoveryBatch::pending)
        .map { UUID.fromString(it.playerId) }.toSet()

    fun pendingPlayers(matchId: UUID): Set<UUID> = store.pendingPlayers(matchId)

    private data class PlayerRestoreReceipt(val playerId: UUID, val recovery: PlayerRecovery)

    private fun <T : Any> completed(operation: () -> T): CompletableFuture<T> =
        runCatching(operation).fold(CompletableFuture<T>::completedFuture, CompletableFuture<T>::failedFuture)

    private fun <T : Any> CompletableFuture<T>.awaitUnwrapped(): T = try {
        join()
    } catch (failure: CompletionException) {
        throw failure.cause ?: failure
    }
}

data class PlayerRecovery(val matchId: UUID, val returnServer: String)
