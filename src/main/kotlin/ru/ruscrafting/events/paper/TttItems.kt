package ru.ruscrafting.events.paper

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.TextDecoration
import org.bukkit.Color
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.enchantments.Enchantment
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemFlag
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.LeatherArmorMeta
import org.bukkit.persistence.PersistentDataType
import org.bukkit.plugin.Plugin
import ru.ruscrafting.events.config.ArcEventsLocale
import ru.ruscrafting.events.domain.TttRole

enum class EventItemKind {
    GUIDE,
    SHOP,
    FIREARM,
    AMMUNITION,
    ROUND_REPORT,
    TRAITOR_BLADE,
    TRAITOR_RADAR,
    TRAITOR_SMOKE,
    DETECTIVE_SCANNER,
    DETECTIVE_MEDKIT,
    DETECTIVE_ARMOR,
}

data class ShopOffer(
    val kind: EventItemKind,
    val material: Material,
    val cost: Int,
    val nameKey: String,
    val loreKey: String,
)

fun interface EventItemResolver {
    fun kind(item: ItemStack?): EventItemKind?
}

class TttItems(
    plugin: Plugin,
    private val locale: ArcEventsLocale,
) : EventItemResolver {
    private val itemKindKey = NamespacedKey(plugin, "event_item")
    private val matchIdKey = NamespacedKey(plugin, "match_id")

    val traitorOffers = listOf(
        ShopOffer(EventItemKind.TRAITOR_BLADE, Material.NETHERITE_SWORD, 2, "menu.shop.traitor-blade-name", "menu.shop.traitor-blade-lore"),
        ShopOffer(EventItemKind.TRAITOR_RADAR, Material.COMPASS, 1, "menu.shop.traitor-radar-name", "menu.shop.traitor-radar-lore"),
        ShopOffer(EventItemKind.TRAITOR_SMOKE, Material.FIREWORK_STAR, 1, "menu.shop.traitor-smoke-name", "menu.shop.traitor-smoke-lore"),
    )
    val detectiveOffers = listOf(
        ShopOffer(EventItemKind.DETECTIVE_SCANNER, Material.COMPASS, 1, "menu.shop.detective-scanner-name", "menu.shop.detective-scanner-lore"),
        ShopOffer(EventItemKind.DETECTIVE_MEDKIT, Material.GOLDEN_APPLE, 1, "menu.shop.detective-medkit-name", "menu.shop.detective-medkit-lore"),
        ShopOffer(EventItemKind.DETECTIVE_ARMOR, Material.IRON_CHESTPLATE, 1, "menu.shop.detective-armor-name", "menu.shop.detective-armor-lore"),
    )

    fun givePreparationLoadout(player: Player, matchId: String) {
        player.inventory.clear()
        player.inventory.armorContents = arrayOfNulls(4)
        player.inventory.setItemInOffHand(ItemStack.empty())
        player.setItemOnCursor(ItemStack.empty())
        player.inventory.setItem(0, simple(Material.IRON_SWORD, locale.render("loadout.blade", player)))
        player.inventory.setItem(1, simple(Material.COOKED_BEEF, locale.render("loadout.rations", player), 4))
        player.inventory.setItem(8, tagged(
            Material.WRITTEN_BOOK,
            EventItemKind.GUIDE,
            matchId,
            locale.render("guide.item-name", player),
            locale.lore("guide.item-lore", player),
        ))
        player.inventory.heldItemSlot = 0
        player.updateInventory()
    }

    fun revealRoleLoadout(player: Player, role: TttRole, matchId: String) {
        if (role != TttRole.INNOCENT) {
            player.inventory.setItem(8, tagged(
                Material.NETHER_STAR,
                EventItemKind.SHOP,
                matchId,
                locale.render("menu.main.shop-name", player),
                locale.lore("menu.main.shop-lore", player),
            ))
        }
        if (role == TttRole.DETECTIVE) {
            val chestplate = ItemStack.of(Material.LEATHER_CHESTPLATE)
            chestplate.editMeta(LeatherArmorMeta::class.java) { meta ->
                meta.setColor(Color.fromRGB(72, 156, 235))
                meta.displayName(nonItalic(locale.render("role.detective-name", player)))
                meta.addItemFlags(ItemFlag.HIDE_DYE)
            }
            player.inventory.chestplate = chestplate
        }
        player.updateInventory()
    }

    fun offerItem(offer: ShopOffer, player: Player, matchId: String): ItemStack = tagged(
        offer.material,
        offer.kind,
        matchId,
        locale.render(offer.nameKey, player),
        locale.lore(offer.loreKey, player),
    )

    fun purchasedItem(kind: EventItemKind, player: Player, matchId: String): ItemStack {
        val offer = (traitorOffers + detectiveOffers).first { it.kind == kind }
        val item = offerItem(offer, player, matchId)
        if (kind == EventItemKind.TRAITOR_BLADE) {
            item.addUnsafeEnchantment(Enchantment.SHARPNESS, 3)
            item.editMeta { it.addItemFlags(ItemFlag.HIDE_ENCHANTS) }
        }
        return item
    }

    fun roundReport(player: Player, matchId: String): ItemStack = tagged(
        Material.WRITTEN_BOOK,
        EventItemKind.ROUND_REPORT,
        matchId,
        locale.render("report.item-name", player),
        locale.lore("report.item-lore", player),
    )

    override fun kind(item: ItemStack?): EventItemKind? {
        if (item == null || item.isEmpty) return null
        val raw = item.itemMeta.persistentDataContainer.get(itemKindKey, PersistentDataType.STRING) ?: return null
        return runCatching { EventItemKind.valueOf(raw) }.getOrNull()
    }

    fun belongsTo(item: ItemStack?, matchId: String): Boolean = item?.takeUnless(ItemStack::isEmpty)?.itemMeta
        ?.persistentDataContainer?.get(matchIdKey, PersistentDataType.STRING) == matchId

    private fun tagged(
        material: Material,
        kind: EventItemKind,
        matchId: String,
        name: Component,
        lore: List<Component>,
    ): ItemStack = ItemStack.of(material).also { item ->
        item.editMeta { meta ->
            meta.displayName(nonItalic(name))
            meta.lore(lore.map(::nonItalic))
            meta.persistentDataContainer.set(itemKindKey, PersistentDataType.STRING, kind.name)
            meta.persistentDataContainer.set(matchIdKey, PersistentDataType.STRING, matchId)
            meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES)
        }
    }

    private fun simple(material: Material, name: Component, amount: Int = 1): ItemStack = ItemStack.of(material, amount).also { item ->
        item.editMeta { meta -> meta.displayName(nonItalic(name)); meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES) }
    }

    companion object {
        fun nonItalic(component: Component): Component = component.decoration(TextDecoration.ITALIC, false)
    }
}
