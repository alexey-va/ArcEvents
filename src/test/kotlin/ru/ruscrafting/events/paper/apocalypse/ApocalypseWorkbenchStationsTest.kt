package ru.ruscrafting.events.paper.apocalypse

import io.kotest.core.spec.style.StringSpec
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import net.kyori.adventure.text.Component
import org.bukkit.ExplosionResult
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.World
import org.bukkit.block.Block
import org.bukkit.block.BlockFace
import org.bukkit.block.Container
import org.bukkit.block.Furnace
import org.bukkit.block.TileState
import org.bukkit.entity.Player
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.event.block.BlockDispenseEvent
import org.bukkit.event.block.BlockDropItemEvent
import org.bukkit.event.block.BlockExplodeEvent
import org.bukkit.event.block.BlockPistonExtendEvent
import org.bukkit.event.block.BlockPistonRetractEvent
import org.bukkit.event.block.BlockPlaceEvent
import org.bukkit.event.entity.EntityExplodeEvent
import org.bukkit.event.inventory.FurnaceBurnEvent
import org.bukkit.event.inventory.FurnaceSmeltEvent
import org.bukkit.event.inventory.InventoryMoveItemEvent
import org.bukkit.event.inventory.InventoryOpenEvent
import org.bukkit.inventory.BlastingRecipe
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.inventory.FurnaceRecipe
import org.bukkit.inventory.InventoryView
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.BlockStateMeta
import org.bukkit.persistence.PersistentDataType
import org.bukkit.plugin.Plugin
import org.bukkit.util.Vector
import org.mockbukkit.mockbukkit.exception.UnimplementedOperationException
import org.opentest4j.TestAbortedException
import ru.arc.paper.testing.MockBukkitTestRuntime
import ru.ruscrafting.events.domain.apocalypse.ApocalypsePoint
import ru.ruscrafting.events.domain.apocalypse.source.ApocalypseWorkbenchStation

