package eu.stgm.pactum.genitore.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import eu.stgm.pactum.genitore.R
import eu.stgm.pactum.genitore.dati.BonusGiorno
import eu.stgm.pactum.genitore.dati.EventoFinestra
import eu.stgm.pactum.genitore.dati.Finestra
import eu.stgm.pactum.genitore.dati.ModificaStorico
import eu.stgm.pactum.genitore.dati.QuadrettoSemaforo
import eu.stgm.pactum.genitore.dati.RegolaFinestra
import eu.stgm.pactum.genitore.dati.StatiSemaforo
import eu.stgm.pactum.genitore.dati.StatoBonus
import eu.stgm.pactum.genitore.dati.StatoSilenzio
import eu.stgm.pactum.genitore.ui.theme.Spazi
import eu.stgm.pactum.genitore.ui.theme.coloreFuoriRegola
import eu.stgm.pactum.genitore.ui.theme.coloreMantenuta
import eu.stgm.pactum.genitore.ui.theme.coloreSilenzio
import eu.stgm.pactum.genitore.ui.theme.inchiostroSuFuoriRegola
import eu.stgm.pactum.genitore.ui.theme.inchiostroSuMantenuta
import eu.stgm.pactum.genitore.ui.theme.inchiostroSuSilenzio
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.time.Instant

// I colori del patto vivono in ui/theme (token `Patto`): un solo rosso, in un
// solo posto — dentro la striscia degli 8 giorni. Il silenzio del canale NON è
// rosso: è un grigio-blu, perché nove volte su dieci è batteria o rete.

/** Ogni quanto si rilegge la finestra mentre la schermata è in primo piano. */
private const val INTERVALLO_RILETTURA_MS = 60_000L

/** La finestra del genitore: tutto ciò che riguarda il patto, niente altro. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FinestraScreen(vm: FinestraViewModel = viewModel()) {
    val stato by vm.stato.collectAsStateWithLifecycle()

    // Prima lettura a ogni ritorno in primo piano, poi rilettura periodica
    // finché la schermata resta visibile: il banner verde "in contatto" non
    // può restare fermo per ore su un telefono lasciato acceso.
    val cicloVita = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(cicloVita) {
        cicloVita.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                vm.aggiorna()
                delay(INTERVALLO_RILETTURA_MS)
            }
        }
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0.dp),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.finestra_titolo)) },
                actions = {
                    IconButton(onClick = { vm.aggiorna() }) {
                        Icon(Icons.Filled.Refresh, stringResource(R.string.azione_aggiorna))
                    }
                },
            )
        },
    ) { padding ->
        val finestra = stato.finestra
        Box(modifier = Modifier.padding(padding).fillMaxSize()) {
            when {
                stato.caricamento && finestra == null -> Centro {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator()
                        Text(
                            text = stringResource(R.string.finestra_caricamento),
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(top = Spazi.s),
                        )
                    }
                }

                stato.configurazioneMancante -> Centro {
                    TestoCentrato(stringResource(R.string.finestra_config_mancante))
                }

                finestra == null -> Centro {
                    TestoCentrato(stringResource(R.string.finestra_errore))
                }

                else -> ContenutoFinestra(
                    finestra = finestra,
                    mostraErrore = stato.errore,
                    ricevutaAlle = stato.ricevutaAlle,
                )
            }
        }
    }
}

@Composable
private fun ContenutoFinestra(
    finestra: Finestra,
    mostraErrore: Boolean,
    ricevutaAlle: Instant?,
) {
    // Per raccontare storico e sforamenti serve il tipo della regola:
    // la finestra porta TUTTE le regole (anche eliminate), quindi la mappa è completa.
    val regolePerId = finestra.regole.associateBy { it.id }

    var mostraIntro by rememberSaveable { mutableStateOf(true) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(Spazi.l),
        verticalArrangement = Arrangement.spacedBy(Spazi.m),
    ) {
        if (mostraErrore) {
            item { RigaDatiVecchi(stringResource(R.string.finestra_errore)) }
        }

        // La cornice: spiega al genitore cos'e Pactum e perche non impone lui le
        // regole. Richiudibile: dopo averla letta non ingombra piu.
        if (mostraIntro) {
            item { CardIntro(onChiudi = { mostraIntro = false }) }
        }

        item { RigaStato(finestra.statoSilenzio, ricevutaAlle) }

        // La scheda EROE: l'anello di oggi e gli otto giorni, prima delle regole.
        // Il genitore che apre l'app vuole sapere PRIMA quanto e in cosa, POI
        // che cosa dice il patto.
        if (finestra.usoRecente.isNotEmpty()) {
            item { SchedaUsoOggi(finestra.usoRecente) }
        }

        // Le medie: quanto in media al giorno, su 7 e 30 giorni. Solo se il
        // server le manda (tolleranza) e almeno una delle due esiste.
        finestra.medie?.let { medie ->
            if (medie.settimana != null || medie.mese != null) {
                item { RigaMedie(medie) }
            }
        }

        item { TitoloSezione(stringResource(R.string.sezione_regole)) }
        if (finestra.regole.isEmpty()) {
            item { TestoVuoto(stringResource(R.string.regole_vuoto)) }
        } else {
            items(finestra.regole, key = { "regola-${it.id}" }) { SchedaRegola(it) }
        }

        item { TitoloSezione(stringResource(R.string.sezione_bonus)) }
        item { SchedaBonus(finestra.bonus, finestra.bonusGiornalieri) }

        item { TitoloSezione(stringResource(R.string.sezione_sforamenti)) }
        if (finestra.sforamentiRecenti.isEmpty()) {
            item { TestoVuoto(stringResource(R.string.sforamenti_vuoto)) }
        } else {
            items(finestra.sforamentiRecenti, key = { "sforamento-${it.id}" }) {
                RigaSforamento(it, regolePerId)
            }
        }

        item { TitoloSezione(stringResource(R.string.sezione_manomissioni)) }
        if (finestra.manomissioniRecenti.isEmpty()) {
            item { TestoVuoto(stringResource(R.string.manomissioni_vuoto)) }
        } else {
            items(finestra.manomissioniRecenti, key = { "manomissione-${it.id}" }) {
                RigaManomissione(it)
            }
        }

        item { TitoloSezione(stringResource(R.string.sezione_storico)) }
        if (finestra.storicoModifiche.isEmpty()) {
            item { TestoVuoto(stringResource(R.string.storico_vuoto)) }
        } else {
            items(finestra.storicoModifiche, key = { "storico-${it.id}" }) {
                RigaStorico(it, regolePerId)
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
 * Le medie del tempo d'uso: quanto in media al giorno, su 7 e 30 giorni.
 * Ogni valore compare solo se il server ha dati per quella finestra.
 */
