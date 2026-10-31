package ru.ruscrafting.events.paper

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import org.bukkit.Material
import org.bukkit.inventory.ItemStack
import ru.arc.paper.testing.MockBukkitTestRuntime
import ru.ruscrafting.events.domain.FirearmId
import org.yaml.snakeyaml.Yaml

class ScopePreviewItemsTest : StringSpec({
    "preview recognizes exactly the twelve event visual item IDs and no other vendor weapons" {
        val ids = mapOf(
            "voxelspawns_megaflintlocks:vs_pistol" to FirearmId.FLINTLOCK,
            "voxelspawns_megaflintlocks:vs_revolver" to FirearmId.REVOLVER,
            "voxelspawns_megaflintlocks:vs_cannon" to FirearmId.HAND_CANNON,
            "voxelspawns_megaflintlocks:vs_rifle_double" to FirearmId.DOUBLE_BARREL,
            "gold_guns:gold_five_seven" to FirearmId.FIVE_SEVEN,
            "gold_guns:gold_g36" to FirearmId.G36,
            "gold_guns:gold_aek_971" to FirearmId.AEK_971,
            "gold_guns:gold_rpl20" to FirearmId.RPL_20,
            "gold_guns:gold_vepr_12" to FirearmId.VEPR_12,
            "gold_guns:gold_m1_garand" to FirearmId.M1_GARAND,
            "gold_guns:gold_vss_vintorez" to FirearmId.VSS_VINTOREZ,
            "gold_guns:gold_mcmillan_new" to FirearmId.MCMILLAN,
        )
        ids.forEach { (id, firearm) -> scopePreviewFirearm(id) shouldBe firearm }
        ids.values.toSet() shouldBe FirearmId.entries.toSet()
        listOf(null, "gold_guns:gold_m79", "gold_guns:gold_rpg_7", "gold_guns:gold_beretta_arx200",
            "voxelspawns_megaflintlocks:vs_pistol3", "gold_g36", " gold_guns:gold_g36", "GOLD_GUNS:GOLD_G36")
            .forEach { scopePreviewFirearm(it) shouldBe null }
    }

    "optional ItemsAdder absence leaves preview lookup and listener discovery usable" {
        MockBukkitTestRuntime.open().use { paper ->
            val plugin = paper.createSimplePlugin("ScopePreviewWithoutItemsAdder")
            val item = ItemStack.of(Material.PAPER)
            scopePreviewItemIdentity(plugin)(item) shouldBe null
            // Also cover a plugin present by name without its optional API classes.
            paper.createSimplePlugin("ItemsAdder")
            scopePreviewItemIdentity(plugin)(item) shouldBe null
            ArcEventsListener::class.java.declaredMethods.isNotEmpty() shouldBe true
        }
    }

    "scope preview permission defaults to OP and declares its optional provider" {
        val descriptor = requireNotNull(ScopePreviewItemsTest::class.java.getResourceAsStream("/plugin.yml")).use {
            Yaml().load<Map<String, Any>>(it)
        }
        val permissions = descriptor["permissions"] as Map<*, *>
        (permissions[SCOPE_PREVIEW_PERMISSION] as Map<*, *>)["default"] shouldBe "op"
        (descriptor["softdepend"] as List<*>).contains("ItemsAdder") shouldBe true
    }
})
