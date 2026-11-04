package ru.ruscrafting.events.paper

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.papermc.paper.event.player.PlayerArmSwingEvent
import io.papermc.paper.event.player.PrePlayerAttackEntityEvent
import net.kyori.adventure.text.Component
import org.bukkit.GameMode
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.block.BlockFace
import org.bukkit.entity.Entity
import org.bukkit.entity.Player
import org.bukkit.event.Event
import org.bukkit.event.block.Action
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.event.player.PlayerSwapHandItemsEvent
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType
import ru.arc.config.ConfigManager
import ru.arc.core.PaperArcRuntime
import ru.arc.core.Tasks
import ru.arc.paper.testing.MockBukkitTestRuntime
import ru.ruscrafting.events.config.ArcEventsConfig
import ru.ruscrafting.events.config.ArcEventsLocale
import ru.ruscrafting.events.config.NodeMode
import ru.ruscrafting.events.domain.FirearmId
import ru.ruscrafting.events.domain.FirearmSpec
import org.bukkit.util.Vector
import java.nio.file.Files
import java.util.UUID

class WorldFirearmsTest : FunSpec({
    test("ammunition recipe exchanges one plain nugget and is removed with its generation") {
        WorldFirearmFixture().use { f ->
            val key = f.ammoRecipe().use { recipe ->
                recipe.recipe.result.amount shouldBe 1
                f.firearms.isWorldAmmunition(recipe.recipe.result) shouldBe true
                val ingredient = recipe.recipe.choiceList.single()
                ingredient.test(ItemStack.of(Material.IRON_NUGGET)) shouldBe true
                ingredient.test(f.firearms.worldAmmunition(f.player, 1)) shouldBe false
                ingredient.test(ItemStack.of(Material.IRON_NUGGET).also { item ->
                    item.editMeta { it.displayName(Component.text("Custom nugget")) }
                }) shouldBe false
                recipe.recipe.key
            }
            f.paper.server.getRecipe(key) shouldBe null
        }
    }

    test("every packed firearm reloads its own magazine and retains its vendor identity") {
        WorldFirearmFixture().use { f ->
            for ((identity, gun) in packedFirearms) {
                f.runtime.forget(f.player.uniqueId)
                f.player.inventory.clear()
                f.player.inventory.setItemInMainHand(f.gun(identity, 0))
                f.player.inventory.setItem(10, f.firearms.worldAmmunition(f.player, 64))
                val spec = gun.spec(f.firearms.spec(gun.profile))
                f.reload() shouldBe true
                f.advance(spec.reloadTicks.toLong())
                f.runtime.state(f.player.inventory.itemInMainHand)?.loaded shouldBe spec.magazineSize
                f.runtime.ammoHudState(f.player)?.reserve shouldBe 64 - spec.magazineSize
                val before = f.accepted.size
                f.click() shouldBe true
                f.accepted.size shouldBe before + 1
                f.accepted.last().spec shouldBe spec
                f.player.inventory.itemInMainHand.itemMeta.persistentDataContainer
                    .get(f.identityKey, PersistentDataType.STRING) shouldBe identity
            }
        }
    }

    test("textured rounds and legacy nuggets combine while match reserve and renamed paper stay isolated") {
        WorldFirearmFixture().use { f ->
            f.player.inventory.setItemInMainHand(f.gun(loaded = 0))
            f.player.inventory.setItem(10, f.firearms.worldAmmunition(f.player, 3))
            f.player.inventory.setItem(11, ItemStack.of(Material.IRON_NUGGET, 2))
            f.player.inventory.setItem(12, f.firearms.ammunition(f.player, "other-match", 64))
            f.player.inventory.setItem(13, ItemStack.of(Material.PAPER, 64))
            f.runtime.ammoHudState(f.player)?.reserve shouldBe 5
            f.reload() shouldBe true
            f.advance(f.reloadTicks)
            f.runtime.state(f.player.inventory.itemInMainHand)?.loaded shouldBe 5
            f.player.inventory.getItem(10) shouldBe null
            f.player.inventory.getItem(11) shouldBe null
            f.player.inventory.getItem(12)?.amount shouldBe 64
            f.player.inventory.getItem(13)?.amount shouldBe 64
        }
    }

    test("burst guns fire every remaining round without inflating the partial volley") {
        WorldFirearmFixture().use { f ->
            for (id in listOf(FirearmId.G36, FirearmId.AEK_971, FirearmId.RPL_20)) {
                val spec = f.firearms.spec(id)
                for (loaded in 1 until spec.roundsPerShot) {
                    f.runtime.forget(f.player.uniqueId)
                    f.player.inventory.setItemInMainHand(f.gun(WORLD_GUN_IDS.getValue(id), loaded))
                    val before = f.accepted.size
                    f.click() shouldBe true
                    f.accepted.size shouldBe before + 1
                    f.accepted.last().spec shouldBe spec.copy(roundsPerShot = loaded, pellets = loaded)
                    f.runtime.state(f.player.inventory.itemInMainHand)?.loaded shouldBe 0
                    f.vanillaReserve() shouldBe 0
                }
            }
            f.runtime.forget(f.player.uniqueId)
            val shotgun = f.firearms.spec(FirearmId.DOUBLE_BARREL)
            f.player.inventory.setItemInMainHand(f.gun(WORLD_GUN_IDS.getValue(shotgun.id), 1))
            f.click() shouldBe true
            f.accepted.last().spec shouldBe shotgun
            f.runtime.state(f.player.inventory.itemInMainHand)?.loaded shouldBe 0
        }
    }

    test("an empty trigger starts one ordinary reload when vanilla reserve exists") {
        WorldFirearmFixture().use { f ->
            f.player.inventory.setItemInMainHand(f.gun(loaded = 0))
            f.player.inventory.setItem(10, ItemStack.of(Material.IRON_NUGGET, 8))
            f.click() shouldBe true
            f.runtime.ammoHudState(f.player)?.reloading shouldBe true
            f.click() shouldBe true
            f.accepted.size shouldBe 0
            f.vanillaReserve() shouldBe 8
            verify(exactly = 1) { f.scopes.animateReload(any(), any(), any(), any(), any()) }
            f.advance(f.reloadTicks)
            f.runtime.state(f.player.inventory.itemInMainHand)?.loaded shouldBe 6
            f.vanillaReserve() shouldBe 2
        }
    }

    test("the last accepted burst starts one reload only after its final muzzle") {
        WorldFirearmFixture().use { f ->
            val id = FirearmId.G36
            f.player.inventory.setItemInMainHand(f.gun(WORLD_GUN_IDS.getValue(id), 2))
            f.player.inventory.setItem(10, ItemStack.of(Material.IRON_NUGGET, 7))
            f.click() shouldBe true
            f.runtime.state(f.player.inventory.itemInMainHand)?.loaded shouldBe 0
            f.vanillaReserve() shouldBe 7
            f.click() shouldBe true
            verify(exactly = 0) { f.scopes.animateReload(any(), any(), any(), any(), any()) }
            f.accepted.single().afterFiring(true)
            f.accepted.single().afterFiring(true)
            verify(exactly = 1) { f.scopes.animateReload(any(), any(), any(), any(), any()) }
            f.vanillaReserve() shouldBe 7
            f.advance(f.firearms.spec(id).reloadTicks.toLong())
            f.runtime.state(f.player.inventory.itemInMainHand)?.loaded shouldBe 7
            f.vanillaReserve() shouldBe 0
        }
    }

    test("deferred reloads are revoked by abort cancellation item slot world authority and creative changes") {
        WorldFirearmFixture().use { f ->
            val elsewhere = f.paper.addSimpleWorld("auto-elsewhere")
            val changes: List<() -> Unit> = listOf(
                { f.runtime.cancel(f.player.uniqueId) },
                { f.player.inventory.heldItemSlot = 1 },
                { f.player.inventory.setItemInMainHand(f.gun(loaded = 0).also { item -> item.editMeta { it.lore(listOf(Component.text("replacement"))) } }) },
                { f.player.teleport(Location(elsewhere, 0.0, 64.0, 0.0)); Unit },
                { f.actionsAllowed = false },
                { f.player.gameMode = GameMode.CREATIVE },
            )
            for (change in changes) {
                f.runtime.forget(f.player.uniqueId)
                f.actionsAllowed = true
                f.player.gameMode = GameMode.SURVIVAL
                f.player.teleport(Location(f.world, 0.0, 64.0, 0.0))
                f.player.inventory.clear()
                f.player.inventory.heldItemSlot = 0
                f.player.inventory.setItemInMainHand(f.gun(loaded = 1))
                f.player.inventory.setItem(10, ItemStack.of(Material.IRON_NUGGET, 6))
                f.click() shouldBe true
                val shot = f.accepted.last()
                change()
                shot.afterFiring(true)
                f.advance(f.reloadTicks)
                f.runtime.state(f.player.inventory.getItem(0))?.loaded shouldBe 0
                f.vanillaReserve() shouldBe 6
            }
            verify(exactly = 0) { f.scopes.animateReload(any(), any(), any(), any(), any()) }
            f.runtime.forget(f.player.uniqueId)
            f.player.gameMode = GameMode.SURVIVAL
            f.player.inventory.heldItemSlot = 0
            f.player.inventory.setItemInMainHand(f.gun(loaded = 1))
            f.click() shouldBe true
            f.accepted.last().afterFiring(false)
            f.advance(f.reloadTicks)
            f.vanillaReserve() shouldBe 6
            f.click() shouldBe true
            f.advance(f.reloadTicks)
            f.runtime.state(f.player.inventory.itemInMainHand)?.loaded shouldBe 6
            f.vanillaReserve() shouldBe 0
        }
    }

    test("manual reload replaces a deferred auto reload and rejected admission never starts one") {
        WorldFirearmFixture().use { f ->
            f.player.inventory.setItemInMainHand(f.gun(loaded = 1))
            f.player.inventory.setItem(10, ItemStack.of(Material.IRON_NUGGET, 8))
            f.admission = false
            f.click() shouldBe true
            f.runtime.state(f.player.inventory.itemInMainHand)?.loaded shouldBe 1
            f.vanillaReserve() shouldBe 8
            f.accepted.size shouldBe 0
            verify(exactly = 0) { f.scopes.animateReload(any(), any(), any(), any(), any()) }
            f.admission = true
            f.click() shouldBe true
            f.reload() shouldBe true
            f.accepted.single().afterFiring(true)
            verify(exactly = 1) { f.scopes.animateReload(any(), any(), any(), any(), any()) }
            f.runtime.cancel(f.player.uniqueId)
            f.accepted.single().afterFiring(true)
            f.advance(f.reloadTicks)
            f.runtime.state(f.player.inventory.itemInMainHand)?.loaded shouldBe 0
            f.vanillaReserve() shouldBe 8
        }
    }

    test("creative empty bursts never acquire an automatic reload or consume their reserve") {
        WorldFirearmFixture().use { f ->
            val gun = f.gun(WORLD_GUN_IDS.getValue(FirearmId.G36), 0)
            f.player.inventory.setItemInMainHand(gun)
            f.player.inventory.setItem(10, ItemStack.of(Material.IRON_NUGGET, 6))
            f.player.gameMode = GameMode.CREATIVE
            f.click() shouldBe true
            f.accepted.single().spec shouldBe f.firearms.spec(FirearmId.G36)
            f.accepted.single().afterFiring(true)
            f.advance(100)
            f.player.inventory.itemInMainHand shouldBe gun
            f.vanillaReserve() shouldBe 6
            verify(exactly = 0) { f.scopes.animateReload(any(), any(), any(), any(), any()) }
        }
    }

    test("first reload normalizes a paper gun before capturing its magazine lease") {
        WorldFirearmFixture().use { f ->
            val gun = f.gun(identity = WORLD_GUN_IDS.getValue(FirearmId.G36)).also { it.type = Material.PAPER }
            gun.maxStackSize shouldBe 64
            f.player.inventory.setItemInMainHand(gun)
            f.player.inventory.setItem(10, ItemStack.of(Material.IRON_NUGGET, 4))
            f.reload() shouldBe true
            f.player.inventory.itemInMainHand.maxStackSize shouldBe 1
            f.advance(f.firearms.spec(FirearmId.G36).reloadTicks.toLong())
            f.runtime.state(f.player.inventory.itemInMainHand) shouldBe WorldFirearmState(FirearmId.G36, 4)
            f.vanillaReserve() shouldBe 0
        }
    }

    test("world hologram reads magazine authority and hides when firearm use is unavailable") {
        WorldFirearmFixture().use { f ->
            f.player.inventory.setItemInMainHand(f.gun(loaded = 3))
            f.player.inventory.setItem(10, ItemStack.of(Material.IRON_NUGGET, 8))
            f.runtime.ammoHudState(f.player) shouldBe FirearmAmmoHudState(3, 6, 8)
            f.player.gameMode = GameMode.CREATIVE
            f.runtime.ammoHudState(f.player) shouldBe FirearmAmmoHudState(3, 6, 8, creative = true)
            f.actionsAllowed = false
            f.runtime.ammoHudState(f.player) shouldBe null
            f.runtime.state(f.player.inventory.itemInMainHand)?.loaded shouldBe 3
        }
    }

    test("all mapped raw guns start empty on a relay independently of event weapon enablement") {
        WorldFirearmFixture().use { f ->
            f.settings.nodeMode shouldBe NodeMode.RELAY
            f.firearms.enabled shouldBe false
            WORLD_GUN_IDS.forEach { (id, identity) ->
                val gun = f.gun(identity = identity)
                f.runtime.state(gun) shouldBe WorldFirearmState(id, 0)
                f.player.inventory.setItemInMainHand(gun)
                f.click() shouldBe true
                f.player.inventory.itemInMainHand shouldBe gun
                f.runtime.forget(f.player.uniqueId)
            }
            f.accepted.size shouldBe 0

            val fake = ItemStack.of(Material.IRON_HORSE_ARMOR).also {
                it.editMeta { meta -> meta.displayName(Component.text("Revolver")) }
            }
            f.player.inventory.setItemInMainHand(fake)
            f.click() shouldBe false
            f.player.inventory.itemInMainHand shouldBe fake
        }
    }

    test("reload spends only vanilla nuggets once and preserves the original IA metadata") {
        WorldFirearmFixture().use { f ->
            val original = f.gun()
            f.player.inventory.setItemInMainHand(original)
            f.player.inventory.setItem(10, ItemStack.of(Material.IRON_NUGGET, 4))
            f.player.inventory.setItem(12, ItemStack.of(Material.IRON_NUGGET, 4))
            val eventAmmo = f.firearms.ammunition(f.player, "event-match", 9)
            val renamed = ItemStack.of(Material.IRON_NUGGET, 64).also {
                it.editMeta { meta -> meta.displayName(Component.text("Ammunition")) }
            }
            f.player.inventory.setItem(11, eventAmmo)
            f.player.inventory.setItem(13, renamed)

            f.reload() shouldBe true
            f.reload() shouldBe true
            f.runtime.state(f.player.inventory.itemInMainHand)?.loaded shouldBe 0
            f.vanillaReserve() shouldBe 8
            f.advance(f.reloadTicks)

            f.runtime.state(f.player.inventory.itemInMainHand)?.loaded shouldBe 6
            f.vanillaReserve() shouldBe 2
            f.player.inventory.getItem(11) shouldBe eventAmmo
            f.player.inventory.getItem(13) shouldBe renamed
            val restored = f.player.inventory.itemInMainHand.clone().also { item ->
                item.editMeta { meta ->
                    meta.persistentDataContainer.remove(f.firearmKey)
                    meta.persistentDataContainer.remove(f.loadedKey)
                }
            }
            restored shouldBe original
            f.runtime.state(original)?.loaded shouldBe 0
            f.reload() shouldBe true
            f.advance(f.reloadTicks * 2)
            f.runtime.state(f.player.inventory.itemInMainHand)?.loaded shouldBe 6
            f.vanillaReserve() shouldBe 2
        }
    }

    test("cancellation and a changed slot snapshot world or authority never debit a pending reload") {
        WorldFirearmFixture().use { f ->
            val elsewhere = f.paper.addSimpleWorld("elsewhere")
            val changes: List<() -> Unit> = listOf(
                { f.runtime.cancel(f.player.uniqueId) },
                { f.player.inventory.heldItemSlot = 1 },
                { f.player.inventory.setItemInMainHand(f.gun().also { gun -> gun.editMeta { it.lore(listOf(Component.text("changed"))) } }) },
                { f.actionsAllowed = false },
                { f.player.teleport(Location(elsewhere, 0.0, 64.0, 0.0)); Unit },
            )
            changes.forEach { change ->
                f.actionsAllowed = true
                f.player.teleport(Location(f.world, 0.0, 64.0, 0.0))
                f.player.inventory.clear()
                f.player.inventory.heldItemSlot = 0
                f.player.inventory.setItemInMainHand(f.gun())
                f.player.inventory.setItem(10, ItemStack.of(Material.IRON_NUGGET, 6))
                f.reload() shouldBe true
                change()
                f.advance(f.reloadTicks)
                f.runtime.state(f.player.inventory.getItem(0))?.loaded shouldBe 0
                f.vanillaReserve() shouldBe 6
            }
            f.actionsAllowed = true
            f.player.teleport(Location(f.world, 0.0, 64.0, 0.0))
            f.player.inventory.heldItemSlot = 0
            f.reload() shouldBe true
            f.advance(f.reloadTicks)
            f.runtime.state(f.player.inventory.itemInMainHand)?.loaded shouldBe 6
            f.vanillaReserve() shouldBe 0
        }
    }

    test("stacked event-tagged or malformed guns cannot fire or load and event ammo is never reserve") {
        WorldFirearmFixture().use { f ->
            f.player.inventory.setItem(10, ItemStack.of(Material.IRON_NUGGET, 32))
            val invalid = listOf(
                f.gun(loaded = 6).also { it.amount = 2 },
                f.gun(loaded = 6).also { gun -> gun.editMeta { meta ->
                    meta.persistentDataContainer.set(f.eventItemKey, PersistentDataType.STRING, EventItemKind.FIREARM.name)
                } },
                f.gun().also { gun -> gun.editMeta { meta ->
                    meta.persistentDataContainer.set(f.loadedKey, PersistentDataType.INTEGER, 6)
                } },
                f.gun(loaded = 6).also { gun -> gun.editMeta { meta ->
                    meta.persistentDataContainer.set(f.loadedKey, PersistentDataType.STRING, "6")
                } },
                f.gun(loaded = 7),
                f.gun(loaded = 6).also { gun -> gun.editMeta { meta ->
                    meta.persistentDataContainer.set(f.firearmKey, PersistentDataType.STRING, FirearmId.G36.name)
                } },
            )
            invalid.forEach { gun ->
                f.runtime.state(gun) shouldBe null
                f.player.inventory.setItemInMainHand(gun)
                f.click()
                f.reload()
                f.advance(f.reloadTicks)
                f.player.inventory.itemInMainHand shouldBe gun
                f.vanillaReserve() shouldBe 32
            }
            f.accepted.size shouldBe 0
            f.player.inventory.clear()
            f.player.inventory.setItemInMainHand(f.gun())
            f.player.inventory.setItem(10, f.firearms.ammunition(f.player, "event-match", 32))
            f.reload() shouldBe true
            f.advance(f.reloadTicks)
            f.runtime.state(f.player.inventory.itemInMainHand)?.loaded shouldBe 0
            f.player.inventory.getItem(10)?.amount shouldBe 32
        }
    }

    test("LMB and entity packet attacks share one cooldown including after a weapon switch") {
        WorldFirearmFixture().use { f ->
            f.player.inventory.setItemInMainHand(f.gun(loaded = 6))
            val air = f.interaction().also { it.isCancelled = true }
            f.runtime.interact(air) shouldBe true
            air.useItemInHand() shouldBe Event.Result.DENY
            val melee = PrePlayerAttackEntityEvent(f.player, mockk<Entity>(), true)
            f.runtime.attack(melee) shouldBe true
            melee.isCancelled shouldBe true
            f.accepted.size shouldBe 1
            f.runtime.state(f.player.inventory.itemInMainHand)?.loaded shouldBe 5
            f.runtime.interact(f.interaction(Action.RIGHT_CLICK_AIR)) shouldBe false
            f.runtime.interact(f.interaction(hand = EquipmentSlot.OFF_HAND)) shouldBe false

            f.player.inventory.setItemInMainHand(f.gun(identity = WORLD_GUN_IDS.getValue(FirearmId.FIVE_SEVEN), loaded = 20))
            f.runtime.cancel(f.player.uniqueId)
            f.click()
            f.accepted.size shouldBe 1
            f.runtime.state(f.player.inventory.itemInMainHand)?.loaded shouldBe 20
            f.advance(f.firearms.spec(FirearmId.REVOLVER).cooldownTicks.toLong())
            f.runtime.attack(PrePlayerAttackEntityEvent(f.player, mockk<Entity>(), true)) shouldBe true
            f.accepted.size shouldBe 2
            f.runtime.state(f.player.inventory.itemInMainHand)?.loaded shouldBe 19

            f.advance(100)
            val denied = PrePlayerAttackEntityEvent(f.player, mockk<Entity>(), true).also { it.isCancelled = true }
            f.runtime.attack(denied) shouldBe true
            f.accepted.size shouldBe 2
        }
    }

    test("ordinary-world gun input cancels native melee and its separate arm animation") {
        WorldFirearmFixture().use { f ->
            val listener = ArcEventsListener(mockk(relaxed = true), mockk(), EventItemResolver { null }, f.runtime)
            FirearmItems({ scopePreviewFirearm(it.itemMeta.persistentDataContainer.get(f.identityKey, PersistentDataType.STRING)) != null }).use { policy ->
                f.player.inventory.setItemInMainHand(f.gun(loaded = 6))
                val air = f.interaction()
                listener.onInteract(air)
                air.useItemInHand() shouldBe Event.Result.DENY
                val airSwing = PlayerArmSwingEvent(f.player, EquipmentSlot.HAND)
                policy.onSwing(airSwing)
                airSwing.isCancelled shouldBe true
                f.accepted.size shouldBe 1

                f.advance(f.firearms.spec(FirearmId.REVOLVER).cooldownTicks.toLong())
                val attack = PrePlayerAttackEntityEvent(f.player, mockk<Entity>(), true)
                listener.onFirearmAttack(attack)
                attack.isCancelled shouldBe true
                val entitySwing = PlayerArmSwingEvent(f.player, EquipmentSlot.HAND)
                policy.onSwing(entitySwing)
                entitySwing.isCancelled shouldBe true
                f.accepted.size shouldBe 2
                f.runtime.state(f.player.inventory.itemInMainHand)?.loaded shouldBe 4

                f.player.inventory.setItemInMainHand(ItemStack.of(Material.IRON_SWORD))
                val ordinaryAttack = PrePlayerAttackEntityEvent(f.player, mockk<Entity>(), true)
                listener.onFirearmAttack(ordinaryAttack)
                ordinaryAttack.isCancelled shouldBe false
            }
        }
    }

    test("a denied shot admission does not consume a round or acquire cooldown") {
        WorldFirearmFixture().use { f ->
            f.player.inventory.setItemInMainHand(f.gun(loaded = 6))
            f.admission = false
            f.click() shouldBe true
            f.runtime.state(f.player.inventory.itemInMainHand)?.loaded shouldBe 6
            f.admission = true
            f.click() shouldBe true
            f.accepted.size shouldBe 1
            f.runtime.state(f.player.inventory.itemInMainHand)?.loaded shouldBe 5
        }
    }

    test("impact rechecks PvP target and source authority then delegates native damage with its shooter") {
        WorldFirearmFixture().use { f ->
            f.player.inventory.setItemInMainHand(f.gun(loaded = 6))
            f.click()
            val shot = f.accepted.single()
            val impact = FirearmHit(Vector(), headshot = false)
            val victim = mockk<Player>(relaxed = true)
            every { victim.uniqueId } returns UUID.randomUUID()
            every { victim.world } returns f.world
            every { victim.isValid } returns true
            every { victim.isDead } returns false
            every { victim.isOnline } returns true
            every { victim.gameMode } returns GameMode.SURVIVAL

            shot.target(f.player) shouldBe false
            shot.target(mockk<Entity>()) shouldBe false
            f.world.pvp = false
            shot.target(victim) shouldBe true
            shot.hit(victim, impact, 8.0)
            f.world.pvp = true
            f.targetsAllowed = false
            shot.target(victim) shouldBe true
            shot.hit(victim, impact, 8.0)
            verify(exactly = 0) { victim.damage(any<Double>(), any<Entity>()) }

            f.targetsAllowed = true
            f.player.inventory.setItemInMainHand(ItemStack.empty())
            shot.valid() shouldBe true
            shot.target(victim) shouldBe true
            shot.hit(victim, impact, 8.0)
            verify(exactly = 1) { victim.damage(8.0, f.player) }
            f.accepted.size shouldBe 1

            f.actionsAllowed = false
            shot.valid() shouldBe false
            shot.hit(victim, impact, 8.0)
            f.actionsAllowed = true
            f.player.teleport(Location(f.paper.addSimpleWorld("other"), 0.0, 64.0, 0.0))
            shot.valid() shouldBe false
            shot.hit(victim, impact, 8.0)
            verify(exactly = 1) { victim.damage(any<Double>(), any<Entity>()) }
        }
    }

    test("creative fires without ammunition and preserves the magazine through game mode changes") {
        WorldFirearmFixture().use { f ->
            f.player.gameMode = GameMode.CREATIVE
            listOf(f.gun(), f.gun(loaded = 0)).forEach { original ->
                f.player.inventory.setItemInMainHand(original)
                val before = f.accepted.size
                f.click() shouldBe true
                f.accepted.size shouldBe before + 1
                f.player.inventory.itemInMainHand shouldBe original
                f.vanillaReserve() shouldBe 0
                f.reload() shouldBe true
                f.advance(f.reloadTicks)
                f.player.inventory.itemInMainHand shouldBe original
            }
            f.player.gameMode = GameMode.SURVIVAL
            f.click() shouldBe true
            f.accepted.size shouldBe 2
            f.runtime.state(f.player.inventory.itemInMainHand)?.loaded shouldBe 0

            val original = f.gun(loaded = 2)
            f.player.inventory.setItemInMainHand(original)
            f.player.inventory.setItem(10, ItemStack.of(Material.IRON_NUGGET, 4))
            f.player.gameMode = GameMode.CREATIVE
            f.advance(f.reloadTicks)
            f.click() shouldBe true
            f.accepted.size shouldBe 3
            f.reload() shouldBe true
            f.advance(f.reloadTicks)
            f.player.inventory.itemInMainHand shouldBe original
            f.vanillaReserve() shouldBe 4
            f.player.gameMode = GameMode.ADVENTURE
            f.click() shouldBe true
            f.accepted.size shouldBe 4
            f.runtime.state(f.player.inventory.itemInMainHand)?.loaded shouldBe 1
            f.vanillaReserve() shouldBe 4
        }
    }

    test("creative cannot complete or carry a pending survival reload back into survival") {
        WorldFirearmFixture().use { f ->
            val transitions: List<() -> Unit> = listOf(
                { f.player.gameMode = GameMode.CREATIVE },
                {
                    f.player.gameMode = GameMode.CREATIVE
                    f.reload() shouldBe true
                    f.player.gameMode = GameMode.SURVIVAL
                },
                {
                    f.player.gameMode = GameMode.CREATIVE
                    val before = f.accepted.size
                    f.click() shouldBe true
                    f.accepted.size shouldBe before + 1
                    f.player.gameMode = GameMode.SURVIVAL
                },
            )
            transitions.forEach { transition ->
                f.player.gameMode = GameMode.SURVIVAL
                f.player.inventory.setItemInMainHand(f.gun())
                f.player.inventory.setItem(10, ItemStack.of(Material.IRON_NUGGET, 6))
                f.reload() shouldBe true
                transition()
                f.advance(f.reloadTicks)
                f.runtime.state(f.player.inventory.itemInMainHand)?.loaded shouldBe 0
                f.vanillaReserve() shouldBe 6
            }
        }
    }

    test("reload presentation follows the exact pending inventory and closes after completion or cancellation") {
        WorldFirearmFixture().use { f ->
            var closes = 0
            var stillCurrent: () -> Boolean = { false }
            every { f.scopes.animateReload(any(), any(), any(), any(), any()) } answers {
                stillCurrent = arg(4)
                AutoCloseable { closes++ }
            }
            f.player.inventory.setItemInMainHand(f.gun())
            f.player.inventory.setItem(10, ItemStack.of(Material.IRON_NUGGET, 6))
            f.reload() shouldBe true
            stillCurrent() shouldBe true
            f.vanillaReserve() shouldBe 6
            f.reload() shouldBe true
            closes shouldBe 0
            stillCurrent() shouldBe true
            f.advance(f.reloadTicks)
            stillCurrent() shouldBe false
            closes shouldBe 1
            f.runtime.state(f.player.inventory.itemInMainHand)?.loaded shouldBe 6
            f.vanillaReserve() shouldBe 0

            f.player.inventory.setItemInMainHand(f.gun())
            f.player.inventory.setItem(10, ItemStack.of(Material.IRON_NUGGET, 6))
            f.reload() shouldBe true
            stillCurrent() shouldBe true
            f.player.inventory.setItemInMainHand(ItemStack.of(Material.STONE))
            stillCurrent() shouldBe false
            f.runtime.cancel(f.player.uniqueId)
            f.advance(f.reloadTicks)
            closes shouldBe 2
            f.vanillaReserve() shouldBe 6
            f.player.inventory.itemInMainHand.type shouldBe Material.STONE
        }
    }

    test("survival and adventure consume reserve and shutdown cancels reloads and impacts") {
        WorldFirearmFixture().use { f ->
            listOf(GameMode.SURVIVAL, GameMode.ADVENTURE).forEach { mode ->
                f.player.gameMode = mode
                f.player.inventory.clear()
                f.player.inventory.setItemInMainHand(f.gun())
                f.player.inventory.setItem(10, ItemStack.of(Material.IRON_NUGGET, 2))
                f.reload() shouldBe true
                f.advance(f.reloadTicks)
                f.runtime.state(f.player.inventory.itemInMainHand)?.loaded shouldBe 2
                f.vanillaReserve() shouldBe 0
                f.click() shouldBe true
                f.runtime.state(f.player.inventory.itemInMainHand)?.loaded shouldBe 1
            }
            val shot = f.accepted.last()
            f.player.inventory.setItem(10, ItemStack.of(Material.IRON_NUGGET, 3))
            f.reload() shouldBe true
            f.runtime.close()
            f.advance(f.reloadTicks)
            f.runtime.state(f.player.inventory.itemInMainHand)?.loaded shouldBe 1
            f.vanillaReserve() shouldBe 3
            shot.valid() shouldBe false
            f.click() shouldBe false
        }
    }
})

