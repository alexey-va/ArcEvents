package ru.ruscrafting.events.paper.apocalypse

import com.google.gson.JsonObject
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import org.bukkit.plugin.Plugin
import ru.ruscrafting.events.config.ArcEventsLocale
import ru.ruscrafting.events.domain.apocalypse.source.ApocalypseSourceItemIdentity
import ru.ruscrafting.events.domain.apocalypse.source.ApocalypseSourceLootItem
import ru.ruscrafting.events.domain.apocalypse.source.ApocalypseWorkbenchIngredient
import ru.ruscrafting.events.domain.apocalypse.source.ApocalypseWorkbenchRecipe
import ru.ruscrafting.events.domain.apocalypse.source.ApocalypseWorkbenchRecipes
import ru.ruscrafting.events.paper.TttFirearms
import java.util.Collections
import java.util.Random

data class ApocalypseWorkbenchIngredientStatus(
    val ingredient: ApocalypseWorkbenchIngredient,
    val required: Int,
    val available: Int,
) {
    val satisfied: Boolean get() = available >= required
}

sealed interface ApocalypseWorkbenchCraftResult {
    /** Every access returns detached native stacks; a menu cannot modify an accepted plan. */
    class Ready(afterStorage: List<ItemStack?>, outputs: List<ItemStack>) : ApocalypseWorkbenchCraftResult {
        private val stored = afterStorage.map { it?.clone() }
        private val created = outputs.map(ItemStack::clone)
        val afterStorage: List<ItemStack?> get() = Collections.unmodifiableList(stored.map { it?.clone() })
        val outputs: List<ItemStack> get() = Collections.unmodifiableList(created.map(ItemStack::clone))
    }
    data class Missing(val ingredients: List<ApocalypseWorkbenchIngredientStatus>) : ApocalypseWorkbenchCraftResult
    data object Full : ApocalypseWorkbenchCraftResult
}

/** Builds one complete replacement storage array. Persistence and the final native write belong to the runtime. */
class ApocalypseWorkbenchCrafting(
    plugin: Plugin,
    locale: ArcEventsLocale,
    firearms: TttFirearms,
    val catalog: ApocalypseWorkbenchRecipes,
) {
    private val items = ApocalypseLootItems(plugin, locale, firearms, catalog)

    fun preview(recipe: ApocalypseWorkbenchRecipe, player: Player?): List<ItemStack> =
        (listOf(recipe.result) + recipe.byproducts).flatMap { items.create(it, player, Random(0)) }

    fun sourceItem(identity: ApocalypseSourceItemIdentity, count: Int = 1, player: Player? = null): ItemStack {
        val nbt = identity.blockId?.let { JsonObject().also { json -> json.addProperty("BlockId", it) }.toString() }
        return items.create(ApocalypseSourceLootItem(identity.itemId, count, nbt = nbt,
            gunId = identity.gunId, ammoId = identity.ammoId, attachmentId = identity.attachmentId), player, Random(0)).single()
    }

    fun identity(item: ItemStack?): ApocalypseSourceItemIdentity? = items.identity(item)
    fun fuelUnits(item: ItemStack?): Int = items.fuelUnits(item)

    fun ingredients(recipe: ApocalypseWorkbenchRecipe, storage: Array<ItemStack?>): List<ApocalypseWorkbenchIngredientStatus> {
        require(storage.size == 36) { "Crafting reads ordinary player storage only" }
        val ingredients = recipe.ingredients.map { ingredient ->
            ApocalypseWorkbenchIngredientStatus(ingredient, ingredient.count, storage.sumOf { item ->
                if (matches(item, ingredient)) item!!.amount else 0
            })
        }
        return if (recipe.fuelUnits == 0) ingredients else ingredients + ApocalypseWorkbenchIngredientStatus(
            ApocalypseWorkbenchIngredient(listOf(ApocalypseSourceItemIdentity(ApocalypseLootItems.REFINERY_FUEL_CELL)), recipe.fuelUnits,
                sourceTag = "apoc:refinery_fuel_units"), recipe.fuelUnits, storage.sumOf(items::fuelUnits))
    }

    fun plan(recipe: ApocalypseWorkbenchRecipe, storage: Array<ItemStack?>, player: Player?): ApocalypseWorkbenchCraftResult {
        val status = ingredients(recipe, storage)
        if (status.any { !it.satisfied } || recipe.unavailableInput != null) return ApocalypseWorkbenchCraftResult.Missing(status)
        val after = storage.map { it?.clone()?.takeUnless(ItemStack::isEmpty) }.toTypedArray()
        for (ingredient in recipe.ingredients.sortedBy { it.alternatives.size }) {
            var remaining = ingredient.count
            for (slot in after.indices) {
                val stack = after[slot]?.takeIf { matches(it, ingredient) } ?: continue
                val take = minOf(remaining, stack.amount)
                after[slot] = if (take == stack.amount) null else stack.also { it.amount -= take }
                remaining -= take
                if (remaining == 0) break
            }
            // Reused ingredient predicates must not spend the same input twice.
            if (remaining != 0) return ApocalypseWorkbenchCraftResult.Missing(status)
        }
        val fuelRemainders = mutableListOf<ItemStack>()
        if (recipe.fuelUnits > 0) {
            var remaining = recipe.fuelUnits
            val slots = after.indices.filter { items.fuelUnits(after[it]) > 0 }.sortedBy { slot ->
                items.fuelUnits(after[slot]) / after[slot]!!.amount
            }
            for (slot in slots) {
                val stack = after[slot] ?: continue
                val available = items.fuelUnits(stack)
                val perCell = available / stack.amount
                val spent = minOf(remaining, available)
                val cells = (spent + perCell - 1) / perCell
                val unused = cells * perCell - spent
                after[slot] = if (cells == stack.amount) null else stack.clone().also { it.amount -= cells }
                if (unused > 0) fuelRemainders += items.partialFuel(stack, unused, player)
                remaining -= spent
                if (remaining == 0) break
            }
            if (remaining != 0) return ApocalypseWorkbenchCraftResult.Missing(status)
        }
        val outputs = preview(recipe, player) + fuelRemainders
        for (output in outputs) {
            var remaining = output.amount
            for (slot in after.indices) {
                val stack = after[slot] ?: continue
                if (!stack.isSimilar(output)) continue
                val take = minOf(remaining, (stack.maxStackSize - stack.amount).coerceAtLeast(0))
                stack.amount += take
                remaining -= take
                if (remaining == 0) break
            }
            for (slot in after.indices) {
                if (remaining == 0) break
                if (after[slot] != null) continue
                val take = minOf(remaining, output.maxStackSize)
                after[slot] = output.clone().also { it.amount = take }
                remaining -= take
            }
            if (remaining != 0) return ApocalypseWorkbenchCraftResult.Full
        }
        return ApocalypseWorkbenchCraftResult.Ready(after.asList(), outputs)
    }

    private fun matches(item: ItemStack?, ingredient: ApocalypseWorkbenchIngredient): Boolean =
        items.identity(item)?.let { identity -> ingredient.alternatives.any { it.accepts(identity) } } == true
}
