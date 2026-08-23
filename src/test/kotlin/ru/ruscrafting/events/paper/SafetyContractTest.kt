package ru.ruscrafting.events.paper

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.bukkit.Location
import org.bukkit.World
import org.bukkit.block.BlockFace
import org.bukkit.entity.Player
import org.bukkit.event.Event
import org.bukkit.event.block.Action
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.inventory.ItemStack
import org.bukkit.potion.PotionEffect
import java.util.UUID

class SafetyContractTest : StringSpec({
    "internal teleport authorization is player and destination bound" {
        val playerId = UUID.randomUUID()
        val world = mockk<World>()
        every { world.uid } returns UUID.randomUUID()
        val expected = Location(world, 12.5, 70.0, -4.5, 90f, 10f)
        val authorizer = InternalTeleportAuthorizer()

        authorizer.authorize(playerId, expected) {
            authorizer.isAuthorized(playerId, expected.clone()) shouldBe true
            authorizer.isAuthorized(playerId, expected.clone().add(0.25, 0.0, 0.0)) shouldBe false
            authorizer.isAuthorized(UUID.randomUUID(), expected) shouldBe false
        }

        authorizer.isAuthorized(playerId, expected) shouldBe false
    }

    "recovery accepts Paper infinite potion duration" {
        PotionSnapshot(
            type = "minecraft:speed",
            duration = PotionEffect.INFINITE_DURATION,
            amplifier = 0,
            ambient = false,
            particles = true,
            icon = true,
        ).validated()
    }

    "recognized active event items never fall through to vanilla interaction" {
        val service = mockk<ArcEventsGameplayBoundary>()
        val menu = mockk<ArcEventsMenu>()
        val items = mockk<EventItemResolver>()
        val player = mockk<Player>()
        val item = mockk<ItemStack>()
        val playerId = UUID.randomUUID()
        every { player.uniqueId } returns playerId
        every { items.kind(item) } returns EventItemKind.DETECTIVE_MEDKIT
        every { service.belongsToCurrentMatch(playerId, item) } returns true
        every { service.useSpecialItem(player, EventItemKind.DETECTIVE_MEDKIT) } returns false
        val event = PlayerInteractEvent(
            player,
            Action.RIGHT_CLICK_AIR,
            item,
            null,
            BlockFace.SELF,
            EquipmentSlot.HAND,
        )

        ArcEventsListener(service, menu, items).onInteract(event)

        event.useItemInHand() shouldBe Event.Result.DENY
        verify(exactly = 1) { service.useSpecialItem(player, EventItemKind.DETECTIVE_MEDKIT) }
    }
})
