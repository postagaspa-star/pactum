package eu.stgm.pactum.figlio

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.compose.viewModel
import eu.stgm.pactum.design.BarraSchede
import eu.stgm.pactum.design.Caricamento
import eu.stgm.pactum.design.VoceBarra
import eu.stgm.pactum.design.attivaBordoPieno
import eu.stgm.pactum.figlio.dati.Impostazioni
import eu.stgm.pactum.figlio.faccende.StatoBlocco
import eu.stgm.pactum.figlio.faccende.VistaFaccende
import eu.stgm.pactum.figlio.permessi.StatoPermessi
import eu.stgm.pactum.figlio.servizio.PactumService
import eu.stgm.pactum.figlio.sessione.ArchivioSessioni
import eu.stgm.pactum.figlio.sessione.PaginaSessioneActivity
import eu.stgm.pactum.figlio.sessione.PagineSessione
import eu.stgm.pactum.figlio.sessione.RichiestaTermine
import eu.stgm.pactum.figlio.ui.CosaVedeScreen
import eu.stgm.pactum.figlio.ui.FaccendeScreen
import eu.stgm.pactum.figlio.ui.ImpostazioniScreen
import eu.stgm.pactum.figlio.ui.Navigazione
import eu.stgm.pactum.figlio.ui.OggiScreen
import eu.stgm.pactum.figlio.ui.OnboardingScreen
import eu.stgm.pactum.figlio.ui.Pagina
import eu.stgm.pactum.figlio.ui.PassoCollegaScreen
import eu.stgm.pactum.figlio.ui.PassoPrimoAvvio
import eu.stgm.pactum.figlio.ui.PrimaRegolaScreen
import eu.stgm.pactum.figlio.ui.ProposteViewModel
import eu.stgm.pactum.figlio.ui.RegoleScreen
import eu.stgm.pactum.figlio.ui.RegoleViewModel
import eu.stgm.pactum.figlio.ui.Scheda
import eu.stgm.pactum.figlio.ui.SessioniScreen
import eu.stgm.pactum.figlio.ui.SitiScreen
import eu.stgm.pactum.figlio.ui.StoricoScreen
import eu.stgm.pactum.figlio.ui.TempoScreen
import eu.stgm.pactum.figlio.ui.rememberBloccoFaccende
import eu.stgm.pactum.figlio.ui.theme.PactumTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import eu.stgm.pactum.design.Sezione