private data class WorldShot(
    val spec: FirearmSpec,
    val valid: () -> Boolean,
    val target: (Entity) -> Boolean,
    val afterFiring: (Boolean) -> Unit,
    val hit: (Entity, FirearmHit, Double) -> Unit,
)

private class WorldFirearmFixture : AutoCloseable {
    val paper = MockBukkitTestRuntime.open()
    private val plugin = paper.createSimplePlugin("ArcEventsWorldFirearmsTest")
    private val dataRoot = Files.createTempDirectory("arcevents-world-firearms-")
    val world = paper.addSimpleWorld("ordinary")
    val player = paper.addPlayer("Shooter")
    val identityKey = NamespacedKey(plugin, "fixture_ia_identity")
    val firearmKey = NamespacedKey(plugin, "world_firearm")
    val loadedKey = NamespacedKey(plugin, "world_loaded_rounds")
    val eventItemKey = NamespacedKey(plugin, "event_item")
    private val vanillaAmmunition = ItemStack.of(Material.IRON_NUGGET)
    val settings: ArcEventsConfig
    val firearms: TttFirearms
    val runtime: WorldFirearms
    val accepted = mutableListOf<WorldShot>()
    val scopes = mockk<TttScopes>(relaxed = true)
    var actionsAllowed = true
    var targetsAllowed = true
    var admission = true
    private var now = 0L
    val reloadTicks: Long get() = firearms.spec(FirearmId.REVOLVER).reloadTicks.toLong()

