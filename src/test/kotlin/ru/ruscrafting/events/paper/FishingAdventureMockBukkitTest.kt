package ru.ruscrafting.events.paper

import io.kotest.core.spec.style.FunSpec
import io.papermc.paper.event.player.PrePlayerAttackEntityEvent
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.spyk
import io.mockk.verify
import org.bukkit.FluidCollisionMode
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.block.BlockFace
import org.bukkit.damage.DamageSource
import org.bukkit.damage.DamageType
import org.bukkit.entity.Entity
import org.bukkit.entity.EntityType
import org.bukkit.entity.FishHook
import org.bukkit.entity.Interaction
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
import org.bukkit.event.player.PlayerInteractAtEntityEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.event.player.PlayerInteractEntityEvent
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.util.RayTraceResult
import org.bukkit.util.Vector
import org.mockbukkit.mockbukkit.entity.CodMock
import org.mockbukkit.mockbukkit.entity.DrownedMock
import org.mockbukkit.mockbukkit.entity.ElderGuardianMock
import org.mockbukkit.mockbukkit.entity.GuardianMock
import org.mockbukkit.mockbukkit.entity.InteractionMock
import org.mockbukkit.mockbukkit.entity.PhantomMock
import org.mockbukkit.mockbukkit.entity.PlayerMock
import org.mockbukkit.mockbukkit.entity.PufferFishMock
import org.mockbukkit.mockbukkit.entity.SalmonMock
import org.mockbukkit.mockbukkit.entity.SilverfishMock
import org.mockbukkit.mockbukkit.entity.SpiderMock
import org.mockbukkit.mockbukkit.entity.TropicalFishMock
import ru.arc.paper.testing.failOnUnsupportedMockBukkitOperation
import ru.ruscrafting.events.domain.EventMode
import ru.ruscrafting.events.domain.FishingGear
import ru.ruscrafting.events.domain.FishingPhase
import ru.ruscrafting.events.domain.MatchPhase
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.function.Consumer

internal class FishingTestWorld(private val server: org.mockbukkit.mockbukkit.ServerMock) : org.mockbukkit.mockbukkit.world.WorldMock() {
    init { name = "arcevents_fishing_archipelago_v2" }

    var passageReady: CompletableFuture<Unit> = CompletableFuture.completedFuture(Unit)

    // MockBukkit lacks the block collision ray. Supply the Paper result for a clear or obstructed swing.
    var meleeObstruction: RayTraceResult? = null
    val hurtAnimations = mutableListOf<UUID>()

    override fun rayTraceBlocks(start: Location, direction: Vector, maxDistance: Double,
        fluidCollisionMode: FluidCollisionMode, ignorePassableBlocks: Boolean): RayTraceResult? = meleeObstruction

