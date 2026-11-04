package ru.ruscrafting.events.paper

import net.kyori.adventure.text.Component
import org.bukkit.Location
import org.bukkit.FluidCollisionMode
import org.bukkit.Material
import org.bukkit.Sound
import org.bukkit.World
import org.bukkit.block.Lidded
import org.bukkit.entity.Display
import org.bukkit.entity.ItemDisplay
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import org.bukkit.plugin.Plugin
import org.bukkit.util.Transformation
import org.bukkit.util.BoundingBox
import org.joml.AxisAngle4f
import org.joml.Vector3f
import ru.arc.paper.display.PacketItemDisplay
import ru.arc.paper.display.PacketTextDisplay
import ru.arc.paper.display.PaperPacketDisplays
import ru.ruscrafting.events.config.ArcEventsLocale
import ru.ruscrafting.events.domain.FirearmId
import ru.ruscrafting.events.domain.ZombiesDirector
import ru.ruscrafting.events.domain.ZombiesOffer
import java.util.UUID
import java.util.logging.Level
import kotlin.math.ceil

/** One physical chest and one paid reservation. Packet props never become dropped items. */
internal class ZombiesMysteryBox(
    private val plugin: Plugin,
    private val locale: ArcEventsLocale,
    private val firearms: TttFirearms,
    private val matchId: UUID,
    private val world: World,
    private val players: Collection<Player>,
    private val director: ZombiesDirector,
    private val displays: PaperPacketDisplays,
    private val standing: (Player) -> Boolean,
    private val present: (Player) -> Boolean,
    private val give: (Player, FirearmId, ItemStack) -> Unit,
    private val sounds: () -> Boolean,
) : AutoCloseable {
    private data class Roll(val owner: Player, val weapon: FirearmId, val items: Map<FirearmId, ItemStack>,
        val candidates: List<FirearmId>, val display: PacketItemDisplay, val beganAt: Long,
        var card: Int = -1, var ready: Boolean = false, var shownSeconds: Int = -1)
    private var roll: Roll? = null
    private var sign: PacketTextDisplay? = null
    private val anchor get() = Location(world, -4.0, 67.0, -21.2)

    fun start() {
        require(chestIntact()) { "Zombies mystery chest does not match the v2 arena" }
        sign = displays.spawnText(Location(world, -4.0, 68.0, -21.1), idle()).apply {
            billboard = Display.Billboard.CENTER
            brightness = Display.Brightness(15, 15)
            isShadowed = true
            lineWidth = 280
            viewRange = .8f
            isVisibleByDefault = false
            players.forEach(::showTo)
        }
        lids(false)
    }

    fun at(location: Location): Boolean = location.world.uid == world.uid && location.blockY == 65 &&
        location.blockZ == -22 && location.blockX in ZombiesArenaGenerator.MYSTERY_CHEST_X

    fun near(player: Player) = standing(player) && player.location.distanceSquared(Location(world, -4.0, 65.5, -21.5)) <= 25

    /** Packet items have no native hitbox. A ray at the visible case/result uses the same idempotent action. */
    fun aimedAt(player: Player): Boolean {
        if (!near(player)) return false
        val current = roll ?: return false
        val weapon = if (current.ready) current.weapon else {
            if (current.card < 0) return false
            current.candidates[current.card % current.candidates.size]
        }
        val eye = player.eyeLocation
        val direction = eye.direction
        val hit = RESULT_BOUNDS.getValue(weapon).clone().shift(anchor.toVector()).expand(.15)
            .rayTrace(eye.toVector(), direction, 5.0)?.hitPosition ?: return false
        val distance = eye.toVector().distance(hit)
        // A five-block interaction crosses at most four chunks. Ray tracing must not prepare new terrain.
        val endpoint = hit.toLocation(world)
        for (x in minOf(eye.blockX shr 4, endpoint.blockX shr 4)..maxOf(eye.blockX shr 4, endpoint.blockX shr 4))
            for (z in minOf(eye.blockZ shr 4, endpoint.blockZ shr 4)..maxOf(eye.blockZ shr 4, endpoint.blockZ shr 4))
                if (!world.isChunkLoaded(x, z)) return false
        val obstruction = world.rayTraceBlocks(eye, direction, distance, FluidCollisionMode.NEVER, true)?.hitBlock
        return obstruction == null
    }

    fun interact(player: Player, now: Long): Boolean {
        if (!near(player)) {
            player.sendEventActionBar(locale.render("zombies.supply-nearby", player))
            return false
        }
        val current = roll
        if (current == null) {
            if (!director.canBuy(player.uniqueId, ZombiesOffer.MYSTERY)) {
                player.sendEventActionBar(locale.render("zombies.supply-poor", player, priceValues(player)))
                return false
            }
            return open(player, now)
        }
        if (current.owner.uniqueId == player.uniqueId && current.ready && now < current.beganAt + ROLL_MS + CLAIM_MS) {
            claim(current)
        } else player.sendEventActionBar(locale.render(if (current.owner.uniqueId == player.uniqueId)
            "zombies.box.rolling-hint" else "zombies.box.busy", player,
            mapOf("player" to Component.text(current.owner.name))))
        return true
    }

    fun open(player: Player, now: Long): Boolean {
        if (!near(player) || roll != null || !director.canBuy(player.uniqueId, ZombiesOffer.MYSTERY)) return false
        var display: PacketItemDisplay? = null
        try {
            check(chestIntact()) { "Mystery chest was changed" }
            val choices = ZombiesDirector.MYSTERY_WEAPONS.filter { it != director.players.getValue(player.uniqueId).weapon }
            val weapon = choices.random()
            // Resolve every candidate before payment, including the exact result stack for slot replacement.
            val items = choices.associateWith { firearms.firearmItem(it, player, matchId.toString()) }
            if (!director.reserveMystery(player.uniqueId, weapon)) return false
            display = displays.spawnItem(anchor, ItemStack(Material.AIR)).apply {
                itemDisplayTransform = ItemDisplay.ItemDisplayTransform.FIXED
                transformation = Transformation(Vector3f(), AxisAngle4f(), Vector3f(.6f), AxisAngle4f())
                brightness = Display.Brightness(15, 15)
                viewRange = .65f
                isVisibleByDefault = false
                players.filter(present).forEach(::showTo)
            }
            roll = Roll(player, weapon, items, choices.shuffled(), display, now)
            lids(true)
            sign?.text(locale.render("zombies.box.rolling", player, mapOf("player" to Component.text(player.name))))
            player.sendEventActionBar(locale.render("zombies.box.opened", player,
                mapOf("price" to locale.text(director.price(player.uniqueId, ZombiesOffer.MYSTERY)))))
            return true
        } catch (failure: Exception) {
            display?.remove()
            roll = null
            val refunded = director.cancelMystery(player.uniqueId, refund = true)
            runCatching { lids(false); sign?.text(idle()) }
            player.sendEventActionBar(locale.render(if (refunded) "zombies.box.cancelled" else "zombies.supply-changed", player,
                mapOf("price" to locale.text(director.price(player.uniqueId, ZombiesOffer.MYSTERY)))))
            failed(player, failure)
            return false
        }
    }

    fun tick(now: Long) {
        val current = roll ?: return
        if (!present(current.owner)) { cancel(current.owner.uniqueId, technical = false); return }
        try {
            check(chestIntact() && current.display.isValid && current.display.location.world.uid == world.uid &&
                current.display.location.distanceSquared(anchor) < .0001) { "Mystery scene lost its physical owner" }
            val age = now - current.beganAt
            if (age >= ROLL_MS + CLAIM_MS) {
                current.owner.sendEventActionBar(locale.render("zombies.box.expired", current.owner))
                cancel(current.owner.uniqueId, technical = false)
                return
            }
            if (age >= ROLL_MS) {
                if (!current.ready) {
                    current.ready = true
                    current.display.itemStack = current.items.getValue(current.weapon)
                    if (sounds()) world.playSound(anchor, Sound.BLOCK_NOTE_BLOCK_CHIME, .75f, 1.4f)
                }
                val seconds = ceil((ROLL_MS + CLAIM_MS - age) / 1000.0).toInt()
                if (seconds != current.shownSeconds) {
                    current.shownSeconds = seconds
                    sign?.text(locale.render("zombies.box.ready", current.owner, resultValues(current, seconds) +
                        ("player" to Component.text(current.owner.name))))
                }
            } else {
                val card = CARD_TIMES.indexOfLast { age >= it }
                if (card >= 0 && card != current.card) {
                    current.card = card
                    current.display.itemStack = current.items.getValue(current.candidates[card % current.candidates.size])
                    if (sounds()) world.playSound(anchor, Sound.BLOCK_NOTE_BLOCK_HAT, .5f, (1.8 - card * .07).toFloat())
                }
            }
        } catch (failure: Exception) {
            cancel(current.owner.uniqueId, technical = true)
            failed(current.owner, failure)
        }
    }

    fun prompt(player: Player, now: Long): Component? {
        if (!near(player)) return null
        val current = roll ?: return locale.render("zombies.box.hint", player, priceValues(player))
        if (current.owner.uniqueId != player.uniqueId || !current.ready) return null
        return locale.render("zombies.box.claim", player, resultValues(current,
            ceil((current.beganAt + ROLL_MS + CLAIM_MS - now).coerceAtLeast(0) / 1000.0).toInt()))
    }

    fun phase(): String = roll?.let { if (it.ready) "ready" else "rolling" } ?: "idle"

    private fun claim(current: Roll) {
        if (roll !== current || !standing(current.owner)) return
        try {
            check(chestIntact()) { "Mystery chest changed before claim" }
            // The caller restores its owned inventory slots if native delivery fails. No drops/overflow.
            give(current.owner, current.weapon, current.items.getValue(current.weapon))
            check(director.claimMystery(current.owner.uniqueId) == current.weapon) { "Mystery claim lost its reservation" }
            roll = null
            current.display.remove()
            lids(false)
            sign?.text(idle())
            current.owner.sendEventActionBar(locale.render("zombies.box.claimed", current.owner, resultValues(current, 0)))
            if (sounds()) world.playSound(anchor, Sound.ENTITY_ITEM_PICKUP, .65f, 1.1f)
        } catch (failure: Exception) {
            cancel(current.owner.uniqueId, technical = true)
            failed(current.owner, failure)
        }
    }

    fun cancel(playerId: UUID, technical: Boolean) {
        val current = roll?.takeIf { it.owner.uniqueId == playerId } ?: return
        roll = null
        director.cancelMystery(playerId, refund = technical)
        current.display.remove()
        runCatching { lids(false); sign?.text(idle()) }
        if (technical && present(current.owner)) current.owner.sendEventActionBar(locale.render("zombies.box.cancelled", current.owner,
            mapOf("price" to locale.text(director.price(playerId, ZombiesOffer.MYSTERY)))))
    }

    private fun failed(player: Player, failure: Exception) {
        plugin.logger.log(Level.SEVERE, "Zombies mystery operation failed match=$matchId player=${player.uniqueId}", failure)
    }
    private fun chestIntact() = ZombiesArenaGenerator.MYSTERY_CHEST_X.all { x ->
        world.isChunkLoaded(x shr 4, -22 shr 4) && world.getBlockAt(x, 65, -22).type == Material.CHEST }
    private fun lids(open: Boolean) = ZombiesArenaGenerator.MYSTERY_CHEST_X.forEach { x ->
        (world.getBlockAt(x, 65, -22).state as? Lidded)?.let { if (open) it.open() else it.close() } }
    private fun idle(player: Player = players.first()) = locale.render("zombies.box.idle", player,
        mapOf("price" to locale.text(director.price(player.uniqueId, ZombiesOffer.MYSTERY))))
    private fun priceValues(player: Player) = mapOf("price" to locale.text(director.price(player.uniqueId, ZombiesOffer.MYSTERY)),
        "points" to locale.text(director.players.getValue(player.uniqueId).points))
    private fun resultValues(current: Roll, seconds: Int) = mapOf("weapon" to
        locale.render("weapon.${current.weapon.name.lowercase()}-name", current.owner), "seconds" to locale.text(seconds))

    override fun close() {
        roll?.let { cancel(it.owner.uniqueId, technical = true) }
        sign?.remove()
        sign = null
        lids(false)
    }

    companion object {
        const val ROLL_MS = 4800L
        const val CLAIM_MS = 15_000L
        val CARD_TIMES = listOf(500L, 650L, 800L, 950L, 1150L, 1400L, 1700L, 2050L, 2500L, 3100L, 3900L)
        // Exact configured IA meshes, FIXED context + native Y180 + .6 scale; see the grounding evidence.
        private val RESULT_BOUNDS = mapOf(
            FirearmId.G36 to BoundingBox(-1.027500, -.448670, .029396, 1.099456, .296952, .190046),
            FirearmId.AEK_971 to BoundingBox(-1.239000, -.454346, -.053175, 1.123425, .285945, .147972),
            FirearmId.RPL_20 to BoundingBox(-1.050750, -.393194, -.102722, .906562, .242554, .238999),
            FirearmId.VEPR_12 to BoundingBox(-1.295250, -.395307, -.055654, 1.064577, .193024, .101847),
            FirearmId.M1_GARAND to BoundingBox(-1.279500, -.318335, -.078375, 1.055770, .243977, .110625),
            FirearmId.VSS_VINTOREZ to BoundingBox(-1.437000, -.385866, -.062625, 1.025599, .367225, .143959),
            FirearmId.MCMILLAN to BoundingBox(-1.252125, -.426587, -.232358, 1.178625, .338250, .478636),
            FirearmId.DOUBLE_BARREL to BoundingBox(-.881250, .214706, .159742, .134377, .397653, .341403),
        )
    }
}
