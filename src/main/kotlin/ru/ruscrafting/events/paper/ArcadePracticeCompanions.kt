package ru.ruscrafting.events.paper

import net.kyori.adventure.text.Component
import org.bukkit.Color
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.entity.Display
import org.bukkit.entity.Player
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.LeatherArmorMeta
import org.bukkit.plugin.Plugin
import org.bukkit.util.Transformation
import org.bukkit.util.Vector
import org.joml.Quaternionf
import org.joml.Vector3f
import ru.arc.core.ScheduledTask
import ru.arc.core.Tasks
import ru.arc.paper.display.PacketBlockDisplay
import ru.arc.paper.display.PacketTextDisplay
import ru.arc.paper.display.PaperPacketDisplays
import ru.arc.paper.npc.PacketNpcPose
import ru.arc.paper.npc.PacketNpcProfile
import ru.arc.paper.npc.PacketPlayerNpc
import ru.arc.paper.npc.PaperPacketNpcs
import ru.ruscrafting.events.config.ArcEventsLocale
import ru.ruscrafting.events.domain.EventMode
import ru.ruscrafting.events.domain.QueuedPlayer
import java.util.UUID
import java.util.logging.Level
import kotlin.math.atan2
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.random.Random

/** These actors have no server entity, fishing hook, inventory, collision or drops. */
internal class ArcadePracticeCompanions(
    private val plugin: Plugin,
    private val displays: PaperPacketDisplays,
    private val owner: Player,
    private val mode: EventMode,
    private val matchId: UUID,
    private val locale: ArcEventsLocale,
    private val bot: QueuedPlayer?,
) : AutoCloseable {
    private val world = owner.world
    private val runner = if (mode == EventMode.DISASTERS) PracticeDisasterRunner(Random(matchId.leastSignificantBits)) else null
    private var stage = FishingArenaStage.CAMP
    private var hazard = ""
    private var age = 0
    private var closed = false
    private var npcs: PaperPacketNpcs? = null
    private var actor: PacketPlayerNpc? = null
    private var label: PacketTextDisplay? = null
    private var fishingLine: PacketBlockDisplay? = null
    private var floatTop: PacketBlockDisplay? = null
    private var floatBase: PacketBlockDisplay? = null
    private var task: ScheduledTask? = null

    fun start() {
        if (closed || npcs != null) return
        require(mode == EventMode.FISHING || (runner != null && bot != null))
        val origin = (runner?.position ?: PracticeHarbourCast.berth(stage)).toLocation(world)
        val id = bot?.playerId ?: matchId
        val renderer = PaperPacketNpcs(plugin, "practice-companions").also { npcs = it }
        try {
            val npc = renderer.spawnPlayer(origin, PacketNpcProfile(id, "rc_" + id.toString().take(8), null, null))
            actor = npc
            npc.isVisibleByDefault = false
            val fishing = mode == EventMode.FISHING
            val jacket = ItemStack.of(Material.LEATHER_CHESTPLATE)
            jacket.editMeta(LeatherArmorMeta::class.java) { it.setColor(Color.fromRGB(if (fishing) 0x418C88 else 0xED832F)) }
            npc.setEquipment(EquipmentSlot.CHEST, jacket)
            if (fishing) {
                npc.setEquipment(EquipmentSlot.HEAD, ItemStack.of(Material.LEATHER_HELMET))
                npc.setEquipment(EquipmentSlot.HAND, ItemStack.of(Material.FISHING_ROD))
                faceHarbour(npc)
            }
            val title = if (fishing) locale.render("practice.companion.angler", owner) else locale.render(
                "practice.companion.runner", owner, mapOf("name" to Component.text(requireNotNull(bot).playerName)),
            )
            label = displays.spawnText(origin, title).also {
                it.isVisibleByDefault = false
                it.billboard = Display.Billboard.CENTER
                it.isShadowed = true
                it.transformation = Transformation(Vector3f(0f, .2f, 0f), Quaternionf(), Vector3f(.82f), Quaternionf())
                it.attachTo(npc)
                it.showTo(owner)
            }
            npc.showTo(owner)
            task = Tasks.scheduler.runTimer(1L, 2L) {
                if (!closed && owner.isOnline && owner.world.uid == world.uid) {
                    if (fishing) animateCast(npc) else moveRunner(npc)
                }
            }
        } catch (error: Throwable) {
            close()
            throw error
        }
    }

    /** The session supplies the accepted island index in fishing and the active hazard in disasters. */
    fun tick(round: Int, hazard: String, active: Boolean) {
        if (closed) return
        this.hazard = if (active) hazard else ""
        if (mode != EventMode.FISHING) return
        val next = FishingArenaStage.entries.getOrNull(round) ?: return
        if (next != stage) {
            clearCast()
            stage = next
            age = 0
            actor?.let(::faceHarbour)
        }
    }

    private fun faceHarbour(npc: PacketPlayerNpc) {
        val berth = PracticeHarbourCast.berth(stage)
        val location = berth.toLocation(world).setDirection(PracticeHarbourCast.water(stage).subtract(berth))
        npc.teleport(location)
        npc.headYaw = location.yaw
    }

    private fun animateCast(npc: PacketPlayerNpc) {
        val frame = age
        age = (age + 2) % PracticeHarbourCast.PERIOD
        if (frame == 0) npc.pitch = -8f
        if (frame == PracticeHarbourCast.RELEASE || frame == PracticeHarbourCast.REEL) {
            npc.swingMainHand()
            npc.pitch = if (frame == PracticeHarbourCast.REEL) -13f else 13f
        }
        val bobber = PracticeHarbourCast.bobber(stage, frame)
        if (bobber == null) {
            if (fishingLine != null) clearCast()
            return
        }
        val tip = PracticeHarbourCast.tip(stage)
        val delta = bobber.clone().subtract(tip)
        val length = delta.length().toFloat().coerceAtLeast(.001f)
        val line = fishingLine ?: block(tip, Material.GRAY_CONCRETE).also { fishingLine = it }
        line.transformation = Transformation(Vector3f(-.012f, -.012f, 0f),
            Quaternionf().rotationTo(0f, 0f, 1f, delta.x.toFloat() / length, delta.y.toFloat() / length, delta.z.toFloat() / length),
            Vector3f(.024f, .024f, length), Quaternionf())
        val base = floatBase ?: block(bobber, Material.WHITE_CONCRETE).also {
            floatBase = it
            it.transformation = Transformation(Vector3f(-.10f, -.08f, -.10f), Quaternionf(), Vector3f(.20f, .13f, .20f), Quaternionf())
        }
        val top = floatTop ?: block(bobber, Material.RED_CONCRETE).also {
            floatTop = it
            it.transformation = Transformation(Vector3f(-.09f, .05f, -.09f), Quaternionf(), Vector3f(.18f, .15f, .18f), Quaternionf())
        }
        val at = bobber.toLocation(world)
        base.teleport(at)
        top.teleport(at)
    }

    private fun block(position: Vector, material: Material): PacketBlockDisplay =
        displays.spawnBlock(position.toLocation(world), material.createBlockData()).apply {
            isVisibleByDefault = false
            teleportDuration = 2
            interpolationDuration = 2
            showTo(owner)
        }

    private fun clearCast() {
        fishingLine?.remove()
        floatTop?.remove()
        floatBase?.remove()
        fishingLine = null
        floatTop = null
        floatBase = null
    }

    private fun moveRunner(npc: PacketPlayerNpc) {
        val runner = runner ?: return
        if (hazard.isEmpty() || !runner.alive) return
        val next = runner.step(hazard) ?: return
        npc.pose = if (hazard == "lightning" && next.x < -8.0) PacketNpcPose.CROUCHING else PacketNpcPose.STANDING
        npc.teleport(next.toLocation(world, runner.yaw, 0f))
        npc.headYaw = runner.yaw
    }

    fun location(): Location? = runner?.takeIf { !closed && it.alive && actor != null }?.position?.toLocation(world)

    fun hitByHazard(damage: Double): Boolean {
        val runner = runner ?: return false
        val npc = actor ?: return false
        if (closed || !runner.alive || damage <= 0.0 || !damage.isFinite()) return false
        npc.playHurtAnimation()
        if (!runner.hit(damage, world.gameTime)) return false
        npc.hideFrom(owner)
        return true
    }

    fun resetRound() {
        val runner = runner ?: return
        val npc = actor ?: return
        if (closed) return
        hazard = ""
        runner.reset()
        npc.pose = PacketNpcPose.STANDING
        npc.teleport(runner.position.toLocation(world))
        npc.headYaw = npc.bodyYaw
        npc.showTo(owner)
    }

    override fun close() {
        if (closed) return
        closed = true
        task?.cancel()
        clearCast()
        label?.remove()
        actor?.remove()
        npcs?.closeAsync()?.whenComplete { _, error ->
            if (error != null) plugin.logger.log(Level.WARNING, "Could not retire practice companion for $matchId", error)
        }
    }
}