    // MockBukkit has no async chunk loader. Tests can withhold the native completion.
    override fun getChunkAtAsync(x: Int, z: Int, generate: Boolean, urgent: Boolean): CompletableFuture<org.bukkit.Chunk> {
        check(generate)
        return passageReady.thenApply { getChunkAt(x, z) }
    }

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
        if (type == Interaction::class.java) {
            // MockBukkit stores the dimensions but does not apply them to the AABB.
            val target = object : InteractionMock(server, UUID.randomUUID()) {
                override fun getWidth() = interactionWidth.toDouble()
                override fun getHeight() = interactionHeight.toDouble()
            }
            target.location = location
            function?.accept(type.cast(target))
            server.registerEntity(target)
            return type.cast(target)
        }
        if (Mob::class.java.isAssignableFrom(type) && type != Villager::class.java) {
            // Only the unimplemented native hurt packet is replaced. Each species keeps its own MockBukkit body.
            val id = UUID.randomUUID()
            fun hurt() { hurtAnimations += id }
            val mob = when (type) {
                EntityType.SILVERFISH.entityClass -> object : SilverfishMock(server, id) { override fun playHurtAnimation(yaw: Float) = hurt() }
                EntityType.SPIDER.entityClass -> object : SpiderMock(server, id) { override fun playHurtAnimation(yaw: Float) = hurt() }
                EntityType.COD.entityClass -> object : CodMock(server, id) { override fun playHurtAnimation(yaw: Float) = hurt() }
                EntityType.SALMON.entityClass -> object : SalmonMock(server, id) { override fun playHurtAnimation(yaw: Float) = hurt() }
                EntityType.DROWNED.entityClass -> object : DrownedMock(server, id) { override fun playHurtAnimation(yaw: Float) = hurt() }
                EntityType.PUFFERFISH.entityClass -> object : PufferFishMock(server, id) { override fun playHurtAnimation(yaw: Float) = hurt() }
                EntityType.TROPICAL_FISH.entityClass -> object : TropicalFishMock(server, id) { override fun playHurtAnimation(yaw: Float) = hurt() }
                EntityType.GUARDIAN.entityClass -> object : GuardianMock(server, id) { override fun playHurtAnimation(yaw: Float) = hurt() }
                EntityType.ELDER_GUARDIAN.entityClass -> object : ElderGuardianMock(server, id) { override fun playHurtAnimation(yaw: Float) = hurt() }
                EntityType.PHANTOM.entityClass -> object : PhantomMock(server, id) { override fun playHurtAnimation(yaw: Float) = hurt() }
                else -> error("Unsupported fishing test mob ${type.name}")
            }
            mob.location = location
            server.registerEntity(mob)
            return type.cast(mob)
        }
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
                    val hitbox = world.entities.filterIsInstance<Interaction>().single()
                    player.inventory.heldItemSlot = 3
                    fixture.paper.callEvent(PlayerInteractAtEntityEvent(player, hitbox, Vector(), EquipmentSlot.HAND))
                    adventure.snapshot().dynamite shouldBe 1
                    fixture.paper.callEvent(PlayerInteractEntityEvent(player, hitbox, EquipmentSlot.HAND)).isCancelled shouldBe true
                    adventure.snapshot().dynamite shouldBe 0
                    adventure.isFirearmTarget(player, boss, "stale-match") shouldBe false
                    adventure.isFirearmTarget(fixture.players[1], boss, matchId) shouldBe false
                    player.inventory.heldItemSlot = 2
                    adventure.isFirearmTarget(player, boss, matchId) shouldBe true
                    adventure.isFirearmTarget(player, hitbox, matchId) shouldBe true
                    fixture.paper.callEvent(PlayerInteractEntityEvent(player, hitbox, EquipmentSlot.HAND)).isCancelled shouldBe true
                    val edge = hitbox.boundingBox.center.setX(hitbox.boundingBox.maxX)
                    val fishingWorld = world as FishingTestWorld
                    fishingWorld.meleeObstruction = RayTraceResult(edge)
                    adventure.hitByFirearm(player, hitbox, 100.0, matchId, edge) shouldBe false
                    fishingWorld.meleeObstruction = null
                    adventure.hitByFirearm(player, hitbox, 100.0, matchId, edge) shouldBe true
                    adventure.hitByFirearm(player, hitbox, 100.0, matchId, edge) shouldBe false
                } else {
                    strike(fixture, player, boss)
                }
                adventure.snapshot().phase shouldBe FishingPhase.TROPHY
                moveToTrader(player, stage)
                trade(adventure, player, "trophy") shouldBe true

                if (stage < FishingArenaStage.entries.lastIndex) {
                    adventure.snapshot().phase shouldBe FishingPhase.TRAVEL
                    val center = FishingArenaStage.entries[stage].centerX
                    val fishingWorld = world as FishingTestWorld
                    if (stage == 0) fishingWorld.passageReady = CompletableFuture()
                    player.simulatePlayerMove(Location(world, center + 0.5, 65.0, -8.5))
                    fixture.advanceTime(1_000)
                    if (stage == 0) {
                        adventure.snapshot().stage shouldBe 0
                        player.location.x shouldBe center + 0.5
                        fishingWorld.passageReady.complete(Unit)
                    }
                    player.location.x shouldBe FishingArenaStage.entries[stage + 1].centerX + 0.5
                    adventure.snapshot().stage shouldBe stage + 1
                    adventure.snapshot().phase shouldBe FishingPhase.CASTING
                }
            }

            // The final hand-in completes the match and immediately closes its live adventure.
            adventure.snapshot().phase shouldBe FishingPhase.CLOSED
            adventure.snapshot().coins shouldBe 0
            adventure.snapshot().bag.isEmpty() shouldBe true
            world.entities.filterIsInstance<LivingEntity>().count { it !is Player } shouldBe 0
            world.entities.filterIsInstance<Interaction>().size shouldBe 0
            world.entities.filterIsInstance<org.bukkit.entity.TextDisplay>().size shouldBe 0
            fixture.packetVisuals.isNotEmpty() shouldBe true
            fixture.packetVisuals.none { it.isValid } shouldBe true
            verify(exactly = 0) { fixture.packetDisplays.close(); fixture.packetDisplays.closeAsync() }
            fixture.service.arcadeSnapshot()?.phase shouldBe MatchPhase.RESOLVING
            fixture.service.arcadeSnapshot()?.winners shouldBe setOf(player.uniqueId)
            fixture.advanceTime(9_000)
            fixture.escrow.pendingCount() shouldBe 0
            fixture.assertOriginalPlayerStateRestored(fixture.players.take(1))
        } }
    }

    test("the smaller catch has a wider target and gives hurt feedback once per visible accepted hit") {
        failOnUnsupportedMockBukkitOperation { TttRoundFixture().use { fixture ->
            fixture.reloadRuntime { it.replace("creature-health: 4.0", "creature-health: 12.0").replace("boss-health: 4.0", "boss-health: 12.0") }
            fixture.startActiveArcade(EventMode.FISHING)
            val player = fixture.players.first() as PlayerMock
            val adventure = requireNotNull(fixture.service.fishingAdventure(player))
            castCatch(fixture, player, adventure)
            placeCatch(fixture, player, adventure)
            val world = player.world as FishingTestWorld
            val creature = world.entities.filterIsInstance<org.bukkit.entity.Silverfish>().single()
            val hitbox = world.entities.filterIsInstance<Interaction>().single()
            // MockBukkit does not register SCALE on silverfish. The native Fishing case checks its client value.
            hitbox.interactionWidth shouldBe 1.8f
            hitbox.interactionHeight shouldBe 1.35f
            hitbox.isResponsive shouldBe true
            fun hurtSounds() = player.heardSounds.count { it.sound == "entity.cod.hurt" }
            val close = player.location
            player.simulatePlayerMove(close.clone().add(0.0, 0.0, 4.0))
            fixture.paper.callEvent(PrePlayerAttackEntityEvent(player, creature, true))
            adventure.snapshot().creatureHealth shouldBe 12.0
            player.simulatePlayerMove(close)
            player.inventory.heldItemSlot = 0
            fixture.paper.callEvent(PrePlayerAttackEntityEvent(player, creature, true))
            adventure.snapshot().creatureHealth shouldBe 12.0
            player.inventory.heldItemSlot = 1
            val issued = player.inventory.itemInMainHand
            player.inventory.setItemInMainHand(fixture.items.fishingWeapon(player, "other-match", FishingGear.KNUCKLES))
            fixture.paper.callEvent(PrePlayerAttackEntityEvent(player, creature, true))
            adventure.snapshot().creatureHealth shouldBe 12.0
            player.inventory.setItemInMainHand(issued)
            adventure.melee(fixture.players[1], creature) shouldBe false
            hurtSounds() shouldBe 0

            fixture.paper.callEvent(PrePlayerAttackEntityEvent(player, creature, true)).isCancelled shouldBe true
            fixture.paper.callEvent(fishingHit(player, creature, 100.0)).isCancelled shouldBe true
            fixture.paper.callEvent(PrePlayerAttackEntityEvent(player, creature, true))
            adventure.snapshot().creatureHealth shouldBe 8.0
            hurtSounds() shouldBe 1

            fixture.advanceTime(700)
            val box = creature.boundingBox
            val beside = Vector(box.maxX + 0.55, box.centerY, box.centerZ)
            player.simulatePlayerMove(close.clone().setDirection(beside.subtract(player.eyeLocation.toVector())))
            box.clone().expand(0.25).rayTrace(player.eyeLocation.toVector(), player.eyeLocation.direction, 3.0) shouldBe null
            fun swing() = PlayerInteractEvent(player, Action.LEFT_CLICK_AIR, issued, null, BlockFace.SELF, EquipmentSlot.HAND)
            world.meleeObstruction = RayTraceResult(close.toVector())
            fixture.paper.callEvent(swing())
            fixture.paper.callEvent(PrePlayerAttackEntityEvent(player, hitbox, true))
            adventure.snapshot().creatureHealth shouldBe 8.0
            hurtSounds() shouldBe 1
            world.meleeObstruction = null
            fixture.paper.callEvent(swing()).isCancelled shouldBe true
            adventure.snapshot().creatureHealth shouldBe 4.0
            fixture.advanceTime(700)
            val pursuit = creature.location.clone().add(0.0, 0.0, 1.6)
            player.simulatePlayerMove(pursuit.clone().setDirection(Vector(1.0, 0.0, 0.0)))
            fixture.paper.callEvent(swing())
            adventure.snapshot().creatureHealth shouldBe 4.0
            player.simulatePlayerMove(pursuit.clone().setDirection(creature.boundingBox.center.subtract(player.eyeLocation.toVector())))
            fixture.paper.callEvent(fishingHit(player, hitbox, 1.0)).isCancelled shouldBe false
            adventure.snapshot().creatureHealth shouldBe 4.0
            fixture.paper.callEvent(PrePlayerAttackEntityEvent(player, hitbox, true))
            fixture.paper.callEvent(fishingHit(player, creature, 100.0))
            adventure.snapshot().bag.size shouldBe 1
            world.entities.filterIsInstance<Interaction>().size shouldBe 0
            hurtSounds() shouldBe 3
            world.hurtAnimations.count { it == creature.uniqueId } shouldBe 3
        } }
    }

    test("the camp clam flees across the shore, rests in reach and never damages its pursuer") {
        failOnUnsupportedMockBukkitOperation { TttRoundFixture().use { fixture ->
            fixture.startActiveArcade(EventMode.FISHING)
            val player = fixture.players.first() as PlayerMock
            val adventure = requireNotNull(fixture.service.fishingAdventure(player))
            castCatch(fixture, player, adventure)
            placeCatch(fixture, player, adventure)
            val clam = player.world.entities.filterIsInstance<org.bukkit.entity.Silverfish>().single()
            val hitbox = player.world.entities.filterIsInstance<Interaction>().single()
            val anchor = clam.location
            layCombatFloor(anchor)
            val health = player.health
            val xp = player.totalExperience
            repeat(3) {
                val start = clam.location
                player.simulatePlayerMove(start.clone().add(0.0, 0.0, 1.8))
                fixture.advanceTime(1_100)
                fixture.advanceTime(395)
                (clam.location.y > start.y + 0.7) shouldBe true
                hitbox.location.toVector() shouldBe clam.location.toVector()
                fixture.advanceTime(265)
                val landing = clam.location
                if (it == 0) (landing.distanceSquared(player.location) > start.distanceSquared(player.location)) shouldBe true
                (landing.distanceSquared(start) > 1.0) shouldBe true
                (landing.distanceSquared(anchor) <= 36.0) shouldBe true
                landing.y shouldBe 65.0
                landing.block.getRelative(BlockFace.DOWN).type.isSolid shouldBe true
                fixture.advanceTime(700)
                clam.location shouldBe landing
                player.health shouldBe health
            }
            player.simulatePlayerMove(clam.location.clone().add(0.0, 0.0, 1.6))
            strike(fixture, player, clam)
            adventure.snapshot().bag.single().species shouldBe "clam"
            adventure.snapshot().totalCatches shouldBe 1
            player.totalExperience shouldBe xp
        } }
    }

    test("the reef catch escapes twice then turns to lunge; a sidestep misses and physical contact hurts once") {
        failOnUnsupportedMockBukkitOperation { TttRoundFixture().use { fixture ->
            fixture.startActiveArcade(EventMode.FISHING)
            val player = fixture.players.first() as PlayerMock
            val adventure = requireNotNull(fixture.service.fishingAdventure(player))
            repeat(3) { catchAndDefeat(fixture, player, adventure) }
            moveToTrader(player, 0)
            trade(adventure, player, "bait") shouldBe true
            castCatch(fixture, player, adventure)
            placeCatch(fixture, player, adventure)
            strike(fixture, player, player.world.entities.filterIsInstance<Mob>().single { it !is Villager })
            moveToTrader(player, 0)
            trade(adventure, player, "trophy") shouldBe true
            player.simulatePlayerMove(Location(player.world, 0.5, 65.0, -8.5))
            fixture.advanceTime(1_000)
            adventure.snapshot().stage shouldBe 1
            castCatch(fixture, player, adventure)
            placeCatch(fixture, player, adventure)
            val fish = player.world.entities.filterIsInstance<Mob>().single { it !is Villager }
            layCombatFloor(fish.location)
            val health = player.health
            fun fleeTwice() {
                repeat(2) {
                    val start = fish.location
                    player.simulatePlayerMove(start.clone().add(0.0, 0.0, 2.5))
                    fixture.advanceTime(1_700)
                    fixture.advanceTime(383)
                    (fish.location.y > start.y + 0.7) shouldBe true
                    fixture.advanceTime(253)
                    fish.location.y shouldBe 65.0
                    player.health shouldBe health
                }
            }
            fun windUp() {
                val start = fish.location
                player.simulatePlayerMove(start.clone().add(0.0, 0.0, 2.5))
                fixture.advanceTime(1_700)
                fixture.advanceTime(1_300)
                (fish.location.y > start.y + 0.25) shouldBe true
                fish.location.z shouldBe start.z
                player.health shouldBe health
            }
            fleeTwice()
            windUp()
            player.simulatePlayerMove(player.location.clone().add(3.0, 0.0, 0.0))
            fixture.advanceTime(273)
            (fish.location.z < player.location.z) shouldBe true
            fixture.advanceTime(193)
            fish.location.y shouldBe 65.0
            player.health shouldBe health

            fleeTwice()
            windUp()
            fixture.advanceTime(180)
            player.health shouldBe health
            fixture.advanceTime(286)
            player.health shouldBe health - 2.0
            fixture.advanceTime(400)
            player.health shouldBe health - 2.0
            strike(fixture, player, fish)
            fixture.advanceTime(700)
            strike(fixture, player, fish)
            adventure.snapshot().bag.last().species shouldBe "reef_perch"
        } }
    }

    test("a discarded hook or a bite beyond the buoys lets the player cast again") {
        failOnUnsupportedMockBukkitOperation { TttRoundFixture().use { fixture ->
            fixture.startActiveArcade(EventMode.FISHING)
            val player = fixture.players.first() as PlayerMock
            val adventure = requireNotNull(fixture.service.fishingAdventure(player))
            player.simulatePlayerMove(Location(player.world, 0.5, 65.0, 16.5))
            val hook = mockk<FishHook>(relaxed = true)
            every { hook.uniqueId } returns UUID.randomUUID()
            every { hook.location } returns Location(player.world, 9.0, 63.0, 25.5)
            hook.location.block.type = Material.WATER
            fun event(state: PlayerFishEvent.State) = PlayerFishEvent(player, null, hook, EquipmentSlot.HAND, state)
            fixture.paper.callEvent(event(PlayerFishEvent.State.FISHING))
            adventure.snapshot().phase shouldBe FishingPhase.BITE
            adventure.actionHint() shouldBe null
            // The hook was discarded by Paper, so the next session tick releases the cast.
            fixture.advanceTime(1_000)
            adventure.snapshot().phase shouldBe FishingPhase.CASTING
            fixture.paper.callEvent(event(PlayerFishEvent.State.FISHING))
            val outside = event(PlayerFishEvent.State.BITE)
            fixture.paper.callEvent(outside)
            outside.isCancelled shouldBe true
            adventure.actionHint() shouldBe null
            adventure.snapshot().phase shouldBe FishingPhase.CASTING
            adventure.snapshot().totalCatches shouldBe 0
            fixture.paper.callEvent(event(PlayerFishEvent.State.FISHING))
            val floatingItem = mockk<Item>(relaxed = true)
            val snag = PlayerFishEvent(player, floatingItem, hook, EquipmentSlot.HAND, PlayerFishEvent.State.CAUGHT_ENTITY)
            fixture.paper.callEvent(snag)
            snag.isCancelled shouldBe true
            verify(exactly = 0) { floatingItem.remove() }
            adventure.snapshot().phase shouldBe FishingPhase.CASTING
            val zone = FishingArenaGenerator.fishingZone(FishingArenaStage.CAMP)
            val water = Location(player.world, zone.center.x, zone.waterSurfaceY.toDouble(), zone.center.z)
            water.block.type = Material.WATER
            every { hook.location } returns water
            every { hook.isValid } returns true
            fixture.paper.callEvent(event(PlayerFishEvent.State.FISHING))
            adventure.actionHint() shouldBe null
            fixture.paper.callEvent(event(PlayerFishEvent.State.BITE))
            (adventure.actionHint() != null) shouldBe true
            fixture.paper.callEvent(event(PlayerFishEvent.State.FAILED_ATTEMPT))
            adventure.actionHint() shouldBe null
            castCatch(fixture, player, adventure)
        } }
    }

    test("held catch waits for valid ground and repeated clicks cannot duplicate its encounter") {
        failOnUnsupportedMockBukkitOperation { TttRoundFixture().use { fixture ->
            fixture.startActiveArcade(EventMode.FISHING)
            val player = fixture.players.first() as PlayerMock
            val adventure = requireNotNull(fixture.service.fishingAdventure(player))
            castCatch(fixture, player, adventure)
            val heldHint = requireNotNull(adventure.actionHint())
            player.inventory.heldItemSlot = 1
            adventure.actionHint() shouldBe null
            player.inventory.heldItemSlot = 4
            adventure.actionHint() shouldBe heldHint
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
            adventure.actionHint() shouldBe null
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

private fun layCombatFloor(anchor: Location) {
    for (x in -8..8) for (z in -8..8) {
        anchor.world.getBlockAt(anchor.blockX + x, 64, anchor.blockZ + z).type = Material.STONE
    }
}

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
        val clearance = player.world.getBlockAt(floor.x + x, floor.y + y, floor.z + z)
        clearance.type = Material.AIR
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
        fixture.paper.callEvent(PrePlayerAttackEntityEvent(player, creature, true)).isCancelled shouldBe true
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
private fun fishingHit(player: Player, creature: Entity, damage: Double): EntityDamageByEntityEvent =
    EntityDamageByEntityEvent(
        player,
        creature,
        DamageCause.ENTITY_ATTACK,
        DamageSource.builder(DamageType.PLAYER_ATTACK).withCausingEntity(player).withDirectEntity(player).build(),
        damage,
    )
