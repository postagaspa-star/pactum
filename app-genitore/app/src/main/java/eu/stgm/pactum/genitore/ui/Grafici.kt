package eu.stgm.pactum.genitore.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import eu.stgm.pactum.design.Spazi
import eu.stgm.pactum.genitore.R
import eu.stgm.pactum.genitore.dati.MediaPeriodo
import eu.stgm.pactum.genitore.dati.Medie
import eu.stgm.pactum.genitore.dati.UsoCategoria
import eu.stgm.pactum.genitore.dati.UsoGiorno
import eu.stgm.pactum.genitore.ui.theme.coloreCategoria

/**
 * I grafici della scheda Tempo, disegnati a mano con Canvas: nessuna libreria,
 * nessun colore fuori dalla palette dell'app.
 *
 * Tre forme sole:
 *  1. [AnelloCategorie]  — come è diviso il tempo di un giorno;
 *  2. [BarreGiorni]      — gli ultimi otto giorni, uno accanto all'altro;
 *  3. [BarraOrizzontale] — quanto vale un numero rispetto al più grande del
 *     giorno (le visite dei siti). Le barre delle app e delle categorie sono
 *     `BarraUso` di core-design, identiche nelle due app.
 *
 * Due leggi che valgono per tutti:
 *  - un giorno SENZA fotografia non è uno zero: è un tratteggio vuoto, perché
 *    "non lo so" e "zero minuti" sono due notizie diverse;
 *  - il terracotta del patto qui non compare MAI (tavola rotonda D2, legge 1):
 *    vive solo nella striscia degli 8 giorni. Andare oltre un limite si dice a
 *    parole, col chip "N min oltre", non col colore.
 */

/** Una fetta dell'anello: la categoria già risolta in etichetta e colore. */
data class FettaCategoria(
    val chiave: String,
    val etichetta: String,
    val minuti: Int,
    val limite: Int?,
    val colore: Color,
)

/** Le categorie di un giorno pronte da disegnare: solo quelle con minuti, dalla più grande. */
fun fetteCategorie(categorie: List<UsoCategoria>): List<FettaCategoria> =
    categorie
        .filter { it.minuti > 0 }
        .sortedByDescending { it.minuti }
        .map { categoria ->
            FettaCategoria(
                chiave = categoria.chiave,
                etichetta = etichettaCategoria(categoria.chiave),
                minuti = categoria.minuti,
                limite = categoria.limite,
                colore = coloreCategoria(categoria.chiave),
            )
        }

/**
 * Le fette del giorno con, in coda, la parte NON categorizzata: `totaleMinuti`
 * meno la somma delle categorie note. Serve a far tornare i conti tra il totale
 * del giorno (il numero grande sopra l'anello) e le fette + la legenda: senza
 * questa fetta "resto" l'anello riempirebbe comunque i 360° mentre la somma
 * delle categorie sarebbe MINORE del totale (le categorie non coprono ogni app),
 * e un genitore che somma la legenda non ritroverebbe il numero grande.
 *
 * La fetta si aggiunge SOLO quando ci sono già categorie e il totale le supera:
 * senza nessuna categoria si lascia all'anello il caso "totale senza
 * ripartizione" (che lo disegna pieno del blu dell'app, onesto). Colore neutro
 * (l'`outline` del tema), mai una tinta di categoria: non è una categoria.
 */
@Composable
fun fetteConResto(categorie: List<UsoCategoria>, totaleMinuti: Int?): List<FettaCategoria> {
    val fette = fetteCategorie(categorie)
    if (fette.isEmpty() || totaleMinuti == null) return fette
    val resto = totaleMinuti - fette.sumOf { it.minuti }
    if (resto <= 0) return fette
    return fette + FettaCategoria(
        chiave = "categoria:__resto__",
        etichetta = stringResource(R.string.grafico_non_categorizzato),
        minuti = resto,
        limite = null,
        colore = MaterialTheme.colorScheme.outline,
    )
}

// --- 1. L'anello --------------------------------------------------------------

/**
 * L'anello delle categorie. Il totale del giorno non sta più al centro: è
 * l'eroe della scheda Tempo e sta sopra, in grande (un numero grande per
 * schermata, tavola rotonda D1) — dentro l'anello sarebbe stato un doppione.
 *
 * Il cappuccio tondo (`StrokeCap.Round`) sporge di mezzo spessore oltre l'arco:
 * se non lo si scontasse in gradi, gli stacchi tra le fette sparirebbero e ogni
 * fetta piccola sembrerebbe grande uguale. Perciò ogni arco si disegna più
 * corto di `stacco + 2 cappucci`, e il risultato a schermo torna proporzionale.
 *
 * Nessuna fetta (o totale a zero) = anello grigio del binario: un contenitore
 * vuoto, non un dato.
 */