internal object PracticeHarbourCast {
    const val RELEASE = 10
    const val LAND = 26
    const val REEL = 82
    const val FINISH = 96
    const val PERIOD = 116

    fun berth(stage: FishingArenaStage) = Vector(stage.centerX + 2.2, FishingArenaGenerator.SPAWN_Y.toDouble(), 15.4)
    fun tip(stage: FishingArenaStage) = berth(stage).add(Vector(-.09, 1.32, .83))
    fun water(stage: FishingArenaStage) = Vector(stage.centerX + 5.2, FishingArenaGenerator.WATER_SURFACE_Y + .98, 23.5)

    fun bobber(stage: FishingArenaStage, tick: Int): Vector? {
        if (tick !in RELEASE..FINISH) return null
        val tip = tip(stage)
        val water = water(stage)
        if (tick in LAND..REEL) {
            val dip = if (tick in REEL - 10..REEL) .13 * sin(Math.PI * (tick - REEL + 10) / 10) else 0.0
            return water.add(Vector(0.0, .045 * sin((tick - LAND) * Math.PI / 14) - dip, 0.0))
        }
        val outbound = tick < LAND
        val progress = if (outbound) (tick - RELEASE).toDouble() / (LAND - RELEASE) else (FINISH - tick).toDouble() / (FINISH - REEL)
        // The float clears the tip on its first and last visible frame.
        val distance = .025 + .975 * progress
        return tip.add(water.subtract(tip).multiply(distance)).add(Vector(0.0, (if (outbound) 1.3 else .55) * 4 * progress * (1 - progress), 0.0))
    }
}

