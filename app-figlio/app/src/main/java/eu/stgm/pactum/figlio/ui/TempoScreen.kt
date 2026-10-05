package eu.stgm.pactum.figlio.ui

import android.content.Context
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import eu.stgm.pactum.design.AnelloCategorie
import eu.stgm.pactum.design.BarreGiorni
import eu.stgm.pactum.design.BloccoMedie
import eu.stgm.pactum.design.CardEvidenza
import eu.stgm.pactum.design.CardNormale
import eu.stgm.pactum.design.CellaMedia
import eu.stgm.pactum.design.GiornoGrafico
import eu.stgm.pactum.design.LegendaCategorie
import eu.stgm.pactum.design.Spazi
import eu.stgm.pactum.design.StatoVuoto
import eu.stgm.pactum.design.TitoloSezione
import eu.stgm.pactum.design.Tono
import eu.stgm.pactum.design.VoceCategoria
import eu.stgm.pactum.design.fetteConResto
import eu.stgm.pactum.figlio.R
import eu.stgm.pactum.figlio.catalogo.CatalogoApp

/**
 * (0.16, contratto v3.8) La pagina Tempo, da "Vedi tutto" in Oggi: la stessa
 * del genitore, coi grafici di core-design. Dall'alto: il dispositivo (se più
 * d'uno), la fila dei giorni (oggi visibile e scelto all'apertura), il totale
 * del giorno con l'anello delle categorie, gli 8 giorni coi totali e le medie
 * degli ultimi 7 e 30 giorni, e tutte le app del giorno.
 *
 * Oggi di questo telefono è quello letto qui; il resto dal server. Senza i
 * campi nuovi (server vecchio) c'è solo oggi.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TempoScreen(onChiudi: () -> Unit, vm: OggiViewModel = viewModel()) {
    val stato by vm.stato.collectAsStateWithLifecycle()
    // (0.16, v3.8) All'apertura (e a ogni ritorno) i tempi freschi: GET /api/patto?tempi=1.
    LifecycleResumeEffect(Unit) {
        vm.aggiorna()
        onPauseOrDispose { }
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.tempo_titolo)) },
                navigationIcon = {
                    IconButton(onClick = onChiudi) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.azione_indietro))
                    }
                },
            )
        },
    ) { padding ->
        val tempi = stato.tempi
        var dispositivoScelto by rememberSaveable { mutableStateOf<Long?>(null) }
        val dispositivo = tempi.firstOrNull { it.id == dispositivoScelto && it.id != null } ?: tempi.firstOrNull()
        if (dispositivo == null) {
            Box(modifier = Modifier.padding(padding).fillMaxSize().padding(Spazi.l + Spazi.xs)) {
                StatoVuoto(stringResource(R.string.oggi_vuoto))
            }
            return@Scaffold
        }
        // Il giorno scelto; null = oggi (l'ultimo). Se sparisce (giorno nuovo), si torna a oggi.
        var giornoScelto by rememberSaveable(dispositivo.id) { mutableStateOf<String?>(null) }
        val giorni = dispositivo.giorni
        val giorno = giorni.firstOrNull { it.giorno == giornoScelto } ?: giorni.last()
        val oggi = giorno.giorno == giorni.last().giorno

        LazyColumn(
            modifier = Modifier.padding(padding).fillMaxSize(),
            contentPadding = PaddingValues(Spazi.l + Spazi.xs),
            verticalArrangement = Arrangement.spacedBy(Spazi.l),
        ) {
            if (tempi.size > 1) {
                item(key = "dispositivi") {
                    SceltaDispositivo(tempi, dispositivo) { dispositivoScelto = it }
                }
            }
            if (giorni.size > 1) {
                item(key = "giorni") {
                    SceltaGiorno(giorni, giorno.giorno) { giornoScelto = it }
                }
            }
            item(key = "giorno") { SchedaGiorno(giorno, oggi, dispositivo.computer) }
            if (dispositivo.storico && giorni.size > 1) {
                item(key = "otto-giorni") { SchedaOttoGiorni(giorni, giorno.giorno, dispositivo.medie) }
            }
            if (giorno.totaleMinuti != null) {
                item(key = "app-titolo") {
                    TitoloSezione(
                        stringResource(if (dispositivo.computer) R.string.tempo_programmi_titolo else R.string.tempo_app_titolo),
                    )
                }
                if (giorno.app.isEmpty()) {
                    item(key = "app-vuoto") {
                        StatoVuoto(
                            stringResource(if (dispositivo.computer) R.string.tempo_programmi_vuoto else R.string.tempo_app_vuoto),
                        )
                    }
                } else {
                    // Un blocco solo, come in Oggi: le righe una sotto l'altra, una linea fra due.
                    item(key = "app") {
                        Column {
                            giorno.app.forEachIndexed { indice, app ->
                                if (indice > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                                RigaAppTempo(app)
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * (0.16) Il riquadro del tempo in Oggi, sotto "In tutto": le barre degli 8
 * giorni (il totale di ognuno scritto sopra) e i totali degli ultimi 7 e 30
 * giorni. Niente se il server non manda gli 8 giorni (server vecchio): allora
 * Oggi resta com'era, con solo oggi.
 */
