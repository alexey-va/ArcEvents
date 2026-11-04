package ru.ruscrafting.events.paper

import com.github.retrooper.packetevents.PacketEvents
import com.github.retrooper.packetevents.PacketEventsAPI
import com.github.retrooper.packetevents.event.PacketSendEvent
import com.github.retrooper.packetevents.event.UserDisconnectEvent
import com.github.retrooper.packetevents.manager.server.ServerManager
import com.github.retrooper.packetevents.manager.server.ServerVersion
import com.github.retrooper.packetevents.protocol.component.ComponentTypes
import com.github.retrooper.packetevents.protocol.component.builtin.item.ItemCustomModelData
import com.github.retrooper.packetevents.protocol.component.builtin.item.ItemModel
import com.github.retrooper.packetevents.protocol.component.builtin.item.ItemSwingAnimation
import com.github.retrooper.packetevents.protocol.item.ItemStack as PacketItemStack
import com.github.retrooper.packetevents.protocol.item.type.ItemTypes
import com.github.retrooper.packetevents.protocol.nbt.NBTCompound
import com.github.retrooper.packetevents.protocol.nbt.NBTInt
import com.github.retrooper.packetevents.protocol.nbt.NBTString
import com.github.retrooper.packetevents.resources.ResourceLocation
import com.github.retrooper.packetevents.protocol.packettype.PacketType
import com.github.retrooper.packetevents.protocol.player.Equipment
import com.github.retrooper.packetevents.protocol.player.EquipmentSlot
import com.github.retrooper.packetevents.protocol.player.ClientVersion
import com.github.retrooper.packetevents.protocol.player.User
import com.github.retrooper.packetevents.wrapper.PacketWrapper
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityEquipment
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerPlayerAbilities
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSetPlayerInventory
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSetCursorItem
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSetSlot
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerWindowItems
import io.github.retrooper.packetevents.impl.netty.NettyManagerImpl
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.bukkit.GameMode
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.PlayerInventory
import ru.arc.core.ScheduledTask
import ru.ruscrafting.events.domain.FirearmId
import java.util.UUID
import java.util.concurrent.CompletableFuture

