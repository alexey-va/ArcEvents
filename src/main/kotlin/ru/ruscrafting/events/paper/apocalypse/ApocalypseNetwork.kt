package ru.ruscrafting.events.paper.apocalypse

import com.google.gson.Gson
import org.bukkit.entity.Player
import ru.arc.network.BackendServerId
import ru.arc.paper.network.BackendTransfer
import ru.arc.paper.network.BackendTransferResult
import ru.arc.redis.RedisOperations
import ru.ruscrafting.events.config.ArcEventsConfig
import ru.ruscrafting.events.paper.EventWork
import java.util.UUID
import java.util.concurrent.CompletableFuture

/** A bounded, exact-CAS arrival ticket. The host's player journal owns every admitted expedition. */
internal class ApocalypseNetwork(
    private val redis: RedisOperations,
    private val settings: () -> ArcEventsConfig,
    private val transfer: BackendTransfer,
    private val work: EventWork,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private data class Ticket(val playerId: String, val origin: String, val host: String, val nonce: String, val at: Long)
    private data class ReturnTicket(val origin: String, val host: String, val nonce: String, val acknowledged: Boolean = false)
    private val gson = Gson()
    private val key = "arc:events:apocalypse:arrivals:v1"
    private val presence = "arc:events:apocalypse:host:v1"
    private val returns = "arc:events:apocalypse:returns:v1"

    fun advertise(ready: Boolean) = redis.saveMapEntries(presence, settings().serverId,
        if (ready) clock().toString() else "0")

    /** Returns the exact submitted ticket so this connection can retry while the host has not consumed it. */
    fun enter(player: Player, currentConnection: () -> Boolean): CompletableFuture<String?> {
        val id = player.uniqueId.toString()
        val c = settings()
        val ticket = Ticket(id, c.serverId, c.hostServer, UUID.randomUUID().toString(), clock())
        val encoded = gson.toJson(ticket)
        return acknowledgeReturn(player.uniqueId, currentConnection).thenCompose {
            redis.loadMapEntries(presence, c.hostServer)
        }.thenCompose { values ->
            val observed = values.firstOrNull()?.toLongOrNull() ?: 0
            check(clock() - observed in 0..c.network.heartbeatStaleSeconds * 1_000L) { "apocalypse host is unavailable" }
            redis.loadMapEntries(key, id)
        }.thenCompose { values ->
            val old = values.firstOrNull()
            val pending = old?.let { ticket(it, id) }
            require(pending == null || pending.origin == ticket.origin && pending.host == ticket.host) { "arrival already pending" }
            redis.compareAndSetMapEntry(key, id, old, encoded)
        }.thenCompose { saved ->
            check(saved) { "arrival changed" }
            val sent = runCatching {
                work.onMain {
                    currentConnection() && fresh(ticket) &&
                        transfer.connect(player, BackendServerId.of(c.hostServer)) == BackendTransferResult.SENT
                }
            }.getOrElse { CompletableFuture.failedFuture(it) }
            sent.handle { accepted, failure ->
                if (failure == null && accepted == true) CompletableFuture.completedFuture<String?>(encoded)
                else redis.compareAndSetMapEntry(key, id, encoded, null).handle<String?> { _, cleanupFailure ->
                    if (failure != null) {
                        if (cleanupFailure != null && cleanupFailure !== failure) failure.addSuppressed(cleanupFailure)
                        throw failure
                    }
                    if (cleanupFailure != null) throw cleanupFailure
                    null
                }
            }.thenCompose { it }
        }
    }

    fun retryEntry(player: Player, issued: String, currentConnection: () -> Boolean): CompletableFuture<Boolean> {
        val id = player.uniqueId.toString()
        val route = ticket(issued, id)
        if (route == null || route.origin != settings().serverId || route.host != settings().hostServer)
            return CompletableFuture.completedFuture(false)
        return redis.loadMapEntries(key, id).thenCompose { values ->
            if (values.firstOrNull() != issued) CompletableFuture.completedFuture(false)
            else work.onMain {
                if (!currentConnection() || !fresh(route)) false else {
                    transfer.connect(player, BackendServerId.of(route.host))
                    true
                }
            }
        }
    }

    fun arrival(playerId: UUID): CompletableFuture<String?> = redis.loadMapEntries(key, playerId.toString()).thenCompose { values ->
        val raw = values.firstOrNull() ?: return@thenCompose CompletableFuture.completedFuture(null)
        val valid = ticket(raw, playerId.toString())
        if (valid != null && valid.host != settings().serverId) return@thenCompose CompletableFuture.completedFuture(null)
        redis.compareAndSetMapEntry(key, playerId.toString(), raw, null).thenApply { consumed ->
            valid?.origin?.takeIf { consumed }
        }
    }

    /** The host persists the issued nonce in PARKED before sending the player. There is no expiry. */
    fun prepareReturn(playerId: UUID, origin: String): CompletableFuture<String> {
        val current = settings()
        if (current.serverId != current.hostServer || origin !in current.network.allowedOrigins)
            return CompletableFuture.failedFuture(IllegalArgumentException("Unrecognized Apocalypse return route"))
        val ticket = ReturnTicket(origin, current.serverId, UUID.randomUUID().toString())
        val id = playerId.toString()
        return redis.loadMapEntries(returns, id).thenCompose { values ->
            val previous = values.firstOrNull()
            require(previous == null || returnTicket(previous).host == current.serverId) { "Return belongs to another host" }
            redis.compareAndSetMapEntry(returns, id, previous, gson.toJson(ticket))
        }.thenApply { saved ->
            check(saved) { "Apocalypse return changed during preparation" }
            ticket.nonce
        }
    }

    fun returnPending(playerId: UUID, origin: String, nonce: String): CompletableFuture<Boolean> {
        val expected = ReturnTicket(origin, settings().serverId, nonce)
        val id = playerId.toString()
        return redis.loadMapEntries(returns, id).thenCompose { values ->
            val raw = values.firstOrNull()
            if (raw == null) {
                // Missing Redis data does not prove arrival. Re-arm from the host's durable journal.
                redis.compareAndSetMapEntry(returns, id, null, gson.toJson(expected)).thenApply { saved ->
                    check(saved) { "Apocalypse return changed during recovery" }
                    true
                }
            } else {
                val actual = returnTicket(raw)
                check(actual.copy(acknowledged = false) == expected) { "Apocalypse return identity changed" }
                CompletableFuture.completedFuture(!actual.acknowledged)
            }
        }
    }

    fun clearAcknowledgedReturn(playerId: UUID, origin: String, nonce: String): CompletableFuture<Boolean> =
        redis.compareAndSetMapEntry(returns, playerId.toString(), gson.toJson(ReturnTicket(origin, settings().serverId, nonce, true)), null)

    /** Only the recorded origin, observing the player on its current connection, can acknowledge this nonce. */
    fun acknowledgeReturn(playerId: UUID, currentConnection: () -> Boolean): CompletableFuture<Boolean> {
        val id = playerId.toString()
        val current = settings()
        return redis.loadMapEntries(returns, id).thenCompose { values ->
            val raw = values.firstOrNull() ?: return@thenCompose CompletableFuture.completedFuture(false)
            val ticket = returnTicket(raw)
            if (ticket.acknowledged || ticket.origin != current.serverId || ticket.host != current.hostServer) CompletableFuture.completedFuture(false)
            else work.onMain(currentConnection).thenCompose { present ->
                if (present) redis.compareAndSetMapEntry(returns, id, raw, gson.toJson(ticket.copy(acknowledged = true)))
                else CompletableFuture.completedFuture(false)
            }
        }
    }

    private fun returnTicket(raw: String): ReturnTicket {
        require(raw.length <= 512)
        return gson.fromJson(raw, ReturnTicket::class.java).also {
            BackendServerId.of(it.origin)
            BackendServerId.of(it.host)
            require(UUID.fromString(it.nonce).toString() == it.nonce)
        }
    }

    private fun ticket(raw: String, playerId: String): Ticket? = runCatching {
        require(raw.length <= 1024)
        gson.fromJson(raw, Ticket::class.java).also {
            require(it.playerId == playerId && UUID.fromString(it.nonce).toString() == it.nonce)
            require(it.origin in settings().network.allowedOrigins)
            BackendServerId.of(it.host)
            require(fresh(it))
        }
    }.getOrNull()

    private fun fresh(ticket: Ticket): Boolean = clock() - ticket.at in 0..30_000
}
