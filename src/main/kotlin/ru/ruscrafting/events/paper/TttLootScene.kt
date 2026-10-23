package ru.ruscrafting.events.paper

import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.Particle
import org.bukkit.entity.Display
import org.bukkit.entity.Item
import org.bukkit.entity.ItemDisplay
import org.bukkit.inventory.ItemStack
import org.bukkit.plugin.Plugin
import org.bukkit.util.Transformation
import org.bukkit.util.Vector
import org.joml.AxisAngle4f
import org.joml.Vector3f
import ru.arc.core.ScheduledTask
import ru.arc.core.Tasks
import ru.arc.paper.chunk.PaperChunkKey
import ru.arc.paper.chunk.PaperChunkTicketAcquireResult
import ru.arc.paper.chunk.PaperChunkTicketLease
import ru.arc.paper.chunk.PaperChunkTicketRegistry
import ru.arc.paper.display.PacketItemDisplay
import ru.arc.paper.display.PaperPacketDisplays
import java.util.concurrent.CompletableFuture
import ru.ruscrafting.events.config.ArcEventsConfig
import java.util.UUID
import java.util.logging.Level

/** Owns server-authoritative pickups and their optional animated ItemDisplay presentation. */
class TttLootScene(
    private val plugin: Plugin,
    private val firearms: TttFirearms,
    private val displays: PaperPacketDisplays,
    private val work: EventWork? = null,
    private val chunkTickets: PaperChunkTicketRegistry = PaperChunkTicketRegistry(plugin),
    private val settings: () -> ArcEventsConfig,
) : AutoCloseable {
    private class LootEntity(
        val pickup: Item,
        var itemDisplay: PacketItemDisplay?,
        var effectDisplay: PacketItemDisplay?,
        val chunkLease: PaperChunkTicketLease,
    )

    private var entities = linkedMapOf<UUID, LootEntity>()
    private var presentationOrder = ArrayDeque<LootEntity>()
    private var sceneEpoch = 0L
    private var presentationEpoch = 0L
    private var presentationWork: CompletableFuture<Unit>? = null
    private var cleanupTail = CompletableFuture.completedFuture(Unit)
    private var animationTask: ScheduledTask? = null
    private var animationTicks = 0
    private var rotation = 0f
    private var itemDisplayFailureLogged = false
    private var effectDisplayFailureLogged = false
    private var cleanupFailureLogged = false

    val size: Int get() = entities.size

    fun spawn(location: Location, stack: ItemStack): Item? {
        val spawn = TttLootPlacement.nearby(location) ?: return null
        return drop(spawn, stack)
    }

    fun spawnExact(location: Location, stack: ItemStack): Item? {
        val spawn = TttLootPlacement.exact(location) ?: return null
        return drop(spawn, stack)
    }

    private fun drop(spawn: Location, stack: ItemStack): Item {
        val item = spawn.world.dropItem(spawn, stack) { configurePickup(it, pickupDelay = 0) }
        try {
            register(item, pickupDelay = 0)
        } catch (failure: Throwable) {
            runCatching(item::remove)
            throw failure
        }
        return item
    }

    fun register(item: Item, pickupDelay: Int = 20) {
        consume(item.uniqueId)
        val location = item.location
        check(location.world.isChunkLoaded(location.blockX shr 4, location.blockZ shr 4)) { "Loot chunk is not loaded" }
        val chunkLease = (chunkTickets.acquire(PaperChunkKey(location.world.uid, location.blockX shr 4, location.blockZ shr 4))
            as? PaperChunkTicketAcquireResult.Acquired)?.lease ?: error("Could not retain loot chunk")
        var itemDisplay: PacketItemDisplay? = null
        var effectDisplay: PacketItemDisplay? = null
        try {
            configurePickup(item, pickupDelay)
            itemDisplay = if (settings().ui.lootDisplays) {
                runCatching { createItemDisplay(item) }
                    .onFailure { logPresentationFailure(effect = false, it) }
                    .getOrNull()
            } else {
                null
            }
            effectDisplay = if (itemDisplay != null) {
                runCatching { createEffectDisplay(item) }
                    .onFailure { logPresentationFailure(effect = true, it) }
                    .getOrNull()
            } else {
                null
            }
            item.setVisibleByDefault(itemDisplay == null)
            val tracked = LootEntity(item, itemDisplay, effectDisplay, chunkLease)
            entities[item.uniqueId] = tracked
            presentationOrder.addLast(tracked)
            if (itemDisplay != null) ensureAnimation()
        } catch (failure: Throwable) {
            entities.remove(item.uniqueId)
            removeDisplay(itemDisplay)
            removeDisplay(effectDisplay)
            releaseChunk(chunkLease)
            throw failure
        }
    }

    /** Rebuilds presentation through the shared budget; authoritative stacks and pickup order survive. */
    fun reconfigure(): CompletableFuture<Unit> {
        stopAnimation()
        val epoch = ++presentationEpoch
        val source = sceneEpoch
        val tasks = work
        val count = presentationOrder.size
        var remaining = count
        fun step(): Boolean {
            if (remaining > 0 && presentationOrder.isNotEmpty()) {
                remaining--
                val tracked = presentationOrder.removeFirst()
                if (entities[tracked.pickup.uniqueId] === tracked) {
                    if (!tracked.pickup.isValid) consume(tracked.pickup.uniqueId)
                    else {
                        rebuildPresentation(tracked)
                        presentationOrder.addLast(tracked)
                    }
                }
            }
            return remaining == 0 || presentationOrder.isEmpty()
        }
        val result = if (tasks == null) runCatching { while (!step()) Unit }.fold(
            { CompletableFuture.completedFuture(Unit) }, CompletableFuture<Unit>::failedFuture,
        ) else tasks.capture({ sceneEpoch == source && presentationEpoch == epoch }, ::step)
        presentationWork = result
        result.whenComplete { _, failure ->
            if (presentationWork === result) presentationWork = null
            if (failure == null && sceneEpoch == source && presentationEpoch == epoch && entities.isNotEmpty()) ensureAnimation()
        }
        return result
    }

    private fun rebuildPresentation(tracked: LootEntity) {
        val pickup = tracked.pickup
        val previousDisplay = tracked.itemDisplay
        val previousEffect = tracked.effectDisplay
        var candidateDisplay: PacketItemDisplay? = null
        var candidateEffect: PacketItemDisplay? = null
        runCatching {
            pickup.itemStack = firearms.refreshItem(pickup.itemStack, null)
            candidateDisplay = if (settings().ui.lootDisplays) createItemDisplay(pickup) else null
            candidateEffect = candidateDisplay?.let { createEffectDisplay(pickup) }
            pickup.setVisibleByDefault(candidateDisplay == null)
            tracked.itemDisplay = candidateDisplay
            tracked.effectDisplay = candidateEffect
            removeDisplay(previousDisplay)
            removeDisplay(previousEffect)
        }.onFailure { failure ->
            logPresentationFailure(effect = false, failure)
            removeDisplay(candidateDisplay)
            removeDisplay(candidateEffect)
            runCatching { pickup.setVisibleByDefault(previousDisplay == null) }.onFailure(::logCleanupFailure)
        }
    }

    fun consume(itemId: UUID) {
        val tracked = entities.remove(itemId) ?: return
        removeDisplay(tracked.itemDisplay)
        removeDisplay(tracked.effectDisplay)
        releaseChunk(tracked.chunkLease)
        if (entities.isEmpty()) {
            presentationOrder = ArrayDeque()
            stopAnimation()
        }
    }

    fun clearAsync(): CompletableFuture<Unit> {
        sceneEpoch++
        presentationEpoch++
        stopAnimation()
        val detached = entities
        entities = linkedMapOf()
        presentationOrder = ArrayDeque()
        if (detached.isEmpty()) return cleanupTail
        val iterator = detached.values.iterator()
        var current: LootEntity? = null
        var phase = 0
        var failure: Throwable? = null
        // Only concrete pickup/visual references and leases survive consumer shutdown in this callback.
        val step = {
            if (current == null && iterator.hasNext()) current = iterator.next()
            current?.let { tracked ->
                runCatching {
                    when (phase++) {
                        0 -> tracked.itemDisplay?.remove()
                        1 -> tracked.effectDisplay?.remove()
                        2 -> tracked.pickup.remove()
                        else -> tracked.chunkLease.close()
                    }
                }.onFailure { problem ->
                    if (failure == null) failure = problem else failure?.addSuppressed(problem)
                }
                if (phase == 4) { current = null; phase = 0 }
            }
            val done = current == null && !iterator.hasNext()
            if (done) failure?.let { throw it }
            done
        }
        val cleaned = work?.cleanupNative(step) ?: runCatching { while (!step()) Unit }.fold(
            { CompletableFuture.completedFuture(Unit) }, CompletableFuture<Unit>::failedFuture,
        )
        cleanupTail = CompletableFuture.allOf(cleanupTail, cleaned).thenApply { Unit }
        return cleanupTail
    }

    fun clear() { clearAsync().whenComplete { _, failure -> failure?.let(::logCleanupFailure) } }
    override fun close() = clear()

    private fun configurePickup(item: Item, pickupDelay: Int) {
        item.pickupDelay = pickupDelay.coerceAtLeast(0)
        item.setUnlimitedLifetime(true)
        item.setCanMobPickup(false)
        item.setGravity(true)
        item.velocity = Vector()
        item.isPersistent = false
    }

    private fun createItemDisplay(item: Item): PacketItemDisplay {
        val presentation = settings().ui.lootDisplay
        val shown = item.itemStack.clone().also { it.amount = 1 }
        return createDisplay(item.location.clone().add(0.0, presentation.height, 0.0), shown, presentation.scale)
    }

    private fun createEffectDisplay(item: Item): PacketItemDisplay? {
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
        return createDisplay(item.location.clone().add(0.0, effect.height, 0.0), stack, effect.scale,
            Display.Brightness(15, 15))
    }

    private fun createDisplay(
        location: Location,
        stack: ItemStack,
        scale: Double,
        brightness: Display.Brightness? = null,
    ): PacketItemDisplay {
        val display = displays.spawnItem(location, stack)
        try {
            display.itemDisplayTransform = ItemDisplay.ItemDisplayTransform.FIXED
            display.billboard = Display.Billboard.FIXED
            display.brightness = brightness
            display.viewRange = settings().ui.lootDisplay.viewRange.toFloat()
            display.interpolationDelay = 0
            display.interpolationDuration = settings().ui.lootDisplay.rotationTicks
            display.transformation = displayTransform(scale, 0f)
            return display
        } catch (failure: Throwable) {
            runCatching(display::remove).exceptionOrNull()?.let(failure::addSuppressed)
            throw failure
        }
    }

    private fun displayTransform(scale: Double, rotation: Float) = Transformation(
        Vector3f(), AxisAngle4f(rotation, 0f, 1f, 0f), Vector3f(scale.toFloat()), AxisAngle4f(),
    )

    private fun ensureAnimation() {
        if (animationTask != null) return
        animationTask = Tasks.scheduler.runTimer(1L, settings().ui.lootDisplay.animationStepTicks) { animate() }
    }

    private fun animate() {
        if (presentationWork?.isDone == false) return
        val presentation = settings().ui.lootDisplay
        val previousTicks = animationTicks
        animationTicks += presentation.animationStepTicks.toInt()
        val rotate = animationTicks / presentation.rotationTicks > previousTicks / presentation.rotationTicks
        val emitParticle = animationTicks / presentation.particleIntervalTicks > previousTicks / presentation.particleIntervalTicks
        if (rotate) rotation += Math.PI.toFloat() + ROTATION_EPSILON
        val source = sceneEpoch
        val epoch = presentationEpoch
        var remaining = presentationOrder.size
        fun step(): Boolean {
            if (remaining > 0 && presentationOrder.isNotEmpty()) {
                remaining--
                val tracked = presentationOrder.removeFirst()
                if (entities[tracked.pickup.uniqueId] === tracked) {
                    animateEntity(tracked, rotate, emitParticle)
                    if (entities[tracked.pickup.uniqueId] === tracked) presentationOrder.addLast(tracked)
                }
            }
            return remaining == 0 || presentationOrder.isEmpty()
        }
        val result = work?.capture({ sceneEpoch == source && presentationEpoch == epoch }, ::step)
            ?: runCatching { while (!step()) Unit }.fold(
                { CompletableFuture.completedFuture(Unit) }, CompletableFuture<Unit>::failedFuture,
            )
        presentationWork = result
        result.whenComplete { _, failure ->
            if (presentationWork === result) presentationWork = null
            if (failure != null && sceneEpoch == source && presentationEpoch == epoch) logPresentationFailure(false, failure)
            if (entities.isEmpty()) stopAnimation()
        }
    }

    private fun animateEntity(tracked: LootEntity, rotate: Boolean, emitParticle: Boolean) {
        val pickup = tracked.pickup
        if (!pickup.isValid) { consume(pickup.uniqueId); return }
        val display = tracked.itemDisplay
        if (display?.isValid != true) {
            removeDisplay(tracked.effectDisplay)
            runCatching { pickup.setVisibleByDefault(true) }.onFailure { logPresentationFailure(effect = false, it) }
            tracked.itemDisplay = null
            tracked.effectDisplay = null
            return
        }
        val effectDisplay = tracked.effectDisplay?.takeIf { it.isValid }
        tracked.effectDisplay = effectDisplay
        val presentation = settings().ui.lootDisplay
        runCatching {
            display.teleport(pickup.location.clone().add(0.0, presentation.height, 0.0))
            effectDisplay?.teleport(pickup.location.clone().add(0.0, settings().weapons.lootEffect.height, 0.0))
        }.onFailure { logPresentationFailure(effect = false, it) }
        if (rotate) {
            runCatching {
                display.interpolationDelay = 0
                display.interpolationDuration = presentation.rotationTicks
                display.transformation = displayTransform(presentation.scale, rotation)
            }.onFailure { logPresentationFailure(effect = false, it) }
            effectDisplay?.also { animatedEffect ->
                runCatching {
                    animatedEffect.interpolationDelay = 0
                    animatedEffect.interpolationDuration = presentation.rotationTicks
                    animatedEffect.transformation = displayTransform(settings().weapons.lootEffect.scale, -rotation * 0.5f)
                }.onFailure { logPresentationFailure(effect = true, it) }
            }
        }
        if (effectDisplay == null && settings().ui.particles && emitParticle) {
            runCatching { pickup.world.spawnParticle(Particle.END_ROD, display.location, 1, 0.12, 0.08, 0.12, 0.001) }
                .onFailure { logPresentationFailure(effect = true, it) }
        }
    }

    private fun stopAnimation() {
        animationTask?.let { task -> runCatching(task::cancel).onFailure(::logCleanupFailure) }
        animationTask = null
        animationTicks = 0
        rotation = 0f
    }

    private fun removeDisplay(display: PacketItemDisplay?) {
        if (display == null) return
        runCatching(display::remove).onFailure(::logCleanupFailure)
    }

    private fun releaseChunk(lease: PaperChunkTicketLease) {
        runCatching(lease::close).onFailure(::logCleanupFailure)
    }

    private fun logPresentationFailure(effect: Boolean, failure: Throwable) {
        if (effect) {
            if (effectDisplayFailureLogged) return
            effectDisplayFailureLogged = true
            plugin.logger.log(Level.WARNING, "ArcEvents loot VFX is unavailable; pickups remain usable", failure)
        } else {
            if (itemDisplayFailureLogged) return
            itemDisplayFailureLogged = true
            plugin.logger.log(Level.WARNING, "ArcEvents loot display is unavailable; using visible pickups", failure)
        }
    }

    private fun logCleanupFailure(failure: Throwable) {
        if (cleanupFailureLogged) return
        cleanupFailureLogged = true
        plugin.logger.log(Level.WARNING, "ArcEvents could not fully clean one loot presentation", failure)
    }

    companion object {
        private const val ROTATION_EPSILON = 0.01f
    }
}

