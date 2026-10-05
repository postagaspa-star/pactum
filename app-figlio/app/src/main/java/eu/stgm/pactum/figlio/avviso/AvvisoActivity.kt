package eu.stgm.pactum.figlio.avviso

import eu.stgm.pactum.design.CardNormale
import eu.stgm.pactum.design.FilaPulsanti
import eu.stgm.pactum.design.Pillola
import eu.stgm.pactum.design.Tono
import eu.stgm.pactum.design.attivaBordoPieno
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import eu.stgm.pactum.design.BarraUso
import eu.stgm.pactum.design.Spazi
import eu.stgm.pactum.figlio.MainActivity
import eu.stgm.pactum.figlio.R
import eu.stgm.pactum.figlio.diagnostica.TempiLog
import eu.stgm.pactum.figlio.permessi.PermessiHelper
import eu.stgm.pactum.figlio.ui.testoDurata
import eu.stgm.pactum.figlio.ui.theme.PactumTheme

/**
 * (0.9) L'avviso a tutto schermo quando si va oltre una regola: si apre sopra
 * l'app in uso e dice quale regola, quanto hai usato e il limite che ti sei
 * dato. NON è un blocco: "Ho capito" (o il tasto indietro) chiude e torna
 * dov'eri, "Apri Pactum" porta alla schermata Oggi.
 *
 * Vive in un task suo, fuori dalle recenti (manifest): chiudendolo si torna
 * all'app di prima, non a una schermata di Pactum rimasta aperta sotto. Parte
 * dalla sentinella, cioè da dietro le quinte: Android (10+) lo permette solo
 * con "Mostra sopra le altre app". Senza (o durante una chiamata) resta la
 * notifica, sul canale che si vede in alto. Un secondo sforamento mentre
 * l'avviso è aperto si aggiunge al primo.
 */
class AvvisoActivity : ComponentActivity() {

    private val avvisi = mutableStateOf<List<Avviso>>(emptyList())

    override fun onCreate(savedInstanceState: Bundle?) {
        // (0.15) Bordo pieno con le icone scure della barra di stato (B11): lo
        // Scaffold qui sotto tiene il contenuto fuori dalle barre di sistema.
        attivaBordoPieno()
        super.onCreate(savedInstanceState)
        avvisi.value = Avviso.daJson(
            savedInstanceState?.getString(EXTRA_AVVISI) ?: intent?.getStringExtra(EXTRA_AVVISI),
        )
        // (0.16) Quanto ci ha messo Android ad aprirla, da quando il servizio l'ha chiesta.
        if (savedInstanceState == null) segnaTempo("avviso-creato", intent)
        if (avvisi.value.isEmpty()) {
            finish()
            return
        }
        setContent {
            PactumTheme {
                AvvisoScreen(
                    avvisi = avvisi.value,
                    onHoCapito = { finish() },
                    onApriPactum = { apriPactum() },
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // (0.16) La prima volta che si vede davvero (una volta per richiesta).
        segnaTempo("avviso-visibile", intent)
        intent?.removeExtra(EXTRA_CHIESTO_ALLE)
    }

    /** (0.16) Una riga di tempo dall'istante della richiesta (TempiLog: solo ms e id di regola). */
    private fun segnaTempo(evento: String, intent: Intent?) {
        val chiesto = intent?.getLongExtra(EXTRA_CHIESTO_ALLE, 0L) ?: 0L
        if (chiesto <= 0L) return
        TempiLog.riga(evento, TempiLog.da(chiesto), "regole=" + avvisi.value.joinToString(",") { it.regolaId.toString() })
    }

    /** Uno sforamento nuovo mentre l'avviso è ancora aperto: si aggiunge, il primo resta. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        avvisi.value = Avviso.unisci(avvisi.value, Avviso.daJson(intent.getStringExtra(EXTRA_AVVISI)))
    }

    /**
     * Uscito con Home o con le app recenti: l'avviso l'ha visto, e non deve
     * ricomparire unito al prossimo. Lo spegnimento dello schermo non passa di
     * qui: l'avviso resta e si ritrova allo sblocco.
     */
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        finish()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(EXTRA_AVVISI, Avviso.inJson(avvisi.value))
    }

    private fun apriPactum() {
        // Come il tocco sulla notifica dello sforamento: Pactum su Oggi.
        startActivity(
            Intent(this, MainActivity::class.java)
                .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                .putExtra(MainActivity.EXTRA_DESTINAZIONE, MainActivity.DEST_OGGI),
        )
        finish()
    }

    companion object {
        private const val EXTRA_AVVISI = "avvisi"

        /** (0.16) Quando il servizio l'ha chiesta (TempiLog.ora), per misurare l'apertura. */
        private const val EXTRA_CHIESTO_ALLE = "chiesto_alle"

        /**
         * Apre l'avviso sopra qualsiasi app, se "Mostra sopra le altre app" è
         * concesso. False se non è partito: la notifica c'è già comunque.
         */
        fun apri(context: Context, avvisi: List<Avviso>): Boolean {
            if (avvisi.isEmpty() || !PermessiHelper.puoMostrareSopra(context)) return false
            // NO_USER_ACTION: non è il ragazzo ad andarsene dall'app che sta
            // usando (niente "onUserLeaveHint" per lei: un video non va in
            // riquadro, un'app non crede di essere stata lasciata).
            val intent = Intent(context, AvvisoActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_USER_ACTION)
                .putExtra(EXTRA_AVVISI, Avviso.inJson(avvisi))
                .putExtra(EXTRA_CHIESTO_ALLE, TempiLog.ora())
            val inizio = TempiLog.ora()
            return try {
                context.startActivity(intent)
                TempiLog.riga("avviso-chiesto", TempiLog.da(inizio), "regole=" + avvisi.joinToString(",") { it.regolaId.toString() })
                true
            } catch (e: RuntimeException) {
                TempiLog.riga("avviso-rifiutato", TempiLog.da(inizio))
                false // Android l'ha rifiutato: resta la notifica
            }
        }
    }
}

/**
 * Stesse parole delle notifiche ("Oggi sei andato oltre", "Nessun blocco: è il
 * tuo patto.") e stessa riga della schermata Oggi: nome, "31 min su 30 min",
 * la barra piena, "1 min oltre". I pulsanti in basso, sotto il pollice.
 */
@Composable
private fun AvvisoScreen(avvisi: List<Avviso>, onHoCapito: () -> Unit, onApriPactum: () -> Unit) {
    Scaffold { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize()) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = Spazi.xl, vertical = Spazi.xxl),
                verticalArrangement = Arrangement.spacedBy(Spazi.l),
            ) {
                Text(
                    text = when {
                        avvisi.all { it.fascia } -> stringResource(R.string.notifica_sforamento_fascia_titolo)
                        // (0.16) Il tempo è finito, non ancora oltre: "Il tempo per Instagram è finito".
                        avvisi.all { it.finito } -> avvisi.singleOrNull()?.nome
                            ?.let { stringResource(R.string.tempo_finito_titolo, it) }
                            ?: stringResource(R.string.tempo_finito_titolo_generico)
                        else -> stringResource(R.string.notifica_sforamento_limite_titolo)
                    },
                    style = MaterialTheme.typography.headlineSmall,
                )
                avvisi.forEach { SchedaAvviso(it) }
                Text(
                    text = stringResource(R.string.avviso_nessun_blocco),
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
            FilaPulsanti(modifier = Modifier.fillMaxWidth().padding(horizontal = Spazi.xl, vertical = Spazi.l)) {
                Button(onClick = onHoCapito) {
                    Text(stringResource(R.string.avviso_ho_capito), maxLines = 1)
                }
                OutlinedButton(onClick = onApriPactum) {
                    Text(stringResource(R.string.avviso_apri_pactum), maxLines = 1)
                }
            }
        }
    }
}

