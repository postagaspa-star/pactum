package eu.stgm.pactum.figlio.valutatore

import android.content.Context
import eu.stgm.pactum.figlio.R
import eu.stgm.pactum.figlio.MainActivity
import eu.stgm.pactum.figlio.bonus.CassettaBonus
import eu.stgm.pactum.figlio.bonus.RegoleBonus
import eu.stgm.pactum.figlio.catalogo.CatalogoApp
import eu.stgm.pactum.figlio.dati.CodaEventi
import eu.stgm.pactum.figlio.dati.Evento
import eu.stgm.pactum.figlio.dati.Impostazioni
import eu.stgm.pactum.figlio.dati.Patto
import eu.stgm.pactum.figlio.dati.PattoLocale
import eu.stgm.pactum.figlio.dati.Regola
import eu.stgm.pactum.figlio.dati.TipiEvento
import eu.stgm.pactum.figlio.dati.TipiRegola
import eu.stgm.pactum.figlio.dati.zonaPatto
import eu.stgm.pactum.figlio.misura.UsageStatsReader
import eu.stgm.pactum.figlio.misura.UsoApp
import eu.stgm.pactum.figlio.notifiche.AvvisiLocali
import eu.stgm.pactum.figlio.permessi.PermessiHelper
import eu.stgm.pactum.figlio.ui.etichettaChiave
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Instant
import java.time.ZoneId

/**
 * La sentinella: confronta l'uso di oggi con la copia locale del patto
 * (PattoLocale, risincronizzata dal worker) e per ogni sforamento nuovo
 * accoda l'evento al registro e alza una notifica gentile al figlio.
 * MAI blocchi (concept.md): registra e ricorda, la conseguenza vive nella
 * conversazione.
 *
 * Dedup: massimo UNO sforamento per regola per giorno (le limite_tempo nel fuso
 * del telefono, le fasce nel giorno di ancoraggio dell'occorrenza), memorizzato
 * in Impostazioni. Il mutex nel companion serializza l'intero corpo: worker e
 * loop del servizio girano nello stesso processo e senza serializzazione due
 * chiamate concorrenti potrebbero superare entrambe il controllo di dedup ed
 * emettere due eventi per lo stesso sforamento (stesso schema di CodaEventi).
 */
class SentinellaPatto(private val context: Context) {

    /** Quello che la sentinella vede adesso: il patto locale e gli sforamenti di oggi. */
    data class Misura(
        val patto: Patto,
        val sforamenti: List<Sforamento>,
        /** Il giorno locale del telefono della misura (YYYY-MM-DD). */
        val giorno: String,
    )

    suspend fun valuta(now: Long = System.currentTimeMillis()) = mutex.withLock {
        val misura = misura(now) ?: return@withLock
        if (misura.sforamenti.isEmpty()) return@withLock

        val impostazioni = Impostazioni(context)
        val coda = CodaEventi(context)
        val regolePerId = misura.patto.regole.associateBy { it.id }
        for (sforamento in misura.sforamenti) {
            // Le limite_tempo dedupano sul giorno (fuso telefono); le fasce sul
            // giorno di ancoraggio dell'occorrenza, così le due parti di una
            // fascia che scavalca la mezzanotte non diventano due sforamenti.
            // Lo stesso giorno viaggia nei dettagli (v2.4): il semaforo lo mette lì.
            val giornoDedup = Valutatore.giornoDelloSforamento(sforamento, misura.giorno)
            if (impostazioni.sforamentoGiaSegnalato(sforamento.regolaId, giornoDedup)) continue
            coda.accoda(
                Evento(
                    tipo = TipiEvento.SFORAMENTO,
                    tsDevice = now,
                    dettagli = Valutatore.dettagliSforamento(sforamento, misura.giorno),
                ),
            )
            // Segnato subito dopo l'accodamento: la coda persiste e riconsegna
            // da sola, quindi l'evento arriverà — ri-valutare non deve duplicarlo.
            impostazioni.registraSforamentoSegnalato(sforamento.regolaId, giornoDedup)
            avvisaGentile(sforamento, regolePerId[sforamento.regolaId])
        }
    }

