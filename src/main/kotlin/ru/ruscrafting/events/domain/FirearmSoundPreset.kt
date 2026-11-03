package ru.ruscrafting.events.domain

enum class FirearmSoundPreset {
    ORIGINAL, SHARP, HEAVY, ECHO;

    fun soundKey(firearm: FirearmId): String = when (this) {
        ORIGINAL -> if (firearm == FirearmId.VSS_VINTOREZ) "arc:weapon_vss_fire"
            else "arc:weapon_${firearm.name.lowercase()}_fire"
        else -> "arc:weapon_preset_${name.lowercase()}_fire"
    }
}
