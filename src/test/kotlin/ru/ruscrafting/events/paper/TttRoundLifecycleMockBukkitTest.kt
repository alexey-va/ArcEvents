package ru.ruscrafting.events.paper

import com.google.gson.Gson
import io.papermc.paper.chat.ChatRenderer
import io.papermc.paper.event.player.AsyncChatEvent
import io.kotest.core.spec.style.FunSpec
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.bukkit.GameMode
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.damage.DamageSource
import org.bukkit.damage.DamageType
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.event.entity.EntityDamageEvent.DamageCause
import org.bukkit.inventory.ItemStack
import net.kyori.adventure.audience.Audience
import net.kyori.adventure.chat.SignedMessage
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import ru.arc.config.ConfigManager
import ru.arc.core.PaperArcRuntime
import ru.arc.core.Tasks
import ru.arc.paper.testing.MockBukkitTestRuntime
import ru.arc.paper.testing.failOnUnsupportedMockBukkitOperation
import ru.ruscrafting.events.config.ArcEventsConfig
import ru.ruscrafting.events.config.ArcEventsLocale
import ru.ruscrafting.events.domain.FirearmId
import ru.ruscrafting.events.domain.MatchEndReason
import ru.ruscrafting.events.domain.MatchPhase
import ru.ruscrafting.events.domain.ParticipantStatus
import ru.ruscrafting.events.domain.PlayerEventStats
import ru.ruscrafting.events.domain.TttRole
import ru.ruscrafting.events.domain.TttTeam
import ru.ruscrafting.events.network.QueueEntry
import ru.ruscrafting.events.network.QueueState
import ru.ruscrafting.events.network.ReservationBatch
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Host-local integration of [ArcEventsService] with the real [ArcEventsListener].
 *
 * Redis queueing and arena reservation are replaced by the service's debug bootstrap so the test can exercise the
 * Paper event bus, scheduler, escrow, combat, victory, and restoration path deterministically in MockBukkit.
 * Redis/network coordination remains covered by the Redis-backed integration suite. Plugin descriptor loading and
 * [ArcEventsPlugin.onEnable] bootstrap are outside this host-local test and do not currently have MockBukkit coverage.
 */
