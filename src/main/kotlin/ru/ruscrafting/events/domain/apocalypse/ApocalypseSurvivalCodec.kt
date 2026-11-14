package ru.ruscrafting.events.domain.apocalypse

import com.google.gson.Gson
import com.google.gson.JsonElement
import ru.arc.redis.safety.BoundedJsonCodec
import ru.arc.redis.safety.JsonArrayContract
import ru.arc.redis.safety.JsonObjectContract
import ru.arc.redis.safety.JsonResourceBounds
import ru.arc.redis.safety.JsonRootContract

/** Gson DTO codecs only; the Paper owner persists their snapshots through the existing durable journal. */
class ApocalypseSurvivalCodec(gson: Gson = Gson()) {
    private val codec = BoundedJsonCodec(
        gson = gson,
        type = ApocalypseSurvivalState::class.java,
        rootContract = JsonObjectContract(
            allowedFields = setOf("formatVersion", "worldTick", "lastHordeDay", "horde", "nextHordeDay"),
            requiredFields = setOf("formatVersion", "worldTick", "lastHordeDay"),
            fieldContracts = mapOf("horde" to ApocalypseStateJson.optional(JsonObjectContract(
                allowedFields = setOf("day", "remaining", "spawned", "nextPulseAt", "active", "startedAt", "endsAt", "pulseAmount", "pulsesRemaining"),
                fieldContracts = mapOf("active" to ApocalypseStateJson.spawns),
            ))),
        ),
        bounds = ApocalypseStateJson.bounds,
        validate = { it.validated() },
    )

    fun encode(state: ApocalypseSurvivalState): String = codec.encode(state)
    fun decode(raw: String): ApocalypseSurvivalState = codec.decode(raw)
}

class ApocalypseInfectionCodec(gson: Gson = Gson()) {
    private val codec = BoundedJsonCodec(
        gson = gson,
        type = ApocalypseInfectionState::class.java,
        rootContract = JsonObjectContract(
            allowedFields = setOf("formatVersion", "worldTick", "stage", "stageStartedAt", "immuneUntil", "curedInfections", "expired"),
            requiredFields = setOf("formatVersion", "worldTick", "stage", "stageStartedAt", "immuneUntil"),
        ),
        bounds = ApocalypseStateJson.bounds,
        validate = { it.validated() },
    )

    fun encode(state: ApocalypseInfectionState): String = codec.encode(state)
    fun decode(raw: String): ApocalypseInfectionState = codec.decode(raw)
}

internal object ApocalypseStateJson {
    val bounds = JsonResourceBounds(maxCharacters = 262_144, maxDepth = 10, maxContainerEntries = 512,
        maxTotalNodes = 8_192, maxStringCharacters = 512)
    val spawns = JsonArrayContract(maxEntries = 256,
        elementContract = JsonObjectContract(setOf("id", "variant", "boss", "sourceEntityId", "profile")))

    fun optional(contract: JsonRootContract): JsonRootContract = object : JsonRootContract {
        override fun validate(value: JsonElement) { if (!value.isJsonNull) contract.validate(value) }
    }
}
