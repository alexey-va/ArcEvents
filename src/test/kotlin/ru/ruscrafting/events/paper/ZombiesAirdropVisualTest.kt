package ru.ruscrafting.events.paper

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import org.joml.Vector3f
import kotlin.math.PI
import kotlin.math.floor

class ZombiesAirdropVisualTest : StringSpec({
    "the aircraft, parachute and barrel clear the authored store at every drop site" {
        for (landingZ in listOf(-17.5f, -16.5f, -15.5f, 7.5f)) {
            val entrance = landingZ > 0
            for (wind in listOf(-1f, 1f)) for (ms in 0L until AirdropFlight.FINISH_MS step 50) {
                val f = AirdropFlight.frame(ms, entrance, wind)
                fun verify(parts: List<AirdropFlight.Part>, x: Float, y: Float, z: Float,
                           scale: Float = 1f, yaw: Float = 0f, spin: Float = 0f) {
                    for (part in parts) {
                        val t = part.transform(scale, yaw, spin)
                        val low = Vector3f(Float.POSITIVE_INFINITY)
                        val high = Vector3f(Float.NEGATIVE_INFINITY)
                        for (i in 0..1) for (j in 0..1) for (k in 0..1) {
                            val v = Vector3f(i.toFloat(), j.toFloat(), k.toFloat()).mul(t.scale)
                            t.leftRotation.transform(v).add(t.translation).add(.5f + x, 65f + y, landingZ + z)
                            low.min(v)
                            high.max(v)
                        }
                        fun cells(a: Float, b: Float) = floor(a + .0001f).toInt()..floor(b - .0001f).toInt()
                        for (bx in cells(low.x, high.x)) for (by in cells(low.y, high.y)) for (bz in cells(low.z, high.z)) {
                            check(ZombiesArenaGenerator.materialAt(bx, by, bz) == null) {
                                "${part.material} crosses $bx,$by,$bz at ${ms}ms for landing Z=$landingZ"
                            }
                        }
                    }
                }
                if (f.planeScale > 0) verify(AirdropFlight.plane, f.planeX, f.planeY, f.planeZ, f.planeScale, spin = ms * .008f)
                if (ms >= AirdropFlight.RELEASE_MS) {
                    verify(listOf(AirdropFlight.barrel), f.barrelX, f.barrelY, f.barrelZ)
                    if (f.canopyScale > 0) verify(AirdropFlight.canopy, f.barrelX, f.barrelY + 1, f.barrelZ,
                        f.canopyScale, yaw = if (entrance) (PI / 2).toFloat() else 0f)
                }
            }
            val handoff = AirdropFlight.frame(AirdropFlight.FINISH_MS, entrance, 1f)
            listOf(handoff.barrelX, handoff.barrelY, handoff.barrelZ, handoff.canopyScale, handoff.planeScale) shouldBe
                List(5) { 0f }
        }
    }
})
