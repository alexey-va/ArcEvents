package ru.ruscrafting.events.paper

import org.bukkit.entity.Player
import java.util.UUID

/** Resolves the original client protocol when Paper sits behind ViaVersion. */
internal class ClientProtocolResolver(
    private val viaVersionLookup: (UUID) -> Int? = ::viaVersionProtocol,
) {
    fun resolve(player: Player): Int = effectiveClientProtocol(player.protocolVersion, viaVersionLookup(player.uniqueId))
}

internal fun effectiveClientProtocol(paperProtocol: Int, viaVersionProtocol: Int?): Int =
    viaVersionProtocol?.takeIf { it > 0 } ?: paperProtocol

private fun viaVersionProtocol(playerId: UUID): Int? = runCatching {
    val viaClass = Class.forName("com.viaversion.viaversion.api.Via")
    val api = viaClass.getMethod("getAPI").invoke(null)
    val method = api.javaClass.methods.first { candidate ->
        candidate.name == "getPlayerVersion" && candidate.parameterCount == 1 &&
            candidate.parameterTypes[0] == UUID::class.java
    }
    (method.invoke(api, playerId) as Number).toInt().takeIf { it > 0 }
}.getOrNull()