@Composable
fun AnelloCategorie(
    fette: List<FettaCategoria>,
    totaleMinuti: Int?,
    modifier: Modifier = Modifier,
    diametro: Dp = 184.dp,
    spessore: Dp = 18.dp,
) {
    val binario = MaterialTheme.colorScheme.surfaceVariant
    val unica = MaterialTheme.colorScheme.primary

    Box(modifier = modifier.size(diametro)) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val penna = spessore.toPx()
            val lato = size.minDimension - penna
            if (lato <= 0f) return@Canvas
            val angolo = Offset(penna / 2f, penna / 2f)
            val misura = Size(lato, lato)
            val raggio = lato / 2f
            val tondo = Stroke(width = penna, cap = StrokeCap.Round)
            val netto = Stroke(width = penna, cap = StrokeCap.Butt)

            val totale = fette.sumOf { it.minuti }
            when {
                fette.isEmpty() && totaleMinuti != null && totaleMinuti > 0 ->
                    // Tempo c'è, ma il figlio non ha mandato la ripartizione:
                    // un anello intero del blu dell'app, onesto e pieno.
                    drawArc(
                        color = unica,
                        startAngle = -90f,
                        sweepAngle = 360f,
                        useCenter = false,
                        topLeft = angolo,
                        size = misura,
                        style = netto,
                    )

                fette.isEmpty() || totale <= 0 -> drawArc(
                    color = binario,
                    startAngle = -90f,
                    sweepAngle = 360f,
                    useCenter = false,
                    topLeft = angolo,
                    size = misura,
                    style = netto,
                )

                fette.size == 1 -> drawArc(
                    color = fette.first().colore,
                    startAngle = -90f,
                    sweepAngle = 360f,
                    useCenter = false,
                    topLeft = angolo,
                    size = misura,
                    style = netto,
                )

                else -> {
                    val gradiCappuccio =
                        Math.toDegrees((penna / 2f / raggio).toDouble()).toFloat()
                    val stacco = 3f
                    var inizio = -90f
                    fette.forEach { fetta ->
                        val giro = 360f * fetta.minuti / totale
                        val disegnato =
                            (giro - stacco - gradiCappuccio * 2f).coerceAtLeast(0.5f)
                        drawArc(
                            color = fetta.colore,
                            startAngle = inizio + stacco / 2f + gradiCappuccio,
                            sweepAngle = disegnato,
                            useCenter = false,
                            topLeft = angolo,
                            size = misura,
                            style = tondo,
                        )
                        inizio += giro
                    }
                }
            }
        }
    }
}

