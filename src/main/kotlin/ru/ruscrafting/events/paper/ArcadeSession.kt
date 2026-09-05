package ru.ruscrafting.events.paper

import net.kyori.adventure.bossbar.BossBar
import net.kyori.adventure.text.Component
import net.kyori.adventure.title.Title
import org.bukkit.GameMode
import org.bukkit.Location
import org.bukkit.Particle
import org.bukkit.Sound
import org.bukkit.attribute.Attribute
import org.bukkit.entity.Player
import org.bukkit.plugin.Plugin
import org.bukkit.potion.PotionEffect
import org.bukkit.potion.PotionEffectType
import org.bukkit.util.Vector
import ru.ruscrafting.events.config.ArcEventsConfig
import ru.ruscrafting.events.config.ArcEventsLocale
import ru.ruscrafting.events.config.EventLocation
import ru.ruscrafting.events.domain.*
import ru.ruscrafting.events.network.QueueEntry
import java.time.Duration
import java.util.UUID
import java.util.logging.Level
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.sin

/** Paper presentation for arcade modes. The existing host owns escrow, routing and the arena lease. */
class ArcadeSession(
    private val plugin: Plugin,
    private val settings: () -> ArcEventsConfig,
    private val locale: ArcEventsLocale,
    private val items: TttItems,
    private val firearms: TttFirearms,
    private val arenaPool: ArenaPool,
    private val network: EventNetworkCoordinator,
    private val escrow: PlayerStateEscrow,
    private val teleport: (Player, EventLocation) -> Unit,
    private val recover: (Player) -> PlayerRecovery?,
    private val recovered: (Player, PlayerRecovery) -> Unit,
    private val clearWeaponState: (UUID) -> Unit,
    private val clock: () -> Long,
) {
    val runtime = ArcadeMatchRuntime(clock)
    val current: ArcadeMatch? get() = runtime.current
    private var rosterCommitted = false
    private var resultShown = false
    private var restoreAt = 0L
    private var hazardAt = 0L
    private var lastHazardPulse = -1L
    private val bars = mutableMapOf<UUID, BossBar>()
    private val recentAttacks = RecentAttackLedger(clock = clock)
    private var impacts = emptyList<Location>()
    private var impactAt = 0L
    private var hazardVictim: UUID? = null

    fun start(matchId: UUID, mode: EventMode, entries: List<QueueEntry>, players: List<Player>, rules: ArcadeRules) {
        check(current == null)
        require(entries.map { UUID.fromString(it.playerId) }.toSet() == players.map { it.uniqueId }.toSet())
        require(players.distinctBy { it.uniqueId }.size == players.size)
        require(entries.all { it.mode == mode.id })
        resultShown = false
        rosterCommitted = false
        val match = runtime.start(matchId, mode, entries.map(QueueEntry::queuedPlayer), rules)
        var mutationAttempted = false
        try {
            escrow.commitThenMutate(matchId, players, entries.associate { UUID.fromString(it.playerId) to it.originServer }, clock()) {
                mutationAttempted = true
                players.forEach { player ->
                    resetPlayer(player)
                    player.inventory.clear()
                    player.inventory.setItemInOffHand(null)
                    teleport(player, requireNotNull(arenaPool.active()).playerSpawn)
                    player.sendEventMessage(locale.render("arcade.preparing", player, mapOf("mode" to modeName(player))))
                    locale.lore("arcade.${mode.id}-guide", player).forEach(player::sendEventMessage)
                    player.saveData()
                }
            }
        } catch (failure: Throwable) {
            // A rejected journal commit must not trap an untouched local roster in recovery.
            // Existing arrival snapshots still require their normal verified restoration.
            if (!mutationAttempted) {
                runCatching { escrow.pendingPlayers(matchId) }.onSuccess { pending ->
                    players.filter { it.uniqueId !in pending }.forEach { runtime.markRecoveryApplied(it.uniqueId) }
                }
            }
            throw failure
        }
        updateHud(match)
    }

    fun confirmRoster() { rosterCommitted = true }
    fun participant(id: UUID): ArcadePlayer? = current?.participants?.get(id)
    fun isParticipant(id: UUID) = participant(id)?.status?.let { it != ParticipantStatus.RESTORED } == true
    fun isAlive(id: UUID) = participant(id)?.status == ParticipantStatus.ALIVE
    fun secondsRemaining(): Int = if (current?.mode == EventMode.DISASTERS && current?.phase == MatchPhase.ACTIVE)
        ceil((hazardAt - clock()).coerceAtLeast(0) / 1000.0).toInt() else runtime.phaseSecondsRemaining()

    fun tick() {
        val before = current ?: return
        if (before.phase == MatchPhase.RESTORING) { restorePlayers(); return }
        if (before.phase == MatchPhase.CANCELLED) { beginRestoration(); return }
        if (before.phase == MatchPhase.RESOLVING) {
            showResult()
            if (clock() >= restoreAt) beginRestoration()
            return
        }
        if (!rosterCommitted) return
        val now = clock()
        val match = runtime.tick()
        if (match.phase == MatchPhase.RESOLVING) { showResult(); return }
        if (match.phase != before.phase) {
            when (match.phase) {
                MatchPhase.COUNTDOWN -> broadcast("arcade.countdown", mapOf("seconds" to locale.text(secondsRemaining())))
                MatchPhase.ACTIVE -> {
                    online().forEach { player ->
                        resetPlayer(player)
                        spawn(player)
                        giveLoadout(player)
                        player.sendEventMessage(locale.render("arcade.started", player, mapOf("mode" to modeName(player))))
                    }
                    if (match.mode == EventMode.DISASTERS) prepareHazard()
                }
                else -> Unit
            }
        }
        if (match.phase == MatchPhase.ACTIVE) {
            if (match.mode == EventMode.GUN_GAME) {
                match.participants.values.filter { it.status == ParticipantStatus.DEAD && (it.respawnAtMs ?: Long.MAX_VALUE) <= now }
                    .forEach { state ->
                        plugin.server.getPlayer(state.playerId)?.takeIf(Player::isOnline)?.let { player ->
                            runtime.respawn(state.playerId)
                            resetPlayer(player)
                            spawn(player)
                            giveLoadout(player)
                            player.sendEventMessage(locale.render("arcade.respawn", player))
                        }
                    }
            } else tickDisasters()
        }
        current?.let(::updateHud)
    }

    fun cancel(reason: MatchEndReason) {
        val match = current ?: return
        if (match.phase == MatchPhase.RESTORING) { restorePlayers(); return }
        if (match.phase == MatchPhase.RESOLVING) { beginRestoration(); return }
        if (match.phase != MatchPhase.CANCELLED) runtime.cancel(reason)
        beginRestoration()
    }

    fun disconnect(player: Player) {
        if (!isParticipant(player.uniqueId)) return
        clearWeaponState(player.uniqueId)
        removeHud(player)
        runtime.disconnect(player.uniqueId)
        if (current?.phase == MatchPhase.CANCELLED) beginRestoration()
    }

    fun markRecovered(player: Player, recovery: PlayerRecovery) {
        val match = current ?: return
        if (match.matchId != recovery.matchId || player.uniqueId !in match.participants) return
        clearWeaponState(player.uniqueId)
        removeHud(player)
        runtime.markRecoveryApplied(player.uniqueId)
    }

    fun shouldCancelDamage(victimId: UUID, attackerId: UUID?, projectile: Boolean, projectileMatchId: UUID?): Boolean {
        val match = current ?: return false
        if (projectileMatchId != null && projectileMatchId != match.matchId) return true
        val victim = participant(victimId)
        val attacker = attackerId?.let(::participant)
        if (victim == null && attacker == null) return false
        if (match.phase != MatchPhase.ACTIVE || victim?.status != ParticipantStatus.ALIVE) return true
        if (projectile && projectileMatchId != match.matchId) return true
        if (attackerId != null && attacker?.status != ParticipantStatus.ALIVE) return true
        if (match.mode == EventMode.DISASTERS) return !match.disasterActive || (attackerId != null) || hazardVictim != victimId
        val now = clock()
        if ((victim.protectedUntilMs ?: 0) > now || (attacker?.protectedUntilMs ?: 0) > now) return true
        return false
    }

    fun recordAttack(victim: UUID, attacker: UUID?) {
        val match = current ?: return
        if (match.mode == EventMode.GUN_GAME && attacker != null && attacker != victim && isAlive(attacker) && isAlive(victim)) {
            recentAttacks.record(match.matchId, victim, attacker)
        }
    }

    fun eliminate(player: Player, killerId: UUID?, knifeKill: Boolean = false) {
        val before = current ?: return
        if (before.phase != MatchPhase.ACTIVE || !isAlive(player.uniqueId)) return
        val killer = killerId ?: recentAttacks.consume(before.matchId, player.uniqueId)
        recentAttacks.forget(player.uniqueId)
        val after = runtime.eliminate(player.uniqueId, killer, knifeKill)
        if (after == before) return
        clearWeaponState(player.uniqueId)
        player.gameMode = GameMode.SPECTATOR
        player.inventory.clear()
        player.inventory.setItemInOffHand(null)
        val seconds = if (after.mode == EventMode.GUN_GAME) after.rules.respawnSeconds else ceil((hazardAt - clock()).coerceAtLeast(0) / 1000.0).toInt()
        player.sendEventMessage(locale.render("arcade.eliminated", player, mapOf("seconds" to locale.text(seconds))))
        if (killer != null && before.participants[killer]?.stage != after.participants[killer]?.stage) {
            plugin.server.getPlayer(killer)?.let { clearWeaponState(killer); giveLoadout(it) }
        }
        if (after.phase == MatchPhase.RESOLVING) showResult()
    }

    fun useKnife(attacker: Player, victim: Player): Boolean {
        val match = current ?: return false
        if (match.mode != EventMode.GUN_GAME || participant(attacker.uniqueId)?.stage != FirearmId.entries.size) return false
        if (items.kind(attacker.inventory.itemInMainHand) != EventItemKind.ARCADE_KNIFE ||
            !items.belongsTo(attacker.inventory.itemInMainHand, match.matchId.toString())) return false
        if (shouldCancelDamage(victim.uniqueId, attacker.uniqueId, false, null)) return false
        eliminate(victim, attacker.uniqueId, knifeKill = true)
        return true
    }

    fun sendChat(player: Player, message: Component) {
        val sender = participant(player.uniqueId) ?: return
        val living = sender.status in setOf(ParticipantStatus.ALIVE, ParticipantStatus.RESERVED)
        online().filter { (participant(it.uniqueId)?.status in setOf(ParticipantStatus.ALIVE, ParticipantStatus.RESERVED)) == living }
            .forEach { viewer -> viewer.sendEventMessage(locale.render(if (living) "chat.match-message" else "chat.spectator-message", viewer,
                mapOf("range" to locale.render("chat.range.global", viewer), "player" to Component.text(player.name), "message" to message))) }
    }

    fun status(player: Player) {
        val match = current ?: return
        player.sendEventMessage(locale.render("arcade.status", player, mapOf(
            "mode" to modeName(player), "phase" to locale.render("phase.${match.phase.name.lowercase()}", player),
            "seconds" to locale.text(secondsRemaining()), "score" to locale.text(participant(player.uniqueId)?.let { if (match.mode == EventMode.GUN_GAME) it.kills else it.score } ?: 0),
        )))
    }

    private fun giveLoadout(player: Player) {
        val match = requireNotNull(current)
        val state = match.participants.getValue(player.uniqueId)
        player.inventory.clear()
        player.inventory.setItemInOffHand(null)
        if (match.mode != EventMode.GUN_GAME) return
        val firearm = FirearmId.entries.getOrNull(state.stage)
        if (firearm == null) player.inventory.setItem(0, items.arcadeKnife(player, match.matchId.toString()))
        else {
            player.inventory.setItem(0, firearms.firearmItem(firearm, player, match.matchId.toString()))
            for (slot in 1..4) player.inventory.setItem(slot, firearms.ammunition(player, match.matchId.toString(), 64))
        }
        player.inventory.heldItemSlot = 0
    }

    private fun spawn(player: Player) {
        val arena = requireNotNull(arenaPool.active())
        if (current?.mode == EventMode.DISASTERS) { teleport(player, arena.playerSpawn); return }
        val candidates = (arena.lootSpawns + arena.playerSpawn).filter { point ->
            val world = plugin.server.getWorld(point.world) ?: return@filter false
            val location = Location(world, point.x, point.y, point.z)
            location.block.isPassable && location.block.getRelative(0, 1, 0).isPassable && location.block.getRelative(0, -1, 0).type.isSolid
        }
        val opponents = online().filter { it.uniqueId != player.uniqueId && isAlive(it.uniqueId) }
        val chosen = candidates.maxByOrNull { point ->
            opponents.minOfOrNull { other ->
                val at = other.location
                (at.x - point.x) * (at.x - point.x) + (at.z - point.z) * (at.z - point.z)
            } ?: 0.0
        } ?: error("No safe arcade spawn remains")
        teleport(player, chosen)
    }

    private fun resetPlayer(player: Player) {
        player.closeInventory()
        player.activePotionEffects.forEach { player.removePotionEffect(it.type) }
        player.gameMode = GameMode.ADVENTURE
        player.noDamageTicks = 0
        player.allowFlight = false
        player.isFlying = false
        player.velocity = Vector()
        player.fireTicks = 0
        player.fallDistance = 0f
        player.health = requireNotNull(player.getAttribute(Attribute.MAX_HEALTH)).value
        player.absorptionAmount = 0.0
        player.foodLevel = 20
        player.saturation = 20f
        player.level = 0
        player.exp = 0f
    }

    private fun prepareHazard() {
        val match = requireNotNull(current)
        impacts = emptyList()
        hazardAt = clock() + match.rules.intermissionSeconds * 1000L
        online().filter { participant(it.uniqueId)?.status in setOf(ParticipantStatus.ALIVE, ParticipantStatus.RESERVED) }.forEach { player ->
            resetPlayer(player)
            spawn(player)
            player.sendEventMessage(locale.render("arcade.disaster-warning", player, mapOf("hazard" to hazardName(player), "seconds" to locale.text(match.rules.intermissionSeconds))))
            player.sendEventMessage(locale.render("arcade.hazard-${hazardId()}-guide", player))
        }
    }

    private fun tickDisasters() {
        val match = requireNotNull(current)
        val now = clock()
        if (!match.disasterActive) {
            if (now >= hazardAt) {
                runtime.beginDisaster()
                hazardAt = now + match.rules.disasterSeconds * 1000L
                lastHazardPulse = -1L
            }
            return
        }
        if (now >= hazardAt) {
            val changed = runtime.completeDisaster()
            impacts = emptyList()
            broadcast("arcade.round-complete", mapOf("round" to locale.text(changed.disasterRound), "total" to locale.text(changed.rules.disasterRounds)))
            if (changed.phase == MatchPhase.RESOLVING) showResult() else prepareHazard()
            return
        }
        val alive = online().filter { isAlive(it.uniqueId) }
        when (hazardId()) {
            "meteors" -> {
                if (impacts.isNotEmpty() && now >= impactAt) {
                    impacts.forEach { point ->
                        if (settings().ui.particles) point.world.spawnParticle(Particle.EXPLOSION, point, 1)
                        if (settings().ui.sounds) point.world.playSound(point, Sound.ENTITY_GENERIC_EXPLODE, 0.7f, 1.1f)
                        alive.filter { it.world == point.world && it.location.distanceSquared(point) <= 9.0 }.forEach { hurt(it, 12.0) }
                    }
                    impacts = emptyList()
                }
                if (now / 3000L != lastHazardPulse && impacts.isEmpty()) {
                    lastHazardPulse = now / 3000L
                    impacts = alive.map { it.location.clone() }
                    impactAt = now + 2000L
                }
                impacts.forEach { point ->
                    // Telegraphs remain visible even when optional cosmetic particles are disabled.
                    for (i in 0..15) point.world.spawnParticle(Particle.FLAME, point.clone().add(cos(i * Math.PI / 8) * 3, 0.2, sin(i * Math.PI / 8) * 3), 1, 0.0, 0.0, 0.0, 0.0)
                }
            }
            "lightning" -> if (now / 2000L != lastHazardPulse) {
                lastHazardPulse = now / 2000L
                alive.filter { player -> (player.location.blockY + 2..72).none { y -> player.world.getBlockAt(player.location.blockX, y, player.location.blockZ).type.isSolid } }
                    .forEach { player ->
                        player.world.strikeLightningEffect(player.location)
                        hurt(player, 6.0)
                    }
            }
            "fog" -> alive.filter { it.location.y < 68.0 }.forEach { player ->
                player.addPotionEffect(PotionEffect(PotionEffectType.DARKNESS, 30, 0, false, false))
                hurt(player, 3.0)
            }
        }
    }

    private fun hurt(player: Player, amount: Double) {
        check(hazardVictim == null) { "Nested hazard damage is not allowed" }
        hazardVictim = player.uniqueId
        try { player.damage(amount) } finally { hazardVictim = null }
    }

    private fun showResult() {
        val match = current ?: return
        if (resultShown || match.phase != MatchPhase.RESOLVING) return
        resultShown = true
        restoreAt = clock() + match.rules.postRoundSeconds * 1000L
        impacts = emptyList()
        val winners = match.winners.mapNotNull { match.participants[it]?.playerName }.sorted().joinToString(", ")
        online().forEach { player ->
            clearWeaponState(player.uniqueId)
            val result = locale.render("arcade.result", player, mapOf("winners" to if (winners.isEmpty()) locale.render("arcade.no-winners", player) else Component.text(winners)))
            player.sendEventMessage(result)
            player.showTitle(Title.title(modeName(player), result, Title.Times.times(Duration.ofMillis(150), Duration.ofSeconds(4), Duration.ofMillis(400))))
        }
        match.participants.values.forEach { participant ->
            runCatching {
                network.recordStats(participant.playerId) { it.recordArcade(match.matchId, participant, participant.playerId in match.winners) }
            }.onFailure { plugin.logger.log(Level.WARNING, "ArcEvents could not submit arcade statistics", it) }
        }
        runCatching { network.announceEnded(match.matchId, null, requireNotNull(match.endReason)) }
            .onFailure { plugin.logger.log(Level.WARNING, "ArcEvents could not announce arcade result", it) }
    }

    private fun beginRestoration() {
        val match = current ?: return
        if (match.phase !in setOf(MatchPhase.RESOLVING, MatchPhase.CANCELLED)) return
        runtime.beginRestoring()
        impacts = emptyList()
        recentAttacks.clear()
        online().forEach { clearWeaponState(it.uniqueId); removeHud(it) }
        restorePlayers()
    }

    private fun restorePlayers() {
        val match = current ?: return
        online().filter { participant(it.uniqueId)?.status != ParticipantStatus.RESTORED }.forEach { player ->
            runCatching { recover(player) }.onSuccess { recovery -> if (recovery != null) recovered(player, recovery) }
                .onFailure { plugin.logger.log(Level.SEVERE, "ArcEvents arcade recovery failed for ${player.uniqueId}", it) }
        }
        val pending = runCatching { escrow.pendingPlayers(match.matchId) }.getOrElse { return }
        val onlineIds = match.participants.keys.filterTo(mutableSetOf()) { plugin.server.getPlayer(it)?.isOnline == true }
        val restoredIds = requireNotNull(current).participants.values.filter { it.status == ParticipantStatus.RESTORED }.mapTo(mutableSetOf()) { it.playerId }
        if (EventRecoveryGate.blocked(onlineIds, pending, restoredIds)) return
        arenaPool.release(match.matchId)
        runtime.release()
        bars.clear()
    }

    private fun updateHud(match: ArcadeMatch) {
        online().forEach { player ->
            val state = match.participants.getValue(player.uniqueId)
            val values = mapOf("mode" to modeName(player), "seconds" to locale.text(secondsRemaining()),
                "score" to locale.text(if (match.mode == EventMode.GUN_GAME) state.kills else state.score), "stage" to locale.text(state.stage + 1),
                "total" to locale.text(FirearmId.entries.size + 1), "round" to locale.text((match.disasterRound + 1).coerceAtMost(match.rules.disasterRounds)), "rounds" to locale.text(match.rules.disasterRounds))
            val text = locale.render("arcade.${match.mode.id}-hud", player, values)
            if (settings().ui.bossBar) {
                val bar = bars.getOrPut(player.uniqueId) { BossBar.bossBar(text, 1f, BossBar.Color.YELLOW, BossBar.Overlay.PROGRESS).also(player::showBossBar) }
                val duration = when (match.phase) {
                    MatchPhase.PREPARING -> match.rules.preparationSeconds
                    MatchPhase.COUNTDOWN -> match.rules.countdownSeconds
                    else -> if (match.mode == EventMode.DISASTERS) {
                        if (match.disasterActive) match.rules.disasterSeconds else match.rules.intermissionSeconds
                    } else match.rules.roundSeconds
                }
                bar.name(text).progress((secondsRemaining().toFloat() / duration.coerceAtLeast(1)).coerceIn(0f, 1f))
            } else removeHud(player)
        }
    }

    private fun removeHud(player: Player) { bars.remove(player.uniqueId)?.let(player::hideBossBar) }
    private fun online(): List<Player> = current?.participants?.values?.filter { it.status != ParticipantStatus.RESTORED }
        ?.mapNotNull { plugin.server.getPlayer(it.playerId)?.takeIf(Player::isOnline) }.orEmpty()
    private fun hazardId(): String = listOf("meteors", "lightning", "fog")[requireNotNull(current).disasterRound % 3]
    private fun hazardName(player: Player) = locale.render("arcade.hazard-${hazardId()}", player)
    private fun modeName(player: Player) = locale.render("arcade.${requireNotNull(current).mode.id}-name", player)
    private fun broadcast(key: String, values: Map<String, Component>) = online().forEach { it.sendEventMessage(locale.render(key, it, values)) }
}
