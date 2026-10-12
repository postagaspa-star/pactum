package eu.stgm.pactum.genitore.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import eu.stgm.pactum.design.Caricamento
import eu.stgm.pactum.design.CardEvidenza
import eu.stgm.pactum.design.CardNormale
import eu.stgm.pactum.design.LegendaStriscia
import eu.stgm.pactum.design.Pillola
import eu.stgm.pactum.design.RigaStato
import eu.stgm.pactum.design.SchermataColorata
import eu.stgm.pactum.design.Segnale
import eu.stgm.pactum.design.Sezione
import eu.stgm.pactum.design.Spazi
import eu.stgm.pactum.design.StatoVuoto
import eu.stgm.pactum.design.StrisciaGiorni
import eu.stgm.pactum.design.TitoloSezione
import eu.stgm.pactum.design.Tono
import eu.stgm.pactum.design.contaGiorni
import eu.stgm.pactum.genitore.R
import eu.stgm.pactum.genitore.dati.Finestra
import eu.stgm.pactum.genitore.dati.RegolaFinestra
import eu.stgm.pactum.genitore.dati.StatoBonus

// (0.23) La pagina di UN dispositivo del figlio scelto (o dei suoi impegni di vita
// reale): in cima la sua striscia, la stessa che il figlio vede in grande su quel
// dispositivo; sotto, le sue regole di oggi, una card ciascuna con lo stato a
// parole e la sua striscia piccola; in fondo quelle non più attive. Ogni regola
// apre il suo dettaglio. Si apre dalla riga del dispositivo nella Panoramica
// (CardDelDispositivo, qui sotto). Le schede vengono da LogicaDispositivi.

@Composable
fun DispositivoScreen(
    diChi: DiChi,
    finestraVm: FinestraViewModel = viewModel(),
    famigliaVm: FamigliaViewModel = viewModel(),
) {
    val statoFinestra by finestraVm.stato.collectAsStateWithLifecycle()
    val famiglia by famigliaVm.stato.collectAsStateWithLifecycle()
    val figlioId = famiglia.figlioId

    LifecycleResumeEffect(figlioId, famiglia.pronta) {
        if (famiglia.pronta) finestraVm.aggiorna(figlioId)
        onPauseOrDispose { }
    }

    val finestra = statoFinestra.finestra.takeIf { statoFinestra.di(figlioId) }
    val scheda = remember(finestra, diChi) { finestra?.let { schedaDi(it, diChi) } }

    SchermataColorata(Sezione.REGOLE) {
        Scaffold(
            containerColor = Color.Transparent,
            contentWindowInsets = WindowInsets(0.dp),
            topBar = {
                BarraPagina(
                    scheda?.let { nomeDellaScheda(it) } ?: when (diChi) {
                        DiChi.Impegni -> stringResource(R.string.impegni_titolo)
                        is DiChi.Dispositivo -> stringResource(R.string.tutte_le_regole)
                    },
                )
            },
        ) { padding ->
            Box(modifier = Modifier.padding(padding).fillMaxSize()) {
                when {
                    famiglia.collegamentoNonValido -> StatoVuoto(
                        titolo = stringResource(R.string.collegamento_non_valido_titolo),
                        testo = stringResource(R.string.collegamento_non_valido),
                        centrato = true,
                    )
                    finestra == null && (statoFinestra.caricamento || !statoFinestra.di(figlioId)) ->
                        Caricamento(testo = stringResource(R.string.tutte_le_regole_caricamento))
                    finestra == null -> StatoVuoto(stringResource(R.string.finestra_errore_nessun_dato), centrato = true)
                    scheda == null -> StatoVuoto(
                        stringResource(
                            if (diChi == DiChi.Impegni) R.string.impegni_nessuno else R.string.dispositivo_non_trovato,
                        ),
                        centrato = true,
                    )
                    else -> PaginaDelDispositivo(scheda, finestra, errore = statoFinestra.errore)
                }
            }
        }
    }
}

