package ru.ruscrafting.events.domain

/** Shared stages for Gun Game loadouts, opponents, scoring and the final knife. */
internal object GunGameProgression {
    val firearms: List<FirearmId> = listOf(
        FirearmId.FIVE_SEVEN,
        FirearmId.REVOLVER,
        FirearmId.G36,
        FirearmId.AEK_971,
        FirearmId.RPL_20,
        FirearmId.VEPR_12,
        FirearmId.M1_GARAND,
        FirearmId.VSS_VINTOREZ,
        FirearmId.MCMILLAN,
    )
    val knifeStage: Int = firearms.size

    fun firearmAt(stage: Int): FirearmId? = firearms.getOrNull(stage)
}
