package ru.ruscrafting.events.paper

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import net.kyori.adventure.text.Component
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.World
import org.bukkit.block.Block
import org.bukkit.block.data.BlockData
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType
import org.bukkit.entity.Player
import org.bukkit.entity.Zombie
import org.bukkit.util.Vector
import org.bukkit.util.RayTraceResult
import org.bukkit.FluidCollisionMode
import ru.arc.paper.display.PacketTextDisplay
import ru.arc.paper.display.PacketItemDisplay
import ru.arc.paper.display.PacketBlockDisplay
import ru.arc.paper.display.PaperPacketDisplays
import ru.arc.core.BukkitTaskScheduler
import ru.arc.core.Tasks
import ru.arc.paper.testing.MockBukkitTestRuntime
import ru.ruscrafting.events.config.ArcEventsLocale
import ru.ruscrafting.events.domain.FirearmId
import ru.ruscrafting.events.domain.TttFirearmCatalog
import ru.ruscrafting.events.domain.ZombiesOffer
import ru.ruscrafting.events.domain.ZombiesRules
import java.util.UUID
import java.util.function.Consumer

class ZombiesAdventureTest : StringSpec({
    "only a living owner can hit a known mob and lethal hits remove it once without native health damage" {
        ZombieControllerFixture().use { f ->
            f.startWave()
            val mob = f.mobs.first()
            val outsider = mockk<Player>(relaxed = true)
            every { outsider.uniqueId } returns UUID.randomUUID()
            every { outsider.isOnline } returns true
            every { outsider.world } returns f.world
            f.game.isFirearmTarget(outsider, mob, f.match.toString()) shouldBe false
            f.game.isFirearmTarget(f.player, mob, UUID.randomUUID().toString()) shouldBe false
            val hit = FirearmHit(mob.location.toVector(), false)
            f.game.hitByFirearm(f.player, mob, hit, 1.0, f.match.toString())
            verify(exactly = 1) { mob.playHurtAnimation(any()) }
            f.game.hitByFirearm(f.player, mob, hit, 1000.0, f.match.toString())
            f.game.hitByFirearm(f.player, mob, hit, 1000.0, f.match.toString())
            verify(exactly = 1) { mob.remove() }
            verify(exactly = 0) { mob.health = any() }
            verify(exactly = 0) { mob.damage(any<Double>()) }
            f.kills shouldBe 1
            f.game.director.players.getValue(f.player.uniqueId).points shouldBe 600
            f.game.director.alive shouldBe 1
            f.game.close()
            f.game.close()
            f.mobs.forEach { verify(exactly = 1) { it.remove() } }
            f.signs.forEach { verify(exactly = 1) { it.remove() } }
        }
    }

    "an owned actor moved out of its arena is removed without awarding a kill" {
        ZombieControllerFixture().use { f ->
            f.startWave()
            val moved = f.mobs.first()
            val otherWorld = mockk<World> { every { uid } returns UUID.randomUUID() }
            every { moved.world } returns otherWorld
            f.now += 1000
            f.game.tick()
            verify(exactly = 1) { moved.remove() }
            f.game.isFirearmTarget(f.player, moved, f.match.toString()) shouldBe false
            f.kills shouldBe 0
            f.game.director.players.getValue(f.player.uniqueId).points shouldBe 500
            f.game.close()
            verify(exactly = 1) { moved.remove() }
        }
    }

    "stale purchase callbacks and movement away from the physical station cannot spend points twice" {
        ZombieControllerFixture().use { f ->
            f.game.start()
            val state = f.game.director.players.getValue(f.player.uniqueId)
            f.game.buy(f.player, state, ZombiesOffer.AMMO) shouldBe false
            f.at = Location(f.world, -11.5, 65.0, -21.0)
            f.game.buy(f.player, state, ZombiesOffer.AMMO) shouldBe true
            f.game.buy(f.player, state, ZombiesOffer.AMMO) shouldBe false
            f.game.director.players.getValue(f.player.uniqueId).points shouldBe 350
            f.game.down(f.player)
            f.game.buy(f.player, f.game.director.players.getValue(f.player.uniqueId), ZombiesOffer.AMMO) shouldBe false
        }
    }

    "native spawn failure resolves and closes the session instead of leaving an unfinishable wave" {
        ZombieControllerFixture().use { f ->
            f.failSpawn = true
            f.startWave()
            f.completed shouldBe listOf(false)
            f.game.belongsTo(f.player) shouldBe false
            f.signs.forEach { verify(exactly = 1) { it.remove() } }
            f.game.tick()
            f.completed shouldBe listOf(false)
        }
    }

    "physical box rolls before a single slot replacement and its repeated click never charges again" {
        ZombieControllerFixture().use { f ->
            f.fundBox()
            val before = f.game.director.players.getValue(f.player.uniqueId).weapon
            f.clickBox()
            f.game.mysteryBoxPhase() shouldBe "rolling"
            f.game.director.players.getValue(f.player.uniqueId).points shouldBe 50
            f.clickBox()
            f.game.director.players.getValue(f.player.uniqueId).points shouldBe 50
            f.game.director.players.getValue(f.player.uniqueId).weapon shouldBe before
            f.now += ZombiesMysteryBox.ROLL_MS
            f.game.tickEffects()
            f.game.mysteryBoxPhase() shouldBe "ready"
            f.game.interact(f.player, null) shouldBe false // looking away preserves ordinary firearm input
            val byBox = f.at.clone()
            f.at = Location(f.world, 15.0, 65.0, 15.0)
            f.game.interact(f.player, null) shouldBe false
            f.at = byBox
            f.at.direction = Vector(-.3, .58, -.55)
            val obstruction = mockk<RayTraceResult> { every { hitBlock } returns f.block(-4, 66, -21) }
            every { f.world.rayTraceBlocks(any(), any(), any<Double>(), FluidCollisionMode.NEVER, true) } returns obstruction
            f.game.interact(f.player, null) shouldBe false
            every { f.world.rayTraceBlocks(any(), any(), any<Double>(), FluidCollisionMode.NEVER, true) } returns null
            val foreign = mockk<World> { every { uid } returns UUID.randomUUID() }
            every { f.player.world } returns foreign
            f.game.interact(f.player, null) shouldBe false
            every { f.player.world } returns f.world
            f.game.director.players.getValue(f.player.uniqueId).weapon shouldBe before
            f.game.interact(f.player, null) shouldBe true
            f.game.mysteryBoxPhase() shouldBe "idle"
            (f.game.director.players.getValue(f.player.uniqueId).weapon != before) shouldBe true
            f.game.director.players.getValue(f.player.uniqueId).points shouldBe 50
            f.clickBox()
            f.game.director.players.getValue(f.player.uniqueId).points shouldBe 50
            f.weaponDisplays.forEach { verify(exactly = 1) { it.remove() } }
            verify(exactly = 0) { f.world.dropItem(any<Location>(), any<ItemStack>()) }
        }
    }

    "box expiry spends the roll while changed chest and failed inventory delivery refund once" {
        for (failure in listOf("expiry", "chest", "inventory", "leave", "shutdown")) ZombieControllerFixture().use { f ->
            f.fundBox()
            val oldWeapon = f.game.director.players.getValue(f.player.uniqueId).weapon
            val oldStack = f.inventory[0]?.clone()
            val oldInventory = f.inventory.mapValues { it.value?.clone() }
            f.clickBox()
            when (failure) {
                "expiry" -> { f.now += ZombiesMysteryBox.ROLL_MS + ZombiesMysteryBox.CLAIM_MS; f.game.tickEffects() }
                "chest" -> { f.block(-5, 65, -22).setType(Material.STONE, false); f.game.tickEffects() }
                "inventory" -> {
                    f.now += ZombiesMysteryBox.ROLL_MS
                    f.game.tickEffects()
                    f.failSlotOnce = 9
                    f.clickBox()
                    f.inventory[0] shouldBe oldStack
                    f.inventory shouldBe oldInventory
                    f.inventory[0]?.itemMeta?.displayName() shouldBe Component.text(oldWeapon.name)
                }
                "leave" -> f.game.disconnect(f.player)
                else -> f.game.close()
            }
            f.game.director.players.getValue(f.player.uniqueId).points shouldBe
                if (failure in setOf("expiry", "leave")) 50 else 700
            f.game.director.players.getValue(f.player.uniqueId).weapon shouldBe oldWeapon
            f.game.close()
            f.weaponDisplays.forEach { verify(exactly = 1) { it.remove() } }
        }
    }

    "assigned zombies breach three boards before entering and continuous aimed sneak repairs without points" {
        ZombieControllerFixture().use { f ->
            f.startWave()
            val mob = f.mobs.first()
            val index = ZombiesArenaGenerator.WINDOWS.indexOfFirst { window ->
                kotlin.math.abs(window.along(mob.location.x, mob.location.z)) < .1 &&
                    kotlin.math.abs(window.inward(mob.location.x, mob.location.z) + 1.5) < .1
            }
            val window = ZombiesArenaGenerator.WINDOWS[index]
            repeat(3) {
                f.now += ZombiesBarricades.STRIKE_MS
                f.game.tick()
                f.game.barricadeBoards()[index] shouldBe 2 - it
            }
            window.gateCells().forEach { (x, y, z) -> f.block(x, y, z).type shouldBe Material.AIR }
            f.game.director.players.getValue(f.player.uniqueId).points shouldBe 500
            f.mobs.filter { window.inward(it.location.x, it.location.z) < 1.1 &&
                kotlin.math.abs(window.along(it.location.x, it.location.z)) < 1.8 }.forEach {
                it.teleport(Location(f.world, window.x + .5 + window.inwardX * 4, 65.0, window.z + .5 + window.inwardZ * 4))
            }
            f.at = Location(f.world, window.x + .5 + window.inwardX * 2, 65.0, window.z + .5 + window.inwardZ * 2)
            f.at.direction = Vector(-window.inwardX.toDouble(), -.2, -window.inwardZ.toDouble())
            f.sneaking = true
            f.game.tickEffects()
            f.now += ZombiesBarricades.REPAIR_MS - 1
            f.game.tickEffects()
            f.game.barricadeBoards()[index] shouldBe 0
            f.sneaking = false
            f.now += 1
            f.game.tickEffects()
            f.game.barricadeBoards()[index] shouldBe 0
            f.sneaking = true
            f.game.tickEffects()
            repeat(3) {
                f.now += ZombiesBarricades.REPAIR_MS
                f.game.tickEffects()
                f.game.barricadeBoards()[index] shouldBe it + 1
            }
            f.game.director.players.getValue(f.player.uniqueId).points shouldBe 500
            window.gateCells().forEach { (x, y, z) -> f.block(x, y, z).type shouldBe Material.SPRUCE_FENCE }
            f.game.close()
            f.boards.forEach { verify(exactly = 1) { it.remove() } }
        }
    }
})