class TttScopesTest : StringSpec({
    "reload samples all twelve mechanical model sequences at authoritative gameplay ticks" {
        ScopeFixture().use { f ->
            f.real = gunPacketItem()
            f.baseline()
            for (id in FirearmId.entries) {
                f.packets.clear()
                val authoritativeBefore = f.real.copy()
                val duration = if (id == FirearmId.FIVE_SEVEN) 28 else 64
                val handle = f.scopes.animateReload(f.player, f.target.copy(firearm = id), duration) { true }
                f.scopes.isReloading(f.playerId) shouldBe true
                f.models().single() shouldBe "arc:event_sights/${id.name.lowercase()}_reload_00"
                f.scopes.isAiming(f.playerId) shouldBe false
                f.scopes.projectedZoom(f.playerId) shouldBe null
                f.tick(duration - 1)
                f.models().last() shouldBe "arc:event_sights/${id.name.lowercase()}_reload_39"
                (f.models().size <= minOf(duration, 40)) shouldBe true
                f.tick(1)
                f.scopes.isReloading(f.playerId) shouldBe false
                f.models().last() shouldBe "arc:event_sights/${id.name.lowercase()}_hip"
                f.pendingTimers() shouldBe 0
                handle.close()
                f.real shouldBe authoritativeBefore
                f.scopes.clear(f.playerId)
            }
        }
    }

    "reload uses the gameplay lease while ordinary aim authorization is temporarily false" {
        ScopeFixture().use { f ->
            f.real = gunPacketItem()
            f.baseline()
            f.scopeAuthorized = false
            var current = true
            f.scopes.animateReload(f.player, f.target, 42) { current }
            f.scopes.validate { _, _ -> false }
            f.tick(5)
            f.models().last().contains("_reload_") shouldBe true
            current = false
            f.real = PacketItemStack.builder().type(ItemTypes.GOLDEN_HOE).build()
            f.tick(1)
            f.pendingTimers() shouldBe 0
            (f.packets.last() as WrapperPlayServerSetPlayerInventory).stack shouldBe f.real
            f.runCancelled()
            (f.packets.last() as WrapperPlayServerSetPlayerInventory).stack shouldBe f.real
        }
    }

    "reload inventory projection preserves metadata and never covers a replacement item" {
        ScopeFixture().use { f ->
            f.real = gunPacketItem()
            f.scopes.animateReload(f.player, f.target.copy(matchId = null), 42) { true }
            f.tick(9)
            val held = f.filter(WrapperPlayServerSetPlayerInventory(2, f.real)) as WrapperPlayServerSetPlayerInventory
            held.stack.getComponent(ComponentTypes.ITEM_MODEL).get().modelLocation.toString() shouldBe f.models().last()
            held.stack.getComponent(ComponentTypes.CUSTOM_DATA) shouldBe f.real.getComponent(ComponentTypes.CUSTOM_DATA)
            val replacement = PacketItemStack.builder().type(ItemTypes.DIAMOND).build()
            val updated = f.filter(WrapperPlayServerSetPlayerInventory(2, replacement)) as WrapperPlayServerSetPlayerInventory
            updated.stack shouldBe replacement
            val otherSlot = f.filter(WrapperPlayServerSetPlayerInventory(3, f.real)) as WrapperPlayServerSetPlayerInventory
            otherSlot.stack.getComponent(ComponentTypes.ITEM_MODEL).get().modelLocation.toString() shouldBe "arc:event_sights/g36_raw"
        }
    }

    "tracked and late observers receive reload mechanisms in a native two-handed pose then restore" {
        ScopeFixture().use { f ->
            f.real = gunPacketItem()
            f.baseline()
            f.tracked = true
            val original = f.real.copy()
            f.scopes.animateReload(f.player, f.target, 42) { true }
            fun shown() = (f.observerPackets.last() as WrapperPlayServerEntityEquipment).equipment.single().item
            shown().type shouldBe ItemTypes.CROSSBOW
            shown().getComponent(ComponentTypes.ITEM_MODEL).get().modelLocation.toString() shouldBe "arc:event_sights/vss_vintorez_reload_00"
            f.tick(20)
            shown().getComponent(ComponentTypes.ITEM_MODEL).get().modelLocation.toString() shouldBe f.models().last()
            val replay = f.filter(WrapperPlayServerEntityEquipment(f.entityId,
                listOf(Equipment(EquipmentSlot.MAIN_HAND, f.real), Equipment(EquipmentSlot.OFF_HAND, f.real))),
                99, f.observerId) as WrapperPlayServerEntityEquipment
            replay.equipment[0].item shouldBe shown()
            replay.equipment[1].item shouldBe original
            val replacement = PacketItemStack.builder().type(ItemTypes.DIAMOND).build()
            val changed = f.filter(WrapperPlayServerEntityEquipment(f.entityId,
                listOf(Equipment(EquipmentSlot.MAIN_HAND, replacement))), 99, f.observerId) as WrapperPlayServerEntityEquipment
            changed.equipment.single().item shouldBe replacement
            f.tick(22)
            shown() shouldBe original
            f.real shouldBe original
            f.packets.filterIsInstance<WrapperPlayServerSetPlayerInventory>().forEach { it.stack.type shouldBe original.type }
            verify(exactly = 0) { f.observer.inventory }
        }
    }

    "cancelled queued observer reload frames cannot overwrite a newer aim pose" {
        ScopeFixture(queued = true).use { f ->
            f.real = gunPacketItem()
            f.baseline()
            f.tracked = true
            val old = f.scopes.animateReload(f.player, f.target, 42) { true }
            f.tick(8)
            f.scopes.cancelReload(f.playerId)
            f.scopes.toggle(f.player, f.target) shouldBe true
            old.close()
            f.tick(6)
            f.runCancelled()
            f.runQueued()
            val shown = f.observerPackets.filterIsInstance<WrapperPlayServerEntityEquipment>().map { it.equipment.single().item }
            shown.size shouldBe 1
            shown.single().getComponent(ComponentTypes.ITEM_MODEL).get().modelLocation.toString() shouldBe "arc:event_sights/vss_vintorez_aim"
            f.scopes.clear(f.playerId)
            f.runQueued()
            (f.observerPackets.last() as WrapperPlayServerEntityEquipment).equipment.single().item shouldBe f.real
        }
    }

    "antique hold corrections reach only observers through aim reload and restoration" {
        for (id in listOf(FirearmId.DOUBLE_BARREL, FirearmId.HAND_CANNON)) ScopeFixture().use { f ->
            f.real = gunPacketItem(crossbow = true)
            f.baseline()
            f.tracked = true
            val original = f.real.copy()
            val target = f.target.copy(firearm = id)
            val prefix = "arc:event_sights/${id.name.lowercase()}_"
            fun shown() = (f.observerPackets.last() as WrapperPlayServerEntityEquipment).equipment.single().item
            fun observerModel() = shown().getComponent(ComponentTypes.ITEM_MODEL).get().modelLocation.toString()
            f.scopes.toggle(f.player, target) shouldBe true
            observerModel() shouldBe "${prefix}observer_hold"
            f.models().last() shouldBe "${prefix}equip_aim"
            f.tick(6)
            observerModel() shouldBe "${prefix}observer_hold"
            f.models().last() shouldBe "${prefix}aim"

            f.scopes.animateReload(f.player, target, 42) { true }
            observerModel() shouldBe "${prefix}reload_00_observer"
            f.models().last() shouldBe "${prefix}reload_00"
            f.tick(20)
            observerModel() shouldBe "${f.models().last()}_observer"
            val replay = f.filter(WrapperPlayServerEntityEquipment(f.entityId,
                listOf(Equipment(EquipmentSlot.MAIN_HAND, f.real))), 99, f.observerId) as WrapperPlayServerEntityEquipment
            replay.equipment.single().item shouldBe shown()
            f.tick(21)
            observerModel() shouldBe "${prefix}reload_39_observer"
            f.models().last() shouldBe "${prefix}reload_39"
            f.tick(1)
            shown() shouldBe original
            f.scopes.toggle(f.player, target) shouldBe true
            f.scopes.clear(f.playerId)
            shown() shouldBe original
            f.real shouldBe original
            f.packets.filterIsInstance<WrapperPlayServerSetPlayerInventory>().forEach {
                it.stack.type shouldBe original.type
                (it.stack.getComponent(ComponentTypes.ITEM_MODEL).orElse(null)?.modelLocation?.toString()?.contains("observer") == true) shouldBe false
            }
        }
    }

    "cancelled queued reload frames and old handles cannot overwrite a newer scope" {
        ScopeFixture(queued = true).use { f ->
            f.real = gunPacketItem()
            f.baseline()
            val old = f.scopes.animateReload(f.player, f.target, 42) { true }
            f.tick(5)
            f.scopes.cancelReload(f.playerId)
            f.scopes.toggle(f.player, f.target.copy(firearm = FirearmId.G36)) shouldBe true
            old.close()
            old.close()
            f.runCancelled()
            f.runQueued()
            f.models() shouldBe listOf("arc:event_sights/g36_equip_aim")
            f.scopes.cancelReload(f.playerId)
            f.tick(6)
            f.runQueued()
            f.models().last() shouldBe "arc:event_sights/g36_aim"
        }
    }

    "Creative manual reload remains cosmetic and a slot or death invalidation cleans up" {
        ScopeFixture().use { f ->
            f.real = gunPacketItem()
            f.gameMode = GameMode.CREATIVE
            f.scopes.animateReload(f.player, f.target.copy(matchId = null), 28) { true }
            f.tick(5)
            f.models().last().contains("_reload_") shouldBe true
            f.scopes.cancelReload(f.playerId)
            f.pendingTimers() shouldBe 0
            f.scopes.hasViewmodel(f.playerId) shouldBe false
            f.scopes.animateReload(f.player, f.target.copy(matchId = null), 28) { true }
            f.heldSlot = 3
            f.tick(1)
            f.pendingTimers() shouldBe 0
            f.heldSlot = 2
            f.scopes.animateReload(f.player, f.target.copy(matchId = null), 28) { true }
            f.dead = true
            f.tick(1)
            f.pendingTimers() shouldBe 0
            f.scopes.hasViewmodel(f.playerId) shouldBe false
        }
    }

    "native swing suppression preserves item identity and metadata and leaves crossbows and other items alone" {
        ScopeFixture().use {
            val native = gunPacketItem()
            val model = ItemModel(ResourceLocation("arc:gold_guns/gold_g36"))
            native.setComponent(ComponentTypes.ITEM_MODEL, model)
            val data = native.getComponent(ComponentTypes.CUSTOM_DATA).get()
            val originalAnimation = native.getComponent(ComponentTypes.SWING_ANIMATION)
            val quiet = firearmWithoutSwing(native, ClientVersion.V_1_21_11.protocolVersion)
            quiet.type shouldBe native.type
            quiet.amount shouldBe native.amount
            quiet.getComponent(ComponentTypes.ITEM_MODEL).get() shouldBe model
            quiet.getComponent(ComponentTypes.CUSTOM_DATA).get() shouldBe data
            quiet.getComponent(ComponentTypes.SWING_ANIMATION).get().type shouldBe ItemSwingAnimation.Type.NONE
            quiet.getComponent(ComponentTypes.SWING_ANIMATION).get().duration shouldBe 6
            native.getComponent(ComponentTypes.SWING_ANIMATION) shouldBe originalAnimation
            (firearmWithoutSwing(quiet, ClientVersion.V_1_21_11.protocolVersion) === quiet) shouldBe true
            (firearmWithoutSwing(native, ClientVersion.V_1_21_4.protocolVersion) === native) shouldBe true
            val crossbow = gunPacketItem(crossbow = true)
            (firearmWithoutSwing(crossbow, ClientVersion.V_1_21_11.protocolVersion) === crossbow) shouldBe true
            val vanilla = PacketItemStack.builder().type(ItemTypes.NETHERITE_SHOVEL).build()
            (firearmWithoutSwing(vanilla, ClientVersion.V_1_21_11.protocolVersion) === vanilla) shouldBe true
        }
    }

    "unscoped raw and event guns receive the component in inventory and cursor packets before first use" {
        ScopeFixture().use { f ->
            for (native in listOf(gunPacketItem(), gunPacketItem(event = true))) {
                val inventory = f.filter(WrapperPlayServerSetPlayerInventory(2, native)) as WrapperPlayServerSetPlayerInventory
                val slot = f.filter(WrapperPlayServerSetSlot(0, 11, 38, native)) as WrapperPlayServerSetSlot
                val window = f.filter(WrapperPlayServerWindowItems(2, 12, listOf(native), native)) as WrapperPlayServerWindowItems
                val cursor = f.filter(WrapperPlayServerSetCursorItem(native)) as WrapperPlayServerSetCursorItem
                listOf(inventory.stack, slot.item, window.items.single(), window.carriedItem.get(), cursor.stack).forEach { shown ->
                    shown.getComponent(ComponentTypes.SWING_ANIMATION).get().type shouldBe ItemSwingAnimation.Type.NONE
                    shown.getComponent(ComponentTypes.ITEM_MODEL).get().modelLocation.toString() shouldBe "arc:event_sights/g36_raw"
                    shown.getComponent(ComponentTypes.CUSTOM_DATA) shouldBe native.getComponent(ComponentTypes.CUSTOM_DATA)
                    shown.type shouldBe native.type
                }
                slot.stateId shouldBe 11
                window.stateId shouldBe 12
            }
            f.packets.isEmpty() shouldBe true
            f.pendingTimers() shouldBe 0
        }
    }

    "raw aliases preserve native state and custom models through repeated client projection" {
        ScopeFixture().use {
            val protocol = ClientVersion.V_1_21_11.protocolVersion
            for (id in FirearmId.entries) {
                val native = gunPacketItem(event = true)
                native.getComponent(ComponentTypes.CUSTOM_DATA).get().getCompoundTagOrNull("PublicBukkitValues")!!
                    .setTag("arcevents:firearm", NBTString(id.name))
                val displayed = firearmClientItem(native, protocol)
                displayed.getComponent(ComponentTypes.ITEM_MODEL).get().modelLocation.toString() shouldBe
                    "arc:event_sights/${id.name.lowercase()}_raw"
                displayed.getComponent(ComponentTypes.CUSTOM_DATA) shouldBe native.getComponent(ComponentTypes.CUSTOM_DATA)
                displayed.type shouldBe native.type
                displayed.amount shouldBe native.amount
                (firearmClientItem(displayed, protocol) === displayed) shouldBe true
                (firearmClientItem(native, ClientVersion.V_1_21_4.protocolVersion) === native) shouldBe true
            }
            val crossbow = gunPacketItem(crossbow = true)
            crossbow.setComponent(ComponentTypes.ITEM_MODEL, ItemModel(crossbow.type.name))
            val displayed = firearmClientItem(crossbow, protocol)
            displayed.type shouldBe ItemTypes.CROSSBOW
            displayed.getComponent(ComponentTypes.ITEM_MODEL).get().modelLocation.toString() shouldBe "arc:event_sights/flintlock_raw"
            displayed.getComponent(ComponentTypes.SWING_ANIMATION) shouldBe crossbow.getComponent(ComponentTypes.SWING_ANIMATION)
            displayed.getComponent(ComponentTypes.CUSTOM_DATA) shouldBe crossbow.getComponent(ComponentTypes.CUSTOM_DATA)
            (firearmClientItem(displayed, protocol) === displayed) shouldBe true
            val custom = gunPacketItem()
            val model = ItemModel(ResourceLocation("operator:custom_gun"))
            custom.setComponent(ComponentTypes.ITEM_MODEL, model)
            firearmClientItem(custom, protocol).getComponent(ComponentTypes.ITEM_MODEL).get() shouldBe model
            for (modelData in listOf(null, 0)) {
                val fallback = gunPacketItem(event = true)
                if (modelData == null) fallback.unsetComponent(ComponentTypes.CUSTOM_MODEL_DATA_LISTS)
                else fallback.setComponent(ComponentTypes.CUSTOM_MODEL_DATA_LISTS, ItemCustomModelData(modelData))
                val shown = firearmClientItem(fallback, protocol)
                shown.getComponent(ComponentTypes.ITEM_MODEL) shouldBe fallback.getComponent(ComponentTypes.ITEM_MODEL)
                shown.getComponent(ComponentTypes.CUSTOM_DATA) shouldBe fallback.getComponent(ComponentTypes.CUSTOM_DATA)
                shown.getComponent(ComponentTypes.SWING_ANIMATION).get().type shouldBe ItemSwingAnimation.Type.NONE
            }
            val unrelated = PacketItemStack.builder().type(ItemTypes.PAPER).build()
            (firearmClientItem(unrelated, protocol) === unrelated) shouldBe true
        }
    }

    "aimed silent sends and ordinary clear retain suppression while close restores authoritative inventory" {
        ScopeFixture().use { f ->
            f.real = gunPacketItem()
            f.baseline()
            f.scopes.toggle(f.player, f.target)
            f.tick(6)
            f.scopes.clear(f.playerId)
            f.packets.filterIsInstance<WrapperPlayServerSetPlayerInventory>().forEach {
                it.stack.getComponent(ComponentTypes.SWING_ANIMATION).get().type shouldBe ItemSwingAnimation.Type.NONE
            }
            f.scopes.toggle(f.player, f.target)
            f.scopes.closeAsync().join()
            f.inventoryRefreshes shouldBe 1
            (f.packets.last() as WrapperPlayServerSetPlayerInventory).stack shouldBe f.real
            val afterClose = f.filter(WrapperPlayServerSetPlayerInventory(2, f.real)) as WrapperPlayServerSetPlayerInventory
            afterClose.stack shouldBe f.real
        }
    }

    "aiming restores the latest native abilities and current held item without ever replacing a helmet" {
        ScopeFixture().use { f ->
            val original = ScopeAbilities(false, false, false, false, .05f, .1f)
            f.baseline(original)
            f.scopes.toggle(f.player, f.target) shouldBe true
            f.tick(6)
            f.scopes.zoom(f.playerId) shouldBe 3f
            ScopeAbilities.read(f.packets.first() as WrapperPlayServerPlayerAbilities) shouldBe original.copy(walkingSpeed = -.3f)
            val latest = ScopeAbilities(true, true, true, false, .08f, .2f)
            f.native = latest
            val update = latest.packet()
            f.scopes.rewriteAbilities(f.playerId, f.entityId, update)
            ScopeAbilities.read(update) shouldBe latest.copy(walkingSpeed = -.6f)
            f.real = PacketItemStack.builder().type(ItemTypes.GOLDEN_HOE).build()
            f.scopes.clear(f.playerId)
            ScopeAbilities.read(f.packets[f.packets.lastIndex - 1] as WrapperPlayServerPlayerAbilities) shouldBe latest
            (f.packets.last() as WrapperPlayServerSetPlayerInventory).stack shouldBe f.real
            f.scopes.zoom(f.playerId) shouldBe null
            f.packets.filterIsInstance<WrapperPlayServerSetPlayerInventory>().any { it.slot == 39 } shouldBe false
            verify(exactly = 0) { f.player.walkSpeed = any() }
            verify(exactly = 0) { f.inventory.helmet }
            verify(exactly = 0) { f.inventory.helmet = any() }
        }
    }

    "match aiming still refuses missing baseline creative mode and old clients" {
        ScopeFixture().use { f ->
            f.scopes.toggle(f.player, f.target) shouldBe false
            f.baseline()
            f.gameMode = GameMode.CREATIVE
            f.scopes.toggle(f.player, f.target) shouldBe false
            f.packets.isEmpty() shouldBe true
        }
        ScopeFixture(protocol = ClientVersion.V_1_21_2.protocolVersion).use { f ->
            f.baseline()
            f.scopes.toggle(f.player, f.target.copy(matchId = null)) shouldBe false
        }
    }

    "creative preview works without a baseline and preserves native creative flags when abilities arrive" {
        ScopeFixture().use { f ->
            f.gameMode = GameMode.CREATIVE
            val preview = f.target.copy(matchId = null)
            f.scopes.toggle(f.player, preview) shouldBe true
            f.scopes.isPreview(f.playerId) shouldBe true
            f.tick(6)
            f.packets.any { it is WrapperPlayServerPlayerAbilities } shouldBe false
            val native = ScopeAbilities(true, true, true, true, .09f, .1f)
            f.native = native
            val packet = native.packet()
            f.scopes.rewriteAbilities(f.playerId, f.entityId, packet)
            ScopeAbilities.read(packet) shouldBe native.copy(walkingSpeed = -.3f)
            f.scopes.clear(f.playerId)
            ScopeAbilities.read(f.packets[f.packets.lastIndex - 1] as WrapperPlayServerPlayerAbilities) shouldBe native
            verify(exactly = 0) { f.player.gameMode = any() }
            verify(exactly = 0) { f.player.walkSpeed = any() }
        }
    }

    "all twelve weapons send native transition and stable endpoints without intermediate pose packets" {
        ScopeFixture().use { f ->
            f.baseline()
            for (id in FirearmId.entries) {
                f.packets.clear()
                f.scopes.toggle(f.player, f.target.copy(firearm = id)) shouldBe true
                f.models() shouldBe listOf("arc:event_sights/${id.name.lowercase()}_equip_aim")
                f.tick(6)
                f.models() shouldBe listOf("equip_aim", "aim").map { "arc:event_sights/${id.name.lowercase()}_$it" }
                firearmScopeZoom(id) shouldBe when (id) {
                    FirearmId.VSS_VINTOREZ -> 3f
                    FirearmId.MCMILLAN -> 5f
                    FirearmId.AEK_971, FirearmId.RPL_20, FirearmId.VEPR_12 -> 1.35f
                    else -> 1.15f
                }
                f.scopes.clear(f.playerId)
            }
        }
    }

    "expanded vendor guns retain their own model through raw projection aim and reload" {
        ScopeFixture().use { f ->
            f.baseline()
            val identity = "gold_guns:gold_beretta_arx200"
            val native = f.real.copy().also { item ->
                val data = NBTCompound()
                data.setTag("itemsadder", NBTCompound().also {
                    it.setTag("namespace", NBTString("gold_guns"))
                    it.setTag("id", NBTString("gold_beretta_arx200"))
                })
                item.setComponent(ComponentTypes.CUSTOM_DATA, data)
            }
            f.real = native
            val model = native.getComponent(ComponentTypes.ITEM_MODEL)
            firearmClientItem(native, ClientVersion.V_1_21_11.protocolVersion)
                .getComponent(ComponentTypes.ITEM_MODEL) shouldBe model
            val target = requireNotNull(packedScopeTarget(identity, f.target.heldSlot))
            f.scopes.toggle(f.player, target) shouldBe true
            f.tick(6)
            val aimed = f.filter(WrapperPlayServerSetPlayerInventory(target.heldSlot, native)) as WrapperPlayServerSetPlayerInventory
            aimed.stack.getComponent(ComponentTypes.ITEM_MODEL) shouldBe model
            f.scopes.hasViewmodel(f.playerId) shouldBe false
            f.scopes.animateReload(f.player, target, 10, false) { true }.use {
                f.tick(2)
                val reloading = f.filter(WrapperPlayServerSetPlayerInventory(target.heldSlot, native)) as WrapperPlayServerSetPlayerInventory
                reloading.stack.getComponent(ComponentTypes.ITEM_MODEL) shouldBe model
            }
        }
    }

    "rapid lower then raise cancels old settling and retains the newest native transition" {
        ScopeFixture().use { f ->
            f.baseline()
            f.scopes.toggle(f.player, f.target)
            f.tick(2)
            f.scopes.toggle(f.player, f.target) shouldBe true
            f.models().last().endsWith("equip_hip") shouldBe true
            f.tick(1)
            f.scopes.toggle(f.player, f.target) shouldBe true
            f.runCancelled()
            f.tick(5)
            f.models().map { it.substringAfter("vss_vintorez_") } shouldBe listOf("equip_aim", "equip_hip", "equip_aim")
            f.tick(1)
            f.models().last().endsWith("vss_vintorez_aim") shouldBe true
            f.scopes.zoom(f.playerId) shouldBe 3f
            f.scopes.toggle(f.player, f.target)
            f.tick(6)
            f.models().takeLast(2).map { it.substringAfter("vss_vintorez_") } shouldBe listOf("equip_hip", "hip")
            f.scopes.zoom(f.playerId) shouldBe null
            f.scopes.isAiming(f.playerId) shouldBe false
            val native = ScopeAbilities(false, false, false, false, .05f, .1f).packet()
            f.scopes.rewriteAbilities(f.playerId, f.entityId, native)
            native.fovModifier shouldBe .1f
            val hip = f.filter(WrapperPlayServerSetPlayerInventory(2, f.real)) as WrapperPlayServerSetPlayerInventory
            hip.stack.getComponent(ComponentTypes.ITEM_MODEL).get().modelLocation.toString() shouldBe "arc:event_sights/vss_vintorez_hip"
        }
    }

    "two immediate RMB presses return to hip and a third press cannot be cleared by the cancelled raise" {
        ScopeFixture().use { f ->
            f.baseline()
            f.scopes.toggle(f.player, f.target)
            f.scopes.toggle(f.player, f.target)
            f.scopes.zoom(f.playerId) shouldBe null
            f.models().last() shouldBe "arc:event_sights/vss_vintorez_equip_hip"
            f.scopes.toggle(f.player, f.target)
            f.runCancelled()
            f.tick(6)
            f.scopes.zoom(f.playerId) shouldBe 3f
            f.models().last() shouldBe "arc:event_sights/vss_vintorez_aim"
        }
    }

    "settled ammo and custom data updates retain the stable model without reentering equip animation" {
        ScopeFixture().use { f ->
            f.baseline()
            f.scopes.toggle(f.player, f.target)
            f.tick(6)
            val data = NBTCompound().also { it.setTag("loaded_rounds", NBTInt(7)) }
            val native = f.real.copy().also { it.amount = 2; it.setComponent(ComponentTypes.CUSTOM_DATA, data) }
            val originalModel = native.getComponent(ComponentTypes.ITEM_MODEL)
            val update = f.filter(WrapperPlayServerSetPlayerInventory(2, native)) as WrapperPlayServerSetPlayerInventory
            update.stack.amount shouldBe 2
            update.stack.getComponent(ComponentTypes.CUSTOM_DATA).get() shouldBe data
            update.stack.getComponent(ComponentTypes.ITEM_MODEL).get().modelLocation.toString() shouldBe "arc:event_sights/vss_vintorez_aim"
            native.getComponent(ComponentTypes.ITEM_MODEL) shouldBe originalModel
            val slot = f.filter(WrapperPlayServerSetSlot(0, 11, 38, native)) as WrapperPlayServerSetSlot
            slot.item.getComponent(ComponentTypes.CUSTOM_DATA).get() shouldBe data
            slot.stateId shouldBe 11
            val items = List(46) { native }
            val full = f.filter(WrapperPlayServerWindowItems(0, 12, items, native)) as WrapperPlayServerWindowItems
            full.items[38].getComponent(ComponentTypes.ITEM_MODEL).get().modelLocation.toString() shouldBe "arc:event_sights/vss_vintorez_aim"
            full.items[5] shouldBe native
            val other = f.filter(WrapperPlayServerSetSlot(2, 13, 38, native)) as WrapperPlayServerSetSlot
            other.item shouldBe native
        }
    }

    "invalidation restores immediately and no delayed frame reaches an old slot or new scope" {
        ScopeFixture(queued = true).use { f ->
            f.baseline()
            f.scopes.toggle(f.player, f.target)
            f.runQueued()
            f.packets.clear()
            f.scopes.clear(f.playerId)
            f.scopes.toggle(f.player, f.target.copy(firearm = FirearmId.G36, heldSlot = 4))
            f.runCancelled()
            f.tick(6)
            f.runQueued()
            val slots = f.packets.filterIsInstance<WrapperPlayServerSetPlayerInventory>()
            slots.first().slot shouldBe 2
            slots.first().stack shouldBe f.real
            slots.drop(1).all { it.slot == 4 } shouldBe true
            f.models().last() shouldBe "arc:event_sights/g36_aim"
            f.scopes.zoom(f.playerId) shouldBe 1.15f
        }
    }

    "old connection packets and disconnect never replace the new baseline or clear its sight" {
        ScopeFixture().use { f ->
            f.baseline()
            f.scopes.toggle(f.player, f.target)
            f.scopes.clear(f.playerId)
            val oldId = f.entityId
            f.entityId = 8
            val current = ScopeAbilities(true, false, true, false, .08f, .2f)
            f.baseline(current)
            f.scopes.toggle(f.player, f.target.copy(firearm = FirearmId.AEK_971)) shouldBe true
            val oldPacket = ScopeAbilities(false, false, false, false, .03f, .4f).packet()
            f.scopes.rewriteAbilities(f.playerId, oldId, oldPacket)
            oldPacket.fovModifier shouldBe .4f
            val oldItem = f.filter(WrapperPlayServerSetPlayerInventory(2, f.real), oldId) as WrapperPlayServerSetPlayerInventory
            oldItem.stack.getComponent(ComponentTypes.ITEM_MODEL) shouldBe f.real.getComponent(ComponentTypes.ITEM_MODEL)
            f.scopes.onUserDisconnect(UserDisconnectEvent(mockk<User> {
                every { uuid } returns f.playerId
                every { entityId } returns oldId
            }))
            f.tick(6)
            f.scopes.zoom(f.playerId) shouldBe 1.35f
            f.packets.clear()
            f.scopes.clear(f.playerId)
            ScopeAbilities.read(f.packets.first() as WrapperPlayServerPlayerAbilities) shouldBe current
        }
    }

    "login packets and disconnects with a null UUID do not touch scope state" {
        ScopeFixture().use { f ->
            f.baseline()
            f.scopes.toggle(f.player, f.target)
            val user = mockk<User> { every { uuid } returns null }
            val event = mockk<PacketSendEvent>(relaxed = true) {
                every { this@mockk.user } returns user
                every { packetType } returns PacketType.Play.Server.PLAYER_ABILITIES
            }
            f.scopes.onPacketSend(event)
            f.scopes.onUserDisconnect(UserDisconnectEvent(user))
            f.scopes.zoom(f.playerId) shouldBe 3f
            verify(exactly = 0) { event.markForReEncode(any()) }
        }
    }

    "shutdown cancels settling and waits for accepted restore packets before completing" {
        ScopeFixture(queued = true).use { f ->
            f.baseline()
            f.scopes.toggle(f.player, f.target)
            val close = f.scopes.closeAsync()
            close.isDone shouldBe false
            f.tick(6)
            f.runCancelled()
            f.runQueued()
            close.join()
            f.packets.size shouldBe 2
            ScopeAbilities.read(f.packets.first() as WrapperPlayServerPlayerAbilities).walkingSpeed shouldBe .1f
            (f.packets.last() as WrapperPlayServerSetPlayerInventory).stack shouldBe f.real
            f.scopes.toggle(f.player, f.target) shouldBe false
        }
    }

    "shutdown owns the scheduled handle even after a network disconnect removed its active scope" {
        ScopeFixture().use { f ->
            f.baseline()
            f.scopes.toggle(f.player, f.target)
            f.pendingTimers() shouldBe 1
            f.scopes.onUserDisconnect(UserDisconnectEvent(mockk<User> {
                every { uuid } returns f.playerId
                every { entityId } returns f.entityId
            }))
            val count = f.packets.size
            f.scopes.closeAsync().join()
            f.pendingTimers() shouldBe 0
            f.runCancelled()
            f.tick(6)
            f.packets.size shouldBe count
        }
    }
    "accepted flight toggles refresh stale flags at zoom entry lower and invalidation" {
        ScopeFixture().use { f ->
            f.baseline(ScopeAbilities(false, false, true, false, .05f, .1f))
            // A double-space flight toggle is accepted without an outbound abilities packet.
            f.native = f.native.copy(flying = true, flySpeed = .08f)
            f.scopes.toggle(f.player, f.target) shouldBe true
            ScopeAbilities.read(f.packets.first() as WrapperPlayServerPlayerAbilities) shouldBe f.native.copy(walkingSpeed = -.3f)
            f.scopes.toggle(f.player, f.target)
            ScopeAbilities.read(f.packets[f.packets.lastIndex - 1] as WrapperPlayServerPlayerAbilities) shouldBe f.native
            f.scopes.toggle(f.player, f.target)
            f.native = f.native.copy(flying = false, flightAllowed = false)
            f.scopes.clear(f.playerId)
            ScopeAbilities.read(f.packets[f.packets.lastIndex - 1] as WrapperPlayServerPlayerAbilities) shouldBe f.native
            verify(exactly = 0) { f.player.isFlying = any() }
            verify(exactly = 0) { f.player.allowFlight = any() }
            verify(exactly = 0) { f.player.flySpeed = any() }
            verify(exactly = 0) { f.player.walkSpeed = any() }
        }
    }

    "observers see a charged gun pose without inventory writes and late equipment updates keep the pose" {
        ScopeFixture().use { f ->
            f.baseline()
            f.tracked = true
            val original = f.real.copy()
            f.scopes.toggle(f.player, f.target)
            val pose = f.observerPackets.single() as WrapperPlayServerEntityEquipment
            pose.entityId shouldBe f.entityId
            pose.equipment.single().item.type shouldBe ItemTypes.CROSSBOW
            pose.equipment.single().item.getComponent(ComponentTypes.CHARGED_PROJECTILES).get().items.size shouldBe 1
            pose.equipment.single().item.getComponent(ComponentTypes.ITEM_MODEL).get().modelLocation.toString() shouldBe "arc:event_sights/vss_vintorez_equip_aim"
            f.real shouldBe original
            (f.packets.last() as WrapperPlayServerSetPlayerInventory).stack.type shouldBe original.type
            val native = WrapperPlayServerEntityEquipment(f.entityId, listOf(Equipment(EquipmentSlot.MAIN_HAND, f.real), Equipment(EquipmentSlot.OFF_HAND, f.real)))
            val replay = f.filter(native, 99, f.observerId) as WrapperPlayServerEntityEquipment
            replay.equipment[0].item.type shouldBe ItemTypes.CROSSBOW
            replay.equipment[1].item shouldBe original
            // An observer's own slots and other entities are independent of the shooter's aim.
            val inventory = f.filter(WrapperPlayServerSetPlayerInventory(2, f.real), 99, f.observerId) as WrapperPlayServerSetPlayerInventory
            inventory.stack shouldBe original
            f.scopes.toggle(f.player, f.target)
            (f.observerPackets.last() as WrapperPlayServerEntityEquipment).equipment.single().item shouldBe original
            f.scopes.toggle(f.player, f.target)
            f.scopes.clear(f.playerId)
            (f.observerPackets.last() as WrapperPlayServerEntityEquipment).equipment.single().item shouldBe original
            verify(exactly = 0) { f.observer.inventory }
        }
    }

    "native equip aliases remain presentation items rejected by the creative inventory boundary" {
        for (suffix in listOf("equip_aim", "equip_hip")) {
            val item = mockk<ItemStack>()
            every { item.isEmpty } returns false
            every { item.itemMeta.itemModel } returns org.bukkit.NamespacedKey("arc", "event_sights/vss_vintorez_$suffix")
            isScopeViewmodelItem(item) shouldBe true
        }
        for (weapon in listOf("double_barrel", "hand_cannon")) {
            for (suffix in listOf("observer_hold", "reload_00_observer", "reload_19_observer", "reload_39_observer")) {
                val item = mockk<ItemStack>()
                every { item.isEmpty } returns false
                every { item.itemMeta.itemModel } returns org.bukkit.NamespacedKey("arc", "event_sights/${weapon}_$suffix")
                isScopeViewmodelItem(item) shouldBe true
            }
        }
    }

    "stable raw vendor aliases remain movable through the creative inventory boundary" {
        for (id in FirearmId.entries) {
            val item = mockk<ItemStack>()
            every { item.isEmpty } returns false
            every { item.itemMeta.itemModel } returns org.bukkit.NamespacedKey("arc", "event_sights/${id.name.lowercase()}_raw")
            isScopeViewmodelItem(item) shouldBe false
        }
    }

    "VSS keeps its zoom and model with no laser work while the setting is disabled" {
        ScopeFixture().use { f ->
            f.baseline()
            f.scopes.toggle(f.player, f.target) shouldBe true
            f.pendingTimers() shouldBe 1
            f.tick(6)
            f.pendingTimers() shouldBe 0
            f.scopes.zoom(f.playerId) shouldBe 3f
            f.models().last() shouldBe "arc:event_sights/vss_vintorez_aim"
            verify(exactly = 0) { f.laser.update() }
        }
    }

    "laser reload reconciles an already aimed VSS each tick without rebuilding its scope" {
        ScopeFixture().use { f ->
            f.baseline()
            f.scopes.toggle(f.player, f.target.copy(firearm = FirearmId.G36))
            f.laserEnabled = true
            f.scopes.validate { _, _ -> true }
            f.tick(6)
            f.pendingTimers() shouldBe 0
            verify(exactly = 0) { f.laser.update() }
            f.scopes.toggle(f.player, f.target)
            f.pendingTimers() shouldBe 2
            f.tick(1)
            verify(exactly = 2) { f.laser.update() }
            f.laserEnabled = false
            f.scopes.validate { _, _ -> true }
            verify(exactly = 1) { f.laser.close() }
            f.tick(5)
            f.pendingTimers() shouldBe 0
            f.scopes.isAiming(f.playerId) shouldBe true
            f.laserEnabled = true
            f.scopes.validate { _, _ -> true }
            f.pendingTimers() shouldBe 1
            f.tick(1)
            verify(exactly = 4) { f.laser.update() }
            f.worldId = UUID.randomUUID()
            f.tick(1)
            f.pendingTimers() shouldBe 0
            f.scopes.isAiming(f.playerId) shouldBe false
            verify(exactly = 2) { f.laser.close() }
        }
    }

})

