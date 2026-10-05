package eu.stgm.pactum.figlio.ui

import eu.stgm.pactum.figlio.dati.LetturaTempi
import eu.stgm.pactum.figlio.dati.MedieServer
import eu.stgm.pactum.figlio.dati.Patto
import eu.stgm.pactum.figlio.dati.PeriodoServer
import eu.stgm.pactum.figlio.dati.Regola
import eu.stgm.pactum.figlio.dati.TipiDispositivo
import eu.stgm.pactum.figlio.dati.TipiRegola
import eu.stgm.pactum.figlio.dati.UsoGiornoServer
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import java.time.LocalDate

/**
 * (0.16, contratto v3.8) Il tempo come lo vede il genitore, nell'app del
 * figlio: gli 8 giorni, i totali degli ultimi 7 e 30 giorni, le categorie e le
 * app di ogni giorno, per ogni dispositivo.
 *
 * I giorni passati, i totali e le medie vengono dal server (GET /api/patto);
 * OGGI di questo telefono viene dalla lettura sul telefono, come in Oggi (è più
 * fresca della fotografia che il server ha ricevuto), e nei totali prende il
 * posto del valore di oggi del server. Le medie si mostrano così come le manda
 * il server (non si rifà il conto). Server senza i campi: solo oggi, come prima.
 */

/** Un'app (o un programma) di un giorno, col nome già da leggere. */
data class AppDelGiorno(val chiave: String, val nome: String, val minuti: Int)

/** Una categoria di un giorno; [limite] = il limite base di una regola su quella categoria. */
data class CategoriaDelGiorno(val chiave: String, val minuti: Int, val limite: Int? = null)

/** Un giorno del Tempo. [totaleMinuti] null = nessun dato (non è zero). */
data class GiornoTempo(
    val giorno: String,
    val totaleMinuti: Int?,
    val app: List<AppDelGiorno> = emptyList(),
    val categorie: List<CategoriaDelGiorno> = emptyList(),
    val sessioniMinuti: Int? = null,
)

/**
 * Un periodo (ultimi 7 o 30 giorni): [minuti] = la media del server così
 * com'è, [giorni] = i giorni con dati, [totale] = la somma con oggi preso dal
 * telefono (null = server senza totali: la riga non si mostra).
 */
data class PeriodoTempo(val minuti: Int, val giorni: Int, val totale: Int?)

data class MedieTempo(val settimana: PeriodoTempo?, val mese: PeriodoTempo?) {
    /** Qualcosa da mostrare c'è. */
    val vuote: Boolean get() = settimana == null && mese == null
}

/**
 * I tempi di un dispositivo. [giorni] dal più vecchio a oggi (l'ultimo è
 * oggi); con un server di prima della v3.8 c'è solo oggi ([storico] falso).
 * [medie] null = non si sanno (server vecchio, o copia del patto di un altro
 * giorno: i suoi "ultimi 7 giorni" non sono quelli di oggi).
 */
data class TempiDispositivo(
    val id: Long?,
    val nome: String,
    val tipo: String,
    val questo: Boolean,
    val revocato: Boolean = false,
    val giorni: List<GiornoTempo>,
    val medie: MedieTempo? = null,
    val storico: Boolean = false,
) {
    val computer: Boolean get() = tipo == TipiDispositivo.COMPUTER
}

object TempiFiglio {

    /** Gli 8 giorni del grafico: oggi e i 7 prima. */
    const val GIORNI_GRAFICO = 8

    /** Le due finestre dei totali (v3.8: ultimi 7 e ultimi 30 giorni, oggi compreso). */
    const val GIORNI_SETTIMANA = 7
    const val GIORNI_MESE = 30

