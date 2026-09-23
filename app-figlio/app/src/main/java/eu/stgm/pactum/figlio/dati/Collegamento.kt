package eu.stgm.pactum.figlio.dati

import android.content.Context
import eu.stgm.pactum.figlio.BuildConfig
import eu.stgm.pactum.figlio.bonus.CassettaBonus
import eu.stgm.pactum.figlio.bonus.ConsegnaBonus
import eu.stgm.pactum.figlio.rete.PostinoClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext

/**
 * Il collegamento di questo telefono al patto (contratto v3, "Abbinamento con
 * codice"). Due strade:
 *  - il codice di 6 cifre che il genitore genera dalla sua app: POST
 *    /api/abbina, e il token che torna si salva come il vecchio campo;
 *  - il vecchio codice lungo (il token), per i telefoni già collegati prima
 *    della v3: continuano a funzionare senza toccare niente.
 *
 * Se il telefono passa a un ALTRO dispositivo del patto, tutto quello che era
 * del dispositivo di prima se ne va (copia del patto, sforamenti già segnalati
 * o in coda, bonus in sospeso). Se è lo stesso dispositivo con un codice nuovo,
 * la sua storia continua e cambia solo il token.
 *
 * Il collegamento gira nell'ambito del PROCESSO, non della schermata (come la
 * consegna del bonus). Il server consuma il codice appena lo riceve e il token
 * lo dà una volta sola: se una rotazione, una pausa o l'uscita dalle
 * Impostazioni interrompessero l'operazione a metà, il codice sarebbe bruciato
 * e il telefono resterebbe senza token. Per questo, una volta partita la
 * richiesta, niente si interrompe fino al token salvato ([NonCancellable]).
 * L'esito resta in [stato] finché una schermata non lo prende: arriva anche a
 * una schermata ricreata.
 */
object Collegamento {

    private val corsa = CorsaCollegamento(CoroutineScope(SupervisorJob() + Dispatchers.IO))

    /** A che punto è il collegamento, per la schermata (anche ricreata). */
    val stato: StateFlow<CorsaCollegamento.Stato> = corsa.stato

    /** Collega con il codice di 6 cifre. False se un collegamento è già in corso. */
    fun avviaConCodice(context: Context, serverGrezzo: String, codice: String): Boolean {
        val app = context.applicationContext
        return corsa.avvia { conCodice(app, serverGrezzo, codice) }
    }

    /** Collega con il vecchio codice lungo. False se un collegamento è già in corso. */
    fun avviaConCodiceLungo(context: Context, serverGrezzo: String, token: String): Boolean {
        val app = context.applicationContext
        return corsa.avvia { conCodiceLungo(app, serverGrezzo, token) }
    }

    /** La schermata ha mostrato l'esito [finito]: non va detto un'altra volta. */
    fun consuma(finito: CorsaCollegamento.Stato.Finito) = corsa.consuma(finito)

