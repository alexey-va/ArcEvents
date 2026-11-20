package ru.ruscrafting.events.paper

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.bukkit.HeightMap
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.World
import ru.arc.paper.testing.MockBukkitTestRuntime
import java.util.UUID

class DisastersRoundTest : StringSpec({
    "lag cannot skip a warning or squeeze a final unannounced strike into the end of a round" {
        val pulse = DisastersPulse(2_000, 1_000, 10_000)
        pulse.advance(3_000) shouldBe DisastersPulse.Beat.MARK
        pulse.advance(4_999) shouldBe DisastersPulse.Beat.CHARGE
        pulse.advance(7_000) shouldBe DisastersPulse.Beat.IMPACT
        pulse.advance(7_001) shouldBe DisastersPulse.Beat.QUIET
        pulse.advance(8_000) shouldBe DisastersPulse.Beat.QUIET
        pulse.advance(10_000) shouldBe DisastersPulse.Beat.QUIET
    }

    "a frozen meteor mark can be dodged and overlapping circles cause only one hit" {
        MockBukkitTestRuntime.open().use {
            val world = mockk<World>(relaxed = true)
            val mover = UUID.randomUUID()
            val stationary = UUID.randomUUID()
            val between = UUID.randomUUID()
            val locations = linkedMapOf(mover to Location(world, .5, 65.0, .5), stationary to Location(world, 5.0, 65.0, .5),
                between to Location(world, 2.75, 65.0, .5))
            val damage = mutableListOf<Pair<UUID, Double>>()
            val round = DisastersRound(0, 0, 10_000)
            fun tick(now: Long) = round.tick(now, locations, emptyList(), false, false) { id, amount -> damage += id to amount }
            tick(0)
            locations.getValue(mover).z += 3.1
            tick(1_999)
            damage shouldBe emptyList()
            tick(2_000)
            damage shouldBe listOf(stationary to 12.0, between to 12.0)
        }
    }

    "a lightning lane strikes exposed occupants, a roof and a last-second sidestep both protect" {
        MockBukkitTestRuntime.open().use {
            val world = mockk<World>(relaxed = true)
            every { world.isChunkLoaded(any<Int>(), any<Int>()) } returns true
            every { world.getBlockAt(any<Int>(), any<Int>(), any<Int>()).type } returns Material.AIR
            every { world.getHighestBlockYAt(any<Int>(), any<Int>(), HeightMap.OCEAN_FLOOR) } answers { if (firstArg<Int>() == -3) 72 else 64 }
            val exposed = UUID.randomUUID()
            val roof = UUID.randomUUID()
            val dodge = UUID.randomUUID()
            val locations = linkedMapOf(exposed to Location(world, .5, 65.0, .5), roof to Location(world, -2.5, 65.0, .5),
                dodge to Location(world, 3.5, 65.0, .5))
            val damage = mutableListOf<Pair<UUID, Double>>()
            val round = DisastersRound(1, 0, 10_000)
            fun tick(now: Long) = round.tick(now, locations, emptyList(), false, false) { id, amount -> damage += id to amount }
            tick(0)
            locations.getValue(dodge).z += DisastersFootprint.LANE_WIDTH + .01
            tick(2_000)
            damage shouldBe listOf(exposed to 6.0)
            val turned = DisastersFootprint(DisasterHazard.LIGHTNING, true)
            turned.contains(0.0, 0.0, DisastersFootprint.LANE_LENGTH) shouldBe true
            turned.contains(DisastersFootprint.LANE_WIDTH + .01, 0.0, 0.0) shouldBe false
        }
    }

    "the first fog bank retreats and the higher second bank never reaches the refuge" {
        val lower = UUID.randomUUID()
        val stairs = UUID.randomUUID()
        val lookout = UUID.randomUUID()
        val locations = mapOf(lower to Location(null, .5, 66.0, .5), stairs to Location(null, .5, 67.5, .5),
            lookout to Location(null, .5, DisasterArenaGenerator.FOG_SAFE_Y, .5))
        val damage = mutableListOf<UUID>()
        val round = DisastersRound(2, 0, 25_000)
        fun tick(now: Long) = round.tick(now, locations, emptyList(), false, false) { id, _ -> damage += id }
        tick(6_000)
        damage shouldBe listOf(lower)
        tick(10_500)
        damage shouldBe listOf(lower)
        tick(20_000)
        tick(20_200)
        damage shouldBe listOf(lower, lower, stairs)
        tick(25_000)
        damage shouldBe listOf(lower, lower, stairs)
    }
})
