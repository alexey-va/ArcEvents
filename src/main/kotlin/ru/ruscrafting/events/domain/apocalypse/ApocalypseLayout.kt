package ru.ruscrafting.events.domain.apocalypse

/** Persisted generation versions share gameplay reads without changing existing world geometry. */
interface ApocalypseLayout {
    val seed: Long
    val starter: ApocalypseSite
    val spawn: ApocalypsePoint
    fun terrainHeight(x: Int, z: Int): Int
    fun sitesNear(x: Int, z: Int): List<ApocalypseSite>
    fun markers(site: ApocalypseSite): List<ApocalypseMarker>
    fun markersInChunk(chunkX: Int, chunkZ: Int): List<ApocalypseMarker>
    fun generate(out: ApocalypseChunkCanvas)
}
