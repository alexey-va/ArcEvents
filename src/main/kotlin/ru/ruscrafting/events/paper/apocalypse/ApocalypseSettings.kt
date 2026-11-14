package ru.ruscrafting.events.paper.apocalypse

import org.bukkit.configuration.file.YamlConfiguration
import java.io.File

data class ApocalypseSettings(
    val world: String,
    val seed: Long,
    val maximumPlayers: Int,
    val hordeDays: Long,
    val hordeDuration: Long,
    val hordePulse: Long,
    val maximumEnemies: Int,
    val infectionChance: Double,
    val infectionStage: Long,
    val immunity: Long,
    val ammoMinimum: Int,
    val ammoMaximum: Int,
) {
    companion object {
        fun load(file: File): ApocalypseSettings {
            val c = YamlConfiguration.loadConfiguration(file)
            return ApocalypseSettings(c.getString("world", "arcevents_apocalypse_v1")!!,
                c.getLong("seed", 68200133), c.getInt("maximum-players", 24), c.getLong("horde-days", 10),
                c.getLong("horde-duration-ticks", 6000), c.getLong("horde-pulse-ticks", 1000),
                c.getInt("maximum-enemies", 256),
                c.getDouble("infection-chance", .25), c.getLong("infection-stage-ticks", 6000),
                c.getLong("immunity-ticks", 7000), c.getInt("loot-ammo-min", 12), c.getInt("loot-ammo-max", 32)).also {
                require(it.world.matches(Regex("arcevents_[a-z0-9_]{1,48}")))
                require(it.maximumPlayers in 1..32 && it.hordeDays in 1..100)
                require(it.hordeDuration in 200..24000 && it.hordePulse in 20..1200)
                require(it.maximumEnemies in 8..256)
                require(it.infectionChance in 0.0..1.0 && it.infectionStage in 200..72000 && it.immunity in 200..72000)
                require(it.ammoMinimum in 1..64 && it.ammoMaximum in it.ammoMinimum..64)
            }
        }
    }
}
