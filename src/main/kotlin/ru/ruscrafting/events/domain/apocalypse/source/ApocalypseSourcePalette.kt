package ru.ruscrafting.events.domain.apocalypse.source

import ru.ruscrafting.events.domain.apocalypse.ApocalypseMarkerKind
import java.util.concurrent.ConcurrentHashMap

/** Vanilla block states stay in the original voxel and orientation; only unavailable registry IDs change material. */
object ApocalypseSourcePalette {
    private val horizontal = listOf("north", "east", "south", "west")
    private val cache = Array(4) { ConcurrentHashMap<String, String>() }
    private val cardinals = horizontal.toSet()
    private const val SKIP = ""
    internal fun direction(value: String, rotation: Int): String {
        val index = horizontal.indexOf(value)
        return if (index == -1) value else horizontal[(index + rotation) and 3]
    }

    fun vanillaState(state: String, rotation: Int = 0): String? {
        val mapped = cache[rotation and 3].computeIfAbsent(state) { convert(it, rotation and 3) }
        return mapped.takeUnless { it == SKIP }
    }
    private val copycatMaterial = Regex(""""Material":\{"Name":"minecraft:([a-z0-9_]+)"""")
    fun vanillaState(state: String, rotation: Int, blockNbt: String?): String? {
        if (blockNbt == null) return vanillaState(state, rotation)
        val material = copycatMaterial.find(blockNbt)?.groupValues?.get(1) ?: return vanillaState(state, rotation)
        val old = state.substringBefore('[').substringAfter(':')
        val form = when {
            old.endsWith("stairs") || old.endsWith("slope") -> "stairs"
            old.endsWith("slab") || old.endsWith("step") || old.endsWith("board") -> "slab"
            old.endsWith("wall") -> "wall"
            else -> return vanillaState(state, rotation)
        }
        val base = when (material) {
            "polished_deepslate","cobbled_deepslate","andesite","granite","diorite","cobblestone","mossy_cobblestone",
            "tuff","polished_tuff","blackstone","polished_blackstone","sandstone","red_sandstone" -> material
            "stone","smooth_sandstone","smooth_red_sandstone","polished_andesite","polished_diorite","polished_granite" ->
                if (form == "wall") return vanillaState(state, rotation) else material
            "deepslate_bricks" -> "deepslate_brick"
            "deepslate_tiles" -> "deepslate_tile"
            "stone_bricks" -> "stone_brick"
            "bricks" -> "brick"
            else -> return vanillaState(state, rotation)
        }
        val native = "${base}_$form"
        val properties = state.substringAfter('[',"").substringBeforeLast(']').split(',').filter { '=' in it }
            .associate { it.substringBefore('=') to it.substringAfter('=') }
        val selected = shapeProps(native, old, properties)
        val converted = "minecraft:$native" + if (selected.isEmpty()) "" else selected.entries.joinToString(",", "[", "]") { "${it.key}=${it.value}" }
        return vanillaState(converted, rotation)
    }
    private fun convert(state: String, rotation: Int): String {
        val id = state.substringBefore('[')
        val ns = id.substringBefore(':', "minecraft")
        val old = id.substringAfter(':')
        if (old in setOf("structure_void", "structure_block")) return SKIP
        if (old == "jigsaw") return "minecraft:air"
        val props = state.substringAfter('[', "").substringBeforeLast(']').split(',')
            .filter { '=' in it }.associate { it.substringBefore('=') to it.substringAfter('=') }
        // Forge ivy has a DOWN face. Java vines cannot lie on a floor.
        if (ns == "immersive_weathering" && old == "ivy" && props["up"] != "true" && horizontal.none { props[it] == "true" }) {
            return if (props["down"] == "true") "minecraft:moss_carpet" else "minecraft:air"
        }
        val base = if (ns == "minecraft") when (old) {
            "grass" -> "short_grass"
            "chain" -> "iron_chain"
            else -> old
        } else substitute(ns, old)
        // Immersive Weathering injected a non-vanilla "weathering" state into vanilla stone.
        // Java 1.20 skull blocks also carried a waterlogged flag absent in 1.21.11.
        val selected = if (ns == "minecraft") props.filterKeys { it != "weathering" && !(base.endsWith("_skull") && it == "waterlogged") }
            else shapeProps(base, old, props)
        val rotated = selected.map { (key, value) ->
            val rotatedValue = when (key) {
                "axis" -> if ((rotation and 1) == 0 || value == "y") value else if (value == "x") "z" else "x"
                "rotation" -> value.toIntOrNull()?.let { ((it + rotation * 4) and 15).toString() } ?: value
                "orientation" -> value.split('_').joinToString("_") { direction(it, rotation) }
                "shape" -> if (base.endsWith("rail")) rail(value, rotation) else value
                else -> direction(value, rotation)
            }
            direction(key, rotation) to rotatedValue
        }.sortedBy { it.first }
        val out = "minecraft:$base"
        return if (rotated.isEmpty()) out else out + rotated.joinToString(",", "[", "]") { "${it.first}=${it.second}" }
    }
    private fun rail(shape: String, turns: Int): String {
        if (shape.startsWith("ascending_")) return "ascending_" + direction(shape.removePrefix("ascending_"), turns)
        val axes = shape.split('_').map { direction(it, turns) }.toSet()
        return listOf("north_south", "east_west", "south_east", "south_west", "north_west", "north_east")
            .firstOrNull { it.split('_').toSet() == axes } ?: shape
    }

    private val stoneShape = Regex("_(slab|stairs|wall)$")
    private fun rock(block: String, slab: String, old: String): String =
        stoneShape.find(old)?.groupValues?.get(1)?.let { "${slab}_$it" } ?: block

    private fun weathered(old: String): String {
        return when {
            old == "moss" || old == "frost" || old == "soot" -> "glow_lichen"
            old == "ivy" -> "vine"
            old == "charred_planks" -> "dark_oak_planks"
            old == "charred_slab" -> "dark_oak_slab"
            old == "charred_stairs" -> "dark_oak_stairs"
            old.endsWith("iron_bars") -> "iron_bars"
            old.endsWith("iron_trapdoor") -> "iron_trapdoor"
            old.endsWith("iron_door") -> "iron_door"
            old.startsWith("cracked_deepslate_brick") -> rock("cracked_deepslate_bricks", "deepslate_brick", old)
            old.startsWith("cracked_deepslate_tile") -> rock("cracked_deepslate_tiles", "deepslate_tile", old)
            old.startsWith("cracked_stone_brick") -> rock("cracked_stone_bricks", "stone_brick", old)
            old == "cracked_bricks" || old == "mossy_bricks" -> "bricks"
            old == "mossy_brick_slab" -> "brick_slab"
            old.startsWith("mossy_stone") -> rock("mossy_cobblestone", "mossy_cobblestone", old)
            old.startsWith("sandy_") -> rock("sandstone", "sandstone", old)
            old.startsWith("snowy_cobble") -> rock("cobblestone", "cobblestone", old)
            old.startsWith("snowy_stone_brick") -> rock("stone_bricks", "stone_brick", old)
            old.startsWith("snowy_stone") || old == "stone_wall" -> rock("andesite", "andesite", old)
            old == "icicle" -> "pointed_dripstone"
            old.contains("leaf_pile") -> "leaf_litter"
            old == "sand_layer_block" -> "snow"
            old == "frosty_grass" -> "short_grass"
            old == "tinted_glass_pane" -> "gray_stained_glass_pane"
            old.contains("iron") -> {
                val prefix = when {
                    "rusted" in old -> "oxidized_"
                    "weathered" in old -> "weathered_"
                    "exposed" in old -> "exposed_"
                    else -> ""
                }
                "waxed_" + prefix + rock("cut_copper", "cut_copper", old)
            }
            else -> error("Unmapped Immersive Weathering piece $old")
        }
    }

    private val createMaterials = mapOf(
        "analog_lever" to "lever", "andesite_alloy_block" to "polished_andesite", "andesite_casing" to "stripped_dark_oak_wood",
        "basin" to "cauldron", "belt" to "polished_blackstone_slab", "bound_cardboard_block" to "packed_mud",
        "brass_block" to "waxed_cut_copper", "brass_casing" to "chiseled_copper", "cardboard_block" to "packed_mud",
        "chute" to "hopper", "smart_chute" to "hopper", "content_observer" to "observer", "contraption_controls" to "observer",
        "controls" to "lever", "copper_casing" to "waxed_chiseled_copper", "copycat_step" to "smooth_stone_slab",
        "crushing_wheel" to "polished_deepslate", "crushing_wheel_controller" to "air",
        "deepslate_zinc_ore" to "deepslate_iron_ore", "depot" to "polished_andesite_slab", "encased_chain_drive" to "polished_andesite",
        "encased_fan" to "observer", "fluid_pipe" to "lightning_rod", "fluid_tank" to "waxed_cut_copper",
        "gearbox" to "polished_andesite", "hand_crank" to "lever", "industrial_iron_block" to "polished_deepslate",
        "item_drain" to "waxed_chiseled_copper", "item_vault" to "chiseled_polished_blackstone", "mechanical_drill" to "piston",
        "mechanical_piston" to "piston", "mechanical_press" to "piston", "mechanical_pump" to "waxed_chiseled_copper",
        "nixie_tube" to "copper_bulb", "ochrum" to "yellow_terracotta", "powered_shaft" to "iron_chain",
        "pulse_repeater" to "repeater", "railway_casing" to "polished_blackstone", "raw_zinc_block" to "raw_iron_block",
        "redstone_contact" to "observer", "redstone_link" to "stone_button", "redstone_requester" to "observer",
        "scoria" to "polished_blackstone", "spout" to "hopper", "steam_engine" to "waxed_chiseled_copper",
        "stockpile_switch" to "observer", "weathered_iron_block" to "weathered_cut_copper", "weighted_ejector" to "heavy_weighted_pressure_plate",
        "zinc_block" to "iron_block",
    )
    private fun substitute(ns: String, old: String): String = when (ns) {
        "immersive_weathering" -> weathered(old)
        "create" -> when {
            old.endsWith("_ladder") -> "ladder"
            old.endsWith("_scaffolding") -> "scaffolding"
            old.endsWith("copper_shingles") || old.endsWith("copper_tiles") -> "waxed_" + old.substringBefore("copper") + "cut_copper"
            old.contains("cut_deepslate") -> rock("polished_deepslate", "polished_deepslate", old)
            else -> createMaterials.getValue(old)
        }
        "createdeco" -> when {
            old.startsWith("decal_") -> "gray_carpet"
            old == "dusk_brick_wall" -> "deepslate_brick_wall"
            old.endsWith("ladder") -> "ladder"
            old.endsWith("bars") || old.endsWith("mesh_fence") -> "iron_bars"
            old.endsWith("stairs") -> "polished_deepslate_stairs"
            old.endsWith("catwalk") -> "iron_trapdoor"
            else -> "polished_deepslate"
        }
        "copycats" -> when {
            old.endsWith("trapdoor") -> "iron_trapdoor"
            old.endsWith("door") -> "iron_door"
            old.endsWith("button") -> "stone_button"
            old.endsWith("stairs") || old.endsWith("slope") -> "polished_andesite_stairs"
            old.endsWith("wall") -> "andesite_wall"
            old.endsWith("pipe") -> "lightning_rod"
            old.endsWith("shaft") -> "iron_chain"
            old.endsWith("cogwheel") -> "chiseled_copper"
            else -> "smooth_stone_slab"
        }
        "jeffs_cursed_walking_structures" -> when (old) {
            "air_duct" -> "chiseled_copper"
            "exposed_air_duct" -> "exposed_chiseled_copper"
            "metal_grate" -> "copper_grate"
            "exposed_metal_grate" -> "exposed_copper_grate"
            "sturdy_bricks" -> "deepslate_bricks"
            "cracked_sturdy_bricks" -> "cracked_deepslate_bricks"
            "warning_block" -> "yellow_glazed_terracotta"
            "sturdy_lamp", "door_opener" -> "sea_lantern"
            "doorblock" -> "iron_block"
            "gun_part_chest", "create_part_chest", "gun_storage_unit" -> "barrel"
            "forcefield_switch" -> "lever"
            "forcefield_generator" -> "chiseled_copper"
            "forcefield", "forcefieldplaceholder" -> "light_blue_stained_glass_pane"
            "skeleton_corpse_lay", "skeleton_corpse_remains", "skeleton_corpse_sit" -> "skeleton_skull"
            else -> if (old.endsWith("_spawner")) "copper_grate" else error("Unknown JCWS block $old")
        }
        "lootr" -> if (old == "lootr_chest") "chest" else "barrel"
        "the_deep_void", "the_deeper_void" -> when {
            old.endsWith("glass") -> if (old.startsWith("sulfur")) "yellow_stained_glass" else "purple_stained_glass"
            old.contains("lantern") -> "soul_lantern"
            old == "liquid_void" -> "water"
            old.endsWith("bookshelf") -> "bookshelf"
            old.contains("stalagmite") -> "pointed_dripstone"
            old.contains("lichen") -> "glow_lichen"
            old.contains("skeleton") || old == "corpse" -> "skeleton_skull"
            old.contains("bone") -> "bone_block"
            old.endsWith("fern") -> "fern"
            old.endsWith("sapling") -> "dark_oak_sapling"
            old.endsWith("tnt") -> "tnt"
            ns == "the_deeper_void" && old == "columbite" -> "deepslate_emerald_ore"
            old.endsWith("_ore") -> "deepslate_iron_ore"
            old.contains("crystal") -> "amethyst_cluster"
            old.endsWith("slab") -> "dark_oak_slab"
            old.endsWith("stairs") -> "dark_oak_stairs"
            old.endsWith("trapdoor") -> "dark_oak_trapdoor"
            old.endsWith("wall") -> "deepslate_brick_wall"
            old.endsWith("bricks") -> "deepslate_bricks"
            old == "rotting_crate" -> "barrel"
            old == "voidlight" -> "ochre_froglight"
            old == "desolate_soil" -> "soul_soil"
            old == "desolate_stem" -> "stripped_dark_oak_log"
            else -> "polished_deepslate"
        }
        "bbb" -> "spruce_slab"
        "cure" -> "fern"
        "decorative_blocks" -> "iron_chain"
        "mcore", "overworld_netherite_ore" -> "deepslate_iron_ore"
        "powderedup" -> "brown_carpet"
        "projectvibrantjourneys" -> when (old) { "rocks" -> "stone_button"; "ice_chunks" -> "light_blue_carpet"; else -> "short_grass" }
        "refurbished_furniture" -> "observer"
        else -> error("Unmapped source block $ns:$old")
    }

    private fun shapeProps(base: String, old: String, props: Map<String, String>): Map<String, String> {
        val keys = when {
            base.endsWith("stairs") -> setOf("facing", "half", "shape", "waterlogged")
            base.endsWith("slab") -> setOf("type", "waterlogged")
            base.endsWith("trapdoor") -> setOf("facing", "half", "open", "powered", "waterlogged")
            base.endsWith("_door") -> setOf("facing", "half", "hinge", "open", "powered")
            base == "vine" -> cardinals + "up"
            base == "glow_lichen" -> cardinals + setOf("up", "down", "waterlogged")
            base.endsWith("pane") || base == "iron_bars" -> cardinals + "waterlogged"
            base.endsWith("_wall") -> cardinals + setOf("up", "waterlogged")
            base == "ladder" -> setOf("facing", "waterlogged")
            base.endsWith("log") || base.endsWith("wood") || base == "iron_chain" -> setOf("axis")
            base == "chest" -> setOf("facing", "type", "waterlogged")
            base == "barrel" -> setOf("facing")
            base == "lever" || base.endsWith("_button") -> setOf("face", "facing", "powered")
            base in setOf("observer", "piston", "lightning_rod", "amethyst_cluster") -> setOf("facing")
            base in setOf("soul_lantern") -> setOf("hanging","waterlogged")
            base in setOf("skeleton_skull") -> setOf("rotation")
            base == "scaffolding" -> setOf("bottom","distance","waterlogged")
            else -> emptySet()
        }
        val native = props.filter { (k, v) -> k in keys && when (k) {
            "facing" -> v in cardinals || (v in setOf("up", "down") && base in setOf("barrel","observer","piston","lightning_rod","amethyst_cluster"))
            "half" -> if (base.endsWith("_door")) v in setOf("lower","upper") else v in setOf("top","bottom")
            "type" -> if (base == "chest") v in setOf("left","right","single") else v in setOf("top","bottom","double")
            "shape" -> v in setOf("straight","inner_left","inner_right","outer_left","outer_right")
            "face" -> v in setOf("wall","floor","ceiling")
            "north","south","west","east" -> if (base.endsWith("_wall")) v in setOf("low","tall","none") else v in setOf("true","false")
            else -> true
        } }.toMutableMap()
        if (base == "glow_lichen" && native.none { it.key != "waterlogged" && it.value == "true" }) native["down"] = "true"
        if (base == "pointed_dripstone") native["vertical_direction"] = if (old == "icicle") "down" else "up"
        if (base == "lightning_rod" && "facing" !in native) {
            native["facing"] = listOf("north","south","east","west","up","down").firstOrNull { props[it] == "true" } ?: "up"
        }
        return native
    }

    internal fun blockMarker(state: String): ApocalypseMarkerKind? = when (state.substringBefore('[')) {
        "lootr:lootr_chest", "lootr:lootr_barrel" -> ApocalypseMarkerKind.LOOT
        "jeffs_cursed_walking_structures:gun_part_chest", "jeffs_cursed_walking_structures:create_part_chest" -> ApocalypseMarkerKind.ARMORY
        "jeffs_cursed_walking_structures:gun_storage_unit" -> ApocalypseMarkerKind.CORE
        "jeffs_cursed_walking_structures:forcefield_switch" -> ApocalypseMarkerKind.POWER
        "jeffs_cursed_walking_structures:forcefield_generator" -> ApocalypseMarkerKind.SECURITY
        "jeffs_cursed_walking_structures:forcefield", "jeffs_cursed_walking_structures:forcefieldplaceholder" -> ApocalypseMarkerKind.FORCEFIELD
        else -> entityMarker(state.substringBefore('[') + "_placer")
    }
    internal fun entityMarker(id: String): ApocalypseMarkerKind? = when (id) {
        "jeffs_cursed_walking_structures:bunker_spawner_placer" -> ApocalypseMarkerKind.BUNKER_SPAWNER
        "jeffs_cursed_walking_structures:hazmat_spawner_placer" -> ApocalypseMarkerKind.HAZMAT_SPAWNER
        "jeffs_cursed_walking_structures:soldier_spawner_placer" -> ApocalypseMarkerKind.SOLDIER_SPAWNER
        "jeffs_cursed_walking_structures:mutated_spawner_placer" -> ApocalypseMarkerKind.MUTATED_SPAWNER
        "jeffs_cursed_walking_structures:warehouse_spawner_placer" -> ApocalypseMarkerKind.WAREHOUSE_SPAWNER
        else -> null
    }

    /** The spatial substitutions of the four pinned processor lists. Loot-table assignment is handled by the container owner. */
    internal fun process(state: String, processor: String?, x: Int, y: Int, z: Int, seed: Long): String {
        val id = state.substringBefore('[')
        val source = processor?.substringAfter(':') ?: return state
        val chance = when (source) {
            "raph" -> when (id) { "minecraft:purpur_block" -> 1.0; "immersive_weathering:ivy", "immersive_weathering:moss" -> .4; else -> return state }
            "jeff", "loot" -> when (id) {
                "minecraft:water" -> 1.0
                "immersive_weathering:ivy" -> .5
                "immersive_weathering:moss" -> .4
                "jeffs_cursed_walking_structures:gun_part_chest" -> if (source == "jeff") .2 else .3
                else -> return state
            }
            "pond" -> when (id) {
                "minecraft:iron_ore" -> .08
                "immersive_weathering:ivy" -> .3
                "jeffs_cursed_walking_structures:metal_grate" -> .33
                "jeffs_cursed_walking_structures:air_duct" -> .4
                else -> return state
            }
            else -> return state
        }
        var mix = seed xor (x * -7046029254386353131L) xor (y * -4658895280553007687L) xor (z * -7723592293110705685L)
        mix = (mix xor (mix ushr 30)) * -4658895280553007687L
        if (((mix xor (mix ushr 27)) ushr 11) / 9007199254740992.0 >= chance) return state
        return when (id) {
            "minecraft:iron_ore" -> "minecraft:iron_block"
            "jeffs_cursed_walking_structures:gun_part_chest" -> "jeffs_cursed_walking_structures:create_part_chest"
            "jeffs_cursed_walking_structures:metal_grate" -> "jeffs_cursed_walking_structures:exposed_metal_grate"
            "jeffs_cursed_walking_structures:air_duct" -> "jeffs_cursed_walking_structures:exposed_air_duct"
            else -> "minecraft:air"
        }
    }
}
