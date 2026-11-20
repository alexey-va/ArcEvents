package ru.ruscrafting.events.paper

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.verify
import ru.ruscrafting.events.domain.EventMode
import ru.ruscrafting.events.network.QueueEntry
import ru.ruscrafting.events.network.QueueState
import java.util.UUID
import java.util.concurrent.CompletableFuture

class FishingAdmissionMockBukkitTest : FunSpec({
    test("a transfer check only resumes the same player's displayed match without reserving") {
        TttRoundFixture().use { fixture ->
            val player = fixture.players.first()
            val id = player.uniqueId
            val matchId = UUID.randomUUID().toString()
            val initial = player.inventory.contents.map { it?.clone() }
            val route = QueueEntry(id.toString(), player.name, "spawn", EventMode.FISHING.id, QueueState.RETURN_PENDING,
                1_000, Long.MAX_VALUE, matchId, "parkour")
            fixture.service.start()
            every { fixture.network.queueEntry(id) } returns CompletableFuture.completedFuture(null)
            fixture.service.retryRoute(player, matchId).join()
            every { fixture.network.queueEntry(id) } returns CompletableFuture.completedFuture(route.copy(matchId = UUID.randomUUID().toString()))
            fixture.service.retryRoute(player, matchId).join()
            every { fixture.network.queueEntry(id) } returns CompletableFuture.completedFuture(route)
            fixture.service.independentInventoryOwner = { true }
            fixture.service.retryRoute(player, matchId).join()
            verify(exactly = 0) { fixture.network.resumeExistingRoute(any(), any(), any()) }
            fixture.service.independentInventoryOwner = { false }
            every { fixture.network.resumeExistingRoute(player, route, true) } returns ReservationStartResult.RECOVERY_PENDING
            fixture.service.retryRoute(player, matchId).join()
            verify(exactly = 1) { fixture.network.resumeExistingRoute(player, route, true) }
            verify(exactly = 0) { fixture.network.join(any(), any()) }
            verify(exactly = 0) { fixture.network.reserveNow(any(), any(), any()) }
            player.inventory.contents.toList() shouldBe initial
        }
    }
    test("unsupported direct-command and host starts cannot enter a native-dialog expedition") {
        TttRoundFixture().use { fixture ->
            fixture.prepareArcadeTerrain(EventMode.FISHING)
            fixture.clientProtocolVersion = MIN_DIALOG_PROTOCOL - 1
            val player = fixture.players.first()
            val original = player.inventory.contents.map { it?.clone() }
            fixture.service.startFishing(player).join() shouldBe ReservationStartResult.CLIENT_UNSUPPORTED
            fixture.service.startFromQueue(player, "fishing", EventMode.FISHING).join() shouldBe ReservationStartResult.CLIENT_UNSUPPORTED
            fixture.service.debugStartLocal(listOf(player), "fishing", EventMode.FISHING) shouldBe DebugMutationResult.PRECONDITION_FAILED
            fixture.service.arcadeSnapshot() shouldBe null
            player.inventory.contents.toList() shouldBe original
            verify(exactly = 0) { fixture.network.reserveNow(any(), any(), EventMode.FISHING) }
        }
    }
})
