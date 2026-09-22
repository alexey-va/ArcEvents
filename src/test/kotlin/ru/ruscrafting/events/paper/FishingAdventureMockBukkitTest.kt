package ru.ruscrafting.events.paper

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.spyk
import io.mockk.verify
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.block.BlockFace
import org.bukkit.damage.DamageSource
import org.bukkit.damage.DamageType
import org.bukkit.entity.FishHook
import org.bukkit.entity.Item
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Mob
import org.bukkit.entity.Player
import org.bukkit.entity.Villager
import org.bukkit.event.block.Action
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.event.entity.EntityDamageEvent.DamageCause
import org.bukkit.event.entity.CreatureSpawnEvent
import org.bukkit.event.player.PlayerFishEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.event.player.PlayerInteractEntityEvent
import org.bukkit.inventory.EquipmentSlot
import org.mockbukkit.mockbukkit.entity.PlayerMock
import ru.arc.paper.testing.failOnUnsupportedMockBukkitOperation
import ru.ruscrafting.events.domain.EventMode
import ru.ruscrafting.events.domain.FishingGear
import ru.ruscrafting.events.domain.FishingPhase
import ru.ruscrafting.events.domain.MatchPhase
import java.util.UUID
import java.util.function.Consumer

internal class FishingTestWorld(private val server: org.mockbukkit.mockbukkit.ServerMock) : org.mockbukkit.mockbukkit.world.WorldMock() {
    init { name = "arcevents_fishing_v3" }

    override fun dropItem(location: Location, stack: org.bukkit.inventory.ItemStack): Item =
        object : org.mockbukkit.mockbukkit.entity.ItemMock(server, UUID.randomUUID(), stack) {
            private var playerPickup = true
            private var mobPickup = true
            private var itemOwner: UUID? = null
            override fun canPlayerPickup() = playerPickup
            override fun setCanPlayerPickup(value: Boolean) { playerPickup = value }
            override fun canMobPickup() = mobPickup
            override fun setCanMobPickup(value: Boolean) { mobPickup = value }
            override fun getOwner() = itemOwner
            override fun setOwner(value: UUID?) { itemOwner = value }
        }.also {
            it.location = location
            server.registerEntity(it)
        }

    override fun <T : org.bukkit.entity.Entity> spawn(location: Location, type: Class<T>): T {
        if (type == org.bukkit.entity.TextDisplay::class.java) return type.cast(textDisplay(location))
        return super.spawn(location, type)
    }

    override fun <T : org.bukkit.entity.Entity> spawn(
        location: Location,
        type: Class<T>,
        function: Consumer<in T>?,
        reason: CreatureSpawnEvent.SpawnReason,
    ): T {
        if (type == org.bukkit.entity.TextDisplay::class.java) {
            val display = type.cast(textDisplay(location))
            function?.accept(display)
            return display
        }
        // MockBukkit 4.116.3 does not implement Mob.setDespawnInPeacefulOverride.
        // Keep the encounter simulation; the real Paper override is checked in live QA.
        if (Mob::class.java.isAssignableFrom(type)) return super.spawn(location, type, null, reason)
        return super.spawn(location, type, function, reason)
    }

    private fun textDisplay(location: Location) =
        object : org.mockbukkit.mockbukkit.entity.TextDisplayMock(server, UUID.randomUUID()) {
            private var billboard = org.bukkit.entity.Display.Billboard.FIXED
            private var shadowed = false
            override fun getBillboard() = billboard
            override fun setBillboard(value: org.bukkit.entity.Display.Billboard) { billboard = value }
            override fun isShadowed() = shadowed
            override fun setShadowed(value: Boolean) { shadowed = value }
        }.also {
            it.location = location
            server.registerEntity(it)
        }
}

