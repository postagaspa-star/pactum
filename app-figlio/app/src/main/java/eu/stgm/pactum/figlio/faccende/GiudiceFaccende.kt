package eu.stgm.pactum.figlio.faccende

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import eu.stgm.pactum.figlio.catalogo.CatalogoApp
import eu.stgm.pactum.figlio.permessi.PermessiHelper

/**
 * (0.13) Le domande al sistema per la barriera delle faccende, tenute per un
 * minuto (le app sempre usabili, chi ha un'icona, chi è di sistema, le
 * schermate Home), e la decisione per un'app (GuardiaFaccende). Lo usano il
 * giro della barriera delle faccende e quello della barriera delle Sessioni
 * (che si fa da parte solo per le app che il blocco copre già).
 * Non è thread-safe: uno per giro.
 */
class GiudiceFaccende(context: Context) {

    private val app = context.applicationContext

    private var sempreUsabili: Set<String> = emptySet()
    private var home: Set<String> = emptySet()
    private var contaNellUso: (String) -> Boolean? = { null }
    private var diSistema: (String) -> Boolean? = { null }
    private var rinfrescatoIl: Long? = null

    /** Le risposte che cambiano di rado si rileggono ogni minuto (o subito, con [forza]). */
    fun rinfresca(monotono: Long, forza: Boolean = false) {
        val ultimo = rinfrescatoIl
        if (!forza && ultimo != null && monotono - ultimo < RINFRESCO_MS) return
        rinfrescatoIl = monotono
        sempreUsabili = SempreUsabiliFaccende.leggi(app)
        home = schermateHome(app)
        val filtro = CatalogoApp.filtroUso(app)
        contaNellUso = { p -> try { filtro(p) } catch (e: Exception) { null } }
        diSistema = classificatoreSistema(app)
    }

    /** La decisione per [primoPiano] adesso (schermo acceso e sbloccato: lo controlla chi chiama). */
    fun decidi(primoPiano: String?, classe: String?, precedente: String?, ora: Istante): DecisioneFaccende =
        GuardiaFaccende.decidi(
            SituazioneFaccende(
                bloccoAttivo = StatoBlocco.attivoAdesso(ora),
                primoPiano = primoPiano,
                schermoAcceso = true,
                sbloccato = true,
                mostraSopra = PermessiHelper.puoMostrareSopra(app),
                accessoUso = PermessiHelper.haAccessoUso(app),
                // La fotocamera aperta da "Scatta la foto" resta usabile mentre si scatta.
                sempreUsabili = sempreUsabili + ScattoInCorso.attuali(ora.muro),
                contaNellUso = contaNellUso,
                diSistema = diSistema,
                classe = classe,
                precedente = precedente,
                home = home,
            ),
        )

    companion object {
        private const val RINFRESCO_MS = 60_000L

        /** Installata col telefono (anche se aggiornata dopo); null se non si trova. Ogni app una volta. */
        fun classificatoreSistema(context: Context): (String) -> Boolean? {
            val pm = context.applicationContext.packageManager
            val risposte = HashMap<String, Boolean?>()
            return { pacchetto ->
                if (risposte.containsKey(pacchetto)) risposte[pacchetto] else diSistema(pm, pacchetto).also { risposte[pacchetto] = it }
            }
        }

        fun diSistema(pm: PackageManager, pacchetto: String): Boolean? = try {
            val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getApplicationInfo(pacchetto, PackageManager.ApplicationInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                pm.getApplicationInfo(pacchetto, 0)
            }
            (info.flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) != 0
        } catch (e: PackageManager.NameNotFoundException) {
            null
        } catch (e: Exception) {
            null
        }

        /** Tutte le schermate Home installate: da lì (e dalle Recenti) una pagina web non è "di un'app". */
        fun schermateHome(context: Context): Set<String> = try {
            val pm = context.applicationContext.packageManager
            val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
            val trovate = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                pm.queryIntentActivities(intent, 0)
            }
            trovate.mapNotNull { it.activityInfo?.packageName }.toSet()
        } catch (e: Exception) {
            emptySet()
        }
    }
}
