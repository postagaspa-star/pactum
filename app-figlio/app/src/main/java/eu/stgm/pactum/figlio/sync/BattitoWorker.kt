package eu.stgm.pactum.figlio.sync

import android.content.Context
import android.os.SystemClock
import androidx.work.BackoffPolicy
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.core.app.NotificationManagerCompat
import eu.stgm.pactum.figlio.aggiornamento.Aggiornatore
import eu.stgm.pactum.figlio.bonus.ConsegnaBonus
import eu.stgm.pactum.figlio.dati.AncoraTempo
import eu.stgm.pactum.figlio.dati.CodaEventi
import eu.stgm.pactum.figlio.dati.Evento
import eu.stgm.pactum.figlio.dati.Impostazioni
import eu.stgm.pactum.figlio.dati.PattoLocale
import eu.stgm.pactum.figlio.dati.TipiEvento
import eu.stgm.pactum.figlio.giornata.ChiusuraSerale
import eu.stgm.pactum.figlio.misura.FotografiaUso
import eu.stgm.pactum.figlio.misura.UsageStatsReader
import eu.stgm.pactum.figlio.faccende.ConsegnaFoto
import eu.stgm.pactum.figlio.faccende.ArchivioBlocco
import eu.stgm.pactum.figlio.faccende.ControlloBlocco
import eu.stgm.pactum.figlio.faccende.PermessiRevocati
import eu.stgm.pactum.figlio.faccende.Orologio
import eu.stgm.pactum.figlio.notifiche.NovitaDalPatto
import eu.stgm.pactum.figlio.permessi.PermessiHelper
import eu.stgm.pactum.figlio.rete.PostinoClient
import eu.stgm.pactum.figlio.servizio.PactumService
import eu.stgm.pactum.figlio.sessione.ConsegnaSessioni
import eu.stgm.pactum.figlio.siti.Domini
import eu.stgm.pactum.figlio.siti.OsservazioneSiti
import eu.stgm.pactum.figlio.siti.RegistroSiti
import eu.stgm.pactum.figlio.siti.ReteDns
import eu.stgm.pactum.figlio.valutatore.SentinellaPatto
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.LocalDate
import java.util.concurrent.TimeUnit

/**
 * La misura + rete di sicurezza: ogni ~15 minuti rilegge l'uso del giorno (e
 * del giorno prima), lo accoda al registro e prova a consegnare battito +
 * eventi al postino. Design retroattivo (architettura.md): il sistema registra
 * la storia d'uso da solo, quindi la misura non dipende da un'app sempre viva.
 * Il canale PRIMARIO del battito è il loop dentro PactumService (Doze rinvia
 * il worker anche per ore); qui il battito resta come backstop — i doppi
 * battiti sono innocui lato server.
 *
 * Nessun vincolo di rete, di proposito: il worker deve SEMPRE girare e
 * misurare anche offline (una serata senza rete va comunque nel registro).
 * PostinoClient tollera l'offline e CodaEventi persiste: gli invii falliti
 * restano in coda per il giro successivo.
 */
