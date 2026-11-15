package ru.ruscrafting.events.paper.apocalypse

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

class ApocalypseJoinRecoveryTest : StringSpec({
    "accepted craft resumes only its applied native generation" {
        apocalypseJoinRecovery(ApocalypsePlayerPhase.ACTIVE, true, "new", "new") shouldBe ApocalypseJoinRecovery.RESUME
        apocalypseJoinRecovery(ApocalypsePlayerPhase.ACTIVE, true, "new", "old") shouldBe ApocalypseJoinRecovery.ORIGIN
        apocalypseJoinRecovery(ApocalypsePlayerPhase.RETURNING, true, "new", "new") shouldBe ApocalypseJoinRecovery.ORIGIN
    }
    "parked native receipt recovers origin while ordinary joins remain unrelated" {
        apocalypseJoinRecovery(ApocalypsePlayerPhase.PARKED, true, "new", "old") shouldBe ApocalypseJoinRecovery.ORIGIN
        apocalypseJoinRecovery(ApocalypsePlayerPhase.PARKED, true, "new", null) shouldBe ApocalypseJoinRecovery.NONE
        apocalypseJoinRecovery(ApocalypsePlayerPhase.PARKED, false, "new", null) shouldBe ApocalypseJoinRecovery.NONE
        apocalypseJoinRecovery(null, false, null, null) shouldBe ApocalypseJoinRecovery.NONE
    }
    "an orphan native inventory is retained for recovery instead of exported" {
        apocalypseJoinRecovery(ApocalypsePlayerPhase.PARKED, false, "new", "old") shouldBe ApocalypseJoinRecovery.QUARANTINE
        apocalypseJoinRecovery(null, false, null, "old") shouldBe ApocalypseJoinRecovery.QUARANTINE
    }
})
