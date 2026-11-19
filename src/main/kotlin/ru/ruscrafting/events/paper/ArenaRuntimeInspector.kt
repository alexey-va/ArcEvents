package ru.ruscrafting.events.paper

import com.sk89q.worldedit.bukkit.BukkitAdapter
import com.sk89q.worldguard.WorldGuard
import com.sk89q.worldguard.protection.flags.Flags
import com.sk89q.worldguard.protection.flags.StateFlag
import org.bukkit.Location
import org.bukkit.GameRules
import org.bukkit.plugin.Plugin
import ru.ruscrafting.events.config.ArenaSettings
import ru.ruscrafting.events.config.EventLocation

fun interface PvpFlagInspector {
    fun allowed(location: Location): Boolean
}

class ArenaRuntimeInspector(private val plugin: Plugin) {
    private val worldGuard: PvpFlagInspector? = if (plugin.server.pluginManager.isPluginEnabled("WorldGuard")) {
        WorldGuardPvpFlagInspector()
    } else {
        null
    }

    @Suppress("DEPRECATION")
    fun ready(arena: ArenaSettings, @Suppress("UNUSED_PARAMETER") maximumPlayers: Int): Boolean {
        if (!arena.enabled || arena.spawns.isEmpty()) return false
        val lobby = arena.lobby ?: return false
        val spectator = arena.spectator ?: return false
        val world = plugin.server.getWorld(arena.world) ?: return false
        if (world.getGameRuleValue(GameRules.PVP) != true) return false
        return ready(lobby) && ready(spectator) && arena.spawns.all(::ready)
    }

    private fun ready(point: EventLocation): Boolean {
        val location = point.bukkitLocation() ?: return false
        if (!location.world.isChunkLoaded(location.blockX shr 4, location.blockZ shr 4)) return false
        return location.hasArenaFooting() && worldGuard?.allowed(location) != false
    }

    private fun EventLocation.bukkitLocation(): Location? = plugin.server.getWorld(world)?.let { loaded ->
        Location(loaded, x, y, z, yaw, pitch)
    }
}

internal fun Location.hasArenaFooting(): Boolean {
    val feet = block
    return feet.isPassable && feet.getRelative(0, 1, 0).isPassable && feet.getRelative(0, -1, 0).type.isSolid
}

private class WorldGuardPvpFlagInspector : PvpFlagInspector {
    override fun allowed(location: Location): Boolean = WorldGuard.getInstance().platform.regionContainer
        .createQuery()
        .queryState(BukkitAdapter.adapt(location), null, Flags.PVP) != StateFlag.State.DENY
}
