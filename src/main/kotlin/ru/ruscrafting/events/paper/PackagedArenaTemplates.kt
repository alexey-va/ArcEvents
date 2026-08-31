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
            id = "japanese-lobby-v1",
            format = PackagedArenaFormat.SPONGE_V3_SCHEMATIC,
            title = "Japanese Lobby",
            source = "https://www.planetminecraft.com/project/japanese-lobby-6691829/",
            sourceArchiveSha256 = "dc612a7a173869f4893acfbfb0064df74cf12bbea235d90dfdaf17c15ae57dba",
            author = "CedricD0812",
            license = "CC BY 4.0",
            licenseUrl = "https://creativecommons.org/licenses/by/4.0/",
            files = listOf(
                PackagedArenaFile(
                    resource = "arena-templates/japanese-lobby-v1/map.schem",
                    destination = "map.schem",
                    sha256 = "dc612a7a173869f4893acfbfb0064df74cf12bbea235d90dfdaf17c15ae57dba",
                ),
            ),
            width = 69,
            height = 65,
            length = 60,
            cropMinimum = TemplateBlockPoint(0, 0, 0),
            cropMaximum = TemplateBlockPoint(68, 35, 59),
        ),
        PackagedArenaTemplate(
            id = "edged-mansion-v1",
            format = PackagedArenaFormat.MCEDIT_SCHEMATIC,
            title = "Edged Mansion",
            source = "https://www.planetminecraft.com/project/edged-mansion/",
            sourceArchiveSha256 = "b49b0c9ab8717acc94636e9ed029f2e19a2d887593520c16b4248088c78d728b",
            author = "kostahansen",
            license = "CC BY 4.0",
            licenseUrl = "https://creativecommons.org/licenses/by/4.0/",
            files = listOf(
                PackagedArenaFile(
                    resource = "arena-templates/edged-mansion-v1/map.schematic",
                    destination = "map.schematic",
                    sha256 = "b49b0c9ab8717acc94636e9ed029f2e19a2d887593520c16b4248088c78d728b",
                ),
            ),
            width = 38,
            height = 32,
            length = 45,
            cropMinimum = TemplateBlockPoint(3, 0, 1),
            cropMaximum = TemplateBlockPoint(30, 29, 41),
        ),
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
        PackagedArenaTemplate(
            id = "practice-yard-v1",
            format = PackagedArenaFormat.ANVIL_WORLD,
            title = "Practice Map Build",
            source = "https://www.planetminecraft.com/project/pratice-map-build/",
            sourceArchiveSha256 = "d9e69297e8b247277346f441f9c3c7551e4d24982e4372f7e051af667e6f4e46",
            author = "Zyumie",
            license = "CC BY 4.0",
            licenseUrl = "https://creativecommons.org/licenses/by/4.0/",
            files = listOf(
                PackagedArenaFile(
                    resource = "arena-templates/practice-yard-v1/region/r.-1.0.mca",
                    destination = "region/r.-1.0.mca",
                    sha256 = "60f4fc5ef6de4ff8741fd2cbe6d0294a1cd0d2423ad2b2ed9ae2710a8c4a6160",
                ),
            ),
            width = 144,
            height = 50,
            length = 160,
        ),
    ).associateBy(PackagedArenaTemplate::id)

    val ids: Set<String> get() = templates.keys

    fun find(id: String): PackagedArenaTemplate? = templates[id]
}
