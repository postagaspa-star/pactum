package eu.stgm.pactum.genitore.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import eu.stgm.pactum.design.ColoriPatto
import eu.stgm.pactum.design.GiornoPatto
import eu.stgm.pactum.design.Spazi
import eu.stgm.pactum.design.StrisciaGiorni
import eu.stgm.pactum.design.contaGiorni
import eu.stgm.pactum.genitore.R
import eu.stgm.pactum.genitore.dati.BonusGiorno
import eu.stgm.pactum.genitore.dati.EventoFinestra
import eu.stgm.pactum.genitore.dati.Finestra
import eu.stgm.pactum.genitore.dati.ModificaStorico
import eu.stgm.pactum.genitore.dati.RegolaFinestra
import eu.stgm.pactum.genitore.dati.StatoBonus
import eu.stgm.pactum.genitore.dati.StatoSilenzio
import eu.stgm.pactum.genitore.dati.TipiRegola
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

// La finestra ha UN protagonista: il patto (tavola rotonda D1). Quanto ha usato
// il telefono vive nella scheda Tempo. Tre livelli, dall'alto:
//  1. il patto — la scheda eroe con la striscia aggregata, poi le regole;
//  2. da guardare insieme — fuori regola e buchi nel registro, fusi;
//  3. la storia — dietro un tocco, chiusa di default.
// I colori del patto vivono in core-design (`ColoriPatto`): un solo rosso, in un
// solo posto — dentro la striscia degli 8 giorni.

/** Ogni quanto si rilegge la finestra mentre la schermata è in primo piano. */
private const val INTERVALLO_RILETTURA_MS = 60_000L

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FinestraScreen(
    notificheNonLette: Int,
    onApriNotifiche: () -> Unit,
    vm: FinestraViewModel = viewModel(),
) {
    val stato by vm.stato.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    // Prima lettura a ogni ritorno in primo piano, poi rilettura periodica
    // finché la schermata resta visibile: "in contatto" non può restare fermo
    // per ore su un telefono lasciato acceso.
    val cicloVita = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(cicloVita) {
        cicloVita.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                vm.aggiorna()
                delay(INTERVALLO_RILETTURA_MS)
            }
        }
    }

    val messaggioMandato = stringResource(R.string.segno_mandato)
    val messaggioGiaMandato = stringResource(R.string.segno_gia_mandato_avviso)
    val messaggioFallito = stringResource(R.string.segno_fallito)
    val ambito = rememberCoroutineScope()
    LaunchedEffect(stato.esitoSegno) {
        val messaggio = when (stato.esitoSegno) {
            FinestraViewModel.EsitoSegno.MANDATO -> messaggioMandato
            FinestraViewModel.EsitoSegno.GIA_MANDATO -> messaggioGiaMandato
            FinestraViewModel.EsitoSegno.FALLITO -> messaggioFallito
            null -> return@LaunchedEffect
        }
        // Consumato subito, e la conferma parte in uno scope suo: consumare
        // cambia la chiave e cancellerebbe questo effetto a metà snackbar.
        vm.consumaEsitoSegno()
        ambito.launch { snackbarHostState.showSnackbar(messaggio) }
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0.dp),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.finestra_titolo)) },
                actions = {
                    PulsanteNotifiche(notificheNonLette, onApriNotifiche)
                    IconButton(onClick = { vm.aggiorna() }) {
                        Icon(Icons.Filled.Refresh, stringResource(R.string.azione_aggiorna))
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        val finestra = stato.finestra
        Box(modifier = Modifier.padding(padding).fillMaxSize()) {
            when {
                stato.caricamento && finestra == null ->
                    Caricamento(stringResource(R.string.finestra_caricamento))

                stato.configurazioneMancante -> Centro {
                    StatoPrimaApertura(
                        titolo = stringResource(R.string.config_mancante_titolo),
                        testo = stringResource(R.string.finestra_config_mancante),
                        centrato = true,
                        modifier = Modifier.padding(horizontal = Spazi.xxl),
                    )
                }

                finestra == null -> Centro {
                    TestoCentrato(stringResource(R.string.finestra_errore_nessun_dato))
                }

                else -> ContenutoFinestra(
                    finestra = finestra,
                    mostraErrore = stato.errore,
                    ricevutaAlle = stato.ricevutaAlle,
                    segnoSpento = segnoGiaMandato(
                        finestra.segnoOggi,
                        stato.segnoMandatoIl,
                        LocalDate.now(),
                    ),
                    invioSegno = stato.invioSegno,
                    onMandaSegno = { vm.mandaSegno() },
                )
            }
        }
    }
}

