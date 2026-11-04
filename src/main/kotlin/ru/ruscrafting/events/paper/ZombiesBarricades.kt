package ru.ruscrafting.events.paper

import net.kyori.adventure.text.Component
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.Particle
import org.bukkit.Sound
import org.bukkit.World
import org.bukkit.block.data.BlockData
import org.bukkit.entity.Display
import org.bukkit.entity.Player
import org.bukkit.entity.Zombie
import org.bukkit.util.Transformation
import org.joml.AxisAngle4f
import org.joml.Vector3f
import ru.arc.paper.display.PacketBlockDisplay
import ru.arc.paper.display.PaperPacketDisplays
import ru.ruscrafting.events.config.ArcEventsLocale
import java.util.UUID
import kotlin.math.abs

/** Authored ingress only: no player blocks, drops, repair points, or free building. */
internal class ZombiesBarricades(
    private val world: World,
    private val locale: ArcEventsLocale,
    private val players: Collection<Player>,
    private val displays: PaperPacketDisplays,
    private val standing: (Player) -> Boolean,
    private val present: (Player) -> Boolean,
    private val enemies: () -> Collection<Zombie>,
    private val damagedAt: (UUID) -> Long,
    private val sounds: () -> Boolean,
    private val particles: () -> Boolean,
) : AutoCloseable {
    private data class Repair(val player: UUID, val beganAt: Long)
    private data class Gate(val window: ZombiesArenaGenerator.Companion.Window,
        val originals: List<BlockData>, val boards: MutableList<PacketBlockDisplay> = mutableListOf(),
        var attackAt: Long = 0, var repair: Repair? = null)
    private val gates = mutableListOf<Gate>()

    fun start() {
        // Validate all fixed cells before touching any of them; v1/foreign arenas cannot be rebuilt here.
        val plans = ZombiesArenaGenerator.WINDOWS.map { window ->
            val originals = window.gateCells().map { (x, y, z) ->
                require(world.isChunkLoaded(x shr 4, z shr 4)) { "Zombies ingress chunk is not loaded" }
                val block = world.getBlockAt(x, y, z)
                require(block.type == Material.SPRUCE_FENCE) { "Zombies ingress does not match the v2 arena at $x,$y,$z" }
                block.blockData.clone()
            }
            Gate(window, originals)
        }
        for (gate in plans) {
            gates += gate
            repeat(MAX_BOARDS) { addBoard(gate) }
        }
    }

    fun at(location: Location): Boolean = location.world.uid == world.uid && location.blockY in 65..68 &&
        gates.any { abs(it.window.along(location.blockX + .5, location.blockZ + .5)) <= 2 &&
            abs(it.window.inward(location.blockX + .5, location.blockZ + .5)) < .1 }

    fun interact(player: Player) {
        val gate = nearest(player) ?: return
        player.sendEventActionBar(locale.render(if (gate.boards.size == MAX_BOARDS) "zombies.barricade.full" else "zombies.barricade.hint",
            player, values(gate)))
    }

    fun tick(now: Long) {
        for (gate in gates) {
            if (enemies().none { it.isValid && !it.isDead && it.world.uid == world.uid &&
                    gate.window.inward(it.location.x, it.location.z) < 1.1 &&
                    abs(gate.window.along(it.location.x, it.location.z)) <= 1.8 }) gate.attackAt = 0
            if (gate.boards.size == MAX_BOARDS) { gate.repair = null; continue }
            val helper = players.firstOrNull { standing(it) && it.isSneaking && nearest(it) === gate && aimsAt(it, gate) }
            if (helper == null || gate.boards.isEmpty() && occupied(gate)) { gate.repair = null; continue }
            val repair = gate.repair?.takeIf { it.player == helper.uniqueId && damagedAt(it.player) < it.beganAt }
                ?: Repair(helper.uniqueId, now).also { gate.repair = it }
            if (now - repair.beganAt < REPAIR_MS) continue
            if (gate.boards.isEmpty()) setCollision(gate, true)
            addBoard(gate)
            gate.repair = Repair(helper.uniqueId, now)
            helper.sendEventActionBar(locale.render("zombies.barricade.repaired", helper, values(gate)))
            if (sounds()) world.playSound(center(gate), Sound.BLOCK_WOOD_PLACE, .75f, .9f)
        }
    }

    /** Returns true until this actor has passed its own entrance. Closed gates never use the arena unstuck teleport. */
    fun entering(mob: Zombie, window: ZombiesArenaGenerator.Companion.Window, now: Long): Boolean {
        val gate = gates.first { it.window == window }
        val inside = window.inward(mob.location.x, mob.location.z)
        if (gate.boards.isEmpty()) {
            mob.isAware = true
            if (inside >= 1.1) return false
            mob.target = null
            mob.pathfinder.moveTo(Location(world, window.x + .5 + window.inwardX * 2.5, 65.0,
                window.z + .5 + window.inwardZ * 2.5), 1.0)
            return true
        }
        mob.target = null
        mob.isAware = false
        // Native pushing cannot move a queued actor around its assigned window before a breach.
        if (inside > .25 || abs(window.along(mob.location.x, mob.location.z)) > 1.6) mob.teleport(spawnLocation(window))
        if (gate.attackAt == 0L) gate.attackAt = now + STRIKE_MS
        if (now >= gate.attackAt) {
            gate.attackAt = now + STRIKE_MS
            mob.swingMainHand()
            gate.boards.removeLast().remove()
            if (particles()) world.spawnParticle(Particle.BLOCK, center(gate), 14, .75, .7, .12,
                Material.SPRUCE_PLANKS.createBlockData())
            if (sounds()) world.playSound(center(gate), Sound.ENTITY_ZOMBIE_ATTACK_WOODEN_DOOR, .7f, .8f)
            if (gate.boards.isEmpty()) {
                setCollision(gate, false)
                if (sounds()) world.playSound(center(gate), Sound.ENTITY_ZOMBIE_BREAK_WOODEN_DOOR, .65f, 1f)
            }
        }
        return true
    }

    fun prompt(player: Player): Component? {
        val gate = nearest(player) ?: return null
        if (gate.boards.size == MAX_BOARDS || !aimsAt(player, gate)) return null
        return locale.render(if (gate.repair?.player == player.uniqueId) "zombies.barricade.repairing" else "zombies.barricade.hint",
            player, values(gate))
    }

    fun boardCounts(): List<Int> = gates.map { it.boards.size }

    private fun nearest(player: Player): Gate? {
        if (!standing(player)) return null
        return gates.firstOrNull { gate ->
            val point = player.location
            gate.window.inward(point.x, point.z) >= .6 &&
                point.distanceSquared(center(gate).subtract(0.0, 1.0, 0.0)) <= 3.25 * 3.25
        }
    }
    private fun aimsAt(player: Player, gate: Gate): Boolean {
        val toward = center(gate).toVector().subtract(player.eyeLocation.toVector())
        return toward.lengthSquared() > .01 && player.eyeLocation.direction.dot(toward.normalize()) >= .82
    }
    private fun occupied(gate: Gate): Boolean {
        // Never rebuild in a player or trap somebody in the shallow spawn alcove. Include every live actor.
        fun inThreshold(at: Location) = at.world.uid == world.uid &&
            gate.window.inward(at.x, at.z) in -2.6..1.1 && abs(gate.window.along(at.x, at.z)) <= 1.8
        return players.any { present(it) && inThreshold(it.location) } ||
            enemies().any { it.isValid && !it.isDead && it.world.uid == world.uid &&
                gate.window.inward(it.location.x, it.location.z) in -.9..1.1 &&
                abs(gate.window.along(it.location.x, it.location.z)) <= 1.8 }
    }
    private fun addBoard(gate: Gate) {
        val part = board(gate.window, gate.boards.size)
        val display = displays.spawnBlock(Location(world, part.x, part.y, part.z), Material.SPRUCE_PLANKS.createBlockData())
        gate.boards += display
        display.transformation = Transformation(Vector3f(), AxisAngle4f(), Vector3f(part.width, part.height, part.depth), AxisAngle4f())
        display.brightness = Display.Brightness(12, 12)
        display.viewRange = .7f
        display.isVisibleByDefault = false
        players.filter(present).forEach(display::showTo)
    }
    private fun setCollision(gate: Gate, closed: Boolean) {
        val blocks = gate.window.gateCells().map { (x, y, z) -> world.getBlockAt(x, y, z) }
        require(blocks.all { it.type == if (closed) Material.AIR else Material.SPRUCE_FENCE }) { "Zombies ingress was modified externally" }
        blocks.forEachIndexed { index, block ->
            if (closed) block.setBlockData(gate.originals[index], false) else block.setType(Material.AIR, false)
        }
    }
    private fun center(gate: Gate) = Location(world, gate.window.x + .5, 66.2, gate.window.z + .5)
    private fun values(gate: Gate) = mapOf("boards" to locale.text(gate.boards.size), "max" to locale.text(MAX_BOARDS))

    override fun close() {
        gates.forEach { gate ->
            gate.boards.forEach(PacketBlockDisplay::remove)
            gate.window.gateCells().forEachIndexed { index, (x, y, z) ->
                if (world.isChunkLoaded(x shr 4, z shr 4)) world.getBlockAt(x, y, z).let { block ->
                    if (block.type in setOf(Material.AIR, Material.SPRUCE_FENCE)) block.setBlockData(gate.originals[index], false)
                }
            }
        }
        gates.clear()
    }

    companion object {
        const val MAX_BOARDS = 3
        const val REPAIR_MS = 1500L
        const val STRIKE_MS = 2000L
        data class Board(val x: Double, val y: Double, val z: Double, val width: Float, val height: Float, val depth: Float)
        fun board(window: ZombiesArenaGenerator.Companion.Window, index: Int): Board {
            require(index in 0 until MAX_BOARDS)
            val acrossX = window.alongX != 0
            return Board(window.x + .5 - if (acrossX) 1.45 else .1, 65.35 + index * .8,
                window.z + .5 - if (acrossX) .1 else 1.45, if (acrossX) 2.9f else .2f, .24f, if (acrossX) .2f else 2.9f)
        }
        fun spawnCoordinates(window: ZombiesArenaGenerator.Companion.Window) = doubleArrayOf(
            window.spawnX, 65.0, window.spawnZ)
    }
    private fun spawnLocation(window: ZombiesArenaGenerator.Companion.Window): Location = spawnCoordinates(window).let { Location(world, it[0], it[1], it[2]) }
}
