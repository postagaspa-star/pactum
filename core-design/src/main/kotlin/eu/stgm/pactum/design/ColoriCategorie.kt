package eu.stgm.pactum.design

import androidx.compose.ui.graphics.Color

/**
 * I colori delle CATEGORIE d'uso, uguali nelle due app: servono solo a dire
 * "questa fetta è quella", non hanno significato di patto — un blu qui non
 * promuove e un ocra non condanna. Perciò sono desaturati e di una famiglia
 * coerente (blu, verde, ocra, terracotta, grigio-blu): niente fluo, niente
 * semaforo.
 *
 * Il terracotta del patto ([ColoriPatto]) NON compare qui: vive solo nella
 * striscia degli 8 giorni, così una fetta grande non si confonde mai con una
 * regola infranta.
 *
 * (0.16) Spostati qui dal tema dell'app del genitore, colori identici: adesso il
 * grafico del tempo c'è anche nell'app del figlio.
 */
object ColoriCategorie {
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
 * aggiornamento e l'altro, né tra un'app e l'altra.
 */
fun coloreCategoria(chiave: String): Color =
    when (chiave.removePrefix("categoria:").lowercase()) {
        "social" -> ColoriCategorie.Social
        "video" -> ColoriCategorie.Video
        "giochi" -> ColoriCategorie.Giochi
        "musica" -> ColoriCategorie.Musica
        "altro" -> ColoriCategorie.Altro
        else -> ColoriCategorie.Riserva[(chiave.hashCode() and Int.MAX_VALUE) % ColoriCategorie.Riserva.size]
    }
