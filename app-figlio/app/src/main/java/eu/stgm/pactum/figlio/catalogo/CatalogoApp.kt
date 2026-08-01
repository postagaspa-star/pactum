package eu.stgm.pactum.figlio.catalogo

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import eu.stgm.pactum.figlio.R

/** Una app installata, mostrabile nel selettore delle regole limite_tempo. */
data class AppInstallata(val pacchetto: String, val etichetta: String)

/**
 * Il catalogo delle app installate e la loro categoria, CONDIVISO tra il
 * selettore delle regole (RegoleScreen) e il valutatore locale
 * (SentinellaPatto): i due devono concordare su come una app finisce in
 * "categoria:social" e su come un pacchetto si legge in chiaro, altrimenti una
 * regola scelta col selettore non scatterebbe mai.
 *
 * `app_o_categoria` (contratto-api.md v2.1) è un nome pacchetto Android oppure
 * una chiave `categoria:*`; il match del valutatore è esatto sul pacchetto o
 * sulla categoria. La categoria di un pacchetto viene da ApplicationInfo.category
 * (mapping interno all'app). QUERY_ALL_PACKAGES è già nel manifest (sideload,
 * nessuna policy Play — architettura.md).
 */
object CatalogoApp {

    const val PREFISSO_CATEGORIA = "categoria:"

    // Le chiavi di categoria fisse del contratto v2.1.
    const val CAT_SOCIAL = "categoria:social"
    const val CAT_GIOCHI = "categoria:giochi"
    const val CAT_VIDEO = "categoria:video"
    const val CAT_MUSICA = "categoria:musica"
    const val CAT_ALTRO = "categoria:altro"

    val CATEGORIE = listOf(CAT_SOCIAL, CAT_GIOCHI, CAT_VIDEO, CAT_MUSICA, CAT_ALTRO)

    /** La categoria del contratto per una app, da ApplicationInfo.category. */
    fun categoriaDi(info: ApplicationInfo): String = when (info.category) {
        ApplicationInfo.CATEGORY_SOCIAL -> CAT_SOCIAL
        ApplicationInfo.CATEGORY_GAME -> CAT_GIOCHI
        ApplicationInfo.CATEGORY_VIDEO -> CAT_VIDEO
        ApplicationInfo.CATEGORY_AUDIO -> CAT_MUSICA
        else -> CAT_ALTRO
    }

    /** La categoria di un pacchetto installato, "categoria:altro" se ignoto. */
    fun categoriaDiPacchetto(context: Context, pacchetto: String): String =
        infoApplicazione(context, pacchetto)?.let { categoriaDi(it) } ?: CAT_ALTRO

    /**
     * Le app con un'icona nel launcher (quelle che il ragazzo riconosce), la
     * propria esclusa, ordinate per etichetta. Chiamata off-main (PackageManager
     * è lento): il selettore la carica su Dispatchers.IO.
     */
    fun appInstallate(context: Context): List<AppInstallata> {
        val pm = context.packageManager
        val mia = context.packageName
        return tutteLeApp(pm)
            .asSequence()
            .filter { it.packageName != mia }
            .filter { pm.getLaunchIntentForPackage(it.packageName) != null }
            .map { AppInstallata(it.packageName, pm.getApplicationLabel(it).toString()) }
            .sortedBy { it.etichetta.lowercase() }
            .toList()
    }

    /**
     * Vero se il tempo passato su questo pacchetto va nella fotografia d'uso.
     *
     * Fuori restano tre cose che non sono "tempo su una app" e che nessun altro
     * strumento (Family Link compreso) conta, e che gonfiavano il totale:
     *  - la schermata Home: aprire il cassetto delle app non è usare una app;
     *  - i pezzi di sistema senza icona propria (servizi, installer, permessi);
     *  - **Pactum stessa**: un testimone che si mette a referto da solo è ridicolo,
     *    e il genitore vedrebbe crescere i minuti solo perché il figlio consulta
     *    il proprio patto.
     * Il criterio non è una lista di nomi (cambia da telefono a telefono) ma due
     * domande al sistema: sei tu il launcher? hai un'icona da cui ti si avvia?
     */
    fun contaNellUso(context: Context, pacchetto: String): Boolean {
        if (pacchetto == context.packageName) return false
        if (pacchetto in PACCHETTI_PACTUM) return false
        if (pacchetto == launcherPredefinito(context)) return false
        return context.packageManager.getLaunchIntentForPackage(pacchetto) != null
    }

    /** Il pacchetto della schermata Home in uso (varia per marca e per scelta). */
    private fun launcherPredefinito(context: Context): String? {
        val intento = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val pm = context.packageManager
        val risolto = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.resolveActivity(intento, PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_DEFAULT_ONLY.toLong()))
        } else {
            @Suppress("DEPRECATION")
            pm.resolveActivity(intento, PackageManager.MATCH_DEFAULT_ONLY)
        }
        return risolto?.activityInfo?.packageName
    }

    /** Le due app del patto: nessuna delle due si conta da sola. */
    private val PACCHETTI_PACTUM = setOf("eu.stgm.pactum.figlio", "eu.stgm.pactum.genitore")

    /**
     * L'etichetta leggibile di un valore `app_o_categoria`: il nome della
     * categoria, oppure l'etichetta dell'app dal pacchetto; ripiego sul valore
     * grezzo se il pacchetto non è (più) installato o è un vecchio testo libero.
     */
    fun etichettaValore(context: Context, valore: String): String {
        if (valore.startsWith(PREFISSO_CATEGORIA)) return nomeCategoria(context, valore)
        val info = infoApplicazione(context, valore) ?: return valore
        return context.packageManager.getApplicationLabel(info).toString()
    }

    /** Il nome leggibile di una chiave di categoria (da strings.xml). */
    fun nomeCategoria(context: Context, chiave: String): String = when (chiave) {
        CAT_SOCIAL -> context.getString(R.string.categoria_social)
        CAT_GIOCHI -> context.getString(R.string.categoria_giochi)
        CAT_VIDEO -> context.getString(R.string.categoria_video)
        CAT_MUSICA -> context.getString(R.string.categoria_musica)
        CAT_ALTRO -> context.getString(R.string.categoria_altro)
        else -> chiave
    }

    private fun infoApplicazione(context: Context, pacchetto: String): ApplicationInfo? = try {
        val pm = context.packageManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.getApplicationInfo(pacchetto, PackageManager.ApplicationInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            pm.getApplicationInfo(pacchetto, 0)
        }
    } catch (e: PackageManager.NameNotFoundException) {
        null
    }

    private fun tutteLeApp(pm: PackageManager): List<ApplicationInfo> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.getInstalledApplications(PackageManager.ApplicationInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            pm.getInstalledApplications(0)
        }
}
