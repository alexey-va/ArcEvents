package ru.ruscrafting.events.paper.apocalypse

import org.bukkit.Bukkit
import org.bukkit.Color
import org.bukkit.Material
import org.bukkit.entity.Zombie
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.LeatherArmorMeta
import org.bukkit.inventory.meta.SkullMeta
import ru.ruscrafting.events.domain.apocalypse.ApocalypseStructureZombieProfile
import ru.ruscrafting.events.domain.apocalypse.ApocalypseZombieEquipment
import java.util.UUID

fun equipApocalypseStructureZombie(mob: Zombie, profile: ApocalypseStructureZombieProfile) {
    val equipment = mob.equipment
    equipment.boots = armorItem(profile.boots)
    equipment.leggings = armorItem(profile.leggings)
    equipment.chestplate = armorItem(profile.chestplate)
    equipment.helmet = armorItem(profile.helmet)
    // The expedition's defeat handler owns the source-specific drop rolls.
    equipment.bootsDropChance = 0f
    equipment.leggingsDropChance = 0f
    equipment.chestplateDropChance = 0f
    equipment.helmetDropChance = 0f
    mob.setVisualFire(profile.visualFire)
}

private fun armorItem(piece: ApocalypseZombieEquipment): ItemStack {
    // Marbled's Arsenal hazmat suit uses a custom model. Vanilla clients see yellow leather in its three slots.
    val hazmat = piece.itemId.startsWith("marbledsarsenal:hazmat_armor_")
    val material = when (piece.itemId) {
        "marbledsarsenal:hazmat_armor_leggings" -> Material.LEATHER_LEGGINGS
        "marbledsarsenal:hazmat_armor_chestplate" -> Material.LEATHER_CHESTPLATE
        "marbledsarsenal:hazmat_armor_helmet" -> Material.LEATHER_HELMET
        else -> requireNotNull(Material.matchMaterial(piece.itemId)) { "Unknown structure zombie armor: ${piece.itemId}" }
    }
    return ItemStack.of(material).also { item ->
        val color = piece.color ?: if (hazmat) 0xE5B92D else null
        if (color != null) item.editMeta(LeatherArmorMeta::class.java) { it.setColor(Color.fromRGB(color)) }
        piece.headOwner?.let { owner ->
            val offlineId = UUID.nameUUIDFromBytes("OfflinePlayer:$owner".toByteArray(Charsets.UTF_8))
            item.editMeta(SkullMeta::class.java) { it.ownerProfile = Bukkit.createPlayerProfile(offlineId, owner) }
        }
    }
}
