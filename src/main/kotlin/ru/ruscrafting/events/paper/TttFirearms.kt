package ru.ruscrafting.events.paper

import net.kyori.adventure.text.Component
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemFlag
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType
import org.bukkit.plugin.Plugin
import ru.ruscrafting.events.config.ArcEventsConfig
import ru.ruscrafting.events.config.ArcEventsLocale
import ru.ruscrafting.events.config.FirearmVisualSettings
import ru.ruscrafting.events.domain.FirearmId
import ru.ruscrafting.events.domain.FirearmRarity
import ru.ruscrafting.events.domain.FirearmSpec
import ru.ruscrafting.events.domain.TttFirearmCatalog

data class FirearmState(val id: FirearmId, val loaded: Int, val matchId: String)

class TttFirearms(
    plugin: Plugin,
    private val locale: ArcEventsLocale,
    private val settings: () -> ArcEventsConfig,
) {
    private val itemKindKey = NamespacedKey(plugin, "event_item")
    private val matchIdKey = NamespacedKey(plugin, "match_id")
    private val firearmKey = NamespacedKey(plugin, "firearm")
    private val loadedKey = NamespacedKey(plugin, "loaded_rounds")

    fun spec(id: FirearmId): FirearmSpec = requireNotNull(TttFirearmCatalog.specs[id])

    fun firearmItem(id: FirearmId, player: Player?, matchId: String, loaded: Int = spec(id).magazineSize): ItemStack {
        val spec = spec(id)
        val visual = visual(id)
        val material = Material.matchMaterial(visual.material)?.takeIf(Material::isItem) ?: fallback(id)
        return ItemStack.of(material).also { item ->
            item.editMeta { meta ->
                meta.displayName(TttItems.nonItalic(locale.render("weapon.${id.name.lowercase()}-name", player)))
                meta.lore(locale.lore("weapon.firearm-lore", player, values(spec, loaded, player)).map(TttItems::nonItalic))
                meta.persistentDataContainer.set(itemKindKey, PersistentDataType.STRING, EventItemKind.FIREARM.name)
                meta.persistentDataContainer.set(matchIdKey, PersistentDataType.STRING, matchId)
                meta.persistentDataContainer.set(firearmKey, PersistentDataType.STRING, id.name)
                meta.persistentDataContainer.set(loadedKey, PersistentDataType.INTEGER, loaded.coerceIn(0, spec.magazineSize))
                meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES, ItemFlag.HIDE_UNBREAKABLE)
                meta.isUnbreakable = true
                if (visual.customModelData > 0) {
                    val model = meta.customModelDataComponent
                    model.floats = listOf(visual.customModelData.toFloat())
                    meta.setCustomModelDataComponent(model)
                }
            }
        }
    }

    fun ammunition(player: Player?, matchId: String, amount: Int): ItemStack = ItemStack.of(Material.IRON_NUGGET, amount.coerceIn(1, 64)).also { item ->
        item.editMeta { meta ->
            meta.displayName(TttItems.nonItalic(locale.render("weapon.ammunition-name", player)))
            meta.lore(locale.lore("weapon.ammunition-lore", player, mapOf("rounds" to locale.text(item.amount))).map(TttItems::nonItalic))
            meta.persistentDataContainer.set(itemKindKey, PersistentDataType.STRING, EventItemKind.AMMUNITION.name)
            meta.persistentDataContainer.set(matchIdKey, PersistentDataType.STRING, matchId)
        }
    }

    fun state(item: ItemStack?): FirearmState? {
        if (item == null || item.isEmpty) return null
        val pdc = item.itemMeta.persistentDataContainer
        if (pdc.get(itemKindKey, PersistentDataType.STRING) != EventItemKind.FIREARM.name) return null
        val id = pdc.get(firearmKey, PersistentDataType.STRING)?.let { runCatching { FirearmId.valueOf(it) }.getOrNull() } ?: return null
        val matchId = pdc.get(matchIdKey, PersistentDataType.STRING) ?: return null
        val loaded = pdc.get(loadedKey, PersistentDataType.INTEGER) ?: return null
        if (loaded !in 0..spec(id).magazineSize) return null
        return FirearmState(id, loaded, matchId)
    }

    fun updateLoaded(item: ItemStack, player: Player?, loaded: Int): ItemStack {
        val current = requireNotNull(state(item))
        val replacement = firearmItem(current.id, player, current.matchId, loaded)
        replacement.amount = item.amount
        return replacement
    }

    fun reserveAmmo(player: Player, matchId: String): Int = player.inventory.storageContents.sumOf { stack ->
        if (ammunitionFor(stack, matchId)) stack?.amount ?: 0 else 0
    }

    fun consumeReserve(player: Player, matchId: String, requested: Int): Int {
        var remaining = requested.coerceAtLeast(0)
        var consumed = 0
        val contents = player.inventory.storageContents
        contents.indices.forEach { slot ->
            if (remaining == 0) return@forEach
            val stack = contents[slot] ?: return@forEach
            if (!ammunitionFor(stack, matchId)) return@forEach
            val take = minOf(stack.amount, remaining)
            stack.amount -= take
            if (stack.amount <= 0) contents[slot] = null
            remaining -= take
            consumed += take
        }
        player.inventory.storageContents = contents
        return consumed
    }

    fun isLoot(item: ItemStack?, matchId: String): Boolean {
        if (item == null || item.isEmpty) return false
        val pdc = item.itemMeta.persistentDataContainer
        return pdc.get(matchIdKey, PersistentDataType.STRING) == matchId &&
            pdc.get(itemKindKey, PersistentDataType.STRING) in setOf(EventItemKind.FIREARM.name, EventItemKind.AMMUNITION.name)
    }

    fun lootRarity(item: ItemStack?): FirearmRarity? {
        if (item == null || item.isEmpty) return null
        return when (item.itemMeta.persistentDataContainer.get(itemKindKey, PersistentDataType.STRING)) {
            EventItemKind.AMMUNITION.name -> FirearmRarity.COMMON
            EventItemKind.FIREARM.name -> state(item)?.let { spec(it.id).rarity }
            else -> null
        }
    }

    private fun ammunitionFor(item: ItemStack?, matchId: String): Boolean {
        if (item == null || item.isEmpty) return false
        val pdc = item.itemMeta.persistentDataContainer
        return pdc.get(itemKindKey, PersistentDataType.STRING) == EventItemKind.AMMUNITION.name &&
            pdc.get(matchIdKey, PersistentDataType.STRING) == matchId
    }

    private fun values(spec: FirearmSpec, loaded: Int, player: Player?): Map<String, Component> = mapOf(
        "loaded" to locale.text(loaded),
        "magazine" to locale.text(spec.magazineSize),
        "damage" to locale.text(if (spec.pellets == 1) spec.damagePerPellet else "${spec.pellets}×${spec.damagePerPellet}"),
        "range" to locale.text(spec.range.toInt()),
        "rarity" to locale.render("weapon.rarity.${spec.rarity.name.lowercase()}", player),
    )

    private fun visual(id: FirearmId): FirearmVisualSettings = settings().weapons.visual(id)

    private fun fallback(id: FirearmId): Material = when (id) {
        FirearmId.FLINTLOCK, FirearmId.REVOLVER -> Material.IRON_HORSE_ARMOR
        FirearmId.HAND_CANNON -> Material.BLAZE_ROD
        FirearmId.DOUBLE_BARREL, FirearmId.VEPR_12 -> Material.CROSSBOW
        FirearmId.FIVE_SEVEN -> Material.GOLDEN_HORSE_ARMOR
        FirearmId.G36, FirearmId.AEK_971, FirearmId.RPL_20 -> Material.NETHERITE_HOE
        FirearmId.M1_GARAND, FirearmId.VSS_VINTOREZ, FirearmId.MCMILLAN -> Material.NETHERITE_SHOVEL
    }
}
