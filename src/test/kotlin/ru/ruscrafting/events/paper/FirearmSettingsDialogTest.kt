package ru.ruscrafting.events.paper

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.bukkit.Material
import org.bukkit.inventory.ItemStack
import ru.arc.config.ConfigManager
import ru.arc.paper.menu.*
import ru.arc.paper.testing.MockBukkitTestRuntime
import ru.ruscrafting.events.config.ArcEventsConfig
import ru.ruscrafting.events.config.ArcEventsLocale
import ru.ruscrafting.events.domain.FirearmId
import java.nio.file.Files

class FirearmSettingsDialogTest : StringSpec({
    "player burst and admin tuning are separate and the captured item and permission are rechecked on save" {
        MockBukkitTestRuntime.open().use { paper ->
            val data = Files.createTempDirectory("arcevents-fire-settings-")
            try {
                ConfigManager.clear()
                for (name in listOf("config.yml", "lang/ru.yml", "lang/en.yml")) {
                    val file = data.resolve(name)
                    Files.createDirectories(file.parent)
                    javaClass.getResourceAsStream("/$name")!!.use { Files.copy(it, file) }
                }
                val plugin = paper.createSimplePlugin("WeaponSettings")
                val player = paper.addPlayer("Gunner")
                val grant = player.addAttachment(plugin, "arcevents.admin", false)
                val config = ArcEventsConfig.load(data)
                val locale = ArcEventsLocale(data) { config }
                val guns = TttFirearms(plugin, locale) { config }
                val base = guns.spec(FirearmId.RPL_20)
                val original = guns.firearmItem(base.id, player, "round", 42)
                player.inventory.setItemInMainHand(original)
                val runtime = mockk<PaperDialogRuntime>(relaxed = true)
                lateinit var screen: PaperDialogScreen
                every { runtime.open(player, any(), any(), any(), any()) } answers { screen = secondArg() }
                var allowed = true
                val settings = FirearmSettingsDialog(runtime, locale, guns, { base.takeIf { allowed } }, {}, { true }, { false })
                val click = mockk<PaperDialogClickContext>()
                every { click.player } returns player
                every { click.number(PaperDialogInputId.of("rounds")) } returns 12f
                every { click.text(PaperDialogInputId.of("damage")) } returns "7,25"
                every { click.text(PaperDialogInputId.of("spread")) } returns "0.5"
                fun press(id: String) = screen.buttons.single { it.id.value == id }.onClick.handle(click)
                fun held() = player.inventory.itemInMainHand

                settings.open(player, false, emptyList(), false) shouldBe true
                screen.buttons.map { it.id.value } shouldBe listOf("save", "full")
                screen.numberInputs.single().end shouldBe 48f
                press("save")
                guns.selectedBurst(held(), base) shouldBe 12
                guns.tunedSpec(held(), base) shouldBe base
                settings.open(player, true, listOf("35", "0"), false)
                guns.tunedSpec(held(), base) shouldBe base

                grant.setPermission("arcevents.admin", true)
                settings.open(player, false, emptyList(), false)
                press("tune")
                screen.inputs.map { it.id.value } shouldBe listOf("damage", "spread")
                grant.setPermission("arcevents.admin", false)
                press("save")
                guns.tunedSpec(held(), base) shouldBe base
                grant.setPermission("arcevents.admin", true)
                settings.open(player, true, emptyList(), false)
                allowed = false
                press("save")
                guns.tunedSpec(held(), base) shouldBe base
                allowed = true
                settings.open(player, true, emptyList(), false)
                player.inventory.setItemInMainHand(ItemStack.of(Material.STONE))
                press("save")
                held().type shouldBe Material.STONE

                player.inventory.setItemInMainHand(guns.setBurst(original, base, 12))
                settings.open(player, true, emptyList(), false)
                press("save")
                val restored = guns.refreshItem(guns.updateLoaded(held(), player, 40), player)
                guns.tunedSpec(restored, base) shouldBe base.copy(damagePerPellet = 7.25, spreadDegrees = .5)
                guns.selectedBurst(restored, base) shouldBe 12
                guns.state(restored) shouldBe FirearmState(base.id, 40, "round")
                player.inventory.setItemInMainHand(restored)
                settings.open(player, true, listOf("NaN", "-1"), false)
                held() shouldBe restored
                settings.open(player, true, listOf("default"), false)
                guns.tunedSpec(held(), base) shouldBe base
                guns.selectedBurst(held(), base) shouldBe 12
                guns.tunedSpec(original, base) shouldBe base
            } finally {
                ConfigManager.clear()
                data.toFile().deleteRecursively()
            }
        }
    }
})