    /** Il codice di 6 cifre. */
    private suspend fun conCodice(app: Context, serverGrezzo: String, codice: String): EsitoCollegamento {
        val server = PostinoClient.normalizzaUrlServer(serverGrezzo)
            ?: return EsitoCollegamento.IndirizzoNonValido
        // Da qui niente si interrompe: il codice il server lo consuma appena lo
        // riceve, e il token che torna non si può più chiedere.
        return withContext(NonCancellable) {
            val risposta = PostinoClient.abbina(server, Abbinamento.richiesta(codice, BuildConfig.VERSION_NAME))
            val esito = Abbinamento.esito(risposta.ok, risposta.codice, risposta.corpo)
            if (esito !is EsitoAbbinamento.Collegato) return@withContext EsitoCollegamento.ConCodice(esito)

            val impostazioni = Impostazioni(app)
            val prima = impostazioni.leggiConfigurazione()
            // Chi era questo telefono: dall'ultimo patto letto, o dall'ultimo abbinamento.
            val identitaPrima = impostazioni.leggiIdentita()
            val pattoPrima = PattoLocale(app).leggi()
            val dispositivoPrima = pattoPrima?.dispositivo?.takeIf { it.id > 0 }
                ?: identitaPrima.dispositivo?.takeIf { it.id > 0 }
            val figlioPrima = pattoPrima?.figlio?.id?.takeIf { it > 0 } ?: identitaPrima.figlio?.id
            val stessoDispositivo = Abbinamento.stessoDispositivo(
                prima.completa, prima.serverUrl, server, dispositivoPrima?.id, esito.dispositivo?.id,
            )
            val stessoFiglio = Abbinamento.stessoFiglio(
                prima.completa, prima.serverUrl, server, figlioPrima, esito.figlio?.id,
            )
            val pattoNuovo = salva(
                app, server, esito.token, Identita(esito.dispositivo, esito.figlio),
                stessoDispositivo, stessoFiglio,
                // Gli sforamenti in coda portano le regole del dispositivo di prima.
                scartaSforamentiInCoda = Abbinamento.scartaSforamentiInCoda(
                    prima.completa, prima.serverUrl, server, stessoDispositivo,
                ),
            )
            // Il collegamento resta valido anche su un dispositivo diverso: il
            // telefono però lo dice chiaro (il genitore potrebbe aver usato
            // "Aggiungi un dispositivo" invece di "Nuovo codice").
            val cambio = Abbinamento.cambioDispositivo(
                eraCollegato = prima.completa,
                stessoServer = Abbinamento.stessoServer(prima.serverUrl, server),
                prima = dispositivoPrima,
                dopo = esito.dispositivo,
                dispositiviDopo = pattoNuovo?.dispositivi.orEmpty(),
            )
            EsitoCollegamento.ConCodice(esito, cambio)
        }
    }

    /**
     * Il vecchio codice lungo, com'era nella 0.7: stesso indirizzo e stesso
     * codice = non cambia niente; altrimenti è un patto diverso.
     */
    private suspend fun conCodiceLungo(app: Context, serverGrezzo: String, token: String): EsitoCollegamento {
        // Un indirizzo scritto male e accettato in silenzio = un'app che non
        // consegna mai niente senza dirlo: si rifiuta subito.
        val server = PostinoClient.normalizzaUrlServer(serverGrezzo)
            ?: return EsitoCollegamento.IndirizzoNonValido
        return withContext(NonCancellable) {
            val prima = Impostazioni(app).leggiConfigurazione()
            val uguale = prima.serverUrl == server && prima.token == token.trim()
            salva(
                app, server, token, identita = null,
                stessoDispositivo = uguale, stessoFiglio = uguale,
                // Col codice lungo non si sa di che dispositivo è: sullo stesso
                // server la coda resta com'era nella 0.7 (niente sforamenti
                // buttati per un sospetto). Su un altro server i numeri delle
                // regole sono di quello vecchio: la coda degli sforamenti va via.
                scartaSforamentiInCoda = Abbinamento.scartaSforamentiInCoda(
                    prima.completa, prima.serverUrl, server, stessoDispositivo = null,
                ),
            )
            EsitoCollegamento.CodiceLungoSalvato
        }
    }

    /** Salva il collegamento e legge subito il patto nuovo: lo restituisce (null senza rete). */
    private suspend fun salva(
        app: Context,
        server: String,
        token: String,
        identita: Identita?,
        stessoDispositivo: Boolean,
        stessoFiglio: Boolean,
        scartaSforamentiInCoda: Boolean,
    ): Patto? = withContext(NonCancellable) {
        val impostazioni = Impostazioni(app)
        // Il bonus in sospeso adesso: se il dispositivo cambia, era del patto vecchio.
        val sospeso = CassettaBonus(app).leggi()
        val copia = PattoLocale(app)
        copia.cambiaCollegamento(cancellaCopia = !stessoDispositivo) {
            impostazioni.salvaCollegamento(server, token, identita, stessoDispositivo, stessoFiglio)
        }
        // Un bonus del patto vecchio non parte verso quello nuovo.
        if (!stessoDispositivo && sospeso != null) ConsegnaBonus.dimenticaInFondo(app, sospeso.id)
        if (scartaSforamentiInCoda) CodaEventi(app).scartaTipo(TipiEvento.SFORAMENTO)
        // Il patto nuovo subito: "Collegato come", regole e striscia senza
        // aspettare il prossimo giro. Senza rete arriva al giro dopo.
        val configurazione = impostazioni.leggiConfigurazione()
        PostinoClient(configurazione).leggiPatto()?.also { copia.salva(it) }
    }
}
