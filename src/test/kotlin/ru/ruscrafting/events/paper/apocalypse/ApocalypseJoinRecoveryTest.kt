package ru.ruscrafting.events.paper.apocalypse

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import java.nio.file.Files
import java.util.UUID

class ApocalypseJoinRecoveryTest : StringSpec({
    "an existing parked return can acquire a receipt without replacing inventory records" {
        val root = Files.createTempDirectory("apocalypse-legacy-return-")
        val store = ApocalypseStorage(root)
        val id = UUID.randomUUID()
        Files.writeString(root.resolve("data/apocalypse/players/$id.json"),
            """{"playerId":"$id","originServer":"spawn","phase":"PARKED","returnPending":true}""")
        val previous = requireNotNull(store.load(id))
        previous.returnTicket shouldBe null
        val next = previous.copy(returnTicket = UUID.randomUUID().toString())
        store.save(next) shouldBe next
        ApocalypseStorage(root).load(id) shouldBe next
    }

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
        apocalypseJoinRecovery(null, false, null, null, inExpeditionWorld = true) shouldBe ApocalypseJoinRecovery.QUARANTINE
        apocalypseJoinRecovery(ApocalypsePlayerPhase.PARKED, true, null, null, inExpeditionWorld = true) shouldBe ApocalypseJoinRecovery.ORIGIN
    }
})
