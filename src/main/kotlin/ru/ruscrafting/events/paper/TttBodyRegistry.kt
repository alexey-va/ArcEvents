package ru.ruscrafting.events.paper

import net.kyori.adventure.text.Component
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.entity.ArmorStand
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.SkullMeta
import org.bukkit.persistence.PersistentDataType
import org.bukkit.plugin.Plugin
import ru.arc.core.ScheduledTask
import ru.arc.core.Tasks
import ru.ruscrafting.events.config.ArcEventsLocale
import ru.ruscrafting.events.domain.CombatRecord
import ru.ruscrafting.events.domain.TttParticipant
import ru.ruscrafting.events.domain.TttRole
import java.util.UUID

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
        val head = ItemStack.of(Material.PLAYER_HEAD)
        head.editMeta(SkullMeta::class.java) { it.owningPlayer = plugin.server.getOfflinePlayer(player.uniqueId) }
        val bodyId = UUID.randomUUID()
        val stand = location.world.spawn(location, ArmorStand::class.java) { armorStand ->
            armorStand.isVisible = false
            armorStand.setGravity(false)
            armorStand.isSmall = true
            armorStand.isSilent = true
            armorStand.isInvulnerable = true
            armorStand.isCollidable = false
            armorStand.isCustomNameVisible = true
            armorStand.customName(locale.render("body.unidentified", values = mapOf("player" to Component.text("???"))))
            armorStand.equipment.helmet = head
            armorStand.persistentDataContainer.set(bodyKey, PersistentDataType.STRING, bodyId.toString())
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
            entityId = stand.uniqueId,
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

    fun readBodyId(stand: ArmorStand): UUID? = stand.persistentDataContainer
        .get(bodyKey, PersistentDataType.STRING)
        ?.let { runCatching { UUID.fromString(it) }.getOrNull() }

    fun removeVictim(victimId: UUID) {
        bodies.values.filter { it.victimId == victimId }.map(BodyRecord::bodyId).forEach(::remove)
    }

    fun remove(bodyId: UUID) {
        despawnTasks.remove(bodyId)?.let { task -> runCatching(task::cancel) }
        bodies.remove(bodyId)?.let { record ->
            runCatching { plugin.server.getEntity(record.entityId)?.remove() }.onFailure { failure ->
                plugin.logger.warning("ArcEvents could not remove TTT body ${record.bodyId}: ${failure.message}")
            }
        }
    }

    fun clear() {
        despawnTasks.values.forEach { task -> runCatching(task::cancel) }
        despawnTasks.clear()
        bodies.values.forEach { record ->
            runCatching { plugin.server.getEntity(record.entityId)?.remove() }.onFailure { failure ->
                plugin.logger.warning("ArcEvents could not remove TTT body ${record.bodyId}: ${failure.message}")
            }
        }
        bodies.clear()
    }

    override fun close() = clear()
}
