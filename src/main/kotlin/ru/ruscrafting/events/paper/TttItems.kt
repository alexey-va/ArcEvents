package ru.ruscrafting.events.paper

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.TextDecoration
import org.bukkit.Color
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemFlag
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.LeatherArmorMeta
import org.bukkit.persistence.PersistentDataType
import org.bukkit.plugin.Plugin
import ru.ruscrafting.events.config.ArcEventsConfig
import ru.ruscrafting.events.config.ArcEventsLocale
import ru.ruscrafting.events.domain.FishingGear
import ru.ruscrafting.events.domain.TttRole

enum class EventItemKind {
    FISHING_ROD,
    FISHING_WEAPON,
    FISHING_DYNAMITE,
    FISHING_LIVE_CATCH,
    FISHING_CATCH_BAG,
    ARCADE_KNIFE,
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
    private val settings: () -> ArcEventsConfig,
) : EventItemResolver {
    private val itemKindKey = NamespacedKey(plugin, "event_item")
    private val matchIdKey = NamespacedKey(plugin, "match_id")

    val traitorOffers: List<ShopOffer>
        get() = settings().gameplay.let { gameplay ->
            listOf(
                ShopOffer(EventItemKind.TRAITOR_BLADE, Material.IRON_SWORD, gameplay.traitorBladeCost, "menu.shop.traitor-blade-name", "menu.shop.traitor-blade-lore"),
                ShopOffer(EventItemKind.TRAITOR_RADAR, Material.COMPASS, gameplay.traitorRadarCost, "menu.shop.traitor-radar-name", "menu.shop.traitor-radar-lore"),
                ShopOffer(EventItemKind.TRAITOR_SMOKE, Material.FIREWORK_STAR, gameplay.traitorSmokeCost, "menu.shop.traitor-smoke-name", "menu.shop.traitor-smoke-lore"),
            )
        }
    val detectiveOffers: List<ShopOffer>
        get() = settings().gameplay.let { gameplay ->
            listOf(
                ShopOffer(EventItemKind.DETECTIVE_SCANNER, Material.COMPASS, gameplay.detectiveScannerCost, "menu.shop.detective-scanner-name", "menu.shop.detective-scanner-lore"),
                ShopOffer(EventItemKind.DETECTIVE_MEDKIT, Material.GOLDEN_APPLE, gameplay.detectiveMedkitCost, "menu.shop.detective-medkit-name", "menu.shop.detective-medkit-lore"),
                ShopOffer(EventItemKind.DETECTIVE_ARMOR, Material.IRON_CHESTPLATE, gameplay.detectiveArmorCost, "menu.shop.detective-armor-name", "menu.shop.detective-armor-lore"),
            )
        }

    fun givePreparationLoadout(player: Player, matchId: String) {
        clearForEvent(player)
        player.inventory.setItem(0, simple(Material.IRON_SWORD, locale.render("loadout.blade", player)))
        val rations = settings().gameplay.preparationRations
        if (rations > 0) player.inventory.setItem(1, simple(Material.COOKED_BEEF, locale.render("loadout.rations", player), rations))
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

    fun clearForEvent(player: Player): Boolean {
        if (!settings().gameplay.clearInventory) return false
        player.inventory.clear()
        player.inventory.armorContents = arrayOfNulls(4)
        player.inventory.setItemInOffHand(ItemStack.empty())
        player.setItemOnCursor(ItemStack.empty())
        player.inventory.heldItemSlot = 0
        player.updateInventory()
        return true
    }

    fun revealRoleLoadout(player: Player, role: TttRole, matchId: String) {
        if (role != TttRole.INNOCENT) {
            player.inventory.setItem(8, tagged(
                Material.NETHER_STAR,
                EventItemKind.SHOP,
                matchId,
                locale.render("menu.event.shop-name", player),
                locale.lore("menu.event.shop-lore", player),
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
        return offerItem(offer, player, matchId)
    }

    fun arcadeKnife(player: Player, matchId: String): ItemStack = tagged(
        Material.IRON_SWORD, EventItemKind.ARCADE_KNIFE, matchId,
        locale.render("arcade.knife-name", player), locale.lore("arcade.knife-lore", player),
    )

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

    fun fishingRod(player: Player, matchId: String, level: Int = 0): ItemStack = tagged(
        Material.FISHING_ROD, EventItemKind.FISHING_ROD, matchId,
        locale.render("fishing.rod-name", player),
        locale.lore("fishing.rod-lore", player, mapOf("rod-level" to locale.text(level + 1))),
    ).also { it.editMeta { meta ->
        meta.isUnbreakable = true
        meta.addItemFlags(ItemFlag.HIDE_UNBREAKABLE)
    } }

    fun fishingWeapon(player: Player, matchId: String, gear: FishingGear): ItemStack {
        require(gear.firearmId == null) { "Firearm gear must use TttFirearms.firearmItem" }
        val material = when (gear) {
            FishingGear.KNUCKLES -> Material.FLINT
            FishingGear.KNIFE -> Material.STONE_SWORD
            FishingGear.MACHETE -> Material.IRON_SWORD
            else -> error("Unsupported melee gear: $gear")
        }
        return tagged(
            material,
            EventItemKind.FISHING_WEAPON,
            matchId,
            locale.render("fishing.gear.${gear.name.lowercase()}-name", player),
            locale.lore("fishing.melee-lore", player, mapOf(
                "damage" to locale.text(gear.meleeDamage),
                "cooldown" to locale.text(gear.cooldownMillis / 1_000.0),
            )),
        ).also { it.editMeta { meta ->
            meta.isUnbreakable = true
            meta.addItemFlags(ItemFlag.HIDE_UNBREAKABLE)
        } }
    }

    /** Compatibility for the original three-stage melee loadout. */
    fun fishingWeapon(player: Player, matchId: String, stage: Int): ItemStack {
        require(stage in 0..2)
        return fishingWeapon(player, matchId, listOf(FishingGear.KNUCKLES, FishingGear.KNIFE, FishingGear.MACHETE)[stage])
    }

    fun fishingDynamite(player: Player, matchId: String, amount: Int): ItemStack = tagged(
        Material.TNT,
        EventItemKind.FISHING_DYNAMITE,
        matchId,
        locale.render("fishing.dynamite-name", player),
        locale.lore("fishing.dynamite-lore", player),
    ).also { it.amount = amount.coerceIn(1, 64) }

    fun fishingLiveCatch(player: Player, matchId: String, species: Component): ItemStack = tagged(
        Material.TROPICAL_FISH,
        EventItemKind.FISHING_LIVE_CATCH,
        matchId,
        locale.render("fishing.live-catch-name", player, mapOf("species" to species)),
        locale.lore("fishing.live-catch-lore", player),
    )

    fun fishingCatchBag(player: Player, matchId: String, count: Int, species: Component, value: Int): ItemStack = tagged(
        Material.COD,
        EventItemKind.FISHING_CATCH_BAG,
        matchId,
        locale.render("fishing.catch-bag-name", player, mapOf("count" to locale.text(count), "species" to species)),
        locale.lore("fishing.catch-bag-lore", player, mapOf("value" to locale.text(value))),
    ).also { it.amount = count.coerceIn(1, 64) }

    fun fishingLedger(player: Player, matchId: String): ItemStack = tagged(
        Material.COMPASS,
        EventItemKind.SHOP,
        matchId,
        locale.render("fishing.ledger-name", player),
        locale.lore("fishing.ledger-lore", player),
    )

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
