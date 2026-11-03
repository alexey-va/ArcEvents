package ru.ruscrafting.events.paper

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.mockk.clearMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import io.papermc.paper.event.player.PrePlayerAttackEntityEvent
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.inventory.ItemStack
import org.bukkit.entity.Player
import org.bukkit.World
import org.bukkit.GameMode
import org.bukkit.Material
import org.bukkit.block.BlockFace
import org.bukkit.entity.Entity
import org.bukkit.event.Event
import org.bukkit.event.block.Action
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.inventory.EquipmentSlot
import ru.ruscrafting.events.config.NodeMode
import ru.ruscrafting.events.domain.FirearmId
import ru.ruscrafting.events.domain.FirearmSpec
import ru.ruscrafting.events.network.ReservationBatch
import java.util.UUID

class TttScopeGameplayMockBukkitTest : StringSpec({
    "event burst guns accept their remaining rounds and shotguns retain one shell per volley" {
        val shots = mockk<FirearmShots>(relaxed = true)
        val accepted = mutableListOf<FirearmSpec>()
        every { shots.fire(any(), any(), any(), any(), any(), any(), any(), any()) } answers {
            accepted += arg<FirearmSpec>(1)
            true
        }
        TttRoundFixture(shots = shots).use { f ->
            f.reloadRuntime { it.replace("weapons:\n  enabled: false", "weapons:\n  enabled: true") }
            f.startActiveRound()
            val player = f.players.first()
            val matchId = requireNotNull(f.service.currentMatch()).matchId.toString()
            player.inventory.clear()
            for (id in listOf(FirearmId.G36, FirearmId.AEK_971, FirearmId.RPL_20)) {
                val spec = f.firearms.spec(id)
                for (loaded in 1 until spec.roundsPerShot) {
                    f.advanceTime(1_000)
                    player.inventory.setItemInMainHand(f.firearms.firearmItem(id, player, matchId, loaded))
                    f.service.useFirearm(player) shouldBe true
                    accepted.last() shouldBe spec.copy(roundsPerShot = loaded, pellets = loaded)
                    f.firearms.state(player.inventory.itemInMainHand)?.loaded shouldBe 0
                    f.firearms.reserveAmmo(player, matchId) shouldBe 0
                }
            }
            f.advanceTime(1_000)
            val shotgun = f.firearms.spec(FirearmId.DOUBLE_BARREL)
            player.inventory.setItemInMainHand(f.firearms.firearmItem(shotgun.id, player, matchId, 1))
            f.service.useFirearm(player) shouldBe true
            accepted.last() shouldBe shotgun
            f.firearms.state(player.inventory.itemInMainHand)?.loaded shouldBe 0
        }
    }

    "an empty event trigger starts one ordinary reload using only its match reserve" {
        val shots = mockk<FirearmShots>(relaxed = true)
        val scopes = mockk<TttScopes>(relaxed = true)
        every { scopes.projectedZoom(any()) } returns null
        TttRoundFixture(shots = shots, scopes = scopes).use { f ->
            f.reloadRuntime { it.replace("weapons:\n  enabled: false", "weapons:\n  enabled: true") }
            f.startActiveRound()
            val player = f.players.first()
            val matchId = requireNotNull(f.service.currentMatch()).matchId.toString()
            player.inventory.clear()
            player.inventory.setItemInMainHand(f.firearms.firearmItem(FirearmId.REVOLVER, player, matchId, 0))
            player.inventory.setItem(10, f.firearms.ammunition(player, matchId, 8))
            player.inventory.setItem(11, f.firearms.ammunition(player, "another-match", 12))
            f.service.useFirearm(player) shouldBe true
            f.service.ammoHudState(player)?.reloading shouldBe true
            f.service.useFirearm(player) shouldBe false
            verify(exactly = 0) { shots.fire(any(), any(), any(), any(), any(), any(), any(), any()) }
            verify(exactly = 1) { scopes.animateReload(any(), any(), any(), any(), any()) }
            f.firearms.reserveAmmo(player, matchId) shouldBe 8
            f.paper.performTicks(f.firearms.spec(FirearmId.REVOLVER).reloadTicks.toLong())
            f.firearms.state(player.inventory.itemInMainHand)?.loaded shouldBe 6
            f.firearms.reserveAmmo(player, matchId) shouldBe 2
            f.firearms.reserveAmmo(player, "another-match") shouldBe 12
        }
    }

    "the final event burst defers one automatic reload until its last muzzle" {
        val shots = mockk<FirearmShots>(relaxed = true)
        val scopes = mockk<TttScopes>(relaxed = true)
        val finished = slot<(Boolean) -> Unit>()
        every { shots.fire(any(), any(), any(), any(), any(), any(), capture(finished), any()) } returns true
        TttRoundFixture(shots = shots, scopes = scopes).use { f ->
            f.reloadRuntime { it.replace("weapons:\n  enabled: false", "weapons:\n  enabled: true") }
            f.startActiveRound()
            val player = f.players.first()
            val matchId = requireNotNull(f.service.currentMatch()).matchId.toString()
            player.inventory.clear()
            player.inventory.setItemInMainHand(f.firearms.firearmItem(FirearmId.G36, player, matchId, 2))
            player.inventory.setItem(10, f.firearms.ammunition(player, matchId, 7))
            f.service.useFirearm(player) shouldBe true
            f.firearms.state(player.inventory.itemInMainHand)?.loaded shouldBe 0
            f.service.useFirearm(player) shouldBe false
            verify(exactly = 0) { scopes.animateReload(any(), any(), any(), any(), any()) }
            finished.captured(true)
            finished.captured(true)
            verify(exactly = 1) { scopes.animateReload(any(), any(), any(), any(), any()) }
            f.firearms.reserveAmmo(player, matchId) shouldBe 7
            f.paper.performTicks(f.firearms.spec(FirearmId.G36).reloadTicks.toLong())
            f.firearms.state(player.inventory.itemInMainHand)?.loaded shouldBe 7
            f.firearms.reserveAmmo(player, matchId) shouldBe 0
        }
    }

    "event deferred reloads reject aborted replaced slot world and lifecycle leases" {
        val shots = mockk<FirearmShots>(relaxed = true)
        val scopes = mockk<TttScopes>(relaxed = true)
        val finished = slot<(Boolean) -> Unit>()
        every { shots.fire(any(), any(), any(), any(), any(), any(), capture(finished), any()) } returns true
        TttRoundFixture(shots = shots, scopes = scopes).use { f ->
            f.reloadRuntime { it.replace("weapons:\n  enabled: false", "weapons:\n  enabled: true") }
            f.startActiveRound()
            val player = f.players.first()
            val matchId = requireNotNull(f.service.currentMatch()).matchId.toString()
            val world = player.world
            val changes: List<() -> Unit> = listOf(
                { f.service.clearFirearmScope(player.uniqueId) },
                { player.inventory.heldItemSlot = 1 },
                { player.inventory.setItemInMainHand(f.firearms.firearmItem(FirearmId.REVOLVER, player, matchId, 0).also {
                    it.editMeta { meta -> meta.lore(listOf(net.kyori.adventure.text.Component.text("replacement"))) }
                }) },
                { player.teleport(org.bukkit.Location(f.paper.addSimpleWorld("event-auto-other"), 0.0, 64.0, 0.0)); Unit },
                { player.gameMode = GameMode.CREATIVE; player.gameMode = GameMode.ADVENTURE },
            )
            for (change in changes) {
                f.service.clearFirearmScope(player.uniqueId)
                player.teleport(org.bukkit.Location(world, 0.5, 64.0, 0.5))
                player.inventory.clear()
                player.inventory.heldItemSlot = 0
                player.inventory.setItemInMainHand(f.firearms.firearmItem(FirearmId.REVOLVER, player, matchId, 1))
                player.inventory.setItem(10, f.firearms.ammunition(player, matchId, 6))
                f.advanceTime(1_000)
                f.service.useFirearm(player) shouldBe true
                change()
                finished.captured(true)
                f.paper.performTicks(f.firearms.spec(FirearmId.REVOLVER).reloadTicks.toLong())
                f.firearms.state(player.inventory.getItem(0))?.loaded shouldBe 0
                f.firearms.reserveAmmo(player, matchId) shouldBe 6
            }
            verify(exactly = 0) { scopes.animateReload(any(), any(), any(), any(), any()) }
            f.service.clearFirearmScope(player.uniqueId)
            player.inventory.setItemInMainHand(f.firearms.firearmItem(FirearmId.REVOLVER, player, matchId, 1))
            f.advanceTime(1_000)
            f.service.useFirearm(player) shouldBe true
            finished.captured(false)
            f.paper.performTicks(f.firearms.spec(FirearmId.REVOLVER).reloadTicks.toLong())
            f.firearms.reserveAmmo(player, matchId) shouldBe 6
            f.advanceTime(1_000)
            f.service.useFirearm(player) shouldBe true
            f.paper.performTicks(f.firearms.spec(FirearmId.REVOLVER).reloadTicks.toLong())
            f.firearms.state(player.inventory.itemInMainHand)?.loaded shouldBe 6
            f.firearms.reserveAmmo(player, matchId) shouldBe 0
        }
    }

    "event manual reload revokes deferred auto and busy or creative triggers never debit reserve" {
        val shots = mockk<FirearmShots>(relaxed = true)
        val scopes = mockk<TttScopes>(relaxed = true)
        val finished = slot<(Boolean) -> Unit>()
        var admitted = false
        every { shots.fire(any(), any(), any(), any(), any(), any(), capture(finished), any()) } answers { admitted }
        TttRoundFixture(shots = shots, scopes = scopes).use { f ->
            f.reloadRuntime { it.replace("weapons:\n  enabled: false", "weapons:\n  enabled: true") }
            f.startActiveRound()
            val player = f.players.first()
            val matchId = requireNotNull(f.service.currentMatch()).matchId.toString()
            player.inventory.clear()
            player.inventory.setItemInMainHand(f.firearms.firearmItem(FirearmId.REVOLVER, player, matchId, 1))
            player.inventory.setItem(10, f.firearms.ammunition(player, matchId, 8))
            f.service.useFirearm(player) shouldBe false
            f.firearms.state(player.inventory.itemInMainHand)?.loaded shouldBe 1
            f.firearms.reserveAmmo(player, matchId) shouldBe 8
            verify(exactly = 0) { scopes.animateReload(any(), any(), any(), any(), any()) }
            admitted = true
            f.service.useFirearm(player) shouldBe true
            f.service.reloadFirearm(player) shouldBe true
            finished.captured(true)
            verify(exactly = 1) { scopes.animateReload(any(), any(), any(), any(), any()) }
            f.service.clearFirearmScope(player.uniqueId)
            finished.captured(true)
            f.paper.performTicks(f.firearms.spec(FirearmId.REVOLVER).reloadTicks.toLong())
            f.firearms.state(player.inventory.itemInMainHand)?.loaded shouldBe 0
            f.firearms.reserveAmmo(player, matchId) shouldBe 8
            player.gameMode = GameMode.CREATIVE
            f.advanceTime(1_000)
            f.service.useFirearm(player) shouldBe true
            finished.captured(true)
            f.paper.performTicks(100)
            f.firearms.state(player.inventory.itemInMainHand)?.loaded shouldBe 0
            f.firearms.reserveAmmo(player, matchId) shouldBe 8
            verify(exactly = 1) { scopes.animateReload(any(), any(), any(), any(), any()) }
        }
    }

    "event reload completion requires its exact item slot world and uncancelled lifecycle" {
        TttRoundFixture().use { f ->
            f.reloadRuntime { it.replace("weapons:\n  enabled: false", "weapons:\n  enabled: true") }
            f.startActiveRound()
            val player = f.players.first()
            val matchId = requireNotNull(f.service.currentMatch()).matchId.toString()
            val elsewhere = f.paper.addSimpleWorld("event-reload-other")
            val world = player.world
            val changes: List<() -> Unit> = listOf(
                { player.inventory.heldItemSlot = 1 },
                { player.inventory.setItemInMainHand(f.firearms.firearmItem(FirearmId.REVOLVER, player, matchId, 0).also {
                    it.editMeta { meta -> meta.lore(listOf(net.kyori.adventure.text.Component.text("changed"))) }
                }) },
                { player.teleport(org.bukkit.Location(elsewhere, 0.0, 64.0, 0.0)); Unit },
                { f.service.clearFirearmScope(player.uniqueId) },
            )
            for (change in changes) {
                f.service.clearFirearmScope(player.uniqueId)
                player.teleport(org.bukkit.Location(world, 0.5, 64.0, 0.5))
                player.inventory.clear()
                player.inventory.heldItemSlot = 0
                player.inventory.setItemInMainHand(f.firearms.firearmItem(FirearmId.REVOLVER, player, matchId, 0))
                player.inventory.setItem(10, f.firearms.ammunition(player, matchId, 6))
                f.service.reloadFirearm(player) shouldBe true
                change()
                f.paper.performTicks(f.firearms.spec(FirearmId.REVOLVER).reloadTicks.toLong())
                f.firearms.state(player.inventory.getItem(0))?.loaded shouldBe 0
                f.firearms.reserveAmmo(player, matchId) shouldBe 6
            }
        }
    }

    "HOST OP creative raw-item preview never creates firearm state or bypasses an owned event" {
        val scopes = mockk<TttScopes>(relaxed = true)
        every { scopes.toggle(any(), any()) } returns true
        var itemId: String? = "gold_guns:gold_g36"
        TttRoundFixture(scopes = scopes, scopePreviewItemId = { itemId }).use { f ->
            val player = f.players.first()
            player.inventory.setItemInMainHand(ItemStack.of(Material.PAPER))
            player.gameMode = GameMode.CREATIVE
            f.service.toggleScopePreview(player) shouldBe false
            player.isOp = true
            player.hasPermission(SCOPE_PREVIEW_PERMISSION) shouldBe true
            val original = player.inventory.itemInMainHand.clone()
            f.service.toggleScopePreview(player) shouldBe true
            verify(exactly = 1) { scopes.toggle(player, FirearmScopeTarget(null, FirearmId.G36, player.inventory.heldItemSlot)) }
            player.inventory.itemInMainHand shouldBe original
            f.firearms.state(player.inventory.itemInMainHand) shouldBe null
            f.service.useFirearm(player) shouldBe false
            itemId = "gold_guns:gold_rpg_7"
            f.service.toggleScopePreview(player) shouldBe false
            itemId = "gold_guns:gold_g36"
            f.startPreparingRound().join() shouldBe DebugMutationResult.APPLIED
            player.inventory.setItemInMainHand(ItemStack.of(Material.PAPER))
            f.service.toggleScopePreview(player) shouldBe false
            f.service.debugAdvance()
            f.service.debugAdvance()
            f.service.toggleScopePreview(player) shouldBe false
            verify(exactly = 1) { scopes.toggle(any(), any()) }
        }
    }

    "RELAY starts OP raw-item preview from RMB without gameplay authority or inventory changes" {
        val scopes = mockk<TttScopes>(relaxed = true)
        val authorization = slot<(Player, FirearmScopeTarget) -> Boolean>()
        every { scopes.toggle(any(), any()) } returns true
        every { scopes.validate(capture(authorization)) } returns Unit
        TttRoundFixture(scopes = scopes, scopePreviewItemId = { "gold_guns:gold_g36" }).use { f ->
            f.reloadRuntime {
                it.replace("node-mode: HOST", "node-mode: RELAY")
                    .replace("weapons:\n  enabled: false", "weapons:\n  enabled: true")
            }
            f.startPreparingRound().join() shouldBe DebugMutationResult.WRONG_NODE
            f.service.snapshot().nodeMode shouldBe NodeMode.RELAY
            f.firearms.enabled shouldBe true
            val player = f.players.first()
            player.inventory.setItemInMainHand(ItemStack.of(Material.PAPER))
            player.gameMode = GameMode.CREATIVE
            f.service.toggleScopePreview(player) shouldBe false
            player.isOp = true
            val original = player.inventory.contents.map { it?.clone() }
            val target = FirearmScopeTarget(null, FirearmId.G36, player.inventory.heldItemSlot)
            val click = PlayerInteractEvent(player, Action.RIGHT_CLICK_AIR, player.inventory.itemInMainHand,
                null, BlockFace.SELF, EquipmentSlot.HAND)

            f.paper.callEvent(click)

            click.useItemInHand() shouldBe Event.Result.DENY
            verify(exactly = 1) { scopes.toggle(player, target) }
            f.service.onReservation(ReservationBatch(UUID.randomUUID(), emptyList())) shouldBe false
            f.service.matchIdentity() shouldBe null
            f.service.phase() shouldBe null
            f.service.useFirearm(player) shouldBe false
            f.service.reloadFirearm(player) shouldBe false
            f.service.toggleFirearmScope(player) shouldBe false
            f.firearms.state(player.inventory.itemInMainHand) shouldBe null
            f.advanceTime(1_000)
            authorization.captured(player, target) shouldBe true
            player.isOp = false
            f.advanceTime(1_000)
            authorization.captured(player, target) shouldBe false
            player.inventory.contents.toList() shouldBe original
            player.gameMode shouldBe GameMode.CREATIVE
            f.paper.playerDataSaveCount(player) shouldBe 0
            clearMocks(scopes, answers = false)
            f.service.close()
            verify(atLeast = 1) { scopes.clearAll() }
        }
    }

    "event-tagged items cannot use raw preview and scope permission is rechecked during motion" {
        val scopes = mockk<TttScopes>(relaxed = true)
        every { scopes.toggle(any(), any()) } returns true
        TttRoundFixture(scopes = scopes, scopePreviewItemId = { "gold_guns:gold_g36" }).use { f ->
            val player = f.players.first()
            player.isOp = true
            player.inventory.setItemInMainHand(f.firearms.firearmItem(FirearmId.G36, player, "stale-match"))
            f.service.toggleScopePreview(player) shouldBe false
            f.reloadRuntime { it.replace("node-mode: HOST", "node-mode: RELAY") }
            f.service.toggleScopePreview(player) shouldBe false
            player.inventory.setItemInMainHand(ItemStack.of(Material.PAPER))
            val target = FirearmScopeTarget(null, FirearmId.G36, player.inventory.heldItemSlot)
            f.service.isScopeAuthorized(player, target) shouldBe true
            player.isOp = false
            f.service.isScopeAuthorized(player, target) shouldBe false
            verify(exactly = 0) { scopes.toggle(any(), any()) }
        }
    }

    "physical entity attack reserves one shot and its later impact reaches the match damage lifecycle" {
        val shots = mockk<FirearmShots>(relaxed = true)
        val impact = slot<(Entity, FirearmHit, Double) -> Unit>()
        every { shots.fire(any(), any(), any(), any(), any(), any(), any(), capture(impact)) } returns true
        TttRoundFixture(shots = shots).use { f ->
            f.reloadRuntime { it.replace("weapons:\n  enabled: false", "weapons:\n  enabled: true") }
            f.startActiveRound()
            val matchId = requireNotNull(f.service.currentMatch()).matchId.toString()
            val originalShooter = f.players.first {
                f.service.participant(it.uniqueId)?.role == ru.ruscrafting.events.domain.TttRole.TRAITOR
            }
            val originalTarget = f.players.first { it.uniqueId != originalShooter.uniqueId }
            originalShooter.inventory.setItemInMainHand(f.firearms.firearmItem(FirearmId.VSS_VINTOREZ, originalShooter, matchId))
            val world = mockk<World>(relaxed = true)
            val shooter = mockk<Player>(relaxed = true)
            every { shooter.uniqueId } returns originalShooter.uniqueId
            every { shooter.name } returns originalShooter.name
            every { shooter.inventory } returns originalShooter.inventory
            every { shooter.eyeLocation } returns originalShooter.eyeLocation
            every { shooter.world } returns world
            val target = mockk<Player>(relaxed = true)
            every { target.uniqueId } returns originalTarget.uniqueId
            every { target.name } returns originalTarget.name
            every { target.location } returns originalTarget.location
            every { target.eyeLocation } returns originalTarget.eyeLocation
            every { target.health } returns 20.0
            var damageEvent: EntityDamageByEntityEvent? = null
            every { target.damage(any<Double>(), shooter) } answers {
                damageEvent = f.meleeHit(shooter, target, firstArg())
            }
            val attack = f.paper.callEvent(PrePlayerAttackEntityEvent(shooter, target, true))

            attack.isCancelled shouldBe true
            verify(exactly = 1) { shots.fire(shooter, any(), any(), any(), any(), any(), any(), any()) }
            verify(exactly = 0) { target.damage(any<Double>(), shooter) }
            val weapon = requireNotNull(f.firearms.state(originalShooter.inventory.itemInMainHand))
            weapon.loaded shouldBe f.firearms.spec(FirearmId.VSS_VINTOREZ).magazineSize - 1
            // Changing the held weapon while a bullet travels cannot turn its impact into an instant knife kill.
            originalShooter.inventory.setItemInMainHand(f.items.purchasedItem(EventItemKind.TRAITOR_BLADE, originalShooter, matchId))
            impact.captured(target, FirearmHit(originalTarget.eyeLocation.toVector(), true), 8.5)
            verify(exactly = 1) { target.damage(any<Double>(), shooter) }
            requireNotNull(damageEvent).isCancelled shouldBe false
            requireNotNull(f.service.participant(originalTarget.uniqueId)).status shouldBe
                ru.ruscrafting.events.domain.ParticipantStatus.ALIVE
            (requireNotNull(f.service.participant(originalShooter.uniqueId)).damageDealt > 0.0) shouldBe true
        }
    }

    "creative event firearms use no ammunition and preserve the magazine when returning to adventure" {
        val shots = mockk<FirearmShots>(relaxed = true)
        every { shots.fire(any(), any(), any(), any(), any(), any(), any(), any()) } returns true
        TttRoundFixture(shots = shots).use { f ->
            f.reloadRuntime { it.replace("weapons:\n  enabled: false", "weapons:\n  enabled: true") }
            f.startActiveRound()
            val player = f.players.first()
            val matchId = requireNotNull(f.service.currentMatch()).matchId.toString()
            player.inventory.clear()
            val empty = f.firearms.firearmItem(FirearmId.REVOLVER, player, matchId, 0)
            player.inventory.setItemInMainHand(empty)
            player.gameMode = GameMode.CREATIVE

            empty.maxStackSize shouldBe 1
            f.service.ammoHudState(player) shouldBe FirearmAmmoHudState(0, 6, 0, creative = true)

            f.service.useFirearm(player) shouldBe true
            f.service.reloadFirearm(player) shouldBe true
            f.advanceTime(1_000)
            player.inventory.itemInMainHand shouldBe empty
            f.firearms.reserveAmmo(player, matchId) shouldBe 0
            player.gameMode = GameMode.ADVENTURE
            f.service.useFirearm(player) shouldBe false
            verify(exactly = 1) { shots.fire(player, any(), any(), any(), any(), any(), any(), any()) }

            val loaded = f.firearms.firearmItem(FirearmId.REVOLVER, player, matchId, 2)
            player.inventory.setItemInMainHand(loaded)
            player.inventory.setItem(10, f.firearms.ammunition(player, matchId, 4))
            player.gameMode = GameMode.CREATIVE
            f.advanceTime(1_000)
            f.service.useFirearm(player) shouldBe true
            f.service.reloadFirearm(player) shouldBe true
            f.advanceTime(1_000)
            player.inventory.itemInMainHand shouldBe loaded
            f.firearms.reserveAmmo(player, matchId) shouldBe 4
            player.gameMode = GameMode.ADVENTURE
            f.service.useFirearm(player) shouldBe true
            f.firearms.state(player.inventory.itemInMainHand)?.loaded shouldBe 1
            f.firearms.reserveAmmo(player, matchId) shouldBe 4
            f.service.ammoHudState(player) shouldBe FirearmAmmoHudState(1, 6, 4)
            verify(exactly = 3) { shots.fire(player, any(), any(), any(), any(), any(), any(), any()) }
        }
    }

    "creative cancels event reloads and completion rechecks the game mode before consuming ammunition" {
        val shots = mockk<FirearmShots>(relaxed = true)
        every { shots.fire(any(), any(), any(), any(), any(), any(), any(), any()) } returns true
        TttRoundFixture(shots = shots).use { f ->
            f.reloadRuntime { it.replace("weapons:\n  enabled: false", "weapons:\n  enabled: true") }
            f.startActiveRound()
            val player = f.players.first()
            val matchId = requireNotNull(f.service.currentMatch()).matchId.toString()
            val transitions: List<() -> Unit> = listOf(
                { player.gameMode = GameMode.CREATIVE },
                {
                    player.gameMode = GameMode.CREATIVE
                    f.service.reloadFirearm(player) shouldBe true
                    player.gameMode = GameMode.ADVENTURE
                },
                {
                    player.gameMode = GameMode.CREATIVE
                    f.service.useFirearm(player) shouldBe true
                    player.gameMode = GameMode.ADVENTURE
                },
            )
            transitions.forEach { transition ->
                player.gameMode = GameMode.ADVENTURE
                player.inventory.clear()
                player.inventory.setItemInMainHand(f.firearms.firearmItem(FirearmId.REVOLVER, player, matchId, 0))
                player.inventory.setItem(10, f.firearms.ammunition(player, matchId, 6))
                f.service.reloadFirearm(player) shouldBe true
                transition()
                repeat(2) { f.advanceTime(1_000) }
                f.firearms.state(player.inventory.itemInMainHand)?.loaded shouldBe 0
                f.firearms.reserveAmmo(player, matchId) shouldBe 6
            }
        }
    }

    "aiming uses the firearm participant phase and match gates for the firearm catalog" {
        val scopes = mockk<TttScopes>(relaxed = true)
        every { scopes.toggle(any(), any()) } returns true
        TttRoundFixture(scopes = scopes).use { f ->
            f.reloadRuntime { it.replace("weapons:\n  enabled: false", "weapons:\n  enabled: true") }
            val player = f.players.first()
            f.startPreparingRound().join() shouldBe DebugMutationResult.APPLIED
            val matchId = requireNotNull(f.service.currentMatch()).matchId.toString()
            fun hold(id: FirearmId, owner: String = matchId) {
                player.inventory.setItemInMainHand(f.firearms.firearmItem(id, player, owner))
            }
            hold(FirearmId.VSS_VINTOREZ)
            f.service.toggleFirearmScope(player) shouldBe false
            verify(exactly = 0) { scopes.toggle(any(), any()) }
            f.service.debugAdvance()
            f.service.debugAdvance()
            f.service.toggleFirearmScope(player) shouldBe true
            verify(exactly = 1) {
                scopes.toggle(player, FirearmScopeTarget(matchId, FirearmId.VSS_VINTOREZ, player.inventory.heldItemSlot))
            }
            hold(FirearmId.VSS_VINTOREZ, UUID.randomUUID().toString())
            f.service.toggleFirearmScope(player) shouldBe false
            val outsider = f.paper.addPlayer("Outside")
            outsider.inventory.setItemInMainHand(f.firearms.firearmItem(FirearmId.MCMILLAN, outsider, matchId))
            f.service.toggleFirearmScope(outsider) shouldBe false
            hold(FirearmId.G36)
            f.service.toggleFirearmScope(player) shouldBe true
            verify(exactly = 2) { scopes.toggle(any(), any()) }
            verify(atLeast = 1) { scopes.clear(player.uniqueId) }
        }
    }

    "reload and periodic weapon or slot replacement revoke the same scope authorization" {
        val scopes = mockk<TttScopes>(relaxed = true)
        val authorization = slot<(org.bukkit.entity.Player, FirearmScopeTarget) -> Boolean>()
        every { scopes.validate(capture(authorization)) } returns Unit
        TttRoundFixture(scopes = scopes).use { f ->
            f.reloadRuntime { it.replace("weapons:\n  enabled: false", "weapons:\n  enabled: true") }
            f.startActiveRound()
            val player = f.players.first()
            val matchId = requireNotNull(f.service.currentMatch()).matchId.toString()
            player.inventory.setItemInMainHand(f.firearms.firearmItem(FirearmId.VSS_VINTOREZ, player, matchId, 0))
            val target = FirearmScopeTarget(matchId, FirearmId.VSS_VINTOREZ, player.inventory.heldItemSlot)
            f.advanceTime(1_000)
            authorization.captured(player, target) shouldBe true
            player.inventory.heldItemSlot = (target.heldSlot + 1) % 9
            authorization.captured(player, target) shouldBe false
            player.inventory.heldItemSlot = target.heldSlot
            player.inventory.addItem(f.firearms.ammunition(player, matchId, 8))
            clearMocks(scopes, answers = false)
            f.service.reloadFirearm(player) shouldBe true
            verify(exactly = 1) { scopes.clear(player.uniqueId) }
            authorization.captured(player, target) shouldBe false
            f.service.stopByAdmin()
            verify(atLeast = 1) { scopes.clearAll() }
        }
    }

    "all firearm tooltips advertise RMB and refresh retains controls and rounds" {
        TttRoundFixture().use { f ->
            val player = f.players.first()
            val plain = PlainTextComponentSerializer.plainText()
            fun text(item: ItemStack) = item.itemMeta.lore().orEmpty().joinToString(" ", transform = plain::serialize)
            val regular = f.firearms.firearmItem(FirearmId.G36, player, "match")
            text(regular).contains("ПКМ") shouldBe true
            text(regular).contains("ЛКМ") shouldBe true
            for (id in FirearmId.entries) {
                val scope = f.firearms.refreshItem(f.firearms.firearmItem(id, player, "match", 1), player)
                text(scope).contains("ПКМ") shouldBe true
                text(scope).contains("прицел вкл/выкл") shouldBe true
                f.firearms.state(scope) shouldBe FirearmState(id, 1, "match")
                scope.itemMeta.lore().orEmpty().all { !it.hasDecoration(net.kyori.adventure.text.format.TextDecoration.ITALIC) } shouldBe true
            }
        }
    }
})
