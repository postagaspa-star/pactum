package eu.stgm.pactum.figlio.faccende

import android.content.Context
import eu.stgm.pactum.figlio.dati.Impostazioni
import eu.stgm.pactum.figlio.rete.PostinoClient
import eu.stgm.pactum.figlio.sync.Ritento
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * (0.13) Le foto delle faccende verso il server (`PUT /api/faccende/{id}/foto`).
 *
 * Una foto scattata si prepara e entra nella coda (ArchivioCodaFoto) nel
 * lavoro del PROCESSO, non della schermata: tornare indietro mentre si
 * prepara non la perde (lo scatto originale è già cancellato). Poi parte
 * subito; se la rete manca o il server non risponde resta lì e riparte da
 * sola: al giro del servizio con attesa crescente (Ritento: 1, 2, poi 4
 * minuti), appena torna la rete (il servizio ascolta la rete) e dal worker.
 *
 * Con la foto viaggiano le bocciature che la faccenda aveva allo scatto
 * (`?bocciature=N`): una foto vecchia, consegnata dopo una bocciatura, il
 * server non la prende (409 `bocciata_nel_frattempo`), e la coda la butta.
 * Prima di mandare, il blocco si rilegge: una foto di una faccenda che non è
 * più da fare, o bocciata dopo lo scatto, non parte. Il server accetta la
 * stessa foto due volte senza farne due. Quando una foto arriva, il blocco si
 * richiede subito: si sblocca quando il server dice che non c'è più niente da fare.
 */
object ConsegnaFoto {

    private val mutex = Mutex()
    private val ambito = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile private var falliti = 0
    @Volatile private var ultimoTentativo = 0L

    /** Com'è andato lo scatto. */
    enum class EsitoScatto { IN_CODA, NON_RIUSCITO, NON_COLLEGATO }

    /**
     * Lo scatto [originale] appena tornato dalla fotocamera per [faccendaId]
     * (che allo scatto aveva [bocciature] bocciature): preparato (senza dati
     * nascosti, lato lungo ≤ 2048), messo in coda al posto di uno scattato
     * prima per la stessa faccenda, e mandato subito. Tutto nel lavoro del
     * processo: se chi chiama se ne va, la foto entra in coda lo stesso.
     */
    suspend fun nuovoScatto(context: Context, faccendaId: Long, bocciature: Int, originale: File): EsitoScatto {
        val app = context.applicationContext
        return ambito.async {
            try {
                preparaEMetti(app, faccendaId, bocciature, originale)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                originale.delete()
                EsitoScatto.NON_RIUSCITO
            }
        }.await()
    }

    private suspend fun preparaEMetti(app: Context, faccendaId: Long, bocciature: Int, originale: File): EsitoScatto {
        val chiave = chiaveCollegamento(app)
        if (chiave == null) {
            originale.delete()
            return EsitoScatto.NON_COLLEGATO
        }
        val nome = FotoFaccenda.prepara(app, originale) ?: return EsitoScatto.NON_RIUSCITO
        ArchivioCodaFoto.modifica(app) {
            it.conScatto(
                FotoInCoda(
                    faccendaId = faccendaId,
                    file = nome,
                    scattataIl = System.currentTimeMillis(),
                    collegamento = chiave,
                    bocciature = bocciature,
                ),
            )
        }
        falliti = 0
        ambito.launch {
            try {
                consegna(app)
            } catch (e: Exception) {
                // resta in coda: riprova il giro del servizio, poi il worker
            }
        }
        return EsitoScatto.IN_CODA
    }

    /** Dal giro del servizio (e dal worker, con [forza]): si riprova se l'attesa è passata. */
    suspend fun riprovaSeServe(context: Context, adesso: Long = System.currentTimeMillis(), forza: Boolean = false) {
        val app = context.applicationContext
        val chiave = withContext(Dispatchers.IO) { chiaveCollegamento(app) } ?: return
        val coda = withContext(Dispatchers.IO) { ArchivioCodaFoto.leggi(app) }
        if (coda.daMandare(chiave).isEmpty()) {
            falliti = 0
            return
        }
        if (!forza && !Ritento.pronto(falliti, ultimoTentativo, adesso)) return
        consegna(app)
    }

    /** La rete è tornata: si riprova subito, senza aspettare l'attesa. */
    fun reteTornata(context: Context) {
        val app = context.applicationContext
        falliti = 0
        ambito.launch {
            try {
                consegna(app)
            } catch (e: Exception) {
                // al giro dopo
            }
        }
    }

