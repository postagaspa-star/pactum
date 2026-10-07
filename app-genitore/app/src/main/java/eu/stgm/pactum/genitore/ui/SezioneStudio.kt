package eu.stgm.pactum.genitore.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import eu.stgm.pactum.design.CardEvidenza
import eu.stgm.pactum.design.CardNormale
import eu.stgm.pactum.design.FilaPulsanti
import eu.stgm.pactum.design.Pillola
import eu.stgm.pactum.design.Spazi
import eu.stgm.pactum.design.Tono
import eu.stgm.pactum.genitore.R
import eu.stgm.pactum.genitore.dati.EsitiSessione
import eu.stgm.pactum.genitore.dati.MASSIMO_MOTIVAZIONE_STUDIO
import eu.stgm.pactum.genitore.dati.RiferimentoGenitore
import eu.stgm.pactum.genitore.dati.StudioSvolto
import java.time.Instant
import java.time.LocalDate

// (0.18, contratto v4.0, parte C) I pezzi della Sessione Studio che si vedono in
// più posti: la card dello Studio di oggi (Panoramica), la domanda "Chiudi lo
// Studio" col motivo obbligatorio, la card della configurazione da approvare ("Da
// decidere") con la sua domanda, e uno Studio fatto (la pagina dello Studio).

/**
 * Lo Studio di oggi, nella Panoramica: in corso ("Studio dalle 15:00 · 42 min
 * dichiarati col timer su 60 · si chiude dopo le 16:00", e "Chiudi lo Studio"),
 * chiuso (con la dichiarazione o il motivo), quello di ieri non chiuso, o quando
 * parte oggi. Sotto, i dispositivi dove lo Studio non c'è, e "Vedi lo Studio".
 */
@Composable
internal fun CardStudioDiOggi(
    oggi: StudioDiOggi,
    nomeFiglio: String?,
    io: RiferimentoGenitore?,
    adesso: Instant,
    senzaStudio: List<DispositivoSenzaBlocco>,
    invio: Boolean,
    onChiudi: (StudioSvolto) -> Unit,
    onApri: () -> Unit,
) {
    val p = parole()
    val contenuto: @Composable () -> Unit = {
        SopraTitolo(stringResource(R.string.studio_di_oggi), colore = androidx.compose.material3.LocalContentColor.current)
        val testo = when (oggi) {
            is StudioDiOggi.InCorso -> testoStatoStudio(p, oggi.studio, adesso)
            is StudioDiOggi.Chiuso -> testoStudioSvolto(p, oggi.studio, io, nomeFiglio, adesso)
            is StudioDiOggi.NonChiusoIeri -> testoIeriNonChiuso(p, oggi.studio)
            is StudioDiOggi.Parte -> p.testo(R.string.studio_parte_alle, oraBreve(oggi.inizio))
            StudioDiOggi.NonApprovato -> nomeDaScrivere(nomeFiglio)?.let { p.testo(R.string.studio_non_approvato, it) }
                ?: p.testo(R.string.studio_non_approvato_senza_nome)
            StudioDiOggi.Niente -> p.testo(R.string.studio_niente_oggi)
        }
        Text(text = testo, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = Spazi.xs))
        when (oggi) {
            is StudioDiOggi.InCorso -> {
                // (Che il blocco dei lavori parte a fine Studio lo dice la riga del blocco, in cima.)
                testoChiudibile(p, oggi.studio, nomeFiglio)?.let {
                    Text(text = it, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = Spazi.xs))
                }
            }
            is StudioDiOggi.Chiuso -> righeChiusuraStudio(p, oggi.studio).forEach {
                Text(text = it, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = Spazi.xs))
            }
            else -> Unit
        }
        senzaStudio.forEach {
            Text(
                text = testoDispositivoSenzaStudio(p, it),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = Spazi.s),
            )
        }
        FilaPulsanti(modifier = Modifier.padding(top = Spazi.m)) {
            if (oggi is StudioDiOggi.InCorso && chiudibileDalGenitore(oggi.studio)) {
                OutlinedButton(onClick = { onChiudi(oggi.studio) }, enabled = !invio) {
                    Text(stringResource(R.string.studio_chiudi), maxLines = 1, softWrap = false)
                }
            }
            TextButton(onClick = onApri) {
                Text(stringResource(R.string.studio_vedi), maxLines = 1, softWrap = false)
            }
        }
    }
    if (oggi is StudioDiOggi.InCorso) {
        CardEvidenza(tono = Tono.Neutro) { contenuto() }
    } else {
        CardNormale { contenuto() }
    }
}

