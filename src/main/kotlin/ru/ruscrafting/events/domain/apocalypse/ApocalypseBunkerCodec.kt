package ru.ruscrafting.events.domain.apocalypse

import com.google.gson.Gson
import ru.arc.redis.safety.BoundedJsonCodec
import ru.arc.redis.safety.JsonObjectContract

class ApocalypseBunkerCodec(gson: Gson = Gson()) {
    private val codec = BoundedJsonCodec(
        gson = gson,
        type = ApocalypseBunkerSpawnerState::class.java,
        rootContract = JsonObjectContract(
            allowedFields = setOf("siteId", "formatVersion", "timer", "pulses", "active"),
            fieldContracts = mapOf("active" to ApocalypseStateJson.spawns),
        ),
        bounds = ApocalypseStateJson.bounds,
        validate = { it.validated() },
    )

    fun encode(state: ApocalypseBunkerSpawnerState): String = codec.encode(state)
    fun decode(raw: String): ApocalypseBunkerSpawnerState = codec.decode(raw)
}
