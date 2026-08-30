package ru.ruscrafting.events.paper

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import ru.ruscrafting.events.config.ArcEventsConfig
import java.nio.file.Files
import java.util.UUID

class ArenaPoolTest : StringSpec({
    "one-shot selection leases one ready arena until the matching release" {
        val root = Files.createTempDirectory("arcevents-pool-")
        try {
            Files.writeString(root.resolve("config.yml"), hostConfig())
            val config = ArcEventsConfig.inspect(root)
            val pool = ArenaPool({ config }) { _, _ -> true }
            val matchId = UUID.fromString("00000000-0000-0000-0000-000000000042")

            pool.selectNext("beta") shouldBe true
            pool.reserve(matchId)?.id shouldBe "beta"
            pool.entries().first { it.id == "beta" }.active shouldBe true
            pool.entries().none { it.next } shouldBe true
            pool.reserve(UUID.randomUUID()) shouldBe null
            pool.release(UUID.randomUUID())
            pool.active()?.id shouldBe "beta"
            pool.release(matchId)
            pool.active() shouldBe null
            pool.selectNext("alpha") shouldBe true
            pool.clear()
            pool.entries().none { it.next || it.active } shouldBe true
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    "automatic selection is reproducible for a match id" {
        val root = Files.createTempDirectory("arcevents-pool-deterministic-")
        try {
            Files.writeString(root.resolve("config.yml"), hostConfig())
            val config = ArcEventsConfig.inspect(root)
            val matchId = UUID.fromString("10000000-0000-0000-2000-000000000001")

            ArenaPool({ config }) { _, _ -> true }.reserve(matchId)?.id shouldBe
                ArenaPool({ config }) { _, _ -> true }.reserve(matchId)?.id
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    "availability stops after the first ready arena" {
        val root = Files.createTempDirectory("arcevents-pool-availability-")
        try {
            Files.writeString(root.resolve("config.yml"), hostConfig())
            val config = ArcEventsConfig.inspect(root)
            val inspected = mutableListOf<String>()
            val pool = ArenaPool({ config }) { arena, _ ->
                inspected += arena.id
                arena.id == "alpha"
            }

            pool.anyReady() shouldBe true
            inspected shouldBe listOf("alpha")
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    "availability reuses a recent runtime inspection" {
        val root = Files.createTempDirectory("arcevents-pool-availability-cache-")
        try {
            Files.writeString(root.resolve("config.yml"), hostConfig())
            val config = ArcEventsConfig.inspect(root)
            val inspected = mutableListOf<String>()
            var current = config
            var now = 1_000L
            val pool = ArenaPool(
                settings = { current },
                ready = { arena, _ ->
                    inspected += arena.id
                    arena.id == "alpha"
                },
                clock = { now },
            )

            pool.anyReady() shouldBe true
            pool.anyReady() shouldBe true
            inspected shouldBe listOf("alpha")

            current = ArcEventsConfig.inspect(root)
            pool.anyReady() shouldBe true
            inspected shouldBe listOf("alpha", "alpha")

            now += 5 * 60_000L
            pool.anyReady() shouldBe true
            inspected shouldBe listOf("alpha", "alpha", "alpha")
        } finally {
            root.toFile().deleteRecursively()
        }
    }
}) {
    companion object {
        private fun hostConfig(): String {
            val spawns = "      - '3,65,3,0,0'"
            fun arena(world: String) = """
                |    enabled: true
                |    world: $world
                |    template: ''
                |    lobby: '1,65,1,0,0'
                |    spectator: '2,65,2,0,0'
                |    minimum: '0,60,0'
                |    maximum: '100,100,100'
                |    spawns:
                |$spawns
                |    loot-spawns: []
            """.trimMargin()
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
                |ttt: {minimum-players: 4, maximum-players: 16, preparation-seconds: 30, countdown-seconds: 8, round-seconds: 600, post-round-seconds: 12, traitor-player-ratio: 4, detective-minimum-players: 6, traitor-credits: 2, detective-credits: 1, body-despawn-seconds: 600}
                |arenas:
                |  alpha:
                |${arena("arena_alpha")}
                |  beta:
                |${arena("arena_beta")}
            """.trimMargin() + "\n"
        }
    }
}
