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
import org.bukkit.util.RayTraceResult
import org.bukkit.event.entity.EntityDamageByEntityEvent
import ru.ruscrafting.events.domain.FirearmId
import java.util.UUID

class TttScopeGameplayMockBukkitTest : StringSpec({
    "physical entity attack fires one ray whose synthetic damage still reaches the match damage lifecycle" {
        TttRoundFixture().use { f ->
            f.reloadRuntime { it.replace("weapons:\n  enabled: false", "weapons:\n  enabled: true") }
            f.startActiveRound()
            val matchId = requireNotNull(f.service.currentMatch()).matchId.toString()
            val originalShooter = f.players[0]
            val originalTarget = f.players[1]
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
            every { world.rayTrace(any(), any(), any(), any(), any(), any(), any()) } returns
                RayTraceResult(originalTarget.eyeLocation.toVector(), target)

            val attack = f.paper.callEvent(PrePlayerAttackEntityEvent(shooter, target, true))

            attack.isCancelled shouldBe true
            verify(exactly = 1) { target.damage(any<Double>(), shooter) }
            requireNotNull(damageEvent).isCancelled shouldBe false
            val weapon = requireNotNull(f.firearms.state(originalShooter.inventory.itemInMainHand))
            weapon.loaded shouldBe f.firearms.spec(FirearmId.VSS_VINTOREZ).magazineSize - 1
            (requireNotNull(f.service.participant(originalShooter.uniqueId)).damageDealt > 0.0) shouldBe true
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