@Composable
fun TempoInOggi(tempi: TempiDispositivo) {
    if (!tempi.storico || tempi.giorni.size < 2) return
    val context = LocalContext.current
    Column(verticalArrangement = Arrangement.spacedBy(Spazi.m)) {
        BarreGiorni(
            giorni = giorniGrafico(tempi.giorni),
            valore = { testoDurataBreve(context, it.toLong()) },
            descrizione = { descrizioneGiorno(context, it) },
        )
        val righe = righeTotali(context, tempi.medie)
        if (righe.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(Spazi.xs)) {
                righe.forEach { (testo, nota) -> TestoConNota(testo, nota) }
            }
        }
    }
}

/** "Ultimi 7 giorni: 15 h 20 min" e, piccolo accanto, "(6 giorni su 7 con dati)"; va a capo tutto intero. */
@Composable
private fun TestoConNota(testo: String, nota: String?) {
    val piccolo = MaterialTheme.typography.bodySmall.fontSize
    Text(
        text = buildAnnotatedString {
            append(testo)
            if (nota != null) {
                append(" ")
                withStyle(SpanStyle(fontSize = piccolo)) {
                    append(stringResource(R.string.tempo_nota_tra_parentesi, nota).replace(' ', '\u00A0'))
                }
            }
        },
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** Quale dispositivo guardare: un chip ciascuno, "Questo telefono" per primo. */
@Composable
private fun SceltaDispositivo(tempi: List<TempiDispositivo>, scelto: TempiDispositivo, onScelta: (Long?) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(Spazi.s),
    ) {
        tempi.forEach { dispositivo ->
            FilterChip(
                selected = dispositivo.id == scelto.id && dispositivo.questo == scelto.questo,
                onClick = { onScelta(dispositivo.id) },
                label = { Text(nomeDispositivo(dispositivo)) },
            )
        }
    }
}

@Composable
private fun nomeDispositivo(dispositivo: TempiDispositivo): String {
    val base = when {
        dispositivo.questo -> stringResource(R.string.oggi_questo_telefono)
        dispositivo.nome.isNotBlank() -> dispositivo.nome
        else -> stringResource(R.string.oggi_dispositivo_senza_nome)
    }
    return if (dispositivo.revocato) stringResource(R.string.oggi_dispositivo_scollegato, base) else base
}

/**
 * La fila dei giorni, dal più vecchio a oggi (l'ultimo dice "oggi"): l'unico
 * posto dove si sceglie il giorno. Parte già scorsa in fondo: oggi si vede.
 */
@Composable
private fun SceltaGiorno(giorni: List<GiornoTempo>, scelto: String, onScelta: (String) -> Unit) {
    val scorrimento = rememberScrollState(initial = Int.MAX_VALUE)
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(scorrimento),
        horizontalArrangement = Arrangement.spacedBy(Spazi.s),
    ) {
        giorni.forEachIndexed { indice, giorno ->
            FilterChip(
                selected = giorno.giorno == scelto,
                onClick = { onScelta(giorno.giorno) },
                label = {
                    Text(
                        if (indice == giorni.lastIndex) stringResource(R.string.tempo_chip_oggi) else giornoNumerico(giorno.giorno),
                    )
                },
            )
        }
    }
}

