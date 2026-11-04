package ru.ruscrafting.events.paper

import ru.ruscrafting.events.domain.FirearmId
import ru.ruscrafting.events.domain.FirearmSpec
import ru.ruscrafting.events.domain.FirearmProjectile

/** Vendor identities stay separate from the twelve event progression/animation profiles. */
internal data class PackedFirearm(
    val profile: FirearmId,
    val animated: Boolean = false,
    val magazine: Int? = null,
    val damage: Double? = null,
    val range: Double? = null,
    val cooldown: Int? = null,
    val reload: Int? = null,
    val rounds: Int? = null,
    val pellets: Int? = null,
    val projectile: FirearmProjectile = FirearmProjectile.BULLET,
) {
    fun spec(base: FirearmSpec): FirearmSpec = base.copy(
        magazineSize = magazine ?: base.magazineSize,
        damagePerPellet = damage ?: base.damagePerPellet,
        range = range ?: base.range,
        cooldownTicks = cooldown ?: base.cooldownTicks,
        reloadTicks = reload ?: base.reloadTicks,
        roundsPerShot = rounds ?: if (animated) base.roundsPerShot else 1,
        pellets = pellets ?: if (animated || profile == FirearmId.DOUBLE_BARREL || profile == FirearmId.VEPR_12) base.pellets else 1,
        projectile = projectile,
    ).validated()
}

internal val packedFirearms: Map<String, PackedFirearm> = buildMap {
    fun voxel(id: String, gun: PackedFirearm) { put("voxelspawns_megaflintlocks:$id", gun) }
    fun gold(id: String, gun: PackedFirearm) { put("gold_guns:$id", gun) }
    voxel("vs_pistol", PackedFirearm(FirearmId.FLINTLOCK, animated = true))
    voxel("vs_revolver", PackedFirearm(FirearmId.REVOLVER, animated = true))
    voxel("vs_cannon", PackedFirearm(FirearmId.HAND_CANNON, animated = true))
    voxel("vs_rifle_double", PackedFirearm(FirearmId.DOUBLE_BARREL, animated = true))
    voxel("vs_pistol3", PackedFirearm(FirearmId.REVOLVER, magazine = 3, cooldown = 12, reload = 42))
    voxel("vs_pistol_multi", PackedFirearm(FirearmId.REVOLVER, magazine = 4, damage = 6.0,
        cooldown = 18, reload = 50, rounds = 4, pellets = 4))
    for (id in listOf("vs_rifle", "vs_rifle_bayonet", "vs_rifle_3")) {
        voxel(id, PackedFirearm(FirearmId.M1_GARAND, magazine = 1, damage = 13.0, range = 80.0, cooldown = 24, reload = 48))
    }
    voxel("vs_rifle_epic", PackedFirearm(FirearmId.M1_GARAND, magazine = 2, damage = 14.0, range = 90.0, cooldown = 22, reload = 52))
    for (id in listOf("vs_rifle_magic", "vs_rifle_magic2", "vs_rifle_magic3", "vs_rifle_magic4")) {
        voxel(id, PackedFirearm(FirearmId.M1_GARAND, magazine = 4, damage = 11.0, range = 85.0, cooldown = 18, reload = 48))
    }
    for (id in listOf("vs_rifle_alchemy", "vs_rifle_alchemy2", "vs_rifle_alchemy3", "vs_rifle_alchemy4", "vs_rifle_alchemy5")) {
        voxel(id, PackedFirearm(FirearmId.HAND_CANNON, magazine = 3, damage = 12.0, range = 55.0, cooldown = 24, reload = 54))
    }
    voxel("vs_snipe", PackedFirearm(FirearmId.MCMILLAN, magazine = 1, reload = 60))
    voxel("vs_snipe_epic", PackedFirearm(FirearmId.MCMILLAN, magazine = 3, reload = 64))
    gold("gold_five_seven", PackedFirearm(FirearmId.FIVE_SEVEN, animated = true))
    gold("gold_g36", PackedFirearm(FirearmId.G36, animated = true))
    gold("gold_aek_971", PackedFirearm(FirearmId.AEK_971, animated = true))
    gold("gold_rpl20", PackedFirearm(FirearmId.RPL_20, animated = true))
    gold("gold_vepr_12", PackedFirearm(FirearmId.VEPR_12, animated = true))
    gold("gold_m1_garand", PackedFirearm(FirearmId.M1_GARAND, animated = true))
    gold("gold_vss_vintorez", PackedFirearm(FirearmId.VSS_VINTOREZ, animated = true))
    gold("gold_mcmillan_new", PackedFirearm(FirearmId.MCMILLAN, animated = true))
    gold("gold_beretta_arx200", PackedFirearm(FirearmId.G36, magazine = 20, damage = 7.0, range = 72.0,
        cooldown = 8, reload = 42, rounds = 1, pellets = 1))
    gold("gold_g3a3", PackedFirearm(FirearmId.M1_GARAND, magazine = 20, damage = 8.0, range = 80.0, cooldown = 9, reload = 44))
    gold("gold_toz_34", PackedFirearm(FirearmId.DOUBLE_BARREL))
    gold("gold_vsk", PackedFirearm(FirearmId.VSS_VINTOREZ, magazine = 20, damage = 7.0, range = 70.0, cooldown = 7))
    gold("gold_m79", PackedFirearm(FirearmId.HAND_CANNON, magazine = 1, range = 64.0, reload = 54, projectile = FirearmProjectile.GRENADE))
    gold("gold_rg_6", PackedFirearm(FirearmId.HAND_CANNON, magazine = 6, damage = 12.0, range = 55.0, cooldown = 24, reload = 70, projectile = FirearmProjectile.GRENADE))
    gold("gold_rpg_7", PackedFirearm(FirearmId.HAND_CANNON, magazine = 1, damage = 20.0, range = 100.0, cooldown = 40, reload = 80, projectile = FirearmProjectile.ROCKET))
}

internal fun packedScopeTarget(identity: String, slot: Int): FirearmScopeTarget? = packedFirearms[identity]?.let {
    FirearmScopeTarget(null, it.profile, slot, identity.takeUnless { _ -> it.animated })
}
