package ru.ruscrafting.events.paper

import net.kyori.adventure.bossbar.BossBar
import net.kyori.adventure.text.Component
import net.kyori.adventure.title.Title
import org.bukkit.GameMode
import org.bukkit.Location
import org.bukkit.Sound
import org.bukkit.attribute.Attribute
import org.bukkit.entity.Player
import org.bukkit.event.entity.EntityDamageEvent
import org.bukkit.event.player.PlayerFishEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.plugin.Plugin
import org.bukkit.util.Vector
import ru.arc.core.ScheduledTask
import ru.arc.core.Tasks
import ru.arc.paper.display.PaperPacketDisplays
import ru.ruscrafting.events.config.ArcEventsConfig
import ru.ruscrafting.events.config.ArcEventsLocale
import ru.ruscrafting.events.config.ArenaSettings
import ru.ruscrafting.events.config.EventLocation
import ru.ruscrafting.events.config.PlayerStateMode
import ru.ruscrafting.events.domain.*
import ru.ruscrafting.events.network.QueueEntry
import java.time.Duration
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.logging.Level
import kotlin.math.ceil

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
    private val recover: (Player, (Result<PlayerRecovery?>) -> Unit) -> Unit,
    private val recovered: (Player, PlayerRecovery) -> Unit,
    private val clearWeaponState: (UUID) -> Unit,
    private val packetDisplays: PaperPacketDisplays,
    private val clock: () -> Long,
    private val hud: TttHud,
    private val work: EventWork? = null,
    private val practiceOpponentLocation: (UUID) -> Location? = { null },
) {
    val runtime = ArcadeMatchRuntime(clock)
    val current: ArcadeMatch? get() = runtime.current
    private var resultShown = false
    private var restoreAt = 0L
    private var hazardAt = 0L
    private var disasterFrames: ScheduledTask? = null
    private val bars = mutableMapOf<UUID, BossBar>()
    private val recentAttacks = RecentAttackLedger(clock = clock)
    private var hazardVictim: UUID? = null
    private var fishing: FishingAdventure? = null
    private var zombies: ZombiesAdventure? = null
    internal var zombiesPracticeDifficulty: ZombiesDifficulty? = null
        private set
    internal var builderZombiesArea: BuilderZombiesArea? = null
        private set
    private var practiceRoster: PracticeRoster? = null
    private var practiceCompanions: ArcadePracticeCompanions? = null

    internal fun start(matchId: UUID, mode: EventMode, entries: List<QueueEntry>, players: List<Player>, rules: ArcadeRules,
        practice: PracticeRoster? = null, zombiesPracticeDifficulty: ZombiesDifficulty? = null,
        builderArea: BuilderZombiesArea? = null): CompletableFuture<Unit> {
        check(current == null)
        require(entries.map { UUID.fromString(it.playerId) }.toSet() == players.map { it.uniqueId }.toSet())
        require(players.distinctBy { it.uniqueId }.size == players.size)
        require(entries.all { it.mode == mode.id })
        check(players.none { escrow.pendingAnotherMatch(it.uniqueId, matchId) }) { "Player still has recovery from another match" }
        val arena = requireNotNull(arenaPool.active())
        resultShown = false
        require(practice == null || practice.matchId == matchId && players.singleOrNull()?.uniqueId == practice.ownerId)
        require(zombiesPracticeDifficulty == null || mode == EventMode.ZOMBIES && QueueEntry.isPracticeMatch(matchId))
        require(builderArea == null || zombiesPracticeDifficulty != null && builderArea.contains(players.single().location))
        val extendedZombiesRound = zombiesPracticeDifficulty != null && zombiesPracticeDifficulty != ZombiesDifficulty.CLASSIC
        val matchRules = if (extendedZombiesRound) rules.copy(roundSeconds = 7_200) else rules
        runtime.start(matchId, mode, entries.map(QueueEntry::queuedPlayer) + practice?.bots.orEmpty(), matchRules,
            extendedZombiesRound = extendedZombiesRound)
        practiceRoster = practice
        this.zombiesPracticeDifficulty = zombiesPracticeDifficulty
        builderZombiesArea = builderArea
        var mutationAttempted = false
        val preservePlayerState = practice != null || QueueEntry.isPracticeMatch(matchId) || settings().playerStateMode == PlayerStateMode.PRESERVE
        fun stillPreparing() = current?.matchId == matchId && current?.phase == MatchPhase.PREPARING && players.all {
            it.isOnline && (builderArea == null || builderArea.contains(it.location))
        }
        val applyPlayer: (IndexedValue<Player>) -> Unit = apply@{ (slot, player) ->
            val preparing = current?.takeIf { it.matchId == matchId && it.phase == MatchPhase.PREPARING } ?: return@apply
            if (!player.isOnline || preparing.participants[player.uniqueId]?.status != ParticipantStatus.RESERVED) return@apply
            check(builderArea?.ready() != false) { "The builder practice floor changed during preparation" }
            mutationAttempted = true
            resetPlayer(player)
            player.inventory.clear()
            player.inventory.armorContents = arrayOfNulls(4)
            player.inventory.setItemInOffHand(null)
            teleport(player, arena.spawnFor(slot, players.size))
            if (preservePlayerState) player.saveData()
        }
        val mutatePlayers: () -> CompletableFuture<Unit> = {
            if (work == null) {
                players.withIndex().forEach(applyPlayer)
                CompletableFuture.completedFuture(Unit)
            } else {
                val iterator = players.withIndex().iterator()
                work.capture(::stillPreparing) {
                    if (iterator.hasNext()) applyPlayer(iterator.next())
                    !iterator.hasNext()
                }
            }
        }
        val operation = runCatching {
            if (preservePlayerState) escrow.commitThenMutateStagesAsync(matchId, players,
                entries.associate { UUID.fromString(it.playerId) to it.originServer }, clock(), ::stillPreparing,
            ) { mutatePlayers() }.thenApply { Unit } else mutatePlayers()
        }.getOrElse { CompletableFuture.failedFuture(it) }
        return operation.handle { _, failure -> failure }.thenCompose { failure -> onMain {
            if (failure != null) {
                if (!mutationAttempted && this.current?.matchId == matchId) {
                    if (preservePlayerState) {
                        runCatching { escrow.pendingPlayers(matchId) }.onSuccess { pending ->
                            players.filter { it.uniqueId !in pending }.forEach { runtime.markRecoveryApplied(it.uniqueId) }
                        }
                    } else players.forEach { runtime.markRecoveryApplied(it.uniqueId) }
                }
                throw failure
            }
            check(stillPreparing()) { "Arcade preparation superseded" }
            if (mode == EventMode.FISHING) {
                val player = players.single()
                val adventure = FishingAdventure(plugin, locale, items, firearms, settings().fishing, matchId, player,
                    player.world, teleport, ::hurt, {
                        if (current?.matchId == matchId && current?.phase == MatchPhase.ACTIVE && isAlive(player.uniqueId)) {
                            runtime.completeFishing(player.uniqueId)
                            showResult()
                        }
                    }, packetDisplays, clock, work)
                fishing = adventure
                adventure.prepareAsync()
            } else CompletableFuture.completedFuture(Unit)
        } }.thenCompose { it }
    }

    private fun <T> onMain(action: () -> T): CompletableFuture<T> = if (work == null) {
        runCatching(action).fold(CompletableFuture<T>::completedFuture, CompletableFuture<T>::failedFuture)
    } else work.onMain(action)

    fun confirmRoster() {
        if (current?.phase != MatchPhase.PREPARING) return
        val match = runtime.beginCountdown()
        online().forEach { player ->
            val title = when (match.mode) {
                EventMode.GUN_GAME -> locale.render("opening.gungame-title", player)
                EventMode.DISASTERS -> locale.render("opening.disasters-title", player, mapOf("hazard" to hazardName(player)))
                EventMode.FISHING -> locale.render("opening.fishing-title", player)
                EventMode.ZOMBIES -> locale.render("zombies.wave-title", player, mapOf("wave" to locale.text(1), "total" to locale.text(zombiesRules().waves)))
                EventMode.TTT -> error("TTT owns its role reveal")
            }
            val objective = if (match.mode == EventMode.DISASTERS) "arcade.hazard-${hazardId()}-guide" else "opening.${match.mode.id}"
            player.showEventOpening(title, locale.render(objective, player))
        }
        updateHud(match)
    }

    fun participant(id: UUID): ArcadePlayer? = current?.participants?.get(id)
    fun isParticipant(id: UUID) = participant(id)?.status?.let { it != ParticipantStatus.RESTORED } == true
    fun isAlive(id: UUID) = participant(id)?.status == ParticipantStatus.ALIVE
    fun secondsRemaining(): Int = if (current?.mode == EventMode.DISASTERS && current?.phase == MatchPhase.ACTIVE)
        ceil((hazardAt - clock()).coerceAtLeast(0) / 1000.0).toInt() else runtime.phaseSecondsRemaining()

    fun tick() {
        val before = current ?: return
        // Async recovery can mark the final participant restored between host ticks.
        // COMPLETED still owns the arena until the durable recovery gate releases it.
        if (before.phase in setOf(MatchPhase.RESTORING, MatchPhase.COMPLETED)) { restorePlayers(); return }
        if (before.phase == MatchPhase.CANCELLED) { beginRestoration(); return }
        if (before.phase == MatchPhase.RESOLVING) {
            showResult()
            if (clock() >= restoreAt) beginRestoration()
            return
        }
        if (before.phase == MatchPhase.PREPARING) return
        val now = clock()
        val match = runtime.tick()
        if (match.phase == MatchPhase.RESOLVING) { showResult(); return }
        if (match.phase != before.phase) {
            when (match.phase) {
                MatchPhase.ACTIVE -> {
                    val roster = online()
                    val arena = requireNotNull(arenaPool.active())
                    check(builderZombiesArea?.ready() != false) { "The builder practice floor changed during the countdown" }
                    roster.forEachIndexed { slot, player ->
                        resetPlayer(player)
                        spawn(player, arena, arena.spawnFor(slot, roster.size))
                        giveLoadout(player)
                    }
                    if (practiceRoster != null && match.mode in setOf(EventMode.DISASTERS, EventMode.FISHING)) {
                        practiceCompanions = ArcadePracticeCompanions(plugin, packetDisplays, roster.single(), match.mode,
                            match.matchId, locale, practiceRoster?.bots?.singleOrNull()).also { it.start() }
                    }
                    if (match.mode == EventMode.DISASTERS) {
                        practiceCompanions?.resetRound()
                        beginHazard(match, now)
                        roster.forEach { player -> player.sendEventMessage(locale.render("arcade.disaster-warning", player,
                            mapOf("hazard" to hazardName(player), "seconds" to locale.text(DisastersFootprint.WARNING_MS / 1_000)))) }
                    }
                    if (match.mode == EventMode.ZOMBIES) {
                        val adventure = ZombiesAdventure(plugin, locale, items, firearms, zombiesRules(),
                            match.matchId, roster.first().world, roster,
                            active = { current?.matchId == match.matchId && current?.phase == MatchPhase.ACTIVE },
                            isAlive = ::isAlive,
                            revivePlayer = { player ->
                                val spectator = player.gameMode == GameMode.SPECTATOR
                                runtime.reviveZombies(player.uniqueId)
                                resetPlayer(player)
                                if (spectator) spawn(player)
                            }, hurt = ::hurt, killed = runtime::recordZombieKill,
                            complete = { won ->
                                if (current?.matchId == match.matchId && current?.phase == MatchPhase.ACTIVE) {
                                    runtime.completeZombies(won, zombies?.director?.completedWaves ?: 0)
                                    showResult()
                                }
                            }, clearWeaponState = clearWeaponState, displays = packetDisplays,
                            sounds = { settings().ui.sounds }, particles = { settings().ui.particles }, clock = clock,
                            builderArea = builderZombiesArea)
                        zombies = adventure
                        adventure.start()
                    }
                    if (match.mode == EventMode.FISHING) {
                        val adventure = requireNotNull(fishing)
                        adventure.startAsync().whenComplete { _, failure ->
                            if (failure != null && work?.isCurrent() != false && fishing === adventure &&
                                current?.matchId == match.matchId) {
                                plugin.logger.log(Level.SEVERE, "Could not start fishing match ${match.matchId}", failure)
                                cancel(MatchEndReason.ADMIN)
                            }
                        }
                    }
                }
                else -> Unit
            }
        }
        if (match.phase == MatchPhase.ACTIVE) {
            if (match.mode == EventMode.GUN_GAME) {
                match.participants.values.filter { it.status == ParticipantStatus.DEAD && (it.respawnAtMs ?: Long.MAX_VALUE) <= now }
                    .forEach { state ->
                        if (practiceRoster?.bots?.any { it.playerId == state.playerId } == true) runtime.respawn(state.playerId)
                        else plugin.server.getPlayer(state.playerId)?.takeIf(Player::isOnline)?.let { player ->
                            runtime.respawn(state.playerId)
                            resetPlayer(player)
                            spawn(player)
                            giveLoadout(player)
                            player.sendEventMessage(locale.render("arcade.respawn", player))
                        }
                    }
            } else if (match.mode == EventMode.DISASTERS) {
                tickDisasters()
            } else if (match.mode == EventMode.FISHING) {
                fishing?.tick()
                fishing?.let { practiceCompanions?.tick(it.snapshot().stage, "", true) }
            }
            else if (match.mode == EventMode.ZOMBIES) zombies?.tick()
        }
        current?.let(::updateHud)
    }

    fun cancel(reason: MatchEndReason) {
        val match = current ?: return
        if (match.phase == MatchPhase.COMPLETED) return
        if (match.phase == MatchPhase.RESTORING) { restorePlayers(); return }
        if (match.phase == MatchPhase.RESOLVING) { beginRestoration(); return }
        if (match.phase != MatchPhase.CANCELLED) runtime.cancel(reason)
        beginRestoration()
    }

    fun disconnect(player: Player) {
        if (!isParticipant(player.uniqueId)) return
        if (current?.phase == MatchPhase.COUNTDOWN) player.clearTitle()
        closeFishing()
        clearWeaponState(player.uniqueId)
        removeHud(player)
        runtime.disconnect(player.uniqueId)
        if (practiceRoster?.ownerId == player.uniqueId && current?.phase in setOf(MatchPhase.ACTIVE, MatchPhase.PREPARING, MatchPhase.COUNTDOWN)) {
            runtime.cancel(MatchEndReason.ADMIN)
        }
        zombies?.disconnect(player)
        // PlayerQuitEvent still exposes the departing player as online; restoring there rejects teleport.
        val departingMatchId = current?.takeIf { it.phase == MatchPhase.CANCELLED }?.matchId
        if (departingMatchId != null) Tasks.scheduler.runLater(1L) {
            if (current?.matchId == departingMatchId && current?.phase == MatchPhase.CANCELLED) beginRestoration()
        }
    }

    fun markRecovered(player: Player, recovery: PlayerRecovery) {
        val match = current ?: return
        if (match.matchId != recovery.matchId || player.uniqueId !in match.participants) return
        closeFishing()
        clearWeaponState(player.uniqueId)
        removeHud(player)
        runtime.markRecoveryApplied(player.uniqueId)
        zombies?.disconnect(player, restored = true)
    }

    fun shouldCancelDamage(victimId: UUID, attackerId: UUID?, projectile: Boolean, projectileMatchId: UUID?): Boolean {
        val match = current ?: return false
        if (projectileMatchId != null && projectileMatchId != match.matchId) return true
        val victim = participant(victimId)?.takeUnless { it.status == ParticipantStatus.RESTORED }
        val attacker = attackerId?.let(::participant)?.takeUnless { it.status == ParticipantStatus.RESTORED }
        if (victim == null && attacker == null) return projectileMatchId != null
        if (match.phase != MatchPhase.ACTIVE || victim?.status != ParticipantStatus.ALIVE) return true
        if (projectile && projectileMatchId != match.matchId) return true
        if (attackerId != null && attacker?.status != ParticipantStatus.ALIVE) return true
        if (match.mode == EventMode.FISHING) return attackerId != null || hazardVictim != victimId
        if (match.mode == EventMode.ZOMBIES) return attackerId != null || hazardVictim != victimId
        if (match.mode == EventMode.DISASTERS) return !match.disasterActive || (attackerId != null) || hazardVictim != victimId
        val now = clock()
        if ((victim.protectedUntilMs ?: 0) > now || (attacker?.protectedUntilMs ?: 0) > now) return true
        return false
    }

    fun recordAttack(victim: UUID, attacker: UUID?, firearmId: FirearmId?) {
        val match = current ?: return
        if (match.mode == EventMode.GUN_GAME && firearmId != null && attacker != null && isAlive(attacker) && isAlive(victim)) {
            recentAttacks.record(match.matchId, victim, attacker, firearmId)
        }
    }

    fun eliminate(player: Player, killerId: UUID?, knifeKill: Boolean = false) {
        val before = current ?: return
        if (before.phase != MatchPhase.ACTIVE || !isAlive(player.uniqueId)) return
        val recent = recentAttacks.consume(before.matchId, player.uniqueId)
        val killer = killerId ?: recent?.attackerId
        val firearmId = recent?.takeIf { !knifeKill && it.attackerId == killer }?.firearmId
        val botKnife = practiceRoster?.bots?.any { it.playerId == killer } == true && before.participants[killer]?.stage == GunGameProgression.knifeStage
        val after = runtime.eliminate(player.uniqueId, killer, knifeKill || botKnife, firearmId)
        if (after == before) return
        if (before.mode == EventMode.ZOMBIES) {
            zombies?.down(player)
            return
        }
        clearWeaponState(player.uniqueId)
        player.gameMode = GameMode.SPECTATOR
        player.inventory.clear()
        player.inventory.setItemInOffHand(null)
        val seconds = if (after.mode == EventMode.GUN_GAME) after.rules.respawnSeconds else ceil((hazardAt - clock()).coerceAtLeast(0) / 1000.0).toInt()
        if (after.mode != EventMode.FISHING) player.sendEventMessage(locale.render("arcade.eliminated", player, mapOf("seconds" to locale.text(seconds))))
        if (killer != null && before.participants[killer]?.stage != after.participants[killer]?.stage) {
            plugin.server.getPlayer(killer)?.let { clearWeaponState(killer); giveLoadout(it) }
        }
        if (after.phase == MatchPhase.RESOLVING) showResult()
    }

    fun useKnife(attacker: Player, victim: Player): Boolean {
        val match = current ?: return false
        if (match.mode != EventMode.GUN_GAME || participant(attacker.uniqueId)?.stage != GunGameProgression.knifeStage) return false
        if (items.kind(attacker.inventory.itemInMainHand) != EventItemKind.ARCADE_KNIFE ||
            !items.belongsTo(attacker.inventory.itemInMainHand, match.matchId.toString())) return false
        if (shouldCancelDamage(victim.uniqueId, attacker.uniqueId, false, null)) return false
        eliminate(victim, attacker.uniqueId, knifeKill = true)
        return true
    }

    internal fun eliminatePracticeFighter(victim: UUID, killer: UUID, firearm: FirearmId?) {
        val before = current ?: return
        if (before.mode != EventMode.GUN_GAME || before.phase != MatchPhase.ACTIVE || practiceRoster?.bots?.none { it.playerId == victim } != false) return
        val after = runtime.eliminate(victim, killer, knifeKill = firearm == null, firearmId = firearm)
        if (before.participants[killer]?.stage != after.participants[killer]?.stage) {
            plugin.server.getPlayer(killer)?.let { clearWeaponState(killer); giveLoadout(it) }
        }
        if (after.phase == MatchPhase.RESOLVING) showResult()
    }

    fun sendChat(player: Player, message: Component) {
        val sender = participant(player.uniqueId) ?: return
        if (sender.status in setOf(ParticipantStatus.DISCONNECTED, ParticipantStatus.RESTORED)) return
        val cooperative = current?.mode == EventMode.ZOMBIES
        val living = cooperative || sender.status in setOf(ParticipantStatus.ALIVE, ParticipantStatus.RESERVED)
        online().filter { cooperative || (participant(it.uniqueId)?.status in setOf(ParticipantStatus.ALIVE, ParticipantStatus.RESERVED)) == living }
            .forEach { viewer -> viewer.sendEventMessage(locale.render(if (living) "chat.match-message" else "chat.spectator-message", viewer,
                mapOf("range" to locale.render("chat.range.global", viewer), "player" to Component.text(player.name), "message" to message))) }
    }

    fun status(player: Player) {
        val match = current ?: return
        if (!isParticipant(player.uniqueId)) {
            player.sendEventMessage(locale.render("match.unavailable", player))
            return
        }
        if (match.mode == EventMode.FISHING && match.phase == MatchPhase.ACTIVE && fishing != null) {
            player.sendEventMessage(requireNotNull(fishing).hud())
            return
        }
        if (match.mode == EventMode.ZOMBIES && zombies != null) {
            player.sendEventMessage(requireNotNull(zombies).hud(player))
            return
        }
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
        val firearm = GunGameProgression.firearmAt(state.stage)
        if (firearm == null) player.inventory.setItem(0, items.arcadeKnife(player, match.matchId.toString()))
        else {
            player.inventory.setItem(0, firearms.firearmItem(firearm, player, match.matchId.toString()))
            for (slot in 1..4) player.inventory.setItem(slot, firearms.ammunition(player, match.matchId.toString(), 64))
        }
        player.inventory.heldItemSlot = 0
    }

    private fun spawn(player: Player, arena: ArenaSettings = requireNotNull(arenaPool.active()), initial: EventLocation? = null) {
        if (current?.mode != EventMode.GUN_GAME) { teleport(player, arena.playerSpawn); return }
        val world = requireNotNull(plugin.server.getWorld(arena.world))
        val distributedSpawns = arena.spawns.size > 1
        fun safe(point: EventLocation): Boolean {
            val location = Location(world, point.x, point.y, point.z)
            return (!distributedSpawns || world.isChunkLoaded(location.blockX shr 4, location.blockZ shr 4)) && location.hasArenaFooting()
        }
        if (distributedSpawns && initial != null && safe(initial)) {
            teleport(player, initial)
            return
        }
        val points = if (distributedSpawns) arena.spawns else arena.lootSpawns + arena.spawns
        val humans = online().filter { it.uniqueId != player.uniqueId && isAlive(it.uniqueId) }.map(Player::getLocation)
        val bots = practiceRoster?.bots.orEmpty().filter { isAlive(it.playerId) }.mapNotNull { practiceOpponentLocation(it.playerId) }
        val opponents = (humans + bots).filter { it.world === world }
        val solo = distributedSpawns && humans.isEmpty() && bots.isNotEmpty()
        val chosen = points.asSequence().filter(::safe).maxByOrNull { point ->
            val distanceSquared = opponents.minOfOrNull { at ->
                val dx = at.x - point.x
                val dz = at.z - point.z
                dx * dx + dz * dz
            } ?: 0.0
            // A distant spawn can leave the only opponent outside the solo player's simulation area.
            if (solo && distanceSquared > 26.0 * 26.0) -distanceSquared else distanceSquared
        } ?: error("No safe arcade spawn remains")
        teleport(player, chosen)
    }

    private fun resetPlayer(player: Player) {
        player.closeInventory()
        player.activePotionEffects.forEach { player.removePotionEffect(it.type) }
        player.gameMode = GameMode.ADVENTURE
        player.isInvulnerable = false
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
        hazardAt = clock() + match.rules.intermissionSeconds * 1000L
        online().filter { participant(it.uniqueId)?.status in setOf(ParticipantStatus.ALIVE, ParticipantStatus.RESERVED) }.forEach { player ->
            resetPlayer(player)
            spawn(player)
            player.sendEventMessage(locale.render("arcade.disaster-warning", player, mapOf("hazard" to hazardName(player), "seconds" to locale.text(match.rules.intermissionSeconds))))
            player.sendEventMessage(locale.render("arcade.hazard-${hazardId()}-guide", player))
        }
        practiceCompanions?.resetRound()
    }

    private fun tickDisasters() {
        val match = requireNotNull(current)
        val now = clock()
        if (now < hazardAt) return
        if (!match.disasterActive) {
            beginHazard(match, now)
            return
        }
        closeDisasters()
        val changed = runtime.completeDisaster()
        val values = mapOf("round" to locale.text(changed.disasterRound), "total" to locale.text(changed.rules.disasterRounds))
        for (player in online()) {
            val scored = changed.participants.getValue(player.uniqueId).score > match.participants.getValue(player.uniqueId).score
            player.sendEventMessage(locale.render(if (scored) "arcade.disaster-survived" else "arcade.round-complete", player, values))
            if (scored && settings().ui.sounds) player.playSound(player.location, Sound.ENTITY_EXPERIENCE_ORB_PICKUP, .65f, 1.35f)
        }
        if (changed.phase == MatchPhase.RESOLVING) showResult() else prepareHazard()
    }

    private fun beginHazard(match: ArcadeMatch, now: Long) {
        runtime.beginDisaster()
        hazardAt = now + match.rules.disasterSeconds * 1000L
        val storm = DisastersRound(match.disasterRound, now, hazardAt)
        val world = requireNotNull(arenaPool.active()).world
        val bot = practiceRoster?.bots?.singleOrNull()?.playerId
        practiceCompanions?.tick(match.disasterRound, hazardId(), true)
        fun frame() {
            val active = current ?: return
            if (active.matchId != match.matchId || active.phase != MatchPhase.ACTIVE || !active.disasterActive ||
                active.disasterRound != match.disasterRound || clock() >= hazardAt) return
            val viewers = online().filter { it.world.name == world }
            val players = viewers.filter { isAlive(it.uniqueId) }.associateBy(Player::getUniqueId)
            val actors = players.mapValues { it.value.location }.toMutableMap()
            if (bot != null && isAlive(bot)) practiceCompanions?.location()?.let { actors[bot] = it }
            val ui = settings().ui
            storm.tick(clock(), actors, viewers, ui.particles, ui.sounds) { id, damage ->
                if (id == bot) {
                    if (practiceCompanions?.hitByHazard(damage) == true) runtime.eliminate(id, null)
                } else players[id]?.let { hurt(it, damage) }
            }
        }
        frame()
        disasterFrames = Tasks.scheduler.runTimer(4L, 4L) {
            runCatching { frame() }.onFailure { failure ->
                plugin.logger.log(Level.SEVERE, "ArcEvents disaster frame failed for ${match.matchId}", failure)
                cancel(MatchEndReason.SHUTDOWN)
            }
        }
    }

    private fun closeDisasters() {
        disasterFrames?.cancel()
        disasterFrames = null
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
        val completedWaves = zombies?.director?.completedWaves ?: 0
        closeFishing()
        closeZombies()
        closePracticeCompanions()
        restoreAt = clock() + match.rules.postRoundSeconds * 1000L
        closeDisasters()
        val winners = match.winners.mapNotNull { match.participants[it]?.playerName }.sorted().joinToString(", ")
        online().forEach { player ->
            clearWeaponState(player.uniqueId)
            val result = if (match.mode == EventMode.FISHING) locale.render(
                if (player.uniqueId in match.winners) "fishing.result-success" else if (match.endReason == MatchEndReason.TIMEOUT) "fishing.result-timeout" else "fishing.result-defeat", player,
            ) else if (match.mode == EventMode.ZOMBIES) locale.render(
                if (player.uniqueId in match.winners) "zombies.result-success" else if (match.endReason == MatchEndReason.TIMEOUT) "zombies.result-timeout" else "zombies.result-defeat", player,
                mapOf("wave" to locale.text(completedWaves), "kills" to locale.text(match.participants.getValue(player.uniqueId).kills)),
            ) else locale.render("arcade.result", player, mapOf("winners" to if (winners.isEmpty()) locale.render("arcade.no-winners", player) else Component.text(winners)))
            player.sendEventMessage(result)
            player.showTitle(Title.title(modeName(player), result, Title.Times.times(Duration.ofMillis(150), Duration.ofSeconds(4), Duration.ofMillis(400))))
        }
        if (practiceRoster == null && zombiesPracticeDifficulty == null) match.participants.values.forEach { participant ->
            runCatching {
                network.recordStats(participant.playerId) { it.recordArcade(match.matchId, participant, participant.playerId in match.winners) }
            }.onFailure { plugin.logger.log(Level.WARNING, "ArcEvents could not submit arcade statistics", it) }
        }
        if (zombiesPracticeDifficulty == null) {
            runCatching { network.announceEnded(match.matchId, null, requireNotNull(match.endReason)) }
                .onFailure { plugin.logger.log(Level.WARNING, "ArcEvents could not announce arcade result", it) }
        }
    }

    private fun beginRestoration() {
        val match = current ?: return
        if (match.phase !in setOf(MatchPhase.RESOLVING, MatchPhase.CANCELLED)) return
        runtime.beginRestoring()
        closeFishing()
        closeZombies()
        closePracticeCompanions()
        closeDisasters()
        recentAttacks.clear()
        online().forEach { clearWeaponState(it.uniqueId); removeHud(it) }
        restorePlayers()
    }

    private fun restorePlayers() {
        val match = current ?: return
        online(includeDisconnected = true).forEach { player ->
            recover(player) { result ->
                result.onSuccess { recovery -> if (recovery != null) recovered(player, recovery) }
                    .onFailure { plugin.logger.log(Level.SEVERE, "ArcEvents arcade recovery failed for ${player.uniqueId}", it) }
            }
        }
        val pending = runCatching { escrow.pendingPlayers(match.matchId) }.getOrElse { return }
        val onlineIds = match.participants.keys.filterTo(mutableSetOf()) { plugin.server.getPlayer(it)?.isOnline == true }
        val restoredIds = requireNotNull(current).participants.values.filter { it.status == ParticipantStatus.RESTORED }.mapTo(mutableSetOf()) { it.playerId }
        if (EventRecoveryGate.blocked(onlineIds, pending, restoredIds)) return
        arenaPool.release(match.matchId)
        runtime.release()
        practiceRoster = null
        zombiesPracticeDifficulty = null
        builderZombiesArea = null
        bars.clear()
    }

    private fun updateHud(match: ArcadeMatch) {
        online().forEach { player ->
            val state = match.participants.getValue(player.uniqueId)
            if (match.mode == EventMode.FISHING && match.phase == MatchPhase.ACTIVE) {
                hud.updateFishingSidebar(player, fishingAdventure(player)?.snapshot())
            }
            val values = mapOf("mode" to modeName(player), "seconds" to locale.text(secondsRemaining()),
                "score" to locale.text(if (match.mode == EventMode.GUN_GAME) state.kills else state.score), "stage" to locale.text(state.stage + 1),
                "total" to locale.text(GunGameProgression.knifeStage + 1), "round" to locale.text((match.disasterRound + 1).coerceAtMost(match.rules.disasterRounds)), "rounds" to locale.text(match.rules.disasterRounds))
            val text = if (match.phase == MatchPhase.COUNTDOWN) locale.render("arcade.countdown", player, values)
                else if (match.mode == EventMode.ZOMBIES && match.phase == MatchPhase.ACTIVE) zombies?.hud(player)
                ?: locale.render("arcade.zombies-hud", player, values)
                else if (match.mode == EventMode.FISHING && match.phase == MatchPhase.ACTIVE && !settings().ui.scoreboard) fishing?.hud()
                ?: locale.render("arcade.fishing-hud", player, values)
                else if (match.mode == EventMode.DISASTERS) locale.render("arcade.disasters-hud", player, values + ("hazard" to hazardName(player)))
                else locale.render("arcade.${match.mode.id}-hud", player, values)
            if (settings().ui.bossBar) {
                val bar = bars.getOrPut(player.uniqueId) { BossBar.bossBar(text, 1f, if (match.mode == EventMode.FISHING) BossBar.Color.BLUE else BossBar.Color.YELLOW, BossBar.Overlay.PROGRESS).also(player::showBossBar) }
                val duration = when (match.phase) {
                    MatchPhase.COUNTDOWN -> match.rules.countdownSeconds
                    else -> if (match.mode == EventMode.DISASTERS) {
                        if (match.disasterActive) match.rules.disasterSeconds else match.rules.intermissionSeconds
                    } else match.rules.roundSeconds
                }
                bar.name(text).progress(if (match.mode == EventMode.ZOMBIES && match.phase == MatchPhase.ACTIVE)
                    zombies?.progress() ?: 0f else (secondsRemaining().toFloat() / duration.coerceAtLeast(1)).coerceIn(0f, 1f))
                if (match.mode == EventMode.DISASTERS && match.phase == MatchPhase.ACTIVE) bar.color(when (DisasterHazard.forRound(match.disasterRound)) {
                    DisasterHazard.METEORS -> BossBar.Color.RED
                    DisasterHazard.LIGHTNING -> BossBar.Color.BLUE
                    DisasterHazard.FOG -> BossBar.Color.WHITE
                })
                if (match.mode == EventMode.ZOMBIES) bar.color(if (zombies?.director?.phase == ZombiesPhase.WAVE) BossBar.Color.RED else BossBar.Color.YELLOW)
            } else {
                removeHud(player)
                if (match.phase == MatchPhase.COUNTDOWN) player.sendEventActionBar(text)
            }
        }
    }

    private fun removeHud(player: Player) { bars.remove(player.uniqueId)?.let(player::hideBossBar) }
    fun handleFish(event: PlayerFishEvent) {
        if (!isParticipant(event.player.uniqueId) || current?.mode != EventMode.FISHING) return
        if (current?.phase != MatchPhase.ACTIVE || fishing == null) event.isCancelled = true
        else fishing?.handleFish(event)
    }
    fun handleFishingDamage(event: EntityDamageEvent): Boolean = fishing?.handleDamage(event) == true
    fun handleFishingInteract(event: PlayerInteractEvent): Boolean = fishing?.handleInteract(event) == true
    fun handleZombiesDamage(event: EntityDamageEvent): Boolean = zombies?.handleDamage(event) == true
    fun handleZombiesMove(event: org.bukkit.event.player.PlayerMoveEvent): Boolean = zombies?.handleMove(event) == true
    fun handleZombiesMelee(player: Player, entity: org.bukkit.entity.Entity): Boolean = zombies?.melee(player, entity) == true
    fun zombiesAdventure(player: Player): ZombiesAdventure? = zombies?.takeIf { it.belongsTo(player) }
    internal fun zombiesPracticeAdventure(): ZombiesAdventure? =
        zombies?.takeIf { zombiesPracticeDifficulty != null && current?.phase == MatchPhase.ACTIVE }

    private fun zombiesRules(): ZombiesRules = when (val difficulty = zombiesPracticeDifficulty) {
        null, ZombiesDifficulty.CLASSIC -> settings().zombies
        else -> ZombiesRules.forDifficulty(difficulty)
    }

    fun zombiesDirector(): ZombiesDirector? = zombies?.director
    fun zombiesBoxPhase(): String = zombies?.mysteryBoxPhase() ?: "-"
    fun zombiesBarricadeBoards(): String = zombies?.barricadeBoards()?.joinToString(",") ?: "-"
    fun handleZombiesTarget(event: org.bukkit.event.entity.EntityTargetLivingEntityEvent): Boolean = zombies?.handleTarget(event) == true
    fun ownsBuilderMob(entity: org.bukkit.entity.Entity): Boolean = zombies?.ownsBuilderMob(entity) == true
    fun fishingAdventure(player: Player): FishingAdventure? {
        val match = current ?: return null
        if (match.mode != EventMode.FISHING || match.phase != MatchPhase.ACTIVE || !isAlive(player.uniqueId)) return null
        return fishing?.takeIf { it.belongsTo(player) }
    }
    private fun closePracticeCompanions() {
        val companions = practiceCompanions
        practiceCompanions = null
        companions?.close()
    }

    private fun closeFishing() {
        val owned = fishing ?: return
        fishing = null
        try {
            owned.close()
        } finally {
            current?.participants?.keys?.forEach(hud::hideFishingSidebar)
        }
    }
    private fun closeZombies() {
        val owned = zombies
        zombies = null
        owned?.close()
    }
    private fun online(includeDisconnected: Boolean = false): List<Player> = current?.participants?.values?.filter {
        it.status != ParticipantStatus.RESTORED && (includeDisconnected || it.status != ParticipantStatus.DISCONNECTED)
    }
        ?.mapNotNull { plugin.server.getPlayer(it.playerId)?.takeIf(Player::isOnline) }.orEmpty()
    private fun hazardId(): String = DisasterHazard.forRound(requireNotNull(current).disasterRound).id
    private fun hazardName(player: Player) = locale.render("arcade.hazard-${hazardId()}", player)
    private fun modeName(player: Player) = locale.render("arcade.${requireNotNull(current).mode.id}-name", player)
    private fun broadcast(key: String, values: Map<String, Component>) = online().forEach { it.sendEventMessage(locale.render(key, it, values)) }
}