class BattitoWorker(appContext: Context, params: WorkerParameters) :
    CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val context = applicationContext
        val impostazioni = Impostazioni(context)
        val coda = CodaEventi(context)

        // Ancora temporale aggiornata a ogni battito: OrologioReceiver la usa
        // per distinguere la sincronizzazione automatica dai cambi d'ora manuali.
        impostazioni.salvaAncoraTempo(
            AncoraTempo(
                wallClock = System.currentTimeMillis(),
                elapsedRealtime = SystemClock.elapsedRealtime(),
            ),
        )

        val configurazione = impostazioni.leggiConfigurazione()
        if (!configurazione.completa) return Result.success() // patto non ancora configurato

        val accessoUso = PermessiHelper.haAccessoUso(context)
        // (0.9) Il servizio del testimone, se non c'è: dopo un aggiornamento, un
        // "Interrompi" dalle app attive o un'uccisione del sistema non riparte
        // da solo, e senza di lui la sentinella torna al ritmo di questo worker.
        // Se è già vivo non cambia niente. Android può rifiutare l'avvio da
        // dietro le quinte (senza l'esenzione dalla batteria): si riprova al giro dopo.
        if (accessoUso) {
            try {
                PactumService.avvia(context)
            } catch (e: Exception) {
                // avvio rifiutato: ci riprova il giro dopo
            }
        }

        // Manomissioni per revoca di permessi (tappa 6): rilevate PRIMA di leggere
        // gli eventi da consegnare, così l'eventuale evento parte in questo giro.
        rilevaManomissioniPermessi(context, impostazioni, coda)

        val oggi = LocalDate.now()
        // (0.9) L'uso di oggi letto una volta: lo usano la fotografia e la sentinella.
        val letturaOggi = if (accessoUso) UsageStatsReader(context).leggiGiorno(oggi) else null

        if (letturaOggi != null) {
            // Oggi + IERI: l'uso dopo l'ultima run del giorno andrebbe perso
            // per sempre (di notte il telefono dorme e la run di mezzanotte
            // non arriva). Il server tiene l'ultima fotografia per giorno,
            // quindi rimandare ieri è idempotente; la sostituzione per giorno
            // evita di riempire la coda di fotografie quasi identiche.
            coda.sostituisciUsoGiornaliero(FotografiaUso.evento(context, letturaOggi))
            coda.sostituisciUsoGiornaliero(FotografiaUso.evento(context, oggi.minusDays(1)))
        }

        // Siti visitati (v2.3): stessa filosofia dell'uso — fotografia
        // cumulativa di oggi e di ieri, sostituita in coda a ogni giro.
        // Prima però si guarda se l'osservazione è ancora in piedi (una VPN
        // spenta è un fatto da registrare) e si prova a riaccenderla.
        OsservazioneSiti.rilevaInterruzione(context)
        OsservazioneSiti.riprendiSeConsentita(context)
        if (ReteDns.dnsPrivatoAttivo(context)) {
            // DNS privato cifrato acceso: i nomi non passano più in chiaro.
            // Si dichiara la cecità invece di raccontare una giornata vuota.
            RegistroSiti.dichiaraCieco(context)
        }
        eventoSitiGiornalieri(context, oggi)?.let { coda.sostituisciSitiGiornalieri(it) }
        eventoSitiGiornalieri(context, oggi.minusDays(1))?.let {
            coda.sostituisciSitiGiornalieri(it)
        }

        val postino = PostinoClient(configurazione)

        // Sync del patto (tappa 5) + valutazione locale degli sforamenti PRIMA
        // della consegna: il server è la fonte di verità, la copia locale serve
        // alla sentinella anche offline. In runCatching (come PactumService): un
        // errore qui NON deve saltare battito ed eventi di questo giro.
        runCatching {
            val (letto, codicePatto) = postino.leggiPattoConCodice()
            // (0.13) 401: questo telefono non è più collegato, il blocco si toglie.
            if (codicePatto == 401) ControlloBlocco.scollegato(context)
            letto?.let { patto ->
                PattoLocale(context).salva(patto)
                // Serie e record si aggiornano anche quando l'app resta chiusa:
                // una serie di 12 giorni mai guardata è comunque un record.
                impostazioni.aggiornaSerie(patto.giorniPatto())
            }
            // Il patto è appena stato letto: niente seconda rilettura. L'uso è
            // quello già letto per la fotografia.
            SentinellaPatto(context).valuta(giornata = letturaOggi, rileggiPatto = false)
        }
        // Riserve del servizio: il bonus rimasto a metà e la chiusura della sera.
        runCatching { ConsegnaBonus.recupera(context) }
        runCatching { ChiusuraSerale.controlla(context) }
        // (0.11) E una "Termina la sessione" rimasta senza rete.
        runCatching { ConsegnaSessioni.riprovaSeServe(context, forza = true) }
        // (0.13) Le faccende a schermo spento: le foto in coda, il blocco
        // (il patto appena letto lo porta già: qui la sveglia, l'avviso della
        // partenza e i permessi mancanti durante il blocco).
        runCatching { ConsegnaFoto.riprovaSeServe(context, forza = true) }
        runCatching { ControlloBlocco.dopo(context) }

        // (0.14) Il battito sotto lo stesso lucchetto della sveglia dello
        // stand-by e del servizio: se ne è partito uno da poco, non se ne fa
        // un altro (null = non serviva, va bene così). Poi la sveglia del
        // prossimo, se non ce n'è una in arrivo.
        val battitoOk = BattitoCadenzato.batti(context) != false
        BattitoCadenzato.programma(context)

        val eventi = coda.inAttesa()
        // (0.13) In pacchi più piccoli se il server dice che il corpo è troppo
        // grande: escono dalla coda solo quelli arrivati (o impossibili da mandare).
        val esitoEventi = postino.inviaEventi(eventi)
        coda.rimuoviConsegnati(esitoEventi.consegnati + esitoEventi.scartati)
        val eventiOk = esitoEventi.tutti

        // Le notifiche del figlio (nuova proposta, verdetto, dalla 0.13 le
        // faccende): best effort, il giro dopo recupera le arretrate.
        NovitaDalPatto.avvisa(context, impostazioni, postino)

        // Auto-aggiornamento (tappa 6): in runCatching come il sync del patto —
        // un errore di rete o d'installazione non deve saltare l'esito del giro.
        runCatching {
            val versioni = postino.leggiVersioni()
            Aggiornatore(context).controlla(configurazione, versioni?.figlio)
        }

        return if (battitoOk && eventiOk) Result.success() else Result.retry()
    }

    /**
     * Rileva la REVOCA (dopo l'onboarding) dei due permessi che tengono in piedi
     * il testimone: l'accesso ai dati di utilizzo e le notifiche. Si confronta lo
     * stato attuale con l'ultimo NOTO (persistito): l'evento manomissione nasce
     * solo sulla transizione concesso→revocato, una volta sola. La prima
     * osservazione fissa solo la base (un permesso già assente non è una revoca).
     */
    private suspend fun rilevaManomissioniPermessi(
        context: Context,
        impostazioni: Impostazioni,
        coda: CodaEventi,
    ) {
        val adesso = System.currentTimeMillis()

        // (0.13) L'accesso all'uso (sempre) e "Mostra sopra le altre app"
        // (durante il blocco delle faccende), con il permesso nei dettagli:
        // sotto lo stesso lucchetto del giro delle faccende, niente doppioni.
        PermessiRevocati.controlla(
            context,
            bloccoAttivo = ArchivioBlocco.leggi(context).attivoAdesso(Orologio.adesso()),
            adesso = adesso,
        )

        val notifOra = NotificationManagerCompat.from(context).areNotificationsEnabled()
        val notifNote = impostazioni.leggiNotificheNote()
        if (notifNote == true && !notifOra) {
            // Niente avviso locale: le notifiche sono spente. L'evento va comunque
            // al registro e il genitore lo vede nella finestra (difesa fuori dal telefono).
            coda.accoda(manomissionePermesso("notifiche_disattivate", adesso))
        }
        if (notifNote != notifOra) impostazioni.registraNotificheNote(notifOra)
    }

    private fun manomissionePermesso(sottoTipo: String, adesso: Long): Evento = Evento(
        tipo = TipiEvento.MANOMISSIONE,
        tsDevice = adesso,
        dettagli = buildJsonObject { put("sotto_tipo", sottoTipo) },
    )

    /**
     * (v2.3) Fotografia cumulativa dei SITI di [giorno]: solo domini e quante
     * volte sono stati chiesti. **null** quando non c'è niente da dire — mai
     * una fotografia vuota: un giorno senza dati deve restare "assenza"
     * (`totale_domini: null` lato server), non uno zero finto.
     *
     * `totale_domini` è il conteggio VERO dei domini distinti anche quando la
     * lista è tagliata ai primi 200: se è tagliata si vede, non si finge.
     */
    private fun eventoSitiGiornalieri(context: Context, giorno: LocalDate): Evento? {
        val fotografia = RegistroSiti.fotografia(context, giorno.toString()) ?: return null
        if (fotografia.domini.isEmpty() && !fotografia.dnsCifrato) return null
        // Ordine deterministico (visite decrescenti, poi alfabetico): il taglio
        // ai primi 200 deve cadere sempre sugli stessi, non a caso.
        val ordinati = fotografia.domini.entries
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
            .take(Domini.LIMITE_DOMINI_FOTOGRAFIA)
        return Evento(
            tipo = TipiEvento.SITI_GIORNALIERI,
            tsDevice = System.currentTimeMillis(),
            dettagli = buildJsonObject {
                put("giorno", giorno.toString())
                put("domini", buildJsonObject {
                    ordinati.forEach { put(it.key, JsonPrimitive(it.value)) }
                })
                put("totale_domini", fotografia.domini.size)
                put("dns_cifrato", fotografia.dnsCifrato)
            },
        )
    }

    companion object {
        private const val NOME_LAVORO = "battito"

        /**
         * UPDATE: mantiene il ciclo dei 15 minuti già in corsa (niente riparti
         * da zero a ogni avvio dell'app) ma applica la richiesta nuova — con
         * KEEP un telefono che avesse già il worker in pancia si terrebbe per
         * sempre i vincoli vecchi (es. il vecchio vincolo di rete).
         */
        fun pianifica(context: Context) {
            val richiesta = PeriodicWorkRequestBuilder<BattitoWorker>(15, TimeUnit.MINUTES)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
                .build()
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(NOME_LAVORO, ExistingPeriodicWorkPolicy.UPDATE, richiesta)
        }
    }
}
