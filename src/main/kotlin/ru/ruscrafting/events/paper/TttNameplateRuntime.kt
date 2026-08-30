package ru.ruscrafting.events.paper

import org.bukkit.Color
import org.bukkit.entity.Player
import ru.arc.paper.nameplate.PaperNameplateOptions
import ru.arc.paper.nameplate.PaperPlayerNameplates
import ru.ruscrafting.events.config.ArcEventsLocale
import ru.ruscrafting.events.config.NameplateSettings
import ru.ruscrafting.events.domain.PlayerEventStats
import ru.ruscrafting.events.domain.TttMatch
import java.util.UUID

/** Owns the hot-reloadable Paper renderer and ArcEvents rows as one lifecycle. */
class TttNameplateRuntime(
    private val locale: ArcEventsLocale,
    private val statistics: (UUID) -> PlayerEventStats,
    private val onlinePlayer: (UUID) -> Player?,
    private val currentMatch: () -> TttMatch?,
    private val rendererFactory: (PaperNameplateOptions) -> PaperPlayerNameplates,
) : AutoCloseable {
    private var renderer: PaperPlayerNameplates? = null
    private var rows: TttNameplates? = null
    private var closed = false

    fun reconfigure(settings: NameplateSettings) {
        check(!closed) { "ArcEvents nameplate runtime is closed" }
        if (!settings.enabled) {
            replace(null, null)
            return
        }

        val candidateRenderer = rendererFactory(settings.toPaperOptions())
        val candidateRows = TttNameplates(
            registry = candidateRenderer.registry,
            locale = locale,
            statistics = statistics,
            onlinePlayer = onlinePlayer,
            refresh = candidateRenderer::refreshNow,
            healthPriority = settings.healthPriority,
            summaryPriority = settings.summaryPriority,
        )
        try {
            currentMatch()?.let(candidateRows::update)
        } catch (failure: Throwable) {
            runCatching(candidateRows::close).onFailure(failure::addSuppressed)
            runCatching(candidateRenderer::close).onFailure(failure::addSuppressed)
            throw failure
        }
        replace(candidateRenderer, candidateRows)
    }

    fun update(match: TttMatch) {
        rows?.update(match)
    }

    fun remove(playerId: UUID) {
        rows?.remove(playerId)
    }

    fun clear() {
        rows?.close()
    }

    override fun close() {
        if (closed) return
        closed = true
        replace(null, null)
    }

    private fun replace(
        nextRenderer: PaperPlayerNameplates?,
        nextRows: TttNameplates?,
    ) {
        val previousRows = rows
        val previousRenderer = renderer
        rows = nextRows
        renderer = nextRenderer
        previousRows?.close()
        previousRenderer?.close()
    }
}

internal fun NameplateSettings.toPaperOptions(): PaperNameplateOptions = PaperNameplateOptions(
    reconcilePeriodTicks = reconcilePeriodTicks,
    maxDistance = maxDistance,
    lineWidth = lineWidth,
    viewRange = viewRange.toFloat(),
    scale = scale.toFloat(),
    verticalOffset = verticalOffset.toFloat(),
    shadowed = shadowed,
    backgroundColor = Color.fromARGB(background.alpha, background.red, background.green, background.blue),
    hideInvisibleTargets = hideInvisibleTargets,
    hideSpectatorTargets = hideSpectatorTargets,
    requireLineOfSight = requireLineOfSight,
)
