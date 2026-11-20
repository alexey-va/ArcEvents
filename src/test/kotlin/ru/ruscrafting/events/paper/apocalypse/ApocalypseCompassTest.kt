package ru.ruscrafting.events.paper.apocalypse

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import net.kyori.adventure.bossbar.BossBar
import net.kyori.adventure.text.Component
import org.bukkit.Location
import ru.arc.dungeons.api.CompassStrip
import ru.arc.paper.testing.MockBukkitTestRuntime
import ru.ruscrafting.events.domain.apocalypse.ApocalypseInfectionStage
import ru.ruscrafting.events.domain.apocalypse.ApocalypsePoint

class ApocalypseCompassTest : StringSpec({
    val exit = GuideRoute(ApocalypsePoint(12, 64, 0))
    val supplies = GuideRoute(ApocalypsePoint(0, 64, -12))
    val guide = ApocalypseGuideSnapshot(day = 1, nextHordeDay = 10, infection = ApocalypseInfectionStage.HEALTHY,
        hasGun = false, looseAmmo = 0, inStarter = true, supplies = listOf(supplies), exit = exit,
        city = null, weapons = null, tracked = GuideTarget.EXIT)

    "turning, walking and selecting a different guide route update the same dungeon strip" {
        MockBukkitTestRuntime.open().use { paper ->
            val player = paper.addPlayer("Navigator")
            player.teleport(Location(player.world, 0.5, 64.0, 0.5, 0f, 0f))
            val other = BossBar.bossBar(Component.text("Other"), 0.5f, BossBar.Color.BLUE, BossBar.Overlay.PROGRESS)
            player.showBossBar(other)
            ApocalypseCompass(player).use { compass ->
                compass.refresh(null)
                val bar = player.activeBossBars().single { it !== other }
                bar.name() shouldBe CompassStrip.render(0f, emptyList())
                bar.color() shouldBe BossBar.Color.WHITE
                bar.overlay() shouldBe BossBar.Overlay.PROGRESS
                bar.progress() shouldBe 1f

                compass.refresh(guide)
                bar.name() shouldBe CompassStrip.render(0f, -90.0, "◇", 0x9bd48d)
                player.teleport(player.location.apply { yaw = -90f })
                compass.refresh(guide)
                bar.name() shouldBe CompassStrip.render(-90f, -90.0, "◇", 0x9bd48d)
                player.teleport(Location(player.world, 12.5, 64.0, -12.5, 0f, 0f))
                compass.refresh(guide)
                bar.name() shouldBe CompassStrip.render(0f, 0.0, "◇", 0x9bd48d)

                player.teleport(Location(player.world, 0.5, 64.0, 0.5, 180f, 0f))
                compass.refresh(guide.copy(tracked = GuideTarget.SUPPLIES))
                bar.name() shouldBe CompassStrip.render(180f, 180.0, "◇", 0x9bd48d)
                compass.refresh(guide.copy(tracked = GuideTarget.CITY))
                bar.name() shouldBe CompassStrip.render(180f, emptyList())
                player.activeBossBars().count() shouldBe 2
            }
            player.activeBossBars().single() shouldBe other
        }
    }

    "a nearby target above or below the player uses the dungeon's vertical markers" {
        MockBukkitTestRuntime.open().use { paper ->
            val player = paper.addPlayer("Climber")
            ApocalypseCompass(player).use { compass ->
                listOf(56.0 to "↑", 72.0 to "↓", 64.0 to "◇").forEach { (height, glyph) ->
                    player.teleport(Location(player.world, 12.5, height, 0.5, -45f, 0f))
                    compass.refresh(guide)
                    val color = if (glyph == "◇") 0x9bd48d else 0xffcf70
                    player.activeBossBars().single().name() shouldBe CompassStrip.render(-45f, -45.0, glyph, color)
                }
            }
        }
    }
})
