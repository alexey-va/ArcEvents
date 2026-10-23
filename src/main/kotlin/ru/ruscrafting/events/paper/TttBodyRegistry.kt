package ru.ruscrafting.events.paper

import net.kyori.adventure.text.Component
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.entity.Display
import org.bukkit.entity.Entity
import org.bukkit.entity.Interaction
import org.bukkit.entity.ItemDisplay
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.SkullMeta
import org.bukkit.persistence.PersistentDataType
import org.bukkit.plugin.Plugin
import org.bukkit.util.Transformation
import org.joml.AxisAngle4f
import org.joml.Vector3f
import ru.arc.core.ScheduledTask
import ru.arc.core.Tasks
import ru.arc.paper.display.PacketDisplay
import ru.arc.paper.display.PacketItemDisplay
import ru.arc.paper.display.PacketTextDisplay
import ru.arc.paper.display.PaperPacketDisplays
import ru.ruscrafting.events.config.ArcEventsLocale
import ru.ruscrafting.events.domain.CombatRecord
import ru.ruscrafting.events.domain.TttParticipant
import ru.ruscrafting.events.domain.TttRole
import java.util.UUID
import java.util.concurrent.CompletableFuture
import kotlin.math.cos
import kotlin.math.sin

enum class BodyVisualKind { HEAD, TORSO, LEFT_ARM, RIGHT_ARM, LEFT_LEG, RIGHT_LEG }

data class BodyVisualPart(
    val kind: BodyVisualKind,
    val material: Material,
    val localX: Double,
    val localZ: Double,
    val centerY: Double,
    val width: Float,
    val height: Float,
    val length: Float,
)

internal fun bodyVisualParts(): List<BodyVisualPart> = listOf(
    BodyVisualPart(BodyVisualKind.HEAD, Material.SKELETON_SKULL, 0.0, 0.72, 0.28, 0.5f, 0.5f, 0.5f),
    BodyVisualPart(BodyVisualKind.TORSO, Material.GRAY_CONCRETE, 0.0, 0.05, 0.16, 0.62f, 0.24f, 0.76f),
    BodyVisualPart(BodyVisualKind.LEFT_ARM, Material.LIGHT_GRAY_CONCRETE, -0.43, 0.05, 0.14, 0.20f, 0.20f, 0.76f),
    BodyVisualPart(BodyVisualKind.RIGHT_ARM, Material.LIGHT_GRAY_CONCRETE, 0.43, 0.05, 0.14, 0.20f, 0.20f, 0.76f),
    BodyVisualPart(BodyVisualKind.LEFT_LEG, Material.BLACK_CONCRETE, -0.17, -0.72, 0.14, 0.25f, 0.22f, 0.78f),
    BodyVisualPart(BodyVisualKind.RIGHT_LEG, Material.BLACK_CONCRETE, 0.17, -0.72, 0.14, 0.25f, 0.22f, 0.78f),
)

data class BodyRecord(
    val bodyId: UUID,
    val matchId: UUID,
    val victimId: UUID,
    val victimName: String,
    val role: TttRole,
    val killerId: UUID?,
    val killedAtMs: Long,
    val location: Location,
    val entityId: UUID,
    val visualEntityIds: Set<UUID>,
    val headEntityId: UUID,
    val labelEntityId: UUID,
    val weaponKey: String,
    val finalDamage: Double,
    val headshot: Boolean,
    var discovered: Boolean = false,
    var detectiveCalled: Boolean = false,
)

