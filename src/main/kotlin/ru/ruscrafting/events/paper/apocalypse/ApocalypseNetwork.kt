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
) {
    private data class Ticket(val playerId: String, val origin: String, val host: String, val nonce: String, val at: Long)
    private val gson = Gson()
    private val key = "arc:events:apocalypse:arrivals:v1"
    private val presence = "arc:events:apocalypse:host:v1"

    fun advertise(ready: Boolean) = redis.saveMapEntries(presence, settings().serverId,
        if (ready) System.currentTimeMillis().toString() else "0")

    fun enter(player: Player, currentConnection: () -> Boolean): CompletableFuture<Boolean> {
        val id = player.uniqueId.toString()
        val c = settings()
        val ticket = Ticket(id, c.serverId, c.hostServer, UUID.randomUUID().toString(), System.currentTimeMillis())
        val encoded = gson.toJson(ticket)
        return redis.loadMapEntries(presence, c.hostServer).thenCompose { values ->
            val observed = values.firstOrNull()?.toLongOrNull() ?: 0
            check(System.currentTimeMillis() - observed in 0..20000) { "apocalypse host is unavailable" }
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
                    currentConnection() && transfer.connect(player, BackendServerId.of(c.hostServer)) == BackendTransferResult.SENT
                }
            }.getOrElse { CompletableFuture.failedFuture(it) }
            sent.handle { accepted, failure ->
                if (failure == null && accepted == true) CompletableFuture.completedFuture(true)
                else redis.compareAndSetMapEntry(key, id, encoded, null).handle { _, cleanupFailure ->
                    if (failure != null) {
                        if (cleanupFailure != null && cleanupFailure !== failure) failure.addSuppressed(cleanupFailure)
                        throw failure
                    }
                    if (cleanupFailure != null) throw cleanupFailure
                    false
                }
            }.thenCompose { it }
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

    private fun ticket(raw: String, playerId: String): Ticket? = runCatching {
        require(raw.length <= 1024)
        gson.fromJson(raw, Ticket::class.java).also {
            require(it.playerId == playerId && UUID.fromString(it.nonce).toString() == it.nonce)
            require(it.origin in settings().network.allowedOrigins)
            BackendServerId.of(it.host)
            require(System.currentTimeMillis() - it.at in 0..30000)
        }
    }.getOrNull()
}