class TttRoundLifecycleMockBukkitTest : FunSpec({
    test("an arriving player is escrowed and cleared before the reserved roster completes") {
        failOnUnsupportedMockBukkitOperation {
            TttRoundFixture().use { fixture ->
                fixture.startReservation()
                val first = fixture.players.first()

                fixture.arrive(first) shouldBe true

                first.inventory.itemInMainHand.isEmpty shouldBe true
                fixture.escrow.pendingCount() shouldBe 1
                fixture.service.currentMatch() shouldBe null
            }
        }
    }

    test("arrival inventory clearing can be disabled without losing recovery") {
        failOnUnsupportedMockBukkitOperation {
            TttRoundFixture().use { fixture ->
                fixture.reloadRuntime { it.replace("clear-inventory: true", "clear-inventory: false") }
                fixture.startReservation()
                val first = fixture.players.first()
                val original = first.inventory.itemInMainHand.clone()

                fixture.arrive(first) shouldBe true

                first.inventory.itemInMainHand shouldBe original
                fixture.escrow.pendingCount() shouldBe 1
            }
        }
    }

    test("elimination stays secret until the body is discovered") {
        failOnUnsupportedMockBukkitOperation {
            TttRoundFixture().use { fixture ->
                fixture.startActiveRound()
                fixture.drainAllMessages(fixture.players)
                val victim = fixture.players.first { fixture.service.participant(it.uniqueId)?.role == TttRole.INNOCENT }
                val witnesses = fixture.players.filterNot { it.uniqueId == victim.uniqueId }

                fixture.service.eliminate(victim)

                witnesses.forEach { witness -> fixture.drainMessages(witness) shouldBe emptyList() }

                fixture.service.debugDiscover(witnesses.first(), victim) shouldBe DebugMutationResult.APPLIED
                witnesses.forEach { witness ->
                    fixture.drainMessages(witness).any { victim.name in it } shouldBe true
                }
            }
        }
    }

    test("isolated TTT chat uses live spatial bands and keeps spectator speech away from living players") {
        failOnUnsupportedMockBukkitOperation {
            TttRoundFixture().use { fixture ->
                fixture.startActiveRound()
                val sender = fixture.players[0]
                val close = fixture.players[1]
                val normal = fixture.players[2]
                val far = fixture.players[3]
                val outsider = fixture.addOutsider("Outsider")
                fixture.move(sender, 0.5)
                fixture.move(close, 6.5)
                fixture.move(normal, 18.5)
                fixture.move(far, 34.5)
                fixture.drainAllMessages(fixture.players + outsider)

                fixture.chat(sender, "Идём в центр")

                fixture.drainMessages(sender) shouldContainExactly listOf("вы › Идём в центр")
                fixture.drainMessages(close) shouldContainExactly listOf("рядом  Alpha › Идём в центр")
                fixture.drainMessages(normal) shouldContainExactly listOf("поблизости  Alpha › Идём в центр")
                fixture.drainMessages(far) shouldContainExactly listOf("далеко  Alpha › Идём в центр")
                fixture.drainMessages(outsider) shouldBe emptyList()

                fixture.reloadRuntime { raw -> raw.replace("maximum-distance: 36.0", "maximum-distance: 30.0") }
                fixture.drainAllMessages(fixture.players + outsider)
                fixture.chat(sender, "Слышно только рядом")
                fixture.drainMessages(far) shouldBe emptyList()
                fixture.drainMessages(outsider) shouldBe emptyList()

                val dead = fixture.players
                    .filter { fixture.service.participant(it.uniqueId)?.role != TttRole.TRAITOR }
                    .take(2)
                dead.forEach { player -> fixture.service.eliminate(player) }
                fixture.service.phase() shouldBe MatchPhase.ACTIVE
                dead.forEach { player -> fixture.move(player, 5.5) }
                fixture.drainAllMessages(fixture.players + outsider)

                fixture.chat(dead[0], "Вижу с той стороны")

                fixture.drainMessages(dead[0]) shouldContainExactly listOf("вы › Вижу с той стороны")
                fixture.drainMessages(dead[1]) shouldContainExactly listOf("рядом  ${dead[0].name} › Вижу с той стороны")
                fixture.players.filterNot(dead::contains).forEach { living ->
                    fixture.drainMessages(living) shouldBe emptyList()
                }
                fixture.drainMessages(outsider) shouldBe emptyList()
            }
        }
    }

    test("a host-local Bukkit TTT round keeps parity active, consumes the knife, and restores every player") {
        failOnUnsupportedMockBukkitOperation {
            TttRoundFixture().use { fixture ->
                fixture.startActiveRound()

                val traitor = fixture.playerWithRole(TttRole.TRAITOR)
                val innocentTeam = fixture.players
                    .filterNot { it.uniqueId == traitor.uniqueId }
                    .sortedBy(Player::getName)
                innocentTeam.map { fixture.service.participant(it.uniqueId)?.role }.toSet() shouldBe
                    setOf(TttRole.INNOCENT, TttRole.DETECTIVE)

                innocentTeam.take(2).forEachIndexed { index, victim ->
                    val hit = fixture.meleeHit(traitor, victim, damage = 40.0)

                    hit.isCancelled shouldBe true
                    fixture.service.participant(victim.uniqueId)?.status shouldBe ParticipantStatus.ALIVE
                    fixture.paper.performTicks(1)
                    withClue(fixture.diagnostics()) {
                        fixture.service.participant(victim.uniqueId)?.status shouldBe ParticipantStatus.DEAD
                    }
                    victim.gameMode shouldBe GameMode.SPECTATOR
                    fixture.service.phase() shouldBe MatchPhase.ACTIVE
                    fixture.service.currentMatch()?.winner shouldBe null
                    fixture.service.currentMatch()?.alive()?.size shouldBe 3 - index
                }

                val finalInnocent = innocentTeam.last()
                fixture.service.currentMatch()?.alive()?.map { it.playerId }?.toSet() shouldBe
                    setOf(traitor.uniqueId, finalInnocent.uniqueId)
                fixture.service.phase() shouldBe MatchPhase.ACTIVE
                fixture.service.report() shouldBe null

                val matchId = requireNotNull(fixture.service.currentMatch()).matchId
                val knife = fixture.items.purchasedItem(
                    EventItemKind.TRAITOR_BLADE,
                    traitor,
                    matchId.toString(),
                )
                fixture.items.kind(knife) shouldBe EventItemKind.TRAITOR_BLADE
                fixture.items.belongsTo(knife, matchId.toString()) shouldBe true
                knife.type shouldBe Material.IRON_SWORD
                knife.enchantments shouldBe emptyMap()
                traitor.inventory.setItemInMainHand(knife)

                val finalHit = fixture.meleeHit(traitor, finalInnocent, damage = 1.0)

                finalHit.isCancelled shouldBe true
                traitor.inventory.itemInMainHand.isEmpty shouldBe true
                fixture.service.participant(finalInnocent.uniqueId)?.status shouldBe ParticipantStatus.DEAD
                finalInnocent.gameMode shouldBe GameMode.SPECTATOR
                fixture.service.phase() shouldBe MatchPhase.RESOLVING
                fixture.service.currentMatch()?.winner shouldBe TttTeam.TRAITORS
                fixture.service.currentMatch()?.endReason shouldBe MatchEndReason.ELIMINATION

                val report = requireNotNull(fixture.service.report())
                report.matchId shouldBe matchId
                report.winner shouldBe TttTeam.TRAITORS
                report.reason shouldBe MatchEndReason.ELIMINATION
                report.combat.map { it.weapon } shouldContainExactly listOf("melee", "melee", "traitor-blade")
                report.combat.map { it.lethal } shouldContainExactly listOf(true, true, true)
                report.participants.single { it.playerId == traitor.uniqueId }.kills shouldBe 3
                fixture.service.qaBodies().size shouldBe 3
                fixture.service.qaBodies().any { it.contains("victim=${finalInnocent.name}") } shouldBe true
                verify(exactly = 4) { fixture.network.recordStats(any(), any()) }
                verify(exactly = 1) {
                    fixture.network.announceEnded(matchId, TttTeam.TRAITORS, MatchEndReason.ELIMINATION)
                }

                fixture.paper.performTicks(59)
                fixture.service.phase() shouldBe MatchPhase.RESOLVING
                fixture.escrow.pendingCount(matchId) shouldBe 4

                fixture.paper.performTicks(1)
                fixture.service.currentMatch() shouldBe null
                fixture.arenaPool.active() shouldBe null
                fixture.escrow.pendingCount() shouldBe 0
                fixture.service.qaBodies() shouldBe emptyList()
                fixture.assertOriginalPlayerStateRestored()
                verify(exactly = 4) { fixture.network.returnRecoveredPlayer(any(), any()) }
            }
        }
    }

    test("a knife issued for another match cannot execute or consume against the current roster") {
        failOnUnsupportedMockBukkitOperation {
            TttRoundFixture().use { fixture ->
                fixture.startActiveRound()
                val traitor = fixture.playerWithRole(TttRole.TRAITOR)
                val victim = fixture.players.first { it.uniqueId != traitor.uniqueId }
                val currentMatchId = requireNotNull(fixture.service.currentMatch()).matchId
                val staleKnife = fixture.items.purchasedItem(
                    EventItemKind.TRAITOR_BLADE,
                    traitor,
                    UUID.randomUUID().toString(),
                )
                traitor.inventory.setItemInMainHand(staleKnife)

                fixture.items.belongsTo(staleKnife, currentMatchId.toString()) shouldBe false
                val hit = fixture.meleeHit(traitor, victim, damage = 1.0)

                hit.isCancelled shouldBe false
                traitor.inventory.itemInMainHand shouldBe staleKnife
                fixture.service.participant(victim.uniqueId)?.status shouldBe ParticipantStatus.ALIVE
                fixture.service.phase() shouldBe MatchPhase.ACTIVE
                val qaStatus = fixture.qaStatusFields()
                qaStatus["match"] shouldBe currentMatchId.toString()
                qaStatus["phase"] shouldBe "active"
                qaStatus["participants"] shouldBe "4"
                qaStatus["alive"] shouldBe "4"
                qaStatus["combat"] shouldBe "1"
            }
        }
    }

    test("live reconfigure refreshes safe runtime visuals while the active round keeps its timing snapshot") {
        failOnUnsupportedMockBukkitOperation {
            TttRoundFixture().use { fixture ->
                fixture.startActiveRound()
                val player = fixture.players.first()
                val matchId = requireNotNull(fixture.service.currentMatch()).matchId.toString()
                player.inventory.setItem(2, fixture.firearms.firearmItem(
                    FirearmId.FLINTLOCK,
                    player,
                    matchId,
                    loaded = 1,
                ))
                fixture.service.snapshot().secondsRemaining shouldBe 60L
                player.inventory.getItem(2)?.type shouldBe Material.IRON_HORSE_ARMOR

                fixture.reloadRuntime { raw ->
                    raw.replace("round-seconds: 60", "round-seconds: 120")
                        .replace("body-despawn-seconds: 60", "body-despawn-seconds: 120")
                        .replace(
                            "flintlock: {material: IRON_HORSE_ARMOR, custom-model-data: 0}",
                            "flintlock: {material: BLAZE_ROD, custom-model-data: 0}",
                        )
                }

                fixture.currentSettings().ttt.roundSeconds shouldBe 120
                fixture.service.snapshot().secondsRemaining shouldBe 60L
                val refreshed = requireNotNull(player.inventory.getItem(2))
                refreshed.type shouldBe Material.BLAZE_ROD
                fixture.firearms.state(refreshed) shouldBe FirearmState(FirearmId.FLINTLOCK, 1, matchId)
                verify(exactly = 1) { fixture.network.reconfigure() }
            }
        }
    }
})

