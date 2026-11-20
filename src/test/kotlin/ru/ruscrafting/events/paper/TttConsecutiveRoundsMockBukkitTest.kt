package ru.ruscrafting.events.paper

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.verify
import org.bukkit.GameMode
import org.bukkit.Material
import org.bukkit.inventory.ItemStack
import ru.arc.paper.testing.failOnUnsupportedMockBukkitOperation
import ru.ruscrafting.events.domain.MatchPhase
import ru.ruscrafting.events.domain.ParticipantStatus
import ru.ruscrafting.events.domain.TttRole
import ru.ruscrafting.events.network.QueueEntry
import ru.ruscrafting.events.network.QueueState
import ru.ruscrafting.events.network.ReservationBatch
import java.util.UUID
import java.util.concurrent.CompletableFuture

class TttConsecutiveRoundsMockBukkitTest : FunSpec({
    test("a public session respawns spectators for unranked sparring and uses fresh roles loot and scores next round") {
        failOnUnsupportedMockBukkitOperation {
            TttRoundFixture().use { f ->
                val original = f.players.associateWith { it.location.clone() to it.inventory.itemInMainHand.clone() }
                f.reloadRuntime { it.replace("weapons:\n  enabled: false", "weapons:\n  enabled: true")
                    .replace("clear-inventory: true", "clear-inventory: false") }
                val session = f.startPublicTtt()
                val (attacker, victim) = f.players.take(2)
                val oldKnife = f.items.purchasedItem(EventItemKind.TRAITOR_BLADE, attacker, session.toString())
                attacker.inventory.setItem(3, oldKnife)
                f.finishPublicTttRound()
                val warmupId = requireNotNull(f.service.matchIdentity())

                f.service.matchState() shouldBe (session to MatchPhase.INTERMISSION)
                (warmupId == session) shouldBe false
                f.players.all { it.gameMode == GameMode.ADVENTURE } shouldBe true
                f.service.belongsToCurrentMatch(attacker.uniqueId, oldKnife) shouldBe false
                attacker.inventory.getItem(3) shouldBe null
                val report = requireNotNull(f.service.report())
                val outsider = f.addOutsider("Observer")
                f.service.shouldCancelDamage(victim.uniqueId, outsider.uniqueId, false, null) shouldBe true
                f.service.shouldCancelDamage(victim.uniqueId, attacker.uniqueId, false, null) shouldBe false
                f.service.shouldCancelDamage(victim.uniqueId, attacker.uniqueId, true, session) shouldBe true
                f.service.shouldCancelDamage(outsider.uniqueId, attacker.uniqueId, false, null) shouldBe true
                victim.health = 4.0
                f.meleeHit(attacker, victim, 40.0).isCancelled shouldBe true
                f.paper.performTicks(1)
                victim.health shouldBe 20.0
                f.service.participant(victim.uniqueId)?.status shouldBe ParticipantStatus.ALIVE
                f.service.report() shouldBe report
                f.service.qaBodies().isEmpty() shouldBe true
                verify(exactly = 4) { f.network.recordStats(any(), any()) }
                verify(exactly = 0) { f.network.returnRecoveredPlayer(any(), any()) }

                f.paper.performTicks(60)
                f.service.phase() shouldBe MatchPhase.PREPARING
                val second = requireNotNull(f.service.currentMatch())
                (second.matchId in setOf(session, warmupId)) shouldBe false
                second.sessionId shouldBe session
                second.participants.values.all { it.status == ParticipantStatus.RESERVED && it.kills == 0 && it.deaths == 0 } shouldBe true
                second.participants.values.count { it.role == TttRole.TRAITOR } shouldBe 1
                verify(exactly = 1) { f.lootSpawner.spawnAsync(match { it.matchId == second.matchId }, any(), any()) }
                f.escrow.pendingCount(session) shouldBe 4
                f.escrow.pendingCount(second.matchId) shouldBe 0
                f.service.debugAdvance()
                f.service.debugAdvance()
                f.finishPublicTttRound()
                requireNotNull(f.service.report()).matchId shouldBe second.matchId
                verify(exactly = 8) { f.network.recordStats(any(), any()) }

                f.players.forEach(f.service::leave)
                f.service.currentMatch() shouldBe null
                f.arenaPool.active() shouldBe null
                f.escrow.pendingCount() shouldBe 0
                f.players.forEach { player ->
                    player.location shouldBe original.getValue(player).first
                    player.inventory.itemInMainHand shouldBe original.getValue(player).second
                    verify(exactly = 1) { f.network.returnRecoveredPlayer(player, match { it.matchId == session }) }
                }
            }
        }
    }

    test("a below-minimum warmup accepts a new reservation and crash recovery returns each participant to their original admission") {
        failOnUnsupportedMockBukkitOperation {
            TttRoundFixture().use { f ->
                val originals = f.players.associateWith { it.inventory.itemInMainHand.clone() }
                val session = f.startPublicTtt()
                f.finishPublicTttRound()
                val left = f.players.first()
                f.service.leave(left)
                left.inventory.itemInMainHand shouldBe originals.getValue(left)
                f.paper.performTicks(120)
                f.service.phase() shouldBe MatchPhase.INTERMISSION
                f.service.tttRefill()?.seats shouldBe 1..1

                val newcomer = f.addOutsider("Newcomer")
                newcomer.inventory.setItemInMainHand(ItemStack.of(Material.APPLE, 6))
                val refill = UUID.randomUUID()
                val entry = QueueEntry(newcomer.uniqueId.toString(), newcomer.name, "spawn", state = QueueState.ARRIVED,
                    joinedAtMs = 1_787_730_000_000L, expiresAtMs = Long.MAX_VALUE, matchId = refill.toString(), destinationServer = "parkour")
                val admission = ReservationBatch(refill, listOf(entry), continuingSessionId = session)
                f.service.onReservation(admission.copy(continuingSessionId = UUID.randomUUID())) shouldBe false
                f.service.onReservation(admission) shouldBe true
                f.service.onArrival(entry) shouldBe true
                f.service.ownsActiveRoute(entry) shouldBe true
                f.paper.performTicks(20)
                f.service.phase() shouldBe MatchPhase.PREPARING
                requireNotNull(f.service.currentMatch()).recoveryId(newcomer.uniqueId) shouldBe refill
                f.escrow.pendingCount(session) shouldBe 3
                f.escrow.pendingCount(refill) shouldBe 1
                f.restartAfterCrash()
                f.paper.performTicks(2)

                f.service.currentMatch() shouldBe null
                f.escrow.pendingCount() shouldBe 0
                f.players.forEach { player ->
                    player.inventory.itemInMainHand shouldBe originals.getValue(player)
                    verify(exactly = 1) { f.network.returnRecoveredPlayer(player, match { it.matchId == session }) }
                }
                newcomer.inventory.itemInMainHand shouldBe ItemStack.of(Material.APPLE, 6)
                verify(exactly = 1) { f.network.returnRecoveredPlayer(newcomer, PlayerRecovery(refill, "spawn")) }
            }
        }
    }

    test("quit during warmup retains the origin snapshot until the same player reconnects") {
        failOnUnsupportedMockBukkitOperation {
            TttRoundFixture().use { f ->
                val player = f.players.first() as org.mockbukkit.mockbukkit.entity.PlayerMock
                val original = player.inventory.itemInMainHand.clone()
                val home = player.location.clone()
                val session = f.startPublicTtt()
                f.finishPublicTttRound()
                player.disconnect() shouldBe true
                f.paper.performTicks(80)
                f.service.phase() shouldBe MatchPhase.INTERMISSION
                f.escrow.pendingCount(session) shouldBe 4
                player.reconnect() shouldBe true
                f.paper.performTicks(2)
                player.inventory.itemInMainHand shouldBe original
                player.location shouldBe home
                f.service.isParticipant(player.uniqueId) shouldBe false
                f.escrow.pendingCount(session) shouldBe 3
                verify(exactly = 1) { f.network.returnRecoveredPlayer(player, PlayerRecovery(session, "spawn")) }
                f.service.stopByAdmin() shouldBe AdminStopResult.MATCH
                f.arenaPool.active() shouldBe null
            }
        }
    }

    test("leaving a new countdown below minimum returns the remaining players to warmup until the last exit") {
        failOnUnsupportedMockBukkitOperation {
            TttRoundFixture(disposable = true).use { f ->
                val session = f.startPublicTtt()
                f.finishPublicTttRound()
                f.paper.performTicks(60)
                f.service.debugAdvance() shouldBe DebugMutationResult.APPLIED
                f.service.phase() shouldBe MatchPhase.COUNTDOWN

                val first = f.players.first()
                f.service.leave(first)
                f.service.phase() shouldBe MatchPhase.INTERMISSION
                f.service.isParticipant(first.uniqueId) shouldBe false
                f.service.shouldCancelDamage(f.players[1].uniqueId, first.uniqueId, false, null) shouldBe true
                f.paper.performTicks(100)
                f.service.phase() shouldBe MatchPhase.INTERMISSION
                f.players.drop(1).forEach(f.service::leave)

                f.arenaPool.active() shouldBe null
                f.service.currentMatch() shouldBe null
                f.escrow.pendingCount() shouldBe 0
                f.players.forEach { player ->
                    player.inventory.contents.filterNotNull() shouldBe emptyList()
                    verify(exactly = 1) { f.network.returnRecoveredPlayer(player, match { it.matchId == session }) }
                }
            }
        }
    }
})

private fun TttRoundFixture.startPublicTtt(): UUID {
    every { network.completeReservation(any(), any()) } returns CompletableFuture.completedFuture(Unit)
    startReservation()
    players.forEach { arrive(it) shouldBe true }
    service.debugAdvance() shouldBe DebugMutationResult.APPLIED
    service.debugAdvance() shouldBe DebugMutationResult.APPLIED
    service.phase() shouldBe MatchPhase.ACTIVE
    return reservationMatchId()
}

private fun TttRoundFixture.finishPublicTttRound() {
    val traitor = playerWithRole(TttRole.TRAITOR)
    service.eliminate(traitor, players.first { it != traitor }.uniqueId)
    service.phase() shouldBe MatchPhase.INTERMISSION
}
