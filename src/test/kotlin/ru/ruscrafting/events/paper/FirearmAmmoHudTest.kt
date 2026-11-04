package ru.ruscrafting.events.paper

import com.google.gson.GsonBuilder
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.TextColor
import net.kyori.adventure.text.format.TextDecoration
import net.kyori.adventure.text.minimessage.MiniMessage
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.FluidCollisionMode
import org.bukkit.GameMode
import org.bukkit.Location
import org.bukkit.World
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.entity.Display
import org.bukkit.entity.Player
import org.bukkit.util.RayTraceResult
import org.bukkit.util.Transformation
import org.bukkit.util.Vector
import ru.arc.paper.display.PacketTextDisplay
import ru.arc.paper.display.PaperPacketDisplays
import ru.ruscrafting.events.config.ArcEventsLocale
import ru.ruscrafting.events.domain.TttFirearmCatalog
import java.io.InputStreamReader
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tan

class FirearmAmmoHudTest : StringSpec({
    "every magazine state has one lit bar per loaded round without burst-like groups" {
        for (language in listOf("ru", "en")) {
            val localized = ammoHudLocale(language)
            val capacities = TttFirearmCatalog.specs.values.map { it.magazineSize }.toSet() + 64
            for (capacity in capacities) for (loaded in 0..capacity) {
                val state = FirearmAmmoHudState(loaded, capacity, 2304)
                val text = firearmAmmoHudText(state, localized)
                val lines = plainAmmoHud(text).lines()
                lines.size shouldBe 4
                lines[0].contains("$loaded / $capacity") shouldBe true
                lines[0].contains(if (language == "ru") "патр." else "rounds") shouldBe true
                lines[1] shouldBe "|".repeat(capacity)
                val bars = text.children().filter { plainAmmoHud(it) == "|" }
                bars.count { it.color() != TextColor.color(0x526170) } shouldBe loaded
                bars.count { it.color() == TextColor.color(0x526170) } shouldBe capacity - loaded
                lines.last().contains("F") shouldBe true
                text.decoration(TextDecoration.ITALIC) shouldBe TextDecoration.State.FALSE
                val bounds = firearmAmmoHudBounds(text)
                bounds.lineCount shouldBe 4
                (bounds.pixelWidth <= 200) shouldBe true
            }
        }
    }

    "low empty reload and Creative preserve truthful counts and discoverable controls" {
        val local = ammoHudLocale("ru")
        val low = firearmAmmoHudText(FirearmAmmoHudState(2, 48), local)
        val lit = low.children().filter { plainAmmoHud(it) == "|" }
        lit.count { it.color() == TextColor.color(0xFFBE6B) } shouldBe 2
        lit.count { it.color() == TextColor.color(0x526170) } shouldBe 46
        val empty = plainAmmoHud(firearmAmmoHudText(FirearmAmmoHudState(0, 48), local))
        empty.startsWith("0 / 48") shouldBe true
        empty.contains("Пусто") shouldBe true
        val reload = plainAmmoHud(firearmAmmoHudText(FirearmAmmoHudState(7, 48, reloading = true), local))
        reload.startsWith("7 / 48") shouldBe true
        reload.contains("ЛКМ") shouldBe true
        reload.contains("ПКМ") shouldBe true
        reload.lines().last().contains("F") shouldBe true
        reload.lines().last().contains("…") shouldBe true
        val creative = plainAmmoHud(firearmAmmoHudText(FirearmAmmoHudState(0, 48, 0, creative = true), local))
        creative.lines().size shouldBe 3
        creative.contains("∞") shouldBe true
        creative.contains("Без расхода") shouldBe true
        creative.contains("|") shouldBe false
        creative.contains("0 / 48") shouldBe false
        creative.contains("Пусто") shouldBe false
        creative.contains("F") shouldBe true
        shouldThrow<IllegalArgumentException> { FirearmAmmoHudState(65, 64) }
        shouldThrow<IllegalArgumentException> { FirearmAmmoHudState(0, 0) }
        shouldThrow<IllegalArgumentException> { FirearmAmmoHudState(0, 65) }
        shouldThrow<IllegalArgumentException> { FirearmAmmoHudState(0, 48, -1) }
        shouldThrow<IllegalArgumentException> { FirearmAmmoHudState(0, 48, zoom = Float.NaN) }
    }

    "shots reuse one owner-only native passenger without movement teleports or unchanged metadata" {
        val f = AmmoHudFixture()
        f.hud.use { hud ->
            hud.tick(listOf(f.player))
            repeat(3) { hud.tick(listOf(f.player)) }
            f.parts.size shouldBe 1
            val part = f.parts.single()
            verify(exactly = 1) { part.isVisibleByDefault = false; part.showTo(f.player); part.attachTo(f.player) }
            verify { part.billboard = Display.Billboard.CENTER; part.teleportDuration = 0 }
            verify(exactly = 1) { part.transformation = any() }
            verify(exactly = 0) { part.text(any()) }
            f.state = f.state!!.copy(loaded = 47)
            hud.refresh(f.player)
            hud.refresh(f.player)
            verify(exactly = 1) { part.text(any()) }
            f.position = Location(f.world, 28.0, 72.0, -32.0, 143f, -67f)
            hud.refresh(f.player)
            f.parts.size shouldBe 1
            verify(exactly = 0) { part.teleport(any()); part.isVisibleByDefault = true; f.player.inventory }
        }
        verify(exactly = 1) { f.parts.single().remove() }
        verify(exactly = 0) { f.displays.close(); f.displays.closeAsync() }
    }

    "no gun death spectator offline world change and explicit teleport cleanup retire the private card" {
        for (exit in listOf<(AmmoHudFixture) -> Unit>(
            { it.state = null }, { it.dead = true }, { it.mode = GameMode.SPECTATOR }, { it.online = false },
        )) {
            val f = AmmoHudFixture()
            f.hud.refresh(f.player)
            exit(f)
            f.hud.tick(listOf(f.player))
            f.hud.close()
            verify(exactly = 1) { f.parts.single().remove() }
        }
        val f = AmmoHudFixture()
        f.hud.refresh(f.player)
        f.world = ammoHudWorld()
        f.hud.refresh(f.player)
        f.parts.size shouldBe 2
        verify(exactly = 1) { f.parts[0].remove() }
        f.hud.clear(f.player.uniqueId) // Same-world teleport listener uses this immediate boundary.
        verify(exactly = 1) { f.parts[1].remove() }
        f.hud.refresh(f.player)
        f.hud.tick(emptyList())
        verify(exactly = 1) { f.parts[2].remove() }
        f.hud.close()
        f.hud.close()
        f.hud.refresh(f.player)
        f.parts.size shouldBe 3
    }

    "CENTER transform separates world-vertical passenger correction from camera-local magazine placement" {
        val bounds = FirearmAmmoHudBounds(184, 4)
        for (yaw in listOf(0f, 43f, 180f)) for (pitch in listOf(-85f, -37f, 0f, 76f))
            for (gap in listOf(.18, .23, .05, .36)) for (zoom in listOf(1f, 1.15f, 1.35f, 3f, 5f)) {
                val eye = Location(null, 13.0, 65.62, -21.0, yaw, pitch)
                val pose = firearmAmmoHudPose(bounds, zoom, gap)
                for (fraction in listOf(1.0, .2)) {
                    val transform = firearmAmmoHudTransformation(pose, bounds, eye, gap, fraction)
                    val forward = eye.direction.normalize()
                    val radians = Math.toRadians(yaw.toDouble())
                    val right = Vector(-cos(radians), 0.0, -sin(radians))
                    val down = forward.clone().crossProduct(right).normalize()
                    val t = transform.translation
                    val fromEye = right.clone().multiply(t.x.toDouble())
                        .subtract(down.clone().multiply(t.y + bounds.originUp * transform.scale.x))
                        .subtract(forward.clone().multiply(t.z.toDouble())).add(Vector(0.0, gap, 0.0))
                    fromEye.dot(right) shouldBe (pose.right * fraction plusOrMinus 1e-6)
                    fromEye.dot(down) shouldBe (pose.down * fraction plusOrMinus 1e-6)
                    fromEye.dot(forward) shouldBe (pose.forward * fraction plusOrMinus 1e-6)
                    (-pose.down - bounds.verticalRadius * pose.scale >= 2.5 * .14 * pose.scale / .28 - 1e-6) shouldBe true
                    pose.right shouldBe 0.0
                    val base = firearmAmmoHudPose(bounds, 1f, gap)
                    val perspective = tan(Math.toRadians(35.0)) / tan(Math.toRadians(35.0 / zoom))
                    (pose.down * perspective) shouldBe (base.down plusOrMinus 1e-6)
                    (pose.scale * perspective) shouldBe (base.scale.toDouble() plusOrMinus 1e-6)
                    (transform.scale.x / fraction).toDouble() shouldBe (pose.scale.toDouble() plusOrMinus 1e-6)
                }
            }
    }

    "HUD lettering is readable at 720p while the largest magazine clears the aim and screen edge" {
        val projection = 672.0 / (2 * tan(Math.toRadians(35.0)))
        for (language in listOf("ru", "en")) for (creative in listOf(false, true)) {
            val text = firearmAmmoHudText(FirearmAmmoHudState(64, 64, 2304, creative), ammoHudLocale(language))
            val bounds = firearmAmmoHudBounds(text)
            val pose = firearmAmmoHudPose(bounds)
            val pixelsPerFontPixel = .025 * pose.scale * projection / pose.forward
            (7 * pixelsPerFontPixel in 14.0..17.0) shouldBe true
            val halfHeight = bounds.verticalRadius * pose.scale
            // At reference FOV the whole card remains inside the image and above the center aim corridor.
            ((-pose.down + halfHeight) / pose.forward < tan(Math.toRadians(35.0))) shouldBe true
            ((-pose.down - halfHeight) / pose.forward > .35) shouldBe true
            (bounds.pixelWidth * pixelsPerFontPixel < 420) shouldBe true
        }
    }

    "wall correction pulls the complete card inward without changing angular size or loading chunks" {
        val f = AmmoHudFixture()
        f.hud.use { hud ->
            hud.refresh(f.player)
            val original = f.transforms.last()
            every { f.world.rayTraceBlocks(any(), any(), any(), FluidCollisionMode.NEVER, true) } answers {
                RayTraceResult(firstArg<Location>().toVector().add(secondArg<Vector>().clone().multiply(.7)))
            }
            hud.refresh(f.player)
            val nearer = f.transforms.last()
            (nearer.scale.x < original.scale.x) shouldBe true
            val gap = f.player.height - f.player.eyeHeight
            val beforeDown = original.translation.y + gap + firearmAmmoHudBounds(f.texts.first()).originUp * original.scale.y
            val afterDown = nearer.translation.y + gap + firearmAmmoHudBounds(f.texts.first()).originUp * nearer.scale.y
            (afterDown / nearer.translation.z) shouldBe (beforeDown / original.translation.z plusOrMinus 1e-6)
            verify { f.parts.single().interpolationDuration = 0 }
            every { f.world.isChunkLoaded(any<Int>(), any<Int>()) } returns false
            hud.refresh(f.player)
            verify(exactly = 1) { f.parts.single().remove() }
            verify(exactly = 0) { f.world.getChunkAt(any<Int>(), any<Int>()) }
        }
    }

    "representative HUD states export production text bounds and transforms for optional visual review" {
        val cases = listOf(
            "full48" to FirearmAmmoHudState(48, 48, 144),
            "low48" to FirearmAmmoHudState(8, 48, 144),
            "empty48" to FirearmAmmoHudState(0, 48, 144),
            "reload48" to FirearmAmmoHudState(7, 48, 144, reloading = true),
            "creative" to FirearmAmmoHudState(0, 48, 0, creative = true),
            "max64" to FirearmAmmoHudState(64, 64, 2304),
            "aim48" to FirearmAmmoHudState(48, 48, 144, zoom = 1.35f),
            "scope5" to FirearmAmmoHudState(5, 5, 20, zoom = 5f),
        )
        val legacy = LegacyComponentSerializer.builder().hexColors().build()
        val samples = listOf("ru", "en").flatMap { language ->
            val local = ammoHudLocale(language)
            cases.map { (name, state) ->
                val text = firearmAmmoHudText(state, local)
                val bounds = firearmAmmoHudBounds(text)
                val pose = firearmAmmoHudPose(bounds, state.zoom)
                val transform = firearmAmmoHudTransformation(pose, bounds, Location(null, 0.0, 1.62, 0.0), .18)
                mapOf("name" to name, "language" to language, "state" to state, "legacy" to legacy.serialize(text),
                    "bounds" to bounds, "pose" to pose,
                    "translation" to listOf(transform.translation.x, transform.translation.y, transform.translation.z))
            }
        }
        samples.size shouldBe 16
        System.getenv("ARC_EVENTS_AMMO_HUD_PREVIEW")?.let { output ->
            val path = Path.of(output)
            path.parent?.let(Files::createDirectories)
            Files.writeString(path, GsonBuilder().setPrettyPrinting().create().toJson(mapOf(
                "evidence" to "Production components and CENTER passenger transforms; offline font/model preview, not a Minecraft capture",
                "assumptions" to "70 degree base vertical FOV with tangent-ratio scope compensation; native FOV smoothing and other client FOV settings are not simulated",
                "samples" to samples,
            )))
        }
    }
})