    /**
     * I tempi di tutti i dispositivi del figlio da mostrare: prima questo
     * telefono (sempre, anche senza patto: almeno oggi letto qui), poi gli
     * altri nell'ordine del server, solo se il server ne manda i tempi.
     * [oggiLocale] = oggi letto sul telefono (null senza il permesso
     * "Accesso all'utilizzo": allora vale quello del server). [nome] dà il nome
     * da leggere di un'app del server (chiave, nome della fotografia, tipo del
     * dispositivo).
     */
    fun dispositivi(
        patto: Patto?,
        oggi: LocalDate,
        oggiLocale: GiornoTempo?,
        nome: (chiave: String, nomeServer: String?, tipo: String) -> String,
    ): List<TempiDispositivo> {
        val questoId = patto?.dispositivo?.id?.takeIf { it > 0 }
        val tipoQuesto = patto?.dispositivo?.tipo?.takeIf { it.isNotBlank() } ?: TipiDispositivo.TELEFONO
        val uso = LetturaTempi.usoRecente(patto?.usoRecenteGrezzo)
        val questo = TempiDispositivo(
            id = questoId,
            nome = patto?.dispositivo?.nome.orEmpty().trim(),
            tipo = tipoQuesto,
            questo = true,
            giorni = giorni(uso, oggi, oggiLocale) { chiave, nomeServer -> nome(chiave, nomeServer, tipoQuesto) },
            medie = medie(LetturaTempi.medie(patto?.medieGrezze), uso, oggi, oggiLocale?.totaleMinuti),
            storico = uso != null,
        )
        val altri = patto?.dispositivi.orEmpty()
            .distinctBy { it.id }
            .filter { it.id > 0 && it.id != questoId }
            .mapNotNull { d ->
                val suo = LetturaTempi.usoRecente(d.usoRecenteGrezzo) ?: return@mapNotNull null
                val giorni = giorni(suo, oggi, null) { chiave, nomeServer -> nome(chiave, nomeServer, d.tipo) }
                // Uno scollegato resta finché ha giorni con dati: il genitore li vede.
                if (d.revocato && giorni.none { it.totaleMinuti != null }) return@mapNotNull null
                TempiDispositivo(
                    id = d.id,
                    nome = d.nome.trim(),
                    tipo = d.tipo,
                    questo = false,
                    revocato = d.revocato,
                    giorni = giorni,
                    medie = medie(LetturaTempi.medie(d.medieGrezze), suo, oggi, null),
                    storico = true,
                )
            }
        // Server che non dice chi è questo telefono (v2): i tempi in cima sono i suoi.
        return listOf(questo) + if (questoId == null) emptyList() else altri
    }

    /**
     * Gli 8 giorni che finiscono [oggi], presi dal server per data (un giorno
     * che il server non ha = senza dati); oggi dalla lettura del telefono se
     * c'è. [server] null (server vecchio): solo oggi.
     */
    fun giorni(
        server: List<UsoGiornoServer>?,
        oggi: LocalDate,
        oggiLocale: GiornoTempo?,
        nome: (chiave: String, nomeServer: String?) -> String = { chiave, nomeServer -> nomeServer ?: chiave },
    ): List<GiornoTempo> {
        val oggiIso = oggi.toString()
        if (server == null) {
            return listOf(oggiLocale ?: GiornoTempo(oggiIso, null))
        }
        val perGiorno = server.associateBy { it.giorno }
        return (GIORNI_GRAFICO - 1 downTo 0).map { indietro ->
            val giorno = oggi.minusDays(indietro.toLong()).toString()
            if (indietro == 0 && oggiLocale != null) {
                oggiLocale
            } else {
                perGiorno[giorno]?.let { inGiorno(it, nome) } ?: GiornoTempo(giorno, null)
            }
        }
    }

    private fun inGiorno(giorno: UsoGiornoServer, nome: (String, String?) -> String): GiornoTempo =
        GiornoTempo(
            giorno = giorno.giorno,
            totaleMinuti = giorno.totaleMinuti,
            app = giorno.app
                .filter { it.minuti > 0 }
                .map { AppDelGiorno(it.chiave, nome(it.chiave, it.nome), it.minuti) }
                .sortedByDescending { it.minuti },
            categorie = giorno.categorie.map { CategoriaDelGiorno(it.chiave, it.minuti, it.limite) },
            sessioniMinuti = giorno.sessioniMinuti,
        )

