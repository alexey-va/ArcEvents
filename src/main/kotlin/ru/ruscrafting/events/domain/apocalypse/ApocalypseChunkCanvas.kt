package ru.ruscrafting.events.domain.apocalypse

/** Inclusive world-coordinate boxes, clipped before they reach Paper's chunk-local storage. */
abstract class ApocalypseChunkCanvas(val chunkX: Int, val chunkZ: Int, val minY: Int, val maxY: Int) {
    val minX = chunkX * 16
    val minZ = chunkZ * 16
    val maxX = minX + 15
    val maxZ = minZ + 15

    fun intersects(x1: Int, z1: Int, x2: Int, z2: Int) = x2 >= minX && z2 >= minZ && x1 <= maxX && z1 <= maxZ

    fun box(x1: Int, y1: Int, z1: Int, x2: Int, y2: Int, z2: Int, block: ApocalypseBlock) {
        val left = maxOf(x1, minX)
        val bottom = maxOf(y1, minY)
        val north = maxOf(z1, minZ)
        val right = minOf(x2, maxX)
        val top = minOf(y2, maxY)
        val south = minOf(z2, maxZ)
        if (left <= right && bottom <= top && north <= south) {
            fill(left - minX, bottom, north - minZ, right - minX, top, south - minZ, block)
        }
    }

    fun block(x: Int, y: Int, z: Int, block: ApocalypseBlock) = box(x, y, z, x, y, z, block)

    protected abstract fun fill(x1: Int, y1: Int, z1: Int, x2: Int, y2: Int, z2: Int, block: ApocalypseBlock)
}

enum class ApocalypseBlock(private val state: String? = null) {
    AIR, BEDROCK, DEEPSLATE, STONE, TUFF, ANDESITE, GRANITE, CLAY, GRAVEL, SAND, WATER,
    DIRT, GRASS_BLOCK, COARSE_DIRT, PODZOL, MOSS_BLOCK, COBBLESTONE, MOSSY_COBBLESTONE,
    OAK_LOG, SPRUCE_LOG, OAK_LEAVES("oak_leaves[persistent=true,distance=1]"),
    SPRUCE_LEAVES("spruce_leaves[persistent=true,distance=1]"), SHORT_GRASS, FERN, DEAD_BUSH,
    COAL_ORE, IRON_ORE, COPPER_ORE, DEEPSLATE_REDSTONE_ORE, DEEPSLATE_GOLD_ORE, DEEPSLATE_DIAMOND_ORE,
    GRAY_CONCRETE_POWDER, BLACK_CONCRETE, GRAY_CONCRETE, LIGHT_GRAY_CONCRETE, WHITE_CONCRETE,
    YELLOW_CONCRETE, ORANGE_TERRACOTTA, CYAN_TERRACOTTA, RED_TERRACOTTA, BROWN_TERRACOTTA,
    LIGHT_GRAY_TERRACOTTA, TERRACOTTA, BRICKS, MUD_BRICKS, RED_NETHER_BRICKS,
    STONE_BRICKS, MOSSY_STONE_BRICKS, CRACKED_STONE_BRICKS, POLISHED_ANDESITE, SMOOTH_STONE, POLISHED_DEEPSLATE,
    DEEPSLATE_BRICKS, CHISELED_STONE_BRICKS, IRON_BLOCK, CUT_COPPER, EXPOSED_CUT_COPPER,
    WEATHERED_CUT_COPPER, WAXED_OXIDIZED_CUT_COPPER, CHISELED_COPPER, COPPER_GRATE,
    GLASS, GRAY_STAINED_GLASS, LIGHT_BLUE_STAINED_GLASS, ORANGE_STAINED_GLASS, LIGHT_BLUE_STAINED_GLASS_PANE("light_blue_stained_glass_pane[east=true,west=true]"), TINTED_GLASS,
    IRON_BARS, IRON_BARS_EW("iron_bars[east=true,west=true]"), IRON_BARS_NS("iron_bars[north=true,south=true]"),
    YELLOW_TERRACOTTA, SMOOTH_STONE_SLAB, STONE_BRICK_SLAB, POLISHED_ANDESITE_SLAB,
    STAIR_NORTH("polished_andesite_stairs[facing=north]"), STAIR_SOUTH("polished_andesite_stairs[facing=south]"),
    OAK_PLANKS, DARK_OAK_PLANKS, SPRUCE_PLANKS, BOOKSHELF, CRAFTING_TABLE, BLAST_FURNACE, FURNACE,
    BARREL, LEVER("lever[face=floor,facing=north]"), LODESTONE, REDSTONE_LAMP, SEA_LANTERN, OCHRE_FROGLIGHT,
    CHAIN("iron_chain"), COBWEB, MAGMA_BLOCK, SMOKER, CAULDRON, REDSTONE_BLOCK, HAY_BLOCK;

    val blockState = "minecraft:" + (state ?: name.lowercase())
}
