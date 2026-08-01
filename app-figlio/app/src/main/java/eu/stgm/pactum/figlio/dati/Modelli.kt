package eu.stgm.pactum.figlio.dati

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import java.util.UUID

/**
 * Un evento del registro (docs/contratto-api.md). `ts_device` è solo
 * informativo: il timestamp che fa fede (`ts_server`) lo assegna il server
 * alla ricezione (architettura.md).
 *
 * `id` è la chiave di idempotenza del contratto: nasce UNA volta alla
 * creazione dell'evento e viaggia con lui (coda su disco compresa), così i
 * reinvii portano lo stesso id e il server non duplica. @EncodeDefault:
 * senza, un campo col valore di default verrebbe omesso dal JSON
 * (encodeDefaults è false) e l'id rinascerebbe diverso a ogni rilettura.
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class Evento(
    @EncodeDefault(EncodeDefault.Mode.ALWAYS)
    val id: String = UUID.randomUUID().toString(),
    val tipo: String,
    @SerialName("ts_device") val tsDevice: Long,
    @EncodeDefault(EncodeDefault.Mode.ALWAYS)
    val dettagli: JsonObject = JsonObject(emptyMap()),
)

object TipiEvento {
    /** Fotografia cumulativa dell'uso del giorno (il server deduplica per giorno). */
    const val USO_GIORNALIERO = "uso_giornaliero"

    /**
     * (v2.3) Fotografia cumulativa dei SITI del giorno: solo domini e quante
     * volte sono stati chiesti. Mai URL, mai contenuti, mai ricerche
     * (docs/contratto-api.md, "Siti visitati").
     */
    const val SITI_GIORNALIERI = "siti_giornalieri"

    /** Riavvio del telefono: marca l'azzeramento di elapsedRealtime, NON è una manomissione. */
    const val RIAVVIO = "riavvio"

    /** Cambio manuale di ora o fuso orario. */
    const val MANOMISSIONE = "manomissione"

    /** Sforamento rilevato dal valutatore locale (max uno per regola per giorno). */
    const val SFORAMENTO = "sforamento"
}

/** I `sotto_tipo` delle manomissioni che l'app del figlio sa dichiarare. */
object SottoTipiManomissione {
    const val PERMESSO_REVOCATO = "permesso_revocato"
    const val NOTIFICHE_DISATTIVATE = "notifiche_disattivate"

    /** (v2.3) L'osservazione dei siti si è fermata: VPN locale spenta o revocata. */
    const val OSSERVAZIONE_SITI_INTERROTTA = "osservazione_siti_interrotta"
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
