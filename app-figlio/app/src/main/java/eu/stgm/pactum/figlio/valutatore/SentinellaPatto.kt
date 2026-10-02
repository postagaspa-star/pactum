package eu.stgm.pactum.figlio.valutatore

import android.content.Context
import eu.stgm.pactum.figlio.MainActivity
import eu.stgm.pactum.figlio.R
import eu.stgm.pactum.figlio.avviso.Avviso
import eu.stgm.pactum.figlio.avviso.AvvisoActivity
import eu.stgm.pactum.figlio.avviso.Chiamata
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
import eu.stgm.pactum.figlio.misura.FotografiaUso
import eu.stgm.pactum.figlio.misura.LetturaGiorno
import eu.stgm.pactum.figlio.misura.UsageStatsReader
import eu.stgm.pactum.figlio.misura.UsoContato
import eu.stgm.pactum.figlio.notifiche.AvvisiLocali
import eu.stgm.pactum.figlio.permessi.PermessiHelper
import eu.stgm.pactum.figlio.rete.PostinoClient
import eu.stgm.pactum.figlio.sessione.StatoSessione
import eu.stgm.pactum.figlio.sync.ConsegnaEventi
import eu.stgm.pactum.figlio.ui.etichettaChiave
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * La sentinella: confronta l'uso di oggi con la copia locale del patto
 * (PattoLocale, risincronizzata dal worker) e per ogni sforamento nuovo
 * accoda l'evento al registro e alza una notifica gentile al figlio; (0.9) se
 * può, apre anche l'avviso a tutto schermo, e la coda parte subito verso il
 * server. MAI blocchi (concept.md): registra e ricorda, la conseguenza vive
 * nella conversazione.
 *
 * Dedup: massimo UNO sforamento per regola per giorno (le limite_tempo nel fuso
 * del telefono, le fasce nel giorno di ancoraggio dell'occorrenza), memorizzato
 * in Impostazioni (Segnalazioni). Il mutex nel companion serializza l'intero
 * corpo: worker e loop del servizio girano nello stesso processo e senza
 * serializzazione due chiamate concorrenti potrebbero superare entrambe il
 * controllo di dedup ed emettere due eventi per lo stesso sforamento (stesso
 * schema di CodaEventi).
 *
 * (0.12) Nel giro veloce del servizio, a schermo acceso, anche i preavvisi
 * "il tempo sta per finire" (Preavvisi): a 5 minuti e a 1 minuto dal limite,
 * una volta per soglia, con la stessa lettura dell'uso.
 */
class SentinellaPatto(private val context: Context) {

    /** Quello che la sentinella vede adesso: il patto locale e gli sforamenti di oggi. */
    data class Misura(
        val patto: Patto,
        val sforamenti: List<Sforamento>,
        /** Il giorno locale del telefono della misura (YYYY-MM-DD). */
        val giorno: String,
    )

    /**
     * L'uso del giorno di una valutazione, letto UNA volta (0.9): da qui escono
     * i minuti per le regole, il totale, l'uso nelle fasce e la fotografia.
     */
    private class Lettura(
        val giorno: LocalDate,
        val zona: ZoneId,
        val giornata: LetturaGiorno,
        val uso: UsoContato,
        val filtro: (String) -> Boolean,
    )

    /** Gli sforamenti appena segnalati e la fotografia del giorno che va con loro. */
    private class Segnalati(val nuovi: List<Sforamento>, val fotografia: Evento)

    /** (0.12) Un giro: gli sforamenti appena segnalati, e fra quanti ms la prossima soglia dei preavvisi. */
    private class Giro(val segnalati: Segnalati?, val prossimaSoglia: Long?)

    /**
     * Valuta e restituisce gli sforamenti NUOVI (vuota se niente di nuovo).
     * Appena ce n'è uno, la coda parte subito verso il server (ConsegnaEventi),
     * fuori dal mutex: una rete lenta non deve far aspettare le altre
     * valutazioni. Senza rete resta in coda: riprovano il giro veloce e il worker.
     *
     * [giornata] = l'uso già letto dal chiamante (il worker, per la fotografia):
     * niente seconda lettura degli eventi. [giornoPassato] = si guarda l'ultimo
     * istante di un giorno già finito ([now]), l'ultimo minuto prima di
     * mezzanotte. [rileggiPatto] = prima di registrare uno sforamento nuovo si
     * rilegge il patto dal server (il worker l'ha appena letto: non serve).
     */
    suspend fun valuta(
        giornata: LetturaGiorno? = null,
        now: Long = giornata?.fine ?: System.currentTimeMillis(),
        giornoPassato: Boolean = false,
        rileggiPatto: Boolean = !giornoPassato,
    ): List<Sforamento> {
        val giro = mutex.withLock { segnalaNuovi(giornata, now, giornoPassato, rileggiPatto, preavvisi = false) }
        val segnalati = giro.segnalati ?: return emptyList()
        consegna(segnalati)
        return segnalati.nuovi
    }

    /**
     * (0.12) Il giro veloce del servizio: come [valuta], più (con [preavvisi],
     * cioè a schermo acceso) i preavvisi "il tempo sta per finire".
     * Restituisce fra quanti ms l'app in primo piano, se resta lì, porta una
     * regola alla prossima soglia (null = nessuna in vista): il servizio
     * guarda di nuovo appena dopo, così il preavviso non arriva in ritardo.
     */
    suspend fun valutaConPreavvisi(now: Long = System.currentTimeMillis(), preavvisi: Boolean = true): Long? {
        val giro = mutex.withLock { segnalaNuovi(null, now, giornoPassato = false, rileggiPatto = true, preavvisi = preavvisi) }
        giro.segnalati?.let { consegna(it) }
        return giro.prossimaSoglia
    }

    /** Lo sforamento appena registrato parte subito verso il server, fuori dal mutex. */
    private suspend fun consegna(segnalati: Segnalati) {
        try {
            ConsegnaEventi.subito(context, segnalati.fotografia)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // resta in coda: riprova il giro veloce, poi il worker
        }
    }

    private suspend fun segnalaNuovi(
        giornata: LetturaGiorno?,
        now: Long,
        giornoPassato: Boolean,
        rileggiPatto: Boolean,
        preavvisi: Boolean,
    ): Giro {
        if (!PermessiHelper.haAccessoUso(context)) return NIENTE
        var patto = PattoLocale(context).leggi() ?: return NIENTE
        // (0.9) Su questo telefono niente limiti a tempo né fasce (solo vita
        // reale, o regole di altri dispositivi): niente da guardare, e niente
        // lettura degli eventi.
        if (!Segnalazioni.daGuardare(patto.regoleDiQuestoDispositivo())) return NIENTE
        val lettura = leggi(now, giornata)
        val giorno = lettura.giorno.toString()
        // (0.12) Prima dei limiti superati, quelli che stanno per esserlo. Un
        // errore qui non salta mai gli sforamenti dello stesso giro.
        val prossimaSoglia = if (preavvisi && !giornoPassato) {
            try {
                preavvisa(patto, lettura, now, giorno)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
        } else {
            null
        }
        // Un giorno già finito: i limiti solo se la copia ne sa i bonus
        // (altrimenti uno sforamento potrebbe essere falso); le fasce non hanno bonus.
        val soloFasce = giornoPassato && !patto.bonusNoti(now)
        val impostazioni = Impostazioni(context)
        val giaSegnalati = impostazioni.leggiSforamentiSegnalati()
        var candidati = Segnalazioni.nuovi(valutaContro(patto, lettura, now, soloFasce), giorno, giaSegnalati)
        if (candidati.isEmpty()) return Giro(null, prossimaSoglia)

        // (0.9) Prima di registrare uno sforamento nuovo, il patto fresco dal
        // server, se c'è rete: una copia rimasta indietro (un bonus appena
        // concesso con la rilettura fallita, una proposta accettata da un altro
        // dispositivo) darebbe uno sforamento falso. Si rivaluta sulla stessa
        // lettura dell'uso. Senza rete si resta sulla copia, come prima.
        if (rileggiPatto) {
            rileggi()?.let { fresco ->
                patto = fresco
                candidati = Segnalazioni.nuovi(valutaContro(fresco, lettura, now, soloFasce), giorno, giaSegnalati)
            }
            if (candidati.isEmpty()) return Giro(null, prossimaSoglia)
        }

        val decisione = Segnalazioni.decidi(
            sforamenti = candidati,
            giornoTelefono = giorno,
            giaSegnalati = giaSegnalati,
            mostraSopra = PermessiHelper.puoMostrareSopra(context),
            inChiamata = Chiamata.inCorso(context),
            giornoPassato = giornoPassato,
        )
        val tuttoSchermo = decisione.aTuttoSchermo.isNotEmpty()
        val dispositivo = impostazioni.leggiIdentita().dispositivo?.id?.takeIf { it > 0 }?.toString()
            ?: impostazioni.leggiConfigurazione().impronta
        val regolePerId = patto.regole.associateBy { it.id }
        val coda = CodaEventi(context)
        for (sforamento in decisione.nuovi) {
            // Le limite_tempo dedupano sul giorno (fuso telefono); le fasce sul
            // giorno di ancoraggio dell'occorrenza, così le due parti di una
            // fascia che scavalca la mezzanotte non diventano due sforamenti.
            // Lo stesso giorno viaggia nei dettagli (v2.4): il semaforo lo mette lì.
            val giornoDedup = Valutatore.giornoDelloSforamento(sforamento, giorno)
            coda.accoda(
                Evento(
                    id = Segnalazioni.idEvento(dispositivo, sforamento.regolaId, giornoDedup),
                    tipo = TipiEvento.SFORAMENTO,
                    tsDevice = now,
                    dettagli = Valutatore.dettagliSforamento(sforamento, giorno),
                ),
            )
            // Segnato subito dopo l'accodamento: la coda persiste e riconsegna
            // da sola, quindi l'evento arriverà — ri-valutare non deve duplicarlo.
            impostazioni.registraSforamentoSegnalato(sforamento.regolaId, giornoDedup)
            avvisaGentile(sforamento, regolePerId[sforamento.regolaId], tuttoSchermo, giornoPassato)
        }
        // Stesso dedup della notifica: una volta per regola per giorno.
        AvvisoActivity.apri(
            context,
            decisione.aTuttoSchermo.map { sforamento ->
                val regola = regolePerId[sforamento.regolaId]
                Avviso.da(sforamento, regola, nomeBersaglio(regola))
            },
        )
        return Giro(Segnalati(decisione.nuovi, FotografiaUso.evento(context, lettura.uso)), prossimaSoglia)
    }

    /**
     * (0.12) I preavvisi "il tempo sta per finire" di adesso, contro la copia
     * locale del patto (niente rilettura dal server: una copia indietro di un
     * bonus dà al massimo un preavviso in anticipo, mai uno sforamento falso).
     * Come gli sforamenti: prima si segnano come detti, poi si avvisa (anche
     * se la notifica non parte: mai un giro in tondo). Quelli che non valgono
     * più si tolgono dalla tendina; a mezzanotte se ne vanno da soli.
     * Restituisce fra quanti ms l'app davanti porta una regola alla prossima
     * soglia (Preavvisi.prossimaSoglia).
     */
    private suspend fun preavvisa(patto: Patto, lettura: Lettura, now: Long, giorno: String): Long? {
        val regole = patto.regoleDiQuestoDispositivo().filter { it.attiva && it.tipo == TipiRegola.LIMITE_TEMPO }
        val impostazioni = Impostazioni(context)
        var fatti = impostazioni.leggiPreavvisiFatti()
        val bonus = bonusOggi(patto, now)
        val indice = lettura.uso.indice
        // Le app davanti adesso; quelle di una Sessione in corso contano solo dopo la sua fine.
        val davanti = Preavvisi.davanti(lettura.giornata, lettura.filtro, StatoSessione.attivaAdesso(now)?.fine)
        val nuovi = Preavvisi.daDare(regole, bonus, indice, giorno, fatti, davanti)
        if (nuovi.isNotEmpty()) {
            val dette = Preavvisi.chiaviDette(nuovi, giorno)
            impostazioni.registraPreavvisi(dette)
            fatti = fatti + dette
            val regolePerId = patto.regole.associateBy { it.id }
            val finoAMezzanotte = Preavvisi.finoAFineGiorno(now, lettura.zona)
            for (preavviso in nuovi) avvisaPreavviso(preavviso, regolePerId[preavviso.regolaId], finoAMezzanotte)
        }
        for (regolaId in Preavvisi.daTogliere(regole, bonus, indice, giorno, fatti)) {
            AvvisiLocali.cancella(context, AvvisiLocali.idPreavviso(regolaId))
        }
        return Preavvisi.prossimaSoglia(regole, bonus, indice, giorno, fatti, davanti)
    }

    /**
     * Gli sforamenti di adesso contro la copia locale del patto, senza toccare
     * registro né notifiche: la usa la chiusura della sera.
     * Null se non c'è niente da valutare (niente permesso, niente patto).
     */
    suspend fun misura(now: Long = System.currentTimeMillis()): Misura? {
        if (!PermessiHelper.haAccessoUso(context)) return null
        val patto = PattoLocale(context).leggi() ?: return null
        // (v3) Solo le regole di QUESTO telefono: una fascia oraria del computer
        // misurata sull'uso del telefono sarebbe uno sforamento falso nel registro.
        if (patto.regoleDiQuestoDispositivo().isEmpty()) return null
        if (!Segnalazioni.daGuardare(patto.regoleDiQuestoDispositivo())) {
            // Solo vita reale: niente da misurare, niente lettura degli eventi.
            val giorno = Instant.ofEpochMilli(now).atZone(ZoneId.systemDefault()).toLocalDate()
            return Misura(patto, emptyList(), giorno.toString())
        }
        val lettura = leggi(now, null)
        return Misura(patto, valutaContro(patto, lettura, now, soloFasce = false), lettura.giorno.toString())
    }

    /** L'uso del giorno di [now], da [giornata] se è di quel giorno, altrimenti letto adesso. */
    private fun leggi(now: Long, giornata: LetturaGiorno?): Lettura {
        val zona = ZoneId.systemDefault()
        val giorno = Instant.ofEpochMilli(now).atZone(zona).toLocalDate()
        // Un filtro per giro: la schermata Home si risolve una volta sola.
        val filtro = CatalogoApp.filtroUso(context)
        // (0.11) La lettura è già senza il tempo passato in una Sessione nelle
        // sue app (UsageStatsReader): limiti, totale e fasce non lo vedono.
        val letta = giornata?.takeIf { it.giorno == giorno }
            ?: UsageStatsReader(context).leggiGiorno(giorno, zona, now)
        return Lettura(giorno, zona, letta, UsoContato.di(context, letta, filtro), filtro)
    }

    /**
     * I bonus di "oggi" valgono solo se la copia è di oggi (fuso del patto).
     * Più il bonus appena dato e non ancora confermato dal server (snackbar
     * aperta, rete assente): chi si dà +15 a limite passato non va segnato
     * fuori regola nei secondi in cui il bonus aspetta. Se il server lo
     * rifiuta, il cassetto si svuota e il giro dopo lo sforamento c'è.
     */
    private suspend fun bonusOggi(patto: Patto, now: Long): Map<String, Int> {
        val oggiPatto = Instant.ofEpochMilli(now).atZone(zonaPatto(patto.fuso)).toLocalDate().toString()
        return RegoleBonus.bonusConSospeso(
            bonusOggi = patto.bonusValidiOggi(now),
            sospeso = CassettaBonus(context).leggi(),
            oggi = oggiPatto,
            residuo = patto.residuoBonusOggi(now),
        )
    }

    private suspend fun valutaContro(patto: Patto, lettura: Lettura, now: Long, soloFasce: Boolean): List<Sforamento> {
        val regole = patto.regoleDiQuestoDispositivo()
            .filter { !soloFasce || it.tipo == TipiRegola.FASCIA_ORARIA }

        // Una regola limite_tempo vale su un pacchetto esatto, su una chiave
        // categoria:* (contratto v2.1) o (0.9) su "totale", tutto il telefono.
        // Le fasce contano le stesse app del totale: niente Home, niente Pactum.
        return Valutatore.valuta(
            regole = regole,
            bonusOggiPerRegola = bonusOggi(patto, now),
            usoMinutiEtichetta = lettura.uso.indice::minuti,
            usoMinutiIntervallo = { inizio, fine ->
                lettura.giornata.millisNellIntervallo(inizio, fine, lettura.filtro) / 60_000
            },
            now = now,
            zona = lettura.zona,
        )
    }

    /** Il patto fresco dal server, salvato come copia locale; null senza rete o se non è entrato. */
    private suspend fun rileggi(): Patto? {
        val configurazione = Impostazioni(context).leggiConfigurazione()
        if (!configurazione.completa) return null
        val patto = PostinoClient(configurazione).leggiPatto() ?: return null
        return patto.takeIf { PattoLocale(context).salva(it) }
    }

    /**
     * Il promemoria al figlio: tono da patto, non da sirena. (0.9) Se l'avviso
     * a tutto schermo non parte, la notifica va sul canale che si vede in alto.
     */
    private fun avvisaGentile(sforamento: Sforamento, regola: Regola?, tuttoSchermo: Boolean, giornoPassato: Boolean) {
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
            // (0.12) Il preavviso di questa regola ("manca 1 minuto") non serve più.
            AvvisiLocali.cancella(context, AvvisiLocali.idPreavviso(sforamento.regolaId))
            titolo = context.getString(
                if (giornoPassato) R.string.notifica_sforamento_limite_ieri_titolo else R.string.notifica_sforamento_limite_titolo,
            )
            testo = context.getString(
                if (giornoPassato) R.string.notifica_sforamento_limite_ieri_testo else R.string.notifica_sforamento_limite_testo,
                nomeBersaglio(regola) ?: "?",
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
            canale = if (tuttoSchermo) AvvisiLocali.CANALE_PATTO else AvvisiLocali.CANALE_SFORAMENTI,
        )
    }

    /**
     * (0.12) "Instagram: mancano 5 minuti al limite che ti sei dato." Sul
     * canale "Il tempo sta per finire" (in alto, sopra l'app in uso); toccata,
     * apre Oggi, dove la regola ha i suoi minuti e il bonus.
     */
    private fun avvisaPreavviso(preavviso: Preavviso, regola: Regola?, scadeTra: Long) {
        val nome = nomeBersaglio(regola) ?: context.getString(R.string.preavviso_bersaglio_ignoto)
        AvvisiLocali.avvisa(
            context,
            id = AvvisiLocali.idPreavviso(preavviso.regolaId),
            titolo = context.getString(R.string.preavviso_titolo),
            testo = context.resources.getQuantityString(
                R.plurals.preavviso_testo,
                preavviso.minutiMancanti,
                nome,
                preavviso.minutiMancanti,
            ),
            destinazione = MainActivity.DEST_OGGI,
            canale = AvvisiLocali.CANALE_PREAVVISI,
            // Domani non vale più: se ne va da sola a mezzanotte.
            scadeTra = scadeTra,
        )
    }

    /** Il bersaglio di un limite di tempo in chiaro: "TikTok", "Social", "Tutto il telefono". */
    private fun nomeBersaglio(regola: Regola?): String? {
        if (regola == null) return null
        val chiave = testoParametro(regola.parametri, "app_o_categoria") ?: return null
        return etichettaChiave(context, chiave, regola.nome)
    }

    private fun testoParametro(parametri: kotlinx.serialization.json.JsonObject, nome: String): String? =
        (parametri[nome] as? kotlinx.serialization.json.JsonPrimitive)?.content

    private companion object {
        val mutex = Mutex()
        val NIENTE = Giro(null, null)
    }
}
