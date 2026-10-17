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
import java.util.concurrent.atomic.AtomicInteger

data class CorePlayerState(
    val playerId: String,
    val returnServer: String,
    val envelope: PaperPlayerStateEnvelope,
) {
    fun validated(codec: PaperPlayerStateCodec): CorePlayerState = apply {
        val playerUuid = UUID.fromString(playerId)
        require(playerUuid.toString() == playerId) { "Recovery player id is not canonical" }
        BackendServerId.of(returnServer)
        val decoded = codec.validateEnvelope(requireNotNull(envelope))
        require(decoded == playerUuid) { "Recovery envelope belongs to a different player" }
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
    private val journal by lazy { DurableRecordJournal(
        root = dataRoot,
        relativeDirectory = Path.of("data/recovery"),
        maxRecordBytes = MAX_BATCH_BYTES,
        encode = { batch: RecoveryBatch -> (gson.toJson(batch) + "\n").toByteArray(StandardCharsets.UTF_8) },
        decode = { bytes -> requireNotNull(gson.fromJson(bytes.toString(StandardCharsets.UTF_8), RecoveryBatch::class.java)) },
        validate = { batch -> batch.validated(stateCodec) },
    ) }
    private data class RecoveryView(
        val batches: List<RecoveryBatch>,
        val pending: Map<UUID, Pair<RecoveryBatch, CorePlayerState>>,
        val matches: Map<UUID, Set<UUID>>,
    )
    @Volatile private var view: RecoveryView? = null

    /** Worker-only cold validation; ready getters never acquire the journal monitor or touch disk. */
    fun prepare() { loadAll() }

    private fun publish(batches: List<RecoveryBatch>) {
        val ordered = batches.sortedBy(RecoveryBatch::createdAtMs)
        val pendingIndex = buildMap {
            ordered.forEach { batch -> batch.pending().forEach { state -> putIfAbsent(UUID.fromString(state.playerId), batch to state) } }
        }
        val matchIndex = ordered.associate { UUID.fromString(it.matchId) to it.pending().mapTo(linkedSetOf()) { state -> UUID.fromString(state.playerId) } }
        view = RecoveryView(ordered, pendingIndex, matchIndex)
    }

    private fun committed(batch: RecoveryBatch): RecoveryBatch {
        publish(loadAll().filterNot { it.matchId == batch.matchId } + batch)
        return batch
    }


    @Synchronized
    fun commit(batch: RecoveryBatch): RecoveryBatch =
        committed(journal.commit(batch.matchId, batch.validated(stateCodec)))

    /** Adds newly arrived players while retaining every earlier pre-mutation snapshot. */
    @Synchronized
    fun merge(batch: RecoveryBatch): RecoveryBatch {
        val candidate = batch.validated(stateCodec)
        val current = journal.loadOrNull(candidate.matchId)
        if (current == null) return committed(journal.commit(candidate.matchId, candidate))
        val existing = current.validated(stateCodec)
        val existingByPlayer = existing.states.associateBy(CorePlayerState::playerId)
        candidate.states.forEach { state ->
            existingByPlayer[state.playerId]?.let { previous ->
                require(previous.returnServer == state.returnServer) {
                    "Recovery return server changed for ${state.playerId}"
                }
                require(state.playerId !in existing.restoredPlayerIds) {
                    "Cannot re-add an already restored player ${state.playerId}"
                }
            }
        }
        val merged = existing.copy(
            createdAtMs = minOf(existing.createdAtMs, candidate.createdAtMs),
            states = existing.states + candidate.states.filter { it.playerId !in existingByPlayer },
        ).validated(stateCodec)
        return committed(journal.commit(merged.matchId, merged))
    }

    @Synchronized
    fun acknowledgeExactly(expected: RecoveryBatch, playerId: UUID): DurableAcknowledgementOutcome {
        val current = journal.loadOrNull(expected.matchId)
            ?: return DurableAcknowledgementOutcome.ALREADY_ACKNOWLEDGED
        if (!expected.sameContent(current)) return DurableAcknowledgementOutcome.CONTENT_MISMATCH
        require(current.states.any { it.playerId == playerId.toString() })
        val changed = current.copy(restoredPlayerIds = current.restoredPlayerIds + playerId.toString()).validated(stateCodec)
        if (changed.pending().isEmpty()) {
            return journal.acknowledgeExactly(current.matchId, current, RecoveryBatch::sameContent).also { outcome ->
                if (outcome != DurableAcknowledgementOutcome.CONTENT_MISMATCH) publish(loadAll().filterNot { it.matchId == current.matchId })
            }
        }
        committed(journal.commit(current.matchId, changed))
        return DurableAcknowledgementOutcome.ACKNOWLEDGED
    }

    fun loadAll(): List<RecoveryBatch> = view?.batches ?: journal.loadAll().map { stored ->
        stored.value.also { batch ->
            require(stored.recordId == batch.matchId) { "Recovery batch filename does not match its match id" }
        }
    }.sortedBy(RecoveryBatch::createdAtMs).also(::publish)

    fun pendingFor(playerId: UUID): Pair<RecoveryBatch, CorePlayerState>? {
        return checkNotNull(view) { "Recovery store has not completed preparation" }.pending[playerId]
    }

    fun pendingPlayers(matchId: UUID): Set<UUID> {
        return checkNotNull(view) { "Recovery store has not completed preparation" }.matches[matchId].orEmpty()
    }

    fun pendingPlayers(): Set<UUID> {
        return checkNotNull(view) { "Recovery store has not completed preparation" }.pending.keys
    }

    fun pendingCount(): Int = view?.pending?.size ?: loadAll().sumOf { it.pending().size }

    companion object {
        private const val MAX_BATCH_BYTES = 768L * 1024L * 1024L
    }
}

class PlayerStateEscrow(
    private val store: RecoveryBatchStore,
    private val playerStates: PaperPlayerStateService = PaperPlayerStateService(),
    private val enabled: Boolean = true,
    private val work: EventWork? = null,
    private val stateCodec: PaperPlayerStateCodec = PaperPlayerStateCodec(),
) {
    private val inFlight = mutableMapOf<UUID, CompletableFuture<*>>()
    private val recoveryTails = mutableMapOf<String, CompletableFuture<Unit>>()
    private var closed = false
    fun isLocked(playerId: UUID): Boolean = playerId in inFlight
    fun close() { closed = true }

    private val cachedRecoveryBacklog = AtomicInteger(if (enabled) loadPendingCount() else 0)
    private val workflow = DurableRecoveryWorkflow<RecoveryBatch, PlayerRestoreReceipt>(
        commit = { candidate -> completed { store.commit(candidate) } },
        sameContent = RecoveryBatch::sameContent,
        acknowledge = { committed, receipt -> completed { store.acknowledgeExactly(committed, receipt.playerId) } },
    )

    /** Capture one native player payload per shared step; hashing, journal IO and indexing stay on the worker. */
    fun <M : Any> commitThenMutateAsync(
        matchId: UUID, players: List<Player>, returnServers: Map<UUID, String>, nowMs: Long,
        isCurrent: () -> Boolean = { true }, mutation: (RecoveryBatch) -> M,
    ): CompletableFuture<DurableMutationReceipt<RecoveryBatch, M>> {
        return commitThenMutateStagesAsync(matchId, players, returnServers, nowMs, isCurrent) { batch ->
            completed { mutation(batch) }
        }
    }

    /** Keeps the player lease until every budgeted native mutation has actually completed. */
    fun <M : Any> commitThenMutateStagesAsync(
        matchId: UUID, players: List<Player>, returnServers: Map<UUID, String>, nowMs: Long,
        isCurrent: () -> Boolean = { true }, mutation: (RecoveryBatch) -> CompletableFuture<M>,
    ): CompletableFuture<DurableMutationReceipt<RecoveryBatch, M>> {
        val tasks = work ?: return completed {
            commitThenMutate(matchId, players, returnServers, nowMs, mutation)
        }.thenCompose { receipt -> receipt.mutation.thenApply { DurableMutationReceipt(receipt.committed, it) } }
        check(enabled && !closed)
        require(players.isNotEmpty() && players.size <= RecoveryBatch.MAX_PLAYERS)
        val identities = players.map(Player::getUniqueId)
        require(identities.distinct().size == identities.size && identities.all(returnServers::containsKey))
        if (identities.any(inFlight::containsKey)) return CompletableFuture.failedFuture(IllegalStateException("Player state operation already pending"))
        val result = CompletableFuture<DurableMutationReceipt<RecoveryBatch, M>>()
        identities.forEach { inFlight[it] = result }
        val pending = players.iterator()
        val captured = mutableListOf<Pair<UUID, ByteArray>>()
        fun current() = !closed && tasks.isCurrent() && isCurrent() && players.all { it.isOnline && !it.isDead } && identities.all { inFlight[it] === result }
        tasks.capture(::current) {
            if (pending.hasNext()) {
                val player = pending.next()
                captured += player.uniqueId to stateCodec.encodePayload(playerStates.capture(player, nowMs))
            }
            !pending.hasNext()
        }.thenCompose {
            tasks.compute {
                val states = captured.map { (id, payload) -> CorePlayerState(id.toString(), returnServers.getValue(id), stateCodec.wrapPayload(payload)) }
                store.merge(RecoveryBatch(matchId = matchId.toString(), createdAtMs = nowMs, states = states))
            }
        }.thenCompose { batch -> tasks.onMain {
            check(current()) { "Event player capture superseded before native mutation" }
            mutation(batch).thenApply { DurableMutationReceipt(batch, it) }
        }.thenCompose { it } }.whenComplete { receipt, failure -> tasks.onMain {
            identities.forEach { inFlight.remove(it, result) }
            refreshRecoveryBacklog()
            if (failure == null) result.complete(receipt) else result.completeExceptionally(failure)
        }.exceptionally { result.completeExceptionally(it); null } }
        return result
    }

    /** Native blob decoding/restoration runs only on Paper and acknowledgement follows actual verified saveData. */
    fun recoverAsync(player: Player, teleport: (Location) -> Boolean = player::teleport): CompletableFuture<PlayerRecovery?> {
        val tasks = work ?: return runCatching { recover(player, teleport) }.fold(CompletableFuture<PlayerRecovery?>::completedFuture, CompletableFuture<PlayerRecovery?>::failedFuture)
        check(enabled && !closed)
        val id = player.uniqueId
        if (id in inFlight) return CompletableFuture.failedFuture(IllegalStateException("Player state operation already pending"))
        val pending = store.pendingFor(id) ?: return CompletableFuture.completedFuture(null)
        val (admittedBatch, admittedState) = pending
        val result = CompletableFuture<PlayerRecovery?>()
        inFlight[id] = result
        // Exact acknowledgements change a batch's content. Serialize recovery within that batch,
        // reading its current receipt only after the previous player's acknowledgement finished.
        val predecessor = recoveryTails[admittedBatch.matchId] ?: CompletableFuture.completedFuture(Unit)
        val tail = CompletableFuture<Unit>()
        recoveryTails[admittedBatch.matchId] = tail
        predecessor.thenCompose { tasks.onMain {
            check(!closed && tasks.isCurrent() && player.isOnline && inFlight[id] === result)
            val latest = requireNotNull(store.pendingFor(id)) { "Player recovery changed before capture" }
            require(latest.first.matchId == admittedBatch.matchId && latest.second == admittedState) { "Player recovery source changed" }
            latest
        } }.thenCompose { (batch, state) -> tasks.compute { stateCodec.prepareDecode(state.envelope) }
            .thenCompose { prepared -> tasks.onMain {
                tasks.capture({ !closed && tasks.isCurrent() && player.isOnline && inFlight[id] === result }) {
                    // Native blob failures retain the exact journal record and never acknowledge it.
                    val snapshot = stateCodec.decode(prepared)
                    require(snapshot.playerId == id)
                    playerStates.restoreAndVerify(player, snapshot) { _, location -> teleport(location) }
                    true
                }
            }.thenCompose { it } }.thenCompose { tasks.compute {
                val outcome = store.acknowledgeExactly(batch, id)
                check(outcome != DurableAcknowledgementOutcome.CONTENT_MISMATCH) { "Recovery batch changed before exact acknowledgement for $id" }
                PlayerRecovery(UUID.fromString(batch.matchId), state.returnServer)
            } }
        }.whenComplete { recovery, failure -> tasks.onMain {
            inFlight.remove(id, result)
            refreshRecoveryBacklog()
            if (failure == null) result.complete(recovery) else result.completeExceptionally(failure)
            recoveryTails.remove(admittedBatch.matchId, tail)
            tail.complete(Unit)
        }.exceptionally {
            result.completeExceptionally(it)
            tail.completeExceptionally(it)
            null
        } }
        return result
    }

    /** Captures and commits every state before [mutation] may touch a player. */
    fun <M : Any> commitThenMutate(
        matchId: UUID,
        players: List<Player>,
        returnServers: Map<UUID, String>,
        nowMs: Long,
        mutation: (RecoveryBatch) -> M,
    ): DurableMutationReceipt<RecoveryBatch, M> {
        check(enabled) { "Player-state escrow is disabled by player-state.mode" }
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
        return try {
            val committed = store.merge(candidate)
            DurableMutationReceipt(committed, mutation(committed))
        } finally {
            refreshRecoveryBacklog()
        }
    }

    fun pendingCount(): Int = if (enabled) store.pendingCount() else 0

    /** Constant-time, thread-safe recovery gauge for runtime health sampling. */
    fun recoveryBacklog(): Int = cachedRecoveryBacklog.get()

    fun pendingCount(matchId: UUID): Int = if (enabled) store.pendingPlayers(matchId).size else 0

    fun recover(player: Player, teleport: (Location) -> Boolean = player::teleport): PlayerRecovery? {
        check(enabled) { "Player-state escrow is disabled by player-state.mode" }
        val (batch, state) = store.pendingFor(player.uniqueId) ?: return null
        val completion = try {
            workflow.restoreThenAcknowledge(batch) {
                completed {
                    playerStates.restoreAndVerify(player, state.envelope) { _, location -> teleport(location) }
                    PlayerRestoreReceipt(player.uniqueId, PlayerRecovery(UUID.fromString(batch.matchId), state.returnServer))
                }
            }
                .awaitUnwrapped()
        } finally {
            refreshRecoveryBacklog()
        }
        if (completion is DurableRecoveryCompletion.ContentMismatch) {
            error("Recovery batch changed before exact acknowledgement for ${player.uniqueId}")
        }
        return completion.restoreReceipt.recovery
    }

    fun pendingPlayers(): Set<UUID> = if (!enabled) emptySet() else store.pendingPlayers()

    fun pendingPlayers(matchId: UUID): Set<UUID> = if (enabled) store.pendingPlayers(matchId) else emptySet()

    private data class PlayerRestoreReceipt(val playerId: UUID, val recovery: PlayerRecovery)

    private fun refreshRecoveryBacklog() {
        if (!enabled) return
        runCatching(::loadPendingCount).onSuccess(cachedRecoveryBacklog::set)
    }

    private fun loadPendingCount(): Int = store.pendingCount()

    private fun <T : Any> completed(operation: () -> T): CompletableFuture<T> =
        runCatching(operation).fold(CompletableFuture<T>::completedFuture, CompletableFuture<T>::failedFuture)

    private fun <T : Any> CompletableFuture<T>.awaitUnwrapped(): T = try {
        join()
    } catch (failure: CompletionException) {
        throw failure.cause ?: failure
    }
}

data class PlayerRecovery(val matchId: UUID, val returnServer: String)