class FishingAdventureMockBukkitTest : FunSpec({
    test("five island expedition requires defeated catches, bait, boss trophies and trader hand-in") {
        failOnUnsupportedMockBukkitOperation { TttRoundFixture().use { fixture ->
            fixture.startActiveArcade(EventMode.FISHING)
            val player = fixture.players.first() as PlayerMock
            val briefing = fixture.drainMessages(player).joinToString(" ")
            listOf("<phase>", "<seconds>", "<maximum>", "<arena>").forEach { placeholder ->
                (placeholder in briefing) shouldBe false
            }
            val adventure = requireNotNull(fixture.service.fishingAdventure(player))
            val world = player.world
            val matchId = requireNotNull(fixture.service.arcadeSnapshot()).matchId.toString()

            for (stage in FishingArenaStage.entries.indices) {
                val bagBeforeIsland = adventure.snapshot().bag.size
                repeat(3) {
                    catchAndDefeat(fixture, player, adventure)
                    adventure.snapshot().catchesOnStage shouldBe (it + 1)
                }
                adventure.snapshot().phase shouldBe FishingPhase.CASTING
                moveToTrader(player, stage)
                adventure.snapshot().bag.size shouldBe (bagBeforeIsland + 3)
                fixture.items.kind(player.inventory.getItem(4)) shouldBe EventItemKind.FISHING_CATCH_BAG

                if (stage == 0) {
                    val npc = world.entities.filterIsInstance<Villager>().single()
                    repeat(3) { remaining ->
                        player.inventory.heldItemSlot = 4
                        val fed = PlayerInteractEntityEvent(player, npc, EquipmentSlot.HAND)
                        fixture.paper.callEvent(fed)
                        fed.isCancelled shouldBe true
                        adventure.snapshot().bag.size shouldBe 2 - remaining
                    }
                    adventure.snapshot().bag.isEmpty() shouldBe true
                    fixture.items.kind(player.inventory.getItem(4)) shouldBe null
                    trade(adventure, player, "buy:KNIFE") shouldBe true
                    adventure.snapshot().equippedGear shouldBe FishingGear.KNIFE
                }

                if (stage == 1) {
                    repeat(3) { trade(adventure, player, "feed") shouldBe true }
                    trade(adventure, player, "buy:PISTOL") shouldBe true
                    adventure.snapshot().equippedGear shouldBe FishingGear.PISTOL
                    val pistol = requireNotNull(player.inventory.getItem(2))
                    val full = requireNotNull(fixture.firearms.state(pistol))
                    full.loaded shouldBe fixture.firearms.spec(full.id).magazineSize
                    player.inventory.setItem(2, fixture.firearms.updateLoaded(pistol, player, 3))
                    adventure.trade(player, adventure.snapshot().copy(), "equip:KNIFE") shouldBe false
                    trade(adventure, player, "equip:KNIFE") shouldBe true
                    trade(adventure, player, "equip:PISTOL") shouldBe true
                    fixture.firearms.state(player.inventory.getItem(2))?.loaded shouldBe 3
                    trade(adventure, player, "buy:AMMO") shouldBe true
                    fixture.firearms.reserveAmmo(player, matchId) shouldBe 32
                    trade(adventure, player, "buy:DYNAMITE") shouldBe true
                    adventure.snapshot().dynamite shouldBe 2

                    // A second tagged stack cannot be spent or silently merged into the authoritative slot.
                    player.inventory.setItem(4, fixture.items.fishingDynamite(player, matchId, 1))
                    player.inventory.heldItemSlot = 3
                    val invalidThrow = rightClick(player, player.inventory.getItem(3))
                    fixture.paper.callEvent(invalidThrow)
                    invalidThrow.isCancelled shouldBe true
                    adventure.snapshot().dynamite shouldBe 2
                    world.entities.filterIsInstance<Item>().count { fixture.items.kind(it.itemStack) == EventItemKind.FISHING_DYNAMITE } shouldBe 0
                    player.inventory.setItem(4, null)

                    val thrown = rightClick(player, player.inventory.getItem(3))
                    fixture.paper.callEvent(thrown)
                    thrown.isCancelled shouldBe true
                    adventure.snapshot().dynamite shouldBe 1
                    val dynamite = world.entities.filterIsInstance<Item>().single { fixture.items.kind(it.itemStack) == EventItemKind.FISHING_DYNAMITE }
                    dynamite.canPlayerPickup() shouldBe false
                    dynamite.canMobPickup() shouldBe false
                    dynamite.owner shouldBe player.uniqueId
                    dynamite.isPersistent shouldBe false
                    val zone = FishingArenaGenerator.fishingZone(FishingArenaStage.entries[stage])
                    val water = Location(world, zone.center.x, zone.waterSurfaceY.toDouble(), zone.center.z)
                    water.block.type = Material.WATER
                    dynamite.teleport(water)
                    fixture.advanceTime(2_600)
                    adventure.snapshot().phase shouldBe FishingPhase.CASTING
                    adventure.snapshot().catchesOnStage shouldBe 3
                    adventure.snapshot().bag.size shouldBe 1
                    world.entities.filterIsInstance<Item>().count { fixture.items.kind(it.itemStack) == EventItemKind.FISHING_DYNAMITE } shouldBe 0
                }

                moveToTrader(player, stage)
                trade(adventure, player, "bait") shouldBe true
                adventure.snapshot().bossBait shouldBe true
                castCatch(fixture, player, adventure)
                placeCatch(fixture, player, adventure)
                val boss = world.entities.filterIsInstance<LivingEntity>().single { it !is Player && it !is org.bukkit.entity.Villager }
                if (stage == 3) {
                    boss.teleport(player.location.clone().add(0.0, 20.0, 0.0))
                    fixture.advanceTime(1_000)
                    boss.location.y shouldBe FishingArenaGenerator.SPAWN_Y.toDouble()
                }
                if (stage == 1) {
                    adventure.isFirearmTarget(player, boss, "stale-match") shouldBe false
                    adventure.isFirearmTarget(fixture.players[1], boss, matchId) shouldBe false
                    player.inventory.heldItemSlot = 2
                    adventure.isFirearmTarget(player, boss, matchId) shouldBe true
                    adventure.hitByFirearm(player, boss, 100.0, matchId) shouldBe true
                } else {
                    strike(fixture, player, boss)
                }
                adventure.snapshot().phase shouldBe FishingPhase.TROPHY
                moveToTrader(player, stage)
                trade(adventure, player, "trophy") shouldBe true

                if (stage < FishingArenaStage.entries.lastIndex) {
                    adventure.snapshot().phase shouldBe FishingPhase.TRAVEL
                    val center = FishingArenaStage.entries[stage].centerX
                    player.simulatePlayerMove(Location(world, center + 0.5, 65.0, -8.5))
                    fixture.advanceTime(1_000)
                    adventure.snapshot().stage shouldBe stage + 1
                    adventure.snapshot().phase shouldBe FishingPhase.CASTING
                }
            }

            // The final hand-in completes the match and immediately closes its live adventure.
            adventure.snapshot().phase shouldBe FishingPhase.CLOSED
            adventure.snapshot().coins shouldBe 0
            adventure.snapshot().bag.isEmpty() shouldBe true
            world.entities.filterIsInstance<LivingEntity>().count { it !is Player } shouldBe 0
            world.entities.filterIsInstance<org.bukkit.entity.TextDisplay>().size shouldBe 0
            fixture.service.arcadeSnapshot()?.phase shouldBe MatchPhase.RESOLVING
            fixture.service.arcadeSnapshot()?.winners shouldBe setOf(player.uniqueId)
            fixture.advanceTime(9_000)
            fixture.escrow.pendingCount() shouldBe 0
            fixture.assertOriginalPlayerStateRestored(fixture.players.take(1))
        } }
    }

    test("held catch waits for valid ground and repeated clicks cannot duplicate its encounter") {
        failOnUnsupportedMockBukkitOperation { TttRoundFixture().use { fixture ->
            fixture.startActiveArcade(EventMode.FISHING)
            val player = fixture.players.first() as PlayerMock
            val adventure = requireNotNull(fixture.service.fishingAdventure(player))
            castCatch(fixture, player, adventure)
            val held = adventure.snapshot()
            val health = player.health
            fixture.advanceTime(5_000)
            adventure.snapshot() shouldBe held
            player.health shouldBe health
            player.world.entities.filterIsInstance<Mob>().count { it !is Villager } shouldBe 0
            fixture.paper.callEvent(rightClick(player, player.inventory.getItem(4))).isCancelled shouldBe true
            adventure.snapshot() shouldBe held

            val floor = prepareCatchFloor(player, adventure)
            floor.getRelative(0, 1, 0).type = Material.STONE
            val click = { PlayerInteractEvent(player, Action.RIGHT_CLICK_BLOCK, player.inventory.getItem(4), floor, BlockFace.UP, EquipmentSlot.HAND) }
            fixture.paper.callEvent(click()).isCancelled shouldBe true
            adventure.snapshot() shouldBe held
            floor.getRelative(0, 1, 0).type = Material.AIR
            floor.type = Material.WATER
            fixture.paper.callEvent(click()).isCancelled shouldBe true
            adventure.snapshot() shouldBe held
            floor.type = Material.STONE
            val staleClick = click()
            fixture.paper.callEvent(click()).isCancelled shouldBe true
            adventure.snapshot().phase shouldBe FishingPhase.CREATURE
            player.inventory.heldItemSlot shouldBe 1
            fixture.items.kind(player.inventory.getItem(4)) shouldBe null
            val creature = player.world.entities.filterIsInstance<Mob>().single { it !is Villager }
            creature.location.x shouldBe floor.x + 0.5
            creature.location.y shouldBe floor.y + 1.0
            fixture.paper.callEvent(staleClick)
            player.world.entities.filterIsInstance<Mob>().filter { it !is Villager }.map { it.uniqueId } shouldBe listOf(creature.uniqueId)
            strike(fixture, player, creature)
            fixture.items.kind(player.inventory.getItem(4)) shouldBe EventItemKind.FISHING_CATCH_BAG
        } }
    }

    test("stranded swimmer returns to the current island without losing the run") {
        failOnUnsupportedMockBukkitOperation { TttRoundFixture().use { fixture ->
            fixture.startActiveArcade(EventMode.FISHING)
            val player = fixture.players.first() as PlayerMock
            val water = Location(player.world, 22.5, 62.5, 0.5)
            water.block.type = Material.WATER
            player.simulatePlayerMove(water)
            fixture.advanceTime(1_000)
            player.location.x shouldBe 22.5
            fixture.advanceTime(4_100)
            player.location.x shouldBe FishingArenaGenerator.spawn(FishingArenaStage.CAMP).x
            fixture.service.arcadeSnapshot()?.phase shouldBe MatchPhase.ACTIVE
            fixture.escrow.pendingCount() shouldBe 1
        } }
    }

    test("swimmer at the water surface also returns to the island") {
        failOnUnsupportedMockBukkitOperation { TttRoundFixture().use { fixture ->
            fixture.startActiveArcade(EventMode.FISHING)
            val player = fixture.players.first() as PlayerMock
            val surface = Location(player.world, 22.5, 64.2, 0.5)
            surface.block.getRelative(0, -1, 0).type = Material.WATER
            player.simulatePlayerMove(surface)
            fixture.advanceTime(1_000)
            player.location.x shouldBe 22.5
            fixture.advanceTime(4_100)
            player.location.x shouldBe FishingArenaGenerator.spawn(FishingArenaStage.CAMP).x
            fixture.service.arcadeSnapshot()?.phase shouldBe MatchPhase.ACTIVE
        } }
    }
})

