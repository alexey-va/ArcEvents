package ru.ruscrafting.events.config

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.throwables.shouldNotThrowAny
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import org.opentest4j.TestAbortedException
import ru.ruscrafting.events.domain.FirearmId
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
            config.weapons.lootEffect.enabled shouldBe false
            config.ttt.preparationSeconds shouldBe 30
            config.ui.dialogsEnabled shouldBe false
            config.ui.lootDisplays shouldBe true
            config.ui.nameplates shouldBe NameplateSettings(
                enabled = true,
                reconcilePeriodTicks = 4L,
                maxDistance = 32.0,
                lineWidth = 180,
                viewRange = 0.5,
                scale = 0.8,
                verticalOffset = 0.55,
                shadowed = true,
                background = NameplateBackgroundSettings(0, 0, 0, 0),
                hideInvisibleTargets = true,
                hideSpectatorTargets = true,
                requireLineOfSight = true,
                healthPriority = 200,
                summaryPriority = 100,
            )
            config.packetChatIsolationEnabled shouldBe false
            config.ui.back shouldBe UiItemSettings("BLUE_STAINED_GLASS_PANE", 11013)
            config.debug.enabled shouldBe false
            config.debug.allowedServerIds shouldBe setOf("lab")
            config.debugMutationsAllowed shouldBe false
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    "live reload accepts nameplate tuning during a match but protects gameplay and topology" {
        val root = Files.createTempDirectory("arcevents-reload-")
        try {
            val current = ArcEventsConfig.load(root)
            val original = Files.readString(root.resolve("config.yml"))

            Files.writeString(root.resolve("config.yml"), original.replace("scale: 0.8", "scale: 0.7"))
            val nameplateCandidate = ArcEventsConfig.inspect(root)
            shouldNotThrowAny {
                ArcEventsReloadPolicy.validate(current, nameplateCandidate, matchOrReservationActive = true)
            }

            Files.writeString(root.resolve("config.yml"), original.replace("round-seconds: 600", "round-seconds: 480"))
            val gameplayCandidate = ArcEventsConfig.inspect(root)
            shouldThrow<IllegalArgumentException> {
                ArcEventsReloadPolicy.validate(current, gameplayCandidate, matchOrReservationActive = true)
            }.message shouldBe "ttt settings can reload only while idle"

            Files.writeString(root.resolve("config.yml"), original.replace("bossbar: true", "bossbar: false"))
            val uiCandidate = ArcEventsConfig.inspect(root)
            shouldThrow<IllegalArgumentException> {
                ArcEventsReloadPolicy.validate(current, uiCandidate, matchOrReservationActive = true)
            }.message shouldBe "non-nameplate ui settings can reload only while idle"

            Files.writeString(root.resolve("config.yml"), original.replace("heartbeat-seconds: 5", "heartbeat-seconds: 6"))
            val networkCandidate = ArcEventsConfig.inspect(root)
            shouldThrow<IllegalArgumentException> {
                ArcEventsReloadPolicy.validate(current, networkCandidate, matchOrReservationActive = false)
            }.message shouldBe "network settings require a restart"
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

    "reviewed parkour profile exposes the three complete CS2 arenas" {
        val repository = opsRoot()
        val config = ArcEventsConfig.inspect(repository.resolve("parkour/plugins/ArcEvents"))

        config.arenas.map(ArenaSettings::id) shouldBe listOf("inferno", "mirage", "nuke")
        config.arenas.all { it.operational(config.ttt.maximumPlayers) } shouldBe true
        val importedArenas = config.arenas.filter { it.template.startsWith("cs2-") }
        importedArenas.all { it.spawns.size == 1 } shouldBe true
        importedArenas.all { it.lootSpawns.size == 32 } shouldBe true
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
