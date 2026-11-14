package ru.ruscrafting.events.paper.apocalypse

import com.destroystokyo.paper.entity.Pathfinder
import com.destroystokyo.paper.entity.ai.Goal
import com.destroystokyo.paper.entity.ai.GoalKey
import com.destroystokyo.paper.entity.ai.GoalType
import com.destroystokyo.paper.entity.ai.MobGoals
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.bukkit.FluidCollisionMode
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.Server
import org.bukkit.World
import org.bukkit.block.Block
import org.bukkit.entity.Entity
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Zombie
import org.bukkit.plugin.Plugin
import org.bukkit.util.RayTraceResult
import org.bukkit.util.Vector
import ru.ruscrafting.events.domain.apocalypse.ApocalypseZombieSpawn
import ru.ruscrafting.events.domain.apocalypse.ApocalypseZombieStats
import ru.ruscrafting.events.domain.apocalypse.ApocalypseZombieVariant
import java.util.EnumSet
import java.util.UUID
import kotlin.math.abs
import kotlin.random.Random

/** Interface-boundary checks; native navigation, projectile physics and client poses need Paper. */
class ApocalypseMobGoalTest : StringSpec({
    "reinstallation replaces the owned goal once while retaining native target selection" {
        val fixture = ApocalypseGoalFixture()
        val registry = mutableListOf<Goal<Zombie>>()
        val targetGoal = mockk<Goal<Zombie>> {
            every { getKey() } returns GoalKey.of(Zombie::class.java, NamespacedKey.minecraft("nearest_attackable_target"))
            every { getTypes() } returns EnumSet.of(GoalType.TARGET)
        }
        registry += targetGoal
        val goals = mockk<MobGoals>(relaxed = true)
        val server = mockk<Server> { every { mobGoals } returns goals }
        every { fixture.plugin.server } returns server
        every { goals.removeGoal(fixture.mob, any<GoalKey<Zombie>>()) } answers {
            val key = secondArg<GoalKey<Zombie>>()
            registry.removeAll { it.getKey() == key }
            Unit
        }
        every { goals.removeAllGoals(fixture.mob, GoalType.MOVE) } answers {
            registry.removeAll { GoalType.MOVE in it.getTypes() }
            Unit
        }
        every { goals.addGoal(fixture.mob, 0, any<Goal<Zombie>>()) } answers {
            registry += thirdArg<Goal<Zombie>>()
            Unit
        }
        repeat(2) {
            installApocalypseMobGoal(fixture.plugin, fixture.mob,
                ApocalypseZombieSpawn("test", ApocalypseZombieVariant.WALKER), ApocalypseZombieStats(20.0, 3.0, .23)) { false }
        }
        registry.size shouldBe 2
        (targetGoal in registry) shouldBe true
        registry.count { it.getKey().namespacedKey.key == "apocalypse_combat" } shouldBe 1
        verify(exactly = 0) { goals.removeAllGoals(fixture.mob, GoalType.TARGET) }
    }

    "melee uses the native attack at twenty world ticks and the exact pursuit multiplier" {
        val fixture = ApocalypseGoalFixture()
        val goal = fixture.goal()
        goal.getTypes() shouldBe EnumSet.of(GoalType.MOVE, GoalType.LOOK)
        goal.shouldActivate() shouldBe true
        goal.start()
        goal.tick()
        fixture.tick = 19
        goal.tick()
        verify(exactly = 1) { fixture.mob.attack(fixture.target) }
        fixture.tick = 20
        goal.tick()
        verify(exactly = 2) { fixture.mob.attack(fixture.target) }
        verify { fixture.pathfinder.moveTo(fixture.target, 1.8) }
        fixture.enabled = false
        goal.shouldStayActive() shouldBe false
        goal.stop()
        verify { fixture.pathfinder.stopPathfinding() }
    }

    "rage respects the owning predicate and never traces into an unloaded chunk" {
        val denied = ApocalypseGoalFixture()
        val block = mockk<Block>(relaxed = true)
        every { block.world } returns denied.world
        every { block.type } returns Material.STONE
        val hit = mockk<RayTraceResult> { every { hitBlock } returns block }
        every { denied.world.rayTraceBlocks(any<Location>(), any<Vector>(), 4.0, FluidCollisionMode.NEVER, true) } returns hit
        var checked = 0
        denied.goal("raged") { checked++; false }.tick()
        checked shouldBe 1
        verify(exactly = 0) { block.setType(any<Material>(), any<Boolean>()) }

        val edge = ApocalypseGoalFixture()
        edge.mobX = 14.0
        edge.targetX = 15.0
        every { edge.world.isChunkLoaded(any<Int>(), any<Int>()) } answers { firstArg<Int>() == 0 && secondArg<Int>() == 0 }
        edge.goal("raged") { true }.tick()
        verify(exactly = 0) { edge.world.rayTraceBlocks(any<Location>(), any<Vector>(), any<Double>(), any<FluidCollisionMode>(), any<Boolean>()) }
    }

    "leaper combines the source horizontal impulse and velocity before fixing vertical speed" {
        val jump = apocalypseLeapVelocity(Vector(3.0, 12.0, 4.0), Vector(.1, .3, -.1), .35)
        (abs(jump.x - .52) < 1e-12) shouldBe true
        jump.y shouldBe .35
        (abs(jump.z - .6) < 1e-12) shouldBe true
        apocalypseLeapVelocity(Vector(0.0, 3.0, 0.0), Vector(1.0, 1.0, 1.0), .35) shouldBe Vector(0.0, .35, 0.0)
    }
})

private class ApocalypseGoalFixture {
    var tick = 0L
    var enabled = true
    var mobX = 0.0
    var targetX = 1.0
    val world = mockk<World>(relaxed = true) {
        every { uid } returns UUID(0, 1)
        every { gameTime } answers { tick }
        every { isChunkLoaded(any<Int>(), any<Int>()) } returns true
    }
    val plugin = mockk<Plugin>(relaxed = true) {
        every { name } returns "ArcEvents"
        every { isEnabled } answers { enabled }
    }
    val target = mockk<LivingEntity>(relaxed = true) {
        every { world } returns this@ApocalypseGoalFixture.world
        every { isValid } returns true
        every { width } returns .6
        every { location } answers { Location(this@ApocalypseGoalFixture.world, targetX, 64.0, 0.0) }
        every { eyeLocation } answers { Location(this@ApocalypseGoalFixture.world, targetX, 65.6, 0.0) }
    }
    val pathfinder = mockk<Pathfinder>(relaxed = true)
    val mob = mockk<Zombie>(relaxed = true) {
        every { world } returns this@ApocalypseGoalFixture.world
        every { target } returns this@ApocalypseGoalFixture.target
        every { pathfinder } returns this@ApocalypseGoalFixture.pathfinder
        every { isValid } returns true
        every { width } returns .6
        every { location } answers { Location(this@ApocalypseGoalFixture.world, mobX, 64.0, 0.0) }
        every { eyeLocation } answers { Location(this@ApocalypseGoalFixture.world, mobX, 65.6, 0.0) }
        every { hasLineOfSight(any<Entity>()) } returns true
    }

    fun goal(profile: String = "default", canBreak: (Block) -> Boolean = { false }) = ApocalypseMobGoal(
        plugin, mob, ApocalypseZombieSpawn("test", ApocalypseZombieVariant.WALKER, profile = profile),
        ApocalypseZombieStats(20.0, 3.0, .23, pursuitSpeed = 1.8), canBreak,
        object : Random() { override fun nextBits(bitCount: Int): Int = 0 },
    )
}
