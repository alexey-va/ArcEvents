package ru.ruscrafting.events.domain.apocalypse

/** West of the original waterfront's last chunk. Its existing lots and migration receipts stay valid. */
internal object ApocalypseFreightPier {
    const val EAST_X = -129
    private const val END = 40

    fun draw(ground: Int, land: (Int, Int) -> Int, put: (Int, Int, Int, String) -> Unit) {
        // Rise gradually from the end of the original road onto the stone station platform.
        fun deck(u: Int) = ground + (u / 4).coerceIn(0, 4)
        fun block(u: Int, dy: Int, z: Int, state: String) = put(EAST_X - u, deck(u) + dy, z, state)
        for (u in 0..END) {
            val north = maxOf(-10, 3 - u)
            val south = minOf(17, 9 + u)
            for (z in north..south) {
                val y = deck(u)
                val soil = land(EAST_X - u, z)
                val edge = z == north || z == south || u == END
                if (edge || u % 8 == 0 && z % 6 == 0) {
                    for (height in soil + 1 until y) put(EAST_X - u, height, z, "tuff_bricks")
                }
                block(u, -1, z, "stone_bricks")
                block(u, 0, z, when {
                    z in -6..-3 && u >= 12 -> if (u % 3 == 0) "stripped_dark_oak_wood" else "gravel"
                    z == -2 && u >= 12 -> "yellow_terracotta"
                    u < 15 && (z == 3 || z == 9) -> "yellow_terracotta"
                    edge -> "polished_tuff"
                    u >= 14 && z >= 10 -> "bricks"
                    z in 4..8 -> "polished_andesite"
                    else -> "stone_bricks"
                })
                for (height in y + 1..maxOf(y + 6, soil + 10)) put(EAST_X - u, height, z, "air")
                if (deck(u + 1) > y && z in 3..9) block(u, 1, z, "polished_andesite_stairs[facing=west]")
                if (u in 13..36 && z == -5) block(u, 1, z, "rail[shape=east_west]")
            }
        }

        // Amber approach signals frame the first turn without narrowing the seven-wide ramp.
        for ((u, z) in listOf(5 to 1, 11 to 11)) {
            block(u, 1, z, "polished_granite")
            for (dy in 2..4) block(u, dy, z, "polished_tuff_wall[up=true]")
            block(u, 5, z, "ochre_froglight[axis=y]")
            block(u, 6, z, "polished_tuff_slab")
        }
        for (u in listOf(13, 36)) for (z in -6..-4) {
            block(u, 1, z, if (z == -5) "stripped_spruce_log[axis=z]" else "polished_tuff")
        }

        // Three sawtooth roof bays shade the platform. The end bay has lost its middle roof panels.
        for (u in 15..38) for (z in 10..16) {
            val tooth = (u - 15) % 8
            val roof = 6 + tooth / 3
            if (u <= 34 || z !in 12..14) {
                block(u, roof, z, if (z == 10 || z == 16) "polished_tuff" else "cyan_terracotta")
                if (tooth == 7) block(u, roof - 1, z, "light_gray_stained_glass")
            }
            if (tooth == 0 && (z == 10 || z == 16)) {
                for (dy in 1 until roof) block(u, dy, z, if (dy == 1) "polished_granite" else "brick_wall[up=true]")
            }
        }
        for (u in listOf(17, 25, 33)) {
            block(u, 5, 13, "ochre_froglight[axis=y]")
            for (dz in 0..2) block(u, 1, 14 + dz, "spruce_stairs[facing=west]")
        }

        // Four braced crane legs straddle the siding. Its arm crosses the skyline above the gallery.
        for (dy in 1..11) {
            for (du in listOf(-3, 3)) for (dz in listOf(-2, 2))
                block(28 + du, dy, -7 + dz, if (dy == 1) "polished_granite" else "red_terracotta")
            if (dy == 1 || dy == 6 || dy == 11) {
                for (du in -3..3) for (dz in listOf(-2, 2)) block(28 + du, dy, -7 + dz, "polished_tuff")
                for (du in listOf(-3, 3)) for (dz in -2..2) block(28 + du, dy, -7 + dz, "polished_tuff")
            } else {
                val brace = (dy - 2) % 5 - 2
                for (dz in listOf(-2, 2)) block(28 + if (dy < 6) brace else -brace, dy, -7 + dz, "orange_terracotta")
            }
        }
        for (u in 25..30) for (z in -9..-5) for (dy in 11..16) {
            val edge = u == 25 || u == 30 || z == -9 || z == -5
            val state = when {
                dy == 11 || dy == 16 -> "polished_tuff"
                !edge -> if (dy == 13 && u == 28 && z == -7) "ochre_froglight[axis=y]" else "air"
                dy in 13..14 && !((u == 25 || u == 30) && (z == -9 || z == -5)) -> "light_blue_stained_glass"
                else -> "yellow_terracotta"
            }
            block(u, dy, z, state)
        }
        for (z in -12..15) {
            val rise = 17 + (z + 12) / 5
            for (u in 27..29) {
                if (u != 28 || z % 5 == 0) block(u, rise, z, if (z < -8) "polished_tuff" else "red_terracotta")
                if (u == 28) continue
                block(u, rise + 4, z, "polished_granite")
                val brace = Math.floorMod(z + 12, 8).let { if (it <= 4) it else 8 - it }
                if (brace in 1..3) block(u, rise + brace, z, "orange_terracotta")
            }
        }
        block(28, 22, 14, "red_terracotta")
        for (dy in 12..21) block(28, dy, 14, "iron_chain[axis=y]")
        block(28, 11, 14, "polished_tuff")
        block(28, 11, 13, "polished_tuff_slab")
        for (u in 26..30) for (z in -12..-10) for (dy in 15..17) block(u, dy, z, "polished_deepslate")

        // A stranded timber flat wagon gives the empty siding a use and leaves the adjacent platform open.
        for (u in 18..24) for (z in -6..-4) {
            if ((u == 19 || u == 23) && z != -5) block(u, 1, z, "polished_blackstone")
            block(u, 2, z, if (z == -5) "dark_oak_planks" else "polished_tuff")
            if (u == 18 || u == 24) block(u, 3, z, "stripped_spruce_log[axis=z]")
        }

        // Two doors and a side passage let survivors cut through the signal office or circle the platform.
        for (u in 34..39) for (z in 2..8) for (dy in 1..5) {
            val side = z == 2 || z == 8
            val end = u == 34 || u == 39
            if (dy == 5) block(u, dy, z, "deepslate_tiles")
            else if ((end || side) && !(dy <= 3 && (end && z in 5..6 || z == 8 && u == 37))) {
                block(u, dy, z, when {
                    dy == 1 -> "bricks"
                    dy in 2..3 && !end && u != 37 -> "light_gray_stained_glass"
                    else -> "light_gray_terracotta"
                })
            }
        }
        for (u in 33..34) for (z in 1..9) block(u, 5, z, "deepslate_tiles")
        block(36, 4, 6, "ochre_froglight[axis=y]")
    }
}