/** Owns native corpse evidence and cosmetic handles borrowed from the plugin display owner. */
class TttBodyRegistry(
    private val plugin: Plugin,
    private val locale: ArcEventsLocale,
    private val displays: PaperPacketDisplays,
    private val clock: () -> Long = System::currentTimeMillis,
    private val work: EventWork? = null,
) : AutoCloseable {
    private val bodyKey = NamespacedKey(plugin, "body_id")
    private val bodies = linkedMapOf<UUID, BodyRecord>()
    private class BodyEntities(
        val anchor: Interaction,
        val label: PacketTextDisplay,
        val head: PacketItemDisplay,
        val visuals: List<PacketDisplay>,
    ) {
        fun removals(): List<() -> Unit> = listOf(anchor::remove) + visuals.map { it::remove }
    }
    private var entityReferences = linkedMapOf<UUID, BodyEntities>()
    private val despawnTasks = mutableMapOf<UUID, ScheduledTask>()

    val size: Int
        get() = bodies.size

    fun get(bodyId: UUID): BodyRecord? = bodies[bodyId]

    fun records(): List<BodyRecord> = bodies.values.toList()

    fun bodyId(entityId: UUID): UUID? = bodies.values.firstOrNull { it.entityId == entityId }?.bodyId

    fun latest(matchId: UUID, victimId: UUID): BodyRecord? =
        bodies.values.lastOrNull { it.matchId == matchId && it.victimId == victimId }

    fun spawn(
        matchId: UUID,
        player: Player,
        participant: TttParticipant,
        killerId: UUID?,
        lethal: CombatRecord?,
        despawnTicks: Long,
    ): BodyRecord {
        val location = player.location.clone()
        val bodyId = UUID.randomUUID()
        val anchor = location.world.spawn(location.clone().add(0.0, 0.05, 0.0), Interaction::class.java) { interaction ->
            interaction.interactionWidth = 1.25f
            interaction.interactionHeight = 0.75f
            interaction.isResponsive = true
            interaction.isPersistent = false
            interaction.persistentDataContainer.set(bodyKey, PersistentDataType.STRING, bodyId.toString())
        }
        val visuals = arrayListOf<PacketDisplay>()
        try {
            val label = displays.spawnText(location.clone().add(0.0, 0.95, 0.0),
                locale.render("body.unidentified", values = mapOf("player" to Component.text("???"))))
                .also { display ->
                    visuals.add(display)
                    display.billboard = Display.Billboard.CENTER
                    display.isShadowed = true
                    display.isSeeThrough = false
                    display.lineWidth = 180
                    display.viewRange = 0.7f
                }
            var head: PacketItemDisplay? = null
            val visualEntityIds = bodyVisualParts().mapTo(linkedSetOf()) { part ->
                val partLocation = bodyPartLocation(location, part).also { it.pitch = 0f }
                if (part.kind == BodyVisualKind.HEAD) {
                    displays.spawnItem(partLocation, ItemStack.of(part.material)).also { display ->
                        visuals.add(display)
                        display.itemDisplayTransform = ItemDisplay.ItemDisplayTransform.HEAD
                        display.transformation = Transformation(
                            Vector3f(),
                            AxisAngle4f(Math.PI.toFloat() / 2f, 1f, 0f, 0f),
                            Vector3f(0.82f, 0.82f, 0.82f),
                            AxisAngle4f(),
                        )
                        configureDisplay(display)
                        head = display
                    }.uniqueId
                } else {
                    displays.spawnBlock(partLocation, plugin.server.createBlockData(part.material)).also { display ->
                        visuals.add(display)
                        display.transformation = Transformation(
                            Vector3f(-part.width / 2f, -part.height / 2f, -part.length / 2f),
                            AxisAngle4f(),
                            Vector3f(part.width, part.height, part.length),
                            AxisAngle4f(),
                        )
                        configureDisplay(display)
                    }.uniqueId
                }
            }
            val record = BodyRecord(
                bodyId = bodyId,
                matchId = matchId,
                victimId = participant.playerId,
                victimName = participant.playerName,
                role = participant.role,
                killerId = killerId,
                killedAtMs = clock(),
                location = location,
                entityId = anchor.uniqueId,
                visualEntityIds = visualEntityIds,
                headEntityId = requireNotNull(head).uniqueId,
                labelEntityId = label.uniqueId,
                weaponKey = lethal?.weapon ?: "environment",
                finalDamage = lethal?.finalDamage ?: 0.0,
                headshot = lethal?.headshot == true,
            )
            entityReferences[bodyId] = BodyEntities(anchor, label, requireNotNull(head), visuals)
            bodies[bodyId] = record
            despawnTasks[bodyId] = Tasks.scheduler.runLater(despawnTicks) {
                despawnTasks.remove(bodyId)
                remove(bodyId)
            }
            return record
        } catch (failure: Throwable) {
            bodies.remove(bodyId)
            entityReferences.remove(bodyId)
            (listOf(anchor::remove) + visuals.map { it::remove }).forEach { remove ->
                runCatching(remove).exceptionOrNull()?.let(failure::addSuppressed)
            }
            throw failure
        }
    }

    fun readBodyId(entity: Entity): UUID? = entity.persistentDataContainer
        .get(bodyKey, PersistentDataType.STRING)
        ?.let { runCatching { UUID.fromString(it) }.getOrNull() }

    fun reveal(bodyId: UUID, label: Component) {
        val record = bodies[bodyId] ?: return
        val entities = entityReferences[bodyId] ?: return
        entities.label.text(label)
        val head = ItemStack.of(Material.PLAYER_HEAD)
        head.editMeta(SkullMeta::class.java) {
            it.owningPlayer = plugin.server.getOfflinePlayer(record.victimId)
        }
        entities.head.itemStack = head
    }

    fun removeVictim(victimId: UUID) {
        bodies.values.filter { it.victimId == victimId }.map(BodyRecord::bodyId).forEach(::remove)
    }

    fun remove(bodyId: UUID) {
        despawnTasks.remove(bodyId)?.let { task -> runCatching(task::cancel) }
        bodies.remove(bodyId)?.let { record ->
            entityReferences.remove(bodyId)?.removals()?.forEach { remove ->
                runCatching(remove).onFailure { failure ->
                    plugin.logger.warning("ArcEvents could not remove TTT body ${record.bodyId}: ${failure.message}")
                }
            }
        }
    }

    fun clearAsync(): CompletableFuture<Unit> {
        despawnTasks.values.forEach { task -> runCatching(task::cancel) }
        despawnTasks.clear()
        val detached = entityReferences
        entityReferences = linkedMapOf()
        bodies.clear()
        val records = detached.values.iterator()
        var removals = emptyList<() -> Unit>().iterator()
        var failure: Throwable? = null
        val step = {
            if (!removals.hasNext() && records.hasNext()) removals = records.next().removals().iterator()
            if (removals.hasNext()) runCatching { removals.next().invoke() }.onFailure { problem ->
                if (failure == null) failure = problem else failure!!.addSuppressed(problem)
            }
            val done = !removals.hasNext() && !records.hasNext()
            if (done) failure?.let { throw it }
            done
        }
        return work?.cleanupNative(step) ?: runCatching { while (!step()) Unit }.fold(
            { CompletableFuture.completedFuture(Unit) }, CompletableFuture<Unit>::failedFuture)
    }

    fun clear() { clearAsync().whenComplete { _, failure ->
        if (failure != null) plugin.logger.warning("ArcEvents could not remove TTT bodies: ${failure.message}")
    } }

    override fun close() = clear()

    private fun configureDisplay(display: PacketDisplay) {
        display.viewRange = 0.8f
        display.shadowRadius = 0.0f
        display.shadowStrength = 0.0f
    }

    private fun bodyPartLocation(origin: Location, part: BodyVisualPart): Location {
        val yaw = Math.toRadians(origin.yaw.toDouble())
        val rightX = cos(yaw)
        val rightZ = sin(yaw)
        val forwardX = -sin(yaw)
        val forwardZ = cos(yaw)
        return origin.clone().add(
            rightX * part.localX + forwardX * part.localZ,
            part.centerY,
            rightZ * part.localX + forwardZ * part.localZ,
        )
    }
}
