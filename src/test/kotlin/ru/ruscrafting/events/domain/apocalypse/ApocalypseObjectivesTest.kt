package ru.ruscrafting.events.domain.apocalypse

import com.google.gson.Gson
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import ru.ruscrafting.events.domain.apocalypse.ApocalypseObjectivePoint.*

class ApocalypseObjectivesTest : StringSpec({
    "the two clues and the call must be made in order, even if another survivor already filled the diesel can" {
        val start = ApocalypseObjectivesState()
        val searchedTanks = start.inspect(DRAIN).inspect(RELAY)
        searchedTanks.inspect(RADIO) shouldBe searchedTanks
        val firstClue = searchedTanks.inspect(APARTMENT)
        firstClue.radioClues shouldBe 1
        firstClue.inspect(APARTMENT).inspect(RADIO) shouldBe firstClue
        val secondClue = firstClue.inspect(DRAIN)
        secondClue shouldBe ApocalypseObjectivesState(relay = true, diesel = true, radioClues = 2)
        val answered = secondClue.inspect(RADIO)
        answered.inspect(APARTMENT).inspect(DRAIN).inspect(RADIO) shouldBe answered
        answered.radioClues shouldBe 3
        start shouldBe ApocalypseObjectivesState()
    }

    "generator and siren progress round-trip by expedition and corrupted state is rejected" {
        val progress = ApocalypseObjectivesState().inspect(RELAY).inspect(DRAIN).copy(portPowered = true, sirenDay = 10)
        val gson = Gson()
        gson.fromJson(gson.toJson(progress), ApocalypseObjectivesState::class.java).validated() shouldBe progress
        gson.fromJson("{}", ApocalypseObjectivesState::class.java).validated() shouldBe ApocalypseObjectivesState()
        shouldThrow<IllegalArgumentException> { progress.copy(diesel = false).validated() }
        shouldThrow<IllegalArgumentException> { progress.copy(radioClues = 4).validated() }
        shouldThrow<IllegalArgumentException> { progress.copy(sirenDay = -1).validated() }
    }

    "a crew can set the siren before the wave and only a third of that night's personal wave changes destination" {
        val tuning = ApocalypseSurvivalSettings()
        val start = tuning.ticksPerDay * 10 + tuning.hordeStartTime
        apocalypseSirenNight(0, tuning) shouldBe 10L
        apocalypseSirenNight(start - 1, tuning) shouldBe 10L
        apocalypseSirenNight(start, tuning) shouldBe null
        apocalypseSirenNight(tuning.ticksPerDay * 11, tuning) shouldBe 20L
        val armed = ApocalypseObjectivesState(sirenDay = 10)
        (0 until 12).filter { armed.diverted("survivor/horde:10:$it") } shouldBe listOf(2, 5, 8, 11)
        listOf("survivor/horde:20:2", "ambient:10:2", "lab:bunker:2", "survivor/horde:10:-1",
            "survivor/horde:10:bunker:2").any(armed::diverted) shouldBe false
        ApocalypseObjectivesState().diverted("survivor/horde:10:2") shouldBe false
    }
})