private fun gunPacketItem(event: Boolean = false, crossbow: Boolean = false): PacketItemStack {
    val data = NBTCompound()
    if (event) {
        data.setTag("PublicBukkitValues", NBTCompound().also {
            it.setTag("arcevents:event_item", NBTString("FIREARM"))
            it.setTag("arcevents:firearm", NBTString("G36"))
            it.setTag("arcevents:loaded_rounds", NBTInt(7))
        })
    } else {
        data.setTag("itemsadder", NBTCompound().also {
            it.setTag("namespace", NBTString(if (crossbow) "voxelspawns_megaflintlocks" else "gold_guns"))
            it.setTag("id", NBTString(if (crossbow) "vs_pistol" else "gold_g36"))
        })
        data.setTag("PublicBukkitValues", NBTCompound().also { it.setTag("arcevents:world_loaded_rounds", NBTInt(7)) })
    }
    return PacketItemStack.builder().type(if (crossbow) ItemTypes.CROSSBOW else ItemTypes.PAPER).amount(1).build().also {
        it.setComponent(ComponentTypes.CUSTOM_DATA, data)
        it.setComponent(ComponentTypes.CUSTOM_MODEL_DATA_LISTS, ItemCustomModelData(if (crossbow) 2100101 else 2100003))
    }
}

