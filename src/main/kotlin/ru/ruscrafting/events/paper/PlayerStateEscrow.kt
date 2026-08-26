@file:Suppress("DEPRECATION")

package ru.ruscrafting.events.paper

import com.google.gson.Gson
import com.google.gson.JsonParser
import org.bukkit.GameMode
import org.bukkit.Location
import org.bukkit.NamespacedKey
import org.bukkit.Registry
import org.bukkit.attribute.Attribute
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import org.bukkit.potion.PotionEffect
import org.bukkit.util.Vector
import ru.arc.network.BackendServerId
import ru.arc.paper.playerstate.PaperPlayerStateCodec
import ru.arc.paper.playerstate.PaperPlayerStateEnvelope
import ru.arc.paper.playerstate.PaperPlayerStateService
import ru.arc.persistence.DurableRecordJournal
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID

sealed interface EscrowedPlayerState {
    val playerId: String
    val returnServer: String
}

data class CorePlayerState(
    override val playerId: String,
    override val returnServer: String,
    val envelope: PaperPlayerStateEnvelope,
) : EscrowedPlayerState {
    fun validated(codec: PaperPlayerStateCodec): CorePlayerState = apply {
        val playerUuid = UUID.fromString(playerId)
        require(playerUuid.toString() == playerId) { "Recovery player id is not canonical" }
        BackendServerId.of(returnServer)
        val decoded = codec.decode(requireNotNull(envelope))
        require(decoded.playerId == playerUuid) { "Recovery envelope belongs to a different player" }
    }
}

data class PotionSnapshot(
    val type: String,
    val duration: Int,
    val amplifier: Int,
    val ambient: Boolean,
    val particles: Boolean,
    val icon: Boolean,
    val hidden: PotionSnapshot? = null,
) {
    fun validated(depth: Int = 0): PotionSnapshot = apply {
        require(depth <= MAX_HIDDEN_DEPTH)
        require(NamespacedKey.fromString(type) != null)
        require((duration == PotionEffect.INFINITE_DURATION || duration in 1..1_000_000) && amplifier in 0..255)
        hidden?.validated(depth + 1)
    }

    companion object {
        private const val MAX_HIDDEN_DEPTH = 8
    }
}