/**
 * Il giorno scelto: "Oggi" / "Il 03/10", il totale grande (o "Nessun dato
 * ricevuto", che non è zero), il tempo in sessione che non conta, e l'anello
 * delle categorie con la legenda.
 */
@Composable
private fun SchedaGiorno(giorno: GiornoTempo, oggi: Boolean, computer: Boolean) {
    val context = LocalContext.current
    CardEvidenza(tono = Tono.Neutro) {
        Text(
            text = if (oggi) {
                stringResource(R.string.tempo_etichetta_oggi)
            } else {
                stringResource(R.string.tempo_etichetta_giorno, giornoNumerico(giorno.giorno))
            },
            style = MaterialTheme.typography.labelLarge,
        )
        val totale = giorno.totaleMinuti
        if (totale == null) {
            Text(
                text = stringResource(R.string.tempo_nessun_dato),
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.padding(top = Spazi.xs),
            )
            Text(
                text = stringResource(if (computer) R.string.tempo_nessun_dato_spiega_computer else R.string.tempo_nessun_dato_spiega),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = Spazi.xs),
            )
            return@CardEvidenza
        }
        Text(text = testoDurata(totale.toLong()), style = MaterialTheme.typography.displaySmall)
        val inSessione = giorno.sessioniMinuti ?: 0
        if (inSessione > 0) {
            Text(
                text = stringResource(R.string.oggi_in_sessione_non_contati, testoDurata(inSessione.toLong())),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = Spazi.xs),
            )
        }
        // Con la fetta "Non in categoria" la legenda somma sempre al totale (core-design).
        val fette = fetteConResto(
            voci = giorno.categorie.map { VoceCategoria(it.chiave, it.minuti, it.limite) },
            totaleMinuti = totale,
            etichetta = { etichettaCategoria(context, it) },
            etichettaResto = stringResource(R.string.tempo_non_categorizzato),
        )
        Spacer(modifier = Modifier.height(Spazi.l))
        Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            AnelloCategorie(fette = fette, totaleMinuti = totale)
        }
        Spacer(modifier = Modifier.height(Spazi.l))
        if (fette.isEmpty()) {
            Text(
                text = stringResource(R.string.tempo_categorie_vuoto),
                style = MaterialTheme.typography.bodyMedium,
            )
        } else {
            LegendaCategorie(
                fette = fette,
                durata = { testoDurata(context, it.toLong()) },
                limite = { context.getString(R.string.tempo_limite, testoDurata(context, it.toLong())) },
            )
        }
    }
}

/** Gli 8 giorni (si guardano: il giorno si sceglie nella fila sopra), e sotto totali e medie. */
@Composable
private fun SchedaOttoGiorni(giorni: List<GiornoTempo>, scelto: String, medie: MedieTempo?) {
    val context = LocalContext.current
    CardNormale {
        Text(
            text = stringResource(R.string.tempo_ultimi_8_giorni),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(Spazi.m))
        BarreGiorni(
            giorni = giorniGrafico(giorni),
            selezionato = scelto,
            valore = { testoDurataBreve(context, it.toLong()) },
            descrizione = { descrizioneGiorno(context, it) },
        )
        val celle = medie?.let { celleTempi(context, it) }.orEmpty()
        if (celle.isNotEmpty()) {
            Spacer(modifier = Modifier.height(Spazi.l))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Spacer(modifier = Modifier.height(Spazi.l))
            BloccoMedie(celle)
        }
    }
}

/** Un'app (o un programma) del giorno: nome a sinistra, minuti a destra. */
@Composable
private fun RigaAppTempo(app: AppDelGiorno) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = Spazi.m),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = app.nome, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Text(text = testoDurata(app.minuti.toLong()), style = MaterialTheme.typography.bodyMedium)
    }
}

/** Gli 8 giorni come li vogliono le barre: il giorno, il totale (null = senza dati), "03" sotto. */
fun giorniGrafico(giorni: List<GiornoTempo>): List<GiornoGrafico> =
    giorni.map { GiornoGrafico(giorno = it.giorno, minuti = it.totaleMinuti) }