@Composable
private fun RigaMedie(medie: eu.stgm.pactum.genitore.dati.Medie) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(Spazi.l),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            medie.settimana?.let {
                CellaMedia(stringResource(R.string.media_settimana), it.minuti)
            }
            medie.mese?.let {
                CellaMedia(stringResource(R.string.media_mese), it.minuti)
            }
        }
    }
}

@Composable
private fun CellaMedia(etichetta: String, minuti: Int) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = etichetta,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(Spazi.xs))
        Text(
            text = testoDurata(minuti.toLong()),
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = stringResource(R.string.media_al_giorno),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * Lo stato del canale col figlio, deciso dal flag `silente` del SERVER.
 *
 * La normalità non grida: "in contatto" è una riga leggera (pallino + testo),
 * non più un rettangolo verde grande quanto un allarme. L'anomalia occupa
 * spazio: solo il silenzio resta una Card piena — e il suo colore è il
 * grigio-blu del canale muto, MAI un rosso del patto, perché nove volte su
 * dieci è batteria scarica o rete.
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
        val inchiostro = inchiostroSuSilenzio()
        Card(
            colors = CardDefaults.cardColors(containerColor = coloreSilenzio()),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(
                modifier = Modifier.padding(Spazi.l),
                verticalAlignment = Alignment.CenterVertically,
            ) {
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
                    if (eta != null) {
                        Text(
                            text = eta,
                            style = MaterialTheme.typography.labelSmall,
                            color = inchiostro,
                            modifier = Modifier.padding(top = 2.dp),
                        )
                    }
                }
            }
        }
    } else {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
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
                    modifier = Modifier.padding(start = Spazi.s),
                )
            }
            if (eta != null) {
                Text(
                    text = eta,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
    }
}

/**
 * Dati vecchi: è un'ETÀ, non un fallimento. Una riga su `surfaceVariant`, mai
 * `errorContainer` — il rosso di sistema resta alla validazione dei form, così
 * il genitore non confonde "mio figlio ha sforato" con "il mio telefono non ha
 * campo".
 */
