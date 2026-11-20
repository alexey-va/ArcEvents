package ru.ruscrafting.events.paper.apocalypse

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import net.kyori.adventure.text.Component
import org.bukkit.command.Command
import org.bukkit.command.CommandSender
import ru.arc.paper.playerstate.PaperPlayerStateCodec
import ru.arc.paper.playerstate.PaperPlayerStateService
import ru.arc.paper.testing.MockBukkitTestRuntime
import ru.ruscrafting.events.config.ArcEventsLocale
import ru.ruscrafting.events.paper.ArcEventsCommand
import ru.ruscrafting.events.paper.EventWork
import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.CompletableFuture

class ApocalypseWorldResetPlanTest : StringSpec({
    "only canonical unused names can select a new generation" {
        listOf("../world", "arcevents_x/../world", "arcevents_A", "arcevents_" + "x".repeat(49)).forEach {
            ApocalypseWorldResetPlan.targetProblem(OLD, it) shouldBe "invalid_world_name"
        }
        ApocalypseWorldResetPlan.targetProblem(OLD, OLD) shouldBe "current_world"
        ApocalypseWorldResetPlan.targetProblem(OLD, "arcevents_apocalypse_v1") shouldBe "legacy_world_alias"
        ApocalypseWorldResetPlan.targetProblem(OLD, NEXT) shouldBe null

        ResetFixture().use { f ->
            f.report() shouldContain "arc_ready=true"
            Files.exists(f.root.resolve(NEXT)) shouldBe false
            Files.exists(f.journals.resolve("world-$NEXT")) shouldBe false
            Files.createSymbolicLink(f.root.resolve(NEXT), f.root.resolve("missing"))
            Files.createDirectory(f.journals.resolve("world-$NEXT"))
            f.report() shouldContain "blockers=target_directory,target_checkpoint"
            Files.writeString(f.root.resolve("$OLD/.arcevents-template"), "other-generator")
            f.report() shouldContain "blockers=source_marker"
            Files.move(f.journals, f.root.resolve("elsewhere"))
            Files.createSymbolicLink(f.journals, f.root.resolve("elsewhere"))
            f.report() shouldContain "blockers=unsafe_directory"
        }
    }

    "parked and offline native sessions keep their gear while both origin world references block retirement" {
        MockBukkitTestRuntime.open().use { paper ->
            paper.addSimpleWorld("world")
            val player = paper.addPlayer("PlanTester")
            val home = PaperPlayerStateService().capture(player, 1)
            val codec = PaperPlayerStateCodec()
            ResetFixture().use { f ->
                val old = home.location.copy(worldId = f.id, worldName = OLD)
                val gear = codec.encode(home.copy(location = old, compassTarget = old))
                val ordinary = codec.encode(home)
                val record = ApocalypsePlayerRecord(player.uniqueId.toString(), "spawn", ApocalypsePlayerPhase.PARKED,
                    ordinary, gear, nativeSession = UUID.randomUUID().toString())
                val clean = f.report(listOf(record.copy(phase = ApocalypsePlayerPhase.ACTIVE)))
                clean shouldContain "arc_ready=true"
                clean shouldContain "active_records=1"
                f.report(listOf(record.copy(returnPending = true))) shouldContain "pending_returns=1"
                val receipt = record.copy(origin = codec.encode(home.copy(
                    location = home.location.copy(worldId = f.id), compassTarget = home.compassTarget.copy(worldName = OLD))))
                val blocked = f.report(listOf(receipt))
                blocked shouldContain "arc_ready=false"
                blocked shouldContain "origin_locations=1 origin_compasses=1"
                blocked shouldContain "affected_sample=${player.uniqueId} blockers=origin_references_world"
                f.report(listOf(record.copy(origin = ordinary.copy(sha256 = "0".repeat(64))))) shouldContain "blockers=unreadable_origin"
                f.report(occupants = 1, online = 1, busy = 1, checkpoint = true, registered = true, changed = true) shouldContain
                    "blockers=world_occupied,pending_work,target_registered,snapshot_changed"
            }
        }
    }

    "the host plan is a permission gated admin command available from the console" {
        val locale = mockk<ArcEventsLocale> { every { render(any(), any(), any()) } returns Component.empty() }
        val runtime = mockk<ApocalypseRuntime>()
        val cmd = ArcEventsCommand(mockk(), mockk(), mockk(), mockk(), locale, { mockk() },
            { error("unexpected reload") }, runtime)
        val sender = mockk<CommandSender>(relaxed = true)
        val command = mockk<Command>()
        every { sender.hasPermission("arcevents.play") } returns true
        val args = arrayOf("admin", "apocalypse-reset-plan", NEXT)
        cmd.onCommand(sender, command, "events", args) shouldBe true
        cmd.onTabComplete(sender, command, "events", arrayOf("admin", "apoc")) shouldBe emptyList()
        verify(exactly = 0) { runtime.planWorldReset(any(), any()) }
        every { sender.hasPermission("arcevents.admin") } returns true
        every { runtime.planWorldReset(NEXT, any()) } answers { secondArg<(String) -> Unit>().invoke("plan") }
        cmd.onTabComplete(sender, command, "events", arrayOf("admin", "apoc")) shouldBe listOf("apocalypse-reset-plan")
        cmd.onCommand(sender, command, "events", args + "extra") shouldBe true
        verify(exactly = 0) { runtime.planWorldReset(any(), any()) }
        cmd.onCommand(sender, command, "events", args) shouldBe true
        verify(exactly = 1) { sender.sendMessage(Component.text("plan")) }
    }
})

private const val OLD = "arcevents_apocalypse_v2"
private const val NEXT = "arcevents_apocalypse_s2"

private class ResetFixture : AutoCloseable {
    val id: UUID = UUID.randomUUID()
    val root = Files.createTempDirectory("apocalypse-reset-plan")
    private val plugin = root.resolve("plugins/ArcEvents")
    val journals = Files.createDirectories(plugin.resolve("data/apocalypse"))
    private val seed = 42L

    init {
        Files.createDirectory(root.resolve(OLD))
        Files.writeString(root.resolve("$OLD/.arcevents-template"), "${ApocalypseOriginalGenerator.TEMPLATE}:$seed")
    }

    fun report(records: List<ApocalypsePlayerRecord> = emptyList(), occupants: Int = 0, online: Int = 0, busy: Int = 0,
        checkpoint: Boolean = false, registered: Boolean = false, changed: Boolean = false): String {
        val plan = ApocalypseWorldResetPlan(OLD, id, seed, NEXT, records)
        plan.inspect(root, plugin, object : EventWork {
            override fun <T> compute(action: () -> T) =
                runCatching(action).fold(CompletableFuture<T>::completedFuture, CompletableFuture<T>::failedFuture)
            override fun <T> onMain(action: () -> T) = compute(action)
            override fun capture(isCurrent: () -> Boolean, step: () -> Boolean): CompletableFuture<Unit> =
                error("No native capture expected")
            override fun isCurrent() = true
        }).join()
        return plan.report(occupants, online, busy, checkpoint, registered, changed)
    }

    override fun close() {
        root.toFile().deleteRecursively()
    }
}
