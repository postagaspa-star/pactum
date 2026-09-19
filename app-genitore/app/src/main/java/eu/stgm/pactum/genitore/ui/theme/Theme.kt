package eu.stgm.pactum.genitore.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import eu.stgm.pactum.design.FormePactum
import eu.stgm.pactum.design.TipografiaPactum

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

// Nessuno schema scuro: Pactum va solo su fondo chiaro (scelta di Andrea).

// Spazi, forme, tipografia e colori del patto (`ColoriPatto`) sono in
// core-design, identici nelle due app. Qui resta solo ciò che è del genitore:
// la palette blu e i colori delle categorie d'uso.

/**
 * I colori delle CATEGORIE d'uso: servono solo a dire "questa fetta è quella",
 * non hanno significato di patto — un blu qui non promuove e un ocra non
 * condanna. Perciò sono desaturati e di famiglia coerente con l'app (blu,
 * verde, ocra, terracotta, grigio-blu): niente fluo, niente semaforo.
 *
 * Il terracotta del patto (`ColoriPatto.FuoriRegola`) NON compare qui: vive
 * solo nella striscia degli 8 giorni, così una fetta grande non si confonde mai
 * con una regola infranta.
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

/** Pactum va sempre su fondo chiaro: la finestra è un referto, si legge su carta
 *  bianca. Su fondo nero i colori del patto perdono il loro significato e l'app
 *  sembra un pannello di diagnostica (scelta di Andrea: niente tema scuro). */
@Composable
fun PactumTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = SchemaChiaro,
        typography = TipografiaPactum,
        shapes = FormePactum,
        content = content,
    )
}
