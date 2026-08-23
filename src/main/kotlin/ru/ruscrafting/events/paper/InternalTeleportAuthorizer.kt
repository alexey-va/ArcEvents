package ru.ruscrafting.events.paper

import org.bukkit.Location
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs

private data class ExpectedEventTeleport(
    val worldId: UUID,
    val x: Double,
    val y: Double,
    val z: Double,
    val yaw: Float,
    val pitch: Float,
) {
    fun matches(location: Location): Boolean = location.world.uid == worldId &&
        abs(location.x - x) <= COORDINATE_EPSILON &&
        abs(location.y - y) <= COORDINATE_EPSILON &&
        abs(location.z - z) <= COORDINATE_EPSILON &&
        abs(location.yaw - yaw) <= ANGLE_EPSILON &&
        abs(location.pitch - pitch) <= ANGLE_EPSILON

    companion object {
        const val COORDINATE_EPSILON = 0.01
        const val ANGLE_EPSILON = 0.1f

        fun from(location: Location) = ExpectedEventTeleport(
            location.world.uid, location.x, location.y, location.z, location.yaw, location.pitch,
        )
    }
}

class InternalTeleportAuthorizer {
    private val expectedByPlayer = ConcurrentHashMap<UUID, ExpectedEventTeleport>()

    fun <T> authorize(playerId: UUID, destination: Location, action: () -> T): T {
        val expected = ExpectedEventTeleport.from(destination)
        check(expectedByPlayer.putIfAbsent(playerId, expected) == null) { "Nested ArcEvents teleport for $playerId" }
        return try {
            action()
        } finally {
            expectedByPlayer.remove(playerId, expected)
        }
    }

    fun isAuthorized(playerId: UUID, destination: Location?): Boolean =
        destination != null && expectedByPlayer[playerId]?.matches(destination) == true
}