private class TttRoundFixture : AutoCloseable {
    val paper = MockBukkitTestRuntime.open()
    private val plugin = paper.createSimplePlugin("ArcEventsTttRoundTest")
    private val dataRoot = Files.createTempDirectory("arcevents-ttt-round-")
    private val world = paper.addSimpleWorld("ttt_arena")
    private var nowMs = 1_787_730_000_000L
    private var started = false
    private val debugLines = mutableListOf<String>()
    private val plain = PlainTextComponentSerializer.plainText()
    private val nextMessages = mutableMapOf<UUID, () -> Component?>()

    private var settings: ArcEventsConfig
    private val locale: ArcEventsLocale
    val items: TttItems
    val firearms: TttFirearms
    val escrow: PlayerStateEscrow
    val network: EventNetworkCoordinator = mockk(relaxed = true) {
        every { stats(any()) } returns PlayerEventStats()
    }
    val arenaPool: ArenaPool
    val service: ArcEventsService
    val players: List<Player>
    private val originals: Map<UUID, OriginalPlayerState>
    private var reservationBatch: ReservationBatch? = null

    init {
        PaperArcRuntime.installScheduling(plugin)
        writeFixtureFiles(dataRoot)
        ConfigManager.clear()
        settings = ArcEventsConfig.load(dataRoot)
        locale = ArcEventsLocale(dataRoot) { settings }
        items = TttItems(plugin, locale) { settings }
        escrow = PlayerStateEscrow(RecoveryBatchStore(dataRoot, Gson()))
        arenaPool = ArenaPool(settings = { settings }, ready = { _, _ -> true })
        firearms = TttFirearms(plugin, locale) { settings }
        val lootScene = TttLootScene(plugin, firearms) { settings }
        service = ArcEventsService(
            plugin = plugin,
            settings = { settings },
            locale = locale,
            escrow = escrow,
            items = items,
            firearms = firearms,
            hud = mockk(relaxed = true),
            lootScene = lootScene,
            smokeGrenades = mockk(relaxed = true),
            network = network,
            debug = ArcEventsDebug({ true }, debugLines::add),
            redisConnected = { true },
            arenaPool = arenaPool,
            weaponPoints = mockk(relaxed = true),
            lootSpawner = mockk(relaxed = true),
            clock = { nowMs },
        )
        paper.server.pluginManager.registerEvents(
            ArcEventsListener(service, mockk(relaxed = true), items),
            plugin,
        )
        players = listOf(
            createPlayer("Alpha", Material.DIAMOND, 10.0, 1),
            createPlayer("Bravo", Material.EMERALD, 20.0, 2),
            createPlayer("Charlie", Material.GOLD_INGOT, 30.0, 3),
            createPlayer("Delta", Material.LAPIS_LAZULI, 40.0, 4),
        )
        originals = players.associate { player ->
            player.uniqueId to OriginalPlayerState(
                location = player.location.clone(),
                gameMode = player.gameMode,
                mainHand = player.inventory.itemInMainHand.clone(),
            )
        }
    }

