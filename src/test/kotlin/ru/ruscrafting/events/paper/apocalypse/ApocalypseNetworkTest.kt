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
    test("same-origin retry refreshes its pending ticket and sends the transfer again") {
        val fixture = ApocalypseNetworkFixture()
        val relay = fixture.network()
        relay.enter(fixture.player).join() shouldBe true
        val before = JsonParser.parseString(requireNotNull(fixture.pending())).asJsonObject

        relay.enter(fixture.player).join() shouldBe true
        val after = JsonParser.parseString(requireNotNull(fixture.pending())).asJsonObject
        (after["nonce"].asString != before["nonce"].asString) shouldBe true
        (after["at"].asLong >= before["at"].asLong) shouldBe true
        after["origin"].asString shouldBe "spawn"
        after["host"].asString shouldBe "parkour"
        verify(exactly = 2) { fixture.transfer.connect(fixture.player, BackendServerId.of("parkour")) }
    }

    test("a valid pending ticket for another origin or host is left untouched") {
        for (raw in listOf(
            ApocalypseNetworkFixture.ticket(origin = "survival"),
            ApocalypseNetworkFixture.ticket(host = "survival"),
        )) {
            val fixture = ApocalypseNetworkFixture()
            fixture.seed(raw)
            shouldThrow<CompletionException> { fixture.network().enter(fixture.player).join() }
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

            fixture.network().enter(fixture.player).join() shouldBe false
            fixture.pending() shouldBe null
            verify(exactly = if (offline) 0 else 1) { fixture.transfer.connect(any(), any()) }
        }
    }

    test("a throwing transfer cleans its own nonce and retains the transfer failure") {
        val fixture = ApocalypseNetworkFixture()
        val failure = IllegalStateException("transfer failed")
        every { fixture.transfer.connect(any(), any()) } throws failure

        shouldThrow<CompletionException> { fixture.network().enter(fixture.player).join() }.cause shouldBe failure
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

        shouldThrow<CompletionException> { fixture.network(redis = raced).enter(fixture.player).join() }
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
            val result = fixture.network().enter(fixture.player)
            if (throws) shouldThrow<CompletionException> { result.join() } else result.join() shouldBe false
            fixture.pending() shouldBe foreign
        }
    }

    test("only the addressed receiver consumes the ticket exactly once") {
        val fixture = ApocalypseNetworkFixture()
        fixture.network().enter(fixture.player).join() shouldBe true
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

    fun network(serverId: String = "spawn", redis: RedisOperations = this.redis): ApocalypseNetwork {
        val settings = mockk<ArcEventsConfig> {
            every { this@mockk.serverId } returns serverId
            every { hostServer } returns "parkour"
            every { network } returns NetworkSettings(true, setOf("spawn", "survival", "parkour"), 300, 45, 5, 20, true, true)
        }
        return ApocalypseNetwork(redis, { settings }, transfer, ApocalypseNetworkTestWork)
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
