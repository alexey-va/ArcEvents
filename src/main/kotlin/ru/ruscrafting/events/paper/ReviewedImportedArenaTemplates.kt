package ru.ruscrafting.events.paper

internal data class ReviewedImportedArenaTemplate(
    val id: String,
    val source: String,
    val sourceArchiveSha256: String,
)

/** Existing sanitized worlds whose exact provenance is pinned by ArcEvents. */
internal object ReviewedImportedArenaTemplates {
    private val templates = listOf(
        ReviewedImportedArenaTemplate(
            id = "cs2-inferno-v1",
            source = "https://www.planetminecraft.com/project/inferno-cs2/",
            sourceArchiveSha256 = "b4d52213e2539d790b57e66a951467ad23c6be4f58a5c3480626a5d7e1dea81a",
        ),
        ReviewedImportedArenaTemplate(
            id = "cs2-mirage-v1",
            source = "https://www.planetminecraft.com/project/de-mirage-cs-go/",
            sourceArchiveSha256 = "38bcb476c0007ac2d03a7d81c6b6722012f29a1cc00634026e60fc08818f83ef",
        ),
        ReviewedImportedArenaTemplate(
            id = "cs2-nuke-v1",
            source = "https://www.planetminecraft.com/project/nuke-csgo-new/",
            sourceArchiveSha256 = "080c1d6c14e1eaeca3525c031916cdfdac176855f1c44c760f889e6446bb7851",
        ),
    ).associateBy(ReviewedImportedArenaTemplate::id)

    val ids: Set<String> get() = templates.keys

    fun find(id: String): ReviewedImportedArenaTemplate? = templates[id]
}
