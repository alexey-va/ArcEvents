package ru.ruscrafting.events.paper.apocalypse

import net.kyori.adventure.text.event.ClickEvent
import org.bukkit.entity.Player
import org.bukkit.plugin.Plugin
import ru.arc.network.NetworkPlayerName
import ru.ruscrafting.events.config.ArcEventsLocale
import ru.ruscrafting.events.paper.EventWork
import ru.ruscrafting.events.paper.sendEventMessage
import java.util.Locale
import java.util.UUID
import java.util.logging.Level

/** Admits only an authenticated host connection. Remote player names are temporary invite addresses. */
internal class ApocalypseCampaignControl(
    private val plugin: Plugin,
    private val locale: ArcEventsLocale,
    private val access: ApocalypseCampaignAccess?,
    private val work: EventWork,
    private val runtime: () -> ApocalypseRuntime,
    private val openManage: (Player) -> Unit,
    private val openFriends: (Player) -> Unit,
) {
    private val remoteInvites = mutableMapOf<Pair<UUID, String>, Long>()
    private val ready = access?.prepare()?.whenComplete { _, failure ->
        if (failure != null) plugin.logger.log(Level.SEVERE, "Could not load Apocalypse campaign access", failure)
    }

    fun isReady(): Boolean = ready?.let { it.isDone && !it.isCompletedExceptionally } == true

    fun canManage(player: Player): Boolean = isReady() && player.isOnline && player.hasPermission("arcevents.play") &&
        runtime().campaignOwnerOf(player.uniqueId) == player.uniqueId && !runtime().isBusy(player.uniqueId)

    fun resolve(player: Player, destination: ApocalypseDestination): UUID? {
        if (!isReady()) return null
        val requested = destination.ownerName ?: return player.uniqueId
        val owner = playerId(requested) ?: access?.namedCompanionCampaign(player.uniqueId, requested) ?: return null
        val remoteInvite = owner to player.name.lowercase(Locale.ROOT)
        val now = System.currentTimeMillis()
        if (ownerPresent(owner) && remoteInvites[remoteInvite]?.let { it > now } == true &&
            access?.invite(owner, player.uniqueId) == true) remoteInvites.remove(remoteInvite)
        return owner.takeIf { mayEnter(player, owner) }
    }

    fun mayEnter(player: Player, owner: UUID): Boolean = isReady() && player.isOnline && player.hasPermission("arcevents.play") &&
        access?.canEnter(owner, player.uniqueId, ownerPresent(owner)) == true

    fun mayReopen(player: Player, owner: UUID): Boolean = isReady() && player.hasPermission("arcevents.play") &&
        access?.canEnter(owner, player.uniqueId, ownerPresent = false) == true

    fun admitted(player: Player, owner: UUID) { access?.consumeInvite(owner, player.uniqueId) }

    fun friends(player: Player): List<String> {
        val acl = access ?: return emptyList()
        val id = player.uniqueId
        return (acl.pendingInvites(id) + acl.companionCampaigns(id)).mapNotNull { owner ->
            plugin.server.getPlayer(owner)?.name ?: acl.companionOwnerName(owner)
        }.distinct().sorted()
    }

    private fun ownerPresent(owner: UUID): Boolean = plugin.server.getPlayer(owner)?.isOnline == true &&
        runtime().campaignOwnerOf(owner) == owner

    private fun playerId(name: String): UUID? = plugin.server.getPlayerExact(name)?.uniqueId
        ?: plugin.server.getOfflinePlayerIfCached(name)?.uniqueId

    fun command(player: Player, args: List<String>): Boolean {
        if (!args.firstOrNull().equals("apocalypse", true)) return false
        val action = args.getOrNull(1)?.lowercase(Locale.ROOT) ?: return false
        if (action !in ACTIONS) return false
        if (!player.hasPermission("arcevents.play")) {
            player.sendEventMessage(locale.render("command.no-permission", player)); return true
        }
        when (action) {
            "common", "my", "friends", "campaign" -> {
                if (args.size != 2) notice(player, "usage")
                else when (action) {
                    "common" -> runtime().enterDestination(player, null)
                    "my" -> runtime().enterDestination(player, ApocalypseDestination())
                    "friends" -> openFriends(player)
                    else -> if (canManage(player)) openManage(player) else notice(player, "manage-denied")
                }
            }
            else -> {
                val name = args.getOrNull(2)?.takeIf { args.size == 3 }?.let { NetworkPlayerName.parseOrNull(it)?.value }
                if (name == null) notice(player, "usage")
                else if (action == "join") runtime().enterDestination(player, ApocalypseDestination(name))
                else if (!canManage(player)) notice(player, "manage-denied")
                else when (action) {
                    "invite" -> invite(player, name)
                    else -> changeCompanion(player, name, action == "companion")
                }
            }
        }
        return true
    }

    private fun invite(player: Player, name: String) {
        val owner = player.uniqueId
        if (name.equals(player.name, true)) { notice(player, "already-owner"); return }
        val now = System.currentTimeMillis()
        remoteInvites.values.removeIf { it <= now }
        val visitor = playerId(name)
        if (visitor != null) {
            if (visitor in access?.companions(owner).orEmpty()) { notice(player, "already-companion", "player" to name); return }
            if (access?.invite(owner, visitor) != true) { notice(player, "invite-full"); return }
            plugin.server.getPlayer(visitor)?.let { target ->
                target.sendEventMessage(locale.render("apocalypse.campaign-control.invite-received", target,
                    mapOf("owner" to locale.text(player.name))).clickEvent(ClickEvent.runCommand("/events apocalypse join ${player.name}")))
            }
        } else {
            val key = owner to name.lowercase(Locale.ROOT)
            if (key !in remoteInvites && (remoteInvites.size >= 128 || remoteInvites.keys.count { it.first == owner } >= 8)) {
                notice(player, "invite-full"); return
            }
            remoteInvites[key] = now + INVITE_DURATION_MS
        }
        notice(player, "invite-sent", "player" to name, "owner" to player.name)
    }

    private fun changeCompanion(player: Player, name: String, approved: Boolean) {
        val visitor = playerId(name)
        if (visitor == null) { notice(player, "player-unknown"); return }
        if (visitor == player.uniqueId) { notice(player, "already-owner"); return }
        val acl = access ?: return
        acl.setCompanion(player.uniqueId, visitor, approved, ownerName = player.name).whenComplete { _, failure -> work.onMain {
            if (plugin.server.getPlayer(player.uniqueId) !== player) return@onMain
            if (failure != null) {
                plugin.logger.log(Level.WARNING, "Could not change Apocalypse companion owner=${player.uniqueId}", failure)
                notice(player, "access-failed")
            } else notice(player, if (approved) "companion-added" else "companion-removed", "player" to name)
        } }
    }

    private fun notice(player: Player, key: String, vararg values: Pair<String, String>) {
        player.sendEventMessage(locale.render("apocalypse.campaign-control.$key", player,
            values.associate { (name, value) -> name to locale.text(value) }))
    }

    private companion object {
        const val INVITE_DURATION_MS = 5 * 60_000L
        val ACTIONS = setOf("common", "my", "friends", "join", "campaign", "invite", "companion", "revoke")
    }
}