    fun startActiveRound() {
        check(!started) { "The fixture owns one TTT round" }
        started = true
        service.start()
        paper.performTicks(1)

        service.debugStartLocal(players, "test") shouldBe DebugMutationResult.APPLIED
        service.phase() shouldBe MatchPhase.PREPARING
        escrow.pendingCount(requireNotNull(service.currentMatch()).matchId) shouldBe players.size
        players.forEach { player ->
            player.gameMode shouldBe GameMode.ADVENTURE
            player.world shouldBe world
            player.location.x shouldBe 0.5
            player.location.y shouldBe 64.0
            player.location.z shouldBe 0.5
        }

        service.debugAdvance() shouldBe DebugMutationResult.APPLIED
        service.phase() shouldBe MatchPhase.COUNTDOWN
        service.currentMatch()?.participants?.values?.all { it.status == ParticipantStatus.ALIVE } shouldBe true
        service.debugAdvance() shouldBe DebugMutationResult.APPLIED
        service.phase() shouldBe MatchPhase.ACTIVE
    }

    fun startReservation() {
        check(!started) { "The fixture owns one TTT session" }
        started = true
        service.start()
        paper.performTicks(1)
        val matchId = UUID.randomUUID()
        reservationBatch = ReservationBatch(matchId, players.mapIndexed { index, player ->
            QueueEntry(
                playerId = player.uniqueId.toString(),
                playerName = player.name,
                originServer = if (index == 0) "spawn" else "survival",
                state = QueueState.ARRIVED,
                joinedAtMs = nowMs - 1_000,
                expiresAtMs = Long.MAX_VALUE,
                matchId = matchId.toString(),
                destinationServer = "parkour",
            )
        })
        service.onReservation(requireNotNull(reservationBatch)) shouldBe true
    }