    /**
     * Gli sforamenti di adesso contro la copia locale del patto, senza toccare
     * registro né notifiche: la usano la sentinella e la chiusura della sera.
     * Null se non c'è niente da valutare (niente permesso, niente patto).
     */
    suspend fun misura(now: Long = System.currentTimeMillis()): Misura? {
        if (!PermessiHelper.haAccessoUso(context)) return null
        val patto = PattoLocale(context).leggi() ?: return null
        // (v3) Solo le regole di QUESTO telefono: una fascia oraria del computer
        // misurata sull'uso del telefono sarebbe uno sforamento falso nel registro.
        val regole = patto.regoleDiQuestoDispositivo()
        if (regole.isEmpty()) return null

        val zona = ZoneId.systemDefault()
        val giorno = Instant.ofEpochMilli(now).atZone(zona).toLocalDate().toString()
        val reader = UsageStatsReader(context)

        // Uso di oggi indicizzato per pacchetto e per categoria: una regola
        // limite_tempo vale su un pacchetto esatto o su una chiave categoria:*
        // (contratto v2.1), e il match dev'essere esatto sull'uno o sull'altra.
        val indice = indiceUso(context, reader.usoDelGiorno(zona = zona, adesso = now))

        // I bonus di "oggi" valgono solo se la copia è di oggi (fuso del patto).
        // Più il bonus appena dato e non ancora confermato dal server (snackbar
        // aperta, rete assente): chi si dà +15 a limite passato non va segnato
        // fuori regola nei secondi in cui il bonus aspetta. Se il server lo
        // rifiuta, il cassetto si svuota e il giro dopo lo sforamento c'è.
        val oggiPatto = Instant.ofEpochMilli(now).atZone(zonaPatto(patto.fuso)).toLocalDate().toString()
        val bonusOggi = RegoleBonus.bonusConSospeso(
            bonusOggi = patto.bonusValidiOggi(now),
            sospeso = CassettaBonus(context).leggi(),
            oggi = oggiPatto,
            residuo = patto.residuoBonusOggi(now),
        )

        val sforamenti = Valutatore.valuta(
            regole = regole,
            bonusOggiPerRegola = bonusOggi,
            usoMinutiEtichetta = indice::minuti,
            usoMinutiIntervallo = { inizio, fine ->
                reader.usoNellIntervallo(inizio, fine)
                    .filterNot { it.pacchetto == context.packageName }
                    .sumOf { it.millisPrimoPiano } / 60_000
            },
            now = now,
            zona = zona,
        )
        return Misura(patto, sforamenti, giorno)
    }

    /** Il promemoria al figlio: tono da patto, non da sirena. */
    private fun avvisaGentile(sforamento: Sforamento, regola: Regola?) {
        val parametri = regola?.parametri
        val titolo: String
        val testo: String
        if (sforamento.tipo == TipiRegola.FASCIA_ORARIA) {
            titolo = context.getString(R.string.notifica_sforamento_fascia_titolo)
            testo = context.getString(
                R.string.notifica_sforamento_fascia_testo,
                sforamento.minutiOltre,
                parametri?.let { testoParametro(it, "dalle") } ?: "?",
                parametri?.let { testoParametro(it, "alle") } ?: "?",
            )
        } else {
            titolo = context.getString(R.string.notifica_sforamento_limite_titolo)
            val nomeApp = parametri?.let { testoParametro(it, "app_o_categoria") }
                ?.let { etichettaChiave(context, it, regola?.nome) } ?: "?"
            testo = context.getString(
                R.string.notifica_sforamento_limite_testo,
                nomeApp,
                sforamento.minutiOltre,
                sforamento.limiteEfficace ?: 0,
            )
        }
        // Si apre su Oggi: lì la regola ha i suoi minuti, la barra e i bonus.
        AvvisiLocali.avvisa(
            context,
            id = AvvisiLocali.idSforamento(sforamento.regolaId),
            titolo = titolo,
            testo = testo,
            destinazione = MainActivity.DEST_OGGI,
        )
    }

    private fun testoParametro(parametri: kotlinx.serialization.json.JsonObject, nome: String): String? =
        (parametri[nome] as? kotlinx.serialization.json.JsonPrimitive)?.content

    companion object {
        private val mutex = Mutex()

        /**
         * L'uso di oggi come lo conta il valutatore, per pacchetto e categoria.
         * Stesso filtro della fotografia che arriva al genitore
         * (CatalogoApp.contaNellUso: fuori la Home, i pezzi di sistema senza
         * icona e le due app Pactum): così lo sforamento, la barra del figlio
         * e la barra del padre contano gli stessi minuti (D3).
         */
        fun indiceUso(context: Context, uso: List<UsoApp>): IndiceUso =
            IndiceUso.daUso(
                uso = uso,
                contaNellUso = { CatalogoApp.contaNellUso(context, it) },
                categoriaDi = { CatalogoApp.categoriaDiPacchetto(context, it) },
            )
    }
}
