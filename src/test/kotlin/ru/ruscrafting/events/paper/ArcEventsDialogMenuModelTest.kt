package ru.ruscrafting.events.paper

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import net.kyori.adventure.text.Component
import org.bukkit.entity.Player
import ru.arc.paper.menu.PaperDialogRuntime
import ru.arc.paper.menu.PaperDialogScreen
import ru.ruscrafting.events.config.ArcEventsConfig
import ru.ruscrafting.events.config.ArcEventsLocale
import java.nio.file.Files
import java.util.UUID

class ArcEventsDialogMenuModelTest : StringSpec({
    "native entry models expose purpose, footer and loading state" {
        val root = Files.createTempDirectory("arcevents-dialog-model-")
        try {
            copyResource("config.yml", root.resolve("config.yml"), replace = "dialogs-enabled: false", with = "dialogs-enabled: true")
            Files.createDirectories(root.resolve("lang"))
            copyResource("lang/en.yml", root.resolve("lang/en.yml"))
            copyResource("lang/ru.yml", root.resolve("lang/ru.yml"))
            val settings = ArcEventsConfig.load(root)
            val locale = ArcEventsLocale(root) { settings }
            val player = mockk<Player>(relaxed = true)
            every { player.uniqueId } returns UUID.randomUUID()
            every { player.hasPermission(any<String>()) } returns true
            val service = mockk<ArcEventsService>(relaxed = true)
            every { service.snapshot() } returns ServiceSnapshot("test", ru.ruscrafting.events.config.NodeMode.HOST, "test", true, true, true, null, 0, null, null, 0, 0, 0, 0, 0, 0)
            val displayed = mutableListOf<PaperDialogScreen>()
            val menu = ArcEventsDialogMenu(mockk<PaperDialogRuntime>(), service, locale, { settings }, { _, _, _ -> }, ClientProtocolResolver { 771 }, { false }) { _, screen, _, _, _ -> displayed += screen }

            menu.open(player, EventsView.Main) shouldBe true
            menu.open(player, EventsView.Help) shouldBe true
            menu.open(player, EventsView.Statistics) shouldBe true
            menu.openTttLoading(player, {}, {}) shouldBe true
            displayed.size shouldBe 4
            displayed.forEach { (it.exitButton != null) shouldBe true }
            displayed.map { it.id } shouldBe listOf("events.main", "events.help", "events.stats", "events.ttt")
            displayed.first().body.isNotEmpty() shouldBe true
            displayed.last().body.first().text.toString().lowercase().let { "loading" in it || "загружаем" in it } shouldBe true
        } finally {
            root.toFile().deleteRecursively()
        }
    }
})

private fun copyResource(name: String, target: java.nio.file.Path, replace: String? = null, with: String? = null) {
    val source = ArcEventsDialogMenuModelTest::class.java.classLoader.getResourceAsStream(name)!!.use { it.readBytes().decodeToString() }
    Files.writeString(target, if (replace != null && with != null) source.replace(replace, with) else source)
}
