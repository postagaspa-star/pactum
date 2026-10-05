package eu.stgm.pactum.design

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * I grafici del tempo, uguali nelle due app (0.16: spostati qui dall'app del
 * genitore, perché il figlio ha lo stesso grafico). Disegnati a mano con Canvas:
 * nessuna libreria, nessun colore fuori dal tema o da [ColoriCategorie].
 * Niente risorse: le parole arrivano dall'app (etichette e funzioni che
 * scrivono le durate).
 *
 * Due forme sole:
 *  1. [AnelloCategorie] (con [LegendaCategorie]) — come è diviso il tempo di un giorno;
 *  2. [BarreGiorni] — gli ultimi otto giorni, uno accanto all'altro.
 * Più [BloccoMedie]: i numeri della settimana e del mese sotto le barre.
 *
 * Due leggi che valgono per tutti:
 *  - un giorno SENZA fotografia non è uno zero: è un tratteggio vuoto, perché
 *    "non lo so" e "zero minuti" sono due notizie diverse;
 *  - il terracotta del patto qui non compare MAI (tavola rotonda D2, legge 1):
 *    vive solo nella striscia degli 8 giorni. Andare oltre un limite si dice a
 *    parole, non col colore.
 */

// --- I dati, semplici: quelli che ha anche un'altra app ------------------------------

/** Una categoria d'uso di un giorno: la chiave del contratto ("categoria:social"), i minuti, il limite se c'è. */
data class VoceCategoria(
    val chiave: String,
    val minuti: Int,
    val limite: Int? = null,
)

/** Una fetta dell'anello: la categoria già risolta in etichetta e colore. */
data class FettaCategoria(
    val chiave: String,
    val etichetta: String,
    val minuti: Int,
    val limite: Int?,
    val colore: Color,
)

/**
 * Un giorno del grafico a barre: il giorno ISO del contratto ("2026-10-04"), i
 * minuti (null = nessuna fotografia: non è uno zero) e la scritta sotto la barra
 * (di solito il giorno del mese).
 */
data class GiornoGrafico(
    val giorno: String,
    val minuti: Int?,
    val etichetta: String = giorno.takeLast(2),
)

/**
 * Una cella di [BloccoMedie]: l'etichetta piccola sopra ("MEDIA SETTIMANA",
 * "ULTIMI 7 GIORNI"), il valore, e sotto le righe piccole che lo spiegano
 * ("su 7 giorni", "6 giorni su 7 con dati"). [grande] = il valore è un totale da
 * leggere per primo: carattere più grande.
 */
data class CellaMedia(
    val etichetta: String,
    val valore: String,
    val sotto: List<String> = emptyList(),
    val grande: Boolean = false,
)

/** Chiave della fetta "non in categoria" di [fetteConResto]. */
const val CHIAVE_RESTO = "categoria:__resto__"

/**
 * Le categorie di un giorno pronte da disegnare: solo quelle con minuti, dalla
 * più grande. [etichetta] dà il nome da mostrare di una chiave (lo scrive l'app).
 */
fun fetteCategorie(voci: List<VoceCategoria>, etichetta: (String) -> String): List<FettaCategoria> =
    voci
        .filter { it.minuti > 0 }
        .sortedByDescending { it.minuti }
        .map { voce ->
            FettaCategoria(
                chiave = voce.chiave,
                etichetta = etichetta(voce.chiave),
                minuti = voce.minuti,
                limite = voce.limite,
                colore = coloreCategoria(voce.chiave),
            )
        }

/**
 * Le fette del giorno con, in coda, la parte NON categorizzata: [totaleMinuti]
 * meno la somma delle categorie note. Serve a far tornare i conti tra il totale
 * del giorno (il numero grande sopra l'anello) e le fette + la legenda: senza
 * questa fetta "resto" l'anello riempirebbe comunque i 360° mentre la somma
 * delle categorie sarebbe MINORE del totale (le categorie non coprono ogni app),
 * e chi somma la legenda non ritroverebbe il numero grande.
 *
 * La fetta si aggiunge SOLO quando ci sono già categorie e il totale le supera:
 * senza nessuna categoria si lascia all'anello il caso "totale senza
 * ripartizione" (che lo disegna pieno del colore dell'app, onesto). Colore
 * neutro ([coloreResto], di solito l'`outline` del tema), mai una tinta di
 * categoria: non è una categoria.
 */
