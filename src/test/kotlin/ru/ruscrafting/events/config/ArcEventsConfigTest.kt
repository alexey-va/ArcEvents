package ru.ruscrafting.events.config

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.throwables.shouldNotThrowAny
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import org.opentest4j.TestAbortedException
import ru.ruscrafting.events.domain.FirearmId
import ru.ruscrafting.events.domain.TttFirearmCatalog
import java.nio.file.Files
import java.nio.file.Path

class ArcEventsConfigTest : StringSpec({
    "bundled defaults are a safe relay with a disabled arena" {
        val root = Files.createTempDirectory("arcevents-config-")
        try {
            val config = ArcEventsConfig.load(root)
            config.serverId shouldBe "parkour"
            config.nodeMode shouldBe NodeMode.RELAY
            config.arena.enabled shouldBe false
            config.arena.template shouldBe ""
            config.arena.operational(config.ttt.maximumPlayers) shouldBe false
            config.weapons.enabled shouldBe true
            config.weapons.dnaSeconds shouldBe 90
            config.weapons.visual(FirearmId.MCMILLAN).material shouldBe "NETHERITE_SHOVEL"
            config.weapons.visual(FirearmId.MCMILLAN).customModelData shouldBe 0
            config.weapons.visuals.keys shouldBe FirearmId.entries.toSet()
            config.weapons.specs shouldBe TttFirearmCatalog.specs
            config.weapons.lootEffect.enabled shouldBe false
            config.eventControls shouldBe EventControlSettings(
                creatorControlsEnabled = true,
                creatorArenaSelectionEnabled = true,
                adminOverrideEnabled = true,
            )
            config.gameplay.preparationRations shouldBe 4
            config.gameplay.clearInventory shouldBe true
            config.gameplay.medkitHealing shouldBe 8.0
            config.gameplay.traitorBladeCost shouldBe 2
            config.gameplay.detectiveArmorCost shouldBe 1
            config.ttt.preparationSeconds shouldBe 30
            config.ui.dialogsEnabled shouldBe false
            config.ui.lootDisplays shouldBe true
            config.ui.lootDisplay shouldBe LootDisplaySettings(
                enabled = true,
                height = 0.18,
                scale = 0.78,
                viewRange = 0.75,
                animationStepTicks = 5L,
                rotationTicks = 40,
                particleIntervalTicks = 10,
            )
            config.arenaRuntime shouldBe ArenaRuntimeSettings(
                viewDistance = 6,
                simulationDistance = 4,
                preserveImportedDisplays = true,
                maxImportedDisplays = 256,
            )
            config.ui.nameplates shouldBe NameplateSettings(
                enabled = true,
                reconcilePeriodTicks = 2L,
                maxDistance = 32.0,
                lineWidth = 180,
                viewRange = 0.5,
                scale = 0.95,
                verticalOffset = 0.55,
                shadowed = false,
                background = NameplateBackgroundSettings(80, 11, 15, 18),
                hideInvisibleTargets = true,
                hideSpectatorTargets = true,
                requireLineOfSight = true,
                minimumViewAlignment = 0.5,
                healthPriority = 200,
                summaryPriority = 100,
            )
            config.packetChatIsolationEnabled shouldBe false
            config.localChat shouldBe LocalChatSettings(
                enabled = true,
                closeDistance = 8.0,
                normalDistance = 24.0,
                maximumDistance = 36.0,
            )
            config.ui.back shouldBe UiItemSettings("BLUE_STAINED_GLASS_PANE", 11013)
            config.ui.menuItems["main-ttt-spyglass"] shouldBe UiItemSettings("SPYGLASS", 0)
            config.debug.enabled shouldBe false
            config.debug.allowedServerIds shouldBe setOf("lab")
            config.debugMutationsAllowed shouldBe false
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    "menu materials and model data are runtime configuration" {
        val root = Files.createTempDirectory("arcevents-menu-item-")
        try {
            ArcEventsConfig.load(root)
            val path = root.resolve("config.yml")
            Files.writeString(path, Files.readString(path).replace(
                "main-ttt-spyglass: {material: SPYGLASS}",
                "main-ttt-spyglass: {material: AMETHYST_SHARD, custom-model-data: 731}",
            ))

            ArcEventsConfig.inspect(root).ui.menuItems["main-ttt-spyglass"] shouldBe
                UiItemSettings("AMETHYST_SHARD", 731)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    "live reload separates immediate next-match route-bound and restart-only settings" {
        val root = Files.createTempDirectory("arcevents-reload-")
        try {
            val current = ArcEventsConfig.load(root)
            val original = Files.readString(root.resolve("config.yml"))

            Files.writeString(root.resolve("config.yml"), original.replace("scale: 0.95", "scale: 0.7"))
            val nameplateCandidate = ArcEventsConfig.inspect(root)
            shouldNotThrowAny {
                ArcEventsReloadPolicy.validate(current, nameplateCandidate, matchOrReservationActive = true, activeArenaId = "default")
            }

            Files.writeString(root.resolve("config.yml"), original.replace("round-seconds: 600", "round-seconds: 480"))
            val gameplayCandidate = ArcEventsConfig.inspect(root)
            shouldNotThrowAny {
                ArcEventsReloadPolicy.validate(current, gameplayCandidate, matchOrReservationActive = true, activeArenaId = "default")
            }

            Files.writeString(root.resolve("config.yml"), original.replace("bossbar: true", "bossbar: false"))
            val uiCandidate = ArcEventsConfig.inspect(root)
            shouldNotThrowAny {
                ArcEventsReloadPolicy.validate(current, uiCandidate, matchOrReservationActive = true, activeArenaId = "default")
            }

            Files.writeString(root.resolve("config.yml"), original.replace("heartbeat-seconds: 5", "heartbeat-seconds: 6"))
            val networkCandidate = ArcEventsConfig.inspect(root)
            shouldNotThrowAny {
                ArcEventsReloadPolicy.validate(current, networkCandidate, matchOrReservationActive = true, activeArenaId = "default")
            }

            Files.writeString(root.resolve("config.yml"), original.replace("maximum-distance: 36.0", "maximum-distance: 40.0"))
            val localChatCandidate = ArcEventsConfig.inspect(root)
            shouldNotThrowAny {
                ArcEventsReloadPolicy.validate(current, localChatCandidate, matchOrReservationActive = true, activeArenaId = "default")
            }

            Files.writeString(root.resolve("config.yml"), original.replace("minimum-players: 4", "minimum-players: 5"))
            val capacityCandidate = ArcEventsConfig.inspect(root)
            shouldNotThrowAny {
                ArcEventsReloadPolicy.validate(current, capacityCandidate, matchOrReservationActive = true, activeArenaId = "default")
            }

            Files.writeString(root.resolve("config.yml"), original.replace("return-to-origin: true", "return-to-origin: false"))
            val routeCandidate = ArcEventsConfig.inspect(root)
            withClue("route-bound setting") {
                shouldThrow<IllegalArgumentException> {
                    ArcEventsReloadPolicy.validate(
                        current,
                        routeCandidate,
                        matchOrReservationActive = false,
                    )
                }.message shouldBe "network.return-to-origin requires a restart"
            }

            Files.writeString(root.resolve("config.yml"), original.replace("max-displays: 256", "max-displays: 128"))
            val sanitationCandidate = ArcEventsConfig.inspect(root)
            shouldThrow<IllegalArgumentException> {
                ArcEventsReloadPolicy.validate(current, sanitationCandidate, matchOrReservationActive = false)
            }.message shouldBe "imported decoration sanitation requires a restart"

            Files.writeString(root.resolve("config.yml"), original.replace("    enabled: false\n\nui:", "    enabled: true\n\nui:"))
            val packetIsolationCandidate = ArcEventsConfig.inspect(root)
            shouldThrow<IllegalArgumentException> {
                ArcEventsReloadPolicy.validate(current, packetIsolationCandidate, matchOrReservationActive = false)
            }.message shouldBe "chat.packet-isolation.enabled requires a restart"

            Files.writeString(root.resolve("config.yml"), original.replace("damage-per-pellet: 13.0", "damage-per-pellet: 12.0"))
            val weaponBalanceCandidate = ArcEventsConfig.inspect(root)
            shouldNotThrowAny {
                ArcEventsReloadPolicy.validate(current, weaponBalanceCandidate, matchOrReservationActive = false)
            }
            shouldThrow<IllegalArgumentException> {
                ArcEventsReloadPolicy.validate(
                    current,
                    weaponBalanceCandidate,
                    matchOrReservationActive = true,
                    activeArenaId = "default",
                )
            }.message shouldBe "weapons.catalog can reload only while idle"

            val arenaIdentitySource = if ("  world: pvp" in original) {
                original.replaceFirst("  world: pvp", "  world: event_pvp")
            } else {
                original + "\narena:\n  world: event_pvp\n"
            }
            Files.writeString(root.resolve("config.yml"), arenaIdentitySource)
            val arenaIdentityCandidate = ArcEventsConfig.inspect(root)
            withClue("arena identity") {
                shouldThrow<IllegalArgumentException> {
                    ArcEventsReloadPolicy.validate(current, arenaIdentityCandidate, matchOrReservationActive = false)
                }.message shouldBe "arena ids, worlds, templates, enablement, and bounds require a plugin restart"
            }

            Files.writeString(root.resolve("config.yml"), original.replace("server-id: parkour", "server-id: spawn"))
            val identityCandidate = ArcEventsConfig.inspect(root)
            withClue("server identity") {
                shouldThrow<IllegalArgumentException> {
                    ArcEventsReloadPolicy.validate(current, identityCandidate, matchOrReservationActive = false)
                }.message shouldBe "server-id requires a restart"
            }
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    "loading an older operator config merges new defaults once without replacing overrides" {
        val root = Files.createTempDirectory("arcevents-config-upgrade-")
        try {
            Files.writeString(root.resolve("config.yml"), "locale:\n  default: en\n")

            val upgraded = ArcEventsConfig.load(root)
            val once = Files.readAllBytes(root.resolve("config.yml"))
            upgraded.defaultLocale shouldBe "en"
            upgraded.eventControls.creatorControlsEnabled shouldBe true
            upgraded.ui.lootDisplay.scale shouldBe 0.78
            upgraded.localChat.maximumDistance shouldBe 36.0
            Files.readString(root.resolve("config.yml")).contains("\narena:") shouldBe false
            Files.readString(root.resolve("config.yml")).contains("\narenas:") shouldBe false
            Files.readString(root.resolve("config.yml")).contains("\nweapons:") shouldBe false

            ArcEventsConfig.load(root)
            Files.readAllBytes(root.resolve("config.yml")).toList() shouldBe once.toList()
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    "local chat distances are finite ordered and bounded" {
        val root = Files.createTempDirectory("arcevents-local-chat-")
        try {
            ArcEventsConfig.load(root)
            val original = Files.readString(root.resolve("config.yml"))
            Files.writeString(root.resolve("config.yml"), original.replace("normal-distance: 24.0", "normal-distance: 8.0"))
            shouldThrow<IllegalArgumentException> { ArcEventsConfig.inspect(root) }
                .message shouldBe "chat.local distances must increase from close to normal to maximum"

            Files.writeString(root.resolve("config.yml"), original.replace("maximum-distance: 36.0", "maximum-distance: 129.0"))
            shouldThrow<IllegalArgumentException> { ArcEventsConfig.inspect(root) }
                .message shouldBe "chat.local.maximum-distance must be between 1 and 128"
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    "nameplate config rejects unsafe renderer values" {
        val root = Files.createTempDirectory("arcevents-nameplate-")
        try {
            ArcEventsConfig.load(root)
            val original = Files.readString(root.resolve("config.yml"))
            Files.writeString(root.resolve("config.yml"), original.replace("vertical-offset: 0.55", "vertical-offset: 8.0"))
            shouldThrow<IllegalArgumentException> { ArcEventsConfig.inspect(root) }
                .message shouldBe "ui.nameplates.vertical-offset must be between -2 and 4"

            Files.writeString(root.resolve("config.yml"), original.replace("minimum-view-alignment: 0.5", "minimum-view-alignment: 1.5"))
            shouldThrow<IllegalArgumentException> { ArcEventsConfig.inspect(root) }
                .message shouldBe "ui.nameplates.minimum-view-alignment must be between -1 and 1"
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    "enabled host arena requires one common bounded player spawn" {
        val root = Files.createTempDirectory("arcevents-arena-")
        try {
            Files.writeString(root.resolve("config.yml"), validHostConfig(spawnCount = 0))
            shouldThrow<IllegalArgumentException> { ArcEventsConfig.inspect(root) }
            Files.writeString(root.resolve("config.yml"), validHostConfig(spawnCount = 1))
            ArcEventsConfig.inspect(root).arena.operational(16) shouldBe true
            ArcEventsConfig.inspect(root).arena.playerSpawn shouldBe ArcEventsConfig.inspect(root).arena.spawns.single()
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    "loot catalog rejects two coordinates inside the same block" {
        val bounds = EventBounds(EventLocation("arena", 0.0, 60.0, 0.0), EventLocation("arena", 10.0, 80.0, 10.0))
        val arena = ArenaSettings(
            id = "test",
            enabled = true,
            world = "arena",
            template = "",
            lobby = EventLocation("arena", 1.5, 65.0, 1.5),
            spectator = EventLocation("arena", 2.5, 65.0, 2.5),
            bounds = bounds,
            spawns = listOf(EventLocation("arena", 3.5, 65.0, 3.5)),
            lootSpawns = listOf(EventLocation("arena", 4.1, 65.1, 4.1), EventLocation("arena", 4.9, 65.9, 4.9)),
            weaponCount = 1,
        )

        arena.operational(16) shouldBe false
    }

    "reviewed parkour profile exposes four combat maps and the disasters arena" {
        val repository = opsRoot()
        val config = ArcEventsConfig.inspect(repository.resolve("parkour/plugins/ArcEvents"))

        config.arenas.map(ArenaSettings::id) shouldBe listOf(
            "inferno",
            "mirage",
            "nuke",
            "ttt-minecraft-b5",
            "disasters",
        )
        config.defaultArenaId shouldBe "inferno"
        config.arenas.all { it.operational(config.ttt.maximumPlayers) } shouldBe true
        val importedArenas = config.arenas.filter { it.id != "disasters" && it.template.endsWith("-v1") }
        importedArenas.all { it.spawns.size == 1 } shouldBe true
        importedArenas.all { it.lootSpawns.size >= 32 } shouldBe true
        importedArenas.all { it.weaponCount == 30 } shouldBe true
        config.weapons.visuals.mapValues { (_, visual) -> visual.customModelData } shouldBe mapOf(
            FirearmId.FLINTLOCK to 2100101,
            FirearmId.REVOLVER to 2100102,
            FirearmId.HAND_CANNON to 2100104,
            FirearmId.DOUBLE_BARREL to 2100103,
            FirearmId.FIVE_SEVEN to 2100002,
            FirearmId.G36 to 2100003,
            FirearmId.AEK_971 to 2100001,
            FirearmId.RPL_20 to 2100006,
            FirearmId.VEPR_12 to 2100007,
            FirearmId.M1_GARAND to 2100004,
            FirearmId.VSS_VINTOREZ to 2100008,
            FirearmId.MCMILLAN to 2100005,
        )
        config.ui.lootDisplays shouldBe true
        config.packetChatIsolationEnabled shouldBe false
        config.weapons.lootEffect.enabled shouldBe false
        config.weapons.lootEffect.customModelData.values.toSet() shouldBe setOf(2, 3, 4, 5, 6)
    }

    "a weapon pickup cannot be configured with zero reserve ammunition" {
        val root = Files.createTempDirectory("arcevents-ammo-")
        try {
            ArcEventsConfig.load(root)
            val original = Files.readString(root.resolve("config.yml"))
            Files.writeString(
                root.resolve("config.yml"),
                original.replace("pickup-ammo-minimum: 12", "pickup-ammo-minimum: 0"),
            )
            shouldThrow<IllegalArgumentException> { ArcEventsConfig.inspect(root) }
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    "loading every reviewed runtime profile is byte stable" {
        listOf(
            "classic/plugins/ArcEvents/config.yml",
            "classic_survival/plugins/ArcEvents/config.yml",
            "parkour/plugins/ArcEvents/config.yml",
            "scripts/lab/plugin-configs/ArcEvents/config.yml",
        ).forEach { relative ->
            val root = Files.createTempDirectory("arcevents-byte-stable-")
            try {
                val target = root.resolve("config.yml")
                Files.copy(opsRoot().resolve(relative), target)
                val before = Files.readAllBytes(target)

                ArcEventsConfig.load(root)

                withClue(relative) {
                    Files.readAllBytes(target).toList() shouldBe before.toList()
                }
            } finally {
                root.toFile().deleteRecursively()
            }
        }
    }

    "built-in templates require a host and a dedicated safe world" {
        val root = Files.createTempDirectory("arcevents-template-")
        try {
            val valid = validHostConfig(spawnCount = 1)
                .replace("world: pvp", "world: arcevents_ttt")
                .replace("  lobby:", "  template: citadel-v1\n  lobby:")
            ArcEventsConfig.inspect(root.also { Files.writeString(it.resolve("config.yml"), valid) }).arena.template shouldBe "citadel-v1"

            Files.writeString(root.resolve("config.yml"), valid.replace("world: arcevents_ttt", "world: pvp"))
            shouldThrow<IllegalArgumentException> { ArcEventsConfig.inspect(root) }

            Files.writeString(root.resolve("config.yml"), valid.replace("node-mode: HOST", "node-mode: RELAY"))
            shouldThrow<IllegalArgumentException> { ArcEventsConfig.inspect(root) }
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    "debug mutations require both the feature flag and an exact allowed server id" {
        val root = Files.createTempDirectory("arcevents-debug-")
        try {
            val base = validHostConfig(spawnCount = 1)
            Files.writeString(root.resolve("config.yml"), base.replace(
                "debug: {enabled: false}",
                "debug: {enabled: true, allowed-server-ids: [lab]}",
            ))
            ArcEventsConfig.inspect(root).debugMutationsAllowed shouldBe false

            Files.writeString(root.resolve("config.yml"), base.replace(
                "debug: {enabled: false}",
                "debug: {enabled: true, allowed-server-ids: [parkour]}",
            ))
            ArcEventsConfig.inspect(root).debugMutationsAllowed shouldBe true
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    "production Redis profile inherits the exact ARC connection in memory" {
        val root = Files.createTempDirectory("arcevents-redis-")
        val dataRoot = root.resolve("ArcEvents")
        val arcRoot = root.resolve("ARC")
        try {
            Files.createDirectories(dataRoot.resolve("modules"))
            Files.createDirectories(arcRoot.resolve("modules"))
            Files.writeString(
                dataRoot.resolve("modules/redis.yml"),
                redisConfig(inherit = true, host = "wrong-host", port = 1, password = "wrong-secret"),
            )
            Files.writeString(
                arcRoot.resolve("modules/redis.yml"),
                redisConfig(inherit = false, host = "redis.internal", port = 25001, password = "production-secret"),
            )

            val redis = ArcEventsRedisBootstrap.load(dataRoot, ArcEventsConfig.load(dataRoot))

            redis.host shouldBe "redis.internal"
            redis.port shouldBe 25001
            redis.username shouldBe "default"
            redis.password shouldBe "production-secret"
            redis.serverName shouldBe "parkour"
            Files.readString(dataRoot.resolve("modules/redis.yml")).contains("wrong-host") shouldBe true
            Files.readString(dataRoot.resolve("modules/redis.yml")).contains("production-secret") shouldBe false
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    "isolated Redis profile remains independent from ARC" {
        val root = Files.createTempDirectory("arcevents-isolated-redis-")
        try {
            Files.createDirectories(root.resolve("modules"))
            Files.writeString(
                root.resolve("modules/redis.yml"),
                redisConfig(inherit = false, host = "127.0.0.1", port = 16379, password = "lab-secret"),
            )

            val redis = ArcEventsRedisBootstrap.load(root, ArcEventsConfig.load(root))

            redis.host shouldBe "127.0.0.1"
            redis.port shouldBe 16379
            redis.password shouldBe "lab-secret"
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    "inheritance fails closed when ARC Redis is disabled" {
        val root = Files.createTempDirectory("arcevents-disabled-redis-")
        val dataRoot = root.resolve("ArcEvents")
        val arcRoot = root.resolve("ARC")
        try {
            Files.createDirectories(dataRoot.resolve("modules"))
            Files.createDirectories(arcRoot.resolve("modules"))
            Files.writeString(
                dataRoot.resolve("modules/redis.yml"),
                redisConfig(inherit = true, host = "wrong-host", port = 1, password = "wrong-secret"),
            )
            Files.writeString(
                arcRoot.resolve("modules/redis.yml"),
                redisConfig(inherit = false, host = "redis.internal", port = 25001, password = "secret")
                    .replace("enabled: true", "enabled: false"),
            )

            shouldThrow<IllegalArgumentException> {
                ArcEventsRedisBootstrap.load(dataRoot, ArcEventsConfig.load(dataRoot))
            }
        } finally {
            root.toFile().deleteRecursively()
        }
    }
}) {
    companion object {
        private fun opsRoot(): Path = System.getProperty("ruscrafting.opsRoot")?.let(Path::of)
            ?: throw TestAbortedException("RusCrafting ops checkout is not configured")

        private fun redisConfig(inherit: Boolean, host: String, port: Int, password: String): String = """
            |enabled: true
            |inherit-connection-from-arc: $inherit
            |host: $host
            |port: $port
            |username: default
            |password: $password
            |server-name: parkour
            |main-server: false
        """.trimMargin() + "\n"

        private fun validHostConfig(spawnCount: Int): String {
            val spawns = (1..spawnCount).joinToString("\n") { "    - '${it + 2},65,${it + 2},0,0'" }
            return """
                |enabled: true
                |server-id: parkour
                |node-mode: HOST
                |host-server: parkour
                |network:
                |  enabled: true
                |  allowed-origins: [spawn, survival, parkour]
                |  queue-entry-seconds: 300
                |  reservation-seconds: 45
                |  heartbeat-seconds: 5
                |  heartbeat-stale-seconds: 20
                |  transfer-on-reservation: true
                |  return-to-origin: true
                |locale: {default: ru, use-client-locale: true}
                |ui:
                |  sounds: true
                |  particles: true
                |  bossbar: true
                |  dialogs-enabled: true
                |  filler: {material: BLACK_STAINED_GLASS_PANE, custom-model-data: 0}
                |ttt:
                |  minimum-players: 4
                |  maximum-players: 16
                |  preparation-seconds: 12
                |  countdown-seconds: 8
                |  round-seconds: 600
                |  post-round-seconds: 12
                |  traitor-player-ratio: 4
                |  detective-minimum-players: 6
                |  traitor-credits: 2
                |  detective-credits: 1
                |  body-despawn-seconds: 600
                |arena:
                |  enabled: true
                |  world: pvp
                |  lobby: '1,65,1,0,0'
                |  spectator: '2,65,2,0,0'
                |  minimum: '0,60,0'
                |  maximum: '100,100,100'
                |  spawns:
                |$spawns
                |debug: {enabled: false}
            """.trimMargin() + "\n"
        }
    }
}