@Composable
private fun PaginaDelDispositivo(scheda: SchedaDispositivo, finestra: Finestra, errore: Boolean) {
    val cornice = LocalCornice.current
    val impegni = scheda.diChi == DiChi.Impegni
    val bonus = remember(scheda, finestra) {
        scheda.dispositivo?.let { bonusDaMostrare(it, finestra.regole, perDispositivo = true) }
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(Spazi.l),
        verticalArrangement = Arrangement.spacedBy(Spazi.s),
    ) {
        if (errore) item(key = "dati-vecchi") { RigaStato(stringResource(R.string.turno_dati_vecchi)) }

        item(key = "testata") { TestataDelDispositivo(scheda, bonus) }

        item(key = "oggi-titolo") {
            TitoloSezione(
                stringResource(if (impegni) R.string.impegni_oggi else R.string.dispositivo_regole_oggi),
                modifier = Modifier.padding(top = Spazi.m),
            )
        }
        if (scheda.oggi.isEmpty()) {
            item(key = "oggi-vuoto") {
                StatoVuoto(stringResource(if (impegni) R.string.impegni_nessuno else R.string.regole_nessuna_sul_dispositivo))
            }
        }
        items(scheda.oggi, key = { "oggi-${it.regola.id}" }) { riga ->
            CardDellaRegola(
                regola = riga.regola,
                stato = testoStatoRegola(parole(), riga),
                onClick = { cornice.apri(Pagina.Regola(riga.regola.id)) },
            )
        }

        if (scheda.nonPiuAttive.isNotEmpty()) {
            item(key = "vecchie-titolo") {
                TitoloSezione(stringResource(R.string.dispositivo_non_piu_attive), modifier = Modifier.padding(top = Spazi.m))
            }
            items(scheda.nonPiuAttive, key = { "vecchia-${it.id}" }) { regola ->
                CardDellaRegola(regola = regola, stato = null, onClick = { cornice.apri(Pagina.Regola(regola.id)) })
            }
        }
    }
}

/** In cima alla pagina: "6 su 7", la striscia grande con la legenda, il bonus. Il nome è nella barra. */
@Composable
private fun TestataDelDispositivo(scheda: SchedaDispositivo, bonus: StatoBonus?) {
    val impegni = scheda.diChi == DiChi.Impegni
    CardEvidenza(tono = Tono.Positivo) {
        SopraTitolo(stringResource(R.string.patto_ultimi_giorni), colore = LocalContentColor.current)
        val (mantenuti, conDati) = contaGiorni(scheda.giorni)
        val senzaDati = scheda.giorni.size - conDati
        if (conDati > 0) {
            Text(text = stringResource(R.string.patto_su, mantenuti, conDati), style = MaterialTheme.typography.displaySmall)
            Text(
                text = pluralStringResource(
                    if (impegni) R.plurals.impegni_giorni_dentro else R.plurals.dispositivo_giorni_dentro,
                    conDati,
                ),
                style = MaterialTheme.typography.bodyMedium,
            )
            if (senzaDati > 0) {
                Text(
                    text = pluralStringResource(R.plurals.patto_senza_dati, senzaDati, senzaDati),
                    style = MaterialTheme.typography.labelMedium,
                )
            }
        } else {
            Text(text = stringResource(R.string.patto_nessun_giorno_con_dati), style = MaterialTheme.typography.bodyMedium)
        }
        if (scheda.giorni.isNotEmpty()) {
            Spacer(Modifier.height(Spazi.m))
            StrisciaGiorni(
                giorni = scheda.giorni,
                lato = 32.dp,
                descrizione = descrizioneStriscia(
                    scheda.giorni,
                    if (impegni) R.plurals.striscia_descrizione_impegni else R.plurals.striscia_descrizione_dispositivo,
                ),
            )
            LegendaStriscia(
                mantenuta = stringResource(R.string.legenda_mantenuta),
                fuoriRegola = stringResource(R.string.legenda_fuori_regola),
                senzaDati = stringResource(R.string.legenda_senza_dati),
                oggi = stringResource(R.string.legenda_oggi),
                modifier = Modifier.padding(top = Spazi.s),
            )
        }
        if (bonus != null) RigaBonus(bonus)
    }
}

/**
 * Una regola nella pagina del dispositivo: la frase, lo stato di oggi a parole
 * ([stato]; null per una regola non più attiva, che ha la pillola) e la sua
 * striscia piccola degli 8 giorni. Si tocca per il dettaglio.
 */
