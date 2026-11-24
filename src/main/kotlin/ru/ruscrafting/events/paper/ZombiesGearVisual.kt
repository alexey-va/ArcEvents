package ru.ruscrafting.events.paper

import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.util.Transformation
import org.bukkit.util.Vector
import org.joml.Quaternionf
import org.joml.Vector3f
import ru.arc.paper.display.PacketBlockDisplay
import ru.arc.paper.display.PaperPacketDisplays
import ru.ruscrafting.events.domain.ZombiesGear
import java.util.UUID

/** Match-private gear anchored at the center of the supporting surface. */
internal class ZombiesGearVisual(displays: PaperPacketDisplays, location: Location, gear: ZombiesGear) : AutoCloseable {
    private val parts = mutableListOf<PacketBlockDisplay>()
    private val turretHead = mutableListOf<PacketBlockDisplay>()
    private val turretBarrel = mutableListOf<PacketBlockDisplay>()
    private var audience = emptySet<UUID>()

    init {
        val anchor = location.clone().also { it.pitch = 0f }
        val pivot = anchor.clone().add(0.0, MUZZLE_HEIGHT, 0.0)

        // Sixteenths relative to the floor for fixed parts and the gun pivot for moving parts. +Z faces forward.
        fun block(material: Material, x: Int, y: Int, z: Int, width: Int, height: Int, depth: Int,
                  roll: Int = 0, mount: MutableList<PacketBlockDisplay>? = null) {
            val size = Vector3f(width.toFloat(), height.toFloat(), depth.toFloat()).div(16f)
            val center = Vector3f(x.toFloat(), y.toFloat(), z.toFloat()).div(16f)
            val rotation = Quaternionf().rotationZ(Math.toRadians(roll.toDouble()).toFloat())
            val corner = rotation.transform(Vector3f(size).mul(-.5f)).add(center)
            val part = displays.spawnBlock(if (mount == null) anchor else pivot, material.createBlockData())
            parts += part
            mount?.add(part)
            part.isVisibleByDefault = false
            part.transformation = Transformation(corner, rotation, size, Quaternionf())
            part.viewRange = .7f
        }

        try {
            when (gear) {
                ZombiesGear.WIRE -> {
                    block(Material.STRIPPED_SPRUCE_LOG, -16, 7, 0, 3, 14, 3)
                    block(Material.STRIPPED_SPRUCE_LOG, 16, 7, 0, 3, 14, 3)
                    block(Material.IRON_CHAIN, -8, 7, -1, 10, 20, 10, 62)
                    block(Material.IRON_CHAIN, -8, 7, 1, 10, 20, 10, -62)
                    block(Material.IRON_CHAIN, 8, 7, -1, 10, 20, 10, 62)
                    block(Material.IRON_CHAIN, 8, 7, 1, 10, 20, 10, -62)
                    block(Material.POLISHED_ANDESITE, -8, 7, 2, 1, 6, 1, -30)
                    block(Material.POLISHED_ANDESITE, -8, 7, 1, 1, 5, 1, 46)
                    block(Material.POLISHED_ANDESITE, 8, 7, 2, 1, 6, 1, 30)
                    block(Material.POLISHED_ANDESITE, 8, 7, 1, 1, 5, 1, -46)
                }
                ZombiesGear.WALL -> {
                    block(Material.POLISHED_ANDESITE, 0, 2, 0, 38, 4, 12)
                    block(Material.STONE_BRICKS, -9, 9, 0, 18, 10, 8)
                    block(Material.CRACKED_STONE_BRICKS, 9, 9, 0, 18, 10, 8)
                    block(Material.SMOOTH_STONE, 0, 15, 0, 37, 2, 9)
                }
                ZombiesGear.TURRET -> {
                    block(Material.DEEPSLATE_TILES, -6, 2, -1, 5, 4, 18)
                    block(Material.DEEPSLATE_TILES, 6, 2, -1, 5, 4, 18)
                    block(Material.GREEN_TERRACOTTA, 0, 4, -1, 15, 4, 15)
                    block(Material.POLISHED_DEEPSLATE, 0, 8, 0, 8, 4, 8)
                    block(Material.GREEN_TERRACOTTA, 0, -1, -1, 12, 6, 12, mount = turretHead)
                    block(Material.CYAN_TERRACOTTA, -2, 3, -2, 6, 2, 6, mount = turretHead)
                    block(Material.POLISHED_ANDESITE, 0, 0, 7, 3, 3, 10, mount = turretBarrel)
                    block(Material.POLISHED_BLACKSTONE, 0, 0, 12, 4, 4, 2, mount = turretBarrel)
                }
                else -> throw IllegalArgumentException("No placed visual for $gear")
            }
        } catch (failure: Exception) {
            close()
            throw failure
        }
    }

    fun faceShot(direction: Vector) {
        val pivot = turretHead.firstOrNull()?.location ?: return
        if (direction.lengthSquared() == 0.0) return
        pivot.direction = direction
        turretBarrel.forEach { it.teleport(pivot) }
        pivot.pitch = 0f
        turretHead.forEach { it.teleport(pivot) }
    }

    fun showTo(viewers: Collection<Player>) {
        if (parts.isEmpty()) return
        val next = viewers.mapTo(mutableSetOf()) { it.uniqueId }
        if (next == audience) return
        val removed = audience - next
        val added = next - audience
        for (part in parts) {
            removed.forEach(part::hideFrom)
            added.forEach(part::showTo)
        }
        audience = next
    }

    override fun close() {
        parts.forEach(PacketBlockDisplay::remove)
        parts.clear()
        turretHead.clear()
        turretBarrel.clear()
        audience = emptySet()
    }

    companion object {
        const val MUZZLE_HEIGHT = .85
    }
}
