package ru.ruscrafting.events.paper.apocalypse

/** Let native flight commands retain their own permission checks, scoped to the administrator herself. */
internal fun isApocalypseSelfFlightCommand(admin: Boolean, command: String): Boolean {
    if (!admin) return false
    val words = command.removePrefix("/").trim().lowercase().split(Regex("\\s+"))
    val arguments = when (words.firstOrNull()?.substringAfter(':')) {
        "fly" -> words.drop(1)
        "cmi" -> if (words.getOrNull(1) == "fly") words.drop(2) else return false
        else -> return false
    }
    return arguments.isEmpty() || arguments.size == 1 && arguments[0] in setOf("on", "off", "true", "false")
}
