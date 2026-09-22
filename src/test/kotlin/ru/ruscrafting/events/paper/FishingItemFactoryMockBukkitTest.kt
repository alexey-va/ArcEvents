package ru.ruscrafting.events.paper

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.bukkit.Material
import ru.ruscrafting.events.domain.FishingGear
import ru.ruscrafting.events.domain.FirearmId

class FishingItemFactoryMockBukkitTest : FunSpec({
    test("fishing gear and supplies keep their kind and exact expedition match") {
        TttRoundFixture().use { fixture ->
            val player = fixture.players.first()
            val matchId = "fishing-items"

            listOf(
                FishingGear.KNUCKLES to Material.FLINT,
                FishingGear.KNIFE to Material.STONE_SWORD,
                FishingGear.MACHETE to Material.IRON_SWORD,
            ).forEach { (gear, material) ->
                val item = fixture.items.fishingWeapon(player, matchId, gear)
                item.type shouldBe material
                fixture.items.kind(item) shouldBe EventItemKind.FISHING_WEAPON
                fixture.items.belongsTo(item, matchId) shouldBe true
                fixture.items.belongsTo(item, "another-match") shouldBe false
            }

            val firearm = fixture.firearms.firearmItem(FirearmId.FIVE_SEVEN, player, matchId, loaded = 7)
            fixture.items.kind(firearm) shouldBe EventItemKind.FIREARM
            fixture.items.belongsTo(firearm, matchId) shouldBe true
            fixture.firearms.state(firearm)?.loaded shouldBe 7

            val rod = fixture.items.fishingRod(player, matchId, level = 1)
            val dynamite = fixture.items.fishingDynamite(player, matchId, amount = 2)
            val ledger = fixture.items.fishingLedger(player, matchId)
            rod.type shouldBe Material.FISHING_ROD
            dynamite.type shouldBe Material.TNT
            dynamite.amount shouldBe 2
            ledger.type shouldBe Material.COMPASS
            fixture.items.kind(rod) shouldBe EventItemKind.FISHING_ROD
            fixture.items.kind(dynamite) shouldBe EventItemKind.FISHING_DYNAMITE
            fixture.items.kind(ledger) shouldBe EventItemKind.SHOP
            listOf(rod, dynamite, ledger).all { fixture.items.belongsTo(it, matchId) } shouldBe true

            player.inventory.setItem(10, fixture.firearms.ammunition(player, matchId, 32))
            fixture.firearms.reserveAmmo(player, matchId) shouldBe 32
            fixture.firearms.reserveAmmo(player, "another-match") shouldBe 0
            fixture.firearms.consumeReserve(player, "another-match", 32) shouldBe 0
            fixture.firearms.consumeReserve(player, matchId, 12) shouldBe 12
            fixture.firearms.reserveAmmo(player, matchId) shouldBe 20
        }
    }
})
