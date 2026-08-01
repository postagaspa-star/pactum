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
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import eu.stgm.pactum.genitore.R
import eu.stgm.pactum.genitore.dati.MediaPeriodo
import eu.stgm.pactum.genitore.dati.Medie
import eu.stgm.pactum.genitore.dati.UsoCategoria
import eu.stgm.pactum.genitore.dati.UsoGiorno
import eu.stgm.pactum.genitore.ui.theme.Spazi
import eu.stgm.pactum.genitore.ui.theme.coloreCategoria
import eu.stgm.pactum.genitore.ui.theme.coloreFuoriRegola

/**
 * I grafici della finestra, disegnati a mano con Canvas: nessuna libreria,
 * nessun colore fuori dalla palette dell'app.
 *
 * Tre forme sole, riusate ovunque:
 *  1. [AnelloCategorie]  — come è diviso il tempo di un giorno;
 *  2. [BarreGiorni]      — gli ultimi otto giorni, uno accanto all'altro;
 *  3. [BarraOrizzontale] — quanto di un limite è stato consumato.
 *
 * Due leggi che valgono per tutti e tre:
 *  - un giorno SENZA fotografia non è uno zero: è un tratteggio vuoto, perché
 *    "non lo so" e "zero minuti" sono due notizie diverse;
 *  - il rosso-terracotta compare SOLO oltre un limite dichiarato: mai per un
 *    totale alto, mai per un dato vecchio, mai per una rete assente.
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
 * meno la somma delle categorie note. Serve a far tornare i conti tra il numero
 * al centro dell'anello (il totale del giorno) e le fette + la legenda: senza
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
 * L'anello delle categorie, col totale del giorno al centro.
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
    etichettaCentro: String,
    modifier: Modifier = Modifier,
    diametro: Dp = 184.dp,
    spessore: Dp = 18.dp,
) {
    val binario = MaterialTheme.colorScheme.surfaceVariant
    val unica = MaterialTheme.colorScheme.primary

    Box(modifier = modifier.size(diametro), contentAlignment = Alignment.Center) {
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

        // Il centro: il numero è l'eroe, l'etichetta lo introduce sottovoce.
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(horizontal = spessore + Spazi.s),
        ) {
            val testo = if (totaleMinuti == null) "—" else testoDurata(totaleMinuti.toLong())
            Text(
                text = testo,
                style = when {
                    testo.length <= 6 -> MaterialTheme.typography.displaySmall
                    testo.length <= 10 -> MaterialTheme.typography.headlineSmall
                    else -> MaterialTheme.typography.titleLarge
                },
                maxLines = 1,
            )
            Text(
                text = etichettaCentro,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
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
 * Quanto è stato consumato, su un binario grigio.
 *
 * Con un limite: la scala è `max(minuti, limite)`, così la tacca del limite sta
 * sempre dentro la barra e l'eccesso si vede per quello che è — la parte oltre
 * la tacca passa al terracotta del patto. Senza limite: la scala è l'app più
 * usata del giorno, e il colore resta quello della categoria.
 */
@Composable
fun BarraOrizzontale(
    minuti: Int,
    limite: Int?,
    riferimento: Int,
    colore: Color,
    modifier: Modifier = Modifier,
    spessore: Dp = 9.dp,
) {
    val binario = MaterialTheme.colorScheme.surfaceVariant
    val oltre = coloreFuoriRegola()
    val segno = MaterialTheme.colorScheme.onSurfaceVariant

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

        val scala = when {
            limite != null -> maxOf(minuti, limite)
            riferimento > 0 -> riferimento
            else -> minuti
        }.coerceAtLeast(1)

        val eccesso = if (limite != null) minuti - limite else 0
        if (eccesso > 0) {
            // Prima tutto terracotta, poi il pieno "entro il limite" sopra:
            // due rettangoli tondi annidati, nessun angolo che stona.
            drawRoundRect(
                color = oltre,
                topLeft = Offset(0f, alto),
                size = Size(larga, alta),
                cornerRadius = tondo,
            )
        }

        val entro = if (limite != null) minOf(minuti, limite) else minuti
        if (entro > 0) {
            val pieno = (larga * entro / scala.toFloat()).coerceIn(alta, larga)
            drawRoundRect(
                color = colore,
                topLeft = Offset(0f, alto),
                size = Size(pieno, alta),
                cornerRadius = tondo,
            )
        }

        if (limite != null && larga > 8.dp.toPx()) {
            val sottile = 2.dp.toPx()
            val x = (larga * limite / scala.toFloat())
                .coerceIn(sottile, larga - sottile)
            drawRect(
                color = segno,
                topLeft = Offset(x - sottile / 2f, alto - 3.dp.toPx()),
                size = Size(sottile, alta + 6.dp.toPx()),
            )
        }
    }
}

