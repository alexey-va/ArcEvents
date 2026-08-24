package ru.ruscrafting.events.paper

import org.bukkit.Location
import org.bukkit.Particle
import org.bukkit.entity.Display
import org.bukkit.entity.Item
import org.bukkit.entity.ItemDisplay
import org.bukkit.inventory.ItemStack
import org.bukkit.plugin.Plugin
import org.bukkit.util.Vector
import org.joml.Matrix4f
import ru.arc.core.ScheduledTask
import ru.arc.core.Tasks
import ru.ruscrafting.events.config.ArcEventsConfig
import java.util.UUID

/** Owns the invisible pickup entities and their animated ItemDisplay presentation. */
class TttLootScene(
    private val plugin: Plugin,
    private val settings: () -> ArcEventsConfig,
) : AutoCloseable {
    private data class LootEntity(val pickupId: UUID, val displayId: UUID?)

    private val entities = linkedMapOf<UUID, LootEntity>()
    private var animationTask: ScheduledTask? = null
    private var animationTicks = 0
    private var rotation = 0f

    val size: Int get() = entities.size

    fun spawn(location: Location, stack: ItemStack): Item {
        val item = location.world.dropItem(location, stack) { configurePickup(it, pickupDelay = 0) }
        register(item, pickupDelay = 0)
        return item
    }

    fun register(item: Item, pickupDelay: Int = 20) {
        consume(item.uniqueId)
        configurePickup(item, pickupDelay)
        val display = if (settings().ui.lootDisplays) createDisplay(item) else null
        item.setVisibleByDefault(display == null)
        entities[item.uniqueId] = LootEntity(item.uniqueId, display?.uniqueId)
        ensureAnimation()
    }

    fun consume(itemId: UUID) {
        val tracked = entities.remove(itemId) ?: return
        tracked.displayId?.let { plugin.server.getEntity(it)?.remove() }
        if (entities.isEmpty()) stopAnimation()
    }

    fun clear() {
        entities.values.forEach { tracked ->
            tracked.displayId?.let { plugin.server.getEntity(it)?.remove() }
            plugin.server.getEntity(tracked.pickupId)?.remove()
        }
        entities.clear()
        stopAnimation()
    }

    override fun close() = clear()

    private fun configurePickup(item: Item, pickupDelay: Int) {
        item.pickupDelay = pickupDelay.coerceAtLeast(0)
        item.setUnlimitedLifetime(true)
        item.setCanMobPickup(false)
        item.setGravity(false)
        item.velocity = Vector()
        item.isPersistent = false
    }

    private fun createDisplay(item: Item): ItemDisplay = item.world.spawn(
        item.location.clone().add(0.0, DISPLAY_HEIGHT, 0.0),
        ItemDisplay::class.java,
    ) { display ->
        val shown = item.itemStack.clone().also { it.amount = 1 }
        display.setItemStack(shown)
        display.itemDisplayTransform = ItemDisplay.ItemDisplayTransform.FIXED
        display.billboard = Display.Billboard.FIXED
        display.setGravity(false)
        display.isInvulnerable = true
        display.isPersistent = false
        display.isSilent = true
        display.viewRange = 0.75f
        display.interpolationDelay = 0
        display.interpolationDuration = ROTATION_TICKS
        display.setTransformationMatrix(Matrix4f().scale(DISPLAY_SCALE))
    }

    private fun ensureAnimation() {
        if (animationTask != null) return
        animationTask = Tasks.scheduler.runTimer(1L, ANIMATION_STEP_TICKS.toLong()) { animate() }
    }

    private fun animate() {
        animationTicks += ANIMATION_STEP_TICKS
        val rotate = animationTicks % ROTATION_TICKS == 0
        if (rotate) rotation += Math.PI.toFloat() + ROTATION_EPSILON
        val iterator = entities.entries.iterator()
        while (iterator.hasNext()) {
            val (_, tracked) = iterator.next()
            val pickup = plugin.server.getEntity(tracked.pickupId) as? Item
            if (pickup?.isValid != true) {
                tracked.displayId?.let { plugin.server.getEntity(it)?.remove() }
                iterator.remove()
                continue
            }
            val display = tracked.displayId?.let { plugin.server.getEntity(it) as? ItemDisplay }
            if (display?.isValid != true) continue
            if (rotate) {
                display.interpolationDelay = 0
                display.interpolationDuration = ROTATION_TICKS
                display.setTransformationMatrix(Matrix4f().scale(DISPLAY_SCALE).rotateY(rotation))
            }
            if (settings().ui.particles && animationTicks % PARTICLE_TICKS == 0) {
                display.world.spawnParticle(
                    Particle.END_ROD,
                    display.location,
                    1,
                    0.12,
                    0.08,
                    0.12,
                    0.001,
                )
            }
        }
        if (entities.isEmpty()) stopAnimation()
    }

    private fun stopAnimation() {
        animationTask?.cancel()
        animationTask = null
        animationTicks = 0
        rotation = 0f
    }

    companion object {
        private const val DISPLAY_HEIGHT = 0.45
        private const val DISPLAY_SCALE = 0.78f
        private const val ANIMATION_STEP_TICKS = 5
        private const val PARTICLE_TICKS = 10
        private const val ROTATION_TICKS = 40
        private const val ROTATION_EPSILON = 0.01f
    }
}
