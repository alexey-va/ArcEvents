package ru.ruscrafting.events.paper

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import ru.ruscrafting.events.domain.EventMode

class DebugBootstrapArgumentsTest : StringSpec({
    val arenas: (EventMode) -> List<String> = { mode -> listOf("auto", if (mode == EventMode.TTT) "inferno" else mode.id) }
    "zombies mode is not mistaken for a player name or a TTT arena" {
        parseDebugBootstrap(listOf("zombies", "P1"), arenas) shouldBe DebugBootstrapArguments(EventMode.ZOMBIES, null, listOf("P1"))
        parseDebugBootstrap(listOf("zombies", "zombies", "P1", "P2"), arenas) shouldBe DebugBootstrapArguments(EventMode.ZOMBIES, "zombies", listOf("P1", "P2"))
        parseDebugBootstrap(listOf("FISHING"), arenas) shouldBe DebugBootstrapArguments(EventMode.FISHING, null, emptyList())
    }
    "legacy bootstrap syntax preserves optional arena and explicit player lists" {
        parseDebugBootstrap(listOf("inferno", "P1"), arenas) shouldBe DebugBootstrapArguments(EventMode.TTT, "inferno", listOf("P1"))
        parseDebugBootstrap(listOf("P1", "P2"), arenas) shouldBe DebugBootstrapArguments(EventMode.TTT, null, listOf("P1", "P2"))
        parseDebugBootstrap(emptyList(), arenas) shouldBe DebugBootstrapArguments(EventMode.TTT, null, emptyList())
    }
})
