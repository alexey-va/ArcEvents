package ru.ruscrafting.events.paper

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import org.bukkit.Location
import org.bukkit.util.Vector
import org.joml.Quaterniond
import org.joml.Vector3d
import ru.ruscrafting.events.domain.FirearmId
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tan

class FirearmMuzzleGeometryTest : StringSpec({
    "all gun anchors match the independent vendor and scope native transform fixture" {
        val source = javaClass.getResourceAsStream("/firearm-muzzle-source.json")!!.bufferedReader().use {
            JsonParser.parseReader(it).asJsonObject
        }
        source["weapons"].asJsonArray.size() shouldBe FirearmId.entries.size
        for (entry in source["weapons"].asJsonArray) {
            val row = entry.asJsonObject
            val id = FirearmId.valueOf(row["weapon"].asString.uppercase())
            val anchor = row["anchor_model"].asJsonArray.map { it.asDouble }
            for ((name, transform) in row["transforms"].asJsonObject.entrySet()) {
                val left = name.endsWith("left")
                val expected = nativeMuzzle(anchor, transform.asJsonObject, left)
                val actual = firearmMuzzleOffset(id, Vector(0, 0, 1), 0f, name == "aim", left, name == "scoped_hip_left")
                actual.distance(Vector(-expected.x, expected.y, -expected.z)) shouldBe (0.0 plusOrMinus 1e-8)
            }
        }
    }

    "settled server zoom aligns the world muzzle with the unchanged 70 degree hand projection" {
        for (id in FirearmId.entries) {
            val raw = firearmMuzzleOffset(id, Vector(0, 0, 1), 0f, true, false)
            val zoom = firearmScopeZoom(id)
            val projected = firearmMuzzleOffset(id, Vector(0, 0, 1), 0f, true, false, true, zoom)
            projected.z shouldBe raw.z
            for ((before, after) in listOf(raw.x to projected.x, raw.y to projected.y)) {
                after / projected.z / tan(Math.toRadians(35.0 / zoom)) shouldBe
                    (before / raw.z / tan(Math.toRadians(35.0)) plusOrMinus 1e-8)
            }
        }
    }

    "model size and handedness remain in the player camera frame through vertical and diagonal aim" {
        for ((yaw, pitch) in listOf(0f to 0f, 90f to -90f, -133f to 44f, 25f to 90f)) {
            val direction = Location(null, 0.0, 0.0, 0.0, yaw, pitch).direction
            val angle = Math.toRadians(yaw.toDouble())
            val right = Vector(-cos(angle), 0.0, -sin(angle))
            val up = right.clone().crossProduct(direction).normalize()
            for (left in listOf(false, true)) {
                val baseline = firearmMuzzleOffset(FirearmId.VSS_VINTOREZ, Vector(0, 0, 1), 0f, false, left, true)
                val actual = firearmMuzzleOffset(FirearmId.VSS_VINTOREZ, direction, yaw, false, left, true)
                actual.dot(right) shouldBe (-baseline.x plusOrMinus 1e-8)
                actual.dot(up) shouldBe (baseline.y plusOrMinus 1e-8)
                actual.dot(direction) shouldBe (baseline.z plusOrMinus 1e-8)
            }
        }
    }
})

private fun nativeMuzzle(anchor: List<Double>, transform: JsonObject, left: Boolean): Vector3d {
    fun values(key: String, fallback: Double) = transform[key]?.asJsonArray?.map { it.asDouble } ?: List(3) { fallback }
    val scale = values("scale", 1.0)
    val rotation = values("rotation", 0.0).map(Math::toRadians)
    val translation = values("translation", 0.0)
    val sign = if (left) -1 else 1
    return Vector3d(anchor[0] - 8, anchor[1] - 8, anchor[2] - 8)
        .mul(scale[0] / 16, scale[1] / 16, scale[2] / 16)
        .rotate(Quaterniond().rotationXYZ(rotation[0], rotation[1] * sign, rotation[2] * sign))
        .add(translation[0] / 16 * sign + .56 * sign, translation[1] / 16 - .52, translation[2] / 16 - .72)
}
