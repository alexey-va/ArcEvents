package ru.ruscrafting.events.paper

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import ru.ruscrafting.events.domain.EventMode
import ru.ruscrafting.events.domain.MatchPhase
import ru.ruscrafting.events.domain.TttRole
import java.util.concurrent.CompletableFuture

class EventOpeningTest : FunSpec({
    val plain = PlainTextComponentSerializer.plainText()

    test("public TTT waits for the durable roster then shows one private role objective through combat start") {
        TttRoundFixture().use { fixture ->
            val commit = CompletableFuture<Unit>()
            every { fixture.network.completeReservation(any(), any()) } returns commit
            fixture.startReservation()
            fixture.players.forEach { fixture.arrive(it) shouldBe true }
            repeat(7) { fixture.advanceTime(1_000) }
            fixture.service.phase() shouldBe MatchPhase.PREPARING
            fixture.players.forEach { fixture.paper.adventureTitles(it).size shouldBe 0 }

            commit.complete(Unit)
            fixture.advanceTime(1_000)
            fixture.service.phase() shouldBe MatchPhase.COUNTDOWN
            val match = requireNotNull(fixture.service.currentMatch())
            fixture.players.forEach { player ->
                val role = requireNotNull(match.participant(player.uniqueId)).role
                val (name, objective) = when (role) {
                    TttRole.INNOCENT -> "Мирный" to "Ищите предателей по уликам"
                    TttRole.TRAITOR -> "Предатель" to "Скрытно устраните мирных"
                    TttRole.DETECTIVE -> "Детектив" to "ПКМ по телу для осмотра"
                }
                val title = fixture.paper.adventureTitles(player).single()
                plain.serialize(title.title()) shouldBe name
                plain.serialize(title.subtitle()) shouldBe objective
            }
            repeat(2) { fixture.advanceTime(1_000) }
            fixture.service.phase() shouldBe MatchPhase.COUNTDOWN
            fixture.advanceTime(1_000)
            fixture.service.phase() shouldBe MatchPhase.ACTIVE
            fixture.players.forEach { fixture.paper.adventureTitles(it).size shouldBe 1 }
        }
    }

    test("Gun Game practice sends its opening only to the ready player and starts on the configured deadline") {
        TttRoundFixture().use { fixture ->
            fixture.reloadRuntime { it.replace("countdown-seconds: 0", "countdown-seconds: 3") }
            val owner = fixture.players.first()
            val commit = CompletableFuture<Unit>()
            every { fixture.network.completeReservation(any(), any()) } returns commit
            fixture.startPracticeReservation(EventMode.GUN_GAME)
            fixture.arrive(owner) shouldBe true
            fixture.advanceTime(30_000)
            fixture.service.phase() shouldBe MatchPhase.PREPARING
            fixture.paper.adventureTitles(owner).size shouldBe 0

            commit.complete(Unit)
            fixture.service.phase() shouldBe MatchPhase.COUNTDOWN
            val title = fixture.paper.adventureTitles(owner).single()
            plain.serialize(title.title()) shouldBe "Гонка оружия"
            plain.serialize(title.subtitle()) shouldBe "Новое оружие за устранение"
            fixture.advanceTime(2_999)
            fixture.service.phase() shouldBe MatchPhase.COUNTDOWN
            fixture.advanceTime(1)
            fixture.service.phase() shouldBe MatchPhase.ACTIVE
            fixture.paper.adventureTitles(owner).size shouldBe 1
            fixture.players.drop(1).forEach { fixture.paper.adventureTitles(it).size shouldBe 0 }
        }
    }
})