/** Shared placement contract for the editor and the actual loot spawn path. */
object TttLootPlacement {
    fun exact(origin: Location): Location? {
        val world = origin.world
        if (!world.isChunkLoaded(origin.blockX shr 4, origin.blockZ shr 4)) return null
        val feet = world.getBlockAt(origin.blockX, origin.blockY, origin.blockZ)
        val head = feet.getRelative(0, 1, 0)
        val floor = feet.getRelative(0, -1, 0)
        if (!feet.isPassable || !head.isPassable || !floor.type.isSolid || floor.type == Material.BARRIER) return null
        return Location(world, feet.x + 0.5, feet.y + 0.1, feet.z + 0.5, origin.yaw, origin.pitch)
    }

    fun nearby(origin: Location): Location? {
        val world = origin.world
        val baseX = origin.blockX
        val baseY = origin.blockY
        val baseZ = origin.blockZ
        val yOffsets = intArrayOf(0, -1, 1, -2, 2, -3, 3)
        for (radius in 0..MAX_PLACEMENT_RADIUS) {
            for (yOffset in yOffsets) for (xOffset in -radius..radius) for (zOffset in -radius..radius) {
                if (kotlin.math.abs(xOffset) + kotlin.math.abs(zOffset) != radius) continue
                exact(
                    Location(
                        world,
                        baseX + xOffset + 0.5,
                        baseY + yOffset + 0.1,
                        baseZ + zOffset + 0.5,
                        origin.yaw,
                        origin.pitch,
                    ),
                )?.let { return it }
            }
        }
        return null
    }

    private const val MAX_PLACEMENT_RADIUS = 4
}
