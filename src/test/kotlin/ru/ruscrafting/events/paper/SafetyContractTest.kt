package ru.ruscrafting.events.paper

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.papermc.paper.event.player.AsyncChatEvent
import io.papermc.paper.event.player.PlayerArmSwingEvent
import io.papermc.paper.event.player.PrePlayerAttackEntityEvent
import net.kyori.adventure.audience.Audience
import ru.arc.core.PaperArcRuntime
import ru.arc.core.Tasks
import ru.arc.paper.testing.MockBukkitTestRuntime
import ru.arc.paper.teleport.ScopedTeleportAuthorizer
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.bukkit.Location
import org.bukkit.World
import org.bukkit.block.BlockFace
import org.bukkit.entity.Entity
import org.bukkit.entity.Player
import org.bukkit.event.Event
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.block.Action
import org.bukkit.event.entity.ProjectileLaunchEvent
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.event.entity.EntityDamageEvent.DamageCause
import org.bukkit.event.player.PlayerCommandPreprocessEvent
import org.bukkit.event.player.PlayerInteractAtEntityEvent
import org.bukkit.event.player.PlayerInteractEntityEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.event.player.PlayerSwapHandItemsEvent
import org.bukkit.event.player.PlayerTeleportEvent
import org.bukkit.event.server.BroadcastMessageEvent
import org.bukkit.command.CommandSender
import net.kyori.adventure.text.Component
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.ItemMeta
import org.bukkit.NamespacedKey
import org.bukkit.Server
import org.bukkit.util.Vector
import org.bukkit.event.inventory.InventoryCreativeEvent
import ru.ruscrafting.events.domain.MatchPhase
import java.util.UUID