class MainActivity : ComponentActivity() {
    // Dove aprirsi quando si arriva da una notifica locale o da "Apri Pactum"
    // (il segnalibro: Navigazione.ingresso dice la scheda e la pagina).
    // Attività a singleTop: onNewIntent la aggiorna quando l'app è già viva.
    private val destinazioneRichiesta = mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        // (0.15) Bordo pieno con le icone scure della barra di stato (B11): i
        // margini delle barre di sistema li tengono gli Scaffold delle schermate.
        attivaBordoPieno()
        super.onCreate(savedInstanceState)
        // Solo a un avvio vero. Dopo una rotazione (o la morte del processo)
        // savedInstanceState c'è e l'intent è ancora quello della notifica:
        // rileggerlo riporterebbe il ragazzo sulla scheda della notifica. Lo
        // stesso dalla schermata delle app recenti, che ripresenta l'intent vecchio.
        val daRecenti = ((intent?.flags ?: 0) and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY) != 0
        if (savedInstanceState == null && !daRecenti) {
            destinazioneRichiesta.value = prendiDestinazione(intent)
        } else {
            intent?.removeExtra(EXTRA_DESTINAZIONE)
        }
        setContent {
            PactumTheme {
                PactumRoot(
                    destinazioneRichiesta = destinazioneRichiesta.value,
                    onDestinazioneConsumata = { destinazioneRichiesta.value = null },
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        destinazioneRichiesta.value = prendiDestinazione(intent)
    }

    override fun onResume() {
        super.onResume()
        inPrimoPiano = true
        mostraPaginaFineRimasta()
    }

    override fun onPause() {
        inPrimoPiano = false
        super.onPause()
    }

    /**
     * (0.12) La pagina della fine di una Sessione rimasta da vedere (niente
     * "Mostra sopra le altre app", passati i 10 minuti, la notifica toccata):
     * adesso, in Pactum, entro 2 ore dalla fine. "Fatta" la segna la pagina
     * quando arriva sullo schermo: se qui non si apre, si riprova al prossimo
     * ritorno su Pactum. La notifica la toglie la pagina stessa.
     */
    private fun mostraPaginaFineRimasta() {
        lifecycleScope.launch {
            val adesso = System.currentTimeMillis()
            val svolta = withContext(Dispatchers.IO) {
                runCatching { PagineSessione.daMostrare(ArchivioSessioni.leggi(applicationContext).svolte, adesso) }.getOrNull()
            } ?: return@launch
            if (PaginaSessioneActivity.inApertura(svolta.id)) return@launch
            PaginaSessioneActivity.apriFine(this@MainActivity, svolta)
        }
    }

    /** La destinazione della notifica, tolta dall'intent: si usa una volta sola. */
    private fun prendiDestinazione(intent: Intent?): String? {
        val destinazione = intent?.getStringExtra(EXTRA_DESTINAZIONE)
        intent?.removeExtra(EXTRA_DESTINAZIONE)
        // (0.11) "Termina la sessione" dalla notifica fissa: la sessione in
        // corso (in Oggi, o sopra le schermate iniziali) apre la sua conferma.
        // Terminare passa sempre da lì.
        if (destinazione == DEST_TERMINA_SESSIONE) RichiestaTermine.chiedi()
        return destinazione
    }

    companion object {
        /**
         * (0.12) Pactum è davanti adesso: il servizio può aprire la pagina della
         * fine di una sessione anche senza "Mostra sopra le altre app".
         */
        @Volatile
        var inPrimoPiano: Boolean = false
            private set

        // I segnalibri delle notifiche: restano gli stessi valori di sempre (ce
        // ne sono già nella tendina di chi aggiorna). Dove portano lo dice
        // Navigazione.ingresso (0.15).
        const val EXTRA_DESTINAZIONE = "destinazione_iniziale"
        const val DEST_OGGI = "oggi"
        const val DEST_REGOLE = "regole"

        /** (0.15) Le proposte stanno in cima a Regole, in "Da decidere". */
        const val DEST_PROPOSTE = "proposte"

        /** (0.15) L'esito di una dichiarazione: lo Storico, sulle dichiarazioni. */
        const val DEST_DIARIO = "diario"

        /** (0.11) La risposta del genitore a una sessione apre le Sessioni. */
        const val DEST_SESSIONI = "sessioni"

        /** (0.11) "Termina la sessione" della notifica fissa: Oggi, con la conferma aperta. */
        const val DEST_TERMINA_SESSIONE = "termina_sessione"

        /** (0.13) Le faccende: dalla barriera ("Apri Pactum") e dalle loro notifiche. */
        const val DEST_FACCENDE = "faccende"
    }
}

/**
 * (0.15) La navigazione del figlio. Prima i passi del primo avvio, in ordine
 * logico: Collega (solo se il telefono non ha mai salvato un collegamento) →
 * Permessi (finché manca l'accesso ai dati di utilizzo) → "Cosa vedono i tuoi
 * genitori" (una volta) → la prima regola. Poi la barra in basso con quattro
 * schede fisse (Oggi · Regole · Sessioni · Lavori) e, sopra, le pagine che si
 * aprono e si chiudono con Indietro (Impostazioni, Siti, Cosa vedono,
 * Storico, tutte le app di oggi).
 */
@Composable
private fun PactumRoot(
    destinazioneRichiesta: String?,
    onDestinazioneConsumata: () -> Unit,
) {
    val context = LocalContext.current
    val ambito = rememberCoroutineScope()
    val impostazioni = remember { Impostazioni(context.applicationContext) }
    var statoPermessi by remember { mutableStateOf(StatoPermessi.leggi(context)) }

    // Al ritorno dalle Impostazioni di sistema lo stato va riletto.
    LifecycleResumeEffect(Unit) {
        statoPermessi = StatoPermessi.leggi(context)
        onPauseOrDispose { }
    }

    // (0.13) Il blocco delle faccende, letto prima di tutto: con un blocco, con
    // faccende da fare, o arrivando da "Apri Pactum" o da una notifica di
    // faccende, la pagina Faccende viene prima dei passi del primo avvio. Le
    // foto non hanno bisogno né di regole né dell'accesso all'uso: un telefono
    // bloccato deve poter mandare le foto sempre.
    val memoriaBlocco by StatoBlocco.memoria.collectAsStateWithLifecycle()
    val bloccato = rememberBloccoFaccende()
    var arrivoFaccende by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(destinazioneRichiesta) {
        if (destinazioneRichiesta == MainActivity.DEST_FACCENDE) arrivoFaccende = true
    }
    val faccendePrima = VistaFaccende.primaDelResto(bloccato, memoriaBlocco, arrivoFaccende)

    // Pactum parte appena c'è l'accesso ai dati di utilizzo, come sempre: anche
    // se il telefono non è ancora collegato (il passo Collega viene prima).
    LaunchedEffect(statoPermessi.accessoUso) {
        if (statoPermessi.accessoUso) PactumService.avvia(context)
    }

    // null = non ancora letto dal disco: niente lampi di schermate sbagliate.
    val configurazione by impostazioni.configurazione.collectAsState(initial = null)
    val cosaVedeVista by impostazioni.cosaVedeVista.collectAsState(initial = null)
    val config = configurazione
    val vista = cosaVedeVista
    if (config == null || vista == null) {
        AttesaAvvio()
        return
    }

    // 1. Collega: solo se il telefono non ha mai salvato un collegamento. Uno
    // già collegato, anche col collegamento non più valido, non torna qui.
    if (!config.completa) {
        PassoPrimoAvvio(faccendePrima, R.string.faccende_poi_collega) { PassoCollegaScreen() }
        return
    }

    // 2. I permessi: finché manca l'accesso ai dati di utilizzo (gli altri tre
    // non fermano: si ritrovano in Oggi e nelle Impostazioni).
    if (!statoPermessi.accessoUso) {
        PassoPrimoAvvio(faccendePrima, R.string.faccende_poi_permessi) {
            OnboardingScreen(
                statoPermessi = statoPermessi,
                onAggiorna = { statoPermessi = StatoPermessi.leggi(context) },
            )
        }
        return
    }

    // 3. Una volta: cosa arriva ai genitori e cosa no, ora che si sa a chi.
    if (!vista) {
        PassoPrimoAvvio(faccendePrima, R.string.faccende_poi_cosa_vede) {
            CosaVedeScreen(onHoCapito = { ambito.launch { impostazioni.registraCosaVedeVista() } })
        }
        return
    }

    // Il nome della scheda, non l'enum: uno stato salvato da una versione con
    // altre schede (PROPOSTE, DIARIO, FACCENDE) diventa la scheda giusta.
    var nomeScheda by rememberSaveable { mutableStateOf(Scheda.OGGI.name) }
    val scheda = Navigazione.schedaSalvata(nomeScheda)
    // Le pagine aperte sopra le schede, come testo: sopravvivono a una rotazione.
    var testoPila by rememberSaveable { mutableStateOf("") }
    val pila = Navigazione.pilaDaTesto(testoPila)
    // Ogni scheda e ogni pagina tiene il suo stato (scorrimento, sezioni aperte)
    // anche quando si cambia scheda o si apre una pagina sopra.
    val stati = rememberSaveableStateHolder()
    // (0.15) "Da decidere" in cima: dalla notifica di una proposta.
    var richiestaInCima by rememberSaveable { mutableIntStateOf(0) }

    fun vaiA(nuova: Scheda) {
        nomeScheda = nuova.name
    }
    fun apri(pagina: Pagina) {
        testoPila = Navigazione.pilaInTesto(pila + pagina)
    }
    fun chiudi() {
        val chiusa = pila.lastOrNull() ?: return
        stati.removeState("pagina-${chiusa.name}")
        testoPila = Navigazione.pilaInTesto(pila.dropLast(1))
    }

    // Arrivo da una notifica: la scheda (e la pagina) giusta, una volta sola,
    // anche se sopra c'era un'altra pagina: per questo sta PRIMA dei return
    // delle pagine. Un segnalibro che non si conosce apre Oggi.
    LaunchedEffect(destinazioneRichiesta) {
        if (destinazioneRichiesta != null) {
            val ingresso = Navigazione.ingresso(destinazioneRichiesta)
            Navigazione.pilaDaTesto(testoPila).forEach { stati.removeState("pagina-${it.name}") }
            nomeScheda = ingresso.scheda.name
            testoPila = Navigazione.pilaInTesto(listOfNotNull(ingresso.pagina))
            if (ingresso.inCima) richiestaInCima++
            onDestinazioneConsumata()
        }
    }

    // Gate della prima regola (concept.md: almeno una regola obbligatoria). Lo
    // stesso RegoleViewModel dell'Activity serve il gate e la scheda Regole.
    val regoleVm: RegoleViewModel = viewModel()
    val statoRegole by regoleVm.stato.collectAsStateWithLifecycle()
    // Le proposte del genitore in attesa danno il numero sulla scheda Regole:
    // stesso ViewModel (dell'Activity) che usa la scheda. Contano solo quelle a
    // cui deve rispondere il figlio, non le sue che aspettano il genitore.
    val proposteVm: ProposteViewModel = viewModel()
    val statoProposte by proposteVm.stato.collectAsStateWithLifecycle()
    LifecycleResumeEffect(Unit) {
        regoleVm.aggiorna()
        proposteVm.aggiorna()
        onPauseOrDispose { }
    }
    // Dopo un collegamento (codice di 6 cifre o codice lungo) regole e proposte
    // sono di un altro collegamento: si rileggono appena cambia, anche se chi
    // l'aveva avviato non c'è più (Impostazioni chiuse a metà, schermata
    // ricreata). Il primo valore è quello di adesso: non conta come cambio.
    LaunchedEffect(Unit) {
        impostazioni.configurazione.distinctUntilChanged().drop(1).collect {
            regoleVm.aggiorna()
            proposteVm.aggiorna()
        }
    }

    // Indietro: chiude la pagina in cima; da una scheda diversa da Oggi torna a
    // Oggi; da Oggi esce (BackHandler spento: decide Android). Davanti al gate
    // della prima regola le schede non si vedono: lì Indietro esce, come prima.
    val gatePrimaRegola = statoRegole.regole.isEmpty() && statoRegole.regoleAltrove == 0
    BackHandler(enabled = pila.isNotEmpty() || (scheda != Scheda.OGGI && !gatePrimaRegola)) {
        val dopo = Navigazione.indietro(scheda, pila) ?: return@BackHandler
        if (dopo.second.size < pila.size) chiudi() else vaiA(dopo.first)
    }

    // Le pagine sopra a tutto: prima del gate della prima regola, così un
    // collegamento cambiato dalle Impostazioni non le toglie di mezzo.
    val paginaInCima = pila.lastOrNull()
    if (paginaInCima != null) {
        stati.SaveableStateProvider("pagina-${paginaInCima.name}") {
            when (paginaInCima) {
                Pagina.IMPOSTAZIONI, Pagina.IMPOSTAZIONI_PERMESSI -> ImpostazioniScreen(
                    onChiudi = {
                        chiudi()
                        regoleVm.aggiorna()
                    },
                    onApriCosaVede = { apri(Pagina.COSA_VEDE) },
                    onApriSiti = { apri(Pagina.SITI) },
                    suiPermessi = paginaInCima == Pagina.IMPOSTAZIONI_PERMESSI,
                )
                Pagina.SITI -> SitiScreen(onChiudi = { chiudi() })
                Pagina.COSA_VEDE -> CosaVedeScreen(onChiudi = { chiudi() })
                Pagina.STORICO, Pagina.STORICO_DICHIARAZIONI -> StoricoScreen(
                    onChiudi = { chiudi() },
                    sulleDichiarazioni = paginaInCima == Pagina.STORICO_DICHIARAZIONI,
                )
                Pagina.TEMPO -> TempoScreen(onChiudi = { chiudi() })
            }
        }
        return
    }

    // 4. Finché il patto non ha nemmeno una regola, prima si crea quella: è il
    // figlio a scrivere il patto. Creata la prima, il server vieta di togliere
    // l'ultima, così il gate non torna; offline la copia locale già
    // sincronizzata basta a superarlo. (v3) Se il figlio ha già regole su un
    // altro dispositivo (il computer), il gate non serve; senza rete vale
    // l'ultimo numero saputo (RegoleViewModel), non uno zero finto.
    //
    // L'attesa solo alla PRIMA lettura. Una rilettura (a ogni ritorno in primo
    // piano) non deve togliere di mezzo il gate: via la regola a metà nel dialogo.
    if (gatePrimaRegola) {
        if (!statoRegole.letto) {
            AttesaAvvio()
        } else {
            PassoPrimoAvvio(faccendePrima, R.string.faccende_poi_prima_regola) { PrimaRegolaScreen(vm = regoleVm) }
        }
        return
    }

    Scaffold(
        // (0.19) Sotto la barra di stato di Android il colore della scheda: la
        // sfumatura della sezione parte da lì.
        containerColor = sezioneDi(scheda).fondo,
        bottomBar = {
            // (0.15) Quattro schede fisse, etichette sempre su una riga (B1).
            BarraSchede(
                voci = Scheda.entries.map { voce ->
                    val inAttesa = when (voce) {
                        // Le proposte del genitore a cui rispondere.
                        Scheda.REGOLE -> statoProposte.pendenti
                        // (0.13) Quanti lavori di casa ci sono da fare.
                        // (0.18) Solo quelli da fare: le foto che aspettano l'approvazione no.
                        Scheda.LAVORI -> memoriaBlocco.daFare.count { !it.aspettaApprovazione }
                        else -> 0
                    }
                    VoceBarra(
                        etichetta = stringResource(etichettaScheda(voce)),
                        icona = painterResource(iconaScheda(voce)),
                        badge = inAttesa.takeIf { it > 0 },
                        descrizioneBadge = when (voce) {
                            Scheda.REGOLE -> pluralStringResource(R.plurals.badge_proposte, inAttesa, inAttesa)
                            Scheda.LAVORI -> pluralStringResource(R.plurals.badge_lavori, inAttesa, inAttesa)
                            else -> null
                        },
                        sezione = sezioneDi(voce),
                    )
                },
                selezionata = scheda.ordinal,
                onSeleziona = { indice -> Scheda.entries.getOrNull(indice)?.let { vaiA(it) } },
            )
        },
    ) { padding ->
        // consumeWindowInsets: il padding dello Scaffold esterno copre già le
        // barre di sistema; senza consumarlo, le TopAppBar degli Scaffold interni
        // riapplicherebbero l'inset della status bar (doppio spazio).
        Box(modifier = Modifier.padding(padding).consumeWindowInsets(padding).fillMaxSize()) {
            stati.SaveableStateProvider(scheda.name) {
                when (scheda) {
                    Scheda.OGGI -> OggiScreen(
                        onApriImpostazioni = { apri(Pagina.IMPOSTAZIONI) },
                        onApriPermessi = { apri(Pagina.IMPOSTAZIONI_PERMESSI) },
                        onApriLavori = { vaiA(Scheda.LAVORI) },
                        onApriTempo = { apri(Pagina.TEMPO) },
                    )
                    Scheda.REGOLE -> RegoleScreen(
                        onApriImpostazioni = { apri(Pagina.IMPOSTAZIONI) },
                        onApriStorico = { apri(Pagina.STORICO) },
                        richiestaInCima = richiestaInCima,
                    )
                    Scheda.SESSIONI -> SessioniScreen(onApriImpostazioni = { apri(Pagina.IMPOSTAZIONI) })
                    Scheda.LAVORI -> FaccendeScreen(onApriImpostazioni = { apri(Pagina.IMPOSTAZIONI) })
                }
            }
        }
    }
}

private fun iconaScheda(scheda: Scheda): Int = when (scheda) {
    Scheda.OGGI -> R.drawable.ic_scheda_oggi
    Scheda.REGOLE -> R.drawable.ic_scheda_regole
    Scheda.SESSIONI -> R.drawable.ic_scheda_sessioni
    Scheda.LAVORI -> R.drawable.ic_scheda_faccende
}

private fun etichettaScheda(scheda: Scheda): Int = when (scheda) {
    Scheda.OGGI -> R.string.scheda_oggi
    Scheda.REGOLE -> R.string.scheda_regole
    Scheda.SESSIONI -> R.string.scheda_sessioni
    Scheda.LAVORI -> R.string.scheda_faccende
}

/**
 * (0.15) Mentre si legge dal disco (pochi millisecondi): il fondo dell'app e
 * basta, niente lampo né rotella che compare e sparisce. Solo se l'attesa
 * dura, la rotella.
 */
@Composable
private fun AttesaAvvio() {
    var lunga by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        delay(600)
        lunga = true
    }
    Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        if (lunga) Caricamento()
    }
}

/** (0.19) Il colore e gli adesivi di ogni scheda. */
internal fun sezioneDi(scheda: Scheda): Sezione = when (scheda) {
    Scheda.OGGI -> Sezione.OGGI
    Scheda.REGOLE -> Sezione.REGOLE
    Scheda.SESSIONI -> Sezione.SESSIONI
    Scheda.LAVORI -> Sezione.LAVORI
}
