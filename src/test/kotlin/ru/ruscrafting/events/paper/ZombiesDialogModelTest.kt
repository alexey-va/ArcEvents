package ru.ruscrafting.events.paper

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.entity.Player
import ru.arc.paper.menu.PaperDialogRuntime
import ru.arc.paper.menu.PaperDialogScreen
import ru.ruscrafting.events.config.ArcEventsConfig
import ru.ruscrafting.events.config.ArcEventsLocale
import ru.ruscrafting.events.config.NodeMode
import ru.ruscrafting.events.domain.EventMode
import ru.ruscrafting.events.network.QueueState
import java.nio.file.Files
import java.util.Locale
import java.util.UUID

class ZombiesDialogModelTest : StringSpec({
    "native zombies guide has localized rules and context-correct join start leave controls" {
        val root = Files.createTempDirectory("zombies-dialog-")
        try {
            Files.writeString(root.resolve("config.yml"), "locale:\n  default: ru\n  use-client-locale: true\nui:\n  dialogs-enabled: true\n")
            val settings = ArcEventsConfig.load(root)
            val locale = ArcEventsLocale(root) { settings }
            val player = mockk<Player>(relaxed = true)
            every { player.uniqueId } returns UUID.randomUUID()
            val service = mockk<ArcEventsService>(relaxed = true)
            every { service.snapshot() } returns ServiceSnapshot("test", NodeMode.HOST, "test", true, true, true, null, 1, null, null, 0, 0, 0, 0, 0, 0)
            val displayed = mutableListOf<PaperDialogScreen>()
            val menu = ArcEventsDialogMenu(mockk<PaperDialogRuntime>(), service, locale, { settings }, { _, _, _ -> },
                ClientProtocolResolver { MIN_DIALOG_PROTOCOL }, { false }) { _, screen, _, _, _ -> displayed += screen }
            val plain = PlainTextComponentSerializer.plainText()
            for (language in listOf(Locale.forLanguageTag("ru"), Locale.US)) {
                every { player.locale() } returns language
                menu.openZombies(player, null, false, false, true, false, 22, 24, {}) shouldBe true
                val entry = displayed.last()
                entry.id shouldBe "events.zombies"
                entry.buttons.map { it.id.value } shouldBe listOf("action_arcade_zombies_solo_name", "action_arcade_zombies_team_name", "rules_zombies")
                val text = entry.body.joinToString("\n") { plain.serialize(it.text) }
                listOf("<waves>", "<minimum>", "<maximum>", "<queue_state>", "arcade.zombies").any(text::contains) shouldBe false
                text.contains("10") shouldBe true
                text.contains("1–4") shouldBe true
                menu.openZombies(player, QueueState.QUEUED, true, false, true, false, 22, 24, {}) shouldBe true
                displayed.last().buttons.map { it.id.value } shouldBe listOf("action_arcade_start_name", "action_arcade_leave_name", "rules_zombies")
                menu.openZombies(player, null, false, true, true, false, 22, 24, {}) shouldBe true
                displayed.last().buttons.map { it.id.value } shouldBe listOf("action_menu_event_evacuate_name", "rules_zombies")
                menu.openZombies(player, null, false, false, false, true, 22, 24, {}) shouldBe true
                displayed.last().buttons.single().id.value shouldBe "rules_zombies"
            }
            dialogFrontendSupported(true, MIN_DIALOG_PROTOCOL - 1, EventsView.Arcade(EventMode.ZOMBIES)) shouldBe false
            dialogFrontendSupported(false, MIN_DIALOG_PROTOCOL, EventsView.Arcade(EventMode.ZOMBIES)) shouldBe false
        } finally { root.toFile().deleteRecursively() }
    }
})