@Composable
private fun SchedaAvviso(avviso: Avviso) {
    CardNormale {
        Column(verticalArrangement = Arrangement.spacedBy(Spazi.s)) {
            if (avviso.fascia) {
                Text(
                    text = stringResource(R.string.avviso_fascia_regola, avviso.dalle ?: stringResource(R.string.dato_mancante), avviso.alle ?: stringResource(R.string.dato_mancante)),
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = stringResource(R.string.avviso_fascia_uso, testoDurata(avviso.minutiOltre.toLong())),
                    style = MaterialTheme.typography.bodyMedium,
                )
            } else {
                val usati = avviso.minutiUsati ?: 0
                val limite = avviso.limiteEfficace ?: 0
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = avviso.nome ?: stringResource(R.string.dato_mancante),
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = stringResource(
                            R.string.oggi_minuti_su_limite,
                            testoDurata(usati.toLong()),
                            testoDurata(limite.toLong()),
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                // Come in Oggi: oltre il limite la barra resta piena, l'eccedenza si dice a parole.
                BarraUso(minuti = usati, limite = limite, massimoDelGiorno = limite)
                if (avviso.finito) {
                    // (0.16) A 30 su 30 i 30 minuti sono permessi: niente "oltre", si dice cosa succede dopo.
                    Text(
                        text = stringResource(
                            // Lo sforamento di oggi è già a registro: niente promessa di registro.
                            if (avviso.giaARegistro) R.string.tempo_finito_testo_gia_a_registro else R.string.tempo_finito_testo,
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                } else {
                    Pillola(stringResource(R.string.oggi_oltre, testoDurata(avviso.minutiOltre.toLong())), tono = Tono.Attenzione)
                }
                avviso.limite?.let { base ->
                    Text(
                        text = if (avviso.bonus > 0) {
                            stringResource(
                                R.string.avviso_limite_dato_bonus,
                                testoDurata(base.toLong()),
                                testoDurata(avviso.bonus.toLong()),
                            )
                        } else {
                            stringResource(R.string.avviso_limite_dato, testoDurata(base.toLong()))
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