private fun catchAndDefeat(fixture: TttRoundFixture, player: PlayerMock, adventure: FishingAdventure) {
    castCatch(fixture, player, adventure)
    fixture.items.kind(player.inventory.getItem(4)) shouldBe EventItemKind.FISHING_LIVE_CATCH
    placeCatch(fixture, player, adventure)
    val creature = player.world.entities.filterIsInstance<LivingEntity>().single { it !is Player && it !is org.bukkit.entity.Villager }
    creature.location.z shouldBe 10.5
    creature.isGlowing shouldBe true
    strike(fixture, player, creature)
    fixture.items.kind(player.inventory.getItem(4)) shouldBe EventItemKind.FISHING_CATCH_BAG
}

private fun castCatch(fixture: TttRoundFixture, player: PlayerMock, adventure: FishingAdventure) {
    val stage = FishingArenaStage.entries[adventure.snapshot().stage]
    player.simulatePlayerMove(Location(player.world, stage.centerX + 0.5, 65.0, 16.5))
    player.inventory.heldItemSlot = 0
    val zone = FishingArenaGenerator.fishingZone(stage)
    val water = Location(player.world, zone.center.x, zone.waterSurfaceY.toDouble(), zone.center.z)
    water.block.type = Material.WATER
    val hook = mockk<FishHook>(relaxed = true)
    every { hook.uniqueId } returns UUID.randomUUID()
    every { hook.location } returns water
    fixture.paper.callEvent(PlayerFishEvent(player, null, hook, EquipmentSlot.HAND, PlayerFishEvent.State.FISHING))
    adventure.snapshot().phase shouldBe FishingPhase.BITE
    val drop = mockk<Item>(relaxed = true)
    val caught = PlayerFishEvent(player, drop, hook, EquipmentSlot.HAND, PlayerFishEvent.State.CAUGHT_FISH).apply { expToDrop = 5 }
    fixture.paper.callEvent(caught)
    caught.isCancelled shouldBe true
    caught.expToDrop shouldBe 0
    verify(exactly = 1) { drop.remove() }
    adventure.snapshot().phase shouldBe FishingPhase.CAUGHT
    player.inventory.heldItemSlot shouldBe 4
}