    init {
        PaperArcRuntime.installScheduling(plugin)
        every { scopes.projectedZoom(any()) } returns null
        ConfigManager.clear()
        listOf("config.yml", "lang/ru.yml", "lang/en.yml").forEach { resource ->
            val destination = dataRoot.resolve(resource)
            Files.createDirectories(destination.parent)
            val source = requireNotNull(WorldFirearmsTest::class.java.getResourceAsStream("/$resource"))
                .use { it.bufferedReader().readText() }
            Files.writeString(destination,
                if (resource == "config.yml") source.replace("weapons:\n  enabled: true", "weapons:\n  enabled: false") else source)
        }
        settings = ArcEventsConfig.load(dataRoot)
        val locale = ArcEventsLocale(dataRoot) { settings }
        firearms = TttFirearms(plugin, locale) { settings }
        val shots = mockk<FirearmShots>()
        every { shots.fire(any(), any(), any(), any(), any(), any(), any(), any()) } answers {
            if (admission) {
                accepted += WorldShot(arg(1), arg(3), arg(4), arg(6), arg(7))
                true
            } else false
        }
        runtime = WorldFirearms(plugin, firearms, locale, scopes,
            { it.itemMeta.persistentDataContainer.get(identityKey, PersistentDataType.STRING) },
            { actionsAllowed }, { _, _ -> targetsAllowed }, shots, { now })
        player.teleport(Location(world, 0.0, 64.0, 0.0))
        player.gameMode = GameMode.SURVIVAL
        world.pvp = true
    }

