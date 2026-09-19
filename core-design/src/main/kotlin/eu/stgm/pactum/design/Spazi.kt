package eu.stgm.pactum.design

import androidx.compose.ui.unit.dp

// core-design è una cartella sorgente inclusa da ENTRAMBE le app (sourceSets in
// app/build.gradle.kts), non un modulo: le due app sono build Gradle separate.
// Regola dura: qui dentro niente `R`, niente `stringResource`, niente risorse.
// Le parole arrivano come parametri, altrimenti il codice si lega a un'app sola.

/**
 * Le spaziature del prodotto, al posto dei 6/10/14 sparsi a mano.
 * Regola: dentro una card solo `xs`/`s`/`m`; tra i blocchi solo `l`/`xl`;
 * `xxl` solo per staccare la sezione eroe dal resto.
 *
 * Il tono passa dalla densità, non dai componenti: genitore padding 16 e
 * spacedBy 12, figlio padding 20 e spacedBy 16.
 */
object Spazi {
    val xs = 4.dp
    val s = 8.dp
    val m = 12.dp
    val l = 16.dp
    val xl = 24.dp
    val xxl = 32.dp
}
