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
            projectile.velocity = player.eyeLocation.direction.normalize().multiply(settings().smoke.throwVelocity)
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
        clouds += SmokeCloud(matchId, center, clock() + settings().smoke.durationSeconds * 1_000L)
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

    /** Re-times the cloud loop; existing cloud expiry stays immutable. */
    fun reconfigure() {
        cloudTask?.cancel()
        cloudTask = null
        if (clouds.isNotEmpty()) ensureCloudTask()
    }

    private fun ensureCloudTask() {
        if (cloudTask != null) return
        cloudTask = Tasks.scheduler.runTimer(1L, settings().smoke.tickIntervalTicks.toLong()) { tickClouds() }
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
        val smoke = settings().smoke
        clouds.forEach { cloud ->
            if (settings().ui.particles) {
                cloud.center.world.spawnParticle(
                    Particle.LARGE_SMOKE,
                    cloud.center,
                    smoke.smokeParticles,
                    smoke.radius * 0.72,
                    1.8,
                    smoke.radius * 0.72,
                    0.025,
                )
                cloud.center.world.spawnParticle(
                    Particle.ASH,
                    cloud.center,
                    smoke.ashParticles,
                    smoke.radius * 0.62,
                    1.5,
                    smoke.radius * 0.62,
                    0.01,
                )
            }
            targets().filter { player ->
                player.isOnline && player.world == cloud.center.world &&
                    player.location.distanceSquared(cloud.center) <= smoke.radius * smoke.radius
            }.forEach { player ->
                player.addPotionEffect(PotionEffect(
                    PotionEffectType.BLINDNESS,
                    smoke.blindnessRefreshTicks,
                    0,
                    false,
                    false,
                    true,
                ))
                player.addPotionEffect(PotionEffect(
                    PotionEffectType.DARKNESS,
                    smoke.darknessRefreshTicks,
                    0,
                    false,
                    false,
                    true,
                ))
            }
        }
    }

}