private fun prepareCatchFloor(player: PlayerMock, adventure: FishingAdventure): org.bukkit.block.Block {
    val stage = FishingArenaStage.entries[adventure.snapshot().stage]
    player.simulatePlayerMove(Location(player.world, stage.centerX + 0.5, 65.0, 12.5))
    val floor = spyk(player.world.getBlockAt(stage.centerX, 64, 10))
    // MockBukkit does not implement native collision bounds. This fixture is a full stone cube.
    every { floor.boundingBox } returns org.bukkit.util.BoundingBox(
        floor.x.toDouble(), 64.0, 10.0, floor.x + 1.0, 65.0, 11.0,
    )
    floor.type = Material.STONE
    for (x in -1..1) for (z in -1..1) for (y in 1..3) {
        val clearance = spyk(player.world.getBlockAt(floor.x + x, floor.y + y, floor.z + z))
        clearance.type = Material.AIR
        every { clearance.isPassable } answers { clearance.type.isAir }
        every { floor.getRelative(x, y, z) } returns clearance
    }
    return floor
}

private fun placeCatch(fixture: TttRoundFixture, player: PlayerMock, adventure: FishingAdventure) {
    val floor = prepareCatchFloor(player, adventure)
    val boss = requireNotNull(adventure.snapshot().encounter).boss
    val click = PlayerInteractEvent(player, Action.RIGHT_CLICK_BLOCK, player.inventory.getItem(4), floor, BlockFace.UP, EquipmentSlot.HAND)
    fixture.paper.callEvent(click).isCancelled shouldBe true
    adventure.snapshot().phase shouldBe if (boss) FishingPhase.BOSS else FishingPhase.CREATURE
}

