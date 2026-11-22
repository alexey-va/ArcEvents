package ru.ruscrafting.events.paper.apocalypse

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import ru.ruscrafting.events.paper.EventWork
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.RejectedExecutionException

class ApocalypseCampaignAccessTest : StringSpec({
    "a visit is targeted, requires the owner, expires after five minutes and is spent by entry" {
        withCampaignAccessRoot { root, work ->
            val owner = UUID.randomUUID()
            val visitor = UUID.randomUUID()
            val stranger = UUID.randomUUID()
            var now = 10_000L
            val access = ApocalypseCampaignAccess(root, work) { now }
            val preparing = access.prepare()
            work.io.removeFirst()()
            preparing.isDone shouldBe false
            access.invite(owner, visitor) shouldBe false
            access.canEnter(owner, visitor, ownerPresent = true) shouldBe false
            work.main.removeFirst()()
            preparing.join()

            access.canEnter(owner, owner, ownerPresent = false) shouldBe true
            access.invite(owner, owner) shouldBe false
            access.invite(owner, visitor) shouldBe true
            access.pendingInvites(visitor) shouldBe setOf(owner)
            access.pendingInvites(stranger) shouldBe emptySet()
            access.canEnter(owner, stranger, ownerPresent = true) shouldBe false
            access.canEnter(owner, visitor, ownerPresent = false) shouldBe false
            now += 299_999L
            access.canEnter(owner, visitor, ownerPresent = true) shouldBe true
            now++
            access.canEnter(owner, visitor, ownerPresent = true) shouldBe false
            access.pendingInvites(visitor) shouldBe emptySet()

            access.invite(owner, visitor) shouldBe true
            access.consumeInvite(owner, visitor)
            access.pendingInvites(visitor) shouldBe emptySet()
            access.canEnter(owner, visitor, ownerPresent = true) shouldBe false
        }
    }

    "guest invitations have per-owner and global capacity with room after use or expiration" {
        withCampaignAccessRoot { root, work ->
            val owners = List(16) { UUID.randomUUID() }
            val visitors = List(8) { UUID.randomUUID() }
            val extra = UUID.randomUUID()
            var now = 10_000L
            val access = ApocalypseCampaignAccess(root, work) { now }
            val preparing = access.prepare()
            work.drain()
            preparing.join()

            visitors.forEach { access.invite(owners.first(), it) shouldBe true }
            access.invite(owners.first(), extra) shouldBe false
            owners.drop(1).forEach { owner -> visitors.forEach { access.invite(owner, it) shouldBe true } }
            access.invite(extra, visitors.first()) shouldBe false
            access.invite(owners.first(), visitors.first()) shouldBe true
            access.consumeInvite(owners.first(), visitors.first())
            access.invite(extra, visitors.first()) shouldBe true
            now += 300_000L
            access.invite(owners.first(), extra) shouldBe true
        }
    }

    "queued companion changes publish after confirmation and survive a fresh startup" {
        withCampaignAccessRoot { root, work ->
            val owner = UUID.randomUUID()
            val first = UUID.randomUUID()
            val second = UUID.randomUUID()
            val visitor = UUID.randomUUID()
            val access = ApocalypseCampaignAccess(root, work)
            val preparing = access.prepare()
            work.drain()
            preparing.join()
            access.invite(owner, first) shouldBe true
            access.invite(owner, visitor) shouldBe true

            val grantFirst = access.setCompanion(owner, first, approved = true, ownerName = "OwnerName")
            val grantSecond = access.setCompanion(owner, second, approved = true)
            val revokeFirst = access.setCompanion(owner, first, approved = false)
            work.io.size shouldBe 1
            work.io.removeFirst()()
            work.io.size shouldBe 0
            grantFirst.isDone shouldBe false
            access.canEnter(owner, first, ownerPresent = false) shouldBe false
            access.companionOwnerName(owner) shouldBe null
            work.main.removeFirst()()
            grantFirst.join() shouldBe true
            access.canEnter(owner, first, ownerPresent = false) shouldBe true
            access.pendingInvites(first) shouldBe emptySet()
            work.drain()
            grantSecond.join() shouldBe true
            revokeFirst.join() shouldBe true
            access.companions(owner) shouldBe setOf(second)
            access.canEnter(owner, first, ownerPresent = true) shouldBe false

            val reopened = ApocalypseCampaignAccess(root, work)
            val reloading = reopened.prepare()
            reopened.canEnter(owner, second, ownerPresent = false) shouldBe false
            work.drain()
            reloading.join()
            reopened.companionCampaigns(second) shouldBe setOf(owner)
            reopened.companionOwnerName(owner) shouldBe "OwnerName"
            reopened.namedCompanionCampaign(second, "ownerNAME") shouldBe owner
            reopened.namedCompanionCampaign(first, "ownerNAME") shouldBe null
            reopened.canEnter(owner, second, ownerPresent = false) shouldBe true
            reopened.canEnter(owner, first, ownerPresent = false) shouldBe false
            reopened.pendingInvites(visitor) shouldBe emptySet()
        }
    }

    "legacy unnamed ACL records load and identical names only count within a visitor's ACL" {
        withCampaignAccessRoot { root, work ->
            val owner = UUID.randomUUID()
            val otherOwner = UUID.randomUUID()
            val visitor = UUID.randomUUID()
            val otherVisitor = UUID.randomUUID()
            val dir = Files.createDirectories(root.resolve("data/apocalypse/campaign-access"))
            Files.writeString(dir.resolve("$owner.json"), """{"owner":"$owner","companions":["$visitor"]}""")
            val access = ApocalypseCampaignAccess(root, work)
            val preparing = access.prepare()
            work.drain()
            preparing.join()
            access.canEnter(owner, visitor, ownerPresent = false) shouldBe true
            access.companionOwnerName(owner) shouldBe null

            val named = access.setCompanion(owner, visitor, approved = true, ownerName = ".Owner_Name")
            val unrelated = access.setCompanion(otherOwner, otherVisitor, approved = true, ownerName = ".OWNER_NAME")
            work.drain()
            named.join() shouldBe false
            unrelated.join() shouldBe true
            access.namedCompanionCampaign(visitor, ".owner_name") shouldBe owner

            val ambiguous = access.setCompanion(otherOwner, visitor, approved = true, ownerName = "<invalid>")
            work.drain()
            ambiguous.join() shouldBe true
            access.companionOwnerName(otherOwner) shouldBe ".OWNER_NAME"
            access.namedCompanionCampaign(visitor, ".owner_name") shouldBe null
            access.namedCompanionCampaign(otherVisitor, ".owner_name") shouldBe otherOwner
        }
    }

    "a rejected write releases the owner queue and a retired write leaves the roster intact" {
        withCampaignAccessRoot { root, work ->
            val owner = UUID.randomUUID()
            val first = UUID.randomUUID()
            val second = UUID.randomUUID()
            val access = ApocalypseCampaignAccess(root, work)
            val preparing = access.prepare()
            work.drain()
            preparing.join()

            work.rejectNext = true
            val rejected = access.setCompanion(owner, first, approved = true)
            val next = access.setCompanion(owner, second, approved = true)
            work.drain()
            rejected.isCompletedExceptionally shouldBe true
            next.join() shouldBe true
            access.companions(owner) shouldBe setOf(second)

            val retired = access.setCompanion(owner, first, approved = true)
            work.current = false
            work.drain()
            retired.isCompletedExceptionally shouldBe true
            val freshWork = CampaignAccessTestWork()
            val reopened = ApocalypseCampaignAccess(root, freshWork)
            val reloading = reopened.prepare()
            freshWork.drain()
            reloading.join()
            reopened.companions(owner) shouldBe setOf(second)
        }
    }
})

