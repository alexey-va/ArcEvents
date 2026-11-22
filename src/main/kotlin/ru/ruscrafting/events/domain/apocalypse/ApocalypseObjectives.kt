package ru.ruscrafting.events.domain.apocalypse

/** Shared by survivors of one expedition. Parts are installed supplies, never inventory items. */
internal data class ApocalypseObjectivesState(
    val relay: Boolean = false,
    val diesel: Boolean = false,
    val portPowered: Boolean = false,
    val radioClues: Int = 0,
    val sirenDay: Long = 0,
) {
    fun validated() = apply {
        require(radioClues in 0..3 && sirenDay >= 0)
        require(!portPowered || relay && diesel)
    }

    fun inspect(point: ApocalypseObjectivePoint): ApocalypseObjectivesState = when (point) {
        ApocalypseObjectivePoint.RELAY -> copy(relay = true)
        ApocalypseObjectivePoint.APARTMENT -> if (radioClues == 0) copy(radioClues = 1) else this
        ApocalypseObjectivePoint.DRAIN -> copy(diesel = true, radioClues = if (radioClues == 1) 2 else radioClues)
        ApocalypseObjectivePoint.RADIO -> if (radioClues == 2) copy(radioClues = 3) else this
        else -> this
    }

    /** Each third actor from each personal wave is drawn to the warehouse. Ambient and bunker actors stay put. */
    fun diverted(token: String): Boolean {
        if (sirenDay == 0L) return false
        val id = token.substringAfter('/', "")
        val prefix = "horde:$sirenDay:"
        if (!id.startsWith(prefix)) return false
        val index = id.substring(prefix.length).toIntOrNull() ?: return false
        return index >= 0 && index % 3 == 2
    }
}

internal enum class ApocalypseObjectivePoint { RELAY, DRAIN, GENERATOR, WINCH, HOME, APARTMENT, RADIO, SIREN }

/** A crew may set the timer days in advance. Once a horde is due, its destination is fixed for that night. */
internal fun apocalypseSirenNight(tick: Long, settings: ApocalypseSurvivalSettings): Long? {
    val day = tick / settings.ticksPerDay
    val interval = settings.hordeEveryDays.toLong()
    if (day > 0 && day % interval == 0L) {
        return day.takeIf { tick % settings.ticksPerDay < settings.hordeStartTime }
    }
    return day - day % interval + interval
}

internal class ApocalypseObjectiveSites(layout: ApocalypseOriginalLayout) {
    private val ground = layout.waterfrontApproach.y - 1
    private val freight = layout.waterfrontLot(-11, 0) != null
    // Original floors use different furniture on different seeds. Prefer the actual map table near the front window.
    private val apartment: ApocalypsePoint
    val mapSupport: String
    init {
        val frontage = layout.waterfrontLot(-7, -1).orEmpty().associate { it.position to it.state }
        apartment = frontage.keys.asSequence()
            .filter { it.y == ground + 7 && it.x in -111..-98 && it.z in -15..-2 }
            .filter { p -> frontage[p] == "minecraft:cartography_table" ||
                frontage[p] == "minecraft:air" && frontage[p.copy(y = p.y - 1)] in MAP_FLOORS }
            .minWithOrNull(compareBy<ApocalypsePoint> { frontage[it] != "minecraft:cartography_table" }
                .thenBy { (it.x + 108) * (it.x + 108) + (it.z + 2) * (it.z + 2) })
            ?: ApocalypsePoint(-109, ground + 1, 0)
        mapSupport = frontage[apartment.copy(y = apartment.y - 1)]?.substringBefore('[') ?: "minecraft:polished_andesite"
    }
    private val homePost = layout.waterfrontLot(-1, 0).orEmpty().asSequence()
        .filter { it.position.x == -8 && it.position.z == 8 && it.state == "minecraft:polished_basalt" }
        .maxBy { it.position.y }.position
    val points = mapOf(
        ApocalypseObjectivePoint.RELAY to ApocalypsePoint(-108, ground + 1, 0),
        ApocalypseObjectivePoint.DRAIN to layout.waterfrontVent.copy(y = layout.waterfrontVent.y - 1),
        ApocalypseObjectivePoint.GENERATOR to if (freight) ApocalypsePoint(-166, ground + 5, 4) else ApocalypsePoint(-123, ground + 1, 10),
        ApocalypseObjectivePoint.WINCH to if (freight) ApocalypsePoint(-160, ground + 5, 8) else ApocalypsePoint(-126, ground + 1, 10),
        ApocalypseObjectivePoint.HOME to homePost.copy(y = homePost.y + 1),
        ApocalypseObjectivePoint.APARTMENT to apartment,
        ApocalypseObjectivePoint.RADIO to ApocalypsePoint(-119, ground + 1, 14),
        ApocalypseObjectivePoint.SIREN to ApocalypsePoint(-88, ground + 1, 15),
    )
    val portLanding = if (freight) ApocalypsePoint(-160, ground + 5, 6) else ApocalypsePoint(-126, ground + 1, 6)
    val homeLanding = homePost.copy(z = 6)
    val beacons = if (freight) listOf(-147, -155, -162).map { ApocalypsePoint(it, ground + 5, -2) }
        else listOf(-119, -110, -102).map { ApocalypsePoint(it, ground + 1, 10) }
    val sirenApron = ApocalypsePoint(-88, ground + 1, 6)

    operator fun get(point: ApocalypseObjectivePoint) = points.getValue(point)

    private companion object {
        val MAP_FLOORS = setOf("minecraft:stone", "minecraft:cracked_stone_bricks", "minecraft:moss_block", "minecraft:mossy_cobblestone")
    }
}
