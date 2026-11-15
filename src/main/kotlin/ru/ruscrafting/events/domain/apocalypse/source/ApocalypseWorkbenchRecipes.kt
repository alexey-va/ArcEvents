package ru.ruscrafting.events.domain.apocalypse.source

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.util.Collections

enum class ApocalypseWorkbenchStation(val sourceId: String) {
    CRAFTING("minecraft:crafting_table"),
    T1("bf1:telegraph"),
    T2("tacz:gun_smith_table"),
    T3("sfms:ms_workbench"),
    AMMO("tacz:ammo_workbench"),
    PRESS("create:mechanical_press"),
    REFINERY("the_deeper_void:void_refinery_control_pad");

    companion object {
        fun fromSource(sourceId: String): ApocalypseWorkbenchStation? = entries.firstOrNull { it.sourceId == sourceId }
    }
}

data class ApocalypseSourceItemIdentity(
    val itemId: String,
    val gunId: String? = null,
    val ammoId: String? = null,
    val attachmentId: String? = null,
    val blockId: String? = null,
) {
    init {
        listOfNotNull(itemId, gunId, ammoId, attachmentId, blockId).forEach {
            require(it.matches(SOURCE_ID)) { "Invalid source item identity: $it" }
        }
    }

    /** Source partial-NBT ingredients constrain only their declared fields. */
    fun accepts(actual: ApocalypseSourceItemIdentity): Boolean = itemId == actual.itemId &&
        (gunId == null || gunId == actual.gunId) && (ammoId == null || ammoId == actual.ammoId) &&
        (attachmentId == null || attachmentId == actual.attachmentId) && (blockId == null || blockId == actual.blockId)

    companion object {
        private val SOURCE_ID = Regex("[a-z0-9_.-]+:[a-z0-9_./-]+")
    }
}

data class ApocalypseWorkbenchIngredient(
    val alternatives: List<ApocalypseSourceItemIdentity>,
    val count: Int,
    val sourceTag: String? = null,
) {
    init {
        require(alternatives.isNotEmpty() && alternatives.size <= 512 && alternatives.distinct().size == alternatives.size)
        require(count in 1..4096)
    }
}

enum class ApocalypseWorkbenchRecipeKind { GUN, AMMO, STATION, MATERIAL }

data class ApocalypseWorkbenchRecipe(
    val id: String,
    val station: ApocalypseWorkbenchStation,
    val kind: ApocalypseWorkbenchRecipeKind,
    val result: ApocalypseSourceLootItem,
    val ingredients: List<ApocalypseWorkbenchIngredient>,
    val nameEn: String? = null,
    val source: String = "",
    val unavailableInput: String? = null,
    val additionalStations: Set<ApocalypseWorkbenchStation> = emptySet(),
    val sourceStation: String = station.sourceId,
    val byproducts: List<ApocalypseSourceLootItem> = emptyList(),
    val fuelUnits: Int = 0,
) {
    init {
        require(id.isNotBlank() && id.length <= 256)
        require(ingredients.isNotEmpty() && ingredients.size <= 32)
        require(result.count in 1..4096)
        ApocalypseSourceItemIdentity(result.itemId, result.gunId, result.ammoId, result.attachmentId)
        require(byproducts.size <= 16 && byproducts.all { it.count in 1..4096 })
        byproducts.forEach { ApocalypseSourceItemIdentity(it.itemId, it.gunId, it.ammoId, it.attachmentId) }
        require(fuelUnits in 0..4096)
    }
}

/** Immutable source recipe data; native item matching and mutation belong to the Paper adapter. */
class ApocalypseWorkbenchRecipes(recipes: List<ApocalypseWorkbenchRecipe>) {
    val all: List<ApocalypseWorkbenchRecipe> = Collections.unmodifiableList(recipes.map { recipe ->
        recipe.copy(ingredients = Collections.unmodifiableList(recipe.ingredients.map {
            it.copy(alternatives = Collections.unmodifiableList(it.alternatives.toList()))
        }), additionalStations = Collections.unmodifiableSet(recipe.additionalStations.toSet()),
            byproducts = Collections.unmodifiableList(recipe.byproducts.toList()))
    })
    private val byId = all.associateBy { it.id }.also { require(it.size == all.size) { "Duplicate source workbench recipe" } }

    fun recipes(station: ApocalypseWorkbenchStation): List<ApocalypseWorkbenchRecipe> = all.filter { it.station == station || station in it.additionalStations }
    fun find(id: String): ApocalypseWorkbenchRecipe? = byId[id]
    fun availableAt(id: String, station: ApocalypseWorkbenchStation): Boolean = find(id)?.let {
        it.station == station || station in it.additionalStations
    } == true

