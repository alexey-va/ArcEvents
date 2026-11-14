package ru.ruscrafting.events.paper.apocalypse

import com.destroystokyo.paper.profile.ProfileProperty
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer
import org.bukkit.*
import org.bukkit.block.*
import org.bukkit.block.banner.Pattern
import org.bukkit.block.banner.PatternType
import org.bukkit.block.sign.Side
import ru.ruscrafting.events.domain.apocalypse.source.ApocalypseSourceBlockEntity
import java.util.UUID

/** Whitelisted decorative data only: no source commands, loot tables, inventory or entity execution. */
internal fun applyApocalypseSourceTile(world: World, entry: ApocalypseSourceBlockEntity) {
    val block = world.getBlockAt(entry.x, entry.y, entry.z)
    if (block.blockData != Bukkit.createBlockData(entry.state)) return // A player/physics already changed this source cell.
    when (val placed = block.state) {
        is Sign -> {
            for ((name, side) in listOf("front_text" to Side.FRONT, "back_text" to Side.BACK)) {
                val text = entry.data[name] as? Map<*, *>
                val messages = text?.get("messages") as? List<*>
                val surface = placed.getSide(side)
                repeat(4) { line ->
                    val raw = messages?.getOrNull(line) as? String ?: if (side == Side.FRONT) entry.data["Text${line + 1}"] as? String else null
                    if (raw != null) surface.line(line, safeText(GsonComponentSerializer.gson().deserialize(raw)))
                }
                (text?.get("color") as? String)?.let { value -> surface.color = DyeColor.valueOf(value.uppercase()) }
                surface.isGlowingText = (text?.get("has_glowing_text") as? Number)?.toInt() == 1
            }
            placed.isWaxed = (entry.data["is_waxed"] as? Number)?.toInt() == 1
            check(placed.update(false, false))
        }
        is Banner -> {
            val patterns = (entry.data["Patterns"] ?: entry.data["patterns"]) as? List<*> ?: return
            placed.patterns = patterns.mapNotNull { raw ->
                val value = raw as? Map<*, *> ?: return@mapNotNull null
                val color = when (val field = value["Color"] ?: value["color"]) {
                    is Number -> DyeColor.getByWoolData(field.toByte())
                    is String -> DyeColor.valueOf(field.uppercase())
                    else -> null
                } ?: return@mapNotNull null
                val id = (value["Pattern"] ?: value["pattern"]) as? String ?: return@mapNotNull null
                val type = PatternType.getByIdentifier(id) ?: Registry.BANNER_PATTERN.get(NamespacedKey.fromString(id) ?: return@mapNotNull null)
                    ?: return@mapNotNull null
                Pattern(color, type)
            }
            check(placed.update(false, false))
        }
        is Skull -> {
            val owner = (entry.data["SkullOwner"] ?: entry.data["profile"]) as? Map<*, *> ?: return
            val numbers = (owner["Id"] ?: owner["id"]) as? List<*>
            val uuid = numbers?.takeIf { it.size == 4 }?.map { (it as Number).toInt().toLong() and 0xffffffffL }
                ?.let { UUID(it[0] shl 32 or it[1], it[2] shl 32 or it[3]) }
                ?: UUID.nameUUIDFromBytes("${entry.x}:${entry.y}:${entry.z}".toByteArray())
            val profile = Bukkit.createProfileExact(uuid, (owner["Name"] ?: owner["name"]) as? String)
            val properties = owner["Properties"] as? Map<*, *>
            (properties?.get("textures") as? List<*>)?.forEach { raw ->
                val texture = raw as? Map<*, *> ?: return@forEach
                val value = texture["Value"] as? String ?: return@forEach
                profile.setProperty(ProfileProperty("textures", value, texture["Signature"] as? String))
            }
            placed.setPlayerProfile(profile)
            check(placed.update(false, false))
        }
    }
}

private fun safeText(component: Component): Component = component.clickEvent(null)
    .children(component.children().map(::safeText))