private class ScopeFixture(private val queued: Boolean = false, protocol: Int = ClientVersion.V_1_21_11.protocolVersion) : AutoCloseable {
    private val previousApi = PacketEvents.getAPI()
    init {
        PacketEvents.setAPI(object : PacketEventsAPI<Any>() {
            private val netty = NettyManagerImpl()
            override fun getServerManager() = ServerManager { ServerVersion.V_1_21_11 }
            override fun getNettyManager() = netty
            override fun getPlugin(): Any = this
            override fun getProtocolManager(): com.github.retrooper.packetevents.manager.protocol.ProtocolManager = error("No live protocol in codec test")
            override fun getPlayerManager(): com.github.retrooper.packetevents.manager.player.PlayerManager = error("No player manager in codec test")
            override fun getInjector(): com.github.retrooper.packetevents.injector.ChannelInjector = error("No channel injection in codec test")
            override fun load() = Unit
            override fun init() = Unit
            override fun terminate() = Unit
            override fun isLoaded() = true
            override fun isInitialized() = true
            override fun isTerminated() = false
        })
    }
    val playerId = UUID.randomUUID()
    var entityId = 7
    val target = FirearmScopeTarget("match", FirearmId.VSS_VINTOREZ, 2)
    val player = mockk<Player>()
    val inventory = mockk<PlayerInventory>(relaxed = true)
    var gameMode = GameMode.ADVENTURE
    var native = ScopeAbilities(false, false, false, false, .05f, .1f)
    var worldId = UUID.randomUUID()
    val observerId = UUID.randomUUID()
    val observer = mockk<Player>()
    var tracked = false
    val observerPackets = mutableListOf<PacketWrapper<*>>()
    val laser = mockk<TttScopeLaser>(relaxed = true)
    var laserEnabled = false
    var real = PacketItemStack.builder().type(ItemTypes.NETHERITE_SHOVEL).build()
    var inventoryRefreshes = 0
    var scopeAuthorized = true
    var heldSlot = 2
    var dead = false
    private val heldNative = mockk<ItemStack>()
    val packets = mutableListOf<PacketWrapper<*>>()
    private val queue = mutableListOf<() -> Unit>()
    private var now = 0L
    private val timers = mutableListOf<Timer>()
    val scopes: TttScopes

