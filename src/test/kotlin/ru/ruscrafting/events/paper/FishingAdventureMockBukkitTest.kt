package ru.ruscrafting.events.paper

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.damage.DamageSource
import org.bukkit.damage.DamageType
import org.bukkit.entity.FishHook
import org.bukkit.entity.Item
import org.bukkit.entity.Mob
import org.bukkit.entity.Player
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.event.entity.EntityDamageEvent.DamageCause
import org.bukkit.event.player.PlayerFishEvent
import org.bukkit.inventory.EquipmentSlot
import org.mockbukkit.mockbukkit.entity.PlayerMock
import ru.arc.paper.testing.failOnUnsupportedMockBukkitOperation
import ru.ruscrafting.events.domain.EventMode
import ru.ruscrafting.events.domain.MatchPhase
import java.util.UUID

/** Only the two unsupported mob policy flags are supplied by this fixture. */
internal class FishingTestWorld(private val server: org.mockbukkit.mockbukkit.ServerMock) :
    org.mockbukkit.mockbukkit.world.WorldMock() {
    init { name = "arcevents_fishing" }

    override fun <T : org.bukkit.entity.Entity> spawn(location: Location, type: Class<T>): T {
        val mob = when (type) {
            org.bukkit.entity.Drowned::class.java -> object : org.mockbukkit.mockbukkit.entity.DrownedMock(server, UUID.randomUUID()) {
                private var removeFar = true
                private var pickup = true
                override fun setRemoveWhenFarAway(value: Boolean) { removeFar = value }
                override fun getRemoveWhenFarAway() = removeFar
                override fun setCanPickupItems(value: Boolean) { pickup = value }
                override fun getCanPickupItems() = pickup
            }
            org.bukkit.entity.ElderGuardian::class.java -> object : org.mockbukkit.mockbukkit.entity.ElderGuardianMock(server, UUID.randomUUID()) {
                private var removeFar = true
                private var pickup = true
                override fun setRemoveWhenFarAway(value: Boolean) { removeFar = value }
                override fun getRemoveWhenFarAway() = removeFar
                override fun setCanPickupItems(value: Boolean) { pickup = value }
                override fun getCanPickupItems() = pickup
            }
            else -> return super.spawn(location, type)
        }
        mob.location = location
        server.registerEntity(mob)
        return type.cast(mob)
    }
}

class FishingAdventureMockBukkitTest : FunSpec({
    test("nine native catches and owned fights complete three islands without vanilla loot") {
        failOnUnsupportedMockBukkitOperation {
            TttRoundFixture().use { fixture ->
                fixture.startActiveArcade(EventMode.FISHING)
                val player = fixture.players.first() as PlayerMock
                val world = player.world
                for (stage in 0..2) {
                    val center = stage * 48
                    for (catch in 1..3) {
                        player.simulatePlayerMove(Location(world, center + 0.5, 65.0, 12.5))
                        player.inventory.heldItemSlot = 0
                        val water = Location(world, center + 0.5, 63.0, 15.5)
                        water.block.type = Material.WATER
                        val hook = mockk<FishHook>(relaxed = true)
                        every { hook.uniqueId } returns UUID.randomUUID()
                        every { hook.location } returns water
                        val cast = PlayerFishEvent(player, null, hook, EquipmentSlot.HAND, PlayerFishEvent.State.FISHING)
                        fixture.paper.callEvent(cast)
                        cast.isCancelled shouldBe false
                        val drop = mockk<Item>(relaxed = true)
                        val caught = PlayerFishEvent(player, drop, hook, PlayerFishEvent.State.CAUGHT_FISH).apply { expToDrop = 5 }
                        fixture.paper.callEvent(caught)
                        caught.isCancelled shouldBe true
                        caught.expToDrop shouldBe 0
                        verify(exactly = 1) { drop.remove() }
                        val mob = world.entities.filterIsInstance<Mob>().single()
                        val health = mob.health
                        fixture.paper.callEvent(fishingHit(fixture.players[1], mob, 100.0)).isCancelled shouldBe true
                        mob.health shouldBe health
                        player.inventory.heldItemSlot = 1
                        fixture.paper.callEvent(fishingHit(player, mob, 100.0)).isCancelled shouldBe true
                        world.entities.filterIsInstance<Mob>().isEmpty() shouldBe true
                    }
                    if (stage < 2) {
                        player.simulatePlayerMove(Location(world, center + 0.5, 65.0, -8.5))
                        fixture.advanceTime(1_000)
                        player.location.x shouldBe center + 48.5
                    }
                }
                fixture.service.arcadeSnapshot()?.phase shouldBe MatchPhase.RESOLVING
                fixture.service.arcadeSnapshot()?.winners shouldBe setOf(player.uniqueId)
                fixture.advanceTime(9_000)
                fixture.escrow.pendingCount() shouldBe 0
                fixture.assertOriginalPlayerStateRestored(fixture.players.take(1))
            }
        }
    }
})

@Suppress("DEPRECATION")
private fun fishingHit(player: Player, mob: Mob, damage: Double): EntityDamageByEntityEvent =
    EntityDamageByEntityEvent(player, mob, DamageCause.ENTITY_ATTACK,
        DamageSource.builder(DamageType.PLAYER_ATTACK).withCausingEntity(player).withDirectEntity(player).build(), damage)
