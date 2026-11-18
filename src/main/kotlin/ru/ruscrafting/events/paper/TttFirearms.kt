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

data class FirearmState(val id: FirearmId, val loaded: Int, val matchId: String)

class TttFirearms(
    plugin: Plugin,
    private val locale: ArcEventsLocale,
    private val settings: () -> ArcEventsConfig,
) {
    val enabled: Boolean get() = settings().weapons.enabled

    private val itemKindKey = NamespacedKey(plugin, "event_item")
    private val matchIdKey = NamespacedKey(plugin, "match_id")
    private val firearmKey = NamespacedKey(plugin, "firearm")
    private val loadedKey = NamespacedKey(plugin, "loaded_rounds")
    private val worldAmmoKey = NamespacedKey(plugin, "world_ammunition")
    private val worldLoadedKey = NamespacedKey(plugin, "world_loaded_rounds")
    private val burstKey = NamespacedKey(plugin, "burst_size")
    private val damageKey = NamespacedKey(plugin, "item_damage")
    private val spreadKey = NamespacedKey(plugin, "item_spread")

    fun spec(id: FirearmId): FirearmSpec = settings().weapons.spec(id)

    internal fun selectedBurst(item: ItemStack, base: FirearmSpec): Int? =
        item.persistentDataContainer.get(burstKey, PersistentDataType.INTEGER)?.takeIf { it in 1..base.magazineSize }

    internal fun tunedSpec(item: ItemStack, base: FirearmSpec): FirearmSpec {
        val pdc = item.persistentDataContainer
        if (!pdc.has(damageKey) && !pdc.has(spreadKey)) return base
        return base.copy(
            damagePerPellet = pdc.get(damageKey, PersistentDataType.DOUBLE)?.takeIf { it in 0.1..40.0 } ?: base.damagePerPellet,
            spreadDegrees = pdc.get(spreadKey, PersistentDataType.DOUBLE)?.takeIf { it in 0.0..30.0 } ?: base.spreadDegrees,
        )
    }

    internal fun setBurst(item: ItemStack, base: FirearmSpec, size: Int?): ItemStack = item.clone().also { copy ->
        require(size == null || size in 1..base.magazineSize)
        copy.editMeta { meta ->
            if (size == null) meta.persistentDataContainer.remove(burstKey)
            else meta.persistentDataContainer.set(burstKey, PersistentDataType.INTEGER, size)
        }
    }

    internal fun setTuning(item: ItemStack, base: FirearmSpec, damage: Double, spread: Double): ItemStack = item.clone().also { copy ->
        require(damage in 0.1..40.0 && spread in 0.0..30.0)
        copy.editMeta { meta ->
            val pdc = meta.persistentDataContainer
            if (damage == base.damagePerPellet) pdc.remove(damageKey) else pdc.set(damageKey, PersistentDataType.DOUBLE, damage)
            if (spread == base.spreadDegrees) pdc.remove(spreadKey) else pdc.set(spreadKey, PersistentDataType.DOUBLE, spread)
        }
    }

    /** A vendor or source-loot item can own its name and lore. Replace only rows matching ArcEvents' old values. */
    internal fun refreshWorldLore(original: ItemStack, replacement: ItemStack, player: Player, base: FirearmSpec) {
        val lore = original.lore()?.takeIf { it.isNotEmpty() } ?: return
        val before = original.persistentDataContainer.get(worldLoadedKey, PersistentDataType.INTEGER) ?: return
        val after = replacement.persistentDataContainer.get(worldLoadedKey, PersistentDataType.INTEGER) ?: return
        fun rows(item: ItemStack, rounds: Int) = locale.lore("weapon.firearm-lore", player,
            values(tunedSpec(item, base), rounds, player)).map(TttItems::nonItalic)
        val changes = rows(original, before).zip(rows(replacement, after)).filter { (old, new) -> old != new }.toMap()
        if (lore.any(changes::containsKey)) replacement.lore(lore.map { changes[it] ?: it })
    }

    fun catalog(): Map<FirearmId, FirearmSpec> = settings().weapons.specs

    fun firearmItem(id: FirearmId, player: Player?, matchId: String, loaded: Int = spec(id).magazineSize): ItemStack {
        val spec = spec(id)
        val visual = visual(id)
        val material = Material.matchMaterial(visual.material)?.takeIf(Material::isItem) ?: fallback(id)
        return ItemStack.of(material).also { item ->
            item.editMeta { meta ->
                meta.setMaxStackSize(1)
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

    fun ammunition(player: Player?, matchId: String, amount: Int): ItemStack = ammoVisual(24802, amount).also { item ->
        item.editMeta { meta ->
            meta.displayName(TttItems.nonItalic(locale.render("weapon.ammunition-name", player)))
            meta.lore(locale.lore("weapon.ammunition-lore", player, mapOf("rounds" to locale.text(item.amount))).map(TttItems::nonItalic))
            meta.persistentDataContainer.set(itemKindKey, PersistentDataType.STRING, EventItemKind.AMMUNITION.name)
            meta.persistentDataContainer.set(matchIdKey, PersistentDataType.STRING, matchId)
        }
    }

    /** One item is one round; the normal crafting recipe exchanges one ordinary iron nugget. */
    fun worldAmmunition(player: Player?, amount: Int): ItemStack = ammoVisual(24801, amount).also { item ->
        item.editMeta { meta ->
            meta.displayName(TttItems.nonItalic(locale.render("weapon.world-ammunition-name", player)))
            meta.lore(locale.lore("weapon.world-ammunition-lore", player).map(TttItems::nonItalic))
            meta.persistentDataContainer.set(worldAmmoKey, PersistentDataType.BYTE, 1.toByte())
        }
    }

    /** Native ArcEvents gun; world combat uses the same profiles without an ItemsAdder identity. */
    fun worldFirearmItem(id: FirearmId, player: Player?, loaded: Int = spec(id).magazineSize): ItemStack =
        firearmItem(id, player, "world", loaded).also { item -> item.editMeta { meta ->
            meta.persistentDataContainer.remove(itemKindKey)
            meta.persistentDataContainer.remove(matchIdKey)
            meta.persistentDataContainer.remove(firearmKey)
            meta.persistentDataContainer.remove(loadedKey)
            meta.persistentDataContainer.set(NamespacedKey(itemKindKey.namespace, "world_firearm"), PersistentDataType.STRING, id.name)
            meta.persistentDataContainer.set(NamespacedKey(itemKindKey.namespace, "world_loaded_rounds"), PersistentDataType.INTEGER, loaded)
        } }

    internal fun isWorldAmmunition(item: ItemStack): Boolean =
        !item.isEmpty && !item.itemMeta.persistentDataContainer.has(itemKindKey) &&
            item.itemMeta.persistentDataContainer.get(worldAmmoKey, PersistentDataType.BYTE) == 1.toByte()

    private fun ammoVisual(modelId: Int, amount: Int): ItemStack = ItemStack.of(Material.PAPER, amount.coerceIn(1, 64)).also { item ->
        item.editMeta { meta ->
            val model = meta.customModelDataComponent
            model.floats = listOf(modelId.toFloat())
            meta.setCustomModelDataComponent(model)
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
        val spec = tunedSpec(item, spec(current.id))
        return item.clone().also { copy ->
            copy.editMeta { meta ->
                meta.persistentDataContainer.set(loadedKey, PersistentDataType.INTEGER, loaded.coerceIn(0, spec.magazineSize))
                meta.lore(locale.lore("weapon.firearm-lore", player, values(spec, loaded, player)).map(TttItems::nonItalic))
            }
        }
    }

    /** Re-renders operator-configured material/model/lore without changing authoritative weapon state. */
    fun refreshItem(item: ItemStack, player: Player?): ItemStack {
        if (item.isEmpty) return item
        state(item)?.let { current ->
            val replacement = firearmItem(current.id, player, current.matchId, current.loaded)
            replacement.amount = item.amount
            replacement.editMeta { meta -> item.itemMeta.persistentDataContainer.copyTo(meta.persistentDataContainer, false) }
            return updateLoaded(replacement, player, current.loaded)
        }
        val pdc = item.itemMeta.persistentDataContainer
        if (pdc.get(itemKindKey, PersistentDataType.STRING) == EventItemKind.AMMUNITION.name) {
            val matchId = pdc.get(matchIdKey, PersistentDataType.STRING) ?: return item
            return ammunition(player, matchId, item.amount)
        }
        return item
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
