package eu.stgm.pactum.figlio.siti

import android.content.Context
import android.content.Intent
import android.net.VpnService
import eu.stgm.pactum.figlio.dati.CodaEventi
import eu.stgm.pactum.figlio.dati.Evento
import eu.stgm.pactum.figlio.dati.Impostazioni
import eu.stgm.pactum.figlio.dati.SottoTipiManomissione
import eu.stgm.pactum.figlio.dati.TipiEvento
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * L'interruttore dell'osservazione dei siti, con la memoria di cosa ha deciso
 * il figlio: acceso solo se LUI l'ha acceso (consenso di sistema alla VPN +
 * scelta esplicita nell'app), spento appena una delle due cade.
 *
 * Ogni spegnimento va a registro come **manomissione** con un sotto_tipo
 * dedicato, esattamente come la revoca dell'accesso ai dati di utilizzo: il
 * genitore vede che l'osservazione si è fermata e quando. Non è una punizione,
 * è il registro che non mente (concept.md).
 */
object OsservazioneSiti {

    const val ORIGINE_APP = "app"
    const val ORIGINE_SISTEMA = "sistema"

    /**
     * L'Intent da lanciare (SOLO da un'Activity, con risultato) per chiedere
     * il consenso VPN al sistema; null se il consenso c'è già.
     */
    fun intentConsenso(context: Context): Intent? = try {
        VpnService.prepare(context)
    } catch (e: RuntimeException) {
        null
    }

    /** true se il consenso VPN di sistema è già stato dato a Pactum. */
    fun consensoConcesso(context: Context): Boolean = try {
        VpnService.prepare(context) == null
    } catch (e: RuntimeException) {
        false
    }

    /** L'osservazione DOVREBBE essere in piedi: scelta del figlio + consenso vivo. */
    suspend fun attivaOra(context: Context): Boolean =
        Impostazioni(context).leggiOsservazioneSitiRichiesta() && consensoConcesso(context)

    /** Il figlio accende l'osservazione (dopo il consenso VPN di sistema). */
    suspend fun accendi(context: Context) {
        val impostazioni = Impostazioni(context)
        impostazioni.registraOsservazioneSitiRichiesta(true)
        impostazioni.registraOsservazioneSitiNota(true)
        OsservatoreSitiService.avvia(context)
    }

    /** Il figlio spegne l'osservazione dall'app: fatto dichiarato, non nascosto. */
    suspend fun spegniDaApp(context: Context) {
        spegni(context, ORIGINE_APP)
        OsservatoreSitiService.ferma(context)
    }

    /**
     * Il sistema ha tolto il consenso (impostazioni VPN di Android, oppure
     * un'altra VPN che prende il posto): lo chiama il servizio da `onRevoke`.
     */
    suspend fun spegniDopoInterruzione(context: Context, origine: String) {
        spegni(context, origine)
    }

    /**
     * Riaccende l'osservazione se il figlio l'aveva accesa e il consenso c'è
     * ancora: dopo un riavvio del telefono o se il servizio è stato ucciso.
     */
    suspend fun riprendiSeConsentita(context: Context) {
        if (OsservatoreSitiService.attivo) return
        if (!attivaOra(context)) return
        OsservatoreSitiService.avvia(context)
    }

    /**
     * Rileva la TRANSIZIONE attiva → non attiva (stesso schema dei permessi in
     * BattitoWorker): l'evento nasce una volta sola, sul cambio, non a ogni
     * giro. La prima osservazione fissa solo la base.
     */
    suspend fun rilevaInterruzione(context: Context) {
        val impostazioni = Impostazioni(context)
        val adesso = attivaOra(context)
        val nota = impostazioni.leggiOsservazioneSitiNota()
        if (nota == true && !adesso) {
            registraInterruzione(context, ORIGINE_SISTEMA)
            // Il consenso non c'è più: senza azzerare la richiesta, il worker
            // proverebbe a riaccendere per sempre una VPN che il sistema nega.
            impostazioni.registraOsservazioneSitiRichiesta(false)
        }
        if (nota != adesso) impostazioni.registraOsservazioneSitiNota(adesso)
    }

    private suspend fun spegni(context: Context, origine: String) {
        val impostazioni = Impostazioni(context)
        val eraAttiva = impostazioni.leggiOsservazioneSitiRichiesta()
        impostazioni.registraOsservazioneSitiRichiesta(false)
        // La base si aggiorna qui: così il controllo del worker non emette un
        // SECONDO evento per la stessa interruzione.
        impostazioni.registraOsservazioneSitiNota(false)
        if (eraAttiva) registraInterruzione(context, origine)
        runCatching { RegistroSiti.salvaSubito(context) }
    }

    private suspend fun registraInterruzione(context: Context, origine: String) {
        CodaEventi(context).accoda(
            Evento(
                tipo = TipiEvento.MANOMISSIONE,
                tsDevice = System.currentTimeMillis(),
                dettagli = buildJsonObject {
                    put("sotto_tipo", SottoTipiManomissione.OSSERVAZIONE_SITI_INTERROTTA)
                    put("origine", origine)
                },
            ),
        )
    }
}