/** La frase di una barra per TalkBack: "03/10: 3 h 5 min", o "03/10: nessun dato". */
fun descrizioneGiorno(context: Context, giorno: GiornoGrafico): String =
    context.getString(
        R.string.tempo_giorno_valore,
        giornoNumerico(giorno.giorno),
        giorno.minuti?.let { testoDurata(context, it.toLong()) } ?: context.getString(R.string.tempo_giorno_senza_dati),
    )

/** Il nome di una categoria come nelle regole ("Social", "Altre app"); una sconosciuta col suo nome. */
fun etichettaCategoria(context: Context, chiave: String): String =
    if (chiave in CatalogoApp.CATEGORIE) {
        CatalogoApp.nomeCategoria(context, chiave)
    } else {
        chiave.removePrefix(CatalogoApp.PREFISSO_CATEGORIA).replaceFirstChar { it.uppercaseChar() }
    }

/**
 * Le righe dei totali in Oggi: "Ultimi 7 giorni: 15 h 20 min" con, se mancano
 * giorni, "6 giorni su 7 con dati". Solo coi totali del server (v3.8): senza,
 * niente righe.
 */
fun righeTotali(context: Context, medie: MedieTempo?): List<Pair<String, String?>> {
    if (medie == null) return emptyList()
    return listOfNotNull(
        medie.settimana?.let { rigaTotale(context, it, TempiFiglio.GIORNI_SETTIMANA, R.string.tempo_ultimi_7_riga) },
        medie.mese?.let { rigaTotale(context, it, TempiFiglio.GIORNI_MESE, R.string.tempo_ultimi_30_riga) },
    )
}

private fun rigaTotale(context: Context, periodo: PeriodoTempo, finestra: Int, formato: Int): Pair<String, String?>? {
    val totale = periodo.totale ?: return null
    return context.getString(formato, testoDurata(context, totale.toLong())) to giorniConDati(context, periodo, finestra)
}

/** "6 giorni su 7 con dati", solo se non tutti i giorni avevano dati (quelli senza non sono zero). */
private fun giorniConDati(context: Context, periodo: PeriodoTempo, finestra: Int): String? =
    if (periodo.giorni < finestra) {
        context.resources.getQuantityString(R.plurals.tempo_giorni_con_dati, periodo.giorni, periodo.giorni, finestra)
    } else {
        null
    }

/**
 * Le celle sotto le barre della pagina Tempo (core-design). Coi totali (v3.8):
 * "Ultimi 7 giorni" col totale in grande, i giorni con dati se ne mancano e la
 * media al giorno. Senza totali (server di prima) le medie, e basta. Un periodo
 * senza dati non ha cella: mai uno zero finto.
 */
fun celleTempi(context: Context, medie: MedieTempo): List<CellaMedia> = listOfNotNull(
    medie.settimana?.let { cella(context, it, TempiFiglio.GIORNI_SETTIMANA, R.string.tempo_ultimi_7, R.string.tempo_media_settimana) },
    medie.mese?.let { cella(context, it, TempiFiglio.GIORNI_MESE, R.string.tempo_ultimi_30, R.string.tempo_media_mese) },
)

private fun cella(context: Context, periodo: PeriodoTempo, finestra: Int, titoloTotale: Int, titoloMedia: Int): CellaMedia {
    val totale = periodo.totale
    return if (totale == null) {
        CellaMedia(
            etichetta = context.getString(titoloMedia),
            valore = testoDurata(context, periodo.minuti.toLong()),
            sotto = listOf(context.resources.getQuantityString(R.plurals.tempo_media_su_giorni, periodo.giorni, periodo.giorni)),
        )
    } else {
        CellaMedia(
            etichetta = context.getString(titoloTotale),
            valore = testoDurata(context, totale.toLong()),
            sotto = listOfNotNull(
                giorniConDati(context, periodo, finestra),
                context.getString(R.string.tempo_media_al_giorno, testoDurata(context, periodo.minuti.toLong())),
            ),
            grande = true,
        )
    }
}
