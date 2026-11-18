package ru.ruscrafting.events.paper

import com.github.retrooper.packetevents.protocol.component.ComponentTypes
import com.github.retrooper.packetevents.protocol.nbt.NBTCompound
import dev.lone.itemsadder.api.CustomStack
import io.github.retrooper.packetevents.util.SpigotConversionUtil
import org.bukkit.inventory.ItemStack
import org.bukkit.plugin.Plugin
import org.bukkit.NamespacedKey
import org.bukkit.persistence.PersistentDataType
import ru.ruscrafting.events.domain.FirearmId

internal const val SCOPE_PREVIEW_PERMISSION = "arcevents.scope-preview"

internal fun scopePreviewFirearm(id: String?): FirearmId? = packedFirearms[id]?.profile

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
    val nativeGunKey = NamespacedKey(plugin, "world_firearm")
    return { item ->
        val native = item.itemMeta?.persistentDataContainer?.get(nativeGunKey, PersistentDataType.STRING)
            ?.let { "arcevents:world_${it.lowercase()}" }?.takeIf { it in packedFirearms }
        // A loaded vendor item carries world_firearm too; its own identity still selects its model and magazine.
        val vendor = if (apiIdentity != null && plugin.server.pluginManager.isPluginEnabled("ItemsAdder")) apiIdentity(item)
            else runCatching { nativeScopePreviewItemIdentity(customData(item)) }.getOrNull()
        vendor ?: native
    }
}

/** ItemsAdder 4.0.18 stores these strings inside minecraft:custom_data. */
internal fun nativeScopePreviewItemIdentity(customData: NBTCompound?): String? {
    val identity = customData?.getCompoundTagOrNull("itemsadder") ?: return null
    val namespace = identity.getStringTagValueOrNull("namespace") ?: return null
    val id = identity.getStringTagValueOrNull("id") ?: return null
    return "$namespace:$id".takeIf { scopePreviewFirearm(it) != null || it == "arc:firearm_ammo" }
}

/** The always-loaded listener and service expose no optional ItemsAdder API types. */
private class ItemsAdderScopeIdentity {
    fun identity(item: ItemStack): String? {
        return runCatching { CustomStack.byItemStack(item)?.namespacedID }.getOrNull()
    }
}