/** La campanella delle notifiche non lette: le notifiche si aprono da qui. */
@Composable
private fun PulsanteNotifiche(nonLette: Int, onClick: () -> Unit) {
    val descrizione = if (nonLette > 0) {
        pluralStringResource(R.plurals.notifiche_non_lette, nonLette, nonLette)
    } else {
        stringResource(R.string.notifiche_titolo)
    }
    IconButton(onClick = onClick) {
        BadgedBox(
            badge = {
                if (nonLette > 0) Badge { Text(testoBadge(nonLette)) }
            },
        ) {
            Icon(Icons.Outlined.Notifications, contentDescription = descrizione)
        }
    }
}

@Composable
private fun ContenutoFinestra(
    finestra: Finestra,
    mostraErrore: Boolean,
    ricevutaAlle: Instant?,
    segnoSpento: Boolean,
    invioSegno: Boolean,
    onMandaSegno: () -> Unit,
) {
    // Per raccontare storico ed eventi serve la regola: la finestra porta TUTTE
    // le regole (anche eliminate), quindi la mappa è completa.
    val regolePerId = finestra.regole.associateBy { it.id }
    val giorni = remember(finestra.striscia) { giorniDaQuadretti(finestra.striscia) }
    val riepilogo = remember(finestra) {
        riepilogoPatto(
            giorni = giorni,
            sforamenti = finestra.sforamentiRecenti,
            manomissioni = finestra.manomissioniRecenti,
            zona = ZoneId.systemDefault(),
            oggi = LocalDate.now(),
        )
    }
    val daGuardare = remember(finestra) {
        daGuardareInsieme(finestra.sforamentiRecenti, finestra.manomissioniRecenti)
    }

    var mostraIntro by rememberSaveable { mutableStateOf(true) }
    var tuttiDaGuardare by rememberSaveable { mutableStateOf(false) }
    var storiaAperta by rememberSaveable { mutableStateOf(false) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(Spazi.l),
        verticalArrangement = Arrangement.spacedBy(Spazi.m),
    ) {
        if (mostraErrore) {
            item {
                RigaDatiVecchi(
                    ricevutaAlle?.let {
                        stringResource(R.string.dati_fermi_alle, oraOppureDataOra(it))
                    } ?: stringResource(R.string.finestra_errore),
                )
            }
        }

        item { RigaStato(finestra.statoSilenzio, ricevutaAlle) }

        // La cornice: cos'è Pactum e perché non impone lui le regole.
        // Richiudibile: dopo averla letta non ingombra più.
        if (mostraIntro) {
            item { CardIntro(onChiudi = { mostraIntro = false }) }
        }

        // --- 1. Il patto --------------------------------------------------------
        if (finestra.regole.isEmpty()) {
            item {
                StatoPrimaApertura(
                    titolo = stringResource(R.string.regole_vuoto_titolo),
                    testo = stringResource(R.string.regole_vuoto),
                    modifier = Modifier.padding(vertical = Spazi.l),
                )
            }
        } else {
            item {
                SchedaPatto(
                    giorni = giorni,
                    riepilogo = riepilogo,
                    segnoSpento = segnoSpento,
                    invioSegno = invioSegno,
                    onMandaSegno = onMandaSegno,
                    // xxl tra l'eroe e il resto (§3.3): lo spacedBy ne mette già m.
                    modifier = Modifier.padding(bottom = Spazi.xxl - Spazi.m),
                )
            }
            item { TitoloSezione(stringResource(R.string.sezione_regole)) }
            items(finestra.regole, key = { "regola-${it.id}" }) {
                SchedaRegola(it, finestra.bonus)
            }
            // I minuti bonus degli 8 giorni sono globali, non di una regola:
            // stanno in coda alle regole, e solo se ce n'è stato almeno uno.
            if (finestra.bonusGiornalieri.any { it.minuti > 0 }) {
                item { StrisciaBonus(finestra.bonusGiornalieri) }
            }
        }

        // --- 2. Da guardare insieme ---------------------------------------------
        // Vuota = non esiste: il "tutto bene" lo dice già la riga di riepilogo.
        if (daGuardare.isNotEmpty()) {
            item { TitoloSezione(stringResource(R.string.sezione_da_guardare)) }
            val visibili = if (tuttiDaGuardare) {
                daGuardare
            } else {
                daGuardare.take(VOCI_DA_GUARDARE_VISIBILI)
            }
            item {
                ListaRighe(visibili) { RigaDaGuardare(it, regolePerId) }
            }
            val nascoste = daGuardare.size - VOCI_DA_GUARDARE_VISIBILI
            if (nascoste > 0) {
                item {
                    TextButton(onClick = { tuttiDaGuardare = !tuttiDaGuardare }) {
                        Text(
                            if (tuttiDaGuardare) {
                                stringResource(R.string.da_guardare_meno)
                            } else {
                                pluralStringResource(R.plurals.da_guardare_altri, nascoste, nascoste)
                            },
                        )
                    }
                }
            }
        }

        // --- 3. La storia -------------------------------------------------------
        if (finestra.storicoModifiche.isNotEmpty()) {
            item {
                TextButton(onClick = { storiaAperta = !storiaAperta }) {
                    Text(stringResource(R.string.sezione_storico))
                    Icon(
                        imageVector = if (storiaAperta) {
                            Icons.Filled.KeyboardArrowUp
                        } else {
                            Icons.Filled.KeyboardArrowDown
                        },
                        contentDescription = null,
                        modifier = Modifier.padding(start = Spazi.xs),
                    )
                }
            }
            if (storiaAperta) {
                item {
                    ListaRighe(finestra.storicoModifiche) { RigaStorico(it, regolePerId) }
                }
            }
        }
    }
}