private class ZombieControllerFixture : AutoCloseable {
    private val paper = MockBukkitTestRuntime.open()
    private val plugin = paper.createSimplePlugin("ZombieControllerTest")
    val world = mockk<World>(relaxed = true)
    val player = mockk<Player>(relaxed = true)
    val match = UUID.randomUUID()
    var at = Location(world, .5, 65.0, -18.5)
    var now = 0L
    var failSpawn = false
    var failSlotOnce: Int? = null
    var sneaking = false
    var kills = 0
    val completed = mutableListOf<Boolean>()
    val mobs = mutableListOf<Zombie>()
    val signs = mutableListOf<PacketTextDisplay>()
    val boards = mutableListOf<PacketBlockDisplay>()
    val weaponDisplays = mutableListOf<PacketItemDisplay>()
    val inventory = mutableMapOf<Int, ItemStack?>()
    private val blocks = mutableMapOf<Triple<Int, Int, Int>, Block>()
    val game: ZombiesAdventure

    init {
        Tasks.install(BukkitTaskScheduler(plugin))
        every { world.uid } returns UUID.randomUUID()
        every { world.entities } returns emptyList()
        every { world.isChunkLoaded(any<Int>(), any<Int>()) } returns true
        every { world.getBlockAt(any<Location>()) } answers { firstArg<Location>().let { block(it.blockX, it.blockY, it.blockZ) } }
        every { world.getBlockAt(any<Int>(), any<Int>(), any<Int>()) } answers { block(firstArg(), secondArg(), thirdArg()) }
        every { player.uniqueId } returns UUID.randomUUID()
        every { player.isOnline } returns true
        every { player.world } returns world
        every { player.location } answers { at.clone() }
        every { player.eyeLocation } answers { at.clone().add(0.0, 1.62, 0.0) }
        every { player.isSneaking } answers { sneaking }
        every { player.inventory.getItem(any<Int>()) } answers { inventory[firstArg()] }
        every { player.inventory.setItem(any<Int>(), any()) } answers {
            val slot = firstArg<Int>()
            if (slot == failSlotOnce) { failSlotOnce = null; error("Synthetic inventory failure") }
            inventory[slot] = secondArg<ItemStack?>()?.clone()
        }
        every { world.spawn(any<Location>(), Zombie::class.java, any<Consumer<Zombie>>()) } answers {
            if (failSpawn) error("Synthetic native spawn rejection")
            var point = firstArg<Location>().clone()
            val mob = mockk<Zombie>(relaxed = true)
            every { mob.uniqueId } returns UUID.randomUUID()
            every { mob.world } returns world
            every { mob.location } answers { point.clone() }
            every { mob.eyeLocation } answers { point.clone().add(0.0, 1.7, 0.0) }
            every { mob.isValid } returns true
            every { mob.isDead } returns false
            every { mob.target } returns null
            every { mob.teleport(any<Location>()) } answers { point = firstArg<Location>().clone(); true }
            thirdArg<Consumer<Zombie>>().accept(mob)
            mobs += mob
            mob
        }
        val locale = mockk<ArcEventsLocale>(relaxed = true)
        every { locale.render(any(), any(), any()) } returns Component.text("Test")
        every { locale.text(any()) } answers { Component.text(firstArg<Any>().toString()) }
        val firearms = mockk<TttFirearms>(relaxed = true)
        every { firearms.spec(any()) } answers { TttFirearmCatalog.specs.getValue(firstArg<FirearmId>()) }
        every { firearms.firearmItem(any(), any(), any(), any()) } answers {
            val weapon = firstArg<FirearmId>()
            val matchId = thirdArg<String>()
            ItemStack(Material.CROSSBOW).also { item -> item.editMeta { meta ->
                meta.displayName(Component.text(weapon.name))
                meta.persistentDataContainer.set(NamespacedKey(plugin, "test_match"), PersistentDataType.STRING, matchId)
            } }
        }
        every { firearms.ammunition(any(), any(), any()) } answers { ItemStack(Material.PAPER, lastArg<Int>()) }
        val items = mockk<TttItems>(relaxed = true)
        every { items.arcadeKnife(any(), any()) } answers { ItemStack(Material.IRON_SWORD).also { item ->
            item.editMeta { meta -> meta.displayName(Component.text("Match knife"))
                meta.persistentDataContainer.set(NamespacedKey(plugin, "test_match"), PersistentDataType.STRING, secondArg<String>()) }
        } }
        val displays = mockk<PaperPacketDisplays>(relaxed = true)
        every { displays.spawnText(any(), any()) } answers { mockk<PacketTextDisplay>(relaxed = true).also(signs::add) }
        every { displays.spawnBlock(any(), any()) } answers { mockk<PacketBlockDisplay>(relaxed = true).also(boards::add) }
        every { displays.spawnItem(any(), any()) } answers {
            val point = firstArg<Location>().clone()
            mockk<PacketItemDisplay>(relaxed = true).also { display ->
                every { display.location } answers { point.clone() }
                every { display.isValid } returns true
                weaponDisplays += display
            }
        }
        game = ZombiesAdventure(plugin, locale, items, firearms, ZombiesRules(), match, world, listOf(player),
            active = { true }, isAlive = { true }, revivePlayer = {}, hurt = { _, _ -> }, killed = { kills++ },
            complete = { completed += it }, clearWeaponState = {}, displays = displays, sounds = { false }, particles = { false }, clock = { now })
    }
    fun block(x: Int, y: Int, z: Int): Block = blocks.getOrPut(Triple(x, y, z)) {
        var type = ZombiesArenaGenerator.materialAt(x, y, z) ?: Material.AIR
        var data = if (type == Material.SPRUCE_FENCE) ZombiesArenaGenerator.fenceData(x, z) else type.createBlockData()
        mockk<Block>(relaxed = true).also { block ->
            every { block.type } answers { type }
            every { block.blockData } answers { data.clone() }
            every { block.isPassable } answers { type == Material.AIR }
            every { block.location } answers { Location(world, x.toDouble(), y.toDouble(), z.toDouble()) }
            every { block.state } returns mockk<org.bukkit.block.Chest>(relaxed = true)
            every { block.getRelative(any<Int>(), any<Int>(), any<Int>()) } answers {
                this@ZombieControllerFixture.block(x + firstArg<Int>(), y + secondArg<Int>(), z + thirdArg<Int>())
            }
            every { block.setType(any<Material>(), any<Boolean>()) } answers { type = firstArg(); data = type.createBlockData() }
            every { block.setBlockData(any<BlockData>(), any<Boolean>()) } answers { data = firstArg<BlockData>().clone(); type = data.material }
        }
    }
    fun startWave() { game.start(); now = 15_000; game.tick() }
    fun fundBox() {
        startWave()
        mobs.take(2).forEach { game.hitByFirearm(player, it, FirearmHit(it.location.toVector(), false), 1000.0, match.toString()) }
        at = Location(world, -4.0, 65.0, -20.5)
    }
    fun clickBox() { game.interact(player, Location(world, -4.0, 65.0, -22.0)) shouldBe true }
    override fun close() { game.close(); Tasks.reset(); paper.close() }
}
