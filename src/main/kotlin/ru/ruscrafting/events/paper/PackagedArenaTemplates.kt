package ru.ruscrafting.events.paper

enum class PackagedArenaFormat {
    SPONGE_V2_SCHEMATIC,
    SPONGE_V3_SCHEMATIC,
    MCEDIT_SCHEMATIC,
    ANVIL_WORLD,
}

enum class PackagedArenaSource {
    CLASSPATH,
    DATA_FOLDER,
}

data class PackagedArenaFile(
    val resource: String,
    val destination: String,
    val sha256: String,
)

data class PackagedArenaTemplate(
    val id: String,
    val format: PackagedArenaFormat,
    val title: String,
    val source: String,
    val sourceArchiveSha256: String,
    val author: String,
    val license: String,
    val licenseUrl: String,
    val files: List<PackagedArenaFile>,
    val width: Int,
    val height: Int,
    val length: Int,
    val cropMinimum: TemplateBlockPoint? = null,
    val cropMaximum: TemplateBlockPoint? = null,
    val assetSource: PackagedArenaSource = PackagedArenaSource.CLASSPATH,
)

data class TemplateBlockPoint(val x: Int, val y: Int, val z: Int)

object PackagedArenaTemplates {
    private val templates = listOf(
        PackagedArenaTemplate(
            id = "ttt-minecraft-b5-v1",
            format = PackagedArenaFormat.SPONGE_V2_SCHEMATIC,
            title = "TTT Minecraft B5",
            source = "https://www.planetminecraft.com/project/ttt_minecraft_b5-minecraft-map/",
            sourceArchiveSha256 = "93f385b9b6916a81dd425cbce3bdfd7c113447cf1192c5e7045c47cb1493ed6e",
            author = "SukovicM (original GMod map by finniespin)",
            license = "Server use only; no redistribution grant",
            licenseUrl = "https://www.planetminecraft.com/project/ttt_minecraft_b5-minecraft-map/",
            files = listOf(
                PackagedArenaFile(
                    resource = "arena-templates/ttt-minecraft-b5-v1/map.schem",
                    destination = "map.schem",
                    sha256 = "b072374dde6c6bbc136fd394e469c1f13d7e66a304b2371944a448bcad1ee289",
                ),
            ),
            width = 128,
            height = 93,
            length = 128,
            cropMinimum = TemplateBlockPoint(0, 0, 0),
            cropMaximum = TemplateBlockPoint(127, 80, 127),
            assetSource = PackagedArenaSource.DATA_FOLDER,
        ),
    ).associateBy(PackagedArenaTemplate::id)

    val ids: Set<String> get() = templates.keys

    fun find(id: String): PackagedArenaTemplate? = templates[id]
}
