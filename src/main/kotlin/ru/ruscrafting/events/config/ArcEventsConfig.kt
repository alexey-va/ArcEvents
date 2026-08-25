package ru.ruscrafting.events.config

import ru.arc.config.Config
import ru.arc.config.ConfigManager
import ru.arc.redis.LegacyRedisSnapshot
import ru.arc.redis.RedisConfigBootstrap
import ru.arc.redis.RedisModuleConfig
import java.nio.file.Files
import java.nio.file.Path

enum class NodeMode { RELAY, HOST }

data class EventLocation(
    val world: String,
    val x: Double,
    val y: Double,
    val z: Double,
    val yaw: Float = 0f,
    val pitch: Float = 0f,
) {
    fun validated(): EventLocation = apply {
        require(world.matches(Regex("[A-Za-z0-9_./-]{1,64}"))) { "Invalid world name" }
        require(listOf(x, y, z).all(Double::isFinite)) { "Location coordinates must be finite" }
        require(y in -2048.0..2048.0) { "Location Y is outside the supported range" }
        require(yaw.isFinite() && pitch.isFinite()) { "Location rotation must be finite" }
    }
}

data class EventBounds(
    val minimum: EventLocation,
    val maximum: EventLocation,
) {
    fun validated(): EventBounds = apply {
        require(minimum.world == maximum.world) { "Arena bounds must be in one world" }
        require(minimum.x < maximum.x && minimum.y < maximum.y && minimum.z < maximum.z) {
            "Arena minimum must be strictly below maximum"
        }
        require(volume() <= MAX_VOLUME) { "Arena volume exceeds the safety limit" }
    }

    fun contains(location: EventLocation): Boolean = location.world == minimum.world &&
        location.x in minimum.x..maximum.x && location.y in minimum.y..maximum.y && location.z in minimum.z..maximum.z

    private fun volume(): Double =
        (maximum.x - minimum.x) * (maximum.y - minimum.y) * (maximum.z - minimum.z)

    companion object {
        private const val MAX_VOLUME = 250_000_000.0
    }
}

data class ArenaSettings(
    val id: String,
    val enabled: Boolean,
    val world: String,
    val template: String,
    val lobby: EventLocation?,
    val spectator: EventLocation?,
    val bounds: EventBounds?,
    val spawns: List<EventLocation>,
    val lootSpawns: List<EventLocation>,
) {
    fun operational(maximumPlayers: Int): Boolean = runCatching {
        require(enabled)
        requireNotNull(lobby).validated()
        requireNotNull(spectator).validated()
        requireNotNull(bounds).validated()
        require(spawns.size >= maximumPlayers)
        require(spawns.distinctBy { Triple(it.x, it.y, it.z) }.size == spawns.size)
        require(bounds.contains(lobby) && bounds.contains(spectator))
        require(spawns.all { bounds.contains(it.validated()) })
        require(lootSpawns.distinctBy { Triple(it.x, it.y, it.z) }.size == lootSpawns.size)
        require(lootSpawns.all { bounds.contains(it.validated()) })
    }.isSuccess
}

data class NetworkSettings(
    val enabled: Boolean,
    val allowedOrigins: Set<String>,
    val queueEntrySeconds: Int,
    val reservationSeconds: Int,
    val heartbeatSeconds: Int,
    val heartbeatStaleSeconds: Int,
    val transferOnReservation: Boolean,
    val returnToOrigin: Boolean,
)

data class TttSettings(
    val minimumPlayers: Int,
    val maximumPlayers: Int,
    val preparationSeconds: Int,
    val countdownSeconds: Int,
    val roundSeconds: Int,
    val postRoundSeconds: Int,
    val traitorPlayerRatio: Int,
    val detectiveMinimumPlayers: Int,
    val traitorCredits: Int,
    val detectiveCredits: Int,
    val bodyDespawnSeconds: Int,
)

data class UiItemSettings(val material: String, val customModelData: Int)

data class FirearmVisualSettings(val material: String, val customModelData: Int)

data class WeaponSettings(
    val enabled: Boolean,
    val dnaSeconds: Int,
    val pistol: FirearmVisualSettings,
    val smg: FirearmVisualSettings,
    val shotgun: FirearmVisualSettings,
    val rifle: FirearmVisualSettings,
)

data class UiSettings(
    val sounds: Boolean,
    val particles: Boolean,
    val bossBar: Boolean,
    val scoreboard: Boolean,
    val lootDisplays: Boolean,
    val dialogsEnabled: Boolean,
    val filler: UiItemSettings,
)