/**
 * La cornice per il genitore: cos'è Pactum e perché non impone lui le regole.
 * È la spiegazione che finora esisteva solo nell'app del figlio. Richiudibile.
 */
@Composable
private fun CardIntro(onChiudi: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(Spazi.l)) {
            Text(
                text = stringResource(R.string.intro_titolo),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            Spacer(Modifier.height(Spazi.s))
            Text(
                text = stringResource(R.string.intro_testo),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            Spacer(Modifier.height(Spazi.s))
            TextButton(
                onClick = onChiudi,
                modifier = Modifier.align(Alignment.End),
            ) {
                Text(stringResource(R.string.intro_chiudi))
            }
        }
    }
}

/**
 * Lo stato del canale col figlio, deciso dal flag `silente` del SERVER.
 *
 * La normalità non grida: "in contatto" è UNA riga (pallino + testo + età del
 * dato), niente card. L'anomalia occupa spazio: solo il silenzio è una Card
 * piena — nel grigio-blu del canale muto, MAI un rosso del patto, perché nove
 * volte su dieci è batteria scarica o rete.
 */
@Composable
private fun RigaStato(statoSilenzio: StatoSilenzio, ricevutaAlle: Instant?) {
    val istante = istanteServer(statoSilenzio.ultimoBattito)
    // L'età del dato: un "In contatto" senza data di raccolta sembrerebbe il
    // presente anche quando non lo è.
    val eta = ricevutaAlle?.let {
        stringResource(R.string.finestra_aggiornata_alle, oraOppureDataOra(it))
    }

    if (statoSilenzio.silente) {
        val inchiostro = ColoriPatto.InchiostroSuSilenzio
        Card(
            colors = CardDefaults.cardColors(containerColor = ColoriPatto.Silenzio),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(modifier = Modifier.padding(Spazi.l)) {
                Icon(
                    imageVector = Icons.Filled.Warning,
                    contentDescription = null,
                    tint = inchiostro,
                    modifier = Modifier.size(24.dp),
                )
                Column(modifier = Modifier.padding(start = Spazi.m)) {
                    Text(
                        text = if (istante != null) {
                            stringResource(R.string.silenzio_allarme, oraOppureDataOra(istante))
                        } else {
                            stringResource(R.string.silenzio_mai)
                        },
                        style = MaterialTheme.typography.titleMedium,
                        color = inchiostro,
                    )
                    Text(
                        text = stringResource(R.string.silenzio_spiega),
                        style = MaterialTheme.typography.bodyMedium,
                        color = inchiostro,
                        modifier = Modifier.padding(top = Spazi.xs),
                    )
                    if (eta != null) {
                        Text(
                            text = eta,
                            style = MaterialTheme.typography.labelSmall,
                            color = inchiostro,
                            modifier = Modifier.padding(top = Spazi.xs),
                        )
                    }
                }
            }
        }
    } else {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .background(MaterialTheme.colorScheme.primary, CircleShape),
            )
            Text(
                text = stringResource(
                    R.string.silenzio_in_contatto,
                    istante?.let { oraOppureDataOra(it) } ?: "—",
                ),
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier
                    .weight(1f)
                    .padding(start = Spazi.s),
            )
            if (eta != null) {
                Text(
                    text = eta,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = Spazi.s),
                )
            }
        }
    }
}

