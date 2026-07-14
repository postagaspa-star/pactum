package eu.stgm.pactum.figlio.dati

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * Un evento del registro. `ts_device` è solo informativo: il timestamp che fa
 * fede (`ts_server`) lo assegna il server alla ricezione (architettura.md).
 */
@Serializable
data class Evento(
    val tipo: String,
    @SerialName("ts_device") val tsDevice: Long,
    val dettagli: JsonObject = JsonObject(emptyMap()),
)

object TipiEvento {
    /** Fotografia cumulativa dell'uso del giorno (il server deduplica per giorno). */
    const val USO_GIORNALIERO = "uso_giornaliero"

    /** Riavvio del telefono: marca l'azzeramento di elapsedRealtime, NON è una manomissione. */
    const val RIAVVIO = "riavvio"

    /** Cambio manuale di ora o fuso orario. */
    const val MANOMISSIONE = "manomissione"
}

/** Il battito "sono viva" inviato ogni ~15 minuti. */
@Serializable
data class Battito(
    @SerialName("ts_device") val tsDevice: Long,
    @SerialName("versione_app") val versioneApp: String,
    @SerialName("elapsed_realtime") val elapsedRealtime: Long,
)

@Serializable
data class PaccoEventi(val eventi: List<Evento>)