    private class Timer(override val id: Int, val due: Long, val action: () -> Unit) : ScheduledTask {
        override var isCancelled = false
        var ran = false
        override fun cancel() { isCancelled = true }
    }

    init {
        every { player.uniqueId } returns playerId
        every { player.isOnline } returns true
        every { player.isDead } answers { dead }
        every { player.gameMode } answers { gameMode }
        every { player.inventory } returns inventory
        every { player.world.uid } answers { worldId }
        every { player.isFlying } answers { native.flying }
        every { player.allowFlight } answers { native.flightAllowed }
        every { player.flySpeed } answers { native.flySpeed * 2 }
        every { player.walkSpeed } answers { native.walkingSpeed * 2 }
        every { observer.uniqueId } returns observerId
        every { observer.canSee(player) } returns true
        every { inventory.itemInMainHand } returns heldNative
        every { inventory.heldItemSlot } answers { heldSlot }
        every { inventory.getItem(any<Int>()) } returns heldNative
        val execute: (() -> Unit) -> CompletableFuture<Unit> = { action ->
            val completion = CompletableFuture<Unit>()
            val run = { action(); completion.complete(Unit); Unit }
            if (queued) queue += run else run()
            completion
        }
        scopes = TttScopes({ recipient ->
            if (recipient === observer) ScopeConnection(99, protocol, observerPackets::add, execute)
            else ScopeConnection(entityId, protocol, packets::add, execute)
        }, { real }, authorized = { _, _ -> scopeAuthorized }, schedule = { ticks, action ->
            (ticks in setOf(1L, 6L)) shouldBe true
            Timer(timers.size, now + ticks, action).also(timers::add)
        }, observers = { if (tracked) listOf(observer) else emptyList() }, createLaser = { laser }, laserEnabled = { laserEnabled },
            refreshInventories = { inventoryRefreshes++ })
    }

