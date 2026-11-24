package ru.ruscrafting.events.domain

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import java.util.UUID

class ZombiesDirectorTest : StringSpec({
    val first = UUID.nameUUIDFromBytes("survivor-one".toByteArray())
    val second = UUID.nameUUIDFromBytes("survivor-two".toByteArray())
    fun director(vararg ids: UUID) = ZombiesDirector(ids.toSet(), ZombiesRules(), 0)
    fun practice(difficulty: ZombiesDifficulty, vararg ids: UUID) =
        ZombiesDirector(ids.toSet(), ZombiesRules.forDifficulty(difficulty), 0)
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
            game.tick(0)
            game.phase shouldBe ZombiesPhase.WAVE
            game.wave shouldBe 1
            for (wave in 1..10) {
                if (wave > 1) {
                    game.tick(game.nextWaveAt - 1)
                    game.phase shouldBe ZombiesPhase.INTERMISSION
                }
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

    "long practice runs reach their configured final wave with distinct enemy mixes and bounded scaling" {
        for ((difficulty, waveCount) in listOf(ZombiesDifficulty.EASY to 25, ZombiesDifficulty.HARD to 35,
            ZombiesDifficulty.HARDCORE to 50)) {
            val game = practice(difficulty, first)
            game.rules.waves shouldBe waveCount
            repeat(waveCount) { index ->
                val kinds = clearWave(game, first)
                val wave = index + 1
                game.wave shouldBe wave
                game.completedWaves shouldBe wave
                game.alive shouldBe 0
                game.pending shouldBe 0
                (kinds.size in 7..28) shouldBe true
                (game.health(ZombieKind.WALKER) in 14.0..92.0) shouldBe true
                (ZombieKind.BOSS in kinds) shouldBe false
                (ZombieKind.BRUTE in kinds) shouldBe (wave % 5 == 0)
                (ZombieKind.RUNNER in kinds) shouldBe (difficulty != ZombiesDifficulty.EASY || wave >= 3)
                (ZombieKind.STRONG in kinds) shouldBe (difficulty == ZombiesDifficulty.HARDCORE ||
                    difficulty == ZombiesDifficulty.HARD && wave >= 3)
                (ZombieKind.BLOATER in kinds) shouldBe (difficulty == ZombiesDifficulty.HARDCORE ||
                    difficulty == ZombiesDifficulty.HARD && wave >= 5)
            }
            game.phase shouldBe ZombiesPhase.WON
            game.jumpToWave(1, 100_000) shouldBe false
        }
    }

    "insane schedules one boss and permits the final supply claim during victory" {
        val game = practice(ZombiesDifficulty.INSANE, first, second)
        game.tick(0)
        game.waveSize shouldBe 1
        game.spawn() shouldBe ZombieKind.BOSS
        game.spawn() shouldBe null
        (game.health(ZombieKind.BOSS) > game.health(ZombieKind.BRUTE)) shouldBe true
        game.removeEnemy(ZombieKind.BOSS, first, 100)
        game.phase shouldBe ZombiesPhase.WON
        game.completedWaves shouldBe 1
        game.canAcceptAirdrop(first) shouldBe true
        game.canAcceptAirdrop(UUID.randomUUID()) shouldBe false
        val before = game.players.getValue(first)
        game.acceptAirdrop(first, bonusPoints = true, deliveredWeapon = FirearmId.G36) shouldBe true
        game.players.getValue(first) shouldBe before.copy(
            copper = before.copper + 3, powder = before.powder + 4, iron = before.iron + 2,
            points = before.points + 250, weapon = FirearmId.G36,
        )
        game.buyGear(first, ZombiesGear.WIRE) shouldBe false
        game.grantSupplies(first, iron = 5) shouldBe false
    }

    "debug enemies respect the cap and wave jumps restart the schedule without credit for skipped or repeated waves" {
        val game = practice(ZombiesDifficulty.HARDCORE, first)
        clearWave(game, first)
        game.jumpToWave(5, 20_000) shouldBe true
        val scheduled = game.pending
        repeat(game.rules.maximumAlive) { game.spawn(ZombieKind.BLOATER) shouldBe ZombieKind.BLOATER }
        game.pending shouldBe scheduled
        game.waveSize shouldBe scheduled + game.rules.maximumAlive
        game.spawn() shouldBe null
        game.spawn(ZombieKind.RUNNER) shouldBe null
        game.removeEnemy(ZombieKind.BLOATER, null, 20_500)
        game.spawn() shouldBe ZombieKind.BRUTE
        game.pending shouldBe scheduled - 1

        game.jumpToWave(0, 21_000) shouldBe false
        game.jumpToWave(51, 21_000) shouldBe false
        game.wave shouldBe 5
        game.alive shouldBe game.rules.maximumAlive
        game.jumpToWave(5, 22_000) shouldBe true
        game.alive shouldBe 0
        game.pending shouldBe scheduled
        game.nextWaveAt shouldBe 22_000
        game.completedWaves shouldBe 1
        clearWave(game, first).first() shouldBe ZombieKind.BRUTE
        game.completedWaves shouldBe 2
        game.jumpToWave(5, 40_000) shouldBe true
        clearWave(game, first)
        game.completedWaves shouldBe 2
    }

    "materials go only to the credited standing killer and drops require an eligible practice player" {
        val game = practice(ZombiesDifficulty.HARD, first, second)
        game.tick(0)
        repeat(3) { game.spawn() }
        game.removeEnemy(ZombieKind.WALKER, null, 100)
        game.removeEnemy(ZombieKind.WALKER, UUID.randomUUID(), 110)
        game.removeEnemy(ZombieKind.RUNNER, first, 120)
        game.players.getValue(first) shouldBe ZombiesSurvivor(points = 625, copper = 1, powder = 1)
        game.players.getValue(second) shouldBe ZombiesSurvivor(points = 550)

        game.down(second, 130) shouldBe true
        game.canAcceptAirdrop(second) shouldBe false
        game.acceptAirdrop(second, bonusPoints = true) shouldBe false
        game.acceptAirdrop(first, bonusPoints = false, deliveredWeapon = FirearmId.FIVE_SEVEN) shouldBe false
        game.acceptAirdrop(first, bonusPoints = false, deliveredWeapon = FirearmId.G36) shouldBe true
        game.players.getValue(first) shouldBe ZombiesSurvivor(points = 625, weapon = FirearmId.G36,
            copper = 4, powder = 5, iron = 2)
        game.acceptAirdrop(first, bonusPoints = true) shouldBe true
        game.players.getValue(first) shouldBe ZombiesSurvivor(points = 875, weapon = FirearmId.G36,
            copper = 7, powder = 9, iron = 4)
        game.down(first, 140) shouldBe true
        game.phase shouldBe ZombiesPhase.LOST
        game.canAcceptAirdrop(first) shouldBe false

        val classic = director(first)
        classic.tick(0)
        classic.jumpToWave(5, 500) shouldBe false
        classic.spawn(ZombieKind.BOSS) shouldBe null
        classic.buyGear(first, ZombiesGear.WIRE) shouldBe false
        classic.grantSupplies(first, copper = 5) shouldBe false
        classic.acceptAirdrop(first, bonusPoints = true) shouldBe false
        clearWave(classic, first)
        classic.players.getValue(first) shouldBe ZombiesSurvivor(points = 1475, selfRevives = 1)
    }

    "gear prices debit exact wallet balances and enforce bench prerequisites atomically" {
        val game = practice(ZombiesDifficulty.EASY, first)
        game.buyGear(first, ZombiesGear.WIRE) shouldBe true
        game.players.getValue(first).points shouldBe 0
        game.buyGear(first, ZombiesGear.WALL) shouldBe false
        game.grantSupplies(first, copper = 17, powder = 22, iron = 4, points = 3800) shouldBe true
        val funded = game.players.getValue(first)
        game.grantSupplies(first, copper = -1, powder = 1) shouldBe false
        game.grantSupplies(first, copper = Int.MAX_VALUE, powder = 1) shouldBe false
        game.buyGear(first, ZombiesGear.SMOKE) shouldBe false
        game.buyGear(first, ZombiesGear.TURRET) shouldBe false
        game.players.getValue(first) shouldBe funded

        game.buyGear(first, ZombiesGear.WALL) shouldBe true
        game.buyGear(first, ZombiesGear.BENCH) shouldBe true
        game.players.getValue(first).points shouldBe 2000
        game.players.getValue(first).bench shouldBe 1
        game.buyGear(first, ZombiesGear.TURRET) shouldBe false
        game.buyGear(first, ZombiesGear.SMOKE) shouldBe true
        game.buyGear(first, ZombiesGear.BENCH) shouldBe true
        game.buyGear(first, ZombiesGear.TURRET) shouldBe true
        game.buyGear(first, ZombiesGear.HE) shouldBe true
        game.buyGear(first, ZombiesGear.AP) shouldBe true
        game.buyGear(first, ZombiesGear.FRAG) shouldBe true
        game.players.getValue(first) shouldBe ZombiesSurvivor(points = 0, selfRevives = 1, bench = 2)
        game.grantSupplies(first, copper = 7, powder = 8, points = 3000) shouldBe true
        val remainder = game.players.getValue(first)
        game.buyGear(first, ZombiesGear.HE) shouldBe false
        game.buyGear(first, ZombiesGear.BENCH) shouldBe false
        game.players.getValue(first) shouldBe remainder
        game.down(first, 1) shouldBe true
        game.buyGear(first, ZombiesGear.WIRE) shouldBe false
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
        shouldThrow<IllegalArgumentException> { game.reserveMystery(first, FirearmId.FIVE_SEVEN) }
        game.buy(first, ZombiesOffer.MYSTERY) shouldBe false
        game.reserveMystery(first, FirearmId.G36) shouldBe true
        game.claimMystery(first) shouldBe FirearmId.G36
        game.players.getValue(first).weapon shouldBe FirearmId.G36
        game.down(first, 1)
        game.canBuy(first, ZombiesOffer.AMMO) shouldBe false
        game.canBuy(UUID.randomUUID(), ZombiesOffer.AMMO) shouldBe false
    }

    "a world box debits once and changes no weapon until its single claim" {
        val game = director(first, second)
        clearWave(game, first)
        val before = game.players.getValue(first)
        game.reserveMystery(first, FirearmId.G36) shouldBe true
        game.reserveMystery(first, FirearmId.MCMILLAN) shouldBe false
        game.players.getValue(first).points shouldBe before.points - 650
        game.players.getValue(first).weapon shouldBe before.weapon
        game.down(first, 20_000)
        game.claimMystery(first) shouldBe null
        game.revive(first) shouldBe true
        game.claimMystery(first) shouldBe FirearmId.G36
        game.claimMystery(first) shouldBe null
        game.cancelMystery(first, refund = true) shouldBe false
        game.players.getValue(first).points shouldBe before.points - 650
    }

    "technical cancellation refunds exactly once while expiry and leaving never permit a free reroll" {
        val game = director(first, second)
        clearWave(game, first)
        val points = game.players.getValue(first).points
        game.reserveMystery(first, FirearmId.G36) shouldBe true
        game.cancelMystery(first, refund = true) shouldBe true
        game.cancelMystery(first, refund = true) shouldBe false
        game.players.getValue(first).points shouldBe points
        game.reserveMystery(first, FirearmId.G36) shouldBe true
        game.cancelMystery(first, refund = false) shouldBe true
        game.cancelMystery(first, refund = true) shouldBe false
        game.players.getValue(first).points shouldBe points - 650
        game.players.getValue(first).weapon shouldBe FirearmId.FIVE_SEVEN
        clearWave(game, first)
        val leavingPoints = game.players.getValue(first).points
        game.reserveMystery(first, FirearmId.G36) shouldBe true
        game.disconnect(first)
        game.claimMystery(first) shouldBe null
        game.cancelMystery(first, refund = true) shouldBe false
        game.players.getValue(first).points shouldBe leavingPoints - 650
    }
})
