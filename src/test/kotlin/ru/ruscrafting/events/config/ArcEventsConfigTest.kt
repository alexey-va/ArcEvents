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
}) {
    companion object {
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
