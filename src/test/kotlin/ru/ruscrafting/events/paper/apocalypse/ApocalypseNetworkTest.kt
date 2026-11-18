package ru.ruscrafting.events.paper.apocalypse

import com.google.gson.Gson
import com.google.gson.JsonParser
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.bukkit.entity.Player
import ru.arc.network.BackendServerId
import ru.arc.paper.network.BackendTransfer
import ru.arc.paper.network.BackendTransferResult
import ru.arc.redis.InMemoryRedis
import ru.arc.redis.RedisOperations
import ru.ruscrafting.events.config.ArcEventsConfig
import ru.ruscrafting.events.config.NetworkSettings
import ru.ruscrafting.events.paper.EventWork
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException

class ApocalypseNetworkTest : FunSpec({
    test("a return remains recoverable across host restarts until the matching origin sees its player") {
        val fixture = ApocalypseNetworkFixture()
        val host = fixture.network(serverId = "parkour")
        val nonce = host.prepareReturn(APOCALYPSE_PLAYER, "spawn").join()
        repeat(2) {
            fixture.network(serverId = "parkour").returnPending(APOCALYPSE_PLAYER, "spawn", nonce).join() shouldBe true
        }
        fixture.network(serverId = "survival").acknowledgeReturn(APOCALYPSE_PLAYER) { true }.join() shouldBe false
        fixture.network(serverId = "spawn").acknowledgeReturn(APOCALYPSE_PLAYER) { false }.join() shouldBe false
        host.returnPending(APOCALYPSE_PLAYER, "spawn", nonce).join() shouldBe true

        fixture.network(serverId = "spawn").acknowledgeReturn(APOCALYPSE_PLAYER) { true }.join() shouldBe true
        host.returnPending(APOCALYPSE_PLAYER, "spawn", nonce).join() shouldBe false
        fixture.network(serverId = "spawn").acknowledgeReturn(APOCALYPSE_PLAYER) { true }.join() shouldBe false
        host.clearAcknowledgedReturn(APOCALYPSE_PLAYER, "spawn", nonce).join() shouldBe true
    }

    test("missing Redis data cannot acknowledge a return with a durable host nonce") {
        val fixture = ApocalypseNetworkFixture()
        val host = fixture.network(serverId = "parkour")
        val nonce = host.prepareReturn(APOCALYPSE_PLAYER, "spawn").join()
        val returns = "arc:events:apocalypse:returns:v1"
        val id = APOCALYPSE_PLAYER.toString()
        val row = requireNotNull(fixture.redis.loadMapEntries(returns, id).join().single())
        fixture.redis.compareAndSetMapEntry(returns, id, row, null).join() shouldBe true

        host.returnPending(APOCALYPSE_PLAYER, "spawn", nonce).join() shouldBe true
        fixture.redis.loadMapEntries(returns, id).join().single() shouldBe row
        fixture.network().acknowledgeReturn(APOCALYPSE_PLAYER) { true }.join() shouldBe true
        host.returnPending(APOCALYPSE_PLAYER, "spawn", nonce).join() shouldBe false
    }

    test("a scheduler delay past admission expiry never sends a player with a dead ticket") {
        val fixture = ApocalypseNetworkFixture()
        var now = System.currentTimeMillis()
        val relay = fixture.network(clock = { now })
        relay.enter(fixture.player) { now += 30_001; true }.join() shouldBe null
        fixture.pending() shouldBe null
        verify(exactly = 0) { fixture.transfer.connect(any(), any()) }
    }

    test("a delayed acknowledgement cannot remove a later expedition's return") {
        val fixture = ApocalypseNetworkFixture()
        val host = fixture.network(serverId = "parkour")
        host.prepareReturn(APOCALYPSE_PLAYER, "spawn").join()
        lateinit var next: String
        val result = fixture.network().acknowledgeReturn(APOCALYPSE_PLAYER) {
            next = host.prepareReturn(APOCALYPSE_PLAYER, "survival").join()
            true
        }
        result.join() shouldBe false
        host.returnPending(APOCALYPSE_PLAYER, "survival", next).join() shouldBe true
    }

    test("same-origin retry refreshes its pending ticket and sends the transfer again") {
        val fixture = ApocalypseNetworkFixture()
        val relay = fixture.network()
        val first = requireNotNull(relay.enter(fixture.player) { fixture.player.isOnline }.join())
        val before = JsonParser.parseString(requireNotNull(fixture.pending())).asJsonObject

        val second = requireNotNull(relay.enter(fixture.player) { fixture.player.isOnline }.join())
        val after = JsonParser.parseString(requireNotNull(fixture.pending())).asJsonObject
        (after["nonce"].asString != before["nonce"].asString) shouldBe true
        (after["at"].asLong >= before["at"].asLong) shouldBe true
        after["origin"].asString shouldBe "spawn"
        after["host"].asString shouldBe "parkour"
        verify(exactly = 2) { fixture.transfer.connect(fixture.player, BackendServerId.of("parkour")) }
        relay.retryEntry(fixture.player, first) { true }.join() shouldBe false
        relay.retryEntry(fixture.player, second) { false }.join() shouldBe false
        relay.retryEntry(fixture.player, second) { true }.join() shouldBe true
        fixture.network(serverId = "parkour").arrival(APOCALYPSE_PLAYER).join() shouldBe "spawn"
        relay.retryEntry(fixture.player, second) { true }.join() shouldBe false
        verify(exactly = 3) { fixture.transfer.connect(fixture.player, BackendServerId.of("parkour")) }
    }

    test("a valid pending ticket for another origin or host is left untouched") {
        for (raw in listOf(
            ApocalypseNetworkFixture.ticket(origin = "survival"),
            ApocalypseNetworkFixture.ticket(host = "survival"),
        )) {
            val fixture = ApocalypseNetworkFixture()
            fixture.seed(raw)
            shouldThrow<CompletionException> { fixture.network().enter(fixture.player) { fixture.player.isOnline }.join() }
                .cause?.message shouldBe "arrival already pending"
            fixture.pending() shouldBe raw
            verify(exactly = 0) { fixture.transfer.connect(any(), any()) }
        }
    }

    test("a failed transfer or offline player removes only its own pending ticket") {
        for (offline in listOf(false, true)) {
            val fixture = ApocalypseNetworkFixture()
            every { fixture.player.isOnline } returns !offline
            every { fixture.transfer.connect(any(), any()) } returns BackendTransferResult.SEND_FAILED

            fixture.network().enter(fixture.player) { fixture.player.isOnline }.join() shouldBe null
            fixture.pending() shouldBe null
            verify(exactly = if (offline) 0 else 1) { fixture.transfer.connect(any(), any()) }
        }
    }

    test("an online retired connection cannot send or retain its transfer ticket") {
        val fixture = ApocalypseNetworkFixture()
        fixture.network().enter(fixture.player) { false }.join() shouldBe null
        fixture.pending() shouldBe null
        verify(exactly = 0) { fixture.transfer.connect(any(), any()) }
    }

    test("a throwing transfer cleans its own nonce and retains the transfer failure") {
        val fixture = ApocalypseNetworkFixture()
        val failure = IllegalStateException("transfer failed")
        every { fixture.transfer.connect(any(), any()) } throws failure

        shouldThrow<CompletionException> { fixture.network().enter(fixture.player) { fixture.player.isOnline }.join() }.cause shouldBe failure
        fixture.pending() shouldBe null
    }

    test("a failed admission CAS cannot overwrite or delete a concurrent ticket") {
        val fixture = ApocalypseNetworkFixture()
        val foreign = ApocalypseNetworkFixture.ticket(origin = "survival")
        val raced = object : RedisOperations by fixture.redis {
            override fun compareAndSetMapEntry(
                key: String, mapKey: String, expectedValue: String?, replacementValue: String?,
            ): CompletableFuture<Boolean> {
                if (key == APOCALYPSE_ARRIVALS && replacementValue != null) fixture.seed(foreign)
                return fixture.redis.compareAndSetMapEntry(key, mapKey, expectedValue, replacementValue)
            }
        }

        shouldThrow<CompletionException> { fixture.network(redis = raced).enter(fixture.player) { fixture.player.isOnline }.join() }
            .cause?.message shouldBe "arrival changed"
        fixture.pending() shouldBe foreign
        verify(exactly = 0) { fixture.transfer.connect(any(), any()) }
    }

    test("failure cleanup cannot delete a ticket replaced during transfer") {
        for (throws in listOf(false, true)) {
            val fixture = ApocalypseNetworkFixture()
            val foreign = ApocalypseNetworkFixture.ticket(origin = "survival")
            every { fixture.transfer.connect(any(), any()) } answers {
                fixture.seed(foreign)
                if (throws) throw IllegalStateException("transfer failed")
                BackendTransferResult.SEND_FAILED
            }
            val result = fixture.network().enter(fixture.player) { fixture.player.isOnline }
            if (throws) shouldThrow<CompletionException> { result.join() } else result.join() shouldBe null
            fixture.pending() shouldBe foreign
        }
    }

    test("only the addressed receiver consumes the ticket exactly once") {
        val fixture = ApocalypseNetworkFixture()
        requireNotNull(fixture.network().enter(fixture.player) { fixture.player.isOnline }.join())
        val raw = fixture.pending()

        fixture.network(serverId = "survival").arrival(APOCALYPSE_PLAYER).join() shouldBe null
        fixture.pending() shouldBe raw
        val host = fixture.network(serverId = "parkour")
        host.arrival(APOCALYPSE_PLAYER).join() shouldBe "spawn"
        host.arrival(APOCALYPSE_PLAYER).join() shouldBe null
        fixture.pending() shouldBe null
    }

    test("the receiver still cleans malformed and expired tickets") {
        for (raw in listOf("not-json", ApocalypseNetworkFixture.ticket(at = System.currentTimeMillis() - 60_000))) {
            val fixture = ApocalypseNetworkFixture()
            fixture.seed(raw)
            fixture.network(serverId = "parkour").arrival(APOCALYPSE_PLAYER).join() shouldBe null
            fixture.pending() shouldBe null
        }
    }
})

