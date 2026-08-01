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
import eu.stgm.pactum.figlio.BuildConfig
import eu.stgm.pactum.figlio.R
import eu.stgm.pactum.figlio.aggiornamento.Aggiornatore
import eu.stgm.pactum.figlio.catalogo.CatalogoApp
import eu.stgm.pactum.figlio.dati.AncoraTempo
import eu.stgm.pactum.figlio.dati.Battito
import eu.stgm.pactum.figlio.dati.CodaEventi
import eu.stgm.pactum.figlio.dati.Evento
import eu.stgm.pactum.figlio.dati.Impostazioni
import eu.stgm.pactum.figlio.dati.PattoLocale
import eu.stgm.pactum.figlio.dati.TipiEvento
import eu.stgm.pactum.figlio.misura.UsageStatsReader
import eu.stgm.pactum.figlio.notifiche.AvvisiLocali
import eu.stgm.pactum.figlio.permessi.PermessiHelper
import eu.stgm.pactum.figlio.rete.PostinoClient
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

        // Manomissioni per revoca di permessi (tappa 6): rilevate PRIMA di leggere
        // gli eventi da consegnare, così l'eventuale evento parte in questo giro.
        rilevaManomissioniPermessi(context, impostazioni, coda)

        val oggi = LocalDate.now()

        if (PermessiHelper.haAccessoUso(context)) {
            // Oggi + IERI: l'uso dopo l'ultima run del giorno andrebbe perso
            // per sempre (di notte il telefono dorme e la run di mezzanotte
            // non arriva). Il server tiene l'ultima fotografia per giorno,
            // quindi rimandare ieri è idempotente; la sostituzione per giorno
            // evita di riempire la coda di fotografie quasi identiche.
            coda.sostituisciUsoGiornaliero(eventoUsoGiornaliero(context, oggi))
            coda.sostituisciUsoGiornaliero(eventoUsoGiornaliero(context, oggi.minusDays(1)))
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
            postino.leggiPatto()?.let { PattoLocale(context).salva(it) }
            SentinellaPatto(context).valuta()
        }

        val battitoOk = postino.inviaBattito(
            Battito(
                tsDevice = System.currentTimeMillis(),
                versioneApp = BuildConfig.VERSION_NAME,
                elapsedRealtime = SystemClock.elapsedRealtime(),
            ),
        )
        if (battitoOk) impostazioni.registraBattitoConsegnato()

        val eventi = coda.inAttesa()
        val eventiOk = postino.inviaEventi(eventi)
        if (eventiOk) coda.rimuoviConsegnati(eventi)

        // Le notifiche del figlio (nuova proposta, verdetto): best effort,
        // il giro dopo recupera le arretrate.
        avvisaNovitaDelPatto(context, impostazioni, postino)

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

        val usoOra = PermessiHelper.haAccessoUso(context)
        val usoNoto = impostazioni.leggiAccessoUsoNoto()
        if (usoNoto == true && !usoOra) {
            coda.accoda(manomissionePermesso("permesso_revocato", adesso))
            // Le notifiche sono ancora attive (è l'accesso all'uso a mancare):
            // un promemoria gentile aiuta a rimettere a posto il patto.
            AvvisiLocali.avvisa(
                context,
                id = AvvisiLocali.ID_MANOMISSIONE_PERMESSO,
                titolo = context.getString(R.string.notifica_permesso_revocato_titolo),
                testo = context.getString(R.string.notifica_permesso_revocato_testo),
            )
        }
        if (usoNoto != usoOra) impostazioni.registraAccessoUsoNoto(usoOra)

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
     * Alza una notifica locale per ogni notifica del server mai avvisata prima
     * (il GET col token del figlio restituisce solo le sue: nuove proposte,
     * verdetti) e poi le marca lette sul server. Senza permesso non si avvisa E
     * non si segna né marca: appena il permesso arriva, il giro successivo
     * recupera (marcare prima di avvisare perderebbe l'avviso).
     */
    private suspend fun avvisaNovitaDelPatto(
        context: Context,
        impostazioni: Impostazioni,
        postino: PostinoClient,
    ) {
        val notifiche = postino.leggiNotifiche() ?: return
        if (notifiche.isEmpty()) return
        if (!AvvisiLocali.puoAvvisare(context)) return

        val giaAvvisate = impostazioni.leggiIdAvvisati()
        val nuove = notifiche.filter { it.id !in giaAvvisate }
        nuove.forEach { notifica ->
            AvvisiLocali.avvisa(
                context,
                // Id con offset: l'id grezzo del server collide con la notifica
                // fissa del testimone (FGS id 1), che verrebbe sostituita.
                id = AvvisiLocali.idNotificaServer(notifica.id),
                titolo = AvvisiLocali.titoloTipo(context, notifica.tipo),
                testo = notifica.messaggio,
                destinazione = AvvisiLocali.destinazioneTipo(notifica.tipo),
            )
        }
        if (nuove.isNotEmpty()) impostazioni.registraIdAvvisati(nuove.map { it.id })

        // Marcate lette sul server (best effort, idempotente): senza, il server
        // accumula le non lette all'infinito e, oltre il tetto locale di 500 id,
        // il figlio si ri-avviserebbe le vecchie. Il giro dopo riprova le fallite.
        notifiche.forEach { postino.marcaNotificaLetta(it.id) }
    }

    /**
     * Fotografia cumulativa dell'uso di [giorno]. Il server, ricevendo più
     * fotografie dello stesso giorno, tiene l'ultima: idempotente per design.
     *
     * (v2.2) La fotografia porta anche `nomi` (etichette leggibili: solo il
     * telefono del figlio può risolvere i pacchetti) e `uso_categorie` (totali
     * per categoria col mapping interno di CatalogoApp — LO STESSO che
     * SentinellaPatto dà in pasto al valutatore, così "categoria:social" nella
     * finestra e nel valutatore contano le stesse app).
     */
    private fun eventoUsoGiornaliero(context: Context, giorno: LocalDate): Evento {
        // Filtrata una volta sola, prima di costruire il JSON: così totale,
        // per-app, nomi e categorie raccontano tutti la stessa storia.
        val uso = UsageStatsReader(context).usoDelGiorno(giorno)
            .filter { CatalogoApp.contaNellUso(context, it.pacchetto) }
        return Evento(
            tipo = TipiEvento.USO_GIORNALIERO,
            tsDevice = System.currentTimeMillis(),
            dettagli = buildJsonObject {
                put("giorno", giorno.toString())
                put("uso_minuti", buildJsonObject {
                    uso.forEach { put(it.pacchetto, JsonPrimitive(it.millisPrimoPiano / 60_000)) }
                })
                // Totale del giorno dai millisecondi veri, non dalla somma dei
                // minuti arrotondati per app (contratto-api.md: totale_minuti).
                put("totale_minuti", uso.sumOf { it.millisPrimoPiano } / 60_000)
                // Solo etichette risolte per i pacchetti presenti in uso_minuti:
                // se il pacchetto non si risolve, il server ripiega da solo sul
                // nome pacchetto (contratto: uso_recente).
                put("nomi", buildJsonObject {
                    uso.forEach {
                        val etichetta = CatalogoApp.etichettaValore(context, it.pacchetto)
                        if (etichetta != it.pacchetto) put(it.pacchetto, JsonPrimitive(etichetta))
                    }
                })
                // Per categoria: somma dei minuti arrotondati per app, così il
                // totale di una categoria torna con le sue app in uso_minuti
                // (stesso arrotondamento per-app di SentinellaPatto).
                put("uso_categorie", buildJsonObject {
                    uso.groupBy { CatalogoApp.categoriaDiPacchetto(context, it.pacchetto) }
                        .forEach { (categoria, usi) ->
                            put(categoria, JsonPrimitive(usi.sumOf { it.millisPrimoPiano / 60_000 }))
                        }
                })
            },
        )
    }

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
