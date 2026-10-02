package eu.stgm.pactum.genitore.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import eu.stgm.pactum.design.Spazi
import eu.stgm.pactum.genitore.R
import eu.stgm.pactum.genitore.dati.EsitiSessione
import eu.stgm.pactum.genitore.dati.MASSIMO_MOTIVAZIONE_SESSIONE
import eu.stgm.pactum.genitore.dati.Sessione

// (0.11) Le sessioni nella Panoramica (contratto v3.5). In cima, insieme alle
// proposte del figlio, una card per ogni sessione che aspetta il genitore — una
// nuova, o un cambio della lista di una già approvata — con "Approva" e "Non
// approvare". Più giù, la sezione Sessioni: quelle fatte negli 8 giorni (inizio,
// durata, fine, chiusure anticipate), quelle approvate con le loro app e quelle
// non più valide perché il telefono è scollegato. Quali app il figlio ha provato ad
// aprire durante una sessione non si vede: non arriva nemmeno al server
// (decisione di Andrea).
//
// La domanda prima del sì (o del no) non vive nella card ma nella Panoramica
// (DomandaSessione): si apre su UNA versione della richiesta, mostra quella, e si
// risponde con quella. Se la card cambia mentre la domanda è aperta (il giro di
// ogni minuto porta una versione nuova), la domanda si chiude e lo si dice: il
// genitore non approva mai una lista che non ha visto.
//
// (0.12) Accanto al nome di ogni sessione, la prima emoji del suo tema (scelto dal
// nome come nell'app del figlio): "📚 Studio", e nelle frasi "la sessione 📚 «Studio»".
// Le frasi le fa Testi.kt (nomeSessioneConEmoji, nomeSessioneTraVirgolette).

/**
 * Come si salva la domanda aperta su una sessione: id, gesto e versione vista.
 * Il contenuto no: dopo una rotazione (o un'app chiusa da Android) si riprende
 * dalla card, ma solo se la versione è ancora quella (v. statoDomanda).
 */
internal val SalvaDomandaSessione: Saver<DomandaSessione?, Any> = Saver(
    save = { domanda -> domanda?.let { arrayListOf<Any>(it.sessioneId, it.esito, it.versione) } },
    restore = { salvata ->
        (salvata as? List<*>)?.let { campi ->
            val id = (campi.getOrNull(0) as? Number)?.toLong()
            val esito = campi.getOrNull(1) as? String
            val versione = (campi.getOrNull(2) as? Number)?.toInt()
            if (id == null || esito == null || versione == null) null else DomandaSessione(id, esito, versione)
        }
    },
)

/**
 * Una sessione che aspetta il genitore: chi la chiede e quale (per un cambio,
 * anche quando l'ha chiesto), le sue app (per un cambio, che cosa cambia), che
 * cosa vuol dire approvarla, e i due gesti. [onApri] apre la domanda su questa
 * versione della richiesta. Senza `versione` (un server che non la manda) non si
 * risponde: i gesti sono spenti, e una riga dice che serve aggiornare il server.
 * [dove] = su quale telefono, quando serve ("sul telefono «Telefono di Luca»");
 * [nomiFinestra] = i nomi delle app nei dati d'uso del telefono.
 */