@Composable
private fun CardDellaRegola(regola: RegolaFinestra, stato: String?, onClick: () -> Unit) {
    CardNormale(onClick = onClick) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(text = descrizioneRegola(regola), style = MaterialTheme.typography.bodyLarge)
                if (stato != null) {
                    Text(
                        text = stato,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = Spazi.xs),
                    )
                } else {
                    Pillola(stringResource(R.string.regola_eliminata), modifier = Modifier.padding(top = Spazi.xs))
                }
                val giorni = remember(regola.semaforo) { giorniDaQuadretti(regola.semaforo) }
                if (giorni.isNotEmpty()) {
                    StrisciaGiorni(
                        giorni = giorni,
                        lato = 20.dp,
                        mostraNumero = false,
                        descrizione = descrizioneStriscia(giorni, R.plurals.striscia_descrizione_regola),
                        modifier = Modifier.padding(top = Spazi.s),
                    )
                }
            }
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
        }
    }
}

/**
 * (0.23) Un dispositivo nella Panoramica, sotto la card del patto: il nome, il
 * suo "6 su 7", la sua striscia piccola, com'è andata oggi in una riga e, se
 * qualcosa è andato storto, quali regole. Si tocca per la sua pagina.
 */
@Composable
internal fun CardDelDispositivo(scheda: SchedaDispositivo, bonus: StatoBonus?, onClick: () -> Unit) {
    val p = parole()
    CardNormale(onClick = onClick) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            scheda.dispositivo?.let { IconaDispositivo(it.tipo) }
            Text(
                text = nomeDellaScheda(scheda),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier
                    .weight(1f)
                    .padding(start = if (scheda.dispositivo != null) Spazi.s else 0.dp),
            )
            val (mantenuti, conDati) = contaGiorni(scheda.giorni)
            if (conDati > 0) {
                Text(text = stringResource(R.string.patto_su, mantenuti, conDati), style = MaterialTheme.typography.labelLarge)
            }
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
        }
        if (scheda.giorni.isNotEmpty()) {
            StrisciaGiorni(
                giorni = scheda.giorni,
                lato = 20.dp,
                mostraNumero = false,
                descrizione = descrizioneStriscia(
                    scheda.giorni,
                    if (scheda.diChi == DiChi.Impegni) R.plurals.striscia_descrizione_impegni else R.plurals.striscia_descrizione_dispositivo,
                ),
                modifier = Modifier.padding(top = Spazi.s),
            )
        }
        Text(
            text = testoOggiInBreve(scheda),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(top = Spazi.s),
        )
        // Le regole fuori oggi, una riga ciascuna: si vede subito cosa, senza aprire.
        scheda.oggi.filter { it.segnale == Segnale.FUORI_REGOLA }.forEach { riga ->
            Text(
                text = stringResource(R.string.regola_e_stato, descrizioneRegola(riga.regola), testoStatoRegola(p, riga)),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = Spazi.xs),
            )
        }
        if (bonus != null) RigaBonus(bonus)
    }
}

/** Il nome della scheda: quello del dispositivo (scollegato, se lo è) o "Impegni di vita reale". */
@Composable
internal fun nomeDellaScheda(scheda: SchedaDispositivo): String {
    val dispositivo = scheda.dispositivo ?: return stringResource(R.string.impegni_titolo)
    val nome = nomeDelDispositivo(dispositivo)
    return if (dispositivo.revocato) stringResource(R.string.dispositivo_chip_scollegato, nome) else nome
}

/** "Oggi: 1 regola fuori", "Oggi: nessuna regola fuori"…; per gli impegni le loro parole. */
@Composable
internal fun testoOggiInBreve(scheda: SchedaDispositivo): String {
    val impegni = scheda.diChi == DiChi.Impegni
    return when (scheda.oggiInBreve) {
        OggiInBreve.NESSUNA_REGOLA -> stringResource(if (impegni) R.string.impegni_nessuno else R.string.oggi_breve_nessuna_regola)
        OggiInBreve.SENZA_DATI ->
            stringResource(if (impegni) R.string.oggi_breve_impegni_senza_dati else R.string.oggi_breve_senza_dati)
        OggiInBreve.TUTTO_DENTRO ->
            stringResource(if (impegni) R.string.oggi_breve_impegni_tutto_dentro else R.string.oggi_breve_tutto_dentro)
        OggiInBreve.FUORI -> pluralStringResource(
            if (impegni) R.plurals.oggi_breve_impegni_fuori else R.plurals.oggi_breve_fuori,
            scheda.fuoriOggi,
            scheda.fuoriOggi,
        )
    }
}
