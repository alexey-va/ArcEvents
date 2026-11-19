package ru.ruscrafting.events.paper

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.verify
import org.bukkit.Material
import org.bukkit.inventory.ItemStack
import org.mockbukkit.mockbukkit.entity.PlayerMock
import ru.ruscrafting.events.domain.EventMode

class PrivatePracticeRecoveryTest : FunSpec({
    for ((origin, mode) in listOf("parkour" to EventMode.TTT, "survival" to EventMode.GUN_GAME)) {
        test("disposable host recovers the seeded practice inventory and $origin return after restart") {
            TttRoundFixture(disposable = true).use { fixture ->
                val owner = fixture.players.first() as PlayerMock
                val home = owner.location.clone()
                val inventory = owner.inventory
                val emeralds = ItemStack.of(Material.EMERALD, 27)
                val boots = ItemStack.of(Material.DIAMOND_BOOTS)
                val bread = ItemStack.of(Material.BREAD, 9)
                inventory.setItem(12, emeralds.clone())
                inventory.boots = boots.clone()
                inventory.setItemInOffHand(bread.clone())
                inventory.heldItemSlot = 5

                val batch = fixture.startPracticeReservation(mode, origin)
                fixture.arrive(owner) shouldBe true
                fixture.escrow.pendingPlayers(batch.matchId) shouldBe setOf(owner.uniqueId)
                inventory.getItem(12) shouldBe null
                inventory.setItem(17, ItemStack.of(Material.PAPER)) // Temporary event item.

                if (origin == "survival") owner.disconnect() shouldBe true
                fixture.restartAfterCrash()
                fixture.service.matchIdentity() shouldBe null
                fixture.escrow.pendingPlayers(batch.matchId) shouldBe setOf(owner.uniqueId)
                if (origin == "survival") owner.reconnect() shouldBe true
                fixture.paper.performTicks(2)

                inventory.getItem(12) shouldBe emeralds
                inventory.boots shouldBe boots
                inventory.itemInOffHand shouldBe bread
                inventory.heldItemSlot shouldBe 5
                inventory.getItem(17) shouldBe null
                owner.location shouldBe home
                fixture.escrow.pendingPlayers() shouldBe emptySet()
                val recovery = PlayerRecovery(batch.matchId, origin)
                verify(exactly = 1) { fixture.network.returnRecoveredPlayer(owner, recovery) }

                inventory.setItem(12, ItemStack.of(Material.COPPER_INGOT))
                fixture.service.handleJoin(owner)
                fixture.paper.performTicks(2)
                inventory.getItem(12)?.type shouldBe Material.COPPER_INGOT
                verify(exactly = 1) { fixture.network.returnRecoveredPlayer(owner, recovery) }
            }
        }
    }
})
