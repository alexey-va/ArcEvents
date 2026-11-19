package ru.ruscrafting.events.domain

import java.util.UUID

/** Only the owner has a backend route, inventory or persistent player statistics. */
internal class PracticeRoster(val matchId: UUID, val ownerId: UUID, val bots: List<QueuedPlayer>) {
    companion object {
        fun create(matchId: UUID, owner: QueuedPlayer, mode: EventMode, tttPlayers: Int): PracticeRoster {
            val count = when (mode) {
                EventMode.TTT -> tttPlayers - 1
                EventMode.GUN_GAME, EventMode.DISASTERS -> 1
                EventMode.FISHING, EventMode.ZOMBIES -> 0
            }
            val names = listOf("Kedr", "Lis", "Sova", "Volk", "Yasen", "Sever")
            val bots = (0 until count).map { index ->
                val name = "Bot_" + names[index]
                QueuedPlayer(UUID.randomUUID(), if (name.equals(owner.playerName, true)) "Bot_${index + 1}" else name,
                    owner.originServer, owner.joinedAtMs)
            }
            return PracticeRoster(matchId, owner.playerId, bots)
        }
    }
}
