package ru.ruscrafting.events.paper

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import org.bukkit.entity.Entity
import org.bukkit.entity.Player
import ru.arc.paper.testing.failOnUnsupportedMockBukkitOperation
import ru.ruscrafting.events.domain.EventMode
import ru.ruscrafting.events.domain.FirearmId
import ru.ruscrafting.events.domain.MatchEndReason
import ru.ruscrafting.events.domain.MatchPhase
import ru.ruscrafting.events.domain.ParticipantStatus
import ru.ruscrafting.events.network.QueueEntry
import java.util.UUID

class ArcadeRoundLifecycleMockBukkitTest : FunSpec({
    test("delayed final recovery releases the completed arcade arena and admits the next match") {
        failOnUnsupportedMockBukkitOperation {
            TttRoundFixture().use { fixture ->
                val completions = linkedMapOf<UUID, (Result<PlayerRecovery?>) -> Unit>()
                val session = fixture.arcadeWithRecovery { player, complete -> completions[player.uniqueId] = complete }
                val id = UUID.randomUUID()
                requireNotNull(fixture.arenaPool.reserve(id, "test", EventMode.GUN_GAME))
                val entries = fixture.players.map { player ->
                    QueueEntry(player.uniqueId.toString(), player.name, "parkour", EventMode.GUN_GAME.id,
                        joinedAtMs = 1L, expiresAtMs = 60_001L)
                }
                session.start(id, EventMode.GUN_GAME, entries, fixture.players,
                    fixture.currentSettings().arcade.rules(EventMode.GUN_GAME)).join()
                session.cancel(MatchEndReason.ADMIN)
                session.current?.phase shouldBe MatchPhase.RESTORING
                completions.size shouldBe fixture.players.size
                fixture.arenaPool.reserve(UUID.randomUUID(), "test") shouldBe null

                fixture.players.dropLast(1).forEach { player ->
                    completions.remove(player.uniqueId)!!.invoke(Result.success(fixture.escrow.recover(player)))
                }
                session.tick()
                session.current?.phase shouldBe MatchPhase.RESTORING
                fixture.escrow.pendingCount(id) shouldBe 1
                fixture.arenaPool.inUse("test") shouldBe true

                val last = fixture.players.last()
                completions.remove(last.uniqueId)!!.invoke(Result.success(fixture.escrow.recover(last)))
                session.current?.phase shouldBe MatchPhase.COMPLETED
                fixture.escrow.pendingCount(id) shouldBe 0
                session.tick()
                session.current shouldBe null
                fixture.arenaPool.active() shouldBe null
                fixture.assertOriginalPlayerStateRestored()

                fixture.startReservation()
                fixture.service.matchState().first shouldBe fixture.reservationMatchId()
            }
        }
    }

    test("fishing owns one player and restores original state after defeat") {
        failOnUnsupportedMockBukkitOperation {
            TttRoundFixture().use { fixture ->
                fixture.startActiveArcade(EventMode.FISHING)
                val player = fixture.players.first()
                fixture.service.arcadeSnapshot()?.participants?.size shouldBe 1
                fixture.escrow.pendingCount() shouldBe 1
                fixture.items.kind(player.inventory.itemInMainHand) shouldBe EventItemKind.FISHING_ROD
                fixture.service.shouldCancelDamage(player.uniqueId, fixture.players[1].uniqueId, false, null) shouldBe true
                fixture.service.eliminate(player)
                fixture.service.arcadeSnapshot()?.phase shouldBe MatchPhase.RESOLVING
                fixture.service.arcadeSnapshot()?.winners shouldBe emptySet()
                fixture.advanceTime(9_000)
                fixture.service.arcadeSnapshot() shouldBe null
                fixture.escrow.pendingCount() shouldBe 0
                fixture.assertOriginalPlayerStateRestored(fixture.players.take(1))
            }
        }
    }

    test("fishing shutdown restores its sole player without touching outsiders") {
        failOnUnsupportedMockBukkitOperation {
            TttRoundFixture().use { fixture ->
                fixture.startActiveArcade(EventMode.FISHING)
                fixture.service.close()
                fixture.escrow.pendingCount() shouldBe 0
                fixture.assertOriginalPlayerStateRestored(fixture.players.take(1))
            }
        }
    }

    test("solo disconnect retains escrow until departure completes") {
        failOnUnsupportedMockBukkitOperation {
            TttRoundFixture().use { fixture ->
                fixture.startActiveArcade(EventMode.FISHING)
                val player = fixture.players.first()
                fixture.service.handleQuit(player)
                fixture.escrow.pendingCount() shouldBe 1
                fixture.service.arcadeSnapshot()?.phase shouldBe MatchPhase.CANCELLED
            }
        }
    }

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

    test("two delayed hits from one GunGame weapon cannot skip the next weapon") {
        failOnUnsupportedMockBukkitOperation {
            val shots = mockk<FirearmShots>(relaxed = true)
            val impact = slot<(Entity, FirearmHit, Double) -> Unit>()
            every { shots.fire(any(), any(), any(), any(), any(), any(), any(), capture(impact)) } returns true
            TttRoundFixture(shots = shots).use { fixture ->
                fixture.reloadRuntime { it.replace("weapons:\n  enabled: false", "weapons:\n  enabled: true") }
                fixture.startActiveArcade(EventMode.GUN_GAME)
                fixture.advanceTime(3_000)
                val shooter = fixture.players.first()
                fixture.service.useFirearm(shooter) shouldBe true
                for (opponent in fixture.players.drop(1).take(2)) {
                    val target = mockk<Player>(relaxed = true)
                    every { target.uniqueId } returns opponent.uniqueId
                    every { target.location } returns opponent.location
                    every { target.inventory } returns opponent.inventory
                    every { target.health } returns opponent.health
                    every { target.isOnline } returns true
                    every { target.damage(any<Double>(), shooter) } answers {
                        fixture.meleeHit(shooter, target, firstArg()).isCancelled shouldBe true
                    }
                    impact.captured(target, FirearmHit(opponent.eyeLocation.toVector(), false), 40.0)
                }

                fixture.paper.performTicks(1)
                val attacker = requireNotNull(fixture.service.arcadeSnapshot()?.participants?.get(shooter.uniqueId))
                attacker.kills shouldBe 2
                attacker.stage shouldBe 1
                fixture.firearms.state(shooter.inventory.itemInMainHand)?.id shouldBe FirearmId.REVOLVER
            }
        }
    }

    test("a restored Disasters player can take ordinary damage while the match continues") {
        failOnUnsupportedMockBukkitOperation {
            TttRoundFixture().use { fixture ->
                fixture.startActiveArcade(EventMode.DISASTERS)
                val player = fixture.players.first()
                fixture.service.leave(player)

                fixture.service.phase() shouldBe MatchPhase.ACTIVE
                fixture.service.arcadeSnapshot()?.participants?.get(player.uniqueId)?.status shouldBe ParticipantStatus.RESTORED
                fixture.service.shouldCancelDamage(player.uniqueId, null, false, null) shouldBe false
                fixture.service.shouldCancelDamage(player.uniqueId, fixture.players[1].uniqueId, false, null) shouldBe true
            }
        }
    }

    test("Disasters activation skips a disconnected participant whose recovery is pending") {
        failOnUnsupportedMockBukkitOperation {
            TttRoundFixture().use { fixture ->
                fixture.startPreparingRound(EventMode.DISASTERS).join() shouldBe DebugMutationResult.APPLIED
                val departing = fixture.players.first()
                fixture.service.handleQuit(departing)
                departing.health = 7.0

                fixture.advanceTime(1_000)

                fixture.service.phase() shouldBe MatchPhase.ACTIVE
                fixture.service.arcadeSnapshot()?.participants?.get(departing.uniqueId)?.status shouldBe ParticipantStatus.DISCONNECTED
                fixture.escrow.pendingCount() shouldBe fixture.players.size
                departing.health shouldBe 7.0
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
