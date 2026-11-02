package ru.ruscrafting.events.paper

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import net.kyori.adventure.text.Component
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType
import ru.arc.core.PaperArcRuntime
import ru.arc.core.Tasks
import ru.arc.paper.testing.MockBukkitTestRuntime

class FirearmItemsTest : FunSpec({
    test("native limit splits old guns and preserves every magazine and unrelated item") {
        MockBukkitTestRuntime.open().use {
            val gun = fixtureStackedGun(4)
            val ammunition = ItemStack.of(Material.IRON_NUGGET, 32)
            val original = arrayOfNulls<ItemStack>(41).also { items -> items[0] = gun; items[1] = ammunition }
            val result = requireNotNull(normalizeFirearmItems(original) { it.type == Material.PAPER })
            val guns = result.filterNotNull().filter { it.type == Material.PAPER }
            guns.size shouldBe 4
            guns.sumOf(ItemStack::getAmount) shouldBe 4
            guns.forEach { single ->
                single.maxStackSize shouldBe 1
                single.itemMeta.persistentDataContainer.get(LOADED_FIXTURE_KEY, PersistentDataType.INTEGER) shouldBe 7
                single.itemMeta.displayName() shouldBe gun.itemMeta.displayName()
            }
            result[1] shouldBe ammunition
            original[0]!!.amount shouldBe 4
            normalizeFirearmItems(result) { item -> item.type == Material.PAPER } shouldBe null
        }
    }

    test("a full inventory preserves legacy overflow and resumes only into storage space") {
        MockBukkitTestRuntime.open().use {
            val original = Array<ItemStack?>(41) { ItemStack.of(Material.STONE) }
            original[0] = fixtureStackedGun(4)
            // An empty armour slot is never used for migration.
            original[36] = null
            normalizeFirearmItems(original) { it.type == Material.PAPER } shouldBe null
            original[9] = null
            val partial = requireNotNull(normalizeFirearmItems(original) { it.type == Material.PAPER })
            partial[0]!!.amount shouldBe 3
            partial[9]!!.amount shouldBe 1
            partial[9]!!.maxStackSize shouldBe 1
            partial[36] shouldBe null
            partial.filterNotNull().filter { it.type == Material.PAPER }.sumOf(ItemStack::getAmount) shouldBe 4
        }
    }

    test("coalesced reconciliation reads current slots and close cancels pending mutation") {
        MockBukkitTestRuntime.open().use { paper ->
            val plugin = paper.createSimplePlugin("FirearmItemsTest")
            PaperArcRuntime.installScheduling(plugin)
            try {
                val player = paper.addPlayer("Collector")
                val policy = FirearmItems({ it.type == Material.PAPER })
                player.inventory.setItem(0, fixtureStackedGun(3))
                policy.refreshLater(player)
                policy.refreshLater(player)
                // Native inventory actions may complete between event and callback.
                player.inventory.setItem(0, fixtureStackedGun(2))
                paper.performTicks(2)
                player.inventory.contents.filterNotNull().filter { it.type == Material.PAPER }
                    .sumOf(ItemStack::getAmount) shouldBe 2
                player.inventory.getItem(0)!!.maxStackSize shouldBe 1
                player.inventory.clear()
                player.setItemOnCursor(fixtureStackedGun(2))
                policy.refreshLater(player)
                paper.performTicks(2)
                player.itemOnCursor.amount shouldBe 1
                player.itemOnCursor.maxStackSize shouldBe 1
                player.inventory.contents.filterNotNull().filter { it.type == Material.PAPER }
                    .sumOf(ItemStack::getAmount) shouldBe 1
                player.inventory.setItem(0, fixtureStackedGun(3))
                policy.refreshLater(player)
                policy.close()
                paper.performTicks(2)
                player.inventory.getItem(0)!!.amount shouldBe 3
            } finally {
                Tasks.reset()
            }
        }
    }

    test("escrow admission is rechecked before the scheduled inventory mutation") {
        MockBukkitTestRuntime.open().use { paper ->
            PaperArcRuntime.installScheduling(paper.createSimplePlugin("FirearmEscrowTest"))
            try {
                val player = paper.addPlayer("Recovering")
                var allowed = true
                FirearmItems({ it.type == Material.PAPER }, { allowed }).use { policy ->
                    val original = fixtureStackedGun(2)
                    player.inventory.setItem(0, original)
                    policy.refreshLater(player)
                    allowed = false
                    paper.performTicks(2)
                    player.inventory.getItem(0) shouldBe original
                    (player.inventory.getItem(1)?.isEmpty != false) shouldBe true
                    allowed = true
                    policy.refreshLater(player)
                    paper.performTicks(2)
                    player.inventory.getItem(0)!!.amount shouldBe 1
                    player.inventory.getItem(1)!!.amount shouldBe 1
                    player.inventory.getItem(0)!!.maxStackSize shouldBe 1
                }
            } finally {
                Tasks.reset()
            }
        }
    }
})

private val LOADED_FIXTURE_KEY = requireNotNull(NamespacedKey.fromString("arcevents:world_loaded_rounds"))

private fun fixtureStackedGun(amount: Int) = ItemStack.of(Material.PAPER, amount).also {
    it.editMeta { meta ->
        meta.displayName(Component.text("Existing gun"))
        meta.persistentDataContainer.set(LOADED_FIXTURE_KEY, PersistentDataType.INTEGER, 7)
    }
}