    /**
     * I totali e le medie, con oggi preso dal telefono: nel totale il valore di
     * oggi del server lascia il posto a [oggiLocale] (e un oggi che il server
     * non aveva diventa un giorno con dati in più). La media resta quella del
     * server. null se il server non le manda, o se i suoi giorni non finiscono
     * oggi (una copia del patto di ieri: la sua settimana non è questa).
     */
    fun medie(
        server: MedieServer?,
        uso: List<UsoGiornoServer>?,
        oggi: LocalDate,
        oggiLocale: Int?,
    ): MedieTempo? {
        if (server == null || uso == null) return null
        val oggiIso = oggi.toString()
        if (uso.lastOrNull()?.giorno != oggiIso) return null
        val oggiServer = uso.last().totaleMinuti
        return MedieTempo(
            settimana = conOggi(server.settimana, oggiServer, oggiLocale, GIORNI_SETTIMANA),
            mese = conOggi(server.mese, oggiServer, oggiLocale, GIORNI_MESE),
        ).takeIf { !it.vuote }
    }

    /** Un periodo con oggi preso dal telefono (v. [medie]). */
    fun conOggi(periodo: PeriodoServer?, oggiServer: Int?, oggiLocale: Int?, finestra: Int): PeriodoTempo? {
        if (oggiLocale == null) {
            return periodo?.let { PeriodoTempo(it.minuti, it.giorni.coerceAtMost(finestra), it.totale) }
        }
        // Il server non aveva nessun giorno: c'è solo oggi, letto qui.
        if (periodo == null) return PeriodoTempo(minuti = oggiLocale, giorni = 1, totale = oggiLocale)
        val totale = periodo.totale
            ?: return PeriodoTempo(periodo.minuti, periodo.giorni.coerceAtMost(finestra), null)
        val giorni = periodo.giorni + if (oggiServer == null) 1 else 0
        return PeriodoTempo(
            minuti = periodo.minuti,
            giorni = giorni.coerceAtMost(finestra),
            totale = (totale - (oggiServer ?: 0) + oggiLocale).coerceAtLeast(0),
        )
    }

    /**
     * I limiti base delle regole di tempo attive per chiave (app o categoria):
     * a parità di chiave vale la regola più vecchia, come nel server. Servono
     * accanto alle categorie di oggi letto sul telefono.
     */
    fun limitiPerChiave(regole: List<Regola>): Map<String, Int> {
        val limiti = LinkedHashMap<String, Int>()
        regole
            .filter { it.attiva && it.tipo == TipiRegola.LIMITE_TEMPO }
            .sortedBy { it.id }
            .forEach { regola ->
                val chiave = (regola.parametri["app_o_categoria"] as? JsonPrimitive)?.takeIf { it.isString }?.content
                val minuti = (regola.parametri["minuti_al_giorno"] as? JsonPrimitive)?.intOrNull
                if (chiave != null && minuti != null && chiave !in limiti) limiti[chiave] = minuti
            }
        return limiti
    }

    /**
     * Le categorie di oggi dalla lettura del telefono: per categoria la somma
     * dei minuti interi delle sue app (lo stesso conto della fotografia che va
     * al genitore), con il limite base se una regola la riguarda.
     */
    fun categorieDiOggi(
        perApp: List<Pair<String, Long>>,
        categoriaDi: (String) -> String,
        limiti: Map<String, Int>,
    ): List<CategoriaDelGiorno> =
        perApp.groupBy({ categoriaDi(it.first) }, { it.second / 60_000 })
            .map { (categoria, minuti) -> CategoriaDelGiorno(categoria, minuti.sum().toInt(), limiti[categoria]) }
            .filter { it.minuti > 0 }
            .sortedByDescending { it.minuti }
}