private fun withCampaignAccessRoot(test: (Path, CampaignAccessTestWork) -> Unit) {
    val root = Files.createTempDirectory("apocalypse-campaign-access-")
    try { test(root, CampaignAccessTestWork()) } finally { root.toFile().deleteRecursively() }
}

private class CampaignAccessTestWork : EventWork {
    val io = ArrayDeque<() -> Unit>()
    val main = ArrayDeque<() -> Unit>()
    var rejectNext = false
    var current = true

    override fun <T> compute(action: () -> T): CompletableFuture<T> {
        if (rejectNext) {
            rejectNext = false
            return CompletableFuture.failedFuture(RejectedExecutionException("I/O queue is full"))
        }
        return io.enqueue(action)
    }

    override fun <T> onMain(action: () -> T): CompletableFuture<T> =
        if (current) main.enqueue(action) else CompletableFuture.failedFuture(CancellationException("Test generation stopped"))
    override fun capture(isCurrent: () -> Boolean, step: () -> Boolean): CompletableFuture<Unit> = error("No native chunks")
    override fun isCurrent() = current

    fun drain() {
        while (io.isNotEmpty() || main.isNotEmpty()) {
            io.removeFirstOrNull()?.invoke()
            main.removeFirstOrNull()?.invoke()
        }
    }

    private fun <T> ArrayDeque<() -> Unit>.enqueue(action: () -> T): CompletableFuture<T> =
        CompletableFuture<T>().also { future ->
            addLast { runCatching(action).fold(future::complete, future::completeExceptionally) }
        }
}