fun fetteConResto(
    voci: List<VoceCategoria>,
    totaleMinuti: Int?,
    etichetta: (String) -> String,
    etichettaResto: String,
    coloreResto: Color,
): List<FettaCategoria> {
    val fette = fetteCategorie(voci, etichetta)
    if (fette.isEmpty() || totaleMinuti == null) return fette
    val resto = totaleMinuti - fette.sumOf { it.minuti }
    if (resto <= 0) return fette
    return fette + FettaCategoria(
        chiave = CHIAVE_RESTO,
        etichetta = etichettaResto,
        minuti = resto,
        limite = null,
        colore = coloreResto,
    )
}

/** Come sopra, col resto nell'`outline` del tema. */
@Composable
fun fetteConResto(
    voci: List<VoceCategoria>,
    totaleMinuti: Int?,
    etichetta: (String) -> String,
    etichettaResto: String,
): List<FettaCategoria> =
    fetteConResto(voci, totaleMinuti, etichetta, etichettaResto, MaterialTheme.colorScheme.outline)

// --- 1. L'anello ------------------------------------------------------------------

/**
 * L'anello delle categorie. Il totale del giorno non sta al centro: è l'eroe
 * della pagina e sta sopra, in grande (un numero grande per schermata) — dentro
 * l'anello sarebbe un doppione.
 *
 * Il cappuccio tondo (`StrokeCap.Round`) sporge di mezzo spessore oltre l'arco:
 * se non lo si scontasse in gradi, gli stacchi tra le fette sparirebbero e ogni
 * fetta piccola sembrerebbe grande uguale. Perciò ogni arco si disegna più
 * corto di `stacco + 2 cappucci`, e il risultato a schermo torna proporzionale.
 *
 * Nessuna fetta (o totale a zero) = anello grigio del binario: un contenitore
 * vuoto, non un dato. Un totale senza ripartizione = anello pieno del `primary`.
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
                    // Tempo c'è, ma non la ripartizione: un anello intero del
                    // colore dell'app, onesto e pieno.
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

/**
 * La legenda dell'anello: pallino del colore, nome, limite se c'è, tempo a
 * destra. [durata] scrive i minuti ("1 h 12 min"), [limite] la riga del limite
 * ("limite 1 h 30 min"): le parole sono dell'app.
 */