    fun arrive(player: Player): Boolean {
        val batch = requireNotNull(reservationBatch)
        return service.onArrival(batch.entries.single { it.playerId == player.uniqueId.toString() })
    }

    fun playerWithRole(role: TttRole): Player {
        val playerId = requireNotNull(service.currentMatch()).participants.values.single { it.role == role }.playerId
        return players.single { it.uniqueId == playerId }
    }

    @Suppress("DEPRECATION")
    fun meleeHit(attacker: Player, victim: Player, damage: Double): EntityDamageByEntityEvent {
        val source = DamageSource.builder(DamageType.PLAYER_ATTACK)
            .withCausingEntity(attacker)
            .withDirectEntity(attacker)
            .build()
        val event = EntityDamageByEntityEvent(attacker, victim, DamageCause.ENTITY_ATTACK, source, damage)
        return paper.callEvent(event)
    }

    fun diagnostics(): String = buildString {
        append("phase=").append(service.phase())
        append(" match=").append(service.currentMatch())
        append(" debug=").append(debugLines.joinToString(" | "))
    }

    fun qaStatusFields(): Map<String, String> = service.qaStatus()
        .split(' ')
        .drop(1)
        .associate { field ->
            val separator = field.indexOf('=')
            require(separator > 0) { "Malformed QA status field: $field" }
            field.substring(0, separator) to field.substring(separator + 1)
        }

    fun assertOriginalPlayerStateRestored() {
        players.forEach { player ->
            val original = requireNotNull(originals[player.uniqueId])
            player.gameMode shouldBe original.gameMode
            player.inventory.itemInMainHand shouldBe original.mainHand
            player.location.world shouldBe original.location.world
            player.location.x shouldBe original.location.x
            player.location.y shouldBe original.location.y
            player.location.z shouldBe original.location.z
            paper.playerDataSaveCount(player) shouldBe 2
        }
    }

    fun currentSettings(): ArcEventsConfig = settings

    fun addOutsider(name: String): Player = paper.addPlayer(name).also { player ->
        nextMessages[player.uniqueId] = player::nextComponentMessage
        player.teleport(Location(world, 0.5, 64.0, 0.5))
    }

    fun move(player: Player, x: Double) {
        player.teleport(Location(world, x, 64.0, 0.5)) shouldBe true
    }

