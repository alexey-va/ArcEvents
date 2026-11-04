package ru.ruscrafting.events.paper

import net.kyori.adventure.text.Component
import net.kyori.adventure.title.Title
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.Particle
import org.bukkit.Sound
import org.bukkit.World
import org.bukkit.attribute.Attribute
import org.bukkit.entity.Display
import org.bukkit.entity.Entity
import org.bukkit.entity.Player
import org.bukkit.entity.Zombie
import org.bukkit.event.entity.EntityDamageEvent
import org.bukkit.event.player.PlayerMoveEvent
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType
import org.bukkit.plugin.Plugin
import org.bukkit.potion.PotionEffect
import org.bukkit.potion.PotionEffectType
import ru.arc.paper.display.PacketTextDisplay
import ru.arc.paper.display.PaperPacketDisplays
import ru.ruscrafting.events.config.ArcEventsLocale
import ru.ruscrafting.events.domain.*
import java.time.Duration
import java.util.UUID
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.sin

/** One arena lease owns every mob, local point and recovery. Native deaths never mint drops or external rewards. */
class ZombiesAdventure(
    private val plugin: Plugin,
    private val locale: ArcEventsLocale,
    private val items: TttItems,
    private val firearms: TttFirearms,
    rules: ZombiesRules,
    private val matchId: UUID,
    private val world: World,
    roster: List<Player>,
    private val active: () -> Boolean,
    private val isAlive: (UUID) -> Boolean,
    private val revivePlayer: (Player) -> Unit,
    private val hurt: (Player, Double) -> Unit,
    private val killed: (UUID) -> Unit,
    private val complete: (Boolean) -> Unit,
    private val clearWeaponState: (UUID) -> Unit,
    private val displays: PaperPacketDisplays,
    private val sounds: () -> Boolean,
    private val particles: () -> Boolean,
    private val clock: () -> Long,
) : AutoCloseable {
    private data class Enemy(val mob: Zombie, val kind: ZombieKind, var health: Double,
        var lastPosition: Location, var movedAt: Long, var attackAt: Long = 0, var feedbackAt: Long = 0, var slam: Location? = null, var slamAt: Long = 0)
    private data class Rescue(val rescuer: UUID, val beganAt: Long)
    val director = ZombiesDirector(roster.mapTo(linkedSetOf(), Player::getUniqueId), rules, clock())
    private val roster = roster.associateBy(Player::getUniqueId)
    private val matchKey = NamespacedKey(plugin, "zombies_match")
    private val enemies = linkedMapOf<UUID, Enemy>()
    private val signs = mutableListOf<PacketTextDisplay>()
    private val downLocations = mutableMapOf<UUID, Location>()
    private val rescues = mutableMapOf<UUID, Rescue>()
    private val protectedUntil = mutableMapOf<UUID, Long>()
    private val damagedAt = mutableMapOf<UUID, Long>()
    private val knifeAt = mutableMapOf<UUID, Long>()
    private var closed = false
    private var signalled = false
    private var spawnCursor = 0

    fun start() {
        check(!closed && active())
        // Only this dedicated, marker-bound world can contain our stale nonpersistent actors.
        world.entities.filter { TAG in it.scoreboardTags }.forEach(Entity::remove)
        roster.values.forEach { equip(it); message(it, "zombies.ready") }
        ZombiesArenaGenerator.STATIONS.forEach { (offer, x) ->
            val owner = roster.values.first()
            signs += displays.spawnText(Location(world, x + .5, 67.0, -21.4),
                locale.render("zombies.station.${offer.name.lowercase()}", owner)).apply {
                billboard = Display.Billboard.CENTER
                brightness = Display.Brightness(15, 15)
                isShadowed = true
                lineWidth = 240
                viewRange = .8f
                isVisibleByDefault = false
                roster.values.forEach(::showTo)
            }
        }
    }

    fun belongsTo(player: Player): Boolean = !closed && active() && player.isOnline && player.world.uid == world.uid &&
        director.players[player.uniqueId]?.status?.let { it != ZombiesStatus.LEFT } == true

    private fun standing(player: Player) = belongsTo(player) && isAlive(player.uniqueId) &&
        director.players[player.uniqueId]?.status == ZombiesStatus.ALIVE

    fun tick() {
        if (closed || !active()) return
        val now = clock()
        val before = director.phase
        director.tick(now)
        if (finish()) return
        if (before != director.phase && director.phase == ZombiesPhase.WAVE) {
            roster.values.filter(::belongsTo).forEach { player ->
                val values = values(player)
                player.showTitle(Title.title(locale.render("zombies.wave-title", player, values),
                    locale.render(if (director.wave % 5 == 0) "zombies.brute-warning" else "zombies.wave-subtitle", player, values),
                    Title.Times.times(Duration.ofMillis(100), Duration.ofSeconds(2), Duration.ofMillis(300))))
                if (sounds()) player.playSound(player.location, Sound.BLOCK_BEACON_ACTIVATE, .65f, .65f)
            }
        }
        updateDowned(now)
        if (finish()) return
        val survivors = roster.values.filter(::standing)
        if (director.phase == ZombiesPhase.WAVE) {
            repeat((survivors.size + 1).coerceAtMost(4)) {
                val location = spawnLocation(survivors) ?: return@repeat
                val kind = director.spawn() ?: return@repeat
                try { spawn(kind, location, now) } catch (failure: Exception) {
                    plugin.logger.log(java.util.logging.Level.SEVERE, "Could not spawn a Zombies actor for match $matchId", failure)
                    close()
                    if (!signalled) { signalled = true; complete(false) }
                    return
                }
            }
            for (entry in enemies.values.toList()) {
                val mob = entry.mob
                if (!mob.isValid || mob.isDead || mob.world.uid != world.uid) {
                    if (enemies.remove(mob.uniqueId) != null) {
                        mob.remove()
                        director.removeEnemy(entry.kind, null, now)
                    }
                    continue
                }
                val target = survivors.minByOrNull { it.location.distanceSquared(mob.location) }
                if (mob.target?.uniqueId != target?.uniqueId) mob.target = target
                if (target == null) continue
                if (mob.location.distanceSquared(entry.lastPosition) > .16) {
                    entry.lastPosition = mob.location
                    entry.movedAt = now
                } else if (now - entry.movedAt > 12_000 && mob.location.distanceSquared(target.location) > 9) {
                    spawnLocation(survivors)?.let { mob.teleport(it); entry.lastPosition = it; entry.movedAt = now }
                }
                if (entry.kind == ZombieKind.BRUTE) tickBrute(entry, survivors, target, now)
                else if (now >= entry.attackAt && mob.location.distanceSquared(target.location) <= 3.2 && mob.hasLineOfSight(target)) {
                    entry.attackAt = now + 1000
                    mob.swingMainHand()
                    damagePlayer(target, 2.5 + director.wave * .25, now)
                }
            }
            if (director.phase == ZombiesPhase.INTERMISSION) supply()
        }
        survivors.forEach { player ->
            if (now - (damagedAt[player.uniqueId] ?: 0) >= 6000) {
                val maximum = player.getAttribute(Attribute.MAX_HEALTH)?.value ?: 20.0
                player.health = (player.health + 2).coerceAtMost(maximum)
            }
        }
        finish()
    }

    private fun spawnLocation(players: List<Player>): Location? {
        if (players.isEmpty()) return null
        val options = ZombiesArenaGenerator.SPAWNS.map { (x, z) -> Location(world, x + .5, 65.0, z + .5) }
            .filter { world.isChunkLoaded(it.blockX shr 4, it.blockZ shr 4) && it.block.isPassable &&
                it.block.getRelative(0, 1, 0).isPassable && it.block.getRelative(0, -1, 0).type.isSolid }
            .sortedByDescending { point -> players.minOf { it.location.distanceSquared(point) } }
            .filter { point -> players.all { it.location.distanceSquared(point) >= 64 } }.take(3)
        return options.takeIf { it.isNotEmpty() }?.let { it[spawnCursor++ % it.size] }
    }

    private fun spawn(kind: ZombieKind, point: Location, now: Long) {
        val mob = world.spawn(point, Zombie::class.java) { zombie ->
            zombie.addScoreboardTag(TAG)
            zombie.persistentDataContainer.set(matchKey, PersistentDataType.STRING, matchId.toString())
            zombie.isPersistent = false
            zombie.removeWhenFarAway = false
            zombie.setCanPickupItems(false)
            zombie.isInvulnerable = true
            zombie.setAdult()
            zombie.setShouldBurnInDay(false)
            zombie.getAttribute(Attribute.ATTACK_DAMAGE)?.baseValue = 0.0
            zombie.getAttribute(Attribute.MOVEMENT_SPEED)?.baseValue = when (kind) {
                ZombieKind.WALKER -> .22
                ZombieKind.RUNNER -> .31
                ZombieKind.BRUTE -> .18
            }
            if (kind == ZombieKind.BRUTE) {
                zombie.getAttribute(Attribute.SCALE)?.baseValue = 1.45
                zombie.equipment.helmet = ItemStack(Material.IRON_HELMET)
                zombie.equipment.chestplate = ItemStack(Material.IRON_CHESTPLATE)
                zombie.customName(locale.render("zombies.brute-name", roster.values.first()))
                zombie.isCustomNameVisible = true
            }
        }
        enemies[mob.uniqueId] = Enemy(mob, kind, director.health(kind), point, now)
        if (particles()) world.spawnParticle(Particle.SMOKE, point.clone().add(0.0, .4, 0.0), 8, .3, .4, .3, .02)
    }

    private fun tickBrute(enemy: Enemy, survivors: List<Player>, target: Player, now: Long) {
        val warning = enemy.slam
        if (warning != null) {
            if (now >= enemy.slamAt) {
                enemy.slam = null
                survivors.filter { it.location.distanceSquared(warning) <= 16 && enemy.mob.hasLineOfSight(it) }
                    .forEach { damagePlayer(it, 10.0, now) }
                if (sounds()) world.playSound(warning, Sound.ENTITY_GENERIC_EXPLODE, .65f, .8f)
            } else for (i in 0..15) world.spawnParticle(Particle.FLAME,
                warning.clone().add(cos(i * Math.PI / 8) * 4, .12, sin(i * Math.PI / 8) * 4), 1, 0.0, 0.0, 0.0, 0.0)
        } else if (now >= enemy.attackAt && enemy.mob.location.distanceSquared(target.location) <= 25) {
            enemy.slam = enemy.mob.location.clone()
            enemy.slamAt = now + 2000
            enemy.attackAt = now + 6000
            if (sounds()) world.playSound(enemy.mob.location, Sound.ENTITY_RAVAGER_ROAR, .6f, .8f)
        }
    }

    private fun damagePlayer(player: Player, damage: Double, now: Long) {
        if (!standing(player) || now < (protectedUntil[player.uniqueId] ?: 0)) return
        damagedAt[player.uniqueId] = now
        rescues.entries.removeIf { it.value.rescuer == player.uniqueId }
        hurt(player, damage)
    }

    fun down(player: Player) {
        if (!belongsTo(player) || !director.down(player.uniqueId, clock())) return
        clearWeaponState(player.uniqueId)
        downLocations[player.uniqueId] = player.location.clone()
        player.health = 1.0
        player.addPotionEffect(PotionEffect(PotionEffectType.GLOWING, 600, 0, false, false))
        val state = director.players.getValue(player.uniqueId)
        message(player, if (state.recoveryAt != null) "zombies.self-revive" else "zombies.downed")
        roster.values.filter { belongsTo(it) && it != player }.forEach {
            message(it, "zombies.teammate-downed", mapOf("player" to Component.text(player.name)))
        }
        finish()
    }

    private fun updateDowned(now: Long) {
        for ((id, state) in director.players) {
            val player = roster[id]?.takeIf(::belongsTo) ?: continue
            if (state.status == ZombiesStatus.WAITING) {
                if (player.gameMode != org.bukkit.GameMode.SPECTATOR) {
                    player.gameMode = org.bukkit.GameMode.SPECTATOR
                    downLocations.remove(id)
                    rescues.remove(id)
                    message(player, "zombies.waiting")
                }
                continue
            }
            if (state.status != ZombiesStatus.DOWNED) continue
            if (state.recoveryAt?.let { now >= it } == true) { revive(player); continue }
            val helper = roster.values.firstOrNull { standing(it) && it.isSneaking &&
                it.location.distanceSquared(player.location) <= 6.25 && it.hasLineOfSight(player) }
            if (helper == null) { rescues.remove(id); continue }
            val rescue = rescues[id]?.takeIf { it.rescuer == helper.uniqueId }
                ?: Rescue(helper.uniqueId, now).also { rescues[id] = it }
            val remaining = ceil((rescue.beganAt + 3000 - now).coerceAtLeast(0) / 1000.0).toInt()
            helper.sendEventActionBar(locale.render("zombies.reviving", helper,
                mapOf("player" to Component.text(player.name), "seconds" to locale.text(remaining))))
            if (remaining == 0) revive(player)
        }
    }

    private fun revive(player: Player) {
        if (!director.revive(player.uniqueId)) return
        restoreStanding(player)
        message(player, "zombies.revived")
    }

    private fun restoreStanding(player: Player) {
        downLocations.remove(player.uniqueId)
        rescues.remove(player.uniqueId)
        player.removePotionEffect(PotionEffectType.GLOWING)
        revivePlayer(player)
        if (director.players.getValue(player.uniqueId).vitality) applyVitality(player)
        player.health = player.getAttribute(Attribute.MAX_HEALTH)?.value ?: 20.0
        protectedUntil[player.uniqueId] = clock() + 4000
    }

    fun handleMove(event: PlayerMoveEvent): Boolean {
        val at = downLocations[event.player.uniqueId] ?: return false
        if (!belongsTo(event.player)) return false
        event.to = at.clone().also { it.yaw = event.to.yaw; it.pitch = event.to.pitch }
        return true
    }

    fun handleDamage(event: EntityDamageEvent): Boolean {
        if (TAG !in event.entity.scoreboardTags) return false
        event.isCancelled = true
        return true
    }

    fun handleTarget(event: org.bukkit.event.entity.EntityTargetLivingEntityEvent): Boolean {
        if (event.entity.uniqueId !in enemies) return false
        if (event.target != null && event.target?.let { it is Player && standing(it) } != true) event.isCancelled = true
        return true
    }

    fun melee(player: Player, entity: Entity): Boolean {
        if (entity.uniqueId !in enemies) return false
        val held = player.inventory.itemInMainHand
        if (standing(player) && player.inventory.heldItemSlot == 1 && items.kind(held) == EventItemKind.ARCADE_KNIFE &&
            items.belongsTo(held, matchId.toString()) && player.location.distanceSquared(entity.location) <= 10 &&
            player.hasLineOfSight(entity) && clock() >= (knifeAt[player.uniqueId] ?: 0)) {
            knifeAt[player.uniqueId] = clock() + 700
            damage(player, enemies.getValue(entity.uniqueId), 8.0, false)
        }
        return true
    }

    fun isFirearmTarget(player: Player, entity: Entity, expectedMatch: String): Boolean =
        expectedMatch == matchId.toString() && standing(player) && enemies[entity.uniqueId]?.mob === entity &&
            entity.world.uid == world.uid && !entity.isDead && entity.isValid

    fun hitByFirearm(player: Player, entity: Entity, impact: FirearmHit, amount: Double, expectedMatch: String) {
        if (!isFirearmTarget(player, entity, expectedMatch) || !amount.isFinite() || amount <= 0) return
        val enemy = enemies.getValue(entity.uniqueId)
        val headshot = impact.position.y >= enemy.mob.eyeLocation.y - .35
        val power = director.players.getValue(player.uniqueId).power
        damage(player, enemy, amount * (1 + power * .35) * if (headshot) 1.75 else 1.0, headshot)
    }

    private fun damage(player: Player, enemy: Enemy, amount: Double, headshot: Boolean) {
        enemy.health -= amount
        if (enemy.health <= 0 && enemies.remove(enemy.mob.uniqueId) != null) {
            val at = enemy.mob.location
            enemy.mob.remove()
            director.removeEnemy(enemy.kind, player.uniqueId, clock())
            killed(player.uniqueId)
            if (particles()) world.spawnParticle(Particle.ASH, at.add(0.0, .8, 0.0), 8, .25, .45, .25, .02)
            if (director.phase == ZombiesPhase.INTERMISSION) supply()
            finish()
        } else if (clock() >= enemy.feedbackAt) {
            enemy.feedbackAt = clock() + 80
            enemy.mob.playHurtAnimation(player.location.yaw)
            if (particles()) world.spawnParticle(Particle.DAMAGE_INDICATOR, enemy.mob.location.add(0.0, 1.0, 0.0), 3, .18, .25, .18, .05)
            if (sounds()) {
                world.playSound(enemy.mob.location, Sound.ENTITY_ZOMBIE_HURT, .5f, if (enemy.kind == ZombieKind.BRUTE) .65f else 1f)
                if (headshot) player.playSound(player.location, Sound.ENTITY_EXPERIENCE_ORB_PICKUP, .3f, 1.8f)
            }
        }
    }

    fun stationAt(location: Location): ZombiesOffer? = if (location.world.uid != world.uid || location.blockY != 65 || location.blockZ != -22) null
        else ZombiesArenaGenerator.STATIONS.entries.firstOrNull { it.value == location.blockX }?.key

    fun canTrade(player: Player, offer: ZombiesOffer): Boolean = standing(player) && player.location.distanceSquared(
        Location(world, ZombiesArenaGenerator.STATIONS.getValue(offer) + .5, 65.5, -21.5)) <= 25

    fun buy(player: Player, expected: ZombiesSurvivor, offer: ZombiesOffer): Boolean {
        if (!canTrade(player, offer) || director.players[player.uniqueId] != expected || !director.canBuy(player.uniqueId, offer)) return false
        val weapon = if (offer == ZombiesOffer.MYSTERY) ZombiesDirector.MYSTERY_WEAPONS.filter { it != expected.weapon }.random() else null
        // Construct an item before debiting the local balance; inventory slots are owned and cannot overflow.
        val replacement = weapon?.let { firearms.firearmItem(it, player, matchId.toString()) }
        if (!director.buy(player.uniqueId, offer, weapon)) return false
        when (offer) {
            ZombiesOffer.AMMO -> resupply(player)
            ZombiesOffer.MYSTERY -> {
                resupply(player, requireNotNull(replacement))
                message(player, "zombies.weapon-received", mapOf("weapon" to locale.render("weapon.${weapon!!.name.lowercase()}-name", player)))
            }
            ZombiesOffer.POWER -> Unit
            ZombiesOffer.VITALITY -> applyVitality(player)
        }
        if (sounds()) player.playSound(player.location, Sound.BLOCK_ANVIL_USE, .45f, 1.3f)
        return true
    }

    private fun applyVitality(player: Player) {
        player.addPotionEffect(PotionEffect(PotionEffectType.HEALTH_BOOST, 36_000, 4, false, false))
        player.health = player.getAttribute(Attribute.MAX_HEALTH)?.value ?: 20.0
    }

    private fun equip(player: Player) {
        player.inventory.setItem(1, items.arcadeKnife(player, matchId.toString()))
        player.inventory.heldItemSlot = 0
        resupply(player)
    }

    private fun resupply(player: Player, replacement: ItemStack? = null) {
        clearWeaponState(player.uniqueId)
        val id = director.players.getValue(player.uniqueId).weapon
        val spec = firearms.spec(id)
        player.inventory.setItem(0, replacement ?: firearms.firearmItem(id, player, matchId.toString()))
        for (slot in 9..12) player.inventory.setItem(slot, null)
        var rounds = spec.magazineSize * 4
        for (slot in 9..12) {
            if (rounds <= 0) break
            val count = minOf(rounds, 64)
            player.inventory.setItem(slot, firearms.ammunition(player, matchId.toString(), count))
            rounds -= count
        }
    }

    private fun supply() {
        roster.values.filter(::belongsTo).forEach { player ->
            restoreStanding(player)
            resupply(player)
            message(player, "zombies.wave-cleared", values(player))
        }
    }

    fun hud(player: Player): Component = locale.render(when {
        director.players[player.uniqueId]?.recoveryAt != null -> "zombies.hud-self-revive"
        director.players[player.uniqueId]?.status == ZombiesStatus.DOWNED -> "zombies.hud-downed"
        director.players[player.uniqueId]?.status == ZombiesStatus.WAITING -> "zombies.hud-waiting"
        director.phase == ZombiesPhase.INTERMISSION -> "zombies.hud-intermission"
        else -> "zombies.hud-wave"
    }, player, values(player))

    fun secondsRemaining(): Int = ceil((director.nextWaveAt - clock()).coerceAtLeast(0) / 1000.0).toInt()
    fun progress(): Float = if (director.phase == ZombiesPhase.INTERMISSION)
        (secondsRemaining().toFloat() / director.rules.intermissionSeconds).coerceIn(0f, 1f)
        else ((director.pending + director.alive).toFloat() / director.waveSize.coerceAtLeast(1)).coerceIn(0f, 1f)

    private fun values(player: Player): Map<String, Component> {
        val state = director.players.getValue(player.uniqueId)
        return mapOf("wave" to locale.text(if (director.phase == ZombiesPhase.INTERMISSION) director.wave + 1 else director.wave),
            "total" to locale.text(director.rules.waves), "remaining" to locale.text(director.pending + director.alive),
            "points" to locale.text(state.points), "power" to locale.text(state.power),
            "seconds" to locale.text(if (state.status == ZombiesStatus.DOWNED)
                ceil(((state.recoveryAt ?: state.bleedOutAt ?: clock()) - clock()).coerceAtLeast(0) / 1000.0).toInt() else secondsRemaining()))
    }

    fun disconnect(player: Player, restored: Boolean = false) {
        director.disconnect(player.uniqueId)
        downLocations.remove(player.uniqueId)
        rescues.remove(player.uniqueId)
        if (!restored) {
            player.removePotionEffect(PotionEffectType.GLOWING)
            player.removePotionEffect(PotionEffectType.HEALTH_BOOST)
        }
        finish()
    }

    private fun finish(): Boolean {
        if (director.phase !in setOf(ZombiesPhase.WON, ZombiesPhase.LOST)) return false
        if (!signalled) { signalled = true; complete(director.phase == ZombiesPhase.WON) }
        return true
    }

    private fun message(player: Player, key: String, values: Map<String, Component> = emptyMap()) = player.sendEventMessage(locale.render(key, player, values))

    override fun close() {
        if (closed) return
        closed = true
        enemies.values.forEach { it.mob.remove() }
        enemies.clear()
        signs.forEach { it.remove() }
        signs.clear()
        roster.values.filter { director.players[it.uniqueId]?.status != ZombiesStatus.LEFT }.forEach { player ->
            player.removePotionEffect(PotionEffectType.GLOWING)
            player.removePotionEffect(PotionEffectType.HEALTH_BOOST)
        }
        downLocations.clear()
        rescues.clear()
    }

    companion object { private const val TAG = "arcevents-zombies" }
}
