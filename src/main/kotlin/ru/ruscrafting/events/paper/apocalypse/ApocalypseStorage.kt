package ru.ruscrafting.events.paper.apocalypse

import com.google.gson.Gson
import ru.arc.paper.playerstate.PaperPlayerStateEnvelope
import ru.arc.paper.playerstate.PaperPlayerStateCodec
import ru.arc.persistence.DurableRecordJournal
import java.nio.file.Path
import java.util.UUID

/** Origin and expedition are separate inventories. RETURNING is replayed before another admission. */
enum class ApocalypsePlayerPhase { ACTIVE, RETURNING, PARKED }

data class ApocalypsePlayerRecord(
    val playerId: String,
    val originServer: String,
    val phase: ApocalypsePlayerPhase,
    val origin: PaperPlayerStateEnvelope?,
    val expedition: PaperPlayerStateEnvelope?,
    val returnPending: Boolean = false,
    val nativeSession: String? = null,
) {
    fun validated() {
        UUID.fromString(playerId)
        nativeSession?.let(UUID::fromString)
        ru.arc.network.BackendServerId.of(originServer)
        require(phase == ApocalypsePlayerPhase.PARKED || origin != null)
        val codec = PaperPlayerStateCodec()
        listOfNotNull(origin, expedition).forEach { require(codec.validateEnvelope(it).toString() == playerId) }
    }
}

/** The existing core journal owns atomic replacement, bounds, fsync and readback. Worker-only. */
class ApocalypseStorage(root: Path) {
    private val gson = Gson()
    private val players = DurableRecordJournal(root, Path.of("data/apocalypse/players"), 8L * 1024 * 1024,
        { value: ApocalypsePlayerRecord -> gson.toJson(value).toByteArray(Charsets.UTF_8) },
        { bytes -> gson.fromJson(bytes.toString(Charsets.UTF_8), ApocalypsePlayerRecord::class.java) },
        ApocalypsePlayerRecord::validated)

    fun loadPlayers(): Map<UUID, ApocalypsePlayerRecord> = players.loadAll().associate {
        UUID.fromString(it.value.playerId) to it.value
    }
    fun save(record: ApocalypsePlayerRecord) = players.commit(record.playerId, record)
    fun load(playerId: UUID): ApocalypsePlayerRecord? = players.loadOrNull(playerId.toString())
}
