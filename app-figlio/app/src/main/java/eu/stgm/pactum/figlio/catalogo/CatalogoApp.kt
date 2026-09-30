package eu.stgm.pactum.figlio.catalogo

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Looper
import eu.stgm.pactum.figlio.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

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
 * una chiave `categoria:*` (dalla 0.9 anche "totale", tutto il telefono); il
 * match del valutatore è esatto sul pacchetto o sulla categoria. La categoria
 * di un pacchetto viene dalle eccezioni fisse (0.9: i messaggi fuori da ogni
 * categoria, YouTube social) e poi da ApplicationInfo.category (mapping
 * interno all'app). QUERY_ALL_PACKAGES è già nel manifest (sideload, nessuna
 * policy Play — architettura.md).
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

    /**
     * (0.9) Tutto il telefono: il limite vale su tutto l'uso del giorno, lo
     * stesso `totale_minuti` della fotografia che arriva al genitore.
     */
    const val CHIAVE_TOTALE = "totale"

    /** Solo la chiave esatta (minuscola, senza spazi): come la accetta il server. */
    fun eTotale(valore: String): Boolean = valore == CHIAVE_TOTALE

    /**
     * (0.9, decisione di Andrea) Le eccezioni fisse, che valgono PRIMA della
     * categoria dichiarata dall'app. L'unica tabella: da qui passano la
     * valutazione delle regole di categoria, la schermata Oggi e le categorie
     * della fotografia per il genitore.
     * - Le app di messaggi non sono in nessuna categoria ("Altre app"): non
     *   contano in social, giochi, video, musica. Discord, Instagram, Snapchat,
     *   TikTok e simili restano social.
     * - YouTube è social (dichiara "video").
     */
    private val CATEGORIE_FISSE: Map<String, String> = buildMap {
        listOf(
            "com.whatsapp", "com.whatsapp.w4b", // WhatsApp, WhatsApp Business
            "org.telegram.messenger", "org.telegram.messenger.web", "org.thunderdog.challegram", // Telegram
            "com.facebook.orca", "com.facebook.mlite", // Messenger, Messenger Lite
            "org.thoughtcrime.securesms", // Signal
            "com.google.android.apps.messaging", // Messaggi di Google
            "com.samsung.android.messaging", // Messaggi Samsung
            "com.viber.voip", // Viber
            "com.tencent.mm", // WeChat
            "jp.naver.line.android", // Line
            "ch.threema.app", // Threema
            "com.skype.raider", // Skype
        ).forEach { put(it, CAT_ALTRO) }
        put("com.google.android.youtube", CAT_SOCIAL)
    }

    /**
     * La categoria del contratto per un pacchetto: prima le eccezioni fisse,
     * poi quella che l'app dichiara ([dichiarata] = ApplicationInfo.category,
     * null se l'app non si trova).
     */
    fun categoriaDi(pacchetto: String, dichiarata: Int?): String =
        CATEGORIE_FISSE[pacchetto] ?: when (dichiarata) {
            ApplicationInfo.CATEGORY_SOCIAL -> CAT_SOCIAL
            ApplicationInfo.CATEGORY_GAME -> CAT_GIOCHI
            ApplicationInfo.CATEGORY_VIDEO -> CAT_VIDEO
            ApplicationInfo.CATEGORY_AUDIO -> CAT_MUSICA
            else -> CAT_ALTRO
        }

    /** La categoria di un pacchetto, "categoria:altro" se ignoto. */
    fun categoriaDiPacchetto(context: Context, pacchetto: String): String =
        categoriaDi(pacchetto, infoApplicazione(context, pacchetto)?.category)

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
     * Il filtro della fotografia d'uso: vero se il tempo passato su un pacchetto
     * conta (nella fotografia, nel totale, nelle regole e nelle fasce).
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
     *
     * (0.9) Un filtro vale per un giro intero: la schermata Home si risolve una
     * volta sola, e ogni pacchetto si chiede al sistema una volta sola (le fasce
     * lo chiedono per ogni sua sessione). Non è thread-safe: un filtro per giro.
     */
    fun filtroUso(context: Context): (String) -> Boolean {
        val pm = context.packageManager
        val mio = context.packageName
        val home = launcherPredefinito(context)
        val risposte = HashMap<String, Boolean>()
        return { pacchetto ->
            risposte.getOrPut(pacchetto) {
                pacchetto != mio &&
                    pacchetto !in PACCHETTI_PACTUM &&
                    pacchetto != home &&
                    pm.getLaunchIntentForPackage(pacchetto) != null
            }
        }
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
     * L'etichetta leggibile di un valore `app_o_categoria`: "Tutto il telefono",
     * il nome della categoria, oppure l'etichetta dell'app dal pacchetto. Se il
     * pacchetto non è (più) installato, l'ultimo nome che l'app gli ha visto;
     * solo se non l'ha mai visto, il valore grezzo (o un vecchio testo libero).
     */
    fun etichettaValore(context: Context, valore: String): String {
        if (eTotale(valore)) return context.getString(R.string.chiave_totale)
        if (valore.startsWith(PREFISSO_CATEGORIA)) return nomeCategoria(context, valore)
        val info = infoApplicazione(context, valore) ?: return ultimoNome(context, valore) ?: valore
        val etichetta = context.packageManager.getApplicationLabel(info).toString()
        ricordaNome(context, valore, etichetta)
        return etichetta
    }

    // --- L'ultimo nome visto per pacchetto ------------------------------------
    // TikTok disinstallata dopo aver scritto la regola: PackageManager non la
    // risolve più e la regola diventerebbe "com.zhiliaoapp.musically". Ogni
    // etichetta risolta passa di qui (anche quelle dei `nomi` della fotografia
    // d'uso, ogni 15 minuti) e resta sul telefono.
    //
    // La descrizione di una regola si scrive DENTRO la composizione, dove il
    // disco non si tocca: i nomi vivono in memoria. Si caricano dal disco una
    // volta, fuori dal thread principale ([precarica] all'avvio del processo,
    // oppure alla prima chiamata da un thread di lavoro), e ogni nome nuovo si
    // scrive su disco da un thread di I/O. Sul thread principale, prima che il
    // caricamento finisca, un'app disinstallata si legge col suo pacchetto:
    // è un attimo, e alla composizione dopo il nome c'è.

    private const val PREFERENZE_NOMI = "nomi_app"

    private val nomi = ConcurrentHashMap<String, String>()

    @Volatile
    private var nomiCaricati = false

    private val ambitoDisco = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Carica i nomi ricordati, fuori dalla composizione: da chiamare all'avvio del processo. */
    fun precarica(context: Context) {
        val app = context.applicationContext
        ambitoDisco.launch { caricaNomi(app) }
    }

    private fun caricaNomi(context: Context) {
        if (nomiCaricati) return
        synchronized(nomi) {
            if (nomiCaricati) return
            preferenzeNomi(context).all.forEach { (pacchetto, nome) ->
                // Un nome risolto nel frattempo è più fresco di quello su disco.
                if (nome is String) nomi.putIfAbsent(pacchetto, nome)
            }
            nomiCaricati = true
        }
    }

    private fun ultimoNome(context: Context, pacchetto: String): String? {
        // Da un thread di lavoro (worker, sentinella, ViewModel) il disco si
        // può leggere; dal thread principale (la composizione) mai.
        if (!nomiCaricati && Looper.myLooper() != Looper.getMainLooper()) caricaNomi(context)
        return nomi[pacchetto]
    }

    private fun ricordaNome(context: Context, pacchetto: String, etichetta: String) {
        // Un'app senza etichetta restituisce il pacchetto stesso: non è un nome.
        if (etichetta.isBlank() || etichetta == pacchetto) return
        // Si scrive solo quando cambia: questa funzione gira a ogni composizione.
        if (nomi.put(pacchetto, etichetta) == etichetta) return
        val app = context.applicationContext
        ambitoDisco.launch { preferenzeNomi(app).edit().putString(pacchetto, etichetta).apply() }
    }

    private fun preferenzeNomi(context: Context) =
        context.applicationContext.getSharedPreferences(PREFERENZE_NOMI, Context.MODE_PRIVATE)

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
