package ru.ruscrafting.events.paper.apocalypse

import ru.ruscrafting.events.domain.apocalypse.ApocalypseMarker
import ru.ruscrafting.events.domain.apocalypse.ApocalypseMarkerKind
import ru.ruscrafting.events.domain.apocalypse.ApocalypsePoint
import ru.ruscrafting.events.domain.apocalypse.source.ApocalypseSourceLootDrop

/** Resolve a destination using source data, without loading or stocking a container. */
internal fun nearestApocalypseWeaponCache(
    candidates: Sequence<ApocalypseMarker>,
    from: ApocalypsePoint,
    isClaimed: (ApocalypseMarker) -> Boolean,
    sourceLoot: (ApocalypseMarker) -> List<ApocalypseSourceLootDrop>?,
): ApocalypseMarker? = candidates
    .filterNot(isClaimed)
    .sortedBy { marker ->
        val dx = marker.x.toDouble() - from.x
        val dy = marker.y.toDouble() - from.y
        val dz = marker.z.toDouble() - from.z
        dx * dx + dy * dy + dz * dz
    }
    .firstOrNull { marker ->
        sourceLoot(marker)?.any { it is ApocalypseSourceLootDrop.Item && ApocalypseLootItems.firearmProfile(it.item) != null }
            ?: (marker.kind == ApocalypseMarkerKind.ARMORY || marker.kind == ApocalypseMarkerKind.CORE)
    }
