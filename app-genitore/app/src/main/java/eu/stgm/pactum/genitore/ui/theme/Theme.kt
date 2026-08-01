package eu.stgm.pactum.genitore.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

// Blu del binocolo: l'app del genitore si distingue dal verde del figlio.
// Lo schema è COMPLETO di proposito: con il solo `primary` tutto il resto
// restava la baseline M3, che è lavanda — FAB, chip, FilledTonalButton e
// FilterChip selezionati erano viola dentro un'app blu.
private val SchemaChiaro = lightColorScheme(
    primary = Color(0xFF2C5D8F),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFD5E3F5),
    onPrimaryContainer = Color(0xFF10395E),
    secondary = Color(0xFF4C6379),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFDDE4EC),
    onSecondaryContainer = Color(0xFF1B2C3A),
    tertiary = Color(0xFF7A5C00),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFF3E3C4),
    onTertiaryContainer = Color(0xFF3A2C00),
    background = Color(0xFFFBFCFD),
    onBackground = Color(0xFF191C1E),
    surface = Color(0xFFFBFCFD),
    onSurface = Color(0xFF191C1E),
    surfaceVariant = Color(0xFFE2E7EC),
    onSurfaceVariant = Color(0xFF43484D),
    outline = Color(0xFF73787D),
    outlineVariant = Color(0xFFC3C8CD),
    error = Color(0xFFB3261E),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFF9DEDC),
    onErrorContainer = Color(0xFF410E0B),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF5F7FA),
    surfaceContainer = Color(0xFFEFF2F6),
    surfaceContainerHigh = Color(0xFFE9EDF2),
    surfaceContainerHighest = Color(0xFFE3E8EE),
)

private val SchemaScuro = darkColorScheme(
    primary = Color(0xFF9CC7F2),
    onPrimary = Color(0xFF123A5E),
    primaryContainer = Color(0xFF24486D),
    onPrimaryContainer = Color(0xFFD5E3F5),
    secondary = Color(0xFFB3C4D4),
    onSecondary = Color(0xFF1E2C38),
    secondaryContainer = Color(0xFF33414E),
    onSecondaryContainer = Color(0xFFDDE4EC),
    tertiary = Color(0xFFE7C77A),
    onTertiary = Color(0xFF3E2E00),
    tertiaryContainer = Color(0xFF4A3A00),
    onTertiaryContainer = Color(0xFFF3E3C4),
    background = Color(0xFF101418),
    onBackground = Color(0xFFE2E7EC),
    surface = Color(0xFF101418),
    onSurface = Color(0xFFE2E7EC),
    surfaceVariant = Color(0xFF42484E),
    onSurfaceVariant = Color(0xFFC3C8CD),
    outline = Color(0xFF8D9299),
    outlineVariant = Color(0xFF42484E),
    error = Color(0xFFF2B8B5),
    onError = Color(0xFF601410),
    errorContainer = Color(0xFF601410),
    onErrorContainer = Color(0xFFF9DEDC),
    surfaceContainerLowest = Color(0xFF0B0E11),
    surfaceContainerLow = Color(0xFF171B1F),
    surfaceContainer = Color(0xFF1B1F23),
    surfaceContainerHigh = Color(0xFF252A2F),
    surfaceContainerHighest = Color(0xFF303539),
)

/**
 * Le spaziature del prodotto, al posto dei 6/10/14 sparsi a mano.
 * Regola: dentro una card solo `xs`/`s`/`m`; tra i blocchi solo `l`/`xl`;
 * `xxl` solo per staccare la sezione eroe dal resto.
 */
object Spazi {
    val xs = 4.dp
    val s = 8.dp
    val m = 12.dp
    val l = 16.dp
    val xl = 24.dp
    val xxl = 32.dp
}

/** Forme: le Card passano da 12 a 14, la scheda eroe e i dialoghi a 20. */
private val PactumShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

/**
 * I colori del PATTO: gli unici fuori da `colorScheme`, perché il loro
 * significato non dipende dal ruolo Material ma dal patto.
 *
 * Tre leggi:
 *  1. Mantenuta/FuoriRegola compaiono SOLO dentro la striscia degli 8 giorni.
 *  2. Il silenzio del canale non è mai rosso: nove volte su dieci è batteria.
 *  3. `error`/`errorContainer` restano solo per la validazione dei form.
 */