data class DebugSettings(
    val enabled: Boolean,
    val allowedServerIds: Set<String>,
) {
    fun mutationsAllowed(serverId: String): Boolean = enabled && serverId in allowedServerIds
}

class ArcEventsConfig(private val config: Config) {
    val enabled: Boolean get() = config.bool("enabled", true)
    val serverId: String get() = config.string("server-id", "parkour").trim().lowercase()
    val nodeMode: NodeMode get() = NodeMode.valueOf(config.string("node-mode", "RELAY").trim().uppercase())
    val hostServer: String get() = config.string("host-server", "parkour").trim().lowercase()
    val defaultLocale: String get() = config.string("locale.default", "ru").trim().lowercase()
    val useClientLocale: Boolean get() = config.bool("locale.use-client-locale", true)
    val debug: DebugSettings
        get() = DebugSettings(
            enabled = config.bool("debug.enabled", false),
            allowedServerIds = config.stringList("debug.allowed-server-ids", listOf("lab"))
                .map { it.trim().lowercase() }
                .filter(String::isNotEmpty)
                .toSet(),
        )
    val debugEnabled: Boolean get() = debug.enabled
    val debugMutationsAllowed: Boolean get() = debug.mutationsAllowed(serverId)

    val network: NetworkSettings
        get() = NetworkSettings(
            enabled = config.bool("network.enabled", true),
            allowedOrigins = config.stringList("network.allowed-origins", listOf("spawn", "survival", "parkour"))
                .map(String::lowercase).toSet(),
            queueEntrySeconds = config.int("network.queue-entry-seconds", 300),
            reservationSeconds = config.int("network.reservation-seconds", 45),
            heartbeatSeconds = config.int("network.heartbeat-seconds", 5),
            heartbeatStaleSeconds = config.int("network.heartbeat-stale-seconds", 20),
            transferOnReservation = config.bool("network.transfer-on-reservation", true),
            returnToOrigin = config.bool("network.return-to-origin", true),
        )

    val ttt: TttSettings
        get() = TttSettings(
            minimumPlayers = config.int("ttt.minimum-players", 4),
            maximumPlayers = config.int("ttt.maximum-players", 16),
            preparationSeconds = config.int("ttt.preparation-seconds", 30),
            countdownSeconds = config.int("ttt.countdown-seconds", 8),
            roundSeconds = config.int("ttt.round-seconds", 600),
            postRoundSeconds = config.int("ttt.post-round-seconds", 12),
            traitorPlayerRatio = config.int("ttt.traitor-player-ratio", 4),
            detectiveMinimumPlayers = config.int("ttt.detective-minimum-players", 6),
            traitorCredits = config.int("ttt.traitor-credits", 2),
            detectiveCredits = config.int("ttt.detective-credits", 1),
            bodyDespawnSeconds = config.int("ttt.body-despawn-seconds", 600),
        )

    val ui: UiSettings
        get() = UiSettings(
            sounds = config.bool("ui.sounds", true),
            particles = config.bool("ui.particles", true),
            bossBar = config.bool("ui.bossbar", true),
            scoreboard = config.bool("ui.scoreboard", true),
            lootDisplays = config.bool("ui.loot-displays", true),
            dialogsEnabled = config.bool("ui.dialogs-enabled", false),
            filler = UiItemSettings(
                material = config.string("ui.filler.material", "GRAY_STAINED_GLASS_PANE").uppercase(),
                customModelData = config.int("ui.filler.custom-model-data", 0),
            ),
        )

    val weapons: WeaponSettings
        get() = WeaponSettings(
            enabled = config.bool("weapons.enabled", true),
            dnaSeconds = config.int("weapons.dna-seconds", 90),
            pistol = firearmVisual("weapons.visuals.pistol", "IRON_HORSE_ARMOR"),
            smg = firearmVisual("weapons.visuals.smg", "GOLDEN_HORSE_ARMOR"),
            shotgun = firearmVisual("weapons.visuals.shotgun", "CROSSBOW"),
            rifle = firearmVisual("weapons.visuals.rifle", "NETHERITE_SHOVEL"),
        )

    val arenas: List<ArenaSettings>
        get() = config.keys("arenas").sorted().map { id -> parseArena(id, "arenas.$id") }
            .ifEmpty { listOf(parseArena("default", "arena")) }

    /** Legacy convenience for safe relay defaults and older integrations. */
    val arena: ArenaSettings get() = arenas.first()