/** The rival dodges on the wharf, shelters in the boat shed, and climbs the headland steps. */
internal class PracticeDisasterRunner(private val random: Random) {
    private val side = if (random.nextBoolean()) 1 else -1
    private var waypoint = 0
    private var waitTicks = 0
    private var health = 20.0
    private var lastHitTick: Long? = null
    val alive get() = health > 0.0
    var position = start()
        private set
    var yaw = 0f
        private set

    private fun start() = Vector(side * 4 + .5, 65.0, .5)

    fun reset() {
        health = 20.0
        lastHitTick = null
        position = start()
        waypoint = 0
        waitTicks = random.nextInt(6, 63)
    }

    fun hit(damage: Double, tick: Long): Boolean {
        // Simultaneous meteor circles overlap. A player receives one damage pulse at that tick.
        if (!alive || damage <= 0.0 || !damage.isFinite() || lastHitTick == tick) return false
        lastHitTick = tick
        health = (health - damage).coerceAtLeast(0.0)
        return !alive
    }

    /** One two-tick walking update. Pauses at turns can leave the rival inside a telegraphed meteor. */
    fun step(hazard: String): Vector? {
        if (!alive) return null
        if (waitTicks > 0) { waitTicks -= 2; return null }
        val target = when (hazard) {
            "meteors" -> meteorCorners[waypoint % meteorCorners.size].let { (x, z) -> side * x to z }
            "lightning" -> if (waypoint % 2 == 0) -12 to 0 else -16 to side
            "fog" -> if (waypoint % 2 == 0) 18 to 0 else 20 to side
            else -> return null
        }
        val dx = target.first + .5 - position.x
        val dz = target.second + .5 - position.z
        val distance = hypot(dx, dz)
        if (distance < .01) {
            waypoint++
            waitTicks = if (hazard == "meteors") random.nextInt(4, 61) else random.nextInt(10, 31)
            return null
        }
        val stride = minOf(1.0, .46 / distance)
        val x = position.x + dx * stride
        val z = position.z + dz * stride
        val blockX = floor(x).toInt()
        val blockZ = floor(z).toInt()
        // The marked lane runs from the wharf at 64 to the lookout at 69, beneath the shed roof.
        val highestFloor = if (blockX >= 8) 69 else 64
        val groundY = (highestFloor downTo 64).first { DisasterArenaGenerator.materialAt(blockX, it, blockZ) != null }
        val stair = DisasterArenaGenerator.materialAt(blockX, groundY, blockZ) == Material.SMOOTH_SANDSTONE_STAIRS
        val feetY = groundY + if (stair && x - blockX < .5) .5 else 1.0
        position = Vector(x, feetY, z)
        yaw = Math.toDegrees(atan2(-dx, dz)).toFloat()
        return position
    }

    private companion object {
        val meteorCorners = listOf(5 to 5, -5 to 5, -5 to -4, 5 to -4)
    }
}