private fun plainAmmoHud(text: Component) = PlainTextComponentSerializer.plainText().serialize(text)

private fun ammoHudLocale(language: String): (String, Map<String, Component>) -> Component {
    val catalog = requireNotNull(FirearmAmmoHudTest::class.java.getResourceAsStream("/lang/$language.yml"))
        .use { YamlConfiguration.loadConfiguration(InputStreamReader(it)) }
    return { key, values -> MiniMessage.miniMessage().deserialize(requireNotNull(catalog.getString(key)),
        TagResolver.resolver(values.map { (name, value) -> Placeholder.component(name, value) })) }
}

private fun ammoHudWorld() = mockk<World>(relaxed = true).apply {
    every { uid } returns UUID.randomUUID()
    every { isChunkLoaded(any<Int>(), any<Int>()) } returns true
    every { rayTraceBlocks(any(), any(), any(), FluidCollisionMode.NEVER, true) } returns null
}

private class AmmoHudFixture {
    var world = ammoHudWorld()
    var position = Location(world, 0.0, 64.0, 0.0)
    var state: FirearmAmmoHudState? = FirearmAmmoHudState(48, 48, 144)
    var online = true
    var dead = false
    var mode = GameMode.SURVIVAL
    val parts = mutableListOf<PacketTextDisplay>()
    val texts = mutableListOf<Component>()
    val transforms = mutableListOf<Transformation>()
    val player = mockk<Player>(relaxed = true) {
        every { uniqueId } returns UUID.randomUUID()
        every { world } answers { this@AmmoHudFixture.world }
        every { location } answers { position.clone().also { it.world = this@AmmoHudFixture.world } }
        every { eyeLocation } answers { location.add(0.0, 1.62, 0.0) }
        every { eyeHeight } returns 1.62
        every { height } returns 1.8
        every { isOnline } answers { online }
        every { isValid } returns true
        every { isDead } answers { dead }
        every { gameMode } answers { mode }
    }
    val displays = mockk<PaperPacketDisplays>(relaxed = true) {
        every { spawnText(any(), any()) } answers {
            texts += secondArg<Component>()
            mockk<PacketTextDisplay>(relaxed = true) {
                every { isValid } returns true
                every { transformation = any() } answers { transforms += firstArg<Transformation>() }
            }.also(parts::add)
        }
    }
    private val local = ammoHudLocale("ru")
    private val locale = mockk<ArcEventsLocale> {
        every { render(any(), any(), any()) } answers { local(firstArg(), thirdArg()) }
    }
    val hud = FirearmAmmoHud(displays, locale) { state }
}