    fun validated(): ArcEventsConfig = apply {
        require(enabled) { "ArcEvents is disabled in config.yml" }
        require(serverId.matches(SERVER_ID)) { "server-id is invalid" }
        require(hostServer.matches(SERVER_ID)) { "host-server is invalid" }
        require(defaultLocale in setOf("ru", "en")) { "locale.default must be ru or en" }
        require(debug.allowedServerIds.isNotEmpty() && debug.allowedServerIds.all { it.matches(SERVER_ID) }) {
            "debug.allowed-server-ids contains an invalid server id"
        }
        val network = network
        require(network.enabled) { "Redis coordination is mandatory for ArcEvents" }
        require(network.allowedOrigins.isNotEmpty() && network.allowedOrigins.all { it.matches(SERVER_ID) }) {
            "network.allowed-origins contains an invalid server id"
        }
        require(serverId in network.allowedOrigins && hostServer in network.allowedOrigins) {
            "allowed origins must include this node and the host"
        }
        require(network.queueEntrySeconds in 30..3600)
        require(network.reservationSeconds in 10..300)
        require(network.heartbeatSeconds in 2..60)
        require(network.heartbeatStaleSeconds >= network.heartbeatSeconds * 2)
        val ttt = ttt
        require(ttt.minimumPlayers in 4..ttt.maximumPlayers)
        require(ttt.maximumPlayers in 4..32)
        require(ttt.preparationSeconds in 3..120)
        require(ttt.countdownSeconds in 3..30)
        require(ttt.roundSeconds in 60..3600)
        require(ttt.postRoundSeconds in 3..60)
        require(ttt.traitorPlayerRatio in 3..8)
        require(ttt.detectiveMinimumPlayers in 4..ttt.maximumPlayers)
        require(ttt.traitorCredits in 0..16 && ttt.detectiveCredits in 0..16)
        require(ttt.bodyDespawnSeconds in ttt.roundSeconds..3600)
        require(ui.filler.customModelData >= 0)
        require(weapons.dnaSeconds in 15..300)
        listOf(weapons.pistol, weapons.smg, weapons.shotgun, weapons.rifle).forEach { visual ->
            require(visual.material.matches(Regex("[A-Z0-9_]{1,64}"))) { "Weapon material is invalid" }
            require(visual.customModelData >= 0) { "Weapon custom-model-data cannot be negative" }
        }
        require(arenas.size in 1..16) { "Arena count is outside the safety limit" }
        require(arenas.map(ArenaSettings::id).distinct().size == arenas.size) { "Arena ids must be unique" }
        require(arenas.map(ArenaSettings::world).distinct().size == arenas.size) { "Arena worlds must be unique" }
        arenas.forEach { arena ->
            require(arena.id.matches(ARENA_ID)) { "arena id is invalid" }
            require(arena.template in SUPPORTED_TEMPLATES) { "arena ${arena.id} template is unsupported" }
            if (arena.template.isNotEmpty()) {
                require(nodeMode == NodeMode.HOST) { "Only a HOST node may provision an arena template" }
                require(arena.world.matches(Regex("[A-Za-z0-9_-]{1,32}"))) { "A provisioned arena requires a safe world name" }
                require(arena.world !in PROTECTED_WORLDS) { "A provisioned arena must use a dedicated world" }
            }
            if (arena.enabled) require(arena.operational(ttt.maximumPlayers)) {
                "Enabled arena ${arena.id} is incomplete or contains an unsafe location"
            }
            require(arena.spawns.size <= 64) { "Arena ${arena.id} has too many player spawns" }
            require(arena.lootSpawns.size <= 128) { "Arena ${arena.id} has too many loot spawns" }
            if (arena.enabled && weapons.enabled && arena.template !in setOf("", "citadel-v1")) {
                require(arena.lootSpawns.size >= ttt.maximumPlayers) {
                    "Imported arena ${arena.id} requires at least ${ttt.maximumPlayers} loot spawns"
                }
            }
        }
        if (nodeMode == NodeMode.HOST) require(arenas.any(ArenaSettings::enabled)) { "HOST node requires an enabled arena" }
        if (nodeMode == NodeMode.HOST) require(serverId == hostServer) { "HOST node must equal host-server" }
    }