@Composable
internal fun CardSessioneDaApprovare(
    richiesta: SessioneDaApprovare,
    nomeFiglio: String?,
    dove: String?,
    nomiFinestra: Map<String, String>,
    invioInCorso: Boolean,
    onApri: (esito: String) -> Unit,
) {
    val p = parole()
    val senzaVersione = richiesta.sessione.versione == null
    CardContenuto {
        Column(modifier = Modifier.padding(Spazi.l)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Etichetta(stringResource(R.string.sessione_tag))
                Spacer(modifier = Modifier.weight(1f))
                // Un cambio dice quando è stato chiesto (richiesta_ts). Una sessione
                // nuova no: `creata_ts` non è l'ora della richiesta se è stata
                // cambiata o richiesta di nuovo dopo un no.
                val chiesto = if (richiesta.cambio) quandoChiesta(p, richiesta.sessione.modificaInAttesa?.richiestaTs) else null
                if (chiesto != null) {
                    Text(
                        text = chiesto,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Text(
                text = chiedeLaSessione(p, nomeFiglio, nomeNelTitolo(richiesta), richiesta.cambio),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(top = Spazi.s),
            )
            if (dove != null) {
                Text(
                    text = dove,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = Spazi.xs),
                )
            }
            ContenutoRichiesta(richiesta, nomiFinestra, limita = true)
            Text(
                text = stringResource(R.string.sessione_non_conta),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = Spazi.m),
            )
            Text(
                text = seApprovi(p, richiesta, nomeFiglio),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = Spazi.s),
            )
            if (senzaVersione) {
                Text(
                    text = stringResource(R.string.sessione_errore_server_da_aggiornare),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = Spazi.s),
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = Spazi.s),
                horizontalArrangement = Arrangement.spacedBy(Spazi.s),
            ) {
                val attivi = !invioInCorso && !senzaVersione
                Button(onClick = { onApri(EsitiSessione.APPROVA) }, enabled = attivi, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.sessione_approva))
                }
                OutlinedButton(onClick = { onApri(EsitiSessione.RIFIUTA) }, enabled = attivi, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.sessione_non_approvare))
                }
            }
        }
    }
}

/** Che cosa vuol dire approvare: una sessione nuova si avvia quando vuole; un cambio vale dalla prossima volta. */
private fun seApprovi(p: Parole, richiesta: SessioneDaApprovare, nomeFiglio: String?): String =
    if (richiesta.cambio) p.testo(R.string.sessione_cambio_se_approvi) else seApproviLaSessione(p, nomeFiglio)

/** Il nome nel titolo: per un cambio quello di adesso (il nuovo, se cambia, lo dice la riga sotto). */
private fun nomeNelTitolo(richiesta: SessioneDaApprovare): String =
    if (richiesta.cambio) richiesta.sessione.nome else richiesta.nome

/**
 * Che cosa chiede una richiesta: le app di una sessione nuova, o che cosa cambia
 * un cambio. Sulla card ([limita]) al massimo [APP_VISIBILI] app per elenco, poi "e
 * altre N"; nella domanda prima del sì tutte.
 */
@Composable
private fun ContenutoRichiesta(richiesta: SessioneDaApprovare, nomiFinestra: Map<String, String>, limita: Boolean) {
    val differenze = richiesta.differenze?.takeIf { richiesta.cambio }
    if (differenze == null) {
        SopraTitolo(stringResource(R.string.sessione_le_app), modifier = Modifier.padding(top = Spazi.m))
        ElencoAppDellaSessione(appDellaSessione(richiesta.app, richiesta.nomi, nomiFinestra), limita)
    } else {
        CambioDellaSessione(differenze, richiesta.nomi, nomiFinestra, limita)
    }
}

/**
 * Un cambio della lista: il nome nuovo; le app che aggiunge e quelle che toglie, e
 * in una riga quelle che restano; le app che restano ma cambiano nome ("«Classe
 * Viva» → «ClasseViva»", e "CAMBIANO SOLO I NOMI" quando è tutto lì). Se non
 * cambiano le app, la lista intera; se non cambia niente, lo si dice.
 */