    fun gun(identity: String = WORLD_GUN_IDS.getValue(FirearmId.REVOLVER), loaded: Int? = null): ItemStack =
        ItemStack.of(Material.IRON_HORSE_ARMOR).also { item ->
            item.editMeta { meta ->
                meta.displayName(Component.text("Collector's gun"))
                meta.lore(listOf(Component.text("Original ItemsAdder lore")))
                meta.persistentDataContainer.set(identityKey, PersistentDataType.STRING, identity)
                if (loaded != null) {
                    meta.persistentDataContainer.set(firearmKey, PersistentDataType.STRING,
                        requireNotNull(scopePreviewFirearm(identity)).name)
                    meta.persistentDataContainer.set(loadedKey, PersistentDataType.INTEGER, loaded)
                }
            }
        }

    fun ammoRecipe() = FirearmAmmoRecipe(plugin, firearms)

    fun interaction(action: Action = Action.LEFT_CLICK_AIR, hand: EquipmentSlot = EquipmentSlot.HAND) =
        PlayerInteractEvent(player, action, player.inventory.itemInMainHand, null, BlockFace.SELF, hand)

    fun click() = runtime.interact(interaction())

    fun reload() = runtime.swap(PlayerSwapHandItemsEvent(player, player.inventory.itemInOffHand, player.inventory.itemInMainHand))

