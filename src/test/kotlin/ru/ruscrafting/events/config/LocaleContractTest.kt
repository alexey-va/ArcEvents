package ru.ruscrafting.events.config

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.shouldBe
import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.Yaml
import ru.arc.config.Config
import java.nio.file.Files
import java.nio.file.Path

class LocaleContractTest : StringSpec({
    "only arcevents is registered and advertised as the event command" {
        val root = Files.createTempDirectory("arcevents-command-")
        try {
            val descriptor = Config(root, "plugin.yml")
            descriptor.keys("commands").toSet() shouldBe setOf("arcevents")
            descriptor.stringList("commands.arcevents.aliases").isEmpty() shouldBe true
            listOf("ru", "en").forEach { language ->
                val locale = Config(root, "lang/$language.yml")
                locale.string("command.help").contains("/arcevents") shouldBe true
                Regex("""/(?:ae|events)\b""").containsMatchIn(Files.readString(root.resolve("lang/$language.yml"))) shouldBe false
            }
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    "Russian and English catalogs have identical nonblank leaves and parameters" {
        val root = Files.createTempDirectory("arcevents-locale-")
        try {
            Config(root, "lang/ru.yml")
            Config(root, "lang/en.yml")
            val ru = catalog(root.resolve("lang/ru.yml"))
            val en = catalog(root.resolve("lang/en.yml"))
            ru.keys shouldBe en.keys
            ru.keys.shouldContainAll(ArcEventsLocale.REQUIRED_SCALARS + ArcEventsLocale.REQUIRED_LISTS)
            ru.forEach { (path, lines) -> parameters(lines.joinToString("\n")) shouldBe parameters(en.getValue(path).joinToString("\n")) }
            ArcEventsLocale.validateFiles(root)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    "disaster HUD uses the configured wave count independently of GunGame weapon count" {
        val root = Files.createTempDirectory("arcevents-wave-hud-")
        try {
            listOf("ru", "en").forEach { language ->
                val hud = Config(root, "lang/$language.yml").string("arcade.disasters-hud")
                hud.contains("<round>/<rounds>") shouldBe true
                hud.contains("<total>") shouldBe false
            }
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    "creative firearm feedback exposes unlimited ammunition without reload guidance" {
        val root = Files.createTempDirectory("arcevents-creative-ammo-")
        try {
            listOf("ru", "en").forEach { language ->
                val text = Config(root, "lang/$language.yml").string("weapon.creative-ammo-actionbar")
                text.contains("<weapon>") shouldBe true
                text.contains("∞") shouldBe true
                listOf("<loaded>", "<magazine>", "<reserve>", "F ").any(text::contains) shouldBe false
            }
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    "GUI text avoids italics shouting and repeated chat prefixes" {
        val root = Files.createTempDirectory("arcevents-style-")
        try {
            listOf("ru", "en").forEach { language ->
                val config = Config(root, "lang/$language.yml")
                val text = catalog(root.resolve("lang/$language.yml"))
                text.values.flatten().forEach { line ->
                    line.contains("<italic>") shouldBe false
                    line.contains("&lt;") shouldBe false
                    line.contains("&gt;") shouldBe false
                    Regex("[А-ЯA-Z]{8,}").containsMatchIn(visible(line)) shouldBe false
                    (Regex("<prefix>").findAll(line).count() <= 1) shouldBe true
                }
                text.filterKeys { it.startsWith("dialog.") || it.startsWith("hud.") || "actionbar" in it }
                    .values.flatten().none { "<prefix>" in it } shouldBe true
                text.filterKeys { it.startsWith("menu.") && it.endsWith(".title") }.values.flatten()
                    .all { visible(it).length in 1..36 } shouldBe true
                config.string("prefix") shouldBe "<white></white>"
            }
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    "preparation points to optional rules and keeps controls compact" {
        val root = Files.createTempDirectory("arcevents-preparation-style-")
        try {
            listOf("ru", "en").forEach { language ->
                val config = Config(root, "lang/$language.yml")
                val guide = config.string("match.preparing-guide")
                authoredLines(guide).size shouldBe 1
                visible(guide).trim() shouldBe visible(guide)
                (visible(guide).length <= 64) shouldBe true
                guide.contains("/arcevents") shouldBe true
                guide.contains("<prefix>") shouldBe false
                val tips = config.stringList("hud.preparing-tips")
                (tips.size in 1..2) shouldBe true
                tips.all { visible(it).length <= 80 && "<bold>" !in it && "<prefix>" !in it } shouldBe true
                val controls = tips.joinToString(" ")
                controls.contains(if (language == "ru") "ЛКМ" else "LMB") shouldBe true
                controls.contains(if (language == "ru") "ПКМ" else "RMB") shouldBe true
                controls.contains("F") shouldBe true
                listOf("hud.bossbar-preparing", "hud.countdown-actionbar").forEach { path ->
                    val line = config.string(path)
                    authoredLines(line).size shouldBe 1
                    line.contains("<time>") shouldBe true
                    line.contains("<bold>") shouldBe false
                    line.contains("<prefix>") shouldBe false
                }
            }
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    "inventory actions explain the outcome without a repeated click footer" {
        val root = Files.createTempDirectory("arcevents-action-copy-")
        try {
            listOf("ru", "en").forEach { language ->
                val config = Config(root, "lang/$language.yml")
                listOf(
                    "menu.admin.arenas-lore", "menu.admin.start-lore", "menu.admin.stop-lore",
                    "menu.admin.reload-lore", "menu.admin.recover-lore", "menu.arenas.entry-lore",
                    "menu.arenas.auto-lore", "menu.body.dna-lore", "menu.body.call-lore",
                    "menu.body.roster-lore", "menu.report.combat-lore", "menu.common.previous-lore",
                    "menu.common.next-lore", "arcade.gungame-lore", "arcade.disasters-lore", "arcade.join-lore",
                    "arcade.leave-lore", "arcade.start-lore", "arcade.fishing-lore", "arcade.fishing-start-lore",
                ).forEach { path ->
                    val lines = config.stringList(path).map(::visible).filter(String::isNotBlank)
                    (lines.size in 1..3) shouldBe true
                    lines.all { it.length <= 88 } shouldBe true
                    lines.none { "▶" in it || Regex("(?i)^(нажмите|click)\\b").containsMatchIn(it) } shouldBe true
                    config.string(path.removeSuffix("-lore") + "-name").isNotBlank() shouldBe true
                }
            }
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    "mode briefings use at most two lines and detailed rules remain separate" {
        val root = Files.createTempDirectory("arcevents-guidance-")
        try {
            listOf("ru", "en").forEach { language ->
                val config = Config(root, "lang/$language.yml")
                listOf("gungame", "disasters", "fishing", "zombies").forEach { mode ->
                    val briefing = config.string("arcade.$mode-briefing")
                    val lines = authoredLines(briefing)
                    (lines.size in 1..2) shouldBe true
                    lines.all { it.isNotBlank() && visible(it).length <= 80 } shouldBe true
                    briefing.contains("<prefix>") shouldBe false
                    Regex("(?i)\\bHUD\\b").containsMatchIn(briefing) shouldBe false
                }
                listOf("ttt-0", "ttt-1", "gungame-0", "disasters-0", "fishing-0", "zombies-0", "zombies-1").forEach { page ->
                    val rules = config.stringList("dialog.rules-$page")
                    (rules.size in 1..12) shouldBe true
                    (rules.sumOf { visible(it).length } <= 700) shouldBe true
                    rules.any(String::isNotBlank) shouldBe true
                }
                listOf("dialog.rules-name", "dialog.rules-next", "dialog.rules-previous").forEach { path ->
                    config.string(path).isNotBlank() shouldBe true
                }
                val controls = config.stringList("dialog.rules-ttt-1").joinToString(" ")
                controls.contains("/arcevents team") shouldBe true
                listOf("arcade.gungame-briefing", "arcade.zombies-briefing").forEach { path ->
                    val line = config.string(path)
                    line.contains(if (language == "ru") "ЛКМ" else "LMB") shouldBe true
                    line.contains(if (language == "ru") "ПКМ" else "RMB") shouldBe true
                    line.contains("F") shouldBe true
                }
                parameters(config.stringList("dialog.rules-disasters-0").joinToString(" ")) shouldBe setOf("rounds")
                parameters(config.stringList("dialog.rules-zombies-0").joinToString(" ")) shouldBe setOf("waves", "minimum", "maximum")
            }
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    "fishing copy exposes the solo expedition flow and result states" {
        val root = Files.createTempDirectory("arcevents-fishing-copy-")
        try {
            listOf("ru", "en").forEach { language ->
                val config = Config(root, "lang/$language.yml")
                val guide = config.stringList("arcade.fishing-guide").joinToString(" ")
                val entry = config.stringList("arcade.fishing-lore").joinToString(" ")
                val facts = config.string("dialog.fishing-facts")
                facts.contains("<minutes>") shouldBe true
                facts.contains("<queue>") shouldBe false
                entry.contains(if (language == "ru") "одиноч" else "solo", ignoreCase = true) shouldBe true
                guide.contains(if (language == "ru") "ПКМ" else "RMB") shouldBe true
                guide.contains(if (language == "ru") "босс" else "boss", ignoreCase = true) shouldBe true
                guide.contains(if (language == "ru") "трофе" else "troph", ignoreCase = true) shouldBe true
                listOf("success", "timeout", "defeat").map { visible(config.string("fishing.result-$it")) }.distinct().size shouldBe 3
                config.string("fishing.result-success").isNotBlank() shouldBe true
                config.string("fishing.result-timeout").isNotBlank() shouldBe true
                config.string("fishing.result-defeat").isNotBlank() shouldBe true
                config.string("fishing.rod-name").isNotBlank() shouldBe true
                config.stringList("fishing.rod-lore").isNotEmpty() shouldBe true
                config.string("fishing.weapon-0-name").isNotBlank() shouldBe true
                config.string("fishing.weapon-1-name").isNotBlank() shouldBe true
                config.string("fishing.weapon-2-name").isNotBlank() shouldBe true
                config.stringList("fishing.weapon-lore").isNotEmpty() shouldBe true
                listOf(
                    "start", "catch", "complete", "travel-unlocked", "creature-defeated", "creature-hit", "hud",
                    "creature-name", "boss-name", "boss-summoned", "creature-summoned", "creature-missing",
                    "boss-warning", "travel-started", "travel-marker", "invalid-catch", "encounter-restored", "telegraph",
                ).forEach { key -> config.string("fishing.$key").isNotBlank() shouldBe true }
                listOf("casting", "bite", "creature", "travel", "boss", "trophy", "complete", "closed").forEach { phase ->
                    config.string("fishing.phase.$phase").isNotBlank() shouldBe true
                }
                config.string("fishing.catch").contains("<catches>") shouldBe true
                val hud = config.string("fishing.hud")
                parameters(hud) shouldBe setOf("stage", "total", "catches", "needed", "coins")
                authoredLines(hud).size shouldBe 1
                (visible(hud).length <= 88) shouldBe true
                ru.ruscrafting.events.domain.FishingIsland.entries.forEach { island ->
                    (island.catchSpecies + island.bossSpecies).forEach { species ->
                        config.string("fishing.species.$species").isNotBlank() shouldBe true
                    }
                }
                config.string("fishing.travel-started").contains("<stage>") shouldBe true
                config.string("fishing.telegraph").contains("<radius>") shouldBe true
            }
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    "round chat has no square badges or bold decoration" {
        val root = Files.createTempDirectory("arcevents-chat-style-")
        try {
            listOf("ru", "en").forEach { language ->
                val config = Config(root, "lang/$language.yml")
                listOf("team.message", "chat.self-message", "chat.match-message", "chat.spectator-message").forEach { path ->
                    val message = config.string(path)
                    message.contains('[') shouldBe false
                    message.contains(']') shouldBe false
                    message.contains("<bold>") shouldBe false
                    message.count { it == '›' } shouldBe 1
                }
            }
        } finally {
            root.toFile().deleteRecursively()
        }
    }
}) {
    companion object {
        private val formattingTags = setOf(
            "black", "dark_blue", "dark_green", "dark_aqua", "dark_red", "dark_purple", "gold", "gray", "dark_gray",
            "blue", "green", "aqua", "red", "light_purple", "yellow", "white", "bold", "b", "italic", "i", "em",
            "underlined", "u", "strikethrough", "st", "obfuscated", "obf", "reset", "r", "newline", "br",
        )

        private fun parameters(text: String): Set<String> = Regex("<([a-z][a-z0-9_-]*)>")
            .findAll(text).map { it.groupValues[1] }.filterNot { it in formattingTags }.toSet()

        private fun visible(text: String): String = Regex("<[^>]+>").replace(text, "")

        private fun authoredLines(text: String): List<String> = text.replace("<newline>", "\n").replace("<br>", "\n").lines()

        private fun catalog(path: Path): Map<String, List<String>> {
            val yaml = Yaml(LoaderOptions().also { it.setAllowDuplicateKeys(false) })
            val root = Files.newBufferedReader(path).use { yaml.load<Map<String, Any?>>(it) }
            val result = linkedMapOf<String, List<String>>()
            fun visit(path: String, value: Any?) {
                when (value) {
                    is Map<*, *> -> value.forEach { (key, child) -> visit(if (path.isEmpty()) key.toString() else "$path.$key", child) }
                    is String -> {
                        require(value.isNotBlank()) { "Blank locale leaf $path" }
                        result[path] = listOf(value)
                    }
                    is List<*> -> {
                        require(value.isNotEmpty() && value.all { it is String } && value.any { (it as String).isNotBlank() }) {
                            "Empty or non-text locale list $path"
                        }
                        result[path] = value.map { it as String }
                    }
                    else -> error("Non-text locale leaf $path")
                }
            }
            visit("", root)
            return result
        }
    }
}
