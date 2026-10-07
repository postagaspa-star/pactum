package eu.stgm.pactum.genitore.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Badge
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import eu.stgm.pactum.design.Spazi
import eu.stgm.pactum.genitore.R
import eu.stgm.pactum.genitore.dati.TipiDispositivo

// I mattoni del genitore che non sono in core-design. (0.15) Titoli di sezione,
// righe di stato, stati vuoti, caricamento, pillole e card sono i componenti comuni
// di core-design (eu.stgm.pactum.design): qui restano il sopra-titolo piccolo,
// l'icona del dispositivo e la scelta del figlio in cima.

/** Sopra-titolo piccolo (la maiuscola, quando serve, sta nella stringa): "DENTRO IL PATTO", "Telefono · oggi". */
@Composable
fun SopraTitolo(
    testo: String,
    modifier: Modifier = Modifier,
    colore: Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
    Text(
        text = testo,
        style = MaterialTheme.typography.labelMedium,
        color = colore,
        modifier = modifier,
    )
}

/** Il numero dentro un badge: oltre 99 non serve contare. */
fun testoBadge(quante: Int): String = if (quante > 99) "99+" else quante.toString()

// --- Famiglia (v3) ---------------------------------------------------------------

/** L'icona del tipo di dispositivo: telefono o computer. Mai un colore d'allarme. */
@Composable
fun IconaDispositivo(
    tipo: String,
    modifier: Modifier = Modifier,
    tinta: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    descrizione: String? = null,
) {
    Icon(
        painter = painterResource(
            if (tipo == TipiDispositivo.COMPUTER) {
                R.drawable.ic_dispositivo_computer
            } else {
                R.drawable.ic_dispositivo_telefono
            },
        ),
        contentDescription = descrizione,
        tint = tinta,
        modifier = modifier.size(20.dp),
    )
}

/**
 * La scelta del figlio, in cima alla lista di ogni scheda (v3), SOLO con più
 * figli: un chip per figlio (la scelta resta ricordata e vale per tutta l'app)
 * col numero delle sue notifiche non lette. (0.15) Con un figlio solo non c'è
 * niente qui (B33): il suo nome sta in cima alla card del patto.
 *
 * (0.10) Accanto al nome, quante sue richieste aspettano il genitore ("Luca · 1
 * da decidere"): con più figli si vede chi aspetta, senza aprirli uno per uno.
 */
/**
 * (0.15) Quante cose aspettano il genitore per il figlio scelto: dalla sua
 * finestra (proposte e sessioni) e dalle sue dichiarazioni, le stesse letture
 * della lista di "Da decidere". Finché la sua finestra non c'è, il numero della
 * famiglia più le dichiarazioni, se si sanno. null = nessun figlio scelto.
 */
@Composable
fun daDecidereDelScelto(
    famiglia: FamigliaViewModel.StatoFamiglia,
    finestraVm: FinestraViewModel = viewModel(),
    proposteVm: ProposteViewModel = viewModel(),
    verdettiVm: VerdettiViewModel = viewModel(),
    studioVm: StudioViewModel = viewModel(),
): Int? {
    val figlio = famiglia.figlioScelto ?: return null
    val statoFinestra by finestraVm.stato.collectAsStateWithLifecycle()
    val statoStudio by studioVm.stato.collectAsStateWithLifecycle()
    val proposte by proposteVm.stato.collectAsStateWithLifecycle()
    val verdetti by verdettiVm.stato.collectAsStateWithLifecycle()
    val dichiarazioni = verdetti.dichiarazioni.takeIf { verdetti.di(figlio.id) }
    val finestra = statoFinestra.finestra.takeIf { statoFinestra.di(figlio.id) }
    return if (finestra != null) {
        quanteDaDecidereDellaFinestra(
            finestra = finestra,
            giaChiuse = proposte.giaChiuse,
            sessioniDecise = statoFinestra.sessioniDecise,
            lettaAlle = statoFinestra.lettaAlle,
            dichiarazioni = dichiarazioni,
            studioDecisaVersione = statoStudio.decise[figlio.id],
        )
    } else {
        quanteDaDecidere(figlio) + (dichiarazioni?.let(::dichiarazioniInAttesa) ?: 0)
    }
}

/**
 * (0.15) Una scheda con la scelta del figlio in cima e sotto [contenuto]
 * (caricamento, errore o elenco). La scelta resta visibile in ogni stato: se i
 * dati di un figlio non arrivano si può sempre tornare a un altro. Niente scelta
 * col 401 (cambiare figlio non serve) e su un server vecchio (un figlio solo).
 */
