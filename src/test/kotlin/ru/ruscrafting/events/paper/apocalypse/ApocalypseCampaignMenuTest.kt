package ru.ruscrafting.events.paper.apocalypse

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.mockk.every
import io.mockk.mockk
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.event.ClickEvent
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.entity.Player
import ru.arc.paper.menu.PaperDialogClickContext
import ru.arc.paper.menu.PaperDialogRuntime
import ru.arc.paper.menu.PaperDialogScreen
import ru.ruscrafting.events.config.ArcEventsConfig
import ru.ruscrafting.events.config.ArcEventsLocale
import java.nio.file.Files

class ApocalypseCampaignMenuTest : StringSpec({
    "friend entry, owner forms and classic links preserve their target and current authority" {
        val root = Files.createTempDirectory("arcevents-campaign-menu-")
        try {
            val config = ArcEventsConfig.load(root)
            val locale = ArcEventsLocale(root) { config }
            val player = mockk<Player>(relaxed = true)
            var play = true
            every { player.isOnline } returns true
            every { player.hasPermission("arcevents.play") } answers { play }
            val messages = mutableListOf<Component>()
            every { player.sendMessage(any<Component>()) } answers { messages += firstArg<Component>() }
            val dialogs = mockk<PaperDialogRuntime>(relaxed = true)
            lateinit var shown: PaperDialogScreen
            every { dialogs.open(player, any(), any(), any(), any()) } answers { shown = secondArg() }
            val click = mockk<PaperDialogClickContext>()
            every { click.player } returns player
            var input = ""
            every { click.text(any()) } answers { input }
            var native = true
            var owner = false
            var known = emptyList<String>()
            val commands = mutableListOf<List<String>>()
            val menu = ApocalypseCampaignMenu(dialogs, locale, { native }, { false }, { owner }, { known },
                { _, args -> commands += args })
            fun choose(id: String) = shown.buttons.single { it.id.value == id }.onClick.handle(click)
            val plain = PlainTextComponentSerializer.plainText()

            menu.openFriends(player)
            shown.buttons.map { it.id.value } shouldBe listOf("join")
            shown.inputs.single().id.value shouldBe "player"
            input = "Two Players"
            choose("join")
            shown.id shouldBe "events.apocalypse.campaign.friends"
            shown.inputs.single().initial shouldBe input
            plain.serialize(shown.body.single().text) shouldContain "Проверьте ник"
            commands.isEmpty() shouldBe true
            input = ".Player1234567890"
            input.length shouldBe shown.inputs.single().maxLength
            choose("join")
            commands.single() shouldBe listOf("apocalypse", "join", input)

            known = listOf("Fox", "Bear", "Marten", "Lynx", "Owl", "fox")
            menu.openFriends(player)
            input = "Guest"
            choose("next")
            shown.inputs.single().initial shouldBe input
            choose("friend_0")
            commands.last() shouldBe listOf("apocalypse", "join", "Owl")

            menu.openManage(player)
            plain.serialize(messages.last()) shouldContain "своей экспедиции"
            owner = true
            for (action in listOf("invite", "companion", "revoke")) {
                menu.openManage(player)
                choose(action)
                shown.id shouldBe "events.apocalypse.campaign.members.$action"
                choose("submit")
                commands.last() shouldBe listOf("apocalypse", action, "Guest")
            }
            menu.openManage(player)
            choose("companion")
            val writes = commands.size
            owner = false
            choose("submit")
            commands.size shouldBe writes
            menu.openFriends(player)
            play = false
            choose("join")
            commands.size shouldBe writes
            play = true

            native = false
            owner = true
            menu.openEntryFallback(player)
            fun links(component: Component): List<ClickEvent> =
                listOfNotNull(component.clickEvent()) + component.children().flatMap(::links)
            links(messages.last()) shouldBe listOf(ClickEvent.runCommand("/arcevents apocalypse common"),
                ClickEvent.runCommand("/arcevents apocalypse my"), ClickEvent.suggestCommand("/arcevents apocalypse join "))
            menu.openManage(player)
            links(messages.last()) shouldBe listOf("invite", "companion", "revoke").map {
                ClickEvent.suggestCommand("/arcevents apocalypse $it ")
            }
        } finally { root.toFile().deleteRecursively() }
    }
})