    /** Manda adesso le foto in coda. True se non ne resta nessuna da mandare. */
    suspend fun consegna(context: Context): Boolean = withContext(Dispatchers.IO) {
        val app = context.applicationContext
        var arrivate = false
        val finite = mutex.withLock {
            ultimoTentativo = System.currentTimeMillis()
            val configurazione = Impostazioni(app).leggiConfigurazione()
            val chiave = chiaveCollegamento(app) ?: return@withLock false
            // Le foto di un altro collegamento non partono mai verso questo.
            ArchivioCodaFoto.modifica(app) { it.senzaAltriCollegamenti(chiave) }
            if (ArchivioCodaFoto.leggi(app).daMandare(chiave).isEmpty()) return@withLock true
            // Prima il blocco, fresco: una foto di una faccenda che non è più da
            // fare, o bocciata dopo lo scatto, non parte (la toglie da qui).
            if (ControlloBlocco.interroga(app) == ControlloBlocco.Esito.LETTO) {
                val daFare = ArchivioBlocco.leggi(app).daFare
                ArchivioCodaFoto.modifica(app) { it.soloDaFare(daFare) }
            }
            val postino = PostinoClient(configurazione)
            var tutte = true
            for (voce in ArchivioCodaFoto.leggi(app).daMandare(chiave)) {
                val file = File(FotoFaccenda.cartellaPronte(app), voce.file)
                val dati = runCatching { file.readBytes() }.getOrNull()
                if (dati == null || !ElaborazioneFoto.eJpeg(dati)) {
                    // Il file non c'è più o è rovinato: va scattata di nuovo.
                    ArchivioCodaFoto.modifica(app) { it.conEsito(voce.faccendaId, voce.file, EsitoFoto.RIFIUTATA, System.currentTimeMillis()) }
                    continue
                }
                val risposta = postino.mandaFoto(voce.faccendaId, dati, voce.bocciature)
                val esito = EsitiFaccende.foto(risposta.ok, risposta.codice, risposta.corpo)
                ArchivioCodaFoto.modifica(app) { it.conEsito(voce.faccendaId, voce.file, esito, System.currentTimeMillis()) }
                // La faccenda com'è adesso (fatta) per la pagina; il blocco lo dice il server, dopo.
                if (esito == EsitoFoto.ARRIVATA) {
                    LetturaFaccende.faccendaDaCorpo(risposta.corpo)
                        ?.takeIf { it.id == voce.faccendaId }
                        ?.let { f -> ArchivioBlocco.modifica(app) { it.conFaccenda(f) } }
                }
                when (esito) {
                    EsitoFoto.ARRIVATA -> arrivate = true
                    EsitoFoto.NON_SERVE, EsitoFoto.RIFIUTATA, EsitoFoto.BOCCIATA_NEL_FRATTEMPO -> Unit
                    // Senza rete o col server che non risponde, le altre non partono nemmeno.
                    EsitoFoto.SENZA_RETE, EsitoFoto.SCOLLEGATO, EsitoFoto.SERVER_VECCHIO, EsitoFoto.ERRORE -> {
                        tutte = false
                        break
                    }
                }
            }
            if (tutte) falliti = 0 else falliti += 1
            tutte
        }
        // Una foto arrivata: il blocco si richiede subito (lo toglie solo il
        // server), anche se il servizio non c'è.
        if (arrivate) {
            ambito.launch {
                try {
                    ControlloBlocco.interroga(app)
                } catch (e: Exception) {
                    ControlloBlocco.richiedi()
                }
            }
        }
        finite
    }

    /**
     * Di quale collegamento sono le foto adesso: l'impronta del codice e, se
     * si sa, il dispositivo e il figlio. Null se il telefono non è collegato.
     * Cambiare solo l'indirizzo del server non cambia il collegamento.
     */
    suspend fun chiaveCollegamento(app: Context): ChiaveCollegamento? {
        val impostazioni = Impostazioni(app)
        val configurazione = impostazioni.leggiConfigurazione()
        if (!configurazione.completa) return null
        val identita = impostazioni.leggiIdentita()
        return ChiaveCollegamento.di(
            impronta = ChiaveCollegamento.impronta(configurazione.token),
            dispositivoId = identita.dispositivo?.id,
            figlioId = identita.figlio?.id,
        )
    }
}
