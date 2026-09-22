package ru.ruscrafting.events.paper

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.verify
import ru.ruscrafting.events.domain.EventMode

class FishingAdmissionMockBukkitTest : FunSpec({
    test("unsupported direct-command and host starts cannot enter a native-dialog expedition") {
        TttRoundFixture().use { fixture ->
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
