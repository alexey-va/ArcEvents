package ru.ruscrafting.events.config

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.shouldBe
import ru.arc.config.Config
import java.nio.file.Files

class LocaleContractTest : StringSpec({
    "Russian and English catalogs have identical nonblank leaves" {
        val root = Files.createTempDirectory("arcevents-locale-")
        try {
            val ru = Config(root, "lang/ru.yml")
            val en = Config(root, "lang/en.yml")
            val ruLeaves = leaves(ru)
            val enLeaves = leaves(en)
            ruLeaves shouldBe enLeaves
            ruLeaves.shouldContainAll(ArcEventsLocale.REQUIRED_SCALARS + ArcEventsLocale.REQUIRED_LISTS)
            ArcEventsLocale.validateFiles(root)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    "catalog does not opt GUI text into italics or shouting caps" {
        val root = Files.createTempDirectory("arcevents-style-")
        try {
            Config(root, "lang/ru.yml")
            Config(root, "lang/en.yml")
            listOf("ru", "en").forEach { language ->
                val raw = Files.readString(root.resolve("lang/$language.yml"))
                raw.contains("<italic>") shouldBe false
                raw.contains("&lt;") shouldBe false
                raw.contains("&gt;") shouldBe false
                raw.contains('•') shouldBe false
                raw.count { it == '·' } shouldBe 1
                Regex("[А-ЯA-Z]{8,}").containsMatchIn(raw) shouldBe false
                Regex("(?m)^    title: '<#20252b>").findAll(raw).count() shouldBe 11
                Regex("(?m)^  [a-z-]*actionbar: '.*<prefix>").containsMatchIn(raw) shouldBe false
                Regex("(?m)^  reserved: '.*parkour").containsMatchIn(raw) shouldBe false
                Regex("(?i)(we will move|transfer has begun|вас перенесут|перенос на арену)").containsMatchIn(raw) shouldBe false
            }
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    "preparation guidance has one quiet leading break and no decorative bullets or bold text" {
        val root = Files.createTempDirectory("arcevents-preparation-style-")
        try {
            listOf("ru", "en").forEach { language ->
                val config = Config(root, "lang/$language.yml")
                val guide = config.string("match.preparing-guide")
                guide.startsWith("\n<prefix> ") shouldBe true
                guide.endsWith('\n') shouldBe false
                guide.contains("<bold>") shouldBe false
                guide.contains('◆') shouldBe false
                config.stringList("hud.preparing-tips").none { "<bold>" in it } shouldBe true
                config.string("hud.bossbar-preparing").contains("<bold>") shouldBe false
                config.string("hud.countdown-actionbar").contains("<bold>") shouldBe false
            }
        } finally {
            root.toFile().deleteRecursively()
        }
    }
}) {
    companion object {
        private val roots = listOf(
            "prefix", "command", "reason", "menu", "arena", "state", "phase", "queue", "match", "role",
            "loadout", "body", "weapon", "roster", "report", "shop", "team", "chat", "admin", "debug", "hud", "guide",
            "nameplate",
        )

        private fun leaves(config: Config): Set<String> = roots.flatMap { root -> collect(config, root) }.toSet()

        private fun collect(config: Config, path: String): Set<String> {
            val children = config.keys(path)
            if (children.isEmpty()) {
                val scalar = config.stringOrNull(path)
                val list = config.stringListOrNull(path)
                require(scalar?.isNotBlank() == true || list?.isNotEmpty() == true) { "Blank locale leaf $path" }
                return setOf(path)
            }
            return children.flatMap { child -> collect(config, "$path.$child") }.toSet()
        }
    }
}