@Composable
private fun CambioDellaSessione(
    differenze: CambioSessione,
    nomiSessione: Map<String, String>,
    nomiFinestra: Map<String, String>,
    limita: Boolean,
) {
    val p = parole()
    differenze.nuovoNome?.let {
        Text(
            text = testoNuovoNome(p, it),
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.padding(top = Spazi.s),
        )
    }
    if (differenze.vuoto) {
        Text(
            text = stringResource(R.string.sessione_non_cambia_niente),
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.padding(top = Spazi.s),
        )
    }
    val appCambiano = differenze.aggiunte.isNotEmpty() || differenze.tolte.isNotEmpty()
    if (differenze.aggiunte.isNotEmpty()) {
        SopraTitolo(stringResource(R.string.sessione_aggiunge), modifier = Modifier.padding(top = Spazi.m))
        ElencoAppDellaSessione(appDellaSessione(differenze.aggiunte, nomiSessione, nomiFinestra), limita)
    }
    if (differenze.tolte.isNotEmpty()) {
        SopraTitolo(stringResource(R.string.sessione_toglie), modifier = Modifier.padding(top = Spazi.m))
        ElencoAppDellaSessione(appDellaSessione(differenze.tolte, nomiSessione, nomiFinestra), limita)
    }
    if (differenze.nomiCambiati.isNotEmpty()) {
        SopraTitolo(
            stringResource(if (differenze.soloNomi) R.string.sessione_cambiano_solo_i_nomi else R.string.sessione_cambiano_anche_i_nomi),
            modifier = Modifier.padding(top = Spazi.m),
        )
        Column(
            modifier = Modifier.fillMaxWidth().padding(top = Spazi.xs),
            verticalArrangement = Arrangement.spacedBy(Spazi.xs),
        ) {
            differenze.nomiCambiati.forEach {
                Text(text = testoNomeCambiato(p, it, nomiFinestra), style = MaterialTheme.typography.bodyLarge)
            }
        }
    }
    if (appCambiano) {
        elencoAppSessione(p, appDellaSessione(differenze.restano, nomiSessione, nomiFinestra), inFrase = true)?.let {
            Text(
                text = stringResource(R.string.sessione_restano, it),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = Spazi.s),
            )
        }
    } else {
        // Le app non cambiano: la lista intera, coi nomi nuovi.
        SopraTitolo(stringResource(R.string.sessione_le_app), modifier = Modifier.padding(top = Spazi.m))
        ElencoAppDellaSessione(appDellaSessione(differenze.restano, nomiSessione, nomiFinestra), limita)
    }
}

/**
 * Le app di una sessione, una per riga, sulla card che le spiega: i nomi (quello
 * dei dati d'uso, e accanto quello della sessione se è diverso); le app senza un
 * nome leggibile con la loro chiave, piccola, così il genitore non approva un'app
 * che non riconosce; e in fondo le app installate da APK, con una riga che dice
 * che cosa sono. Con [limita], al massimo [APP_VISIBILI] app e poi "e altre N app".
 */