    fun chat(player: Player, rawMessage: String) {
        val message = Component.text(rawMessage)
        val event = AsyncChatEvent(
            true,
            player,
            paper.server.onlinePlayers.mapTo(mutableSetOf<Audience>()) { it },
            ChatRenderer.defaultRenderer(),
            message,
            message,
            SignedMessage.system(rawMessage, message),
        )
        paper.server.scheduler.executeAsyncEvent(event).get(5, TimeUnit.SECONDS)
        event.isCancelled shouldBe true
        paper.performTicks(2)
    }

    fun drainAllMessages(players: Iterable<Player>) {
        players.forEach(::drainMessages)
    }

    fun drainMessages(player: Player): List<String> {
        val nextMessage = requireNotNull(nextMessages[player.uniqueId])
        return buildList {
            while (true) {
                val message = nextMessage() ?: break
                add(plain.serialize(message))
            }
        }
    }

    fun reloadRuntime(transform: (String) -> String) {
        val configPath = dataRoot.resolve("config.yml")
        Files.writeString(configPath, transform(Files.readString(configPath)))
        settings = ArcEventsConfig.inspect(dataRoot)
        service.reconfigureRuntime()
    }

    private fun createPlayer(name: String, material: Material, x: Double, amount: Int): Player =
        paper.addPlayer(name).also { player ->
            nextMessages[player.uniqueId] = player::nextComponentMessage
            player.gameMode = GameMode.SURVIVAL
            player.teleport(Location(world, x, 70.0, -5.0, 90f, 0f))
            player.inventory.setItemInMainHand(ItemStack.of(material, amount))
        }

    override fun close() {
        var failure: Throwable? = null

        fun closeStep(block: () -> Unit) {
            try {
                block()
            } catch (thrown: Throwable) {
                val previous = failure
                if (previous == null) {
                    failure = thrown
                } else {
                    previous.addSuppressed(thrown)
                }
            }
        }

        if (started) closeStep(service::close)
        closeStep(Tasks::reset)
        closeStep(ConfigManager::clear)
        closeStep(paper::close)
        closeStep {
            check(dataRoot.toFile().deleteRecursively()) { "Failed to delete fixture directory $dataRoot" }
        }
        failure?.let { throw it }
    }
}

private data class OriginalPlayerState(
    val location: Location,
    val gameMode: GameMode,
    val mainHand: ItemStack,
)

private fun writeFixtureFiles(dataRoot: Path) {
    Files.writeString(dataRoot.resolve("config.yml"), TTT_FIXTURE_CONFIG)
    listOf("lang/ru.yml", "lang/en.yml").forEach { resource ->
        val destination = dataRoot.resolve(resource)
        Files.createDirectories(destination.parent)
        requireNotNull(TttRoundLifecycleMockBukkitTest::class.java.getResourceAsStream("/$resource")).use { source ->
            Files.copy(source, destination, StandardCopyOption.REPLACE_EXISTING)
        }
    }
}

private val TTT_FIXTURE_CONFIG = """
    enabled: true
    server-id: parkour
    node-mode: HOST
    host-server: parkour
    locale:
      default: ru
      use-client-locale: false
    debug:
      enabled: true
      allowed-server-ids:
        - parkour
    network:
      enabled: true
      allowed-origins:
        - parkour
      return-to-origin: false
    gameplay:
      clear-inventory: true
    ttt:
      minimum-players: 4
      maximum-players: 4
      preparation-seconds: 3
      countdown-seconds: 3
      round-seconds: 60
      post-round-seconds: 3
      traitor-player-ratio: 4
      detective-minimum-players: 4
      traitor-credits: 2
      detective-credits: 1
      body-despawn-seconds: 60
    ui:
      sounds: false
      particles: false
      bossbar: false
      scoreboard: false
      loot-displays: false
    weapons:
      enabled: false
      dna-seconds: 90
      visuals:
        flintlock: {material: IRON_HORSE_ARMOR, custom-model-data: 0}
    arenas:
      test:
        enabled: true
        world: ttt_arena
        template: ''
        lobby: '0.5,64.0,0.5'
        spectator: '5.5,66.0,5.5'
        minimum: '-20.0,50.0,-20.0'
        maximum: '60.0,100.0,60.0'
        spawns:
          - '0.5,64.0,0.5'
        loot-spawns: []
        weapon-count: 0
""".trimIndent() + "\n"
