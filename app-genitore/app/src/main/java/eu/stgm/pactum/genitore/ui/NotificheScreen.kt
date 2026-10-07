package eu.stgm.pactum.genitore.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.outlined.AddCircle
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import eu.stgm.pactum.design.Spazi
import eu.stgm.pactum.design.RigaStato
import eu.stgm.pactum.design.StatoVuoto
import eu.stgm.pactum.design.Caricamento
import eu.stgm.pactum.genitore.R
import eu.stgm.pactum.genitore.dati.Figlio
import eu.stgm.pactum.genitore.dati.Notifica
import eu.stgm.pactum.genitore.dati.TipiDispositivo

/**
 * Le notifiche non lette del patto, di tutti i figli. (0.15) È una pagina che si
 * apre dalla campanella di ogni scheda; ogni riga si tocca e porta dove si guarda
 * il fatto ([onApri]: la stessa tabella delle notifiche di sistema). "Letta" è un
 * gesto del genitore, qui — la vedetta non segna mai niente da sola.
 */
@Composable
fun NotificheScreen(
    onApri: (ApriDaNotifica) -> Unit,
    vm: NotificheViewModel = viewModel(),
    famigliaVm: FamigliaViewModel = viewModel(),
) {
    val cornice = LocalCornice.current
    val stato by vm.stato.collectAsStateWithLifecycle()
    // (v3) Le notifiche sono di tutti i figli: la famiglia dice di chi è ciascuna.
    val famiglia by famigliaVm.stato.collectAsStateWithLifecycle()
    val messaggioLettaFallita = stringResource(R.string.notifica_letta_fallita)
    val testi = parole()
    var confermaTutte by rememberSaveable { mutableStateOf(false) }

    LifecycleResumeEffect(Unit) {
        vm.aggiorna()
        onPauseOrDispose { }
    }

    // Un fallimento di "segna come letta" si dice una volta: l'evento si consuma
    // subito (come l'esito del segno), così riaprire la lista non lo ripete. Lo
    // snackbar parte in uno scope suo: consumare cambia la chiave e
    // cancellerebbe questo effetto a metà messaggio.
    LaunchedEffect(stato.lettaFallita) {
        if (!stato.lettaFallita) return@LaunchedEffect
        vm.consumaLettaFallita()
        cornice.messaggi.mostra(messaggioLettaFallita)
    }
    // (0.9) "Segna tutte come lette" andata a metà: si dice quante sono rimaste.
    LaunchedEffect(stato.tutteFallite) {
        val fallite = stato.tutteFallite ?: return@LaunchedEffect
        vm.consumaTutteFallite()
        cornice.messaggi.mostra(testi.testo(R.string.notifiche_segna_tutte_fallite, fallite))
    }

    if (confermaTutte) {
        // Non si torna indietro: prima una domanda, che dice la verità su cosa succede.
        AlertDialog(
            onDismissRequest = { confermaTutte = false },
            title = { Text(stringResource(R.string.notifiche_segna_tutte_titolo)) },
            text = { Text(stringResource(R.string.notifiche_segna_tutte_testo)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confermaTutte = false
                        vm.segnaTutteLette()
                    },
                ) {
                    Text(stringResource(R.string.notifiche_segna_tutte_conferma))
                }
            },
            dismissButton = {
                TextButton(onClick = { confermaTutte = false }) {
                    Text(stringResource(R.string.azione_annulla))
                }
            },
        )
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0.dp),
        topBar = {
            BarraPagina(stringResource(R.string.notifiche_titolo)) {
                IconButton(onClick = { vm.aggiorna() }) {
                    Icon(Icons.Filled.Refresh, stringResource(R.string.azione_aggiorna))
                }
            }
        },
    ) { padding ->
        Box(modifier = Modifier.padding(padding).fillMaxSize()) {
            when {
                // (0.15) 401: il collegamento di questo telefono non vale più. Non è la rete (B26).
                famiglia.collegamentoNonValido -> StatoVuoto(
                    centrato = true,
                    titolo = stringResource(R.string.collegamento_non_valido_titolo),
                    testo = stringResource(R.string.collegamento_non_valido),
                    azione = stringResource(R.string.azione_collega_di_nuovo),
                    onAzione = { cornice.apri(Pagina.Impostazioni(SezioneImpostazioni.COLLEGAMENTO)) },
                )

                stato.configurazioneMancante -> StatoVuoto(
                    centrato = true,
                    titolo = stringResource(R.string.config_mancante_titolo),
                    testo = stringResource(R.string.notifiche_config_mancante),
                    azione = stringResource(R.string.azione_collega),
                    onAzione = { cornice.apri(Pagina.Impostazioni(SezioneImpostazioni.COLLEGAMENTO)) },
                )

                // Solo la prima lettura: dopo, le riletture del badge (ogni
                // minuto) aggiornano la lista senza coprirla con la rotella.
                stato.caricamento && !stato.primaLetturaFatta ->
                    Caricamento(testo = stringResource(R.string.notifiche_caricamento))

                stato.errore && stato.notifiche.isEmpty() -> StatoVuoto(stringResource(R.string.notifiche_errore), centrato = true)

                else -> LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(vertical = Spazi.s),
                ) {
                    // Aggiornamento fallito con una lista già in mano: si dice che i
                    // dati sono vecchi, invece di spacciarli per freschi in silenzio.
                    if (stato.errore) {
                        item(key = "dati-vecchi") {
                            Box(modifier = Modifier.padding(horizontal = Spazi.l, vertical = Spazi.s)) {
                                RigaStato(stringResource(R.string.notifiche_dati_vecchi))
                            }
                        }
                    }
                    if (stato.notifiche.isEmpty()) {
                        item(key = "vuoto") {
                            StatoVuoto(
                                stringResource(R.string.notifiche_vuoto),
                                icona = Icons.Outlined.CheckCircle,
                                modifier = Modifier.padding(Spazi.l),
                            )
                        }
                    } else if (stato.notifiche.size > 1) {
                        // (0.9) Con più di una, tutte insieme (dopo una conferma).
                        item(key = "segna-tutte") {
                            TextButton(
                                onClick = { confermaTutte = true },
                                enabled = !stato.segnaturaInCorso,
                                modifier = Modifier.padding(horizontal = Spazi.s),
                            ) {
                                Text(stringResource(R.string.notifiche_segna_tutte))
                            }
                        }
                    }
                    // (0.15) Una riga per notifica, ciascuna un elemento suo (B16), e
                    // ciascuna si tocca per andare dove si guarda il fatto (B38).
                    items(stato.notifiche, key = { "notifica-${it.id}" }) { notifica ->
                        RigaNotifica(
                            notifica = notifica,
                            // (0.10) Con la famiglia (chi propone), le proposte
                            // (che cosa) e i nomi delle app (mai un pacchetto);
                            // (0.11) e le sessioni (quali app chiede).
                            testo = testoNotifica(
                                parole(),
                                notifica,
                                stato.regolePerId,
                                famiglia.figli,
                                stato.propostePerId,
                                stato.nomi,
                                stato.sessioniPerId,
                                // (0.13) Qui la riga dice il fatto; il tocco porta alla foto.
                                nellaTendina = false,
                            ),
                            diChi = etichettaNotifica(notifica, famiglia.figli),
                            icona = iconaTipo(notifica, famiglia.figli),
                            onApri = { onApri(destinazioneDellaRiga(notifica)) },
                            onSegnaLetta = { vm.segnaLetta(notifica) },
                        )
                        HorizontalDivider(
                            color = MaterialTheme.colorScheme.outlineVariant,
                            modifier = Modifier.padding(horizontal = Spazi.l),
                        )
                    }
                }
            }
        }
    }
}

