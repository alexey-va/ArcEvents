package ru.ruscrafting.events.paper

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.bukkit.Material
import org.bukkit.entity.Item
import org.bukkit.event.player.PlayerDropItemEvent
import org.bukkit.inventory.ItemStack
import ru.ruscrafting.events.domain.EventMode
import ru.ruscrafting.events.domain.MatchPhase
import ru.ruscrafting.events.domain.TttRole
import java.util.concurrent.CompletableFuture

class SoloPracticeMatchTest : FunSpec({
    test("only an administrator can request practice and adventure clients are checked before routing") {
        TttRoundFixture().use { fixture ->
            val owner = fixture.players.first()
            fixture.service.startSoloTest(owner, EventMode.TTT).join() shouldBe ReservationStartResult.NOT_OWNER
            owner.isOp = true
            fixture.clientProtocolVersion = MIN_DIALOG_PROTOCOL - 1
            fixture.service.startSoloTest(owner, EventMode.FISHING).join() shouldBe ReservationStartResult.CLIENT_UNSUPPORTED
            fixture.service.startSoloTest(owner, EventMode.ZOMBIES).join() shouldBe ReservationStartResult.CLIENT_UNSUPPORTED
            verify(exactly = 0) { fixture.network.reservePractice(any(), any()) }
            every { fixture.network.reservePractice(owner, EventMode.TTT) } returns CompletableFuture.completedFuture(ReservationStartResult.STARTED)
            fixture.service.startSoloTest(owner, EventMode.TTT).join() shouldBe ReservationStartResult.STARTED
            verify(exactly = 0) { fixture.network.join(any(), any()) }
        }
    }

    test("a TTT practice arrival creates hidden opposing roles and reserves and restores only its owner") {
        TttRoundFixture().use { fixture ->
            val owner = fixture.players.first()
            fixture.reloadRuntime { it.replace("clear-inventory: true", "clear-inventory: false") }
            owner.inventory.setItem(12, ItemStack(Material.ENDER_PEARL, 3))
            val inventory = owner.inventory.contents.map { it?.clone() }
            val batch = fixture.startPracticeReservation(EventMode.TTT)
            owner.isOp = false
            fixture.arrive(owner) shouldBe false
            owner.inventory.contents.toList() shouldBe inventory
            owner.isOp = true
            fixture.arrive(owner) shouldBe true
            val match = requireNotNull(fixture.service.currentMatch())
            owner.inventory.getItem(12)?.takeUnless { it.isEmpty } shouldBe null
            match.phase shouldBe MatchPhase.PREPARING
            match.participants.size shouldBe 4
            match.participants.values.count { it.role == TttRole.TRAITOR } shouldBe 1
            match.participants.values.count { it.role == TttRole.DETECTIVE } shouldBe 1
            fixture.players.drop(1).any { fixture.service.isParticipant(it.uniqueId) } shouldBe false
            val guide = requireNotNull(owner.inventory.getItem(8))
            val looseGuide = mockk<Item> { every { itemStack } returns guide }
            fixture.service.isForeignPracticeLoot(fixture.players[1].uniqueId, looseGuide) shouldBe true
            fixture.service.isForeignPracticeLoot(owner.uniqueId, looseGuide) shouldBe false
            val dropped = mockk<Item> { every { itemStack } returns ItemStack(Material.DIAMOND) }
            fixture.paper.callEvent(PlayerDropItemEvent(owner, dropped)).isCancelled shouldBe true
            fixture.escrow.pendingPlayers(batch.matchId) shouldBe setOf(owner.uniqueId)
            verify(exactly = 1) { fixture.network.completeReservation(batch.matchId, listOf(owner.uniqueId)) }
            fixture.service.leave(owner)
            owner.inventory.contents.toList() shouldBe inventory
            fixture.service.matchIdentity() shouldBe null
            verify(exactly = 0) { fixture.network.recordStats(any(), any()) }
        }
    }

    test("Gun Game practice reserves one human with one rival and can be left before the countdown") {
        TttRoundFixture().use { fixture ->
            val owner = fixture.players.first()
            val batch = fixture.startPracticeReservation(EventMode.GUN_GAME)
            fixture.arrive(owner) shouldBe true
            val match = requireNotNull(fixture.service.arcadeSnapshot())
            match.participants.size shouldBe 2
            match.participants.values.single { it.playerId != owner.uniqueId }.playerName shouldBe "Bot_Kedr"
            verify(exactly = 1) { fixture.network.completeReservation(batch.matchId, listOf(owner.uniqueId)) }
            fixture.service.leave(owner)
            fixture.advanceTime(1000)
            fixture.service.matchIdentity() shouldBe null
            verify(exactly = 0) { fixture.network.recordStats(any(), any()) }
        }
    }
})
