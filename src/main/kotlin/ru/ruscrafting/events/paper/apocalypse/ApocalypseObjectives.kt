package ru.ruscrafting.events.paper.apocalypse

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.Particle
import org.bukkit.Sound
import org.bukkit.World
import org.bukkit.block.Bell
import org.bukkit.block.Block
import org.bukkit.block.BlockFace
import org.bukkit.block.data.Lightable
import org.bukkit.entity.Player
import ru.ruscrafting.events.config.ArcEventsLocale
import ru.ruscrafting.events.domain.apocalypse.ApocalypseObjectivePoint
import ru.ruscrafting.events.domain.apocalypse.ApocalypseObjectivePoint.*
import ru.ruscrafting.events.domain.apocalypse.ApocalypseObjectiveSites
import ru.ruscrafting.events.domain.apocalypse.ApocalypseObjectivesState
import ru.ruscrafting.events.domain.apocalypse.ApocalypsePoint
import ru.ruscrafting.events.domain.apocalypse.ApocalypseSurvivalRules
import ru.ruscrafting.events.domain.apocalypse.apocalypseSirenNight
import ru.ruscrafting.events.paper.EventWork
import java.util.UUID

/** All native operations belong to this one expedition. The runtime supplies its durable checkpoint and participants. */
internal class ApocalypseObjectives(
    private val world: World,
    private val sites: ApocalypseObjectiveSites,
    private val locale: ArcEventsLocale,
    private val work: EventWork,
    private val districtReady: (org.bukkit.Chunk) -> Boolean,
    private val eligible: (Player) -> Boolean,
    private val progress: () -> ApocalypseObjectivesState,
    private val rules: ApocalypseSurvivalRules,
    private val tick: () -> Long,
    private val save: (ApocalypseObjectivesState, () -> Unit) -> Boolean,
    private val log: (String, Throwable) -> Unit,
) {
    private val travelers = mutableSetOf<UUID>()
    private var engineReadyAt: Long? = null

    private data class Fixture(
        val point: ApocalypsePoint,
        val material: Material,
        val support: Material?,
        val control: ApocalypseObjectivePoint? = null,
        val data: String? = null,
    )

    private val fixtures = buildList {
        fun control(id: ApocalypseObjectivePoint, material: Material, support: Material? = Material.POLISHED_ANDESITE, data: String? = null) =
            add(Fixture(sites[id], material, support, id, data))
        control(RELAY, Material.WAXED_CHISELED_COPPER)
        control(DRAIN, Material.WAXED_EXPOSED_CUT_COPPER, null)
        control(APARTMENT, Material.CARTOGRAPHY_TABLE, Material.matchMaterial(sites.mapSupport))
        control(RADIO, Material.NOTE_BLOCK)
        control(GENERATOR, Material.LODESTONE)
        control(WINCH, Material.GRINDSTONE, data = "grindstone[face=floor,facing=east]")
        control(HOME, Material.GRINDSTONE, Material.POLISHED_BASALT, "grindstone[face=floor,facing=west]")
        control(SIREN, Material.BELL, data = "bell[attachment=floor,facing=north]")
        val radio = sites[RADIO]
        add(Fixture(radio.copy(y = radio.y + 1), Material.LIGHTNING_ROD, Material.NOTE_BLOCK, RADIO))
        val engine = sites[GENERATOR]
        add(Fixture(engine.copy(x = engine.x - 1), Material.WAXED_OXIDIZED_COPPER_GRATE, Material.POLISHED_ANDESITE, GENERATOR))
        add(Fixture(engine.copy(x = engine.x - 1, y = engine.y + 1), Material.POLISHED_BLACKSTONE_WALL,
            Material.WAXED_OXIDIZED_COPPER_GRATE, GENERATOR))
        sites.beacons.forEach { lamp -> add(Fixture(lamp, Material.WAXED_COPPER_BULB,
            if (lamp.z == -2) Material.YELLOW_TERRACOTTA else Material.POLISHED_ANDESITE)) }
    }
    private val byPoint = fixtures.associateBy { it.point }
    private val foundations = fixtures.filter { it.support != null }.associate { it.point.copy(y = it.point.y - 1) to it.support }
    private val blockData = fixtures.mapNotNull(Fixture::data).distinct().associateWith { Bukkit.createBlockData(it) }

    private fun at(p: ApocalypsePoint) = Location(world, p.x + .5, p.y + .5, p.z + .5)
    private fun participant(player: Player) = player.world === world && !player.isDead && eligible(player)
    private fun near(player: Player, p: ApocalypsePoint, distance: Int): Boolean =
        player.world === world && at(p).distanceSquared(player.location) <= distance * distance
    private fun loadedBlock(p: ApocalypsePoint): Block? =
        if (world.isChunkLoaded(p.x shr 4, p.z shr 4)) world.getBlockAt(p.x, p.y, p.z) else null
    private fun nextSirenNight() = apocalypseSirenNight(tick(), rules.settings)
    private fun armedTonight(state: ApocalypseObjectivesState) = state.sirenDay > 0 && state.sirenDay == rules.dayAt(tick())

    /** Follow accepted scenery migrations. A foreign block or a changed foundation is left intact. */
    private fun prepare(f: Fixture, powered: Boolean) {
        val floor = f.support ?: return
        val block = loadedBlock(f.point) ?: return
        if (!districtReady(block.chunk) || block.getRelative(BlockFace.DOWN).type != floor) return
        if (block.type.isAir) {
            val data = f.data?.let(blockData::getValue)
            if (data == null) block.setType(f.material, false) else block.setBlockData(data, false)
        }
        if (f.material == Material.WAXED_COPPER_BULB && block.type == f.material) {
            val light = block.blockData as Lightable
            if (light.isLit != powered) { light.isLit = powered; block.setBlockData(light, false) }
        }
    }

    fun protects(block: Block): Boolean {
        if (block.world !== world) return false
        val point = ApocalypsePoint(block.x, block.y, block.z)
        val fixture = byPoint[point]
        return (fixture?.material == block.type || foundations[point] == block.type) && districtReady(block.chunk)
    }

    fun interact(player: Player, block: Block): Boolean {
        if (!participant(player) || block.world !== world) return false
        val f = byPoint[ApocalypsePoint(block.x, block.y, block.z)] ?: return false
        val control = f.control ?: return false
        if (block.type != f.material || !near(player, f.point, 7) || !districtReady(block.chunk)) return false
        val old = progress()
        when (control) {
            WINCH, HOME -> if (old.portPowered) travel(player, control) else say(player, "winch-offline")
            GENERATOR -> when {
                old.portPowered -> say(player, "engine-running")
                !old.relay -> say(player, "need-relay", RELAY)
                !old.diesel -> say(player, "need-diesel", DRAIN)
                engineReadyAt != null -> say(player, "hold-engine")
                else -> {
                    engineReadyAt = world.gameTime + 240
                    say(player, "engine-start")
                    world.playSound(at(sites[GENERATOR]), Sound.BLOCK_PISTON_EXTEND, 1.3f, .65f)
                }
            }
            SIREN -> {
                val day = nextSirenNight()
                if (day == null) say(player, if (armedTonight(old)) "siren-committed" else "siren-late")
                else if (old.sirenDay == day) say(player, "siren-ready", value = day)
                else change(player, old.copy(sirenDay = day)) {
                    world.playSound(at(sites[SIREN]), Sound.BLOCK_BELL_RESONATE, 1.1f, .65f)
                    say(player, "siren-ready", value = day)
                }
            }
            else -> {
                val next = old.inspect(control)
                val received: () -> Unit = {
                    when (control) {
                        RELAY -> say(player, if (old.relay) "relay-found" else "relay-taken", GENERATOR)
                        DRAIN -> {
                            if (!old.diesel || next.radioClues == old.radioClues) say(player, "diesel-taken", GENERATOR)
                            if (next.radioClues != old.radioClues) say(player, "clue-log", RADIO)
                        }
                        APARTMENT -> say(player, "clue-map", DRAIN)
                        RADIO -> {
                            player.playSound(at(sites[RADIO]), Sound.BLOCK_AMETHYST_BLOCK_CHIME, .8f, .65f)
                            val reply = when (next.radioClues) { 3 -> "radio-answer"; 1 -> "radio-missing-log"; else -> "radio-fragment" }
                            say(player, reply,
                                if (next.radioClues == 1) DRAIN else APARTMENT)
                        }
                        else -> Unit
                    }
                }
                if (old == next) received() else change(player, next, received)
            }
        }
        return true
    }

    private fun change(player: Player, next: ApocalypseObjectivesState, after: () -> Unit) {
        if (!save(next) { if (participant(player)) after() }) say(player, "saving")
    }

    /** One pass per active second. Engine cranking needs someone at the motor, including after a handoff. */
    fun tick(survivors: List<Player>) {
        val crew = survivors.filter(::participant)
        val powered = progress().portPowered
        for (fixture in fixtures) if (crew.any { near(it, fixture.point, 56) }) prepare(fixture, powered)
        for (player in crew) {
            if (engineReadyAt != null && near(player, sites[GENERATOR], 5)) continue
            if (!near(player, sites[RADIO], 64) && !near(player, sites[HOME], 8)) continue
            val look = player.getTargetBlockExact(5) ?: continue
            val f = byPoint[ApocalypsePoint(look.x, look.y, look.z)] ?: continue
            val control = f.control ?: continue
            if (look.type == f.material && districtReady(look.chunk)) {
                player.sendActionBar(text(player, "use-${control.name.lowercase()}"))
            }
        }
        if (powered) { engineReadyAt = null; return }
        val readyAt = engineReadyAt ?: return
        val engine = sites[GENERATOR]
        val tenders = crew.filter { near(it, engine, 5) }
        if (tenders.isEmpty()) {
            engineReadyAt = null
            crew.filter { near(it, engine, 28) }.forEach { say(it, "engine-stalled") }
            return
        }
        val remaining = ((readyAt - world.gameTime).coerceIn(0, 240) + 19) / 20
        val exhaust = at(engine).add(-1.0, 1.6, 0.0)
        world.spawnParticle(Particle.CAMPFIRE_COSY_SMOKE, exhaust, 2, .06, .04, .06, .025)
        world.playSound(at(engine), Sound.BLOCK_PISTON_CONTRACT, 1.5f, .97f - remaining * .035f)
        if (remaining > 0) tenders.forEach { it.sendActionBar(text(it, "engine-seconds", value = remaining)) }
        else save(progress().copy(portPowered = true)) {
            engineReadyAt = null
            fixtures.filter { it.point in sites.beacons }.forEach { prepare(it, true) }
            world.playSound(at(engine), Sound.BLOCK_BEACON_ACTIVATE, 1.4f, .85f)
            world.players.filter(::participant).forEach { say(it, "engine-powered", WINCH) }
        }
    }

    fun engineDefender(mob: Location, survivors: List<Player>): Player? {
        if (engineReadyAt == null || mob.world !== world || mob.distanceSquared(at(sites[GENERATOR])) > 36 * 36) return null
        return survivors.firstOrNull { participant(it) && near(it, sites[GENERATOR], 5) }
    }

    fun sirenDefender(survivors: List<Player>) = survivors.filter { participant(it) && near(it, sites[SIREN], 32) }
        .minByOrNull { it.location.distanceSquared(at(sites[SIREN])) }

    fun soundSiren() {
        val bell = loadedBlock(sites[SIREN]) ?: return
        if (districtReady(bell.chunk) && bell.type == Material.BELL) (bell.state as? Bell)?.ring()
    }

    /** If the warehouse is empty the reserved horde actors remain in the checkpoint until someone visits. */
    fun sirenSpawn(token: String): Location? {
        val center = sites.sirenApron
        val offset = Math.floorMod(token.hashCode(), 15)
        repeat(15) { attempt ->
            val index = (offset + attempt) % 15
            val p = center.copy(x = center.x - (index % 5) * 3, z = center.z - 2 + 2 * (index / 5))
            val feet = loadedBlock(p) ?: return@repeat
            if (districtReady(feet.chunk) && canSpawnApocalypseZombieAt(feet))
                return Location(world, p.x + .5, p.y.toDouble(), p.z + .5)
        }
        return null
    }

    fun arrival(player: Player) {
        if (participant(player) && progress().radioClues == 0) {
            player.playSound(player.location, Sound.BLOCK_COMPARATOR_CLICK, .75f, .6f)
            say(player, "radio-fragment", APARTMENT)
        }
    }

    fun journal(player: Player, guide: ApocalypseGuide) {
        if (!participant(player)) return
        val s = progress()
        val port = when { s.portPowered -> "port-done" to WINCH; !s.relay -> "port-relay" to RELAY
            !s.diesel -> "port-diesel" to DRAIN; else -> "port-engine" to GENERATOR }
        val radio = when (s.radioClues) { 0 -> "radio-map" to APARTMENT; 1 -> "radio-log" to DRAIN
            2 -> "radio-call" to RADIO; else -> "radio-done" to RADIO }
        val night = nextSirenNight()
        val siren = when {
            night == null && armedTonight(s) -> "siren-armed"
            night == null -> "siren-night"
            s.sirenDay == night -> "siren-armed"
            else -> "siren-setup"
        }
        fun pages(book: Boolean): List<Component> = listOf(
            Triple("port", port.first, port.second), Triple("distress", radio.first, radio.second), Triple("siren", siren, SIREN),
        ).map { (title, key, point) ->
            val color = if (book) NamedTextColor.DARK_RED else NamedTextColor.GOLD
            text(player, "chapter-$title").color(color).appendNewline()
                .append(text(player, "journal-$key", point, night ?: s.sirenDay))
                .appendNewline().append(text(player, "address", point).color(if (book) NamedTextColor.DARK_GRAY else NamedTextColor.AQUA))
        }
        guide.showObjectives(player, pages(false), pages(true))
    }

    private fun travel(player: Player, from: ApocalypseObjectivePoint) {
        val id = player.uniqueId
        if (!travelers.add(id)) return
        val landing = if (from == WINCH) sites.homeLanding else sites.portLanding
        world.getChunkAtAsync(landing.x shr 4, landing.z shr 4, true).whenComplete { _, failure -> work.onMain {
            travelers.remove(id)
            if (failure != null) log("winch chunk ${world.name} $landing", failure)
            if (!participant(player) || !near(player, sites[from], 7) || !progress().portPowered) return@onMain
            val feet = loadedBlock(landing)
            if (failure != null || feet == null || !districtReady(feet.chunk) || !feet.type.isAir ||
                !feet.getRelative(BlockFace.UP).type.isAir || feet.getRelative(BlockFace.DOWN).type != Material.POLISHED_ANDESITE) {
                say(player, "winch-blocked")
                return@onMain
            }
            val yaw = if (from == WINCH) -90f else 90f
            world.playSound(player.location, Sound.BLOCK_CHAIN_HIT, .8f, .65f)
            if (player.teleport(Location(world, landing.x + .5, landing.y.toDouble(), landing.z + .5, yaw, 0f))) {
                player.fallDistance = 0f
                player.playSound(player.location, Sound.BLOCK_CHAIN_HIT, .8f, 1.25f)
            } else say(player, "winch-blocked")
        } }
    }

    private fun text(player: Player, key: String, point: ApocalypseObjectivePoint? = null, value: Number = 0): Component {
        val p = point?.let(sites::get)
        return locale.render("apocalypse.objectives.$key", player, mapOf(
            "pos" to locale.text(p?.let { "${it.x} ${it.y} ${it.z}" }.orEmpty()), "value" to locale.text(value),
        ))
    }
    private fun say(player: Player, key: String, point: ApocalypseObjectivePoint? = null, value: Number = 0) =
        player.sendMessage(text(player, key, point, value))
}