@Composable
internal fun RigaDatiVecchi(testo: String) {
    Text(
        text = testo,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .fillMaxWidth()
            .background(
                MaterialTheme.colorScheme.surfaceVariant,
                MaterialTheme.shapes.small,
            )
            .padding(horizontal = Spazi.m, vertical = Spazi.s),
    )
}

@Composable
private fun SchedaRegola(regola: RegolaFinestra) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(Spazi.l)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = descrizioneRegola(regola.tipo, regola.parametri),
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.weight(1f),
                )
                if (!regola.attiva) {
                    Etichetta(stringResource(R.string.regola_eliminata))
                }
            }
            Spacer(modifier = Modifier.height(Spazi.m))
            Semaforo(regola.semaforo)
        }
    }
}

/**
 * La striscia degli otto giorni, dal più vecchio a oggi.
 *
 * Tre cose che il vecchio semaforo sbagliava:
 *  - il numero del giorno sta DENTRO il quadretto, non sotto: una riga sola,
 *    quindi l'allineamento non salta più sull'ultima colonna;
 *  - "oggi" è un ANELLO `primary` (il blu dell'app), non la parola "oggi" né un
 *    rettangolo nero su fondo saturo;
 *  - "nessun dato" è un quadretto VUOTO (`surfaceVariant` + bordo `outline`):
 *    un "non lo so" deve sembrare assente, non guasto.
 *
 * Ingombro della cella uniforme (quadretto + 8.dp): l'anello si disegna dentro
 * lo spazio già riservato a tutte, quindi la striscia non si deforma.
 */