    companion object {
        private val SERVER_ID = Regex("[a-z0-9_-]{1,32}")
        private val ARENA_ID = Regex("[a-z0-9_-]{1,32}")
        private val SUPPORTED_TEMPLATES = setOf("", "citadel-v1", "cs2-inferno-v1", "cs2-nuke-v1", "cs2-mirage-v1")
        private val PROTECTED_WORLDS = setOf("world", "world_nether", "world_the_end", "pvp", "parkour1")

        fun load(dataRoot: Path): ArcEventsConfig = ArcEventsConfig(ConfigManager.of(dataRoot, "config.yml")).validated()

        fun inspect(dataRoot: Path): ArcEventsConfig = ArcEventsConfig(Config(dataRoot, "config.yml")).validated()

        private fun parseLocation(world: String, raw: String): EventLocation? {
            if (raw.isBlank()) return null
            val values = raw.split(',').map(String::trim)
            require(values.size in 3..5) { "Location must be x,y,z[,yaw,pitch]" }
            return EventLocation(
                world = world,
                x = values[0].toDouble(),
                y = values[1].toDouble(),
                z = values[2].toDouble(),
                yaw = values.getOrNull(3)?.toFloat() ?: 0f,
                pitch = values.getOrNull(4)?.toFloat() ?: 0f,
            ).validated()
        }

        private fun parseBound(world: String, raw: String): EventLocation? = parseLocation(world, raw)?.copy(yaw = 0f, pitch = 0f)
    }

    private fun firearmVisual(path: String, fallback: String): FirearmVisualSettings = FirearmVisualSettings(
        material = config.string("$path.material", fallback).trim().uppercase(),
        customModelData = config.int("$path.custom-model-data", 0),
    )

    private fun parseArena(id: String, path: String): ArenaSettings {
        val world = config.string("$path.world", "pvp").trim()
        val lobby = parseLocation(world, config.string("$path.lobby", ""))
        val spectator = parseLocation(world, config.string("$path.spectator", ""))
        val minimum = parseBound(world, config.string("$path.minimum", ""))
        val maximum = parseBound(world, config.string("$path.maximum", ""))
        return ArenaSettings(
            id = id.trim().lowercase(),
            enabled = config.bool("$path.enabled", false),
            world = world,
            template = config.string("$path.template", "").trim().lowercase(),
            lobby = lobby,
            spectator = spectator,
            bounds = if (minimum != null && maximum != null) EventBounds(minimum, maximum) else null,
            spawns = config.stringList("$path.spawns", emptyList()).mapNotNull { parseLocation(world, it) },
            lootSpawns = config.stringList("$path.loot-spawns", emptyList()).mapNotNull { parseLocation(world, it) },
        )
    }
}

object ArcEventsRedisBootstrap {
    fun load(dataRoot: Path, settings: ArcEventsConfig): RedisModuleConfig {
        val redisPath = dataRoot.resolve("modules/redis.yml")
        val existed = Files.isRegularFile(redisPath)
        RedisConfigBootstrap.ensure(dataRoot) {
            if (existed) null else readArcRedis(dataRoot, settings.serverId)
        }
        val redisConfig = ConfigManager.ofModule(dataRoot, RedisModuleConfig.RESOURCE)
        if (redisConfig.bool("inherit-connection-from-arc", false)) {
            val inherited = requireNotNull(readArcRedis(dataRoot, settings.serverId)) {
                "ARC Redis profile is required when inherit-connection-from-arc is enabled"
            }
            inherited.applyTo(redisConfig)
        }
        var redis = RedisModuleConfig(redisConfig)
        val expectedMain = settings.serverId == "spawn"
        if (redis.serverName != settings.serverId || redis.mainServer != expectedMain) {
            redisConfig.setString("server-name", settings.serverId)
            redisConfig.setBoolean("main-server", expectedMain)
            redisConfig.saveStrict()
            redis = RedisModuleConfig(redisConfig)
        }
        require(redis.enabled) { "Redis is mandatory for ArcEvents" }
        return redis
    }

    private fun readArcRedis(dataRoot: Path, serverId: String): LegacyRedisSnapshot? {
        val arcRoot = dataRoot.parent?.resolve("ARC") ?: return null
        val sourcePath = arcRoot.resolve("modules/redis.yml")
        if (!Files.isRegularFile(sourcePath)) return null
        val source = Config(arcRoot, "modules/redis.yml")
        return LegacyRedisSnapshot(
            enabled = source.bool("enabled", true),
            host = source.string("host", "127.0.0.1"),
            port = source.int("port", 6379),
            username = source.string("username", ""),
            password = source.string("password", ""),
            serverName = serverId,
            mainServer = serverId == "spawn",
        )
    }
}