    companion object {
        private const val RESOURCE = "/apocalypse/workbench-recipes.json"
        private val T3_AMMUNITION = setOf("tacz:ammo/12g", "tacz:ammo/556x45", "tacz:ammo/308", "tacz:ammo/57x28", "tacz:ammo/40mm", "tacz:ammo/9mm")

        fun load(): ApocalypseWorkbenchRecipes = requireNotNull(ApocalypseWorkbenchRecipes::class.java.getResourceAsStream(RESOURCE)) {
            "Missing source workbench catalog: $RESOURCE"
        }.use { stream ->
            val bytes = stream.readNBytes(1024 * 1024 + 1)
            require(bytes.size <= 1024 * 1024) { "Source workbench catalog exceeds bound" }
            decode(bytes.toString(Charsets.UTF_8))
        }

        internal fun decode(json: String): ApocalypseWorkbenchRecipes {
            val root = JsonParser.parseString(json).asJsonObject
            require(root.get("schema").asInt == 1)
            val tags = root.getAsJsonObject("tags")?.entrySet()?.associate { (tag, values) ->
                tag to values.asJsonArray.map { ApocalypseSourceItemIdentity(it.asString) }
            }.orEmpty()
            val recipes = root.getAsJsonArray("recipes")
            require(recipes.size() in 1..512)
            return ApocalypseWorkbenchRecipes(recipes.map { raw ->
                val recipe = raw.asJsonObject
                val station = requireNotNull(ApocalypseWorkbenchStation.fromSource(recipe.get("station").asString)) {
                    "Unsupported source workbench station in ${recipe.get("id")}: ${recipe.get("station")}"
                }
                val result = output(recipe.getAsJsonObject("output"))
                val ingredients = recipe.getAsJsonArray("inputs").map { value ->
                    val ingredient = value.asJsonObject
                    val tag = ingredient.stringOrNull("tag")
                    val alternatives = if (tag != null) requireNotNull(tags[tag]) { "Unresolved original ingredient tag: $tag" }
                    else listOf(identity(ingredient))
                    ApocalypseWorkbenchIngredient(alternatives, ingredient.get("count").asInt, tag)
                }
                ApocalypseWorkbenchRecipe(recipe.get("id").asString, station,
                    ApocalypseWorkbenchRecipeKind.valueOf(recipe.get("kind").asString.uppercase()), result, ingredients,
                    nameEn = recipe.getAsJsonObject("gun")?.stringOrNull("nameEn"), source = recipe.stringOrNull("source").orEmpty(),
                    unavailableInput = recipe.stringOrNull("unavailableInput"), additionalStations =
                        if (recipe.get("id").asString in T3_AMMUNITION) setOf(ApocalypseWorkbenchStation.T3) else emptySet(),
                    sourceStation = recipe.stringOrNull("sourceStation") ?: station.sourceId,
                    byproducts = recipe.getAsJsonArray("byproducts")?.map { output(it.asJsonObject) }.orEmpty(),
                    fuelUnits = recipe.get("fuelUnits")?.asInt ?: 0)
            })
        }

        private fun output(value: JsonObject): ApocalypseSourceLootItem {
            val count = value.get("count").asInt
            val id = value.get("id").asString
            val nbt = value.getAsJsonObject("nbt")?.toString()
            return when (value.get("kind").asString) {
                "gun" -> ApocalypseSourceLootItem("tacz:modern_kinetic_gun", count, nbt = nbt, gunId = id)
                "ammo" -> ApocalypseSourceLootItem("tacz:ammo", count, nbt = nbt, ammoId = id)
                "item" -> ApocalypseSourceLootItem(id, count, nbt = nbt)
                else -> error("Unsupported workbench output: ${value.get("kind")}")
            }
        }

        private fun identity(value: JsonObject): ApocalypseSourceItemIdentity {
            val nbt = value.getAsJsonObject("nbt")
            require(nbt == null || nbt.keySet().all { it in setOf("GunId", "AmmoId", "AttachmentId", "BlockId") }) {
                "Unsupported original ingredient NBT: $nbt"
            }
            return ApocalypseSourceItemIdentity(value.get("item").asString, nbt?.stringOrNull("GunId"),
                nbt?.stringOrNull("AmmoId"), nbt?.stringOrNull("AttachmentId"), nbt?.stringOrNull("BlockId"))
        }

        private fun JsonObject.stringOrNull(key: String): String? = get(key)?.takeUnless { it.isJsonNull }?.asString
    }
}
