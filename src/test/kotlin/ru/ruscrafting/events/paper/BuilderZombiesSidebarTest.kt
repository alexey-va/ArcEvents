package ru.ruscrafting.events.paper

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.entity.Player
import ru.arc.paper.api.ArcSidebarFrame
import ru.arc.paper.api.ArcSidebarHandle
import ru.ruscrafting.events.config.ArcEventsConfig
import ru.ruscrafting.events.config.ArcEventsLocale
import java.nio.file.Files
import java.util.Locale
import java.util.UUID

class BuilderZombiesSidebarTest : StringSpec({
    "both locales show commands for the viewer's permission, readiness and participation" {
        val root = Files.createTempDirectory("builder-zombies-sidebar-")
        try {
            Files.writeString(root.resolve("config.yml"), "locale:\n  use-client-locale: true\n")
            val settings = ArcEventsConfig.load(root)
            val player = mockk<Player>()
            val handle = mockk<ArcSidebarHandle>(relaxed = true)
            val frame = slot<ArcSidebarFrame>()
            every { handle.show(player, capture(frame)) } returns Unit
            val guide = BuilderZombiesSidebar(handle, ArcEventsLocale(root) { settings })
            val plain = PlainTextComponentSerializer.plainText()
            fun rows(admin: Boolean, ready: Boolean = false, playing: Boolean = false): List<String> {
                every { player.hasPermission("arcevents.admin") } returns admin
                guide.update(player, ready, playing)
                return frame.captured.rows.map(plain::serialize)
            }

            for (language in listOf(Locale.forLanguageTag("ru"), Locale.US)) {
                every { player.locale() } returns language
                val russian = language.language == "ru"
                val name = if (russian) "ник" else "name"
                val join = rows(admin = false)
                plain.serialize(frame.captured.title) shouldBe if (russian) "Тренировка «Зомби»" else "Zombies practice"
                join.filter { it.startsWith("/") } shouldBe listOf("/events zombies ready")
                join.size shouldBe 3
                val ready = rows(admin = false, ready = true)
                ready.last() shouldBe "/events zombies ready"
                ready[1] shouldBe if (russian) "Снять готовность" else "To cancel readiness"
                val leave = rows(admin = false, ready = true, playing = true)
                leave shouldBe listOf(if (russian) "Выйти из тренировки" else "To leave practice", "/events leave")

                val start = rows(admin = true)
                start.first() shouldBe "/events admin zombies"
                start.filter { it.trimStart().startsWith("start ") }.map { it.substringBefore(" ·").trim() } shouldBe
                    listOf("start easy", "start easy $name")
                val modes = start.dropWhile { it != (if (russian) "Сложность" else "Difficulty") }.drop(1).take(4)
                modes.map { it.trim().substringBefore(' ') } shouldBe listOf("easy", "hard", "hardcore", "insane")
                start.takeLast(join.size) shouldBe join
                rows(admin = true, ready = true).takeLast(ready.size) shouldBe ready

                val running = rows(admin = true, playing = true)
                running.first() shouldBe "/events admin zombies"
                running.filter { it.startsWith("  ") }.map { it.trim().substringBefore(' ') } shouldBe
                    listOf("status", "wave", "spawn", "airdrop", "grant", "gear", "stop")
                running.takeLast(leave.size) shouldBe leave
                frame.captured.hiddenNameEntries shouldBe emptySet()
            }
            val id = UUID.randomUUID()
            guide.hide(id)
            verify(exactly = 1) { handle.hide(id) }
        } finally {
            root.toFile().deleteRecursively()
        }
    }
})
