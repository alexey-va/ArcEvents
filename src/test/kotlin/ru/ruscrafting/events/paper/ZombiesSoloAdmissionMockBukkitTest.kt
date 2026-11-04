package ru.ruscrafting.events.paper

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.verify
import java.util.UUID
import java.util.concurrent.CompletableFuture

class ZombiesSoloAdmissionMockBukkitTest : FunSpec({
    test("solo Zombies rejects unsupported clients before joining or reserving") {
        TttRoundFixture().use { fixture ->
            fixture.clientProtocolVersion = MIN_DIALOG_PROTOCOL - 1
            val player = fixture.players.first()
            val original = player.inventory.contents.map { it?.clone() }

            fixture.service.startZombiesSolo(player).join() shouldBe ReservationStartResult.CLIENT_UNSUPPORTED

            verify(exactly = 0) { fixture.network.reserveZombiesSolo(any()) }
            verify(exactly = 0) { fixture.network.join(any()) }
            fixture.service.arcadeSnapshot() shouldBe null
            player.inventory.contents.toList() shouldBe original
        }
    }

    test("pending recovery blocks both queue admission and solo Zombies") {
        TttRoundFixture().use { fixture ->
            val player = fixture.players.first()
            fixture.escrow.commitThenMutate(UUID.randomUUID(), listOf(player), mapOf(player.uniqueId to "spawn"), 1_000L) { Unit }

            fixture.service.joinQueue(player).join() shouldBe false
            fixture.service.startZombiesSolo(player).join() shouldBe ReservationStartResult.RECOVERY_PENDING

            verify(exactly = 0) { fixture.network.join(any()) }
            verify(exactly = 0) { fixture.network.reserveZombiesSolo(any()) }
        }
    }

    test("service preserves the authoritative queue future and delegates solo through its explicit API") {
        TttRoundFixture().use { fixture ->
            val player = fixture.players.first()
            val joined = CompletableFuture<Boolean>()
            every { fixture.network.join(player) } returns joined
            every { fixture.network.reserveZombiesSolo(player) } returns CompletableFuture.completedFuture(ReservationStartResult.STARTED)

            val actual = fixture.service.joinQueue(player)
            actual shouldBe joined
            actual.isDone shouldBe false
            joined.complete(false)
            actual.join() shouldBe false
            verify(exactly = 0) { fixture.network.reserveZombiesSolo(any()) }
            fixture.service.startZombiesSolo(player).join() shouldBe ReservationStartResult.STARTED
            verify(exactly = 1) { fixture.network.reserveZombiesSolo(player) }
        }
    }
})
