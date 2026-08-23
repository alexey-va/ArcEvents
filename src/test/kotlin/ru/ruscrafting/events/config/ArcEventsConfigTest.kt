package ru.ruscrafting.events.config

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import java.nio.file.Files

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
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    "enabled host arena requires complete bounded spawn data" {
        val root = Files.createTempDirectory("arcevents-arena-")
        try {
            Files.writeString(root.resolve("config.yml"), validHostConfig(spawnCount = 15))
            shouldThrow<IllegalArgumentException> { ArcEventsConfig.inspect(root) }
            Files.writeString(root.resolve("config.yml"), validHostConfig(spawnCount = 16))
            ArcEventsConfig.inspect(root).arena.operational(16) shouldBe true
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    "built-in templates require a host and a dedicated safe world" {
        val root = Files.createTempDirectory("arcevents-template-")
        try {
            val valid = validHostConfig(spawnCount = 16)
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