private const val APOCALYPSE_ARRIVALS = "arc:events:apocalypse:arrivals:v1"
private val APOCALYPSE_PLAYER = UUID(0, 721)

private class ApocalypseNetworkFixture {
    val redis = InMemoryRedis()
    val player = mockk<Player> {
        every { uniqueId } returns APOCALYPSE_PLAYER
        every { isOnline } returns true
    }
    val transfer = mockk<BackendTransfer> {
        every { connect(any(), any()) } returns BackendTransferResult.SENT
    }

    init {
        redis.saveMapEntries("arc:events:apocalypse:host:v1", "parkour", System.currentTimeMillis().toString()).join()
    }

    fun network(serverId: String = "spawn", redis: RedisOperations = this.redis, clock: () -> Long = System::currentTimeMillis): ApocalypseNetwork {
        val settings = mockk<ArcEventsConfig> {
            every { this@mockk.serverId } returns serverId
            every { hostServer } returns "parkour"
            every { network } returns NetworkSettings(true, setOf("spawn", "survival", "parkour"), 300, 45, 5, 20, true, true)
        }
        return ApocalypseNetwork(redis, { settings }, transfer, ApocalypseNetworkTestWork, clock)
    }

    fun seed(raw: String) { redis.saveMapEntries(APOCALYPSE_ARRIVALS, APOCALYPSE_PLAYER.toString(), raw).join() }
    fun pending(): String? = redis.loadMapEntries(APOCALYPSE_ARRIVALS, APOCALYPSE_PLAYER.toString()).join().single()

    companion object {
        fun ticket(origin: String = "spawn", host: String = "parkour", at: Long = System.currentTimeMillis()): String =
            Gson().toJson(mapOf("playerId" to APOCALYPSE_PLAYER.toString(), "origin" to origin, "host" to host,
                "nonce" to UUID.randomUUID().toString(), "at" to at))
    }
}

private object ApocalypseNetworkTestWork : EventWork {
    override fun <T> compute(action: () -> T): CompletableFuture<T> = onMain(action)
    override fun <T> onMain(action: () -> T): CompletableFuture<T> = try {
        CompletableFuture.completedFuture(action())
    } catch (failure: Throwable) {
        CompletableFuture.failedFuture(failure)
    }
    override fun capture(isCurrent: () -> Boolean, step: () -> Boolean): CompletableFuture<Unit> =
        error("Network entry does not use native capture")
    override fun isCurrent(): Boolean = true
}
