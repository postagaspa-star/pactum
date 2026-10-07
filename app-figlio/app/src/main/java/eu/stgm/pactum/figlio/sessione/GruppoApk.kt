package eu.stgm.pactum.figlio.sessione

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import eu.stgm.pactum.figlio.misura.PeriodoSessione
import java.time.LocalDate
import java.time.ZoneId

/**
 * (0.11) `gruppo:apk`: le app installate fuori dal Play Store. Un'app è nel
 * gruppo se non è di sistema e chi l'ha installata non è il Play Store
 * (AppDellaSessione.nelGruppoApk). Qui solo le domande al sistema.
 */
object GruppoApk {

    /**
     * Un classificatore per un giro: true = nel gruppo, false = no, null = non
     * si sa (app che non si trova, sistema che non risponde). Ogni pacchetto si
     * chiede una volta sola. Non è thread-safe: uno per giro, come il filtro d'uso.
     */
    fun classificatore(context: Context): (String) -> Boolean? {
        val pm = context.applicationContext.packageManager
        val risposte = HashMap<String, Boolean?>()
        return { pacchetto ->
            if (risposte.containsKey(pacchetto)) risposte[pacchetto] else leggi(pm, pacchetto).also { risposte[pacchetto] = it }
        }
    }

    private fun leggi(pm: PackageManager, pacchetto: String): Boolean? {
        val info = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getApplicationInfo(pacchetto, PackageManager.ApplicationInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                pm.getApplicationInfo(pacchetto, 0)
            }
        } catch (e: Exception) {
            return null
        }
        val diSistema = (info.flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) != 0
        if (diSistema) return false
        var letto = true
        val installatore = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                pm.getInstallSourceInfo(pacchetto).installingPackageName
            } else {
                @Suppress("DEPRECATION")
                pm.getInstallerPackageName(pacchetto)
            }
        } catch (e: Exception) {
            letto = false
            null
        }
        return AppDellaSessione.nelGruppoApk(diSistema = false, installatore = installatore, installatoreLetto = letto)
    }
}

/** (0.11) I periodi di Sessione di un giorno, e se il telefono sa quali sessioni ci sono state. */
data class PeriodiDelGiorno(val periodi: List<PeriodoSessione>, val note: Boolean)

/**
 * (0.11) I periodi di Sessione per la misura di un giorno: le sessioni svolte
 * che lo toccano, ciascuna con le sue app. Se qualcosa va storto non si
 * toglie niente (meglio contare tutto che non contare per sbaglio), e i
 * minuti in sessione non si sanno.
 */
object PeriodiSessione {

    fun delGiorno(context: Context, giorno: LocalDate, zona: ZoneId, fine: Long): PeriodiDelGiorno = try {
        val inizio = giorno.atStartOfDay(zona).toInstant().toEpochMilli()
        val note = ArchivioSessioni.sessioniNote(context)
        val svolte = if (fine > inizio) ArchivioSessioni.leggi(context).periodi(inizio, fine) else emptyList()
        // (0.18, contratto v4.0) Anche la Sessione Studio: il tempo nelle app
        // della sua lista non conta (limiti, categorie, totale, fasce), e va in
        // `sessioni_minuti`. I suoi periodi sono sull'ora del server: si portano
        // sull'orologio del telefono (quello degli eventi d'uso) con lo scarto misurato.
        val studio = if (fine > inizio) periodiStudio(context, inizio, fine) else emptyList()
        if (svolte.isEmpty() && studio.isEmpty()) {
            PeriodiDelGiorno(emptyList(), note)
        } else {
            // Un classificatore per tutta la lettura: ogni app si chiede una volta.
            val gruppoApk = GruppoApk.classificatore(context)
            PeriodiDelGiorno(
                svolte.map { svolta ->
                    // Nella misura "non si sa" vuol dire fuori: conta come sempre.
                    PeriodoSessione(svolta.inizio, svolta.fine) { p -> AppDellaSessione.ammette(svolta.app, p, gruppoApk) == true }
                } + studio.map { (da, a, app) ->
                    PeriodoSessione(da, a) { p -> AppDellaSessione.ammette(app, p, gruppoApk) == true }
                },
                note,
            )
        }
    } catch (e: Exception) {
        PeriodiDelGiorno(emptyList(), note = false)
    }

    /** (0.18) I periodi di Studio che toccano [da, a), sull'orologio del telefono, con le loro app. */
    private fun periodiStudio(context: Context, da: Long, a: Long): List<Triple<Long, Long, List<String>>> {
        val scarto = eu.stgm.pactum.figlio.faccende.ArchivioBlocco.leggi(context).scarto ?: 0L
        val adesso = System.currentTimeMillis()
        return eu.stgm.pactum.figlio.studio.ArchivioStudio.leggi(context).periodi
            .map { Triple(it.inizio - scarto, (it.fine?.minus(scarto)) ?: adesso, it.app) }
            .filter { (inizio, fine, _) -> inizio < a && fine > da && fine > inizio }
    }
}
