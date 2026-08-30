package ru.ruscrafting.events.paper

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.inventory.ItemStack
import ru.arc.paper.testing.MockBukkitTestRuntime
import ru.ruscrafting.events.config.ArcEventsConfig

class TttLootSceneTest : FunSpec({
    lateinit var paper: MockBukkitTestRuntime

    beforeEach {
        paper = MockBukkitTestRuntime.open()
    }

    afterEach {
        paper.close()
    }

    test("an unsafe imported loot point is skipped instead of aborting match preparation") {
        val world = paper.addSimpleWorld("loot-world")
        world.getBlockAt(0, 63, 0).type = Material.BARRIER
        val scene = TttLootScene(
            plugin = paper.createSimplePlugin("ArcEventsLootTest"),
            firearms = mockk(relaxed = true),
            settings = { mockk<ArcEventsConfig>(relaxed = true) },
        )

        scene.spawn(Location(world, 0.5, 64.0, 0.5), ItemStack.of(Material.IRON_NUGGET)).shouldBeNull()
        scene.size shouldBe 0
    }

    test("exact points never move while random points may find nearby solid flooring") {
        val world = paper.addSimpleWorld("placement-world")
        world.getBlockAt(0, 63, 0).type = Material.BARRIER
        world.getBlockAt(1, 63, 0).type = Material.STONE
        val origin = Location(world, 0.5, 64.1, 0.5)

        TttLootPlacement.exact(origin).shouldBeNull()
        TttLootPlacement.nearby(origin)?.blockX shouldBe 1
    }
})