class ApocalypseWorkbenchStationsTest : StringSpec({
    "native tile identity distinguishes every station and the final gate rechecks player, world, distance and block" {
        MockBukkitTestRuntime.open().use { paper ->
            val plugin = paper.createSimplePlugin("ApocalypseStationsTest")
            val world = paper.addSimpleWorld("expedition")
            val other = paper.addSimpleWorld("ordinary")
            world.getChunkAt(0, 0)
            val player = paper.addPlayer("Survivor")
            var active = true
            ApocalypseWorkbenchStations(plugin, { world }, { active }).use { stations ->
                stations.register()
                for (station in ApocalypseWorkbenchStation.entries.filter { it != ApocalypseWorkbenchStation.CRAFTING }) {
                    val item = ApocalypseWorkbenchStations.stationItem(plugin, station, station.sourceId)
                    item.type shouldBe Material.DROPPER
                    ApocalypseWorkbenchStations.stationOf(item, plugin) shouldBe station
                    val tile = (item.itemMeta as BlockStateMeta).blockState as TileState
                    tile.persistentDataContainer.get(NamespacedKey(plugin, "apocalypse_workbench_source"), PersistentDataType.STRING) shouldBe station.sourceId
                    val sourceItem = when (station) {
                        ApocalypseWorkbenchStation.T1, ApocalypseWorkbenchStation.AMMO -> "tacz:workbench_a"
                        ApocalypseWorkbenchStation.T3 -> "tacz:workbench_b"
                        else -> station.sourceId
                    }
                    item.itemMeta.persistentDataContainer.get(NamespacedKey(plugin, "apocalypse_source_item"), PersistentDataType.STRING) shouldBe sourceItem
                    item.itemMeta.persistentDataContainer.get(NamespacedKey(plugin, "apocalypse_source_block"), PersistentDataType.STRING) shouldBe
                        station.sourceId.takeIf { it != sourceItem }
                    val block = placeStation(stations, plugin, player, world, station)
                    val anchor = ApocalypsePoint(block.x, block.y, block.z)
                    player.teleport(block.location.clone().add(.5, .5, .5)) shouldBe true
                    stations.stationAt(block) shouldBe station
                    withClue("station=$station anchor=$anchor player=${player.location} dead=${player.isDead} loaded=${world.isChunkLoaded(anchor.x shr 4, anchor.z shr 4)} canonical=${stations.stationAt(world.getBlockAt(anchor.x, anchor.y, anchor.z))}") {
                        stations.isUsable(player, anchor, station) shouldBe true
                    }
                    stations.isUsable(player, anchor, ApocalypseWorkbenchStation.CRAFTING) shouldBe false
                    active = false
                    stations.isUsable(player, anchor, station) shouldBe false
                    active = true
                    player.teleport(block.location.clone().add(6.51, .5, .5))
                    stations.isUsable(player, anchor, station) shouldBe false
                    player.teleport(Location(other, .5, 64.5, .5))
                    stations.isUsable(player, anchor, station) shouldBe false
                    player.teleport(block.location.clone().add(.5, .5, .5))
                    block.type = Material.AIR
                    block.type = Material.DROPPER
                    stations.stationAt(block) shouldBe null
                    stations.isUsable(player, anchor, station) shouldBe false
                }
                ApocalypseWorkbenchStations.stationOf(ItemStack.of(Material.DROPPER), plugin) shouldBe null
                ApocalypseWorkbenchStations.stationOf(identifiedItem(plugin, Material.DROPPER, "tacz:gun_smith_table"), plugin) shouldBe null
                val table = world.getBlockAt(4, 64, 0).also { it.type = Material.CRAFTING_TABLE }
                player.teleport(table.location.clone().add(.5, .5, .5))
                stations.stationAt(table) shouldBe ApocalypseWorkbenchStation.CRAFTING
                stations.isUsable(player, ApocalypsePoint(4, 64, 0), ApocalypseWorkbenchStation.CRAFTING) shouldBe true
                stations.stationAt(other.getBlockAt(4, 64, 0).also { it.type = Material.CRAFTING_TABLE }) shouldBe null
                val named = placeEvent(plugin, player, world.getBlockAt(0, 64, 0), ApocalypseWorkbenchStation.T2)
                named.itemInHand.editMeta { it.displayName(Component.text("Named workstation")) }
                stations.onPlace(named)
                (named.block.state as Container).customName() shouldBe Component.text("Named workstation")
                stations.close()
                stations.isUsable(player, ApocalypsePoint(4, 64, 0), ApocalypseWorkbenchStation.CRAFTING) shouldBe false
            }
        }
    }

    "native workstation inventories reject player and hopper access without affecting ordinary inventories" {
        MockBukkitTestRuntime.open().use { paper ->
            val plugin = paper.createSimplePlugin("ApocalypseStationInventoryTest")
            val world = paper.addSimpleWorld("expedition")
            val player = paper.addPlayer("Survivor")
            ApocalypseWorkbenchStations(plugin, { world }, { true }).use { stations ->
                stations.register()
                val station = placeStation(stations, plugin, player, world, ApocalypseWorkbenchStation.T2)
                val plain = world.getBlockAt(2, 64, 0).also { it.type = Material.DROPPER }
                val stationInventory = (station.state as Container).inventory
                val plainInventory = (plain.state as Container).inventory
                if (stationInventory.location != station.location) throw TestAbortedException(
                    "MockBukkit 4.116.3 InventoryMock.getLocation returns the first world spawn for block holders; native workstation inventory isolation remains unverified",
                )
                val view = mockk<InventoryView> { every { topInventory } returns stationInventory }
                InventoryOpenEvent(view).also(stations::onOpen).isCancelled shouldBe true
                InventoryMoveItemEvent(stationInventory, ItemStack.of(Material.DIAMOND), player.inventory, true)
                    .also(stations::onMove).isCancelled shouldBe true
                InventoryMoveItemEvent(player.inventory, ItemStack.of(Material.DIAMOND), stationInventory, false)
                    .also(stations::onMove).isCancelled shouldBe true
                InventoryMoveItemEvent(plainInventory, ItemStack.of(Material.DIAMOND), player.inventory, true)
                    .also(stations::onMove).isCancelled shouldBe false
            }
        }
    }

    "unidentified blocks keep native behavior while station placement, redstone, explosions and pistons are protected" {
        MockBukkitTestRuntime.open().use { paper ->
            val plugin = paper.createSimplePlugin("ApocalypseStationEventsTest")
            val world = paper.addSimpleWorld("expedition")
            val other = paper.addSimpleWorld("ordinary")
            val player = paper.addPlayer("Survivor")
            var active = true
            ApocalypseWorkbenchStations(plugin, { world }, { active }).use { stations ->
                stations.register()
                val station = placeStation(stations, plugin, player, world, ApocalypseWorkbenchStation.T2)
                val plain = world.getBlockAt(2, 64, 0).also { it.type = Material.DROPPER }
                val table = world.getBlockAt(3, 64, 0).also { it.type = Material.CRAFTING_TABLE }
                val outside = placeStation(stations, plugin, player, other, ApocalypseWorkbenchStation.T2)
                BlockDispenseEvent(station, ItemStack.of(Material.DIAMOND), Vector()).also(stations::onDispense).isCancelled shouldBe true
                BlockDispenseEvent(plain, ItemStack.of(Material.DIAMOND), Vector()).also(stations::onDispense).isCancelled shouldBe false
                BlockDispenseEvent(outside, ItemStack.of(Material.DIAMOND), Vector()).also(stations::onDispense).isCancelled shouldBe false
                val blockExplosion = BlockExplodeEvent(plain, plain.state, mutableListOf(station, plain, table, outside), 1f, ExplosionResult.DESTROY)
                stations.onBlockExplosion(blockExplosion)
                blockExplosion.blockList() shouldBe listOf(plain, table, outside)
                val entityExplosion = EntityExplodeEvent(player, station.location, mutableListOf(station, plain), 1f, ExplosionResult.DESTROY)
                stations.onEntityExplosion(entityExplosion)
                entityExplosion.blockList() shouldBe listOf(plain)
                BlockPistonExtendEvent(plain, listOf(station), BlockFace.EAST).also(stations::onExtend).isCancelled shouldBe true
                BlockPistonRetractEvent(plain, listOf(station), BlockFace.EAST).also(stations::onRetract).isCancelled shouldBe true
                BlockPistonExtendEvent(plain, listOf(table), BlockFace.EAST).also(stations::onExtend).isCancelled shouldBe false
                active = false
                BlockBreakEvent(station, player).also(stations::onBreak).isCancelled shouldBe true
                BlockBreakEvent(plain, player).also(stations::onBreak).isCancelled shouldBe false
                val denied = placeEvent(plugin, player, world.getBlockAt(6, 64, 0), ApocalypseWorkbenchStation.T3)
                stations.onPlace(denied)
                denied.isCancelled shouldBe true
                val outsidePlacement = placeEvent(plugin, player, other.getBlockAt(6, 64, 0), ApocalypseWorkbenchStation.T3)
                stations.onPlace(outsidePlacement)
                outsidePlacement.isCancelled shouldBe false
            }
        }
    }

    "native station breaking retains one identified carrier and all container and dependent-block drops" {
        MockBukkitTestRuntime.open().use { paper ->
            val plugin = paper.createSimplePlugin("ApocalypseStationDropsTest")
            val world = paper.addSimpleWorld("expedition")
            val player = paper.addPlayer("Survivor")
            ApocalypseWorkbenchStations(plugin, { world }, { true }).use { stations ->
                stations.register()
                val block = placeStation(stations, plugin, player, world, ApocalypseWorkbenchStation.PRESS)
                val before = block.state
                player.inventory.setItemInMainHand(ItemStack.of(Material.IRON_PICKAXE))
                val carrier = try {
                    before.getDrops(player.inventory.itemInMainHand, player).single { it.type == Material.DROPPER }
                } catch (failure: UnimplementedOperationException) {
                    throw TestAbortedException("MockBukkit 4.116.3 BlockState.getDrops(ItemStack, Entity) is unimplemented; native breaking remains unverified", failure)
                }
                BlockBreakEvent(block, player).also(stations::onBreak).isCancelled shouldBe false
                block.type = Material.AIR
                val drops = mutableListOf(
                    world.dropItem(block.location, carrier),
                    world.dropItem(block.location, ItemStack.of(Material.DIAMOND, 17)),
                    world.dropItem(block.location, ItemStack.of(Material.TORCH)),
                )
                val event = BlockDropItemEvent(block, before, player, drops)
                stations.onDrop(event)
                event.items.map { it.itemStack.type to it.itemStack.amount } shouldBe listOf(Material.DROPPER to 1, Material.DIAMOND to 17, Material.TORCH to 1)
                event.items.first().itemStack.itemMeta.persistentDataContainer
                    .get(NamespacedKey(plugin, "apocalypse_source_item"), PersistentDataType.STRING) shouldBe "create:mechanical_press"
            }
        }
    }

    "tungsten blasting matches the typed source item only, keeps source time and XP, and removes its generation" {
        MockBukkitTestRuntime.open().use { paper ->
            val plugin = paper.createSimplePlugin("ApocalypseTungstenTest")
            val world = paper.addSimpleWorld("expedition")
            val other = paper.addSimpleWorld("ordinary")
            val raw = identifiedItem(plugin, Material.RAW_IRON, "the_deeper_void:raw_tungsten")
            val ingot = identifiedItem(plugin, Material.IRON_INGOT, "the_deeper_void:tungsten_ingot")
            val key = NamespacedKey(plugin, "apocalypse_tungsten_blasting")
            ApocalypseWorkbenchStations(plugin, { world }, { true }).use { stations ->
                stations.register()
                stations.registerTungstenBlasting(raw, ingot)
                val recipe = paper.server.getRecipe(key) as BlastingRecipe
                recipe.inputChoice.test(raw) shouldBe true
                recipe.inputChoice.test(ItemStack.of(Material.RAW_IRON)) shouldBe false
                recipe.inputChoice.test(identifiedItem(plugin, Material.RAW_IRON, "mcore:raw_titanium")) shouldBe false
                recipe.result shouldBe ingot
                recipe.cookingTime shouldBe 240
                recipe.experience shouldBe 2f
                for ((target, denied) in listOf(world to false, other to true)) {
                    val block = target.getBlockAt(0, 64, 0).also { it.type = Material.BLAST_FURNACE }
                    (block.state as Furnace).inventory.smelting = raw.clone()
                    FurnaceBurnEvent(block, ItemStack.of(Material.COAL), 1600).also(stations::onBurn).isCancelled shouldBe denied
                    FurnaceSmeltEvent(block, raw.clone(), ingot.clone(), recipe).also(stations::onSmelt).isCancelled shouldBe denied
                    (block.state as Furnace).inventory.smelting = ItemStack.of(Material.RAW_IRON)
                    FurnaceBurnEvent(block, ItemStack.of(Material.COAL), 1600).also(stations::onBurn).isCancelled shouldBe false
                }
                val ordinaryRecipe = FurnaceRecipe(NamespacedKey.minecraft("iron_ingot_from_smelting_raw_iron"), ItemStack.of(Material.IRON_INGOT), Material.RAW_IRON, .7f, 200)
                val ordinaryFurnace = world.getBlockAt(2, 64, 0).also { it.type = Material.FURNACE }
                (ordinaryFurnace.state as Furnace).inventory.smelting = raw.clone()
                FurnaceBurnEvent(ordinaryFurnace, ItemStack.of(Material.COAL), 1600).also(stations::onBurn).isCancelled shouldBe true
                FurnaceSmeltEvent(ordinaryFurnace, raw.clone(), ItemStack.of(Material.IRON_INGOT), ordinaryRecipe)
                    .also(stations::onSmelt).isCancelled shouldBe true
                val renamed = raw.clone().also { item -> item.editMeta { it.displayName(Component.text("Rare tungsten")) } }
                val blast = world.getBlockAt(3, 64, 0).also { it.type = Material.BLAST_FURNACE }
                (blast.state as Furnace).inventory.smelting = renamed
                FurnaceBurnEvent(blast, ItemStack.of(Material.COAL), 1600).also(stations::onBurn).isCancelled shouldBe true
                val fallback = FurnaceSmeltEvent(blast, renamed.clone(), ItemStack.of(Material.IRON_INGOT), ordinaryRecipe)
                stations.onSmelt(fallback)
                fallback.isCancelled shouldBe true
                fallback.source shouldBe renamed
                (blast.state as Furnace).inventory.smelting shouldBe renamed
                FurnaceSmeltEvent(ordinaryFurnace, ItemStack.of(Material.RAW_IRON), ItemStack.of(Material.IRON_INGOT), ordinaryRecipe)
                    .also(stations::onSmelt).isCancelled shouldBe false
                stations.close()
                paper.server.getRecipe(key) shouldBe null
                stations.register()
                stations.registerTungstenBlasting(raw, ingot)
                (paper.server.getRecipe(key) as BlastingRecipe).result shouldBe ingot
            }
            paper.server.getRecipe(key) shouldBe null
        }
    }
})

private fun placeStation(stations: ApocalypseWorkbenchStations, plugin: Plugin, player: Player, world: World, station: ApocalypseWorkbenchStation): Block {
    val event = placeEvent(plugin, player, world.getBlockAt(0, 64, 0), station)
    stations.onPlace(event)
    event.isCancelled shouldBe false
    return event.block
}

private fun placeEvent(plugin: Plugin, player: Player, block: Block, station: ApocalypseWorkbenchStation): BlockPlaceEvent {
    block.type = Material.AIR
    val previous = block.state
    block.type = Material.DROPPER
    return BlockPlaceEvent(block, previous, block.getRelative(BlockFace.DOWN), ApocalypseWorkbenchStations.stationItem(plugin, station, station.sourceId), player, true, EquipmentSlot.HAND)
}

private fun identifiedItem(plugin: Plugin, material: Material, id: String): ItemStack = ItemStack.of(material).also { item ->
    item.editMeta { it.persistentDataContainer.set(NamespacedKey(plugin, "apocalypse_source_item"), PersistentDataType.STRING, id) }
}
