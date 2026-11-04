package ru.ruscrafting.events.paper

import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.RecipeChoice
import org.bukkit.inventory.ShapelessRecipe
import org.bukkit.plugin.Plugin

/** Generation-owned recipe; exact choice protects custom nuggets and event items from crafting. */
internal class FirearmAmmoRecipe(private val plugin: Plugin, firearms: TttFirearms) : Listener, AutoCloseable {
    private val key = NamespacedKey(plugin, "firearm_ammunition")
    internal val recipe = ShapelessRecipe(key, firearms.worldAmmunition(null, 1))
        .addIngredient(RecipeChoice.ExactChoice(ItemStack.of(Material.IRON_NUGGET)))

    init {
        check(plugin.server.addRecipe(recipe)) { "Could not register firearm ammunition recipe: $key" }
        plugin.server.onlinePlayers.forEach { it.discoverRecipe(key) }
    }

    @EventHandler
    fun onJoin(event: PlayerJoinEvent) { event.player.discoverRecipe(key) }

    override fun close() { plugin.server.removeRecipe(key) }
}
