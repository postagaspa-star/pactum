package eu.stgm.pactum.genitore.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import eu.stgm.pactum.design.BarraUso
import eu.stgm.pactum.design.FettaCategoria
import eu.stgm.pactum.design.GiornoGrafico
import eu.stgm.pactum.design.Spazi
import eu.stgm.pactum.design.VoceCategoria
import eu.stgm.pactum.design.fetteConResto
import eu.stgm.pactum.genitore.R
import eu.stgm.pactum.genitore.dati.UsoCategoria
import eu.stgm.pactum.genitore.dati.UsoGiorno

/**
 * (0.16) I grafici del Tempo (anello, legenda, barre degli 8 giorni, medie e
 * totali) e i colori delle categorie stanno in core-design, uguali nell'app del
 * figlio. Qui restano i ponti dai dati del contratto ai dati semplici dei
 * grafici, e la riga dei siti (solo del genitore).
 */

/** Le categorie di un giorno come le vuole l'anello. */
fun vociCategorie(categorie: List<UsoCategoria>): List<VoceCategoria> =
    categorie.map { VoceCategoria(chiave = it.chiave, minuti = it.minuti, limite = it.limite) }

/** Gli 8 giorni come li vogliono le barre: il giorno e il suo totale (null = senza dati). */
fun giorniGrafico(giorni: List<UsoGiorno>): List<GiornoGrafico> =
    giorni.map { GiornoGrafico(giorno = it.giorno, minuti = it.totaleMinuti) }

/** Le fette del giorno, col resto "Non in categoria" (v. core-design). */
@Composable
fun fetteDelGiorno(giorno: UsoGiorno): List<FettaCategoria> =
    fetteConResto(
        voci = vociCategorie(giorno.categorie),
        totaleMinuti = giorno.totaleMinuti,
        etichetta = ::etichettaCategoria,
        etichettaResto = stringResource(R.string.grafico_non_categorizzato),
    )

// --- 3. I siti -----------------------------------------------------------------

/**
 * Una riga "sito visitato": il dominio a sinistra, quante volte è stato chiesto
 * a destra, la barra proporzionale sotto — la stessa lettura delle app, così il
 * genitore legge le due liste con lo stesso occhio.
 *
 * (v3) Sul computer ([minuti] non null) a destra c'è prima il tempo e poi le
 * visite ("42 min · 7 visite"), e la barra è sui minuti: [riferimento] è allora
 * il sito con più minuti del giorno.
 *
 * Il nome è un DOMINIO e basta (`instagram.com`), mai una pagina: quello che sta
 * dopo il nome del sito non lo vede nemmeno il telefono del figlio.
 */
@Composable
fun RigaBarraSito(
    dominio: String,
    visite: Int,
    riferimento: Int,
    modifier: Modifier = Modifier,
    minuti: Int? = null,
) {
    val testoVisite = pluralStringResource(R.plurals.siti_visite, visite, visite)
    Column(modifier = modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = dominio,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = if (minuti != null) {
                    stringResource(R.string.siti_minuti_e_visite, testoDurata(minuti.toLong()), testoVisite)
                } else {
                    testoVisite
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = Spazi.s),
            )
        }
        // (0.15) La stessa barra delle app (una misura sola: B6/B8).
        Spacer(modifier = Modifier.height(Spazi.xs))
        BarraUso(minuti = minuti ?: visite, limite = null, massimoDelGiorno = riferimento.coerceAtLeast(minuti ?: visite))
    }
}