/**
 * La scheda EROE: com'è andata la parola data negli ultimi 8 giorni.
 * Il numero grande è il conteggio dei fatti ("6 su 7"), la stessa striscia che
 * vede il figlio, e una riga che dice a parole ciò che la striscia disegna.
 * Nessuna serie: la serie è del figlio, non di chi guarda (tavola rotonda C6).
 *
 * Senza `striscia` (server vecchio) la scheda non va in errore: restano la riga
 * di riepilogo e il segno.
 */
@Composable
private fun SchedaPatto(
    giorni: List<GiornoPatto>,
    riepilogo: RiepilogoPatto,
    segnoSpento: Boolean,
    invioSegno: Boolean,
    onMandaSegno: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(modifier = Modifier.padding(Spazi.l)) {
            SopraTitolo(stringResource(R.string.patto_ultimi_giorni))

            if (giorni.isNotEmpty()) {
                val (mantenuti, conDati) = contaGiorni(giorni)
                val senzaDati = giorni.size - conDati
                Spacer(Modifier.height(Spazi.s))
                if (conDati > 0) {
                    Text(
                        text = stringResource(R.string.patto_su, mantenuti, conDati),
                        style = MaterialTheme.typography.displaySmall,
                    )
                    Text(
                        text = pluralStringResource(R.plurals.patto_giorni_dentro, conDati),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    if (senzaDati > 0) {
                        Text(
                            text = pluralStringResource(
                                R.plurals.patto_senza_dati,
                                senzaDati,
                                senzaDati,
                            ),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = Spazi.xs),
                        )
                    }
                } else {
                    Text(
                        text = stringResource(R.string.patto_nessun_giorno_con_dati),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.height(Spazi.m))
                StrisciaGiorni(
                    giorni = giorni,
                    lato = 32.dp,
                    descrizione = descrizioneStriscia(giorni),
                )
            }

            Spacer(Modifier.height(Spazi.m))
            Text(
                text = testoRiepilogo(riepilogo),
                style = MaterialTheme.typography.bodyMedium,
            )

            // Il gesto non poliziesco: un riconoscimento a testo fisso, uno al
            // giorno. Il genitore sa prima che cosa arriva al figlio.
            Spacer(Modifier.height(Spazi.s))
            TextButton(
                onClick = onMandaSegno,
                enabled = !segnoSpento && !invioSegno,
            ) {
                Text(
                    if (segnoSpento) {
                        stringResource(R.string.segno_gia_mandato)
                    } else {
                        stringResource(R.string.segno_manda)
                    },
                )
            }
            Text(
                text = stringResource(R.string.segno_cosa_arriva),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** La frase che TalkBack legge al posto dei singoli quadretti. */
@Composable
private fun descrizioneStriscia(giorni: List<GiornoPatto>): String {
    val (mantenuti, conDati) = contaGiorni(giorni)
    val senzaDati = giorni.size - conDati
    val base = pluralStringResource(R.plurals.striscia_descrizione, mantenuti, mantenuti, conDati)
    return if (senzaDati > 0) {
        base + ", " + pluralStringResource(R.plurals.patto_senza_dati, senzaDati, senzaDati)
    } else {
        base
    }
}

/** "Nessun giorno fuori regola · registro completo", oppure i conti. */
@Composable
private fun testoRiepilogo(riepilogo: RiepilogoPatto): String {
    val fuori = riepilogo.giorniFuoriRegola
    val buchi = riepilogo.buchiNelRegistro
    val parteFuori = if (fuori == 0) {
        stringResource(R.string.riepilogo_nessun_fuori_regola)
    } else {
        pluralStringResource(R.plurals.riepilogo_giorni_fuori_regola, fuori, fuori)
    }
    val parteBuchi = if (buchi == 0) {
        stringResource(R.string.riepilogo_registro_completo)
    } else {
        pluralStringResource(R.plurals.riepilogo_buchi, buchi, buchi)
    }
    return "$parteFuori · $parteBuchi"
}

/**
 * Una regola (§3.4): il tipo come sopra-titolo, la frase, la sua striscia
 * piccola da 20dp — un dettaglio, non un verdetto: il verdetto sta in cima.
 * Le limite_tempo attive portano i bonus ancora disponibili come chip.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SchedaRegola(regola: RegolaFinestra, bonus: StatoBonus) {
    CardContenuto {
        Column(modifier = Modifier.padding(Spazi.l)) {
            SopraTitolo(
                testo = etichettaTipoRegola(regola.tipo),
                colore = MaterialTheme.colorScheme.primary,
            )
            Text(
                text = descrizioneRegola(regola),
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(top = Spazi.xs),
            )
            if (!regola.attiva) {
                Spacer(Modifier.height(Spazi.s))
                Etichetta(stringResource(R.string.regola_eliminata))
            }
            val giorni = giorniDaQuadretti(regola.semaforo)
            if (giorni.isNotEmpty()) {
                Spacer(Modifier.height(Spazi.m))
                StrisciaGiorni(
                    giorni = giorni,
                    lato = 20.dp,
                    mostraNumero = false,
                    descrizione = descrizioneStriscia(giorni),
                )
            }
            if (regola.attiva && regola.tipo == TipiRegola.LIMITE_TEMPO) {
                Spacer(Modifier.height(Spazi.m))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(Spazi.s),
                    verticalArrangement = Arrangement.spacedBy(Spazi.xs),
                ) {
                    Etichetta(
                        stringResource(
                            R.string.bonus_chip_giorno,
                            bonus.giorno.residui,
                            bonus.giorno.tetto,
                        ),
                    )
                    Etichetta(
                        stringResource(
                            R.string.bonus_chip_settimana,
                            bonus.settimana.residui,
                            bonus.settimana.tetto,
                        ),
                    )
                }
            }
        }
    }
}

/** I minuti bonus di ciascuno degli 8 giorni (globali, non per regola). */
@Composable
private fun StrisciaBonus(bonusGiornalieri: List<BonusGiorno>) {
    Column(modifier = Modifier.fillMaxWidth().padding(top = Spazi.xs)) {
        SopraTitolo(stringResource(R.string.bonus_striscia_titolo))
        Spacer(modifier = Modifier.height(Spazi.s))
        Row(horizontalArrangement = Arrangement.spacedBy(Spazi.xs)) {
            bonusGiornalieri.forEachIndexed { indice, giorno ->
                val oggi = indice == bonusGiornalieri.lastIndex
                val forma = RoundedCornerShape(6.dp)
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(
                        modifier = Modifier
                            .size(width = 34.dp, height = 26.dp)
                            .background(
                                if (giorno.minuti > 0) {
                                    MaterialTheme.colorScheme.primaryContainer
                                } else {
                                    MaterialTheme.colorScheme.surfaceVariant
                                },
                                forma,
                            )
                            .then(
                                if (oggi) {
                                    // L'anello di "oggi" è primary, come nella
                                    // striscia: mai il nero di onSurface.
                                    Modifier.border(2.dp, MaterialTheme.colorScheme.primary, forma)
                                } else {
                                    Modifier
                                },
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = giorno.minuti.toString(),
                            style = MaterialTheme.typography.labelMedium,
                            color = if (giorno.minuti > 0) {
                                MaterialTheme.colorScheme.onPrimaryContainer
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                    }
                    Text(
                        text = giorno.giorno.takeLast(2),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/** Una riga di "Da guardare insieme": che cosa, e quando. */
@Composable
private fun RigaDaGuardare(voce: VoceDaGuardare, regolePerId: Map<Long, RegolaFinestra>) {
    val titolo = when (voce.genere) {
        GenereVoce.FUORI_REGOLA -> testoFuoriRegola(voce.evento, regolePerId)
        GenereVoce.BUCO_NEL_REGISTRO -> testoBuco(voce.evento)
    }
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = Spazi.m)) {
        Text(text = titolo, style = MaterialTheme.typography.bodyLarge)
        TestoOrario(voce.evento.tsServer, Modifier.padding(top = Spazi.xs))
    }
}

@Composable
private fun testoFuoriRegola(evento: EventoFinestra, regolePerId: Map<Long, RegolaFinestra>): String {
    val regola = campoLong(evento.dettagli, "regola_id")?.let { regolePerId[it] }
    return if (regola != null) {
        stringResource(R.string.sforamento_su_regola, descrizioneRegola(regola))
    } else {
        stringResource(R.string.sforamento_generico)
    }
}

@Composable
private fun testoBuco(evento: EventoFinestra): String {
    val sottoTipo = campoTesto(evento.dettagli, "sotto_tipo")
    return when (sottoTipo) {
        "cambio_ora" -> stringResource(R.string.manomissione_cambio_ora)
        "cambio_fuso" -> stringResource(R.string.manomissione_cambio_fuso)
        "silenzio" -> stringResource(R.string.manomissione_silenzio)
        // Tappa 6: rilevate al giro del worker sul telefono del figlio.
        "permesso_revocato" -> stringResource(R.string.manomissione_permesso_revocato)
        "notifiche_disattivate" -> stringResource(R.string.manomissione_notifiche_disattivate)
        else -> stringResource(R.string.manomissione_generica, sottoTipo ?: "?")
    }
}

@Composable
private fun RigaStorico(modifica: ModificaStorico, regolePerId: Map<Long, RegolaFinestra>) {
    val titolo = when (modifica.azione) {
        "creazione" -> stringResource(R.string.storico_creazione)
        "modifica" -> if (modifica.direzione == "stringe") {
            stringResource(R.string.storico_modifica_stringe)
        } else {
            stringResource(R.string.storico_modifica_allenta)
        }
        "eliminazione" -> stringResource(R.string.storico_eliminazione)
        else -> modifica.azione
    }
    // La regola raccontata coi parametri DELLA modifica (dopo, o prima se
    // eliminata), non con quelli vigenti: lo storico racconta il passato. Il
    // nome leggibile dell'app vale solo se l'app è ancora la stessa.
    val parametri = modifica.dopo ?: modifica.prima
    val regola = regolePerId[modifica.regolaId]
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = Spazi.m)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = titolo,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f),
            )
            if (modifica.concordata) {
                Etichetta(stringResource(R.string.storico_concordata))
            }
        }
        if (regola != null && parametri != null) {
            val stessaApp = parametroTesto(parametri, "app_o_categoria") ==
                parametroTesto(regola.parametri, "app_o_categoria")
            Text(
                text = descrizioneRegola(
                    regola.tipo,
                    parametri,
                    regola.nome.takeIf { stessaApp },
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        TestoOrario(modifica.tsServer, Modifier.padding(top = Spazi.xs))
    }
}

private fun campoLong(oggetto: JsonObject, nome: String): Long? =
    (oggetto[nome] as? JsonPrimitive)?.content?.toLongOrNull()

private fun campoTesto(oggetto: JsonObject, nome: String): String? =
    (oggetto[nome] as? JsonPrimitive)?.content