    fun baseline(value: ScopeAbilities = ScopeAbilities(false, false, false, false, .05f, .1f)) {
        native = value
        scopes.rewriteAbilities(playerId, entityId, value.packet())
    }

    fun models(): List<String> = packets.filterIsInstance<WrapperPlayServerSetPlayerInventory>().mapNotNull {
        it.stack.getComponent(ComponentTypes.ITEM_MODEL).orElse(null)?.modelLocation?.toString()?.takeIf { id -> id.startsWith("arc:event_sights/") }
    }

    fun filter(packet: PacketWrapper<*>, sourceEntityId: Int = entityId, recipientId: UUID = playerId): PacketWrapper<*> {
        var current = packet
        val event = mockk<PacketSendEvent>(relaxed = true)
        every { event.packetType } returns requireNotNull(packet.packetTypeData.packetType)
        every { event.user.uuid } returns recipientId
        every { event.user.entityId } returns sourceEntityId
        every { event.clientVersion } returns ClientVersion.V_1_21_11
        every { event.serverVersion } returns ServerVersion.V_1_21_11
        every { event.lastUsedWrapper } answers { current }
        every { event.lastUsedWrapper = any() } answers { current = firstArg() }
        scopes.onPacketSend(event)
        return current
    }

    fun tick(count: Int) = repeat(count) {
        now++
        timers.filter { !it.ran && !it.isCancelled && it.due <= now }.toList().forEach { timer -> timer.ran = true; timer.action() }
    }

    fun runCancelled() = timers.filter { it.isCancelled && !it.ran }.toList().forEach { it.ran = true; it.action() }
    fun pendingTimers(): Int = timers.count { !it.ran && !it.isCancelled }

    fun runQueued() {
        while (queue.isNotEmpty()) queue.removeAt(0).invoke()
    }

    override fun close() {
        val close = scopes.closeAsync()
        runQueued()
        close.join()
        PacketEvents.setAPI(previousApi)
    }
}
