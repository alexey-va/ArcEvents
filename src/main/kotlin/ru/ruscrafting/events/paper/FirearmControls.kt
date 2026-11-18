package ru.ruscrafting.events.paper

import org.bukkit.GameMode
import org.bukkit.entity.Entity
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import ru.ruscrafting.events.domain.FirearmSpec
import java.util.UUID

/** A burst can reserve its next game round only. The held item pays at its muzzle frame. */
internal class FirearmBurst(
    val size: Int,
    val intervalTicks: Int,
    val canStopAt: Long,
    val takeRound: () -> Boolean,
)

/** Shared trigger, item and cooldown ownership for both world guns and match guns. */
internal class FirearmControls(private val shots: FirearmShots, private val clock: () -> Long) {
    private val active = mutableMapOf<UUID, FirearmBurst>()
    private val cooldownUntil = mutableMapOf<UUID, Long>()
    private val automaticReloads = mutableMapOf<UUID, Any>()

    fun cancel(id: UUID) {
        active.remove(id)
        automaticReloads.remove(id)
    }

    fun forget(id: UUID) {
        cancel(id)
        cooldownUntil.remove(id)
    }

    fun clear() {
        active.clear()
        automaticReloads.clear()
        cooldownUntil.clear()
    }

    fun fire(
        player: Player, spec: FirearmSpec, selected: Int?, loaded: Int, aiming: Boolean,
        valid: () -> Boolean, target: (Entity) -> Boolean, headshotMultiplier: Double = 1.0,
        withLoaded: (ItemStack, Int) -> ItemStack, hasReserve: () -> Boolean, reload: () -> Boolean,
        empty: () -> Unit, busy: () -> Unit,
        hit: (Entity, FirearmHit, Double) -> Unit,
    ): Boolean {
        val id = player.uniqueId
        val now = clock()
        val creative = player.gameMode == GameMode.CREATIVE
        // Two input packets can describe the same physical attack. Debounce the stop gesture for one tick.
        active[id]?.let { running ->
            if (now < running.canStopAt) return false
            cancel(id)
            return true
        }
        if (creative) automaticReloads.remove(id)
        if (id in automaticReloads || now < (cooldownUntil[id] ?: Long.MIN_VALUE)) return false
        if (!creative && loaded == 0) {
            if (hasReserve()) return reload()
            cooldownUntil[id] = now + 350L
            empty()
            return false
        }
        val mode = player.gameMode
        val slot = player.inventory.heldItemSlot
        var expected = player.inventory.itemInMainHand.clone()
        var remaining = loaded
        fun stillHeld() = valid() && player.gameMode == mode && player.inventory.heldItemSlot == slot &&
            player.inventory.itemInMainHand == expected
        fun debit(rounds: Int) {
            if (creative) return
            remaining -= rounds
            expected = withLoaded(expected, remaining)
            player.inventory.setItemInMainHand(expected)
        }

        if (selected != null || spec.roundsPerShot > 1) {
            val count = minOf(selected ?: spec.roundsPerShot, if (creative) spec.magazineSize else loaded)
            val perTrigger = firearmRoundPellets(spec).size
            val interval = if (selected == null) 1 else (spec.cooldownTicks + perTrigger - 1) / perTrigger
            val costs = firearmBurstCosts(count, spec.roundsPerShot, perTrigger)
            var muzzle = 0
            lateinit var burst: FirearmBurst
            burst = FirearmBurst(costs.size, interval, now + 50L) {
                if (active[id] !== burst || muzzle >= costs.size || !stillHeld()) false else {
                    debit(costs[muzzle++])
                    cooldownUntil[id] = maxOf(cooldownUntil[id] ?: 0L, clock() + interval * 50L)
                    true
                }
            }
            active[id] = burst
            val accepted = shots.fireBurst(player, spec, aiming, valid, target, headshotMultiplier, burst,
                afterFiring = { launched ->
                    if (active.remove(id, burst) && launched) {
                        cooldownUntil[id] = maxOf(cooldownUntil[id] ?: 0L, clock() + interval * 50L)
                        if (!creative && remaining == 0 && stillHeld() && hasReserve()) reload()
                    }
                }, hit = hit)
            if (!accepted) {
                active.remove(id, burst)
                busy()
                return false
            }
            cooldownUntil[id] = maxOf(cooldownUntil[id] ?: 0L, now + (if (selected == null) spec.cooldownTicks else 1) * 50L)
            return true
        }

        val trigger = if (creative) spec else requireNotNull(firearmTriggerSpec(spec, loaded))
        val auto = if (!creative && loaded == trigger.roundsPerShot && hasReserve()) Any() else null
        if (!shots.fire(player, trigger, aiming, valid, target, headshotMultiplier, afterFiring = { launched ->
                if (auto != null && automaticReloads.remove(id, auto) && launched && stillHeld()) reload()
            }, hit = hit)) {
            busy()
            return false
        }
        debit(trigger.roundsPerShot)
        cooldownUntil[id] = now + spec.cooldownTicks * 50L
        if (auto != null) automaticReloads[id] = auto
        return true
    }
}

/** Operator profiles may spend several units for one muzzle; even a partial final group pays its exact cost. */
internal fun firearmBurstCosts(rounds: Int, roundsPerTrigger: Int, muzzlesPerTrigger: Int): List<Int> = buildList {
    var remaining = rounds
    while (remaining > 0) {
        val group = minOf(remaining, roundsPerTrigger)
        val muzzles = minOf(group, muzzlesPerTrigger)
        repeat(muzzles) { add(group / muzzles + if (it < group % muzzles) 1 else 0) }
        remaining -= group
    }
}
