package ru.ruscrafting.events.paper

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import net.kyori.adventure.text.Component
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.World
import org.bukkit.block.Block
import org.bukkit.entity.Player
import org.bukkit.entity.Zombie
import org.bukkit.util.Vector
import ru.arc.paper.display.PacketTextDisplay
import ru.arc.paper.display.PaperPacketDisplays
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
    var kills = 0
    val completed = mutableListOf<Boolean>()
    val mobs = mutableListOf<Zombie>()
    val signs = mutableListOf<PacketTextDisplay>()
    val game: ZombiesAdventure

    init {
        every { world.uid } returns UUID.randomUUID()
        every { world.entities } returns emptyList()
        every { world.isChunkLoaded(any<Int>(), any<Int>()) } returns true
        val block = mockk<Block>(relaxed = true)
        every { block.isPassable } returns true
        every { block.type } returns Material.STONE
        every { block.getRelative(any<Int>(), any<Int>(), any<Int>()) } returns block
        every { world.getBlockAt(any<Location>()) } returns block
        every { player.uniqueId } returns UUID.randomUUID()
        every { player.isOnline } returns true
        every { player.world } returns world
        every { player.location } answers { at.clone() }
        every { world.spawn(any<Location>(), Zombie::class.java, any<Consumer<Zombie>>()) } answers {
            if (failSpawn) error("Synthetic native spawn rejection")
            val point = firstArg<Location>().clone()
            val mob = mockk<Zombie>(relaxed = true)
            every { mob.uniqueId } returns UUID.randomUUID()
            every { mob.world } returns world
            every { mob.location } answers { point.clone() }
            every { mob.eyeLocation } answers { point.clone().add(0.0, 1.7, 0.0) }
            every { mob.isValid } returns true
            every { mob.isDead } returns false
            every { mob.target } returns null
            thirdArg<Consumer<Zombie>>().accept(mob)
            mobs += mob
            mob
        }
        val locale = mockk<ArcEventsLocale>(relaxed = true)
        every { locale.render(any(), any(), any()) } returns Component.text("Test")
        every { locale.text(any()) } answers { Component.text(firstArg<Any>().toString()) }
        val firearms = mockk<TttFirearms>(relaxed = true)
        every { firearms.spec(any()) } answers { TttFirearmCatalog.specs.getValue(firstArg<FirearmId>()) }
        val displays = mockk<PaperPacketDisplays>(relaxed = true)
        every { displays.spawnText(any(), any()) } answers { mockk<PacketTextDisplay>(relaxed = true).also(signs::add) }
        game = ZombiesAdventure(plugin, locale, mockk(relaxed = true), firearms, ZombiesRules(), match, world, listOf(player),
            active = { true }, isAlive = { true }, revivePlayer = {}, hurt = { _, _ -> }, killed = { kills++ },
            complete = { completed += it }, clearWeaponState = {}, displays = displays, sounds = { false }, particles = { false }, clock = { now })
    }
    fun startWave() { game.start(); now = 15_000; game.tick() }
    override fun close() { game.close(); paper.close() }
}
