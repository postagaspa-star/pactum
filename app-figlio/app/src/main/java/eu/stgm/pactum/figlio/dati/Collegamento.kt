package eu.stgm.pactum.figlio.dati

import android.content.Context
import eu.stgm.pactum.figlio.BuildConfig
import eu.stgm.pactum.figlio.bonus.CassettaBonus
import eu.stgm.pactum.figlio.bonus.ConsegnaBonus
import eu.stgm.pactum.figlio.rete.PostinoClient

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
 */
object Collegamento {

    /**
     * Collega con il codice di 6 cifre. null = indirizzo del server non valido
     * (lo si dice sul campo, senza chiamare nessuno).
     */
    suspend fun conCodice(context: Context, serverGrezzo: String, codice: String): EsitoAbbinamento? {
        val app = context.applicationContext
        val server = PostinoClient.normalizzaUrlServer(serverGrezzo) ?: return null
        val risposta = PostinoClient.abbina(server, AbbinaIn(codice, BuildConfig.VERSION_NAME))
        val esito = Abbinamento.esito(risposta.ok, risposta.codice, risposta.corpo)
        if (esito is EsitoAbbinamento.Collegato) {
            val impostazioni = Impostazioni(app)
            val prima = impostazioni.leggiConfigurazione()
            // Chi era questo telefono: dall'ultimo patto letto, o dall'ultimo abbinamento.
            val identitaPrima = impostazioni.leggiIdentita()
            val pattoPrima = PattoLocale(app).leggi()
            val dispositivoPrima = pattoPrima?.dispositivo?.id?.takeIf { it > 0 }
                ?: identitaPrima.dispositivo?.id
            val figlioPrima = pattoPrima?.figlio?.id?.takeIf { it > 0 } ?: identitaPrima.figlio?.id
            val stessoDispositivo = Abbinamento.stessoDispositivo(
                prima.completa, prima.serverUrl, server, dispositivoPrima, esito.dispositivo?.id,
            )
            val stessoFiglio = Abbinamento.stessoFiglio(
                prima.completa, prima.serverUrl, server, figlioPrima, esito.figlio?.id,
            )
            salva(
                app, server, esito.token, Identita(esito.dispositivo, esito.figlio),
                stessoDispositivo, stessoFiglio,
                // Gli sforamenti in coda portano le regole del dispositivo di prima.
                scartaSforamentiInCoda = prima.completa && !stessoDispositivo,
            )
        }
        return esito
    }

    /**
     * Il vecchio codice lungo, com'era nella 0.7: stesso indirizzo e stesso
     * codice = non cambia niente; altrimenti è un patto diverso. false =
     * indirizzo non valido.
     */
    suspend fun conCodiceLungo(context: Context, serverGrezzo: String, token: String): Boolean {
        val app = context.applicationContext
        val server = PostinoClient.normalizzaUrlServer(serverGrezzo) ?: return false
        val prima = Impostazioni(app).leggiConfigurazione()
        val uguale = prima.serverUrl == server && prima.token == token.trim()
        salva(
            app, server, token, identita = null,
            stessoDispositivo = uguale, stessoFiglio = uguale,
            // Col codice lungo non si sa di che dispositivo è: la coda resta
            // com'era nella 0.7 (niente sforamenti buttati per un sospetto).
            scartaSforamentiInCoda = false,
        )
        return true
    }

    private suspend fun salva(
        app: Context,
        server: String,
        token: String,
        identita: Identita?,
        stessoDispositivo: Boolean,
        stessoFiglio: Boolean,
        scartaSforamentiInCoda: Boolean,
    ) {
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
        PostinoClient(configurazione).leggiPatto()?.let { copia.salva(it) }
    }
}