/**
 * Una notifica: icona del tipo, di chi è, cosa è successo, quando — e "Letta" a
 * destra (una parola, non una spunta che sembra "approva"). Tutta la riga si tocca
 * e porta dove si guarda il fatto ([onApri]). Il [testo] lo scrive l'app
 * (testoNotifica, lo stesso della notifica di sistema). (v3) [diChi] = di quale
 * figlio (e dispositivo), con più figli o più dispositivi.
 */
@Composable
private fun RigaNotifica(
    notifica: Notifica,
    testo: TestoNotifica,
    diChi: String?,
    icona: Painter,
    onApri: () -> Unit,
    onSegnaLetta: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onApri)
            .padding(start = Spazi.l, end = Spazi.xs, top = Spazi.m, bottom = Spazi.m),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(
            painter = icona,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = Spazi.xs).size(24.dp),
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(start = Spazi.m),
        ) {
            val sopra = listOfNotNull(diChi, istanteServer(notifica.tsServer)?.let { testoQuando(parole(), it) })
            if (sopra.isNotEmpty()) SopraTitolo(sopra.joinToString(" · "))
            Text(
                text = testo.titolo,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                text = testo.testo,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = Spazi.xs),
            )
        }
        val descrizione = stringResource(R.string.notifica_segna_letta)
        TextButton(onClick = onSegnaLetta, modifier = Modifier.semantics { contentDescription = descrizione }) {
            Text(stringResource(R.string.notifica_letta))
        }
    }
}