object Patto {
    val MantenutaChiaro = Color(0xFF1E6B33)
    val MantenutaScuro = Color(0xFF6FBF73)
    val FuoriRegolaChiaro = Color(0xFFC97C62)
    val FuoriRegolaScuro = Color(0xFFB3543F)
    val SilenzioChiaro = Color(0xFF4C5A69)
    val SilenzioScuro = Color(0xFF9AA7B4)

    /** L'inchiostro sopra i pieni del patto: bianco o quasi-nero, mai grigio. */
    val InchiostroChiaro = Color(0xFFFFFFFF)
    val InchiostroScuro = Color(0xFF10181C)
}

/**
 * I colori delle CATEGORIE d'uso: servono solo a dire "questa fetta è quella",
 * non hanno significato di patto — un blu qui non promuove e un ocra non
 * condanna. Perciò sono desaturati e di famiglia coerente con l'app (blu,
 * verde, ocra, terracotta, grigio-blu): niente fluo, niente semaforo.
 *
 * Il rosso-terracotta del patto (`FuoriRegolaChiaro`) NON compare qui: resta
 * riservato all'eccesso oltre il limite, così una fetta grande non si confonde
 * mai con una regola infranta.
 */
object Categorie {
    // Cinque tinte tenute lontane a mano sulla ruota: blu 210°, terracotta 18°,
    // verde 100°, ocra 42°, grigio-blu 213° quasi scarico. Le due calde
    // (terracotta e ocra) sono le più a rischio di confondersi in un pallino da
    // 11 dp: stanno a 24° l'una dall'altra e a chiarezza diversa, apposta.
    val Social = Color(0xFF3F6FA6)
    val Video = Color(0xFF9A5A40)
    val Giochi = Color(0xFF608E49)
    val Musica = Color(0xFFAA893C)
    val Altro = Color(0xFF7E8894)

    /** Per le chiavi fuori convenzione: stessa famiglia, scelte in modo stabile. */
    val Riserva = listOf(
        Color(0xFF3F8A85),
        Color(0xFF85628A),
        Color(0xFF5B67A0),
        Color(0xFF8F5A6B),
    )
}

/**
 * Il colore di una chiave di categoria del contratto ("categoria:social").
 * Una chiave sconosciuta prende un colore di riserva sempre uguale a sé stesso
 * (dipende solo dal nome): la stessa categoria non cambia tinta tra un
 * aggiornamento e l'altro.
 */
fun coloreCategoria(chiave: String): Color =
    when (chiave.removePrefix("categoria:").lowercase()) {
        "social" -> Categorie.Social
        "video" -> Categorie.Video
        "giochi" -> Categorie.Giochi
        "musica" -> Categorie.Musica
        "altro" -> Categorie.Altro
        else -> Categorie.Riserva[(chiave.hashCode() and Int.MAX_VALUE) % Categorie.Riserva.size]
    }

// L'app va sempre su fondo chiaro (v. PactumTheme), quindi qui valgono sempre
// le varianti "chiare" dei colori del patto.

@Composable
fun coloreMantenuta(): Color = Patto.MantenutaChiaro

@Composable
fun coloreFuoriRegola(): Color = Patto.FuoriRegolaChiaro

@Composable
fun coloreSilenzio(): Color = Patto.SilenzioChiaro

/** Inchiostro leggibile sopra un pieno "mantenuta". */
@Composable
fun inchiostroSuMantenuta(): Color = Patto.InchiostroChiaro

/** Inchiostro leggibile sopra un pieno "fuori regola". */
@Composable
fun inchiostroSuFuoriRegola(): Color = Patto.InchiostroScuro

/** Inchiostro leggibile sopra il pieno del silenzio. */
@Composable
fun inchiostroSuSilenzio(): Color = Patto.InchiostroChiaro

/** Pactum va sempre su fondo chiaro: la finestra è un referto, si legge su carta
 *  bianca. Su fondo nero i colori del patto perdono il loro significato e l'app
 *  sembra un pannello di diagnostica. Lo schema scuro resta definito ma non è
 *  in uso (scelta di Andrea, 31/07). */
@Composable
fun PactumTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = SchemaChiaro,
        typography = PactumTypography,
        shapes = PactumShapes,
        content = content,
    )
}