/** Una riga d'uso col grafico: nome e tempo sopra, barra sotto, limite raccontato. */
@Composable
fun RigaBarraUso(
    nome: String,
    minuti: Int,
    limite: Int?,
    riferimento: Int,
    colore: Color,
    modifier: Modifier = Modifier,
) {
    val terracotta = coloreFuoriRegola()
    Column(modifier = modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = nome,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = testoDurata(minuti.toLong()),
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(start = Spazi.s),
            )
        }
        BarraOrizzontale(
            minuti = minuti,
            limite = limite,
            riferimento = riferimento,
            colore = colore,
        )
        if (limite != null) {
            val sforato = minuti - limite
            Text(
                text = if (sforato > 0) {
                    stringResource(
                        R.string.grafico_oltre_limite,
                        testoDurata(sforato.toLong()),
                    )
                } else {
                    stringResource(R.string.tempo_limite, testoDurata(limite.toLong()))
                },
                style = MaterialTheme.typography.labelSmall,
                color = if (sforato > 0) {
                    terracotta
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }
    }
}

// --- La scheda eroe della finestra -------------------------------------------

/**
 * La scheda in cima alla finestra: l'anello del giorno con la sua legenda e,
 * sotto, gli otto giorni. È la prima cosa che il genitore vede — e in tre
 * secondi dice quanto, in cosa, e se quel giorno somiglia agli altri.
 *
 * Se la fotografia di OGGI non è ancora arrivata (capita: il telefono manda a
 * intervalli), l'anello mostra l'ULTIMO giorno che ha dati e lo dice col suo
 * nome — "14/07", non "oggi". Il ricorso a un anello grigio col trattino resta
 * solo per il caso in cui non ci sia proprio nulla: un "non lo so" non si
 * traveste da zero, ma neanche da schermata vuota.
 */
@Composable
fun SchedaUsoOggi(
    usoRecente: List<UsoGiorno>,
    medie: Medie? = null,
    modifier: Modifier = Modifier,
) {
    if (usoRecente.isEmpty()) return
    // Il giorno mostrato segue la selezione (come già fa la sezione Tempo): se il
    // giorno scelto sparisce al cambio di giornata si ricade sul predefinito
    // (l'ultimo con dati), non su un fantasma. null = nessuna scelta.
    val predefinito = usoRecente.lastOrNull { it.totaleMinuti != null } ?: usoRecente.last()
    var giornoScelto by rememberSaveable { mutableStateOf<String?>(null) }
    val mostrato = usoRecente.firstOrNull { it.giorno == giornoScelto } ?: predefinito
    val eOggi = mostrato.giorno == usoRecente.last().giorno
    val fette = fetteConResto(mostrato.categorie, mostrato.totaleMinuti)

    Card(shape = MaterialTheme.shapes.large, modifier = modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(Spazi.l)) {
            Text(
                text = stringResource(R.string.grafico_eroe_titolo),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(Spazi.m))
            Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                AnelloCategorie(
                    fette = fette,
                    totaleMinuti = mostrato.totaleMinuti,
                    etichettaCentro = when {
                        mostrato.totaleMinuti == null ->
                            stringResource(R.string.grafico_nessun_dato)
                        eOggi -> stringResource(R.string.tempo_chip_oggi)
                        else -> giornoBreve(mostrato.giorno)
                    },
                )
            }
            Spacer(modifier = Modifier.height(Spazi.l))
            if (fette.isEmpty()) {
                Text(
                    text = if (mostrato.totaleMinuti == null) {
                        stringResource(R.string.tempo_nessun_dato_spiega)
                    } else {
                        stringResource(R.string.grafico_categorie_vuoto)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                LegendaCategorie(fette)
            }
            Spacer(modifier = Modifier.height(Spazi.l))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Spacer(modifier = Modifier.height(Spazi.l))
            Text(
                text = stringResource(R.string.grafico_ultimi_giorni),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(Spazi.m))
            // Le barre ora si TOCCANO: la giornata scelta guida l'anello e la
            // legenda qui sopra (stesso meccanismo già collaudato in Tempo).
            BarreGiorni(
                giorni = usoRecente,
                selezionato = mostrato.giorno,
                onScelta = { giornoScelto = it },
            )

            // Le medie settimanale/mensile: additive e nascoste per riga se il
            // server non le manda (campo/sotto-oggetto null = niente da mostrare,
            // mai uno zero finto).
            if (medie != null && (medie.settimana != null || medie.mese != null)) {
                Spacer(modifier = Modifier.height(Spazi.l))
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Spacer(modifier = Modifier.height(Spazi.l))
                Row(
                    modifier = Modifier.fillMaxWidth(),
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
        }
    }
}

/** Una cella "media": etichetta piccola, minuti grandi, e su quanti giorni poggia. */
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