/**
 * "15:00", nel fuso del PATTO (parte E, «Fuso»): come gli orari approvati e come
 * "oggi" della Panoramica, anche con un telefono in viaggio in un altro fuso.
 */
internal fun oraBreve(istante: Instant, zona: java.time.ZoneId = FUSO_PATTO): String =
    java.time.format.DateTimeFormatter.ofPattern("HH:mm").format(istante.atZone(zona))

/**
 * "Chiudere lo Studio di Luca?": che cosa succede, e il MOTIVO obbligatorio (da 3
 * a 300 caratteri). "Chiudi lo Studio" si accende solo quando il motivo va bene.
 */
@Composable
internal fun DialogoChiudiStudio(
    nomeFiglio: String?,
    studioId: Long,
    onChiudi: (String) -> Unit,
    onAnnulla: () -> Unit,
) {
    val p = parole()
    var motivo by rememberSaveable(studioId) { mutableStateOf("") }
    val problema = problemaMotivo(motivo)
    AlertDialog(
        onDismissRequest = onAnnulla,
        title = {
            Text(
                nomeDaScrivere(nomeFiglio)?.let { stringResource(R.string.studio_chiudi_titolo, it) }
                    ?: stringResource(R.string.studio_chiudi_titolo_senza_nome),
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Spazi.s)) {
                Text(stringResource(R.string.studio_chiudi_testo))
                OutlinedTextField(
                    value = motivo,
                    onValueChange = { motivo = it },
                    label = { Text(stringResource(R.string.studio_chiudi_campo)) },
                    // L'errore si dice solo quando c'è già qualcosa di scritto.
                    isError = problema != null && problema != ProblemaMotivo.VUOTO,
                    supportingText = { Text(testoProblemaMotivo(p, problema)) },
                    maxLines = 4,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            Button(onClick = { onChiudi(motivo) }, enabled = problema == null) {
                Text(stringResource(R.string.studio_chiudi), maxLines = 1, softWrap = false)
            }
        },
        dismissButton = {
            TextButton(onClick = onAnnulla) { Text(stringResource(R.string.azione_annulla)) }
        },
    )
}

/**
 * La configurazione dello Studio da approvare, in "Da decidere": chi la chiede e
 * da dove, com'è (giorni, orari, minimo, le due liste, con quello che cambia
 * rispetto all'approvata), quando vale, e Approva / Rifiuta con la versione vista.
 */
@Composable
internal fun CardRichiestaStudio(
    richiesta: RichiestaStudio,
    nomeFiglio: String?,
    invio: Boolean,
    onApri: (esito: String) -> Unit,
) {
    val p = parole()
    CardNormale {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Pillola(stringResource(R.string.studio_titolo))
            Spacer(modifier = Modifier.weight(1f))
        }
        Text(
            text = testoChiedeStudio(p, nomeFiglio, richiesta.cambio),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(top = Spazi.s),
        )
        testoPropostaDa(p, richiesta.proposta)?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = Spazi.xs),
            )
        }
        ContenutoRichiestaStudio(richiesta)
        Text(
            text = stringResource(R.string.studio_lista_stretta),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = Spazi.s),
        )
        FilaPulsanti(modifier = Modifier.padding(top = Spazi.m)) {
            Button(onClick = { onApri(EsitiSessione.APPROVA) }, enabled = !invio) {
                Text(stringResource(R.string.studio_approva), maxLines = 1, softWrap = false)
            }
            OutlinedButton(onClick = { onApri(EsitiSessione.RIFIUTA) }, enabled = !invio) {
                Text(stringResource(R.string.studio_rifiuta), maxLines = 1, softWrap = false)
            }
        }
    }
}

