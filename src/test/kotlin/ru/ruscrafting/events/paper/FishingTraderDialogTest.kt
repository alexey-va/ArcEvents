package ru.ruscrafting.events.paper

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.mockk.every
import io.mockk.mockk
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.TextDecoration
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.entity.Player
import ru.arc.paper.menu.PaperDialogRuntime
import ru.ruscrafting.events.config.ArcEventsConfig
import ru.ruscrafting.events.config.ArcEventsLocale
import ru.ruscrafting.events.domain.FishingProgress
import java.nio.file.Files
import java.util.Locale

class FishingTraderDialogTest : StringSpec({
    "trader and armory render both locales with explicit locked weapons and stable native identities" {
        val root = Files.createTempDirectory("fishing-trader-")
        try {
            val settings = ArcEventsConfig.load(root)
            val locale = ArcEventsLocale(root) { settings }
            val player = mockk<Player>(relaxed = true)
            every { player.health } returns 20.0
            every { player.foodLevel } returns 20
            val menu = FishingTraderDialog(mockk<PaperDialogRuntime>(), mockk<ArcEventsService>(), locale, { settings }, { false })
            for (language in listOf(Locale.forLanguageTag("ru"), Locale.US)) {
                every { player.locale() } returns language
                val state = FishingProgress().copy(coins = 300)
                val trader = menu.model(player, state, false) {}
                val armory = menu.model(player, state, true) {}
                trader.id shouldBe "events.fishing.trader"
                armory.id shouldBe "events.fishing.armory"
                trader.columns shouldBe 2
                trader.buttons.size shouldBe 8
                armory.buttons.size shouldBe 8
                listOf(trader, armory).forEach { screen ->
                    (screen.exitButton != null) shouldBe true
                    screen.buttons.map { it.id }.distinct().size shouldBe screen.buttons.size
                    (screen.buttons.any { it.closeDialogBeforeAction }) shouldBe false
                    (screen.body + ru.arc.paper.menu.PaperDialogBody(screen.title)).forEach { body ->
                        body.text.decoration(TextDecoration.ITALIC) shouldBe TextDecoration.State.FALSE
                        plain(body.text).contains("fishing.trader.") shouldBe false
                        plain(body.text).contains("<coins>") shouldBe false
                    }
                }
                plain(armory.buttons.first { it.id.value == "equip_knuckles" }.label) shouldContain "✔"
                plain(armory.buttons.first { it.id.value == "buy_pistol" }.label) shouldContain
                    if (language.language == "ru") "Недоступно" else "Unavailable"
                plain(trader.body[1].text) shouldContain "300"
                plain(trader.body[3].text) shouldContain
                    if (language.language == "ru") "только в этом заходе" else "expedition only"
            }
        } finally { root.toFile().deleteRecursively() }
    }
})

private fun plain(component: Component): String = PlainTextComponentSerializer.plainText().serialize(component)