@Composable
fun ConSceltaFiglio(
    famiglia: FamigliaViewModel.StatoFamiglia,
    fissa: Boolean,
    modifier: Modifier = Modifier,
    contenuto: @Composable BoxScope.() -> Unit,
) {
    Column(modifier) {
        if (fissa && famiglia.piuFigli && !famiglia.serverVecchio && !famiglia.collegamentoNonValido) {
            Box(modifier = Modifier.padding(top = Spazi.l)) { IntestazioneFiglio(famiglia, margineLaterale = Spazi.l) }
        }
        Box(modifier = Modifier.fillMaxWidth().weight(1f), content = contenuto)
    }
}

/**
 * (0.15) La scelta del figlio come prima riga di un elenco: scorre col resto. Si
 * vede nello stesso punto di quella fissa di [ConSceltaFiglio] (stessi margini).
 */
fun LazyListScope.sceltaDelFiglio(famiglia: FamigliaViewModel.StatoFamiglia) {
    if (famiglia.piuFigli && !famiglia.serverVecchio) {
        item(key = "figli") { IntestazioneFiglio(famiglia) }
    }
}

@Composable
fun IntestazioneFiglio(
    famiglia: FamigliaViewModel.StatoFamiglia,
    margineLaterale: Dp = 0.dp,
    famigliaVm: FamigliaViewModel = viewModel(),
) {
    if (famiglia.serverVecchio || !famiglia.piuFigli) return
    val scelto = famiglia.figlioScelto
    // Il figlio scelto: lo stesso numero della barra e della Panoramica. Gli
    // altri: quello della famiglia (proposte + sessioni).
    val delScelto = daDecidereDelScelto(famiglia)
    val p = parole()
    val descrizione = stringResource(R.string.figlio_scelta_descrizione)
    val senzaNome = stringResource(R.string.figlio_senza_nome)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = margineLaterale)
            .semantics { contentDescription = descrizione },
        horizontalArrangement = Arrangement.spacedBy(Spazi.s),
    ) {
        famiglia.figli.forEach { figlio ->
            val nonLette = figlio.notificheNonLette
            val etichettaNonLette = if (nonLette > 0) {
                pluralStringResource(R.plurals.figlio_notifiche_non_lette, nonLette, nonLette)
            } else {
                null
            }
            FilterChip(
                selected = figlio.id == scelto?.id,
                onClick = { famigliaVm.scegli(figlio.id) },
                label = {
                    val quante = if (figlio.id == scelto?.id && delScelto != null) delScelto else quanteDaDecidere(figlio)
                    Text(nomeConDaDecidere(figlio.nome.ifBlank { senzaNome }, testoDaDecidere(p, quante)))
                },
                trailingIcon = if (etichettaNonLette != null) {
                    {
                        // Il blu dell'app, come il badge della campanella.
                        Badge(
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.semantics { contentDescription = etichettaNonLette },
                        ) {
                            Text(testoBadge(nonLette))
                        }
                    }
                } else {
                    null
                },
            )
        }
    }
}

/**
 * (0.10) Il nome del figlio e, se ce ne sono, le sue richieste da decidere nel blu
 * dell'app ("Luca · 1 da decidere"): si notano, senza il rosso del patto.
 */
@Composable
private fun nomeConDaDecidere(nome: String, daDecidere: String?): AnnotatedString = buildAnnotatedString {
    append(nome)
    if (daDecidere != null) {
        append(" · ")
        withStyle(SpanStyle(color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)) {
            append(daDecidere)
        }
    }
}

/**
 * Righe di lista con il divisore in mezzo: i genitori, i dispositivi di un
 * figlio. Non sono card: sono un elenco, e si leggono come tale.
 */
@Composable
fun <T> ListaRighe(
    voci: List<T>,
    modifier: Modifier = Modifier,
    riga: @Composable (T) -> Unit,
) {
    androidx.compose.foundation.layout.Column(modifier = modifier.fillMaxWidth()) {
        voci.forEachIndexed { indice, voce ->
            if (indice > 0) androidx.compose.material3.HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            riga(voce)
        }
    }
}

/**
 * L'orario di un fatto del registro, nel fuso del telefono, sottovoce. (0.15) Un
 * formato solo: "oggi 15:10", "ieri 15:10", "14/09 15:10".
 */
@Composable
fun TestoOrario(tsServer: String?, modifier: Modifier = Modifier) {
    val istante = istanteServer(tsServer) ?: return
    Text(
        text = testoQuando(parole(), istante),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier,
    )
}
