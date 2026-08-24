package ru.ruscrafting.events.paper

import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.Particle
import org.bukkit.Sound
import org.bukkit.entity.Player
import org.bukkit.entity.Projectile
import org.bukkit.entity.Snowball
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType
import org.bukkit.plugin.Plugin
import org.bukkit.potion.PotionEffect
import org.bukkit.potion.PotionEffectType
import ru.arc.core.ScheduledTask
import ru.arc.core.Tasks
import ru.ruscrafting.events.config.ArcEventsConfig
import java.util.UUID

/** Owns smoke projectiles, persistent clouds, effects, particles, and cleanup. */
class TttSmokeGrenades(
    private val plugin: Plugin,
    private val settings: () -> ArcEventsConfig,
    private val currentMatchId: () -> UUID?,
    private val targets: () -> Collection<Player>,
    private val clock: () -> Long = System::currentTimeMillis,
) : AutoCloseable {
    private data class SmokeCloud(val matchId: UUID, val center: Location, val expiresAtMs: Long)

    private val smokeMatchKey = NamespacedKey(plugin, "smoke_match")
    private val projectileIds = linkedSetOf<UUID>()
    private val clouds = mutableListOf<SmokeCloud>()
    private var cloudTask: ScheduledTask? = null

    fun launch(player: Player, matchId: UUID): Snowball? = runCatching {
        player.launchProjectile(Snowball::class.java).also { projectile ->
            projectile.item = ItemStack.of(Material.FIREWORK_STAR)
            projectile.velocity = player.eyeLocation.direction.normalize().multiply(THROW_VELOCITY)
            projectile.isPersistent = false
            projectile.persistentDataContainer.set(smokeMatchKey, PersistentDataType.STRING, matchId.toString())
            projectileIds += projectile.uniqueId
        }
    }.getOrNull()?.takeIf { it.isValid }

    fun handleHit(projectile: Projectile): Boolean {
        val matchId = projectile.persistentDataContainer.get(smokeMatchKey, PersistentDataType.STRING)
            ?.let { runCatching { UUID.fromString(it) }.getOrNull() }
            ?: return false
        projectileIds.remove(projectile.uniqueId)
        if (currentMatchId() != matchId) return true
        val center = projectile.location.clone().add(0.0, 0.35, 0.0)
        clouds += SmokeCloud(matchId, center, clock() + CLOUD_DURATION_MS)
        if (settings().ui.sounds) {
            center.world.playSound(center, Sound.ENTITY_GENERIC_EXPLODE, 0.6f, 0.65f)
        }
        ensureCloudTask()
        return true
    }

    fun clear() {
        projectileIds.forEach { plugin.server.getEntity(it)?.remove() }
        projectileIds.clear()
        clouds.clear()
        cloudTask?.cancel()
        cloudTask = null
    }

    override fun close() = clear()

    private fun ensureCloudTask() {
        if (cloudTask != null) return
        cloudTask = Tasks.scheduler.runTimer(1L, CLOUD_TICK_INTERVAL.toLong()) { tickClouds() }
    }

    private fun tickClouds() {
        val now = clock()
        val activeMatch = currentMatchId()
        clouds.removeIf { cloud -> cloud.expiresAtMs <= now || cloud.matchId != activeMatch }
        if (clouds.isEmpty()) {
            cloudTask?.cancel()
            cloudTask = null
            return
        }
        clouds.forEach { cloud ->
            if (settings().ui.particles) {
                cloud.center.world.spawnParticle(
                    Particle.LARGE_SMOKE,
                    cloud.center,
                    34,
                    CLOUD_RADIUS * 0.72,
                    1.8,
                    CLOUD_RADIUS * 0.72,
                    0.025,
                )
                cloud.center.world.spawnParticle(
                    Particle.ASH,
                    cloud.center,
                    18,
                    CLOUD_RADIUS * 0.62,
                    1.5,
                    CLOUD_RADIUS * 0.62,
                    0.01,
                )
            }
            targets().filter { player ->
                player.isOnline && player.world == cloud.center.world &&
                    player.location.distanceSquared(cloud.center) <= CLOUD_RADIUS * CLOUD_RADIUS
            }.forEach { player ->
                player.addPotionEffect(PotionEffect(
                    PotionEffectType.BLINDNESS,
                    BLINDNESS_REFRESH_TICKS,
                    0,
                    false,
                    false,
                    true,
                ))
                player.addPotionEffect(PotionEffect(
                    PotionEffectType.DARKNESS,
                    DARKNESS_REFRESH_TICKS,
                    0,
                    false,
                    false,
                    true,
                ))
            }
        }
    }

    companion object {
        private const val THROW_VELOCITY = 1.15
        private const val CLOUD_RADIUS = 5.5
        private const val CLOUD_DURATION_MS = 8_000L
        private const val CLOUD_TICK_INTERVAL = 5
        private const val BLINDNESS_REFRESH_TICKS = 35
        private const val DARKNESS_REFRESH_TICKS = 28
    }
}
