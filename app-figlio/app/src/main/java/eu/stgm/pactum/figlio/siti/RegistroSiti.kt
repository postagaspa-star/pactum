package eu.stgm.pactum.figlio.siti

import android.content.Context
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.time.LocalDate

/**
 * Il conteggio dei domini per giorno, sul telefono del figlio.
 *
 * Sta in memoria (le query DNS arrivano a raffica: un file per richiesta
 * sarebbe una scrittura ogni pochi secondi) e si posa su disco ogni tanto e
 * quando l'osservazione si ferma. Al giro del BattitoWorker la fotografia
 * cumulativa del giorno parte verso il postino (evento `siti_giornalieri`,
 * docs/contratto-api.md).
 *
 * Un solo processo (servizio VPN, worker e UI ci girano dentro insieme):
 * i metodi sono `@Synchronized` sull'oggetto, che basta.
 *
 * `dns_cifrato` è **appiccicoso sul giorno**: se una volta sola nella
 * giornata il DNS cifrato ci ha resi ciechi, il giorno resta dichiarato
 * cieco. Un buco dichiarato vale più di un numero comodo.
 */
object RegistroSiti {

    /** La fotografia di un giorno, come esce verso il registro. */
    class Fotografia(val domini: Map<String, Int>, val dnsCifrato: Boolean)

    private const val NOME_FILE = "siti_giornalieri.json"
    private const val GIORNI_TENUTI = 8L
    private const val PAUSA_SALVATAGGIO_MS = 30_000L

    private class GiornoInMemoria {
        val domini = HashMap<String, Int>()
        var cieco = false
    }

    @Serializable
    private class GiornoSalvato(
        val domini: Map<String, Int> = emptyMap(),
        @SerialName("dns_cifrato") val dnsCifrato: Boolean = false,
    )

    @Serializable
    private class Archivio(val giorni: Map<String, GiornoSalvato> = emptyMap())

    private val json = Json { ignoreUnknownKeys = true }
    private val giorni = HashMap<String, GiornoInMemoria>()
    private var caricato = false
    private var sporco = false
    private var ultimoSalvataggio = 0L

    /**
     * Registra una richiesta DNS. Il nome passa dal filtro ([Domini]): se è
     * rumore tecnico non entra, se è il bootstrap di un resolver cifrato
     * diventa una dichiarazione di cecità invece che una riga.
     */
    @Synchronized
    fun osserva(context: Context, nomeChiesto: String, giorno: String = oggi()) {
        if (Domini.eResolverCifrato(nomeChiesto)) {
            dichiaraCieco(context, giorno)
            return
        }
        val dominio = Domini.dominioOsservabile(nomeChiesto) ?: return
        carica(context)
        val stato = giorni.getOrPut(giorno) { GiornoInMemoria() }
        stato.domini[dominio] = (stato.domini[dominio] ?: 0) + 1
        sporco = true
        forseSalva(context)
    }

    /**
     * "Per un pezzo di questa giornata non ho potuto vedere": DNS cifrato
     * (DoH/DoT) attivo. È un DATO, non un errore (contratto-api.md).
     */
    @Synchronized
    fun dichiaraCieco(context: Context, giorno: String = oggi()) {
        carica(context)
        val stato = giorni.getOrPut(giorno) { GiornoInMemoria() }
        if (stato.cieco) return
        stato.cieco = true
        sporco = true
        salva(context)
    }

    /** La fotografia del giorno, null se di quel giorno non si sa niente. */
    @Synchronized
    fun fotografia(context: Context, giorno: String): Fotografia? {
        carica(context)
        val stato = giorni[giorno] ?: return null
        return Fotografia(HashMap(stato.domini), stato.cieco)
    }

    /** Quanti domini distinti sono stati osservati oggi (per l'app del figlio). */
    @Synchronized
    fun dominiDiOggi(context: Context): Int {
        carica(context)
        return giorni[oggi()]?.domini?.size ?: 0
    }

    /** Posa su disco quello che c'è in memoria (fine osservazione, chiusura). */
    @Synchronized
    fun salvaSubito(context: Context) {
        if (!caricato || !sporco) return
        salva(context)
    }

    private fun oggi(): String = LocalDate.now().toString()

    private fun carica(context: Context) {
        if (caricato) return
        caricato = true
        val file = File(context.applicationContext.filesDir, NOME_FILE)
        if (!file.exists()) return
        val archivio = runCatching {
            json.decodeFromString(Archivio.serializer(), file.readText())
        }.getOrNull() ?: return
        archivio.giorni.forEach { (giorno, salvato) ->
            val stato = GiornoInMemoria()
            stato.domini.putAll(salvato.domini)
            stato.cieco = salvato.dnsCifrato
            giorni[giorno] = stato
        }
    }

    private fun forseSalva(context: Context) {
        val adesso = System.currentTimeMillis()
        if (adesso - ultimoSalvataggio < PAUSA_SALVATAGGIO_MS) return
        salva(context)
    }

    private fun salva(context: Context) {
        potatura()
        val archivio = Archivio(
            giorni.mapValues { (_, stato) ->
                GiornoSalvato(domini = HashMap(stato.domini), dnsCifrato = stato.cieco)
            },
        )
        val cartella = context.applicationContext.filesDir
        val file = File(cartella, NOME_FILE)
        val temporaneo = File(cartella, "$NOME_FILE.tmp")
        val scritto = runCatching {
            temporaneo.writeText(json.encodeToString(Archivio.serializer(), archivio))
            if (!temporaneo.renameTo(file)) {
                file.delete()
                temporaneo.renameTo(file)
            }
        }.isSuccess
        if (scritto) {
            sporco = false
            ultimoSalvataggio = System.currentTimeMillis()
        }
    }

    /** Il registro serve per gli 8 giorni della finestra: il resto si butta. */
    private fun potatura() {
        val soglia = LocalDate.now().minusDays(GIORNI_TENUTI).toString()
        giorni.keys.removeAll { it < soglia }
    }
}