class SafetyContractTest : StringSpec({
    "the two interaction packets of a body click open evidence only once" {
        val player = mockk<Player>()
        val playerId = UUID.randomUUID()
        val server = mockk<Server>()
        var tick = 100
        every { player.uniqueId } returns playerId
        every { player.server } returns server
        every { server.currentTick } answers { tick }
        val body = mockk<Entity>()
        val bodyId = UUID.randomUUID()
        val service = mockk<ArcEventsGameplayBoundary>(relaxed = true) {
            every { fishingAdventure(player) } returns null
            every { readBodyId(body) } returns bodyId
        }
        val menu = mockk<ArcEventsMenu>(relaxed = true)
        val listener = ArcEventsListener(service, menu, mockk())
        PlayerInteractAtEntityEvent(player, body, Vector(), EquipmentSlot.HAND)
            .also(listener::onInteractAtEntity).isCancelled shouldBe true
        tick++
        PlayerInteractEntityEvent(player, body, EquipmentSlot.HAND)
            .also(listener::onInteractEntity).isCancelled shouldBe true
        PlayerInteractAtEntityEvent(player, body, Vector(), EquipmentSlot.OFF_HAND)
            .also(listener::onInteractAtEntity).isCancelled shouldBe true
        verify(exactly = 1) { service.inspectBody(player, bodyId) }
        verify(exactly = 1) { menu.open(player, EventsView.Body(bodyId)) }
        tick += 4
        PlayerInteractEntityEvent(player, body, EquipmentSlot.HAND).also(listener::onInteractEntity)
        verify(exactly = 2) { menu.open(player, EventsView.Body(bodyId)) }
    }

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
        every { service.handleFishingInteract(any()) } returns false
        every { service.isBuilderZombiesParticipant(playerId) } returns false
        every { service.zombiesAdventure(player) } returns null
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

    "event guide and round report items start fresh menu flows from the active match and main hand" {
        val service = mockk<ArcEventsGameplayBoundary>(relaxed = true)
        val menu = mockk<ArcEventsMenu>(relaxed = true)
        val player = mockk<Player>()
        val playerId = UUID.randomUUID()
        every { player.uniqueId } returns playerId
        val items = mockk<EventItemResolver>()
        val listener = ArcEventsListener(service, menu, items)

        for ((kind, view) in listOf(EventItemKind.GUIDE to EventsView.Help, EventItemKind.ROUND_REPORT to EventsView.Report)) {
            val item = mockk<ItemStack>()
            every { items.kind(item) } returns kind
            every { service.belongsToCurrentMatch(playerId, item) } returns true

            fun interact(hand: EquipmentSlot) =
                PlayerInteractEvent(player, Action.RIGHT_CLICK_AIR, item, null, BlockFace.SELF, hand)
                    .also(listener::onInteract).useItemInHand()

            interact(EquipmentSlot.OFF_HAND) shouldBe Event.Result.DENY
            interact(EquipmentSlot.HAND) shouldBe Event.Result.DENY
            every { service.belongsToCurrentMatch(playerId, item) } returns false
            interact(EquipmentSlot.HAND) shouldBe Event.Result.DENY

            verify(exactly = 1) { menu.openRoot(player, view) }
            verify(exactly = 0) { menu.open(player, view) }
        }
    }

    "pre-cancelled vanilla item interactions still reach ArcEvents" {
        val handler = ArcEventsListener::class.java
            .getDeclaredMethod("onInteract", PlayerInteractEvent::class.java)
            .getAnnotation(EventHandler::class.java)

        handler.ignoreCancelled shouldBe false
        handler.priority shouldBe EventPriority.HIGHEST
    }

    "firearms dispatch LMB in air or blocks and reserve RMB for scope only in the main hand" {
        val service = mockk<ArcEventsGameplayBoundary>(relaxed = true)
        val player = mockk<Player>()
        val item = mockk<ItemStack>()
        val items = mockk<EventItemResolver>()
        every { player.uniqueId } returns UUID.randomUUID()
        every { items.kind(item) } returns EventItemKind.FIREARM
        every { service.belongsToCurrentMatch(player.uniqueId, item) } returns true
        val listener = ArcEventsListener(service, mockk(), items)
        for (action in listOf(Action.LEFT_CLICK_AIR, Action.LEFT_CLICK_BLOCK, Action.RIGHT_CLICK_AIR, Action.RIGHT_CLICK_BLOCK)) {
            for (hand in listOf(EquipmentSlot.HAND, EquipmentSlot.OFF_HAND)) {
                val event = PlayerInteractEvent(player, action, item, null, BlockFace.SELF, hand)
                listener.onInteract(event)
                event.useItemInHand() shouldBe Event.Result.DENY
            }
        }
        verify(exactly = 2) { service.useFirearm(player) }
        verify(exactly = 2) { service.toggleFirearmScope(player) }
    }

    "LMB entity attacks fire once through the firearm gate and never apply vanilla melee" {
        val service = mockk<ArcEventsGameplayBoundary>(relaxed = true)
        val player = mockk<Player>(relaxed = true)
        val item = mockk<ItemStack>()
        val items = mockk<EventItemResolver>()
        every { player.uniqueId } returns UUID.randomUUID()
        every { player.inventory.itemInMainHand } returns item
        every { items.kind(item) } returns EventItemKind.FIREARM
        every { service.belongsToCurrentMatch(player.uniqueId, item) } returns true
        val event = PrePlayerAttackEntityEvent(player, mockk(), true)

        ArcEventsListener(service, mockk(), items).onFirearmAttack(event)

        event.isCancelled shouldBe true
        verify(exactly = 1) { service.useFirearm(player) }
    }

    "the separate native arm packet after an air or entity shot never fires a second bullet" {
        MockBukkitTestRuntime.open().use { paper ->
            PaperArcRuntime.installScheduling(paper.createSimplePlugin("EventFirearmSwingTest"))
            try {
                val service = mockk<ArcEventsGameplayBoundary>(relaxed = true)
                val player = mockk<Player>(relaxed = true)
                val item = mockk<ItemStack>()
                val items = mockk<EventItemResolver>()
                every { player.uniqueId } returns UUID.randomUUID()
                every { player.inventory.itemInMainHand } returns item
                every { items.kind(item) } returns EventItemKind.FIREARM
                every { service.belongsToCurrentMatch(player.uniqueId, item) } returns true
                val listener = ArcEventsListener(service, mockk(), items)
                FirearmItems({ items.kind(it) == EventItemKind.FIREARM }).use { policy ->
                    val air = PlayerInteractEvent(player, Action.LEFT_CLICK_AIR, item, null, BlockFace.SELF, EquipmentSlot.HAND)
                    listener.onInteract(air)
                    air.useItemInHand() shouldBe Event.Result.DENY
                    val airSwing = PlayerArmSwingEvent(player, EquipmentSlot.HAND)
                    policy.onSwing(airSwing)
                    airSwing.isCancelled shouldBe true

                    val attack = PrePlayerAttackEntityEvent(player, mockk(), true)
                    listener.onFirearmAttack(attack)
                    attack.isCancelled shouldBe true
                    val entitySwing = PlayerArmSwingEvent(player, EquipmentSlot.HAND)
                    policy.onSwing(entitySwing)
                    entitySwing.isCancelled shouldBe true
                }
                verify(exactly = 2) { service.useFirearm(player) }
            } finally {
                Tasks.reset()
            }
        }
    }

    "ordinary melee remains outside the firearm attack and sound cancellation gate" {
        val service = mockk<ArcEventsGameplayBoundary>(relaxed = true)
        val player = mockk<Player>(relaxed = true)
        val item = mockk<ItemStack>()
        val items = mockk<EventItemResolver>()
        every { player.inventory.itemInMainHand } returns item
        val listener = ArcEventsListener(service, mockk(), items)
        for (kind in listOf(null, EventItemKind.TRAITOR_BLADE, EventItemKind.ARCADE_KNIFE, EventItemKind.FISHING_WEAPON)) {
            every { items.kind(item) } returns kind
            val attack = PrePlayerAttackEntityEvent(player, mockk(), true)
            listener.onFirearmAttack(attack)
            attack.isCancelled shouldBe false
        }
        verify(exactly = 0) { service.useFirearm(any()) }
    }

    "raw preview handles main-hand RMB only and never routes into firing" {
        val service = mockk<ArcEventsGameplayBoundary>(relaxed = true)
        val player = mockk<Player>(relaxed = true)
        val item = mockk<ItemStack>()
        val items = mockk<EventItemResolver>()
        every { items.kind(item) } returns null
        every { service.toggleScopePreview(player) } returns true
        val listener = ArcEventsListener(service, mockk(), items)
        val main = PlayerInteractEvent(player, Action.RIGHT_CLICK_AIR, item, null, BlockFace.SELF, EquipmentSlot.HAND)
        listener.onInteract(main)
        main.useItemInHand() shouldBe Event.Result.DENY
        listener.onInteract(PlayerInteractEvent(player, Action.LEFT_CLICK_AIR, item, null, BlockFace.SELF, EquipmentSlot.HAND))
        listener.onInteract(PlayerInteractEvent(player, Action.RIGHT_CLICK_AIR, item, null, BlockFace.SELF, EquipmentSlot.OFF_HAND))
        verify(exactly = 1) { service.toggleScopePreview(player) }
        verify(exactly = 0) { service.useFirearm(any()) }
        verify(exactly = 0) { service.toggleFirearmScope(any()) }
    }

    "creative preview copies and queued transient cursors are denied while unrelated creative items remain usable" {
        val service = mockk<ArcEventsGameplayBoundary>(relaxed = true)
        val player = mockk<Player>(relaxed = true)
        val playerId = UUID.randomUUID()
        var preview = true
        every { player.uniqueId } returns playerId
        every { player.hasPermission(ArcEventsListener.ADMIN_BYPASS_PERMISSION) } returns true
        every { service.isScopePreview(playerId) } answers { preview }
        every { service.clearFirearmScope(playerId) } answers { preview = false }
        fun cursor(model: String?): ItemStack = mockk {
            every { isEmpty } returns false
            every { itemMeta } returns mockk<ItemMeta> {
                every { itemModel } returns model?.let(NamespacedKey::fromString)
            }
        }
        fun creative(item: ItemStack) = mockk<InventoryCreativeEvent>(relaxed = true) {
            every { whoClicked } returns player
            every { cursor } returns item
        }
        val listener = ArcEventsListener(service, mockk(), mockk())
        val first = creative(cursor(null))
        listener.onMenuClick(first)
        verify(exactly = 1) { first.isCancelled = true }
        preview shouldBe false
        for (frame in listOf("hip", "raise_1", "raise_2", "raise_3", "aim")) {
            val queued = creative(cursor("arc:event_sights/vss_vintorez_$frame"))
            listener.onMenuClick(queued)
            verify(exactly = 1) { queued.isCancelled = true }
        }
        val ordinary = creative(cursor("minecraft:stone"))
        listener.onMenuClick(ordinary)
        verify(exactly = 0) { ordinary.isCancelled = true }
        val unrelatedModel = creative(cursor("arc:event_sights/unrelated_aim"))
        listener.onMenuClick(unrelatedModel)
        verify(exactly = 0) { unrelatedModel.isCancelled = true }
    }

    "admin participants reload instead of swapping hands" {
        val service = mockk<ArcEventsGameplayBoundary>()
        val player = mockk<Player>()
        val playerId = UUID.randomUUID()
        every { player.uniqueId } returns playerId
        every { player.location } returns mockk()
        every { player.isSneaking } returns false
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

    "current event shortcuts consume shifted F and let the event command alias through" {
        MockBukkitTestRuntime.open().use { paper ->
            val player = paper.addPlayer("event-shortcut")
            val service = mockk<ArcEventsGameplayBoundary> {
                every { isParticipant(player.uniqueId) } returns true
                every { phase() } returns MatchPhase.ACTIVE
                every { reloadFirearm(player) } returns true
            }
            val menu = mockk<ArcEventsMenu> {
                every { openRoot(player) } returns Unit
            }
            val listener = ArcEventsListener(service, menu, mockk(), openShortcut = {
                menu.openRoot(it)
                true
            })
            fun swap(sneaking: Boolean): PlayerSwapHandItemsEvent {
                player.isSneaking = sneaking
                return PlayerSwapHandItemsEvent(player, player.inventory.itemInOffHand, player.inventory.itemInMainHand)
                    .also(listener::onSwap)
            }
            swap(true).isCancelled shouldBe true
            verify(exactly = 1) { menu.openRoot(player) }
            verify(exactly = 0) { service.reloadFirearm(player) }
            swap(false).isCancelled shouldBe true
            verify(exactly = 1) { service.reloadFirearm(player) }

            listOf("/events", "/arcevents", "/arcevents:events leave", "/arcevents:arcevents menu").forEach { message ->
                PlayerCommandPreprocessEvent(player, message).also(listener::onCommand).isCancelled shouldBe false
            }
            PlayerCommandPreprocessEvent(player, "/other:events").also(listener::onCommand).isCancelled shouldBe true
        }
    }

    "state recovery consumes plain and shifted F before firearm input" {
        MockBukkitTestRuntime.open().use { paper ->
            val player = paper.addPlayer("firearm-state-lock")
            val plugin = paper.createSimplePlugin("event-state-lock")
            val service = mockk<ArcEventsGameplayBoundary> {
                every { isParticipant(player.uniqueId) } returns true
                every { phase() } returns MatchPhase.ACTIVE
                every { reloadFirearm(player) } returns true
            }
            val menu = mockk<ArcEventsMenu> {
                every { openFirearmSettings(player, quiet = true) } returns true
            }
            var locked = true
            paper.server.pluginManager.registerEvents(ArcEventsListener(service, menu, mockk()), plugin)
            paper.server.pluginManager.registerEvents(EventStateProtection { locked }, plugin)

            fun swap(sneaking: Boolean) {
                player.isSneaking = sneaking
                paper.callEvent(PlayerSwapHandItemsEvent(player, player.inventory.itemInOffHand,
                    player.inventory.itemInMainHand)).isCancelled shouldBe true
            }

            swap(false)
            swap(true)
            verify(exactly = 0) { service.reloadFirearm(player) }
            verify(exactly = 0) { menu.openFirearmSettings(player, quiet = true) }

            locked = false
            swap(false)
            swap(true)
            verify(exactly = 1) { service.reloadFirearm(player) }
            verify(exactly = 1) { menu.openFirearmSettings(player, quiet = true) }
        }
    }

    "administrators can use commands and teleport while participating in every arcade mode" {
        MockBukkitTestRuntime.open().use { paper ->
            val player = paper.addPlayer("admin-travel")
            val destination = Location(mockk<World>(), 12.0, 70.0, 4.0)
            val service = mockk<ArcEventsGameplayBoundary>(relaxed = true) {
                every { isParticipant(player.uniqueId) } returns true
                every { phase() } returns MatchPhase.ACTIVE
                every { withinArena(any()) } returns false
            }
            val listener = ArcEventsListener(service, mockk(), mockk())
            for (mode in listOf("fishing", "zombies", "practice")) {
                every { service.isFishingParticipant(player.uniqueId) } returns (mode == "fishing")
                every { service.isZombiesParticipant(player.uniqueId) } returns (mode == "zombies")
                every { service.isPracticeParticipant(player.uniqueId) } returns (mode == "practice")
                player.isOp = true
                PlayerCommandPreprocessEvent(player, "/tp another-player").also(listener::onCommand).isCancelled shouldBe false
                PlayerTeleportEvent(player, player.location, destination).also(listener::onTeleport).isCancelled shouldBe false
                player.isOp = false
                PlayerCommandPreprocessEvent(player, "/tp another-player").also(listener::onCommand).isCancelled shouldBe true
                PlayerTeleportEvent(player, player.location, destination).also(listener::onTeleport).isCancelled shouldBe true
            }
        }
    }

    "fishing administrators cannot export or import expedition items even during restoration" {
        val service = mockk<ArcEventsGameplayBoundary>(relaxed = true)
        val player = mockk<Player>(relaxed = true)
        val playerId = UUID.randomUUID()
        every { player.uniqueId } returns playerId
        every { player.hasPermission(ArcEventsListener.ADMIN_BYPASS_PERMISSION) } returns true
        every { service.isFishingParticipant(playerId) } returns true
        every { service.isParticipant(playerId) } returns true
        every { service.phase() } returns MatchPhase.RESTORING
        every { service.canDropLoot(any(), any()) } returns false
        every { service.canPickupLoot(any(), any()) } returns false
        val drop = mockk<org.bukkit.event.player.PlayerDropItemEvent>(relaxed = true)
        every { drop.player } returns player
        val pickup = mockk<org.bukkit.event.entity.EntityPickupItemEvent>(relaxed = true)
        every { pickup.entity } returns player
        val listener = ArcEventsListener(service, mockk(), mockk())
        listener.onDrop(drop)
        listener.onPickup(pickup)
        verify(exactly = 1) { drop.isCancelled = true }
        verify(exactly = 1) { pickup.isCancelled = true }
        verify(exactly = 0) { service.registerDroppedLoot(any()) }
    }

    "match melee cannot bypass isolation by targeting an outside administrator" {
        val service = mockk<ArcEventsGameplayBoundary>(relaxed = true)
        val attacker = mockk<Player>(relaxed = true)
        val victim = mockk<Player>(relaxed = true)
        val attackerId = UUID.randomUUID()
        val victimId = UUID.randomUUID()
        every { attacker.uniqueId } returns attackerId
        every { victim.uniqueId } returns victimId
        every { victim.hasPermission(ArcEventsListener.ADMIN_BYPASS_PERMISSION) } returns true
        every { service.isParticipant(attackerId) } returns true
        every { service.shouldCancelDamage(victimId, attackerId, false, null) } returns true
        val event = mockk<EntityDamageByEntityEvent>(relaxed = true)
        every { event.entity } returns victim
        every { event.damager } returns attacker
        every { event.cause } returns DamageCause.ENTITY_ATTACK

        ArcEventsListener(service, mockk(), mockk()).onDamage(event)

        verify(exactly = 1) { event.isCancelled = true }
        verify(exactly = 1) { service.shouldCancelDamage(victimId, attackerId, false, null) }
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

    "a valid traitor blade hit is consumed by the special-item path" {
        val service = mockk<ArcEventsGameplayBoundary>(relaxed = true)
        val attacker = mockk<Player>()
        val victim = mockk<Player>()
        val attackerId = UUID.randomUUID()
        val victimId = UUID.randomUUID()
        every { attacker.uniqueId } returns attackerId
        every { victim.uniqueId } returns victimId
        every { victim.location } returns mockk()
        every { service.isParticipant(victimId) } returns true
        every { service.shouldCancelDamage(victimId, attackerId, false, null) } returns false
        every { service.useTraitorBlade(attacker, victim) } returns true
        val event = mockk<EntityDamageByEntityEvent>(relaxed = true)
        every { event.entity } returns victim
        every { event.damager } returns attacker
        every { event.cause } returns DamageCause.ENTITY_ATTACK

        ArcEventsListener(service, mockk(), mockk()).onDamage(event)

        verify(exactly = 1) { service.useTraitorBlade(attacker, victim) }
        verify(exactly = 1) { event.isCancelled = true }
        verify(exactly = 0) { service.damageMultiplier(any()) }
        verify(exactly = 0) { service.recordDamage(any(), any(), any(), any()) }
    }

    "a zero damage match projectile never uses the shooter's held blade or changes kill attribution" {
        val service = mockk<ArcEventsGameplayBoundary>(relaxed = true)
        val attacker = mockk<Player>(relaxed = true)
        val victim = mockk<Player>(relaxed = true)
        val projectile = mockk<org.bukkit.entity.Projectile>()
        val event = mockk<EntityDamageByEntityEvent>(relaxed = true)
        val matchId = UUID.randomUUID()
        val attackerId = UUID.randomUUID()
        val victimId = UUID.randomUUID()
        every { attacker.uniqueId } returns attackerId
        every { victim.uniqueId } returns victimId
        every { projectile.shooter } returns attacker
        every { event.entity } returns victim
        every { event.damager } returns projectile
        every { event.cause } returns DamageCause.PROJECTILE
        every { event.finalDamage } returns 0.0
        every { service.isParticipant(victimId) } returns true
        every { service.isParticipant(attackerId) } returns true
        every { service.projectileMatchId(projectile) } returns matchId
        every { service.damageMultiplier(attackerId) } returns 1.0
        every { service.useTraitorBlade(attacker, victim) } returns true

        ArcEventsListener(service, mockk(), mockk()).onDamage(event)

        verify(exactly = 1) { service.shouldCancelDamage(victimId, attackerId, true, matchId) }
        verify(exactly = 0) { service.useTraitorBlade(attacker, victim) }
        verify(exactly = 0) { service.recordAttack(any(), any()) }
        verify(exactly = 0) { service.recordDamage(any(), any(), any(), any()) }
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

    "event messages use their explicit transport and restore native sending when closed" {
        val player = mockk<Player>(relaxed = true)
        val message = Component.text("event")
        val deliveries = mutableListOf<Triple<Player, Component, Boolean>>()
        ArcEventsMessageDelivery.activate { recipient, component, overlay ->
            deliveries += Triple(recipient, component, overlay)
        }
        try {
            player.sendEventMessage(message)
            player.sendEventActionBar(message)
            deliveries shouldBe listOf(Triple(player, message, false), Triple(player, message, true))
            verify(exactly = 0) { player.sendMessage(any<Component>()) }
        } finally {
            ArcEventsMessageDelivery.deactivate()
        }
        player.sendEventMessage(message)
        verify(exactly = 1) { player.sendMessage(message) }
    }
})