/** Le righe della proposta e quando vale: sulla card e nella domanda prima del sì. */
@Composable
private fun ContenutoRichiestaStudio(richiesta: RichiestaStudio) {
    val p = parole()
    Column(modifier = Modifier.padding(top = Spazi.s), verticalArrangement = Arrangement.spacedBy(Spazi.xs)) {
        righeRichiestaStudio(p, richiesta).forEach {
            Text(text = it, style = MaterialTheme.typography.bodyMedium)
        }
        righeQuandoValeStudio(p, richiesta).forEach {
            Text(text = it, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        }
        richiesta.motivazionePrecedente?.trim()?.takeIf { it.isNotEmpty() }?.let {
            Text(
                text = p.testo(R.string.studio_motivazione_precedente, it),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * La domanda prima di decidere la configurazione: per il sì, di nuovo com'è e
 * quando vale (si approva quello che si vede, con la sua versione); per il no, il
 * perché facoltativo (lo legge il figlio).
 */
@Composable
internal fun DialogoDecisioneStudio(
    esito: String,
    richiesta: RichiestaStudio,
    nomeFiglio: String?,
    onConferma: (String?) -> Unit,
    onAnnulla: () -> Unit,
) {
    val approva = esito == EsitiSessione.APPROVA
    var perche by rememberSaveable(richiesta.versione) { mutableStateOf("") }
    val nome = nomeDaScrivere(nomeFiglio)
    AlertDialog(
        onDismissRequest = onAnnulla,
        title = {
            Text(
                when {
                    approva && nome != null -> stringResource(R.string.studio_approva_titolo, nome)
                    approva -> stringResource(R.string.studio_approva_titolo_senza_nome)
                    nome != null -> stringResource(R.string.studio_rifiuta_titolo, nome)
                    else -> stringResource(R.string.studio_rifiuta_titolo_senza_nome)
                },
            )
        },
        text = {
            Column(
                modifier = Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(Spazi.s),
            ) {
                if (approva) {
                    ContenutoRichiestaStudio(richiesta)
                } else {
                    Text(stringResource(R.string.studio_rifiuta_testo))
                    OutlinedTextField(
                        value = perche,
                        onValueChange = { perche = it.take(MASSIMO_MOTIVAZIONE_STUDIO) },
                        label = { Text(stringResource(R.string.proposta_campo_perche)) },
                        supportingText = { Text(stringResource(R.string.sessione_perche_massimo, MASSIMO_MOTIVAZIONE_STUDIO)) },
                        maxLines = 4,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        },
        confirmButton = {
            Button(onClick = { onConferma(perche.ifBlank { null }) }) {
                Text(stringResource(if (approva) R.string.studio_approva else R.string.studio_rifiuta), maxLines = 1, softWrap = false)
            }
        },
        dismissButton = {
            TextButton(onClick = onAnnulla) { Text(stringResource(R.string.azione_annulla)) }
        },
    )
}

/**
 * Uno Studio fatto (o in corso), nella pagina dello Studio: il giorno, come è
 * cominciato, com'è finito (o lo stato), la dichiarazione o il motivo, e i tratti
 * di attività con tipo, parola, lavoro e minuti (anche gli interrotti).
 */
@Composable
internal fun CardStudioSvolto(
    studio: StudioSvolto,
    io: RiferimentoGenitore?,
    nomeFiglio: String?,
    adesso: Instant,
    titoloLavoro: (Long) -> String?,
) {
    val p = parole()
    CardNormale {
        val giorno = studio.giorno?.let { g -> runCatching { LocalDate.parse(g) }.getOrNull() }
        val sopra = listOfNotNull(
            giorno?.let { testoGiorno(p, it, LocalDate.now(FUSO_PATTO)) },
            testoOrigineStudio(p, studio),
        ).joinToString(" · ")
        SopraTitolo(sopra)
        Text(
            text = testoStudioSvolto(p, studio, io, nomeFiglio, adesso),
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.padding(top = Spazi.xs),
        )
        righeChiusuraStudio(p, studio).forEach {
            Text(
                text = it,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = Spazi.xs),
            )
        }
        studio.sessioneChiusa?.nome?.trim()?.takeIf { it.isNotEmpty() }?.let {
            Text(
                text = p.testo(R.string.studio_sessione_chiusa, it),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = Spazi.xs),
            )
        }
        val tratti = trattiInOrdine(studio)
        Column(modifier = Modifier.padding(top = Spazi.s), verticalArrangement = Arrangement.spacedBy(Spazi.xs)) {
            if (tratti.isEmpty()) {
                Text(
                    text = stringResource(R.string.studio_nessun_tratto),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            tratti.forEach {
                Text(text = "• " + testoTratto(p, it, titoloLavoro), style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}
