package ru.ruscrafting.events.paper

import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.Particle
import org.bukkit.World
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
    private val firearms: TttFirearms,
    private val settings: () -> ArcEventsConfig,
) : AutoCloseable {
    private data class LootEntity(
        val pickupId: UUID,
        val itemDisplayId: UUID?,
        val effectDisplayId: UUID?,
        val chunkKey: LootChunkKey,
    )

    private val entities = linkedMapOf<UUID, LootEntity>()
    private val chunkTickets = LootChunkTicketRegistry(plugin)
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
        val chunkKey = chunkTickets.acquire(item.location)
        var itemDisplay: ItemDisplay? = null
        var effectDisplay: ItemDisplay? = null
        try {
            configurePickup(item, pickupDelay)
            itemDisplay = if (settings().ui.lootDisplays) createItemDisplay(item) else null
            effectDisplay = if (itemDisplay != null) createEffectDisplay(item) else null
            item.setVisibleByDefault(itemDisplay == null)
            entities[item.uniqueId] = LootEntity(item.uniqueId, itemDisplay?.uniqueId, effectDisplay?.uniqueId, chunkKey)
            ensureAnimation()
        } catch (failure: Throwable) {
            itemDisplay?.remove()
            effectDisplay?.remove()
            chunkTickets.release(chunkKey)
            throw failure
        }
    }

    fun consume(itemId: UUID) {
        val tracked = entities.remove(itemId) ?: return
        tracked.itemDisplayId?.let { plugin.server.getEntity(it)?.remove() }
        tracked.effectDisplayId?.let { plugin.server.getEntity(it)?.remove() }
        chunkTickets.release(tracked.chunkKey)
        if (entities.isEmpty()) stopAnimation()
    }

    fun clear() {
        entities.values.forEach { tracked ->
            tracked.itemDisplayId?.let { plugin.server.getEntity(it)?.remove() }
            tracked.effectDisplayId?.let { plugin.server.getEntity(it)?.remove() }
            plugin.server.getEntity(tracked.pickupId)?.remove()
        }
        entities.clear()
        chunkTickets.clear()
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

    private fun createItemDisplay(item: Item): ItemDisplay = item.world.spawn(
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

    private fun createEffectDisplay(item: Item): ItemDisplay? {
        val rarity = firearms.lootRarity(item.itemStack) ?: return null
        val effect = settings().weapons.lootEffect
        if (!effect.enabled) return null
        val visual = effect.visual(rarity)
        val material = Material.matchMaterial(visual.material)?.takeIf(Material::isItem) ?: return null
        if (visual.customModelData <= 0) return null
        val stack = ItemStack.of(material).also { shown ->
            shown.editMeta { meta ->
                val model = meta.customModelDataComponent
                model.floats = listOf(visual.customModelData.toFloat())
                meta.setCustomModelDataComponent(model)
            }
        }
        return item.world.spawn(
            item.location.clone().add(0.0, effect.height, 0.0),
            ItemDisplay::class.java,
        ) { display ->
            display.setItemStack(stack)
            display.itemDisplayTransform = ItemDisplay.ItemDisplayTransform.FIXED
            display.billboard = Display.Billboard.FIXED
            display.brightness = Display.Brightness(15, 15)
            display.setGravity(false)
            display.isInvulnerable = true
            display.isPersistent = false
            display.isSilent = true
            display.viewRange = 0.75f
            display.interpolationDelay = 0
            display.interpolationDuration = ROTATION_TICKS
            display.setTransformationMatrix(Matrix4f().scale(effect.scale.toFloat()))
        }
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
            val entry = iterator.next()
            val tracked = entry.value
            val pickup = plugin.server.getEntity(tracked.pickupId) as? Item
            if (pickup?.isValid != true) {
                tracked.itemDisplayId?.let { plugin.server.getEntity(it)?.remove() }
                tracked.effectDisplayId?.let { plugin.server.getEntity(it)?.remove() }
                iterator.remove()
                chunkTickets.release(tracked.chunkKey)
                continue
            }
            val display = tracked.itemDisplayId?.let { plugin.server.getEntity(it) as? ItemDisplay }
            if (display?.isValid != true) {
                tracked.effectDisplayId?.let { plugin.server.getEntity(it)?.remove() }
                pickup.setVisibleByDefault(true)
                entry.setValue(tracked.copy(itemDisplayId = null, effectDisplayId = null))
                continue
            }
            val effectDisplay = tracked.effectDisplayId?.let { plugin.server.getEntity(it) as? ItemDisplay }
            val current = if (tracked.effectDisplayId != null && effectDisplay?.isValid != true) {
                tracked.copy(effectDisplayId = null).also(entry::setValue)
            } else {
                tracked
            }
            if (rotate) {
                display.interpolationDelay = 0
                display.interpolationDuration = ROTATION_TICKS
                display.setTransformationMatrix(Matrix4f().scale(DISPLAY_SCALE).rotateY(rotation))
                effectDisplay?.takeIf(ItemDisplay::isValid)?.also { animatedEffect ->
                    animatedEffect.interpolationDelay = 0
                    animatedEffect.interpolationDuration = ROTATION_TICKS
                    animatedEffect.setTransformationMatrix(
                        Matrix4f().scale(settings().weapons.lootEffect.scale.toFloat()).rotateY(-rotation * 0.5f),
                    )
                }
            }
            if (current.effectDisplayId == null && settings().ui.particles && animationTicks % PARTICLE_TICKS == 0) {
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

internal data class LootChunkKey(val worldId: UUID, val x: Int, val z: Int)

/** Keeps non-persistent TTT pickups and displays loaded for the lifetime of their loot scene. */
internal class LootChunkTicketRegistry(private val plugin: Plugin) {
    private data class Ticket(val world: World, var references: Int, val removeOnRelease: Boolean)

    private val tickets = linkedMapOf<LootChunkKey, Ticket>()

    fun acquire(location: Location): LootChunkKey {
        val world = location.world
        val key = LootChunkKey(world.uid, location.blockX shr 4, location.blockZ shr 4)
        val current = tickets[key]
        if (current != null) {
            current.references += 1
            return key
        }
        val added = world.addPluginChunkTicket(key.x, key.z, plugin)
        tickets[key] = Ticket(world, 1, added)
        return key
    }

    fun release(key: LootChunkKey) {
        val ticket = tickets[key] ?: return
        ticket.references -= 1
        if (ticket.references > 0) return
        tickets.remove(key)
        if (ticket.removeOnRelease) ticket.world.removePluginChunkTicket(key.x, key.z, plugin)
    }

    fun clear() {
        tickets.forEach { (key, ticket) ->
            if (ticket.removeOnRelease) ticket.world.removePluginChunkTicket(key.x, key.z, plugin)
        }
        tickets.clear()
    }
}
