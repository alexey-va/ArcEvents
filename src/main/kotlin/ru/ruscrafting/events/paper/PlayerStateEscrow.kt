@file:Suppress("DEPRECATION")

package ru.ruscrafting.events.paper

import com.google.gson.Gson
import org.bukkit.GameMode
import org.bukkit.Location
import org.bukkit.NamespacedKey
import org.bukkit.Registry
import org.bukkit.attribute.Attribute
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import org.bukkit.potion.PotionEffect
import org.bukkit.util.Vector
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID

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
    val playerId: String,
    val returnServer: String,
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
) {
    fun validated(gson: Gson): PlayerStateSnapshot = apply {
        require(formatVersion == 1)
        require(UUID.fromString(matchId).toString() == matchId)
        require(UUID.fromString(playerId).toString() == playerId)
        require(returnServer.matches(Regex("[a-z0-9_-]{1,32}")))
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

        fun capture(matchId: UUID, player: Player, returnServer: String, nowMs: Long, gson: Gson): PlayerStateSnapshot {
            require(player.isOnline) { "Cannot capture an offline player" }
            val location = player.location
            val compassTarget = player.compassTarget
            val unsigned = PlayerStateSnapshot(
                matchId = matchId.toString(),
                playerId = player.uniqueId.toString(),
                returnServer = returnServer,
                checksum = "",
                capturedAtMs = nowMs,
                storageBase64 = encodeItems(player.inventory.storageContents),
                armorBase64 = encodeItems(player.inventory.armorContents),
                offhandBase64 = encodeItem(player.inventory.itemInOffHand),
                cursorBase64 = encodeItem(player.itemOnCursor),
                selectedSlot = player.inventory.heldItemSlot,
                compassWorld = compassTarget.world.name,
                compassX = compassTarget.x,
                compassY = compassTarget.y,
                compassZ = compassTarget.z,
                world = location.world.name,
                x = location.x,
                y = location.y,
                z = location.z,
                yaw = location.yaw,
                pitch = location.pitch,
                health = player.health,
                absorption = player.absorptionAmount,
                foodLevel = player.foodLevel,
                saturation = player.saturation,
                exhaustion = player.exhaustion,
                level = player.level,
                exp = player.exp,
                totalExperience = player.totalExperience,
                gameMode = player.gameMode.name,
                allowFlight = player.allowFlight,
                flying = player.isFlying,
                flySpeed = player.flySpeed,
                walkSpeed = player.walkSpeed,
                velocityX = player.velocity.x,
                velocityY = player.velocity.y,
                velocityZ = player.velocity.z,
                fireTicks = player.fireTicks,
                fallDistance = player.fallDistance,
                remainingAir = player.remainingAir,
                noDamageTicks = player.noDamageTicks.coerceAtLeast(0),
                freezeTicks = player.freezeTicks.coerceAtLeast(0),
                gliding = player.isGliding,
                swimming = player.isSwimming,
                sprinting = player.isSprinting,
                potionEffects = player.activePotionEffects.map { it.snapshot() },
            )
            return unsigned.copy(checksum = calculateChecksum(unsigned, gson)).validated(gson)
        }

        private fun encodeItems(items: Array<out ItemStack?>): String =
            Base64.getEncoder().encodeToString(ItemStack.serializeItemsAsBytes(items))

        private fun encodeItem(item: ItemStack?): String? = item?.takeUnless(ItemStack::isEmpty)?.serializeAsBytes()
            ?.let(Base64.getEncoder()::encodeToString)

        private fun calculateChecksum(unsigned: PlayerStateSnapshot, gson: Gson): String = MessageDigest.getInstance("SHA-256")
            .digest(gson.toJson(unsigned).toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

        private fun PotionEffect.snapshot(): PotionSnapshot = PotionSnapshot(
            type = type.key.toString(),
            duration = duration,
            amplifier = amplifier,
            ambient = isAmbient,
            particles = hasParticles(),
            icon = hasIcon(),
            hidden = hiddenPotionEffect?.snapshot(),
        )
    }
}

data class RecoveryBatch(
    val formatVersion: Int = 1,
    val matchId: String,
    val createdAtMs: Long,
    val snapshots: List<PlayerStateSnapshot>,
    val restoredPlayerIds: Set<String> = emptySet(),
) {
    fun validated(gson: Gson): RecoveryBatch = apply {
        require(formatVersion == 1)
        require(UUID.fromString(matchId).toString() == matchId)
        require(createdAtMs > 0)
        require(snapshots.size in 1..32)
        require(snapshots.map(PlayerStateSnapshot::playerId).distinct().size == snapshots.size)
        require(snapshots.all { it.matchId == matchId })
        snapshots.forEach { it.validated(gson) }
        require(restoredPlayerIds.all { restored -> snapshots.any { it.playerId == restored } })
    }

    fun pending(): List<PlayerStateSnapshot> = snapshots.filter { it.playerId !in restoredPlayerIds }
}

class RecoveryBatchStore(
    dataRoot: Path,
    private val gson: Gson = Gson(),
) {
    private val directory = dataRoot.resolve("data/recovery")

    init {
        Files.createDirectories(directory)
    }

    @Synchronized
    fun prepare(matchId: UUID, players: List<Player>, returnServers: Map<UUID, String>, nowMs: Long): RecoveryBatch {
        require(players.isNotEmpty() && players.size <= 32)
        require(players.map(Player::getUniqueId).distinct().size == players.size)
        require(players.all { it.uniqueId in returnServers })
        val batch = RecoveryBatch(
            matchId = matchId.toString(),
            createdAtMs = nowMs,
            snapshots = players.map { PlayerStateSnapshot.capture(matchId, it, requireNotNull(returnServers[it.uniqueId]), nowMs, gson) },
        ).validated(gson)
        write(batch)
        return read(path(matchId)).also { loaded -> require(loaded == batch) { "Recovery batch readback mismatch" } }
    }

    @Synchronized
    fun acknowledge(matchId: UUID, playerId: UUID): RecoveryBatch? {
        val file = path(matchId)
        if (!Files.isRegularFile(file)) return null
        val batch = read(file)
        require(batch.snapshots.any { it.playerId == playerId.toString() })
        val changed = batch.copy(restoredPlayerIds = batch.restoredPlayerIds + playerId.toString()).validated(gson)
        if (changed.pending().isEmpty()) {
            Files.deleteIfExists(file)
            return null
        }
        write(changed)
        return changed
    }

    @Synchronized
    fun loadAll(): List<RecoveryBatch> {
        if (!Files.isDirectory(directory)) return emptyList()
        return Files.list(directory).use { paths ->
            paths.filter { it.fileName.toString().endsWith(".json") }
                .map(::read)
                .toList()
                .sortedBy(RecoveryBatch::createdAtMs)
        }
    }

    fun pendingFor(playerId: UUID): Pair<RecoveryBatch, PlayerStateSnapshot>? = loadAll().firstNotNullOfOrNull { batch ->
        batch.pending().firstOrNull { it.playerId == playerId.toString() }?.let { batch to it }
    }

    fun pendingPlayers(matchId: UUID): Set<UUID> {
        val file = path(matchId)
        if (!Files.isRegularFile(file)) return emptySet()
        return read(file).pending().map { UUID.fromString(it.playerId) }.toSet()
    }

    @Synchronized
    private fun write(batch: RecoveryBatch) {
        val validated = batch.validated(gson)
        val target = path(UUID.fromString(validated.matchId))
        val temporary = Files.createTempFile(directory, ".${validated.matchId}-", ".tmp")
        val bytes = (gson.toJson(validated) + "\n").toByteArray(StandardCharsets.UTF_8)
        require(bytes.size <= MAX_BATCH_BYTES) { "Recovery batch is too large" }
        FileChannel.open(temporary, StandardOpenOption.WRITE).use { channel ->
            channel.write(ByteBuffer.wrap(bytes))
            channel.force(true)
        }
        try {
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun read(file: Path): RecoveryBatch {
        val size = Files.size(file)
        require(size in 1..MAX_BATCH_BYTES.toLong()) { "Recovery batch has an invalid size" }
        return gson.fromJson(Files.readString(file), RecoveryBatch::class.java).validated(gson)
    }

    private fun path(matchId: UUID): Path = directory.resolve("$matchId.json")

    companion object {
        private const val MAX_BATCH_BYTES = 32 * 3_500_000
    }
}

class PlayerStateEscrow(
    private val store: RecoveryBatchStore,
) {
    fun prepare(matchId: UUID, players: List<Player>, returnServers: Map<UUID, String>, nowMs: Long): RecoveryBatch =
        store.prepare(matchId, players, returnServers, nowMs)

    fun pendingCount(): Int = store.loadAll().sumOf { it.pending().size }

    fun pendingCount(matchId: UUID): Int = store.pendingPlayers(matchId).size

    fun recover(player: Player, teleport: (Location) -> Boolean = player::teleport): PlayerRecovery? {
        val (batch, snapshot) = store.pendingFor(player.uniqueId) ?: return null
        apply(player, snapshot, teleport)
        val mismatches = mismatches(player, snapshot)
        require(mismatches.isEmpty()) { "Player state verification failed for ${player.uniqueId}: ${mismatches.joinToString(",")}" }
        player.saveData()
        store.acknowledge(UUID.fromString(batch.matchId), player.uniqueId)
        return PlayerRecovery(UUID.fromString(batch.matchId), snapshot.returnServer)
    }

    fun pendingPlayers(): Set<UUID> = store.loadAll().flatMap(RecoveryBatch::pending)
        .map { UUID.fromString(it.playerId) }.toSet()

    fun pendingPlayers(matchId: UUID): Set<UUID> = store.pendingPlayers(matchId)

    private fun apply(player: Player, snapshot: PlayerStateSnapshot, teleport: (Location) -> Boolean) {
        val world = requireNotNull(player.server.getWorld(snapshot.world)) { "Recovery world ${snapshot.world} is not loaded" }
        player.closeInventory()
        player.inventory.storageContents = ItemStack.deserializeItemsFromBytes(Base64.getDecoder().decode(snapshot.storageBase64))
        player.inventory.armorContents = ItemStack.deserializeItemsFromBytes(Base64.getDecoder().decode(snapshot.armorBase64))
        player.inventory.setItemInOffHand(decodeItem(snapshot.offhandBase64) ?: ItemStack.empty())
        player.setItemOnCursor(decodeItem(snapshot.cursorBase64) ?: ItemStack.empty())
        player.inventory.heldItemSlot = snapshot.selectedSlot
        val compassWorld = requireNotNull(player.server.getWorld(snapshot.compassWorld)) {
            "Recovery compass world ${snapshot.compassWorld} is not loaded"
        }
        player.compassTarget = Location(compassWorld, snapshot.compassX, snapshot.compassY, snapshot.compassZ)
        player.gameMode = GameMode.valueOf(snapshot.gameMode)
        player.allowFlight = snapshot.allowFlight
        player.isFlying = snapshot.flying && snapshot.allowFlight
        player.flySpeed = snapshot.flySpeed
        player.walkSpeed = snapshot.walkSpeed
        player.activePotionEffects.forEach { player.removePotionEffect(it.type) }
        snapshot.potionEffects.forEach { saved ->
            player.addPotionEffect(saved.effect())
        }
        require(teleport(Location(world, snapshot.x, snapshot.y, snapshot.z, snapshot.yaw, snapshot.pitch))) {
            "Recovery teleport was rejected for ${player.uniqueId}"
        }
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
