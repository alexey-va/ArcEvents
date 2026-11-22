package ru.ruscrafting.events.paper.apocalypse

import com.google.gson.Gson
import ru.arc.network.NetworkPlayerName
import ru.arc.persistence.DurableRecordJournal
import ru.ruscrafting.events.paper.EventWork
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture

/** Confined to the host main thread. The journal is opened and accessed on the I/O worker. */
internal class ApocalypseCampaignAccess(
    private val root: Path,
    private val work: EventWork,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {
    private data class Visit(val owner: UUID, val visitor: UUID)

    private data class StoredAccess(val owner: UUID, val companions: Set<UUID>, val ownerName: String? = null) {
        fun validate() {
            require(owner !in companions) { "Campaign ACL includes its owner" }
            ownerName?.let { NetworkPlayerName.of(it) }
        }

        fun forRecord(recordId: String): StoredAccess {
            require(owner.toString() == recordId) { "Campaign ACL owner does not match record $recordId" }
            return copy(companions = java.util.Set.copyOf(companions))
        }
    }

    private var journal: DurableRecordJournal<StoredAccess>? = null
    private var preparation: CompletableFuture<Unit>? = null
    private var accessByOwner = mutableMapOf<UUID, StoredAccess>()
    private val invitations = mutableMapOf<Visit, Long>()
    private val writes = mutableMapOf<UUID, CompletableFuture<Boolean>>()

    fun prepare(): CompletableFuture<Unit> {
        preparation?.let { return it.copy() }
        val future = work.compute {
            val gson = Gson()
            val store = DurableRecordJournal(root, Path.of("data/apocalypse/campaign-access"), 64L * 1024,
                { value: StoredAccess -> gson.toJson(value).toByteArray(Charsets.UTF_8) },
                { bytes -> gson.fromJson(bytes.toString(Charsets.UTF_8), StoredAccess::class.java) },
                StoredAccess::validate)
            val initial = mutableMapOf<UUID, StoredAccess>()
            store.loadAll().forEach { record ->
                val access = record.value.forRecord(record.recordId)
                if (access.companions.isNotEmpty()) initial[access.owner] = access
            }
            store to initial
        }.thenCompose { (store, initial) ->
            work.onMain {
                accessByOwner = initial
                journal = store
            }
        }
        preparation = future
        return future.copy()
    }

    fun companions(owner: UUID): Set<UUID> = accessByOwner[owner]?.companions.orEmpty()

    fun companionCampaigns(visitor: UUID): Set<UUID> = accessByOwner.values.asSequence()
        .filter { visitor in it.companions }.map { it.owner }.toSet()

    fun companionOwnerName(owner: UUID): String? = accessByOwner[owner]?.ownerName

    fun namedCompanionCampaign(visitor: UUID, name: String): UUID? {
        if (NetworkPlayerName.parseOrNull(name) == null) return null
        return accessByOwner.values.asSequence()
            .filter { visitor in it.companions && it.ownerName.equals(name, ignoreCase = true) }
            .map { it.owner }.singleOrNull()
    }

    fun invite(owner: UUID, visitor: UUID): Boolean {
        if (journal == null || owner == visitor || visitor in companions(owner)) return false
        val now = nowMillis()
        expireInvitations(now)
        val visit = Visit(owner, visitor)
        if (visit !in invitations && (invitations.size >= MAX_INVITATIONS ||
                invitations.keys.count { it.owner == owner } >= MAX_INVITATIONS_PER_OWNER)) return false
        invitations[visit] = now + INVITATION_MILLIS
        return true
    }

    fun pendingInvites(visitor: UUID): Set<UUID> {
        expireInvitations(nowMillis())
        return invitations.keys.asSequence().filter { it.visitor == visitor }.map { it.owner }.toSet()
    }

    fun canEnter(owner: UUID, visitor: UUID, ownerPresent: Boolean): Boolean =
        owner == visitor || visitor in companions(owner) || ownerPresent &&
            invitations[Visit(owner, visitor)]?.let { it > nowMillis() } == true

    /** Call after a fresh entry succeeds on the host. */
    fun consumeInvite(owner: UUID, visitor: UUID) {
        invitations.remove(Visit(owner, visitor))
    }

    fun setCompanion(owner: UUID, visitor: UUID, approved: Boolean, ownerName: String? = null): CompletableFuture<Boolean> {
        if (owner == visitor) return CompletableFuture.completedFuture(false)
        val store = journal ?: return CompletableFuture.failedFuture(IllegalStateException("Campaign ACL is unavailable"))
        val validatedName = NetworkPlayerName.parseOrNull(ownerName)?.value
        val recordId = owner.toString()
        val previous = writes[owner] ?: CompletableFuture.completedFuture(false)
        val write = previous.handle { _, _ -> Unit }.thenCompose {
            work.compute {
                if (!work.isCurrent()) throw CancellationException("Campaign ACL generation stopped")
                // A failed write can leave an atomic replacement on disk. Read the owner record again.
                val current = store.loadOrNull(recordId)?.forRecord(recordId) ?: StoredAccess(owner, emptySet())
                val next = if (approved) {
                    current.copy(companions = current.companions + visitor, ownerName = validatedName ?: current.ownerName)
                } else current.copy(companions = current.companions - visitor)
                store.commit(recordId, next).forRecord(recordId)
            }
        }.thenCompose { access ->
            work.onMain {
                val changed = (visitor in companions(owner)) != (visitor in access.companions)
                if (access.companions.isEmpty()) accessByOwner.remove(owner) else accessByOwner[owner] = access
                if (changed) consumeInvite(owner, visitor)
                changed
            }
        }
        writes[owner] = write
        write.whenComplete { _, _ -> work.onMain { writes.remove(owner, write) } }
        return write.copy()
    }

    private fun expireInvitations(now: Long) {
        invitations.values.removeIf { it <= now }
    }

    private companion object {
        const val INVITATION_MILLIS = 5 * 60_000L
        const val MAX_INVITATIONS_PER_OWNER = 8
        const val MAX_INVITATIONS = 128
    }
}
