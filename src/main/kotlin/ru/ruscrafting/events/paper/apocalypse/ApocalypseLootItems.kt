package ru.ruscrafting.events.paper.apocalypse

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.TextDecoration
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer
import org.bukkit.*
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.CompassMeta
import org.bukkit.inventory.meta.Damageable
import org.bukkit.inventory.meta.PotionMeta
import org.bukkit.inventory.meta.EnchantmentStorageMeta
import org.bukkit.persistence.PersistentDataType
import org.bukkit.plugin.Plugin
import org.bukkit.potion.PotionType
import ru.ruscrafting.events.config.ArcEventsLocale
import ru.ruscrafting.events.domain.FirearmId
import ru.ruscrafting.events.domain.apocalypse.source.ApocalypseSourceLoot
import ru.ruscrafting.events.domain.apocalypse.source.ApocalypseSourceLootItem
import ru.ruscrafting.events.paper.TttFirearms
import java.util.Random

/** Explicit item equivalents for the existing native weapon/material catalog; source rolls stay untouched. */
internal class ApocalypseLootItems(
    plugin: Plugin,
    private val locale: ArcEventsLocale,
    private val firearms: TttFirearms,
) {
    private val medicine = NamespacedKey(plugin, "apocalypse_medicine")
    private val tool = NamespacedKey(plugin, "apocalypse_tool")

    fun create(source: ApocalypseSourceLootItem, player: Player, random: Random, mapTarget: Location? = null): List<ItemStack> {
        require(source.count in 1..4096)
        val gun = source.gunId ?: source.itemId.takeIf(weapons::containsKey)
        val result = when {
            gun != null -> List(source.count) {
                val id = requireNotNull(weapons[gun]) { "Unmapped original firearm $gun" }
                val loaded = integerTag(source.nbt, "GunCurrentAmmoCount")?.coerceIn(0, firearms.spec(id).magazineSize) ?: 0
                firearms.worldFirearmItem(id, player, loaded)
            }
            source.ammoId != null || source.itemId in pointBlankAmmo -> split(source.count, 64) { firearms.worldAmmunition(player, it) }
            source.attachmentId != null -> split(source.count, 64) { ItemStack.of(Material.IRON_NUGGET, it) }
            source.itemId == "cure:vaccine" -> split(source.count, Material.HONEY_BOTTLE.maxStackSize) { amount ->
                ItemStack.of(Material.HONEY_BOTTLE, amount).also { stack -> stack.editMeta {
                    it.displayName(locale.render("apocalypse.vaccine", player).decoration(TextDecoration.ITALIC, false))
                    it.persistentDataContainer.set(medicine, PersistentDataType.BYTE, 1.toByte())
                } }
            }
            source.explorationMap != null && mapTarget != null -> List(source.count) {
                ItemStack.of(Material.COMPASS).also { stack -> stack.editMeta(CompassMeta::class.java) {
                    it.lodestone = mapTarget; it.isLodestoneTracked = false
                } }
            }
            else -> {
                val material = material(source.itemId)
                split(source.count, material.maxStackSize) { ItemStack.of(material, it) }
            }
        }
        return result.map { original ->
            var item = original
            val potion = potionTypes[source.itemId] ?: stringTag(source.nbt, "Potion")?.substringAfter(':')
                ?.uppercase()?.let { runCatching { PotionType.valueOf(it) }.getOrNull() }
            if (potion != null && item.itemMeta is PotionMeta) item.editMeta(PotionMeta::class.java) { it.basePotionType = potion }
            integerTag(source.nbt, "Damage")?.let { wear ->
                if (item.itemMeta is Damageable) item.editMeta(Damageable::class.java) { it.damage = wear.coerceIn(0, item.type.maxDurability.toInt()) }
            }
            source.nbt?.let { tag ->
                val entries = Regex("\"?(?:StoredEnchantments|Enchantments)\"?\\s*:\\s*\\[([^]]*)]").find(tag)?.groupValues?.get(1)
                Regex("\\{([^{}]*)}").findAll(entries.orEmpty()).forEach { entry ->
                    val id = stringTag(entry.value, "id") ?: return@forEach
                    // Native compatibility: this runtime has no Combat Roll charges/enchantment.
                    val nativeId = when (id) {
                        "minecraft:sweeping" -> "minecraft:sweeping_edge"
                        "combatroll:multi_roll" -> "minecraft:unbreaking"
                        else -> id
                    }
                    val key = NamespacedKey.fromString(nativeId)
                    val enchantment = key?.let(org.bukkit.Registry.ENCHANTMENT::get) ?: error("Unmapped source enchantment $id")
                    val level = integerTag(entry.value, "lvl") ?: return@forEach
                    if (item.itemMeta is EnchantmentStorageMeta) item.editMeta(EnchantmentStorageMeta::class.java) { it.addStoredEnchant(enchantment, level, true) }
                    else item.addUnsafeEnchantment(enchantment, level)
                }
            }
            source.enchantLevels?.let { levels -> item = item.enchantWithLevels(levels.coerceIn(1, 100), source.enchantTreasure, random) }
            item.editMeta { meta ->
                if (source.itemId == "marbledsmelees:crowbar") {
                    meta.persistentDataContainer.set(tool, PersistentDataType.STRING, "CROWBAR")
                    meta.displayName(locale.render("apocalypse.guide.crowbar-name", player).decoration(TextDecoration.ITALIC, false))
                }
                source.displayName?.let { meta.displayName(component(it)) }
                if (source.lore.isNotEmpty()) meta.lore(source.lore.map(::component))
            }
            item
        }
    }

    companion object {
        private val weapons = buildMap<String, FirearmId> {
            fun add(profile: FirearmId, ids: String) { ids.split(' ').filter(String::isNotBlank).forEach { require(put(it, profile) == null) } }
            add(FirearmId.REVOLVER, "bf1:sw_model3 pointblank:rhino")
            add(FirearmId.FIVE_SEVEN, "bf1:m1916 tacz:m1911 tacz:glock_17 tacz:p320 pointblank:glock17 pointblank:glock18 pointblank:m1911a1 pointblank:m9 pointblank:mk23 pointblank:p30l pointblank:tti_viper")
            add(FirearmId.HAND_CANNON, "bf1:mhgl tacz:deagle pointblank:deserteagle pointblank:at4 pointblank:javelin pointblank:smaw pointblank:m32mgl")
            add(FirearmId.DOUBLE_BARREL, "tacz:db_long tacz:db_short pointblank:citoricxs pointblank:hs12")
            add(FirearmId.M1_GARAND, "bf1:man_m95 bf1:martini bf1:obrez bf1:rsc1917 pointblank:mk14ebr pointblank:m950")
            add(FirearmId.G36, "bf1:smg0818 tacz:aug tacz:g36k tacz:hk_mp5a5 tacz:m16a1 tacz:m4a1 tacz:p90 tacz:scar_h tacz:scar_l tacz:uzi tacz:vector45 pointblank:aug pointblank:g36c pointblank:g36k pointblank:g41 pointblank:hk416 pointblank:m16a1 pointblank:m4a1 pointblank:m4a1mod1 pointblank:m4sopmodii pointblank:mp5 pointblank:mp7 pointblank:p90 pointblank:ro635 pointblank:scarl pointblank:star15 pointblank:tmp pointblank:uar10 pointblank:ump45 pointblank:vector pointblank:xm29 pointblank:xm7")
            add(FirearmId.AEK_971, "bf1:vg15 tacz:ak47 pointblank:ak12 pointblank:ak47 pointblank:ak74 pointblank:an94")
            add(FirearmId.RPL_20, "tacz:m249 tacz:rpk pointblank:aughbar pointblank:lamg pointblank:m134minigun pointblank:m249 pointblank:mk48")
            add(FirearmId.VEPR_12, "pointblank:aa12 pointblank:m1014 pointblank:m590 pointblank:m870 pointblank:spas12")
            add(FirearmId.MCMILLAN, "tacz:m95 pointblank:ballista pointblank:c14 pointblank:gm6lynx pointblank:l96a1 pointblank:sl8 pointblank:wa2000 pointblank:xm3")
        }
        private val pointBlankAmmo = setOf("ammo12gauge", "ammo338lapua", "ammo357", "ammo45acp", "ammo46", "ammo50ae", "ammo50bmg", "ammo545", "ammo556", "ammo57", "ammo68", "ammo762", "ammo762x51", "ammo9mm", "at4_rocket", "javelin_rocket", "smaw_rocket", "grenade20mm", "grenade40mm").map { "pointblank:$it" }.toSet()
        private val replacements = buildMap<String, Material> {
            fun add(material: Material, ids: String) { ids.split(' ').filter(String::isNotBlank).forEach { require(put(it, material) == null) } }
            add(Material.IRON_INGOT, "create:andesite_alloy create:iron_sheet create:sturdy_sheet createdeco:industrial_iron_ingot createdeco:netherite_sheet mcore:steel_ingot mcore:steel_scrap mcore:steel_sheet mcore:titanium_ingot the_deeper_void:rhenium")
            add(Material.COPPER_INGOT, "create:brass_ingot create:brass_sheet create:copper_sheet create:zinc_ingot the_deeper_void:zinc_crystal")
            add(Material.COPPER_BLOCK, "create:brass_casing")
            add(Material.BLAZE_POWDER, "create:blaze_cake")
            add(Material.BLAST_FURNACE, "create:empty_blaze_burner")
            add(Material.CHEST, "create:item_vault")
            add(Material.PISTON, "create:mechanical_arm")
            add(Material.IRON_HOE, "create:mechanical_harvester")
            add(Material.CAULDRON, "create:mechanical_mixer")
            add(Material.ANVIL, "create:mechanical_press")
            add(Material.IRON_AXE, "create:mechanical_saw")
            add(Material.HOPPER, "create:smart_chute")
            add(Material.SLIME_BALL, "create:super_glue")
            add(Material.WHEAT_SEEDS, "farmersdelight:cabbage_seeds")
            add(Material.BEETROOT_SEEDS, "farmersdelight:tomato_seeds")
            add(Material.STRING, "farmersdelight:rope")
            add(Material.BUNDLE, "sophisticatedbackpacks:backpack")
            add(Material.HOPPER, "sophisticatedbackpacks:pickup_upgrade")
            add(Material.QUARTZ, "table_top_craft:chess_piece_figure")
            add(Material.IRON_NUGGET, "mcore:steel_nugget")
            add(Material.IRON_BARS, "create:fluid_pipe create:shaft")
            add(Material.LEATHER, "create:belt_connector rotten_flesh_to_leather:dried_flesh the_deep_void:mourner_hide")
            add(Material.PAPER, "create:cardboard create:empty_schematic tacz:attachment")
            add(Material.HOPPER, "create:chute")
            add(Material.IRON_PICKAXE, "create:crushing_wheel create:mechanical_drill mcore:steel_pickaxe")
            add(Material.DROPPER, "create:deployer create:spout")
            add(Material.BUCKET, "create:fluid_tank")
            add(Material.PISTON, "create:mechanical_crafter create:steam_engine")
            add(Material.AMETHYST_SHARD, "create:polished_rose_quartz create:rose_quartz the_deep_void:lavendite the_deeper_void:razorback_crystal the_deeper_void:void_crystal")
            add(Material.OBSIDIAN, "create:powdered_obsidian")
            add(Material.REDSTONE, "create:precision_mechanism")
            add(Material.CRAFTING_TABLE, "create:schematic_table")
            add(Material.GOLD_NUGGET, "create:cogwheel")
            add(Material.IRON_AXE, "create:wrench marbledsmelees:fire_axe marbledsmelees:modern_axe marbledsmelees:pipe_wrench")
            add(Material.WHEAT, "create:wheat_flour farmersdelight:raw_pasta farmersdelight:rice")
            add(Material.COOKIE, "create:bar_of_chocolate")
            add(Material.COAL, "create_netherless:coal_rod")
            add(Material.FERN, "cure:fox_weed_paste")
            add(Material.STONE_SWORD, "farmersdelight:flint_knife marbledsmelees:baseball_bat marbledsmelees:police_baton")
            add(Material.IRON_SWORD, "farmersdelight:iron_knife marbledsmelees:barbed_baseball_bat marbledsmelees:bone_saw marbledsmelees:crowbar marbledsmelees:katana marbledsmelees:machete marbledsmelees:steel_baseball_bat marbledsmelees:stop_sign marbledsmelees:tanto")
            add(Material.IRON_AXE, "marbledsmelees:sledgehammer")
            add(Material.ROTTEN_FLESH, "farmersdelight:rotten_tomato the_deep_void:bone_mush")
            add(Material.COCOA_BEANS, "herbalbrews:coffee_beans")
            add(Material.DRIED_KELP, "herbalbrews:dried_black_tea herbalbrews:dried_green_tea herbalbrews:dried_oolong_tea")
            add(Material.MOSS_CARPET, "immersive_weathering:moss_clump the_deep_void:rotten_grass")
            add(Material.BARREL, "jeffs_cursed_walking_structures:ammo_box the_deeper_void:sealed_box")
            add(Material.POTION, "kubejs:adrenaline_shot kubejs:morphine_shot kubejs:steriod_shot marbledsfirstaid:bandages marbledsfirstaid:bandaid marbledsfirstaid:tonic")
            add(Material.STRING, "marbledsfirstaid:cloth")
            add(Material.IRON_SHOVEL, "mcore:steel_shovel")
            add(Material.RAW_IRON, "mcore:raw_titanium the_deeper_void:raw_tungsten")
            add(Material.GUNPOWDER, "powderedup:saltpeter powderedup:sulfur_dust")
            add(Material.BONE_MEAL, "the_deep_void:bone_marrow")
            add(Material.BONE, "the_deep_void:rotten_bone")
            add(Material.QUARTZ, "the_deep_void:onyx the_deeper_void:titanium_crystal the_deeper_void:tungsten_crystal")
            add(Material.SENTRY_ARMOR_TRIM_SMITHING_TEMPLATE, "the_deep_void:scavenger_armor_trim")
            add(Material.NETHERITE_UPGRADE_SMITHING_TEMPLATE, "the_deep_void:smithing_template_onyx")
            add(Material.DIAMOND_PICKAXE, "the_deeper_void:tungsten_pickaxe")
            add(Material.LEATHER_HELMET, "marbledsarsenal:black_gp5_gas_mask marbledsarsenal:white_gp5_gas_mask marbledsarsenal:hazmat_armor_helmet marbledsarsenal:helmet_cm6m_gas_mask marbledsarsenal:helmet_cm7m_gas_mask marbledsarsenal:helmet_cm8m_gas_mask")
            add(Material.LEATHER_CHESTPLATE, "marbledsarsenal:hazmat_armor_chestplate")
            add(Material.LEATHER_BOOTS, "marbledsarsenal:hazmat_armor_boots")
            add(Material.IRON_HELMET, "marbledsarsenal:combat_helmet marbledsarsenal:olive_combat_helmet marbledsarsenal:un_combat_helmet marbledsarsenal:desert_military_armor_helmet marbledsarsenal:winter_military_armor_helmet marbledsarsenal:riot_armor_helmet marbledsarsenal:swat_armor_helmet")
            add(Material.IRON_CHESTPLATE, "marbledsarsenal:black_plate_carrier_light marbledsarsenal:olive_plate_carrier_light marbledsarsenal:desert_military_armor_chestplate marbledsarsenal:winter_military_armor_chestplate marbledsarsenal:riot_armor_chestplate marbledsarsenal:swat_armor_chestplate")
            add(Material.IRON_LEGGINGS, "marbledsarsenal:desert_military_armor_leggings marbledsarsenal:winter_military_armor_leggings marbledsarsenal:riot_armor_leggings marbledsarsenal:swat_armor_leggings")
            add(Material.IRON_BOOTS, "marbledsarsenal:desert_military_armor_boots marbledsarsenal:winter_military_armor_boots marbledsarsenal:riot_armor_boots marbledsarsenal:swat_armor_boots")
        }
        private val potionTypes = mapOf("kubejs:adrenaline_shot" to PotionType.SWIFTNESS, "kubejs:morphine_shot" to PotionType.HEALING,
            "kubejs:steriod_shot" to PotionType.STRENGTH, "marbledsfirstaid:bandages" to PotionType.HEALING,
            "marbledsfirstaid:bandaid" to PotionType.HEALING, "marbledsfirstaid:tonic" to PotionType.REGENERATION)
        internal fun nativeWeapon(source: String): FirearmId = requireNotNull(weapons[source]) { "Unmapped source firearm $source" }
        internal fun isCrowbar(item: ItemStack?, plugin: Plugin): Boolean = item != null && !item.isEmpty &&
            item.itemMeta.persistentDataContainer.get(NamespacedKey(plugin, "apocalypse_tool"), PersistentDataType.STRING) == "CROWBAR"
        internal fun material(source: String): Material = (if (source.startsWith("minecraft:")) Material.matchMaterial(source) else replacements[source])
            ?.takeIf { it.isItem && it != Material.AIR } ?: error("Unmapped original loot item $source")
        internal fun validateCatalog(catalog: ApocalypseSourceLoot) {
            catalog.referencedGuns.forEach(::nativeWeapon)
            catalog.referencedItems.forEach { id ->
                if (id !in weapons && id !in pointBlankAmmo && id !in setOf("tacz:modern_kinetic_gun", "tacz:ammo", "cure:vaccine")) material(id)
            }
        }
        private fun split(amount: Int, maximum: Int, factory: (Int) -> ItemStack): List<ItemStack> = buildList {
            var left = amount
            while (left > 0) { val count = minOf(left, maximum); add(factory(count)); left -= count }
        }
        private fun component(value: String): Component = runCatching { GsonComponentSerializer.gson().deserialize(value) }
            .getOrElse { Component.text(value) }.decoration(TextDecoration.ITALIC, false)
        private fun integerTag(nbt: String?, key: String): Int? = nbt?.let { Regex("(?:^|[,{])\\s*\"?${Regex.escape(key)}\"?\\s*:\\s*(-?\\d+)").findAll(it).lastOrNull()?.groupValues?.get(1)?.toIntOrNull() }
        private fun stringTag(nbt: String?, key: String): String? = nbt?.let { Regex("(?:^|[,{])\\s*\"?${Regex.escape(key)}\"?\\s*:\\s*\"([^\"]+)\"").findAll(it).lastOrNull()?.groupValues?.get(1) }
    }
}