    fun advance(ticks: Long) {
        now += ticks * 50L
        paper.performTicks(ticks)
    }

    fun vanillaReserve() = player.inventory.storageContents.sumOf {
        if (it != null && it.isSimilar(vanillaAmmunition)) it.amount else 0
    }

    override fun close() {
        try {
            runtime.close()
        } finally {
            Tasks.reset()
            ConfigManager.clear()
            paper.close()
            check(dataRoot.toFile().deleteRecursively())
        }
    }
}

private val WORLD_GUN_IDS = mapOf(
    FirearmId.FLINTLOCK to "voxelspawns_megaflintlocks:vs_pistol",
    FirearmId.REVOLVER to "voxelspawns_megaflintlocks:vs_revolver",
    FirearmId.HAND_CANNON to "voxelspawns_megaflintlocks:vs_cannon",
    FirearmId.DOUBLE_BARREL to "voxelspawns_megaflintlocks:vs_rifle_double",
    FirearmId.FIVE_SEVEN to "gold_guns:gold_five_seven",
    FirearmId.G36 to "gold_guns:gold_g36",
    FirearmId.AEK_971 to "gold_guns:gold_aek_971",
    FirearmId.RPL_20 to "gold_guns:gold_rpl20",
    FirearmId.VEPR_12 to "gold_guns:gold_vepr_12",
    FirearmId.M1_GARAND to "gold_guns:gold_m1_garand",
    FirearmId.VSS_VINTOREZ to "gold_guns:gold_vss_vintorez",
    FirearmId.MCMILLAN to "gold_guns:gold_mcmillan_new",
)