@Composable
fun LegendaCategorie(
    fette: List<FettaCategoria>,
    durata: (Int) -> String,
    limite: (Int) -> String,
    modifier: Modifier = Modifier,
) {
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
                    val limiteFetta = fetta.limite
                    if (limiteFetta != null) {
                        Text(
                            text = limite(limiteFetta),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Text(
                    text = durata(fetta.minuti),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}

// --- 2. Le barre degli otto giorni ---------------------------------------------------

/**
 * Gli otto giorni, dal più vecchio a oggi: una barra per giorno, alta in
 * proporzione al massimo dei giorni mostrati.
 *
 * È un grafico da guardare: il giorno si sceglie altrove (nella fila dei
 * giorni), in un posto solo. La barra ACCESA è quella [selezionato] (o l'ultima,
 * oggi, se nessuna scelta): `primary` pieno. Le altre sono grigie (`outline`),
 * con un contrasto vero sul fondo della card. Un giorno senza dati non ha barra:
 * ha un tratteggio basso e vuoto.
 *
 * (0.16) Con [valore] ogni barra porta sopra il suo tempo, scritto corto
 * ("2h31"): il totale di ogni giorno si legge guardando, senza toccare. La
 * scritta si rimpicciolisce da sola se non ci sta (testo grande). Con
 * [descrizione] ogni giorno ha la sua frase per TalkBack ("03/10: 3 h 5 min").
 *
 * La cella si stringe da sola sugli schermi piccoli: meglio più magra che
 * tagliata dal bordo.
 */
@Composable
fun BarreGiorni(
    giorni: List<GiornoGrafico>,
    modifier: Modifier = Modifier,
    selezionato: String? = null,
    altezza: Dp = 104.dp,
    valore: ((Int) -> String)? = null,
    descrizione: ((GiornoGrafico) -> String)? = null,
) {
    if (giorni.isEmpty()) return
    val massimo = giorni.mapNotNull { it.minuti }.maxOrNull() ?: 0
    val acceso = MaterialTheme.colorScheme.primary
    val spento = MaterialTheme.colorScheme.outline
    val assente = MaterialTheme.colorScheme.outline
    val spazio = Spazi.s
    val cima = RoundedCornerShape(topStart = 7.dp, topEnd = 7.dp)
    // Con le scritte sopra le barre, la barra più alta lascia loro una riga.
    val stileValore = MaterialTheme.typography.labelSmall
    val riserva = if (valore != null) {
        with(LocalDensity.current) { stileValore.lineHeight.toDp() } + 2.dp
    } else {
        0.dp
    }

    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        // Con le scritte ogni colonna prende anche lo spazio tra le barre (la
        // scritta è più larga della barra): n colonne di barra + spazio, che
        // stanno sempre nella larghezza (anche sotto i ~272 dp l'ultima, oggi,
        // non si stringe).
        val larghezza = if (valore != null) {
            (maxWidth / giorni.size - spazio).coerceIn(12.dp, 26.dp)
        } else {
            ((maxWidth - spazio * (giorni.size - 1)) / giorni.size).coerceIn(12.dp, 26.dp)
        }
        val colonna = if (valore != null) larghezza + spazio else larghezza

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(
                if (valore != null) 0.dp else spazio,
                Alignment.CenterHorizontally,
            ),
            verticalAlignment = Alignment.Bottom,
        ) {
            giorni.forEachIndexed { indice, giorno ->
                val inLuce = if (selezionato == null) {
                    indice == giorni.lastIndex
                } else {
                    giorno.giorno == selezionato
                }
                val frase = descrizione?.invoke(giorno)
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .then(if (valore != null) Modifier.width(colonna) else Modifier)
                        .then(if (frase != null) Modifier.clearAndSetSemantics { contentDescription = frase } else Modifier),
                ) {
                    Box(
                        modifier = Modifier.width(colonna).height(altezza),
                        contentAlignment = Alignment.BottomCenter,
                    ) {
                        val minuti = giorno.minuti
                        if (minuti == null) {
                            Canvas(
                                modifier = Modifier.width(larghezza).height(10.dp),
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
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                if (valore != null) {
                                    TestoSuUnaRiga(
                                        testo = valore(minuti),
                                        style = stileValore.copy(
                                            fontWeight = if (inLuce) FontWeight.SemiBold else FontWeight.Normal,
                                        ),
                                        minimo = 8.sp,
                                        color = if (inLuce) {
                                            MaterialTheme.colorScheme.onSurface
                                        } else {
                                            MaterialTheme.colorScheme.onSurfaceVariant
                                        },
                                        modifier = Modifier.fillMaxWidth(),
                                    )
                                    Spacer(modifier = Modifier.height(2.dp))
                                }
                                Box(
                                    modifier = Modifier
                                        .width(larghezza)
                                        .height(((altezza - riserva) * frazione).coerceAtLeast(3.dp))
                                        .background(
                                            if (inLuce) acceso else spento,
                                            cima,
                                        ),
                                )
                            }
                        }
                    }
                    Text(
                        text = giorno.etichetta,
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 1,
                        softWrap = false,
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

// --- 3. Le medie e i totali ---------------------------------------------------------

/**
 * I numeri della settimana e del mese, una cella accanto all'altra. Una cella
 * c'è solo se l'app ha il dato per quel periodo: mai uno zero finto.
 */
@Composable
fun BloccoMedie(celle: List<CellaMedia>, modifier: Modifier = Modifier) {
    if (celle.isEmpty()) return
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(Spazi.l),
    ) {
        celle.forEach { MediaCella(it, Modifier.weight(1f)) }
    }
}

/** Una cella: etichetta piccola, il valore, e sotto le righe che lo spiegano. */
@Composable
private fun MediaCella(cella: CellaMedia, modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        Text(
            text = cella.etichetta,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(Spazi.xs))
        // Col testo grande va a capo invece di tagliarsi.
        Text(
            text = cella.valore,
            style = if (cella.grande) MaterialTheme.typography.titleLarge else MaterialTheme.typography.titleMedium,
        )
        cella.sotto.forEach { riga ->
            Text(
                text = riga,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