@Composable
private fun Semaforo(semaforo: List<QuadrettoSemaforo>) {
    if (semaforo.isEmpty()) return
    val spazio = Spazi.xs
    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        // Si punta alla misura di progetto (cella 40, quadretto 32) e ci si
        // stringe solo su schermi che non ci arrivano: meglio più piccolo che
        // tagliato fuori dal bordo.
        val cella = ((maxWidth - spazio * (semaforo.size - 1)) / semaforo.size)
            .coerceIn(20.dp, 40.dp)
        val lato = cella - 8.dp
        val grande = lato >= 32.dp
        val forma = RoundedCornerShape(if (grande) 10.dp else 6.dp)
        val formaAnello = RoundedCornerShape(if (grande) 14.dp else 10.dp)

        Row(horizontalArrangement = Arrangement.spacedBy(spazio)) {
            semaforo.forEachIndexed { indice, quadretto ->
                val oggi = indice == semaforo.lastIndex
                val conDati = quadretto.stato == StatiSemaforo.VERDE ||
                    quadretto.stato == StatiSemaforo.ROSSO
                val mantenuta = quadretto.stato == StatiSemaforo.VERDE
                val sfondo = when {
                    !conDati -> MaterialTheme.colorScheme.surfaceVariant
                    mantenuta -> coloreMantenuta()
                    else -> coloreFuoriRegola()
                }
                Box(
                    modifier = Modifier
                        .size(cella)
                        .then(
                            if (oggi) {
                                Modifier.border(
                                    2.dp,
                                    MaterialTheme.colorScheme.primary,
                                    formaAnello,
                                )
                            } else {
                                Modifier
                            },
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Box(
                        modifier = Modifier
                            .size(lato)
                            .background(sfondo, forma)
                            .then(
                                if (!conDati) {
                                    Modifier.border(
                                        1.dp,
                                        MaterialTheme.colorScheme.outline,
                                        forma,
                                    )
                                } else {
                                    Modifier
                                },
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (conDati) {
                            Text(
                                // Il giorno del mese ("2026-07-14" → "14").
                                text = quadretto.data.takeLast(2),
                                style = MaterialTheme.typography.labelMedium,
                                color = if (mantenuta) {
                                    inchiostroSuMantenuta()
                                } else {
                                    inchiostroSuFuoriRegola()
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SchedaBonus(bonus: StatoBonus, bonusGiornalieri: List<BonusGiorno>) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(Spazi.l)) {
            Text(
                text = stringResource(
                    R.string.bonus_residui_giorno,
                    bonus.giorno.residui,
                    bonus.giorno.tetto,
                ),
                style = MaterialTheme.typography.bodyLarge,
            )
            Text(
                text = stringResource(
                    R.string.bonus_residui_settimana,
                    bonus.settimana.residui,
                    bonus.settimana.tetto,
                ),
                style = MaterialTheme.typography.bodyLarge,
            )
            Spacer(modifier = Modifier.height(Spazi.m))
            Text(
                text = stringResource(R.string.bonus_striscia_titolo),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(Spazi.s))
            Row(horizontalArrangement = Arrangement.spacedBy(Spazi.xs)) {
                bonusGiornalieri.forEachIndexed { indice, giorno ->
                    val oggi = indice == bonusGiornalieri.lastIndex
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
                                    RoundedCornerShape(6.dp),
                                )
                                .then(
                                    if (oggi) {
                                        // L'anello di "oggi" è primary, come
                                        // nella striscia: mai il nero di onSurface.
                                        Modifier.border(
                                            2.dp,
                                            MaterialTheme.colorScheme.primary,
                                            RoundedCornerShape(6.dp),
                                        )
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
}

@Composable
private fun RigaSforamento(evento: EventoFinestra, regolePerId: Map<Long, RegolaFinestra>) {
    val regola = campoLong(evento.dettagli, "regola_id")?.let { regolePerId[it] }
    val descrizione = if (regola != null) {
        stringResource(
            R.string.sforamento_su_regola,
            descrizioneRegola(regola.tipo, regola.parametri),
        )
    } else {
        stringResource(R.string.sforamento_generico)
    }
    RigaEvento(titolo = descrizione, tsServer = evento.tsServer)
}

@Composable
private fun RigaManomissione(evento: EventoFinestra) {
    val sottoTipo = campoTesto(evento.dettagli, "sotto_tipo")
    val titolo = when (sottoTipo) {
        "cambio_ora" -> stringResource(R.string.manomissione_cambio_ora)
        "cambio_fuso" -> stringResource(R.string.manomissione_cambio_fuso)
        "silenzio" -> stringResource(R.string.manomissione_silenzio)
        // Tappa 6: rilevate al giro del worker sul telefono del figlio.
        "permesso_revocato" -> stringResource(R.string.manomissione_permesso_revocato)
        "notifiche_disattivate" -> stringResource(R.string.manomissione_notifiche_disattivate)
        else -> stringResource(R.string.manomissione_generica, sottoTipo ?: "?")
    }
    RigaEvento(titolo = titolo, tsServer = evento.tsServer)
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
    // eliminata), non con quelli vigenti: lo storico racconta il passato.
    val parametri = modifica.dopo ?: modifica.prima
    val tipo = regolePerId[modifica.regolaId]?.tipo
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(horizontal = Spazi.l, vertical = Spazi.m)) {
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
            if (tipo != null && parametri != null) {
                Text(
                    text = descrizioneRegola(tipo, parametri),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TestoOrario(modifica.tsServer)
        }
    }
}

@Composable
private fun RigaEvento(titolo: String, tsServer: String) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(horizontal = Spazi.l, vertical = Spazi.m)) {
            Text(text = titolo, style = MaterialTheme.typography.bodyLarge)
            TestoOrario(tsServer)
        }
    }
}

@Composable
private fun TestoOrario(tsServer: String) {
    val istante = istanteServer(tsServer) ?: return
    Text(
        text = dataOraLocale(istante),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun TitoloSezione(testo: String) {
    Text(
        text = testo,
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.padding(top = Spazi.s),
    )
}

@Composable
private fun TestoVuoto(testo: String) {
    Text(
        text = testo,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun Etichetta(testo: String) {
    Surface(
        shape = RoundedCornerShape(50),
        color = MaterialTheme.colorScheme.secondaryContainer,
    ) {
        Text(
            text = testo,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
        )
    }
}

@Composable
private fun Centro(contenuto: @Composable () -> Unit) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        contenuto()
    }
}

@Composable
private fun TestoCentrato(testo: String) {
    Text(
        text = testo,
        style = MaterialTheme.typography.bodyMedium,
        textAlign = TextAlign.Center,
        modifier = Modifier.padding(horizontal = Spazi.xxl),
    )
}

private fun campoLong(oggetto: JsonObject, nome: String): Long? =
    (oggetto[nome] as? JsonPrimitive)?.content?.toLongOrNull()

private fun campoTesto(oggetto: JsonObject, nome: String): String? =
    (oggetto[nome] as? JsonPrimitive)?.content
