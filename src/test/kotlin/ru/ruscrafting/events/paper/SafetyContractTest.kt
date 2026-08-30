package ru.ruscrafting.events.paper

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.papermc.paper.event.player.AsyncChatEvent
import net.kyori.adventure.audience.Audience
import ru.arc.paper.teleport.ScopedTeleportAuthorizer
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.bukkit.Location
import org.bukkit.World
import org.bukkit.block.BlockFace
import org.bukkit.entity.Player
import org.bukkit.event.Event
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.block.Action
import org.bukkit.event.entity.ProjectileLaunchEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.event.player.PlayerSwapHandItemsEvent
import org.bukkit.event.server.BroadcastMessageEvent
import org.bukkit.command.CommandSender
import net.kyori.adventure.text.Component
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.inventory.ItemStack
import ru.ruscrafting.events.domain.MatchPhase
import java.util.UUID

class SafetyContractTest : StringSpec({
    "internal teleport authorization is player and destination bound" {
        val playerId = UUID.randomUUID()
        val world = mockk<World>()
        every { world.uid } returns UUID.randomUUID()
        val expected = Location(world, 12.5, 70.0, -4.5, 90f, 10f)
        val authorizer = ScopedTeleportAuthorizer()

        authorizer.authorize(playerId, expected) {
            authorizer.isAuthorized(playerId, expected.clone()) shouldBe true
            authorizer.isAuthorized(playerId, expected.clone().add(0.25, 0.0, 0.0)) shouldBe false
            authorizer.isAuthorized(UUID.randomUUID(), expected) shouldBe false
        }

        authorizer.isAuthorized(playerId, expected) shouldBe false
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

    "pre-cancelled vanilla item interactions still reach ArcEvents" {
        val handler = ArcEventsListener::class.java
            .getDeclaredMethod("onInteract", PlayerInteractEvent::class.java)
            .getAnnotation(EventHandler::class.java)

        handler.ignoreCancelled shouldBe false
        handler.priority shouldBe EventPriority.HIGHEST
    }

    "admin participants reload instead of swapping hands" {
        val service = mockk<ArcEventsGameplayBoundary>()
        val player = mockk<Player>()
        val playerId = UUID.randomUUID()
        every { player.uniqueId } returns playerId
        every { player.location } returns mockk()
        every { player.hasPermission(ArcEventsListener.ADMIN_BYPASS_PERMISSION) } returns true
        every { service.isParticipant(playerId) } returns true
        every { service.phase() } returns MatchPhase.ACTIVE
        every { service.reloadFirearm(player) } returns true
        val event = mockk<PlayerSwapHandItemsEvent>(relaxed = true)
        every { event.player } returns player

        ArcEventsListener(service, mockk(), mockk()).onSwap(event)

        verify(exactly = 1) { event.isCancelled = true }
        verify(exactly = 1) { service.reloadFirearm(player) }
    }

    "admin participants still use match damage rules" {
        val service = mockk<ArcEventsGameplayBoundary>(relaxed = true)
        val player = mockk<Player>()
        val playerId = UUID.randomUUID()
        every { player.uniqueId } returns playerId
        every { player.location } returns mockk()
        every { player.hasPermission(ArcEventsListener.ADMIN_BYPASS_PERMISSION) } returns true
        every { service.isParticipant(playerId) } returns true
        every { service.shouldCancelDamage(playerId, null, false, null) } returns true
        val event = mockk<org.bukkit.event.entity.EntityDamageEvent>(relaxed = true)
        every { event.entity } returns player

        ArcEventsListener(service, mockk(), mockk()).onDamage(event)

        verify(exactly = 1) { service.shouldCancelDamage(playerId, null, false, null) }
        verify(exactly = 1) { event.isCancelled = true }
    }

    "admin participants still register match projectiles" {
        val service = mockk<ArcEventsGameplayBoundary>()
        val player = mockk<Player>()
        val playerId = UUID.randomUUID()
        val projectile = mockk<org.bukkit.entity.Projectile>()
        every { player.uniqueId } returns playerId
        every { player.hasPermission(ArcEventsListener.ADMIN_BYPASS_PERMISSION) } returns true
        every { projectile.shooter } returns player
        every { service.isParticipant(playerId) } returns true
        every { service.registerProjectile(projectile) } returns true
        val event = mockk<ProjectileLaunchEvent>(relaxed = true)
        every { event.entity } returns projectile

        ArcEventsListener(service, mockk(), mockk()).onProjectileLaunch(event)

        verify(exactly = 1) { service.registerProjectile(projectile) }
        verify(exactly = 0) { event.isCancelled = true }
    }

    "global chat excludes players whose event chat is isolated" {
        val service = mockk<ArcEventsGameplayBoundary>()
        val sender = mockk<Player>()
        val participant = mockk<Player>()
        val outsider = mockk<Player>()
        val nonPlayerAudience = mockk<Audience>()
        val senderId = UUID.randomUUID()
        val participantId = UUID.randomUUID()
        val outsiderId = UUID.randomUUID()
        every { sender.uniqueId } returns senderId
        every { participant.uniqueId } returns participantId
        every { outsider.uniqueId } returns outsiderId
        every { service.handlesMatchChat(senderId) } returns false
        every { service.handlesMatchChat(participantId) } returns true
        every { service.handlesMatchChat(outsiderId) } returns false
        val viewers = mutableSetOf<Audience>(participant, outsider, nonPlayerAudience)
        val event = mockk<AsyncChatEvent>(relaxed = true)
        every { event.player } returns sender
        every { event.viewers() } returns viewers

        ArcEventsListener(service, mockk(), mockk()).onChat(event)

        viewers.shouldContainExactlyInAnyOrder(outsider, nonPlayerAudience)
        verify(exactly = 0) { event.isCancelled = true }
    }

    "server broadcasts exclude players whose event chat is isolated" {
        val service = mockk<ArcEventsGameplayBoundary>()
        val participant = mockk<Player>()
        val outsider = mockk<Player>()
        val console = mockk<CommandSender>()
        val participantId = UUID.randomUUID()
        val outsiderId = UUID.randomUUID()
        every { participant.uniqueId } returns participantId
        every { outsider.uniqueId } returns outsiderId
        every { service.handlesMatchChat(participantId) } returns true
        every { service.handlesMatchChat(outsiderId) } returns false
        val recipients = mutableSetOf<CommandSender>(participant, outsider, console)
        val event = BroadcastMessageEvent(false, Component.text("outside"), recipients)

        ArcEventsListener(service, mockk(), mockk()).onBroadcast(event)

        recipients.shouldContainExactlyInAnyOrder(outsider, console)
    }

    "imported block sanitizer recognizes every banner palette variant" {
        org.bukkit.Material.entries
            .filter { it.name.endsWith("_BANNER") }
            .all(::isImportedBlockDecoration) shouldBe true
        isImportedBlockDecoration(org.bukkit.Material.ARMOR_STAND) shouldBe false
    }

    "packet isolation scope admits only the current ArcEvents recipient" {
        val player = mockk<Player>()
        val playerId = UUID.randomUUID()
        val otherId = UUID.randomUUID()
        every { player.uniqueId } returns playerId
        ArcEventsMessageDelivery.activate()
        try {
            ArcEventsMessageDelivery.deliver(player) {
                ArcEventsMessageDelivery.isInternal(playerId) shouldBe true
                ArcEventsMessageDelivery.isInternal(otherId) shouldBe false
            }
            ArcEventsMessageDelivery.isInternal(playerId) shouldBe false
        } finally {
            ArcEventsMessageDelivery.deactivate()
        }
    }

    "packet isolation suppresses only external messages to event participants" {
        shouldSuppressChatPacket(isolatedRecipient = true, internalMessage = false) shouldBe true
        shouldSuppressChatPacket(isolatedRecipient = true, internalMessage = true) shouldBe false
        shouldSuppressChatPacket(isolatedRecipient = false, internalMessage = false) shouldBe false
    }
})