@Composable
private fun ElencoAppDellaSessione(app: AppDellaSessione, limita: Boolean) {
    val p = parole()
    val massimo = if (limita) APP_VISIBILI else Int.MAX_VALUE
    val nomi = app.nomi.take(massimo)
    val chiavi = app.senzaNome.take((massimo - nomi.size).coerceAtLeast(0))
    val nascoste = app.nomi.size + app.senzaNome.size - nomi.size - chiavi.size
    Column(
        modifier = Modifier.fillMaxWidth().padding(top = Spazi.xs),
        verticalArrangement = Arrangement.spacedBy(Spazi.xs),
    ) {
        nomi.forEach { Text(text = testoNomeApp(p, it), style = MaterialTheme.typography.bodyLarge) }
        chiavi.forEach {
            Text(
                text = stringResource(R.string.sessione_app_pacchetto, it),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (nascoste > 0) {
            Text(
                text = stringResource(R.string.sessione_e_altre_app, nascoste),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (app.gruppoApk) {
            Column {
                Text(text = stringResource(R.string.sessione_app_apk), style = MaterialTheme.typography.bodyLarge)
                Text(
                    text = stringResource(R.string.sessione_app_apk_spiega),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * La domanda prima di rispondere a [richiesta], cioè alla versione che il
 * genitore aveva davanti quando l'ha aperta (mai a quella che arriva dopo). Sul sì
 * mostra di nuovo, per intero, che cosa approva — le app, o che cosa cambia — e
 * che cosa vuol dire; sul no che cosa succede, col perché facoltativo (al massimo
 * 500 caratteri).
 */
@Composable
internal fun DialogoDecisioneSessione(
    esito: String,
    richiesta: SessioneDaApprovare,
    nomeFiglio: String?,
    nomiFinestra: Map<String, String>,
    onConferma: (String?) -> Unit,
    onAnnulla: () -> Unit,
) {
    val p = parole()
    var perche by rememberSaveable { mutableStateOf("") }
    val approva = esito == EsitiSessione.APPROVA
    val nome = nomeSessioneTraVirgolette(p, nomeNelTitolo(richiesta))
    AlertDialog(
        onDismissRequest = onAnnulla,
        title = {
            Text(
                stringResource(
                    when {
                        approva && richiesta.cambio -> R.string.sessione_approva_cambio_titolo
                        approva -> R.string.sessione_approva_titolo
                        richiesta.cambio -> R.string.sessione_non_approvare_cambio_titolo
                        else -> R.string.sessione_non_approvare_titolo
                    },
                    nome,
                ),
            )
        },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(Spazi.m),
            ) {
                if (approva) {
                    // Che cosa approva, per intero: la stessa versione della card che ha aperto.
                    Column { ContenutoRichiesta(richiesta, nomiFinestra, limita = false) }
                    Text(stringResource(R.string.sessione_non_conta), style = MaterialTheme.typography.bodyMedium)
                    Text(seApprovi(p, richiesta, nomeFiglio), style = MaterialTheme.typography.bodyMedium)
                } else {
                    Text(
                        text = stringResource(
                            if (richiesta.cambio) {
                                R.string.sessione_non_approvare_cambio_spiega
                            } else {
                                R.string.sessione_non_approvare_spiega
                            },
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    // Il server ne accetta al massimo 500 (contratto v3.5): il campo si
                    // ferma lì, e lo dice.
                    OutlinedTextField(
                        value = perche,
                        onValueChange = { perche = it.take(MASSIMO_MOTIVAZIONE_SESSIONE) },
                        label = { Text(stringResource(R.string.proposta_campo_perche)) },
                        supportingText = {
                            Text(stringResource(R.string.sessione_perche_massimo, MASSIMO_MOTIVAZIONE_SESSIONE))
                        },
                        maxLines = 4,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        },
        confirmButton = {
            Button(onClick = { onConferma(if (approva) null else perche.trim().ifBlank { null }) }) {
                Text(stringResource(if (approva) R.string.sessione_approva else R.string.sessione_non_approvare))
            }
        },
        dismissButton = {
            TextButton(onClick = onAnnulla) { Text(stringResource(R.string.azione_annulla)) }
        },
    )
}

/**
 * La sezione Sessioni della Panoramica: che cosa sono, in una riga; le sessioni
 * fatte negli 8 giorni, dalla più recente ("📚 Studio · oggi 15:02–16:40 · chiusa
 * prima (prevista 2 h)"), le prime [SESSIONI_SVOLTE_VISIBILI] e le altre dietro un
 * tocco; le sessioni approvate, che il figlio può avviare, con le loro app, chiuse
 * di default; e quelle non più valide perché il loro telefono è scollegato
 * ([nonPiuValide]). Non c'è se non c'è niente di tutto questo (o se il server non
 * conosce le sessioni). [telefono] = il nome del telefono da scrivere sopra una
 * riga (null = non serve: un telefono solo).
 */
internal fun LazyListScope.sezioneSessioni(
    svolte: List<SessioneRaccontata>,
    approvate: List<Sessione>,
    nonPiuValide: List<Sessione>,
    nomiFinestra: Map<String, String>,
    telefono: (Long?) -> String?,
    tutteLeSvolte: Boolean,
    onTutteLeSvolte: () -> Unit,
    approvateAperte: Boolean,
    onApprovate: () -> Unit,
) {
    if (svolte.isEmpty() && approvate.isEmpty() && nonPiuValide.isEmpty()) return
    item(key = "sessioni-titolo") {
        Column(modifier = Modifier.fillMaxWidth()) {
            TitoloSezione(stringResource(R.string.sezione_sessioni))
            Text(
                text = stringResource(R.string.sessioni_spiega),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = Spazi.xs),
            )
        }
    }
    if (svolte.isEmpty()) {
        item(key = "sessioni-nessuna") { RigaVuota(stringResource(R.string.sessioni_nessuna_svolta)) }
    } else {
        val visibili = if (tutteLeSvolte) svolte else svolte.take(SESSIONI_SVOLTE_VISIBILI)
        item(key = "sessioni-svolte") {
            Column(modifier = Modifier.fillMaxWidth()) {
                SopraTitolo(stringResource(R.string.sessioni_ultimi_giorni))
                ListaRighe(visibili) { RigaSessioneSvolta(it, telefono(it.svolta.dispositivoId)) }
            }
        }
        val nascoste = svolte.size - SESSIONI_SVOLTE_VISIBILI
        if (nascoste > 0) {
            item(key = "sessioni-altre") {
                TextButton(onClick = onTutteLeSvolte) {
                    Text(
                        if (tutteLeSvolte) {
                            stringResource(R.string.sessioni_meno)
                        } else {
                            pluralStringResource(R.plurals.sessioni_altre, nascoste, nascoste)
                        },
                    )
                }
            }
        }
    }
    if (approvate.isNotEmpty()) {
        item(key = "sessioni-approvate") {
            TextButton(onClick = onApprovate) {
                Text(stringResource(R.string.sessioni_approvate, approvate.size))
                Icon(
                    imageVector = if (approvateAperte) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                    contentDescription = null,
                    modifier = Modifier.padding(start = Spazi.xs),
                )
            }
        }
        if (approvateAperte) {
            item(key = "sessioni-approvate-elenco") {
                ListaRighe(approvate) { sessione ->
                    RigaSessioneApprovata(
                        sessione = sessione,
                        nomiFinestra = nomiFinestra,
                        telefono = telefono(sessione.dispositivoId ?: sessione.dispositivo?.id),
                    )
                }
            }
        }
    }
    if (nonPiuValide.isNotEmpty()) {
        item(key = "sessioni-non-piu-valide") {
            Column(modifier = Modifier.fillMaxWidth().padding(top = Spazi.s)) {
                SopraTitolo(stringResource(R.string.sessioni_non_piu_valide))
                ListaRighe(nonPiuValide) { sessione ->
                    RigaSessioneNonPiuValida(sessione, telefono(sessione.dispositivoId ?: sessione.dispositivo?.id))
                }
            }
        }
    }
}

/** Una sessione fatta, in una riga; una in corso nel blu dell'app, così si nota. */
@Composable
private fun RigaSessioneSvolta(sessione: SessioneRaccontata, telefono: String?) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = Spazi.m)) {
        if (telefono != null) {
            SopraTitolo(telefono.uppercase(), modifier = Modifier.padding(bottom = Spazi.xs))
        }
        Text(
            text = testoSessioneSvolta(parole(), sessione),
            style = MaterialTheme.typography.bodyLarge,
            color = if (sessione.fine == FineSessione.IN_CORSO) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurface
            },
        )
    }
}

/** Una sessione approvata: il nome, e le sue app in una riga. */
@Composable
private fun RigaSessioneApprovata(sessione: Sessione, nomiFinestra: Map<String, String>, telefono: String?) {
    val p = parole()
    val app = elencoAppSessione(p, appDellaSessione(sessione.app, sessione.nomi, nomiFinestra))
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = Spazi.m)) {
        if (telefono != null) {
            SopraTitolo(telefono.uppercase(), modifier = Modifier.padding(bottom = Spazi.xs))
        }
        Text(text = nomeSessioneConEmoji(p, sessione.nome), style = MaterialTheme.typography.bodyLarge)
        if (app != null) {
            Text(
                text = app,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = Spazi.xs),
            )
        }
    }
}

/** Una sessione di un telefono scollegato: non si avvia più, e non c'è niente da decidere. */
@Composable
private fun RigaSessioneNonPiuValida(sessione: Sessione, telefono: String?) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = Spazi.m)) {
        if (telefono != null) {
            SopraTitolo(telefono.uppercase(), modifier = Modifier.padding(bottom = Spazi.xs))
        }
        Text(
            text = stringResource(R.string.sessione_non_piu_valida, nomeSessioneConEmoji(parole(), sessione.nome)),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
