package ru.ruscrafting.events.paper

import com.github.retrooper.packetevents.protocol.component.ComponentTypes
import com.github.retrooper.packetevents.protocol.nbt.NBTCompound
import dev.lone.itemsadder.api.CustomStack
import io.github.retrooper.packetevents.util.SpigotConversionUtil
import org.bukkit.inventory.ItemStack
import org.bukkit.plugin.Plugin
import ru.ruscrafting.events.domain.FirearmId

internal const val SCOPE_PREVIEW_PERMISSION = "arcevents.scope-preview"

internal fun scopePreviewFirearm(id: String?): FirearmId? = when (id) {
    "voxelspawns_megaflintlocks:vs_pistol" -> FirearmId.FLINTLOCK
    "voxelspawns_megaflintlocks:vs_revolver" -> FirearmId.REVOLVER
    "voxelspawns_megaflintlocks:vs_cannon" -> FirearmId.HAND_CANNON
    "voxelspawns_megaflintlocks:vs_rifle_double" -> FirearmId.DOUBLE_BARREL
    "gold_guns:gold_five_seven" -> FirearmId.FIVE_SEVEN
    "gold_guns:gold_g36" -> FirearmId.G36
    "gold_guns:gold_aek_971" -> FirearmId.AEK_971
    "gold_guns:gold_rpl20" -> FirearmId.RPL_20
    "gold_guns:gold_vepr_12" -> FirearmId.VEPR_12
    "gold_guns:gold_m1_garand" -> FirearmId.M1_GARAND
    "gold_guns:gold_vss_vintorez" -> FirearmId.VSS_VINTOREZ
    "gold_guns:gold_mcmillan_new" -> FirearmId.MCMILLAN
    else -> null
}

internal fun scopePreviewItemIdentity(
    plugin: Plugin,
    customData: (ItemStack) -> NBTCompound? = { item ->
        SpigotConversionUtil.fromBukkitItemStack(item).getComponent(ComponentTypes.CUSTOM_DATA).orElse(null)
    },
): (ItemStack) -> String? {
    val present = runCatching {
        Class.forName("dev.lone.itemsadder.api.CustomStack", false, plugin.javaClass.classLoader)
    }.isSuccess
    val apiIdentity = if (present) ItemsAdderScopeIdentity()::identity else null
    return { item ->
        if (apiIdentity != null && plugin.server.pluginManager.isPluginEnabled("ItemsAdder")) apiIdentity(item)
        else runCatching { nativeScopePreviewItemIdentity(customData(item)) }.getOrNull()
    }
}

/** ItemsAdder 4.0.18 stores these strings inside minecraft:custom_data. */
internal fun nativeScopePreviewItemIdentity(customData: NBTCompound?): String? {
    val identity = customData?.getCompoundTagOrNull("itemsadder") ?: return null
    val namespace = identity.getStringTagValueOrNull("namespace") ?: return null
    val id = identity.getStringTagValueOrNull("id") ?: return null
    return "$namespace:$id".takeIf { scopePreviewFirearm(it) != null }
}

/** The always-loaded listener and service expose no optional ItemsAdder API types. */
private class ItemsAdderScopeIdentity {
    fun identity(item: ItemStack): String? {
        return runCatching { CustomStack.byItemStack(item)?.namespacedID }.getOrNull()
    }
}