private fun strike(fixture: TttRoundFixture, player: PlayerMock, creature: LivingEntity) {
    val adventure = requireNotNull(fixture.service.fishingAdventure(player))
    if (adventure.snapshot().equippedGear.firearmId != null) {
        player.inventory.heldItemSlot = 2
        adventure.hitByFirearm(
            player,
            creature,
            100.0,
            requireNotNull(fixture.service.arcadeSnapshot()).matchId.toString(),
        ) shouldBe true
    } else {
        player.inventory.heldItemSlot = 1
        fixture.paper.callEvent(fishingHit(player, creature, 100.0)).isCancelled shouldBe true
    }
}

private fun moveToTrader(player: PlayerMock, stageIndex: Int) {
    val center = FishingArenaStage.entries[stageIndex].centerX
    player.simulatePlayerMove(Location(player.world, center + 8.0, 65.0, 1.5))
}

private fun trade(adventure: FishingAdventure, player: Player, action: String): Boolean =
    adventure.trade(player, adventure.snapshot(), action)

private fun rightClick(player: PlayerMock, item: org.bukkit.inventory.ItemStack?) =
    PlayerInteractEvent(player, Action.RIGHT_CLICK_AIR, item, null, BlockFace.SELF, EquipmentSlot.HAND)

@Suppress("DEPRECATION")
private fun fishingHit(player: Player, creature: LivingEntity, damage: Double): EntityDamageByEntityEvent =
    EntityDamageByEntityEvent(
        player,
        creature,
        DamageCause.ENTITY_ATTACK,
        DamageSource.builder(DamageType.PLAYER_ATTACK).withCausingEntity(player).withDirectEntity(player).build(),
        damage,
    )