/**
 * L'icona per tipo: mai un colore d'allarme, solo una forma che si riconosce.
 * (0.15) Spento e acceso hanno l'icona del dispositivo VERO: un telefono spento
 * quella del telefono, non del computer (B19).
 */
@Composable
private fun iconaTipo(notifica: Notifica, figli: List<Figlio>): Painter = when (notifica.tipo) {
    "sforamento" -> painterResource(R.drawable.ic_scheda_tempo)
    "manomissione" -> rememberVectorPainter(Icons.Outlined.Info)
    "bonus" -> rememberVectorPainter(Icons.Outlined.AddCircle)
    "modifica_regola" -> rememberVectorPainter(Icons.Outlined.Edit)
    // (0.10) Anche le proposte del figlio e i suoi ritiri: è la scheda dove si decide.
    "proposta_risposta", "proposta_annullata", "nuova_proposta", "proposta_ritirata", "dichiarazione" ->
        painterResource(R.drawable.ic_scheda_turno)
    "sospensione", "ripresa" -> painterResource(
        if (tipoDelDispositivo(notifica, figli) == TipiDispositivo.COMPUTER) {
            R.drawable.ic_dispositivo_computer
        } else {
            R.drawable.ic_dispositivo_telefono
        },
    )
    // (0.11) Le sessioni: sono del telefono.
    "sessione_da_approvare", "sessione_eliminata" -> painterResource(R.drawable.ic_dispositivo_telefono)
    // (0.13) I lavori di casa.
    TipiNotificaFaccende.FACCENDA_FATTA, TipiNotificaFaccende.FACCENDE_FINITE, TipiNotificaFaccende.FACCENDA_CONFERMATA ->
        painterResource(R.drawable.ic_scheda_lavori)
    // (0.18) La configurazione dello Studio si decide in "Da decidere".
    TipiNotificaStudio.DA_APPROVARE -> painterResource(R.drawable.ic_scheda_turno)
    else -> painterResource(R.drawable.ic_notifica_binocolo)
}

/** Il tipo del dispositivo della notifica (dalla famiglia); senza, il telefono. */
private fun tipoDelDispositivo(notifica: Notifica, figli: List<Figlio>): String {
    val id = notifica.dispositivoId ?: return TipiDispositivo.TELEFONO
    return figli.flatMap { it.dispositivi }.firstOrNull { it.id == id }?.tipo ?: TipiDispositivo.TELEFONO
}
