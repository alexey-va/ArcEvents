package ru.ruscrafting.events.paper

import net.kyori.adventure.text.Component
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.entity.BlockDisplay
import org.bukkit.entity.Display
import org.bukkit.entity.Entity
import org.bukkit.entity.Interaction
import org.bukkit.entity.ItemDisplay
import org.bukkit.entity.Player
import org.bukkit.entity.TextDisplay
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.SkullMeta
import org.bukkit.persistence.PersistentDataType
import org.bukkit.plugin.Plugin
import org.bukkit.util.Transformation
import org.joml.AxisAngle4f
import org.joml.Vector3f
import ru.arc.core.ScheduledTask
import ru.arc.core.Tasks
import ru.ruscrafting.events.config.ArcEventsLocale
import ru.ruscrafting.events.domain.CombatRecord
import ru.ruscrafting.events.domain.TttParticipant
import ru.ruscrafting.events.domain.TttRole
import java.util.UUID
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

/** Owns the complete Bukkit lifecycle of TTT corpse evidence. */
class TttBodyRegistry(
    private val plugin: Plugin,
    private val locale: ArcEventsLocale,
    private val clock: () -> Long = System::currentTimeMillis,
) : AutoCloseable {
    private val bodyKey = NamespacedKey(plugin, "body_id")
    private val bodies = linkedMapOf<UUID, BodyRecord>()
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
        val label = location.world.spawn(location.clone().add(0.0, 0.95, 0.0), TextDisplay::class.java) { display ->
            display.text(locale.render("body.unidentified", values = mapOf("player" to Component.text("???"))))
            runCatching { display.billboard = Display.Billboard.CENTER }
            runCatching { display.isShadowed = true }
            runCatching { display.isSeeThrough = false }
            runCatching { display.lineWidth = 180 }
            runCatching { display.viewRange = 0.7f }
            display.isPersistent = false
        }
        var headEntityId: UUID? = null
        val visualEntityIds = bodyVisualParts().mapTo(linkedSetOf()) { part ->
            val partLocation = bodyPartLocation(location, part)
            if (part.kind == BodyVisualKind.HEAD) {
                location.world.spawn(partLocation, ItemDisplay::class.java) { display ->
                    display.setItemStack(ItemStack.of(part.material))
                    runCatching { display.itemDisplayTransform = ItemDisplay.ItemDisplayTransform.HEAD }
                    runCatching { display.setRotation(location.yaw, 0f) }
                    runCatching {
                        display.transformation = Transformation(
                            Vector3f(),
                            AxisAngle4f(Math.PI.toFloat() / 2f, 1f, 0f, 0f),
                            Vector3f(0.82f, 0.82f, 0.82f),
                            AxisAngle4f(),
                        )
                    }
                    configureDisplay(display)
                    headEntityId = display.uniqueId
                }.uniqueId
            } else {
                location.world.spawn(partLocation, BlockDisplay::class.java) { display ->
                    display.block = plugin.server.createBlockData(part.material)
                    runCatching { display.setRotation(location.yaw, 0f) }
                    runCatching {
                        display.transformation = Transformation(
                            Vector3f(-part.width / 2f, -part.height / 2f, -part.length / 2f),
                            AxisAngle4f(),
                            Vector3f(part.width, part.height, part.length),
                            AxisAngle4f(),
                        )
                    }
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
            headEntityId = requireNotNull(headEntityId),
            labelEntityId = label.uniqueId,
            weaponKey = lethal?.weapon ?: "environment",
            finalDamage = lethal?.finalDamage ?: 0.0,
            headshot = lethal?.headshot == true,
        )
        bodies[bodyId] = record
        despawnTasks[bodyId] = Tasks.scheduler.runLater(despawnTicks) {
            despawnTasks.remove(bodyId)
            remove(bodyId)
        }
        return record
    }

    fun readBodyId(entity: Entity): UUID? = entity.persistentDataContainer
        .get(bodyKey, PersistentDataType.STRING)
        ?.let { runCatching { UUID.fromString(it) }.getOrNull() }

    fun reveal(bodyId: UUID, label: Component) {
        val record = bodies[bodyId] ?: return
        (plugin.server.getEntity(record.labelEntityId) as? TextDisplay)?.text(label)
        val head = ItemStack.of(Material.PLAYER_HEAD)
        head.editMeta(SkullMeta::class.java) {
            it.owningPlayer = plugin.server.getOfflinePlayer(record.victimId)
        }
        (plugin.server.getEntity(record.headEntityId) as? ItemDisplay)?.setItemStack(head)
    }

    fun removeVictim(victimId: UUID) {
        bodies.values.filter { it.victimId == victimId }.map(BodyRecord::bodyId).forEach(::remove)
    }

    fun remove(bodyId: UUID) {
        despawnTasks.remove(bodyId)?.let { task -> runCatching(task::cancel) }
        bodies.remove(bodyId)?.let { record ->
            runCatching { record.entityIds().forEach { plugin.server.getEntity(it)?.remove() } }.onFailure { failure ->
                plugin.logger.warning("ArcEvents could not remove TTT body ${record.bodyId}: ${failure.message}")
            }
        }
    }

    fun clear() {
        despawnTasks.values.forEach { task -> runCatching(task::cancel) }
        despawnTasks.clear()
        bodies.values.forEach { record ->
            runCatching { record.entityIds().forEach { plugin.server.getEntity(it)?.remove() } }.onFailure { failure ->
                plugin.logger.warning("ArcEvents could not remove TTT body ${record.bodyId}: ${failure.message}")
            }
        }
        bodies.clear()
    }

    override fun close() = clear()

    private fun configureDisplay(display: Display) {
        runCatching { display.viewRange = 0.8f }
        runCatching { display.shadowRadius = 0.0f }
        runCatching { display.shadowStrength = 0.0f }
        display.isPersistent = false
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

private fun BodyRecord.entityIds(): Set<UUID> =
    visualEntityIds + entityId + labelEntityId