/** La legenda dell'anello: pallino del colore, nome, limite se c'è, tempo a destra. */
@Composable
fun LegendaCategorie(fette: List<FettaCategoria>, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(Spazi.s),
    ) {
        fette.forEach { fetta ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(modifier = Modifier.size(11.dp).background(fetta.colore, CircleShape))
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(start = Spazi.m),
                ) {
                    Text(
                        text = fetta.etichetta,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    val limite = fetta.limite
                    if (limite != null) {
                        Text(
                            text = stringResource(
                                R.string.tempo_limite,
                                testoDurata(limite.toLong()),
                            ),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Text(
                    text = testoDurata(fetta.minuti.toLong()),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}

// --- 2. Le barre degli otto giorni -------------------------------------------

/**
 * Gli otto giorni della finestra, dal più vecchio a oggi: una barra per giorno,
 * alta in proporzione al massimo della finestra.
 *
 * La barra ACCESA è quella scelta (o oggi, se nessuna scelta): `primary` pieno.
 * Le altre sono lo stesso blu al 35%: sono contesto, non protagoniste.
 * Un giorno senza fotografia non ha barra: ha un tratteggio basso e vuoto.
 *
 * La cella si stringe da sola sugli schermi piccoli (stessa regola del semaforo):
 * meglio più magra che tagliata dal bordo.
 */
@Composable
fun BarreGiorni(
    giorni: List<UsoGiorno>,
    modifier: Modifier = Modifier,
    selezionato: String? = null,
    onScelta: ((String) -> Unit)? = null,
    altezza: Dp = 104.dp,
) {
    if (giorni.isEmpty()) return
    val massimo = giorni.mapNotNull { it.totaleMinuti }.maxOrNull() ?: 0
    val acceso = MaterialTheme.colorScheme.primary
    val spento = MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)
    val assente = MaterialTheme.colorScheme.outline
    val spazio = Spazi.s
    val cima = RoundedCornerShape(topStart = 7.dp, topEnd = 7.dp)

    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        val larghezza = ((maxWidth - spazio * (giorni.size - 1)) / giorni.size)
            .coerceIn(12.dp, 26.dp)

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(spazio, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.Bottom,
        ) {
            giorni.forEachIndexed { indice, giorno ->
                val inLuce = if (selezionato == null) {
                    indice == giorni.lastIndex
                } else {
                    giorno.giorno == selezionato
                }
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = if (onScelta == null) {
                        Modifier
                    } else {
                        Modifier.clickable { onScelta.invoke(giorno.giorno) }
                    },
                ) {
                    Box(
                        modifier = Modifier.width(larghezza).height(altezza),
                        contentAlignment = Alignment.BottomCenter,
                    ) {
                        val minuti = giorno.totaleMinuti
                        if (minuti == null) {
                            Canvas(
                                modifier = Modifier.fillMaxWidth().height(10.dp),
                            ) {
                                val bordo = 1.dp.toPx()
                                drawRoundRect(
                                    color = assente,
                                    topLeft = Offset(bordo / 2f, bordo / 2f),
                                    size = Size(
                                        size.width - bordo,
                                        size.height - bordo,
                                    ),
                                    cornerRadius = CornerRadius(
                                        4.dp.toPx(),
                                        4.dp.toPx(),
                                    ),
                                    style = Stroke(
                                        width = bordo,
                                        pathEffect = PathEffect.dashPathEffect(
                                            floatArrayOf(6f, 5f),
                                            0f,
                                        ),
                                    ),
                                )
                            }
                        } else {
                            val frazione =
                                if (massimo > 0) minuti.toFloat() / massimo else 0f
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height((altezza * frazione).coerceAtLeast(3.dp))
                                    .background(
                                        if (inLuce) acceso else spento,
                                        cima,
                                    ),
                            )
                        }
                    }
                    Text(
                        // Il giorno del mese ("2026-07-14" → "14").
                        text = giorno.giorno.takeLast(2),
                        style = MaterialTheme.typography.labelSmall,
                        color = if (inLuce) {
                            MaterialTheme.colorScheme.onSurface
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        modifier = Modifier.padding(top = Spazi.xs),
                    )
                }
            }
        }
    }
}

// --- 3. Le barre orizzontali --------------------------------------------------

/**
 * Quanto vale un numero rispetto al più grande del giorno, su un binario grigio.
 * La usano le visite dei siti: il `riferimento` è il sito più richiesto, un
 * confronto tra pari dentro la giornata, mai una soglia da rispettare — un sito
 * visitato non è un'infrazione (contratto-api.md, "Siti visitati — limiti e
 * patto etico"). Per questo non ha né limite né tacca, e mai il terracotta.
 */
@Composable
fun BarraOrizzontale(
    quantita: Int,
    riferimento: Int,
    colore: Color,
    modifier: Modifier = Modifier,
    spessore: Dp = 9.dp,
) {
    val binario = MaterialTheme.colorScheme.surfaceVariant

    Canvas(modifier = modifier.fillMaxWidth().height(spessore + 8.dp)) {
        val alta = spessore.toPx()
        val larga = size.width
        if (larga <= 0f || alta <= 0f) return@Canvas
        val alto = (size.height - alta) / 2f
        val tondo = CornerRadius(alta / 2f, alta / 2f)

        drawRoundRect(
            color = binario,
            topLeft = Offset(0f, alto),
            size = Size(larga, alta),
            cornerRadius = tondo,
        )

        val scala = (if (riferimento > 0) riferimento else quantita).coerceAtLeast(1)
        if (quantita > 0) {
            val pieno = (larga * quantita / scala.toFloat()).coerceIn(alta, larga)
            drawRoundRect(
                color = colore,
                topLeft = Offset(0f, alto),
                size = Size(pieno, alta),
                cornerRadius = tondo,
            )
        }
    }
}

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
        BarraOrizzontale(
            quantita = minuti ?: visite,
            riferimento = riferimento,
            colore = MaterialTheme.colorScheme.primary,
        )
    }
}

// --- Le medie -------------------------------------------------------------------

/**
 * Le medie settimanale e mensile del tempo d'uso, una accanto all'altra. Ogni
 * cella compare solo se il server ha dati per quel periodo: mai uno zero finto.
 */
@Composable
fun BloccoMedie(medie: Medie, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(Spazi.l),
    ) {
        medie.settimana?.let {
            MediaCella(R.string.media_settimana, it, Modifier.weight(1f))
        }
        medie.mese?.let {
            MediaCella(R.string.media_mese, it, Modifier.weight(1f))
        }
    }
}

/** Una cella "media": etichetta piccola, minuti, e su quanti giorni poggia. */
@Composable
private fun MediaCella(etichetta: Int, media: MediaPeriodo, modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        Text(
            text = stringResource(etichetta),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(Spazi.xs))
        Text(
            text = testoDurata(media.minuti.toLong()),
            style = MaterialTheme.typography.titleLarge,
            maxLines = 1,
        )
        Text(
            text = stringResource(R.string.media_su_giorni, media.giorni),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
