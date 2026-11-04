package ru.ruscrafting.events.paper

import com.github.retrooper.packetevents.protocol.nbt.NBTCompound
import com.github.retrooper.packetevents.protocol.nbt.NBTInt
import com.github.retrooper.packetevents.protocol.nbt.NBTString
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import org.bukkit.Material
import org.bukkit.inventory.ItemStack
import ru.arc.paper.testing.MockBukkitTestRuntime
import ru.ruscrafting.events.domain.FirearmId
import org.yaml.snakeyaml.Yaml

class ScopePreviewItemsTest : StringSpec({
    "preview recognizes all thirty-six vendor firearms with distinct native identities" {
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
        ids.forEach { (id, firearm) ->
            scopePreviewFirearm(id) shouldBe firearm
            nativeScopePreviewItemIdentity(itemsAdderCustomData(id.substringBefore(':'), id.substringAfter(':'))) shouldBe id
        }
        ids.values.toSet() shouldBe FirearmId.entries.toSet()
        packedFirearms.size shouldBe 36
        packedFirearms.forEach { (identity, gun) ->
            scopePreviewFirearm(identity) shouldBe gun.profile
            nativeScopePreviewItemIdentity(itemsAdderCustomData(identity.substringBefore(':'), identity.substringAfter(':'))) shouldBe identity
            gun.spec(ru.ruscrafting.events.domain.TttFirearmCatalog.specs.getValue(gun.profile)).validated()
            packedScopeTarget(identity, 0)?.nativeItemId shouldBe identity.takeUnless { gun.animated }
        }
        listOf(null, "gold_guns:not_a_gun", "voxelspawns_megaflintlocks:missing", "gold_g36", " gold_guns:gold_g36", "GOLD_GUNS:GOLD_G36")
            .forEach { scopePreviewFirearm(it) shouldBe null }
    }

    "valid operator burst profiles cannot invalidate a smaller packed magazine" {
        packedFirearms.forEach { (_, gun) ->
            val base = ru.ruscrafting.events.domain.TttFirearmCatalog.specs.getValue(gun.profile)
                .copy(magazineSize = 64, roundsPerShot = 2, pellets = 2).validated()
            val derived = gun.spec(base)
            (derived.roundsPerShot <= derived.magazineSize) shouldBe true
            if (!gun.animated && gun.rounds == null) derived.roundsPerShot shouldBe 1
        }
    }

    "native preview rejects missing malformed wrong-type and unsupported item identities" {
        listOf(null, NBTCompound(), NBTCompound().also { it.setTag("itemsadder", NBTString("gold_guns:gold_g36")) },
            NBTCompound().also { it.setTag("namespace", NBTString("gold_guns")); it.setTag("id", NBTString("gold_g36")) },
        ).forEach { nativeScopePreviewItemIdentity(it) shouldBe null }
        listOf("namespace", "id").forEach { key ->
            val missing = itemsAdderCustomData("gold_guns", "gold_g36")
            missing.getCompoundTagOrThrow("itemsadder").removeTag(key)
            nativeScopePreviewItemIdentity(missing) shouldBe null
            val wrongType = itemsAdderCustomData("gold_guns", "gold_g36")
            wrongType.getCompoundTagOrThrow("itemsadder").setTag(key, NBTInt(1))
            nativeScopePreviewItemIdentity(wrongType) shouldBe null
        }
        listOf("" to "gold_g36", "gold_guns" to "", "other" to "gold_g36",
            "gold_guns" to "missing", "GOLD_GUNS" to "gold_g36", "gold_guns" to " gold_g36",
        ).forEach { (namespace, id) -> nativeScopePreviewItemIdentity(itemsAdderCustomData(namespace, id)) shouldBe null }
    }

    "optional ItemsAdder absence uses native identity and leaves listener discovery usable" {
        MockBukkitTestRuntime.open().use { paper ->
            val plugin = paper.createSimplePlugin("ScopePreviewWithoutItemsAdder")
            val item = ItemStack.of(Material.PAPER)
            scopePreviewItemIdentity(plugin) { null }(item) shouldBe null
            val original = item.clone()
            val customData = itemsAdderCustomData("gold_guns", "gold_g36")
            scopePreviewItemIdentity(plugin) { customData }(item) shouldBe "gold_guns:gold_g36"
            item shouldBe original
            // Also cover a plugin present by name without its optional API classes.
            paper.createSimplePlugin("ItemsAdder")
            scopePreviewItemIdentity(plugin) { customData }(item) shouldBe "gold_guns:gold_g36"
            scopePreviewItemIdentity(plugin) { error("Unavailable native converter") }(item) shouldBe null
            ArcEventsListener::class.java.declaredMethods.isNotEmpty() shouldBe true
        }
    }

    "cosmetic scope preview is available to ordinary players and declares its optional provider" {
        val descriptor = requireNotNull(ScopePreviewItemsTest::class.java.getResourceAsStream("/plugin.yml")).use {
            Yaml().load<Map<String, Any>>(it)
        }
        val permissions = descriptor["permissions"] as Map<*, *>
        (permissions[SCOPE_PREVIEW_PERMISSION] as Map<*, *>)["default"] shouldBe true
        (descriptor["softdepend"] as List<*>).contains("ItemsAdder") shouldBe true
    }
})

private fun itemsAdderCustomData(namespace: String, id: String) = NBTCompound().also { data ->
    data.setTag("itemsadder", NBTCompound().also { identity ->
        identity.setTag("namespace", NBTString(namespace))
        identity.setTag("id", NBTString(id))
    })
}
