package ru.ruscrafting.events.paper

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import ru.arc.paper.testing.failOnUnsupportedMockBukkitOperation
import ru.ruscrafting.events.domain.EventMode
import ru.ruscrafting.events.domain.MatchPhase
import ru.ruscrafting.events.domain.ParticipantStatus

class ArcadeRoundLifecycleMockBukkitTest : FunSpec({
    test("GunGame debug bootstrap activates the real arcade session") {
        failOnUnsupportedMockBukkitOperation {
            TttRoundFixture().use { fixture ->
                fixture.startActiveArcade(EventMode.GUN_GAME)

                fixture.service.arcadeSnapshot()?.mode shouldBe EventMode.GUN_GAME
                fixture.service.arcadeSnapshot()?.phase shouldBe MatchPhase.ACTIVE
                fixture.service.arcadeSnapshot()?.participants?.values
                    ?.all { it.status == ParticipantStatus.ALIVE } shouldBe true
                fixture.players.all { it.inventory.itemInMainHand.isEmpty.not() } shouldBe true
                fixture.escrow.pendingCount() shouldBe fixture.players.size
                fixture.service.close()
                fixture.assertOriginalPlayerStateRestored()
            }
        }
    }

    test("Disasters host runs six hazard cycles and restores the roster") {
        failOnUnsupportedMockBukkitOperation {
            TttRoundFixture().use { fixture ->
                fixture.startActiveArcade(EventMode.DISASTERS)

                fixture.advanceTime(3_000)
                repeat(6) {
                    fixture.advanceTime(5_000)
                    if (it < 5) fixture.advanceTime(3_000)
                }

                fixture.service.arcadeSnapshot()?.disasterRound shouldBe 6
                fixture.service.arcadeSnapshot()?.phase shouldBe MatchPhase.RESOLVING
                fixture.advanceTime(9_000)
                fixture.service.arcadeSnapshot() shouldBe null
                fixture.assertOriginalPlayerStateRestored()
            }
        }
    }
})
