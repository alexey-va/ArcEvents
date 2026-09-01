package ru.ruscrafting.events.config

import ru.arc.config.Config
import ru.arc.config.ConfigManager
import ru.arc.network.BackendServerId
import ru.arc.redis.RedisConnectionSettingsSnapshot
import ru.arc.redis.RedisConfigBootstrap
import ru.arc.redis.RedisModuleConfig
import ru.ruscrafting.events.domain.FirearmId
import ru.ruscrafting.events.domain.FirearmRarity
import ru.ruscrafting.events.domain.FirearmSpec
import ru.ruscrafting.events.domain.TttFirearmCatalog
import java.nio.file.Files
import java.nio.file.Path
import kotlin.math.floor

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
    val weaponCount: Int = 0,
) {
    val playerSpawn: EventLocation get() = spawns.single()

    fun operational(@Suppress("UNUSED_PARAMETER") maximumPlayers: Int): Boolean = runCatching {
        require(enabled)
        requireNotNull(lobby).validated()
        requireNotNull(spectator).validated()
        requireNotNull(bounds).validated()
        require(spawns.size == 1)
        require(bounds.contains(lobby) && bounds.contains(spectator))
        require(spawns.all { bounds.contains(it.validated()) })
        require(lootSpawns.distinctBy { Triple(floor(it.x), floor(it.y), floor(it.z)) }.size == lootSpawns.size)
        require(lootSpawns.all { bounds.contains(it.validated()) })
        require(weaponCount in 0..lootSpawns.size)
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

data class EventControlSettings(
    val creatorControlsEnabled: Boolean,
    val creatorArenaSelectionEnabled: Boolean,
    val adminOverrideEnabled: Boolean,
)

data class LocalChatSettings(
    val enabled: Boolean,
    val closeDistance: Double,
    val normalDistance: Double,
    val maximumDistance: Double,
)

data class GameplaySettings(
    val spawnReturnSeconds: Int,
    val spawnReturnMovementTolerance: Double,
    val radarDurationSeconds: Int,
    val clearInventory: Boolean,
    val preparationRations: Int,
    val medkitHealing: Double,
    val traitorBladeCost: Int,
    val traitorRadarCost: Int,
    val traitorSmokeCost: Int,
    val detectiveScannerCost: Int,
    val detectiveMedkitCost: Int,
    val detectiveArmorCost: Int,
    val pickupAmmoMagazines: Int,
    val pickupAmmoMinimum: Int,
    val pickupAmmoMaximum: Int,
    val pickupDelayTicks: Int,
    val headshotMultiplier: Double,
    val preparingTipSeconds: Int,
)

data class SmokeSettings(
    val throwVelocity: Double,
    val radius: Double,
    val durationSeconds: Int,
    val tickIntervalTicks: Int,
    val blindnessRefreshTicks: Int,
    val darknessRefreshTicks: Int,
    val smokeParticles: Int,
    val ashParticles: Int,
)

data class ArenaRuntimeSettings(
    val viewDistance: Int,
    val simulationDistance: Int,
    val preserveImportedDisplays: Boolean,
    val maxImportedDisplays: Int,
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

data class NameplateBackgroundSettings(
    val alpha: Int,
    val red: Int,
    val green: Int,
    val blue: Int,
)

data class NameplateSettings(
    val enabled: Boolean,
    val reconcilePeriodTicks: Long,
    val maxDistance: Double,
    val lineWidth: Int,
    val viewRange: Double,
    val scale: Double,
    val verticalOffset: Double,
    val shadowed: Boolean,
    val background: NameplateBackgroundSettings,
    val hideInvisibleTargets: Boolean,
    val hideSpectatorTargets: Boolean,
    val requireLineOfSight: Boolean,
    val minimumViewAlignment: Double,
    val healthPriority: Int,
    val summaryPriority: Int,
)

data class FirearmVisualSettings(val material: String, val customModelData: Int)

data class LootEffectSettings(
    val enabled: Boolean,
    val material: String,
    val customModelData: Map<FirearmRarity, Int>,
    val height: Double,
    val scale: Double,
) {
    fun visual(rarity: FirearmRarity): FirearmVisualSettings =
        FirearmVisualSettings(material, customModelData.getValue(rarity))
}

data class LootDisplaySettings(
    val enabled: Boolean,
    val height: Double,
    val scale: Double,
    val viewRange: Double,
    val animationStepTicks: Long,
    val rotationTicks: Int,
    val particleIntervalTicks: Int,
)

data class WeaponSettings(
    val enabled: Boolean,
    val dnaSeconds: Int,
    val specs: Map<FirearmId, FirearmSpec>,
    val visuals: Map<FirearmId, FirearmVisualSettings>,
    val lootEffect: LootEffectSettings,
) {
    fun spec(id: FirearmId): FirearmSpec = specs.getValue(id)
    fun visual(id: FirearmId): FirearmVisualSettings = visuals.getValue(id)
}

data class UiSettings(
    val sounds: Boolean,
    val particles: Boolean,
    val bossBar: Boolean,
    val scoreboard: Boolean,
    val lootDisplay: LootDisplaySettings,
    val dialogsEnabled: Boolean,
    val nameplates: NameplateSettings,
    val filler: UiItemSettings,
    val back: UiItemSettings,
) {
    val lootDisplays: Boolean get() = lootDisplay.enabled
}

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
    val packetChatIsolationEnabled: Boolean get() = config.bool("chat.packet-isolation.enabled", false)
    val localChat: LocalChatSettings
        get() = LocalChatSettings(
            enabled = config.bool("chat.local.enabled", true),
            closeDistance = config.double("chat.local.close-distance", 8.0),
            normalDistance = config.double("chat.local.normal-distance", 24.0),
            maximumDistance = config.double("chat.local.maximum-distance", 36.0),
        )
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

    val eventControls: EventControlSettings
        get() = EventControlSettings(
            creatorControlsEnabled = config.bool("event-controls.creator-controls-enabled", true),
            creatorArenaSelectionEnabled = config.bool("event-controls.creator-arena-selection-enabled", true),
            adminOverrideEnabled = config.bool("event-controls.admin-override-enabled", true),
        )

    val gameplay: GameplaySettings
        get() = GameplaySettings(
            spawnReturnSeconds = config.int("gameplay.spawn-return-seconds", 10),
            spawnReturnMovementTolerance = config.double("gameplay.spawn-return-movement-tolerance", 0.05),
            radarDurationSeconds = config.int("gameplay.radar-duration-seconds", 30),
            clearInventory = config.bool("gameplay.clear-inventory", true),
            preparationRations = config.int("gameplay.preparation-rations", 4),
            medkitHealing = config.double("gameplay.medkit-healing", 8.0),
            traitorBladeCost = config.int("gameplay.role-shop.traitor-blade-cost", 2),
            traitorRadarCost = config.int("gameplay.role-shop.traitor-radar-cost", 1),
            traitorSmokeCost = config.int("gameplay.role-shop.traitor-smoke-cost", 1),
            detectiveScannerCost = config.int("gameplay.role-shop.detective-scanner-cost", 1),
            detectiveMedkitCost = config.int("gameplay.role-shop.detective-medkit-cost", 1),
            detectiveArmorCost = config.int("gameplay.role-shop.detective-armor-cost", 1),
            pickupAmmoMagazines = config.int("gameplay.pickup-ammo-magazines", 3),
            pickupAmmoMinimum = config.int("gameplay.pickup-ammo-minimum", 12),
            pickupAmmoMaximum = config.int("gameplay.pickup-ammo-maximum", 48),
            pickupDelayTicks = config.int("gameplay.pickup-delay-ticks", 20),
            headshotMultiplier = config.double("gameplay.headshot-multiplier", 1.5),
            preparingTipSeconds = config.int("gameplay.preparing-tip-seconds", 4),
        )

    val smoke: SmokeSettings
        get() = SmokeSettings(
            throwVelocity = config.double("smoke.throw-velocity", 1.15),
            radius = config.double("smoke.radius", 5.5),
            durationSeconds = config.int("smoke.duration-seconds", 8),
            tickIntervalTicks = config.int("smoke.tick-interval-ticks", 5),
            blindnessRefreshTicks = config.int("smoke.blindness-refresh-ticks", 35),
            darknessRefreshTicks = config.int("smoke.darkness-refresh-ticks", 28),
            smokeParticles = config.int("smoke.smoke-particles", 34),
            ashParticles = config.int("smoke.ash-particles", 18),
        )

    val arenaRuntime: ArenaRuntimeSettings
        get() = ArenaRuntimeSettings(
            viewDistance = config.int("arena-runtime.view-distance", 6),
            simulationDistance = config.int("arena-runtime.simulation-distance", 4),
            preserveImportedDisplays = config.bool("arena-runtime.imported-decorations.preserve-displays", true),
            maxImportedDisplays = config.int("arena-runtime.imported-decorations.max-displays", 256),
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
            lootDisplay = LootDisplaySettings(
                enabled = config.bool("ui.loot-displays", true),
                height = config.double("ui.loot-display.height", 0.18),
                scale = config.double("ui.loot-display.scale", 0.78),
                viewRange = config.double("ui.loot-display.view-range", 0.75),
                animationStepTicks = config.long("ui.loot-display.animation-step-ticks", 5L),
                rotationTicks = config.int("ui.loot-display.rotation-ticks", 40),
                particleIntervalTicks = config.int("ui.loot-display.particle-interval-ticks", 10),
            ),
            dialogsEnabled = config.bool("ui.dialogs-enabled", false),
            nameplates = NameplateSettings(
                enabled = config.bool("ui.nameplates.enabled", true),
                reconcilePeriodTicks = config.long("ui.nameplates.reconcile-period-ticks", 4L),
                maxDistance = config.double("ui.nameplates.max-distance", 32.0),
                lineWidth = config.int("ui.nameplates.line-width", 180),
                viewRange = config.double("ui.nameplates.view-range", 0.5),
                scale = config.double("ui.nameplates.scale", 0.8),
                verticalOffset = config.double("ui.nameplates.vertical-offset", 0.55),
                shadowed = config.bool("ui.nameplates.shadowed", true),
                background = NameplateBackgroundSettings(
                    alpha = config.int("ui.nameplates.background.alpha", 0),
                    red = config.int("ui.nameplates.background.red", 0),
                    green = config.int("ui.nameplates.background.green", 0),
                    blue = config.int("ui.nameplates.background.blue", 0),
                ),
                hideInvisibleTargets = config.bool("ui.nameplates.hide-invisible-targets", true),
                hideSpectatorTargets = config.bool("ui.nameplates.hide-spectator-targets", true),
                requireLineOfSight = config.bool("ui.nameplates.require-line-of-sight", true),
                minimumViewAlignment = config.double("ui.nameplates.minimum-view-alignment", 0.5),
                healthPriority = config.int("ui.nameplates.layers.health-priority", 200),
                summaryPriority = config.int("ui.nameplates.layers.summary-priority", 100),
            ),
            filler = UiItemSettings(
                material = config.string("ui.filler.material", "GRAY_STAINED_GLASS_PANE").uppercase(),
                customModelData = config.int("ui.filler.custom-model-data", 0),
            ),
            back = UiItemSettings(
                material = config.string("ui.back.material", "BLUE_STAINED_GLASS_PANE").uppercase(),
                customModelData = config.int("ui.back.custom-model-data", 11013),
            ),
        )

    val weapons: WeaponSettings
        get() = WeaponSettings(
            enabled = config.bool("weapons.enabled", true),
            dnaSeconds = config.int("weapons.dna-seconds", 90),
            specs = FirearmId.entries.associateWith { id ->
                firearmSpec("weapons.catalog.${id.name.lowercase()}", TttFirearmCatalog.specs.getValue(id))
            },
            visuals = FirearmId.entries.associateWith { id ->
                firearmVisual("weapons.visuals.${id.name.lowercase()}", firearmFallback(id))
            },
            lootEffect = LootEffectSettings(
                enabled = config.bool("weapons.loot-effect.enabled", false),
                material = config.string("weapons.loot-effect.material", "POTION").trim().uppercase(),
                customModelData = FirearmRarity.entries.associateWith { rarity ->
                    config.int("weapons.loot-effect.${rarity.name.lowercase()}-custom-model-data", 0)
                },
                height = config.double("weapons.loot-effect.height", 0.08),
                scale = config.double("weapons.loot-effect.scale", 0.9),
            ),
        )

    val arenas: List<ArenaSettings>
        get() = config.keys("arenas").sorted().map { id -> parseArena(id, "arenas.$id") }
            .ifEmpty { listOf(parseArena("default", "arena")) }

    val defaultArenaId: String get() = config.string("default-arena", "").trim().lowercase()

    /** Legacy convenience for safe relay defaults and older integrations. */
    val arena: ArenaSettings get() = arenas.first()

    fun validated(): ArcEventsConfig = apply {
        require(enabled) { "ArcEvents is disabled in config.yml" }
        BackendServerId.of(serverId)
        BackendServerId.of(hostServer)
        require(defaultLocale in setOf("ru", "en")) { "locale.default must be ru or en" }
        val localChat = localChat
        require(localChat.closeDistance.isFinite() && localChat.closeDistance in 1.0..128.0) {
            "chat.local.close-distance must be between 1 and 128"
        }
        require(localChat.normalDistance.isFinite() && localChat.normalDistance in 1.0..128.0) {
            "chat.local.normal-distance must be between 1 and 128"
        }
        require(localChat.maximumDistance.isFinite() && localChat.maximumDistance in 1.0..128.0) {
            "chat.local.maximum-distance must be between 1 and 128"
        }
        require(localChat.closeDistance < localChat.normalDistance && localChat.normalDistance < localChat.maximumDistance) {
            "chat.local distances must increase from close to normal to maximum"
        }
        require(debug.allowedServerIds.isNotEmpty() && debug.allowedServerIds.all { BackendServerId.parseOrNull(it) != null }) {
            "debug.allowed-server-ids contains an invalid server id"
        }
        val network = network
        require(network.enabled) { "Redis coordination is mandatory for ArcEvents" }
        require(network.allowedOrigins.isNotEmpty() && network.allowedOrigins.all { BackendServerId.parseOrNull(it) != null }) {
            "network.allowed-origins contains an invalid server id"
        }
        require(serverId in network.allowedOrigins && hostServer in network.allowedOrigins) {
            "allowed origins must include this node and the host"
        }
        require(network.queueEntrySeconds in 30..3600)
        require(network.reservationSeconds in 10..300)
        require(network.heartbeatSeconds in 2..60)
        require(network.heartbeatStaleSeconds >= network.heartbeatSeconds * 2)
        val gameplay = gameplay
        require(gameplay.spawnReturnSeconds in 1..60)
        require(gameplay.spawnReturnMovementTolerance.isFinite() && gameplay.spawnReturnMovementTolerance in 0.0..2.0)
        require(gameplay.radarDurationSeconds in 1..300)
        require(gameplay.preparationRations in 0..64)
        require(gameplay.medkitHealing.isFinite() && gameplay.medkitHealing in 0.5..40.0)
        require(listOf(
            gameplay.traitorBladeCost,
            gameplay.traitorRadarCost,
            gameplay.traitorSmokeCost,
            gameplay.detectiveScannerCost,
            gameplay.detectiveMedkitCost,
            gameplay.detectiveArmorCost,
        ).all { it in 0..16 }) { "role shop costs must be between 0 and 16" }
        require(gameplay.pickupAmmoMagazines in 1..16)
        require(gameplay.pickupAmmoMinimum in 1..64)
        require(gameplay.pickupAmmoMaximum in gameplay.pickupAmmoMinimum..64)
        require(gameplay.pickupDelayTicks in 0..200)
        require(gameplay.headshotMultiplier.isFinite() && gameplay.headshotMultiplier in 1.0..5.0)
        require(gameplay.preparingTipSeconds in 1..60)
        val smoke = smoke
        require(smoke.throwVelocity.isFinite() && smoke.throwVelocity in 0.1..4.0)
        require(smoke.radius.isFinite() && smoke.radius in 0.5..16.0)
        require(smoke.durationSeconds in 1..60)
        require(smoke.tickIntervalTicks in 1..40)
        require(smoke.blindnessRefreshTicks in smoke.tickIntervalTicks..200)
        require(smoke.darknessRefreshTicks in smoke.tickIntervalTicks..200)
        require(smoke.smokeParticles in 0..500 && smoke.ashParticles in 0..500)
        require(arenaRuntime.viewDistance in 2..16)
        require(arenaRuntime.simulationDistance in 2..arenaRuntime.viewDistance)
        require(arenaRuntime.maxImportedDisplays in 0..1_024) {
            "arena-runtime.imported-decorations.max-displays must be between 0 and 1024"
        }
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
        require(ui.filler.material.matches(Regex("[A-Z0-9_]{1,64}")))
        require(ui.filler.customModelData >= 0)
        require(ui.back.material.matches(Regex("[A-Z0-9_]{1,64}")))
        require(ui.back.customModelData >= 0)
        val lootDisplay = ui.lootDisplay
        require(lootDisplay.height.isFinite() && lootDisplay.height in -1.0..2.0) {
            "ui.loot-display.height must be between -1 and 2"
        }
        require(lootDisplay.scale.isFinite() && lootDisplay.scale in 0.1..4.0) {
            "ui.loot-display.scale must be between 0.1 and 4"
        }
        require(lootDisplay.viewRange.isFinite() && lootDisplay.viewRange in 0.1..4.0) {
            "ui.loot-display.view-range must be between 0.1 and 4"
        }
        require(lootDisplay.animationStepTicks in 1L..40L) {
            "ui.loot-display.animation-step-ticks must be between 1 and 40"
        }
        require(lootDisplay.rotationTicks in 5..200) {
            "ui.loot-display.rotation-ticks must be between 5 and 200"
        }
        require(lootDisplay.particleIntervalTicks in 1..200) {
            "ui.loot-display.particle-interval-ticks must be between 1 and 200"
        }
        val nameplates = ui.nameplates
        require(nameplates.reconcilePeriodTicks in 1L..20L) {
            "ui.nameplates.reconcile-period-ticks must be between 1 and 20"
        }
        require(nameplates.maxDistance.isFinite() && nameplates.maxDistance in 1.0..128.0) {
            "ui.nameplates.max-distance must be between 1 and 128"
        }
        require(nameplates.lineWidth in 1..1_024) { "ui.nameplates.line-width must be between 1 and 1024" }
        require(nameplates.viewRange.isFinite() && nameplates.viewRange in 0.1..4.0) {
            "ui.nameplates.view-range must be between 0.1 and 4"
        }
        require(nameplates.scale.isFinite() && nameplates.scale in 0.25..2.0) {
            "ui.nameplates.scale must be between 0.25 and 2"
        }
        require(nameplates.verticalOffset.isFinite() && nameplates.verticalOffset in -2.0..4.0) {
            "ui.nameplates.vertical-offset must be between -2 and 4"
        }
        require(nameplates.minimumViewAlignment.isFinite() && nameplates.minimumViewAlignment in -1.0..1.0) {
            "ui.nameplates.minimum-view-alignment must be between -1 and 1"
        }
        require(listOf(
            nameplates.background.alpha,
            nameplates.background.red,
            nameplates.background.green,
            nameplates.background.blue,
        ).all { it in 0..255 }) { "ui.nameplates.background channels must be between 0 and 255" }
        require(nameplates.healthPriority in -10_000..10_000 && nameplates.summaryPriority in -10_000..10_000) {
            "ui.nameplates layer priorities must be between -10000 and 10000"
        }
        require(nameplates.healthPriority != nameplates.summaryPriority) {
            "ui.nameplates layer priorities must be distinct"
        }
        require(weapons.dnaSeconds in 15..300)
        require(weapons.specs.keys == FirearmId.entries.toSet()) { "Every firearm requires a gameplay spec" }
        weapons.specs.forEach { (id, spec) ->
            require(spec.id == id) { "Firearm spec identity mismatch for $id" }
            spec.validated()
        }
        weapons.visuals.values.forEach { visual ->
            require(visual.material.matches(Regex("[A-Z0-9_]{1,64}"))) { "Weapon material is invalid" }
            require(visual.customModelData >= 0) { "Weapon custom-model-data cannot be negative" }
        }
        require(weapons.visuals.keys == FirearmId.entries.toSet()) { "Every firearm requires a visual" }
        require(weapons.lootEffect.material.matches(Regex("[A-Z0-9_]{1,64}"))) { "Loot effect material is invalid" }
        require(weapons.lootEffect.customModelData.keys == FirearmRarity.entries.toSet()) {
            "Every firearm rarity requires a loot effect model"
        }
        require(weapons.lootEffect.customModelData.values.all { it >= 0 }) {
            "Loot effect custom-model-data cannot be negative"
        }
        require(weapons.lootEffect.height.isFinite() && weapons.lootEffect.height in -1.0..2.0)
        require(weapons.lootEffect.scale.isFinite() && weapons.lootEffect.scale in 0.1..4.0)
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
            require(arena.spawns.size <= 1) { "Arena ${arena.id} must use one common player spawn" }
            require(arena.lootSpawns.size <= 128) { "Arena ${arena.id} has too many loot spawns" }
            if (arena.enabled && weapons.enabled && arena.template !in setOf("", "citadel-v1")) {
                require(arena.lootSpawns.size >= ttt.maximumPlayers) {
                    "Imported arena ${arena.id} requires at least ${ttt.maximumPlayers} loot spawns"
                }
                require(arena.weaponCount in FirearmId.entries.size..arena.lootSpawns.size) {
                    "Imported arena ${arena.id} weapon-count must be between ${FirearmId.entries.size} and ${arena.lootSpawns.size}"
                }
            }
        }
        require(defaultArenaId.isEmpty() || arenas.any { it.enabled && it.id == defaultArenaId }) {
            "default-arena must name an enabled arena"
        }
        if (nodeMode == NodeMode.HOST) require(arenas.any(ArenaSettings::enabled)) { "HOST node requires an enabled arena" }
        if (nodeMode == NodeMode.HOST) require(serverId == hostServer) { "HOST node must equal host-server" }
    }

    companion object {
        private val ARENA_ID = Regex("[a-z0-9_-]{1,32}")
        private val SUPPORTED_TEMPLATES = setOf(
            "",
            "citadel-v1",
            "ttt-minecraft-b5-v1",
            "cs2-inferno-v1",
            "cs2-mirage-v1",
            "cs2-nuke-v1",
        )
        private val PROTECTED_WORLDS = setOf("world", "world_nether", "world_the_end", "pvp", "parkour1")

        fun load(dataRoot: Path): ArcEventsConfig {
            mergeMissing(dataRoot)
            return inspect(dataRoot)
        }

        fun mergeMissing(dataRoot: Path): Boolean = Config(dataRoot, "config.yml").mergeMissingFromBundled(
            "config.yml",
            setOf("arena", "arenas", "weapons"),
        )

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

    private fun firearmSpec(path: String, fallback: FirearmSpec): FirearmSpec = FirearmSpec(
        id = fallback.id,
        magazineSize = config.int("$path.magazine-size", fallback.magazineSize),
        roundsPerShot = config.int("$path.rounds-per-shot", fallback.roundsPerShot),
        pellets = config.int("$path.pellets", fallback.pellets),
        damagePerPellet = config.double("$path.damage-per-pellet", fallback.damagePerPellet),
        range = config.double("$path.range", fallback.range),
        spreadDegrees = config.double("$path.spread-degrees", fallback.spreadDegrees),
        cooldownTicks = config.int("$path.cooldown-ticks", fallback.cooldownTicks),
        reloadTicks = config.int("$path.reload-ticks", fallback.reloadTicks),
        rarity = FirearmRarity.valueOf(config.string("$path.rarity", fallback.rarity.name).trim().uppercase()),
        lootWeight = config.int("$path.loot-weight", fallback.lootWeight),
    )

    private fun firearmFallback(id: FirearmId): String = when (id) {
        FirearmId.FLINTLOCK, FirearmId.REVOLVER -> "IRON_HORSE_ARMOR"
        FirearmId.HAND_CANNON -> "BLAZE_ROD"
        FirearmId.DOUBLE_BARREL, FirearmId.VEPR_12 -> "CROSSBOW"
        FirearmId.FIVE_SEVEN -> "GOLDEN_HORSE_ARMOR"
        FirearmId.G36, FirearmId.AEK_971, FirearmId.RPL_20 -> "NETHERITE_HOE"
        FirearmId.M1_GARAND, FirearmId.VSS_VINTOREZ, FirearmId.MCMILLAN -> "NETHERITE_SHOVEL"
    }

    private fun parseArena(id: String, path: String): ArenaSettings {
        val world = config.string("$path.world", "pvp").trim()
        val lobby = parseLocation(world, config.string("$path.lobby", ""))
        val spectator = parseLocation(world, config.string("$path.spectator", ""))
        val minimum = parseBound(world, config.string("$path.minimum", ""))
        val maximum = parseBound(world, config.string("$path.maximum", ""))
        val lootSpawns = config.stringList("$path.loot-spawns", emptyList()).mapNotNull { parseLocation(world, it) }
        return ArenaSettings(
            id = id.trim().lowercase(),
            enabled = config.bool("$path.enabled", false),
            world = world,
            template = config.string("$path.template", "").trim().lowercase(),
            lobby = lobby,
            spectator = spectator,
            bounds = if (minimum != null && maximum != null) EventBounds(minimum, maximum) else null,
            spawns = config.stringList("$path.spawns", emptyList()).mapNotNull { parseLocation(world, it) },
            lootSpawns = lootSpawns,
            weaponCount = config.int("$path.weapon-count", lootSpawns.indices.count { it % 4 != 3 }),
        )
    }
}

/** Defines the immediate, next-operation, and restart-only parts of the live configuration contract. */
object ArcEventsReloadPolicy {
    fun validate(
        current: ArcEventsConfig,
        candidate: ArcEventsConfig,
        matchOrReservationActive: Boolean,
        activeArenaId: String? = null,
    ) {
        require(candidate.enabled == current.enabled) { "enabled requires a restart" }
        require(candidate.serverId == current.serverId) { "server-id requires a restart" }
        require(candidate.nodeMode == current.nodeMode) { "node-mode requires a restart" }
        require(candidate.hostServer == current.hostServer) { "host-server requires a restart" }
        require(candidate.network.enabled == current.network.enabled) { "network.enabled requires a restart" }
        require(candidate.packetChatIsolationEnabled == current.packetChatIsolationEnabled) {
            "chat.packet-isolation.enabled requires a restart"
        }
        require(
            candidate.arenaRuntime.preserveImportedDisplays == current.arenaRuntime.preserveImportedDisplays &&
                candidate.arenaRuntime.maxImportedDisplays == current.arenaRuntime.maxImportedDisplays
        ) { "imported decoration sanitation requires a restart" }
        require(candidate.arenas.map(::reloadIdentity) == current.arenas.map(::reloadIdentity)) {
            "arena ids, worlds, templates, enablement, and bounds require a plugin restart"
        }
        // These values interpret durable entries written by every node. A local
        // empty-queue observation cannot make a distributed live swap atomic, so
        // coordinated restart is the only safe boundary.
        require(candidate.network.allowedOrigins == current.network.allowedOrigins) {
            "network.allowed-origins requires a restart"
        }
        require(candidate.network.reservationSeconds == current.network.reservationSeconds) {
            "network.reservation-seconds requires a restart"
        }
        require(candidate.network.transferOnReservation == current.network.transferOnReservation) {
            "network.transfer-on-reservation requires a restart"
        }
        require(candidate.network.returnToOrigin == current.network.returnToOrigin) {
            "network.return-to-origin requires a restart"
        }
        if (matchOrReservationActive) {
            require(candidate.weapons.enabled == current.weapons.enabled) {
                "weapons.enabled can reload only while idle"
            }
            require(candidate.weapons.specs == current.weapons.specs) {
                "weapons.catalog can reload only while idle"
            }
            val arenaId = requireNotNull(activeArenaId) { "active arena identity is unavailable" }
            val activeCurrent = current.arenas.firstOrNull { it.id == arenaId }
            val activeCandidate = candidate.arenas.firstOrNull { it.id == arenaId }
            require(activeCurrent != null && activeCandidate == activeCurrent) {
                "active arena settings can reload only after the current match"
            }
        }
    }

    private fun reloadIdentity(arena: ArenaSettings): List<Any?> = listOf(
        arena.id,
        arena.world,
        arena.template,
        arena.enabled,
        arena.bounds,
    )
}

object ArcEventsRedisBootstrap {
    fun load(dataRoot: Path, settings: ArcEventsConfig): RedisModuleConfig {
        val redisPath = dataRoot.resolve("modules/redis.yml")
        val existed = Files.isRegularFile(redisPath)
        RedisConfigBootstrap.ensure(dataRoot) {
            if (existed) null else readArcRedisSettings(dataRoot, settings.serverId)
        }
        val redisConfig = ConfigManager.ofModule(dataRoot, RedisModuleConfig.RESOURCE)
        if (redisConfig.bool("inherit-connection-from-arc", false)) {
            val inherited = requireNotNull(readArcRedisSettings(dataRoot, settings.serverId)) {
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

    private fun readArcRedisSettings(dataRoot: Path, serverId: String): RedisConnectionSettingsSnapshot? {
        val arcRoot = dataRoot.parent?.resolve("ARC") ?: return null
        val sourcePath = arcRoot.resolve("modules/redis.yml")
        if (!Files.isRegularFile(sourcePath)) return null
        val source = Config(arcRoot, "modules/redis.yml")
        return RedisConnectionSettingsSnapshot(
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