data class PlayerStateSnapshot(
    val formatVersion: Int = 1,
    val matchId: String,
    override val playerId: String,
    override val returnServer: String,
    val checksum: String,
    val capturedAtMs: Long,
    val storageBase64: String,
    val armorBase64: String,
    val offhandBase64: String?,
    val cursorBase64: String?,
    val selectedSlot: Int,
    val compassWorld: String,
    val compassX: Double,
    val compassY: Double,
    val compassZ: Double,
    val world: String,
    val x: Double,
    val y: Double,
    val z: Double,
    val yaw: Float,
    val pitch: Float,
    val health: Double,
    val absorption: Double,
    val foodLevel: Int,
    val saturation: Float,
    val exhaustion: Float,
    val level: Int,
    val exp: Float,
    val totalExperience: Int,
    val gameMode: String,
    val allowFlight: Boolean,
    val flying: Boolean,
    val flySpeed: Float,
    val walkSpeed: Float,
    val velocityX: Double,
    val velocityY: Double,
    val velocityZ: Double,
    val fireTicks: Int,
    val fallDistance: Float,
    val remainingAir: Int,
    val noDamageTicks: Int,
    val freezeTicks: Int,
    val gliding: Boolean,
    val swimming: Boolean,
    val sprinting: Boolean,
    val potionEffects: List<PotionSnapshot>,
) : EscrowedPlayerState {
    fun validated(gson: Gson): PlayerStateSnapshot = apply {
        require(formatVersion == 1)
        require(UUID.fromString(matchId).toString() == matchId)
        require(UUID.fromString(playerId).toString() == playerId)
        BackendServerId.of(returnServer)
        require(checksum.matches(Regex("[a-f0-9]{64}")))
        require(checksum == calculateChecksum(copy(checksum = ""), gson)) { "Player-state checksum mismatch" }
        require(capturedAtMs > 0)
        require(selectedSlot in 0..8)
        require(world.matches(Regex("[A-Za-z0-9_./-]{1,64}")))
        require(compassWorld.matches(Regex("[A-Za-z0-9_./-]{1,64}")))
        require(listOf(compassX, compassY, compassZ, x, y, z, health, absorption, velocityX, velocityY, velocityZ).all(Double::isFinite))
        require(yaw.isFinite() && pitch.isFinite() && saturation.isFinite() && exhaustion.isFinite() && fallDistance.isFinite())
        require(health >= 0.0 && absorption >= 0.0)
        require(foodLevel in 0..20 && level in 0..1_000_000 && totalExperience in 0..Int.MAX_VALUE)
        require(exp in 0f..1f)
        require(GameMode.valueOf(gameMode) in GameMode.entries)
        require(flySpeed in -1f..1f && walkSpeed in -1f..1f)
        require(fireTicks in -1_000_000..1_000_000 && remainingAir in -1_000_000..1_000_000 && noDamageTicks in 0..1_000_000)
        require(freezeTicks in 0..1_000_000)
        require(storageBase64.length <= MAX_CONTAINER_CHARS && armorBase64.length <= MAX_CONTAINER_CHARS)
        ItemStack.deserializeItemsFromBytes(Base64.getDecoder().decode(storageBase64))
        ItemStack.deserializeItemsFromBytes(Base64.getDecoder().decode(armorBase64))
        listOfNotNull(offhandBase64, cursorBase64).forEach {
            require(it.length <= MAX_ITEM_CHARS)
            ItemStack.deserializeBytes(Base64.getDecoder().decode(it))
        }
        require(potionEffects.size <= 64)
        potionEffects.forEach(PotionSnapshot::validated)
    }

    companion object {
        private const val MAX_CONTAINER_CHARS = 3_000_000
        private const val MAX_ITEM_CHARS = 1_000_000

        private fun calculateChecksum(unsigned: PlayerStateSnapshot, gson: Gson): String = MessageDigest.getInstance("SHA-256")
            .digest(gson.toJson(unsigned).toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }
}

data class RecoveryBatch(
    val formatVersion: Int = CURRENT_FORMAT_VERSION,
    val matchId: String,
    val createdAtMs: Long,
    val snapshots: List<PlayerStateSnapshot> = emptyList(),
    val states: List<CorePlayerState> = emptyList(),
    val restoredPlayerIds: Set<String> = emptySet(),
) {
    fun validated(gson: Gson, codec: PaperPlayerStateCodec): RecoveryBatch = apply {
        require(formatVersion in LEGACY_FORMAT_VERSION..CURRENT_FORMAT_VERSION) { "Unsupported recovery batch format" }
        require(UUID.fromString(matchId).toString() == matchId)
        require(createdAtMs > 0)
        when (formatVersion) {
            LEGACY_FORMAT_VERSION -> {
                require(states.isEmpty()) { "Legacy recovery batch cannot contain core envelopes" }
                require(snapshots.size in 1..MAX_PLAYERS)
                require(snapshots.all { it.matchId == matchId })
                snapshots.forEach { it.validated(gson) }
            }
            CURRENT_FORMAT_VERSION -> {
                require(snapshots.isEmpty()) { "Current recovery batch cannot contain legacy snapshots" }
                require(states.size in 1..MAX_PLAYERS)
                states.forEach { it.validated(codec) }
            }
        }
        val all = allStates()
        require(all.map(EscrowedPlayerState::playerId).distinct().size == all.size)
        require(restoredPlayerIds.all { restored -> all.any { it.playerId == restored } })
    }

    fun allStates(): List<EscrowedPlayerState> = when (formatVersion) {
        LEGACY_FORMAT_VERSION -> snapshots
        CURRENT_FORMAT_VERSION -> states
        else -> error("Unsupported recovery batch format")
    }

    fun pending(): List<EscrowedPlayerState> = allStates().filter { it.playerId !in restoredPlayerIds }

    companion object {
        const val LEGACY_FORMAT_VERSION = 1
        const val CURRENT_FORMAT_VERSION = 2
        const val MAX_PLAYERS = 32
    }
}

private data class LegacyRecoveryBatchWire(
    val formatVersion: Int,
    val matchId: String,
    val createdAtMs: Long,
    val snapshots: List<PlayerStateSnapshot>,
    val restoredPlayerIds: Set<String> = emptySet(),
)

class RecoveryBatchStore(
    dataRoot: Path,
    private val gson: Gson = Gson(),
    private val stateCodec: PaperPlayerStateCodec = PaperPlayerStateCodec(),
) {
    private val journal = DurableRecordJournal(
        root = dataRoot,
        relativeDirectory = Path.of("data/recovery"),
        maxRecordBytes = MAX_BATCH_BYTES,
        encode = { batch: RecoveryBatch -> (gson.toJson(batch) + "\n").toByteArray(StandardCharsets.UTF_8) },
        decode = ::decodeBatch,
        validate = { batch -> batch.validated(gson, stateCodec) },
    )

    @Synchronized
    fun prepare(matchId: UUID, states: List<CorePlayerState>, nowMs: Long): RecoveryBatch {
        require(states.isNotEmpty() && states.size <= RecoveryBatch.MAX_PLAYERS)
        val batch = RecoveryBatch(
            matchId = matchId.toString(),
            createdAtMs = nowMs,
            states = states,
        ).validated(gson, stateCodec)
        return journal.commit(matchId.toString(), batch)
    }

    @Synchronized
    fun acknowledge(matchId: UUID, playerId: UUID): RecoveryBatch? {
        val batch = journal.loadOrNull(matchId.toString()) ?: return null
        require(batch.allStates().any { it.playerId == playerId.toString() })
        val changed = batch.copy(restoredPlayerIds = batch.restoredPlayerIds + playerId.toString()).validated(gson, stateCodec)
        if (changed.pending().isEmpty()) {
            journal.acknowledge(matchId.toString())
            return null
        }
        return journal.commit(matchId.toString(), changed)
    }

    @Synchronized
    fun loadAll(): List<RecoveryBatch> = journal.loadAll().map { stored ->
        stored.value.also { batch ->
            require(stored.recordId == batch.matchId) { "Recovery batch filename does not match its match id" }
        }
    }.sortedBy(RecoveryBatch::createdAtMs)

    fun pendingFor(playerId: UUID): Pair<RecoveryBatch, EscrowedPlayerState>? = loadAll().firstNotNullOfOrNull { batch ->
        batch.pending().firstOrNull { it.playerId == playerId.toString() }?.let { batch to it }
    }

    fun pendingPlayers(matchId: UUID): Set<UUID> {
        return journal.loadOrNull(matchId.toString())?.pending().orEmpty()
            .map { UUID.fromString(it.playerId) }.toSet()
    }

    private fun decodeBatch(bytes: ByteArray): RecoveryBatch {
        val raw = bytes.toString(StandardCharsets.UTF_8)
        val root = JsonParser.parseString(raw).asJsonObject
        return when (root.get("formatVersion")?.asInt) {
            RecoveryBatch.LEGACY_FORMAT_VERSION -> {
                val legacy = requireNotNull(gson.fromJson(root, LegacyRecoveryBatchWire::class.java))
                RecoveryBatch(
                    formatVersion = legacy.formatVersion,
                    matchId = legacy.matchId,
                    createdAtMs = legacy.createdAtMs,
                    snapshots = legacy.snapshots,
                    restoredPlayerIds = legacy.restoredPlayerIds,
                )
            }
            RecoveryBatch.CURRENT_FORMAT_VERSION -> requireNotNull(gson.fromJson(root, RecoveryBatch::class.java))
            else -> throw IllegalArgumentException("Unsupported recovery batch format")
        }
    }

    companion object {
        private const val MAX_BATCH_BYTES = 768L * 1024L * 1024L
    }
}

class PlayerStateEscrow(
    private val store: RecoveryBatchStore,
    private val playerStates: PaperPlayerStateService = PaperPlayerStateService(),
) {
    fun prepare(matchId: UUID, players: List<Player>, returnServers: Map<UUID, String>, nowMs: Long): RecoveryBatch {
        require(players.isNotEmpty() && players.size <= RecoveryBatch.MAX_PLAYERS)
        require(players.map(Player::getUniqueId).distinct().size == players.size)
        require(players.all { it.uniqueId in returnServers })
        val states = players.map { player ->
            CorePlayerState(
                playerId = player.uniqueId.toString(),
                returnServer = requireNotNull(returnServers[player.uniqueId]).also(BackendServerId::of),
                envelope = playerStates.captureEnvelope(player, nowMs),
            )
        }
        return store.prepare(matchId, states, nowMs)
    }

    fun pendingCount(): Int = store.loadAll().sumOf { it.pending().size }

    fun pendingCount(matchId: UUID): Int = store.pendingPlayers(matchId).size

    fun recover(player: Player, teleport: (Location) -> Boolean = player::teleport): PlayerRecovery? {
        val (batch, state) = store.pendingFor(player.uniqueId) ?: return null
        when (state) {
            is CorePlayerState -> playerStates.restoreAndVerify(player, state.envelope) { _, location -> teleport(location) }
            is PlayerStateSnapshot -> restoreLegacy(player, state, teleport)
        }
        store.acknowledge(UUID.fromString(batch.matchId), player.uniqueId)
        return PlayerRecovery(UUID.fromString(batch.matchId), state.returnServer)
    }

    fun pendingPlayers(): Set<UUID> = store.loadAll().flatMap(RecoveryBatch::pending)
        .map { UUID.fromString(it.playerId) }.toSet()

    fun pendingPlayers(matchId: UUID): Set<UUID> = store.pendingPlayers(matchId)

    private fun restoreLegacy(player: Player, snapshot: PlayerStateSnapshot, teleport: (Location) -> Boolean) {
        val world = requireNotNull(player.server.getWorld(snapshot.world)) { "Recovery world ${snapshot.world} is not loaded" }
        val compassWorld = requireNotNull(player.server.getWorld(snapshot.compassWorld)) {
            "Recovery compass world ${snapshot.compassWorld} is not loaded"
        }
        val storage = ItemStack.deserializeItemsFromBytes(Base64.getDecoder().decode(snapshot.storageBase64))
        val armor = ItemStack.deserializeItemsFromBytes(Base64.getDecoder().decode(snapshot.armorBase64))
        val offhand = decodeItem(snapshot.offhandBase64) ?: ItemStack.empty()
        val cursor = decodeItem(snapshot.cursorBase64) ?: ItemStack.empty()
        val effects = snapshot.potionEffects.map { it.effect() }
        require(teleport(Location(world, snapshot.x, snapshot.y, snapshot.z, snapshot.yaw, snapshot.pitch))) {
            "Recovery teleport was rejected for ${player.uniqueId}"
        }
        player.closeInventory()
        player.inventory.storageContents = storage
        player.inventory.armorContents = armor
        player.inventory.setItemInOffHand(offhand)
        player.setItemOnCursor(cursor)
        player.inventory.heldItemSlot = snapshot.selectedSlot
        player.compassTarget = Location(compassWorld, snapshot.compassX, snapshot.compassY, snapshot.compassZ)
        player.gameMode = GameMode.valueOf(snapshot.gameMode)
        player.allowFlight = snapshot.allowFlight
        player.isFlying = snapshot.flying && snapshot.allowFlight
        player.flySpeed = snapshot.flySpeed
        player.walkSpeed = snapshot.walkSpeed
        player.activePotionEffects.forEach { player.removePotionEffect(it.type) }
        effects.forEach(player::addPotionEffect)
        player.health = snapshot.health.coerceAtMost(requireNotNull(player.getAttribute(Attribute.MAX_HEALTH)).value)
        player.absorptionAmount = snapshot.absorption
        player.foodLevel = snapshot.foodLevel
        player.saturation = snapshot.saturation
        player.exhaustion = snapshot.exhaustion
        player.totalExperience = snapshot.totalExperience
        player.level = snapshot.level
        player.exp = snapshot.exp
        player.velocity = Vector(snapshot.velocityX, snapshot.velocityY, snapshot.velocityZ)
        player.fireTicks = snapshot.fireTicks
        player.fallDistance = snapshot.fallDistance
        player.remainingAir = snapshot.remainingAir
        player.noDamageTicks = snapshot.noDamageTicks
        player.freezeTicks = snapshot.freezeTicks
        player.isGliding = snapshot.gliding
        player.isSwimming = snapshot.swimming
        player.isSprinting = snapshot.sprinting
        player.updateInventory()
        val mismatches = mismatches(player, snapshot)
        require(mismatches.isEmpty()) { "Player state verification failed for ${player.uniqueId}: ${mismatches.joinToString(",")}" }
        player.saveData()
    }

    private fun mismatches(player: Player, snapshot: PlayerStateSnapshot): List<String> = buildList {
        val location = player.location
        if (encodeItems(player.inventory.storageContents) != snapshot.storageBase64) add("storage")
        if (encodeItems(player.inventory.armorContents) != snapshot.armorBase64) add("armor")
        if (encodeItem(player.inventory.itemInOffHand) != snapshot.offhandBase64) add("offhand")
        if (encodeItem(player.itemOnCursor) != snapshot.cursorBase64) add("cursor")
        if (player.inventory.heldItemSlot != snapshot.selectedSlot) add("selectedSlot")
        val compassTarget = player.compassTarget
        if (compassTarget.world.name != snapshot.compassWorld ||
            compassTarget.distanceSquared(Location(compassTarget.world, snapshot.compassX, snapshot.compassY, snapshot.compassZ)) >= 0.01
        ) add("compassTarget")
        if (location.world.name != snapshot.world ||
            location.distanceSquared(Location(location.world, snapshot.x, snapshot.y, snapshot.z)) >= 0.01 ||
            kotlin.math.abs(location.yaw - snapshot.yaw) > 0.1f || kotlin.math.abs(location.pitch - snapshot.pitch) > 0.1f
        ) add("location")
        if (player.gameMode.name != snapshot.gameMode) add("gameMode")
        if (player.allowFlight != snapshot.allowFlight || player.isFlying != (snapshot.flying && snapshot.allowFlight)) add("flight")
        if (kotlin.math.abs(player.flySpeed - snapshot.flySpeed) > 0.0001f ||
            kotlin.math.abs(player.walkSpeed - snapshot.walkSpeed) > 0.0001f
        ) add("speeds")
        if (player.foodLevel != snapshot.foodLevel || kotlin.math.abs(player.saturation - snapshot.saturation) > 0.0001f ||
            kotlin.math.abs(player.exhaustion - snapshot.exhaustion) > 0.0001f
        ) add("food")
        if (player.level != snapshot.level || kotlin.math.abs(player.exp - snapshot.exp) > 0.0001f ||
            player.totalExperience != snapshot.totalExperience
        ) add("experience")
        if (kotlin.math.abs(player.health - snapshot.health.coerceAtMost(requireNotNull(player.getAttribute(Attribute.MAX_HEALTH)).value)) >= 0.001 ||
            kotlin.math.abs(player.absorptionAmount - snapshot.absorption) >= 0.001
        ) add("health")
        if (player.fireTicks != snapshot.fireTicks || kotlin.math.abs(player.fallDistance - snapshot.fallDistance) > 0.0001f ||
            player.remainingAir != snapshot.remainingAir || player.noDamageTicks != snapshot.noDamageTicks ||
            player.freezeTicks != snapshot.freezeTicks
        ) add("timers")
        if (player.isGliding != snapshot.gliding || player.isSwimming != snapshot.swimming || player.isSprinting != snapshot.sprinting) {
            add("movementFlags")
        }
        if (player.velocity.distanceSquared(Vector(snapshot.velocityX, snapshot.velocityY, snapshot.velocityZ)) > 0.000001) add("velocity")
        if (player.activePotionEffects.map { it.snapshot() }.sortedBy(PotionSnapshot::type) !=
            snapshot.potionEffects.sortedBy(PotionSnapshot::type)
        ) add("potionEffects")
    }

    private fun encodeItems(items: Array<out ItemStack?>): String =
        Base64.getEncoder().encodeToString(ItemStack.serializeItemsAsBytes(items))

    private fun encodeItem(item: ItemStack?): String? = item?.takeUnless(ItemStack::isEmpty)?.serializeAsBytes()
        ?.let(Base64.getEncoder()::encodeToString)

    private fun decodeItem(encoded: String?): ItemStack? =
        encoded?.let { ItemStack.deserializeBytes(Base64.getDecoder().decode(it)) }

    private fun PotionSnapshot.effect(): PotionEffect {
        val type = requireNotNull(Registry.MOB_EFFECT.get(requireNotNull(NamespacedKey.fromString(type))))
        return PotionEffect(type, duration, amplifier, ambient, particles, icon, hidden?.effect())
    }

    private fun PotionEffect.snapshot(): PotionSnapshot = PotionSnapshot(
        type = type.key.toString(), duration = duration, amplifier = amplifier, ambient = isAmbient,
        particles = hasParticles(), icon = hasIcon(), hidden = hiddenPotionEffect?.snapshot(),
    )
}

data class PlayerRecovery(val matchId: UUID, val returnServer: String)
