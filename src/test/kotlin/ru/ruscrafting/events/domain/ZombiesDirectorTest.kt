package ru.ruscrafting.events.domain

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import java.util.UUID

class ZombiesDirectorTest : StringSpec({
    val first = UUID.nameUUIDFromBytes("survivor-one".toByteArray())
    val second = UUID.nameUUIDFromBytes("survivor-two".toByteArray())
    fun director(vararg ids: UUID) = ZombiesDirector(ids.toSet(), ZombiesRules(), 0)
    fun clearWave(game: ZombiesDirector, killer: UUID): List<ZombieKind> {
        game.tick(game.nextWaveAt)
        val kinds = mutableListOf<ZombieKind>()
        while (game.phase == ZombiesPhase.WAVE) {
            val kind = requireNotNull(game.spawn())
            kinds += kind
            game.removeEnemy(kind, killer, game.nextWaveAt)
        }
        return kinds
    }

    "solo and four-player teams complete ten escalating waves with runners and two brutes" {
        for (count in 1..4) {
            val ids = (0 until count).map { UUID.nameUUIDFromBytes("team-$it".toByteArray()) }.toSet()
            val game = ZombiesDirector(ids, ZombiesRules(), 0)
            game.tick(14_999)
            game.wave shouldBe 0
            game.spawn() shouldBe null
            for (wave in 1..10) {
                val kinds = clearWave(game, ids.first())
                kinds.size shouldBe 8 + (wave - 1) * 3 + (count - 1) * 3
                kinds.count { it == ZombieKind.BRUTE } shouldBe if (wave % 5 == 0) 1 else 0
                kinds.any { it == ZombieKind.RUNNER } shouldBe (wave >= 3)
                game.alive shouldBe 0
                game.pending shouldBe 0
                game.phase shouldBe if (wave == 10) ZombiesPhase.WON else ZombiesPhase.INTERMISSION
            }
            game.tick(Long.MAX_VALUE)
            game.wave shouldBe 10
            game.canBuy(ids.first(), ZombiesOffer.AMMO) shouldBe false
        }
        shouldThrow<IllegalArgumentException> { director() }
        shouldThrow<IllegalArgumentException> { ZombiesRules(maximumAlive = 33).validated() }
    }

    "completed wave count remains exact between waves and after a failed following wave" {
        val game = director(first)
        game.completedWaves shouldBe 0
        clearWave(game, first)
        game.phase shouldBe ZombiesPhase.INTERMISSION
        game.completedWaves shouldBe 1
        game.tick(game.nextWaveAt)
        game.wave shouldBe 2
        game.completedWaves shouldBe 1
        game.down(first, 30_000)
        game.revive(first)
        game.down(first, 34_000)
        game.phase shouldBe ZombiesPhase.LOST
        game.completedWaves shouldBe 1
    }

    "spawn cap bounds active mobs and missing or outsider kills award no kill points" {
        val game = ZombiesDirector(setOf(first, second), ZombiesRules(maximumAlive = 8), 0)
        game.tick(game.nextWaveAt)
        repeat(8) { game.spawn() shouldBe ZombieKind.WALKER }
        game.spawn() shouldBe null
        game.alive shouldBe 8
        game.pending shouldBe 3
        game.removeEnemy(ZombieKind.WALKER, null, 15_000)
        game.removeEnemy(ZombieKind.WALKER, UUID.randomUUID(), 15_000)
        game.players.values.map { it.points } shouldBe listOf(500, 500)
        game.spawn() shouldBe ZombieKind.WALKER
        game.removeEnemy(ZombieKind.WALKER, first, 15_000)
        game.players.getValue(first).points shouldBe 600
        game.players.getValue(second).points shouldBe 550
    }

    "solo charge keeps a downed session alive exactly once" {
        val game = director(first)
        game.down(first, 1000) shouldBe true
        game.down(first, 1001) shouldBe false
        game.phase shouldBe ZombiesPhase.INTERMISSION
        game.players.getValue(first).let { it.selfRevives shouldBe 0; it.recoveryAt shouldBe 4000 }
        game.tick(4000)
        game.revive(first) shouldBe true
        game.revive(first) shouldBe false
        game.down(first, 5000) shouldBe true
        game.phase shouldBe ZombiesPhase.LOST
        game.revive(first) shouldBe false
        game.spawn() shouldBe null
    }

    "teammate rescue restores a player and bleedout waits for a completed wave" {
        val game = director(first, second)
        game.players.values.all { it.selfRevives == 0 } shouldBe true
        game.down(second, 0) shouldBe true
        game.revive(second) shouldBe true
        game.down(second, 1) shouldBe true
        game.tick(25_000)
        game.players.getValue(second).status shouldBe ZombiesStatus.DOWNED
        game.tick(25_001)
        game.players.getValue(second).status shouldBe ZombiesStatus.WAITING
        game.revive(second) shouldBe false
        clearWave(game, first)
        game.players.getValue(second).status shouldBe ZombiesStatus.ALIVE
        game.players.getValue(second).points shouldBe 500 + 11 * 50 + 175
    }

    "last standing teammate falling or leaving ends the run without reviving disconnected players" {
        val game = director(first, second)
        game.down(first, 0)
        game.down(second, 0)
        game.phase shouldBe ZombiesPhase.LOST
        val leaving = director(first, second)
        leaving.disconnect(second)
        clearWave(leaving, first)
        leaving.players.getValue(second).status shouldBe ZombiesStatus.LEFT
        leaving.players.getValue(second).points shouldBe 500
        leaving.down(first, 20_000)
        leaving.phase shouldBe ZombiesPhase.LOST
    }

    "local purchases enforce funds upgrades state and the curated weapon pool" {
        val game = director(first)
        game.buy(first, ZombiesOffer.POWER) shouldBe false
        game.buy(first, ZombiesOffer.AMMO) shouldBe true
        game.players.getValue(first).points shouldBe 350
        clearWave(game, first)
        game.price(first, ZombiesOffer.POWER) shouldBe 1000
        game.buy(first, ZombiesOffer.POWER) shouldBe true
        game.price(first, ZombiesOffer.POWER) shouldBe 2000
        game.buy(first, ZombiesOffer.POWER) shouldBe false
        repeat(5) { clearWave(game, first) }
        game.buy(first, ZombiesOffer.POWER) shouldBe true
        game.buy(first, ZombiesOffer.POWER) shouldBe true
        game.players.getValue(first).power shouldBe 3
        game.buy(first, ZombiesOffer.POWER) shouldBe false
        game.buy(first, ZombiesOffer.VITALITY) shouldBe true
        game.buy(first, ZombiesOffer.VITALITY) shouldBe false
        shouldThrow<IllegalArgumentException> { game.buy(first, ZombiesOffer.MYSTERY, FirearmId.FIVE_SEVEN) }
        game.buy(first, ZombiesOffer.MYSTERY, FirearmId.G36) shouldBe true
        game.players.getValue(first).weapon shouldBe FirearmId.G36
        game.down(first, 1)
        game.canBuy(first, ZombiesOffer.AMMO) shouldBe false
        game.canBuy(UUID.randomUUID(), ZombiesOffer.AMMO) shouldBe false
    }
})
