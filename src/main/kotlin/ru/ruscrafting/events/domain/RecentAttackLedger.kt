package ru.ruscrafting.events.domain

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

data class RecentAttack(
    val matchId: UUID,
    val attackerId: UUID,
    val recordedAtMs: Long,
    val firearmId: FirearmId?,
)

class RecentAttackLedger(
    private val ttlMs: Long = 10_000L,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val attacks = ConcurrentHashMap<UUID, RecentAttack>()

    init {
        require(ttlMs in 1_000L..60_000L)
    }

    fun record(matchId: UUID, victimId: UUID, attackerId: UUID, firearmId: FirearmId? = null) {
        if (victimId == attackerId) return
        attacks[victimId] = RecentAttack(matchId, attackerId, clock(), firearmId)
    }

    fun consume(matchId: UUID, victimId: UUID): RecentAttack? {
        val attack = attacks.remove(victimId) ?: return null
        val age = clock() - attack.recordedAtMs
        return attack.takeIf { it.matchId == matchId && age in 0..ttlMs }
    }

    fun clear() = attacks.clear()
}
