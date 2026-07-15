package eu.stgm.pactum.figlio.aggiornamento

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import eu.stgm.pactum.figlio.BuildConfig
import eu.stgm.pactum.figlio.dati.ConfigurazionePostino
import eu.stgm.pactum.figlio.dati.Impostazioni
import eu.stgm.pactum.figlio.dati.InfoVersione
import eu.stgm.pactum.figlio.rete.PostinoClient
import java.io.File
import java.io.IOException

/**
 * L'auto-aggiornamento del figlio (tappa 6, contratto-api.md GET /api/versione).
 *
 * Confronta il `versioneCode` pubblicato dal postino col proprio
 * BuildConfig.VERSION_CODE; se il server è più avanti scarica l'APK — SOLO dal
 * server del patto, mai da un host del payload — e lo installa via
 * PackageInstaller (la firma con la stessa chiave è la garanzia d'integrità).
 * La prima installazione mostra il dialogo di sistema (InstallReceiver), le
 * successive possono essere silenziose dove Android lo concede.
 *
 * Anti-martellamento: il versionCode già tentato resta in Impostazioni, così
 * non si riscarica l'APK né si ripresenta il dialogo a ogni giro del worker.
 * L'installazione è del tutto assente prima della configurazione del patto:
 * il chiamante passa la config, e senza server non si scarica nulla.
 */
class Aggiornatore(private val context: Context) {

    suspend fun controlla(configurazione: ConfigurazionePostino, info: InfoVersione?) {
        if (info == null || !configurazione.completa) return
        if (info.versioneCode <= BuildConfig.VERSION_CODE) return

        val impostazioni = Impostazioni(context)
        if (impostazioni.leggiVersioneTentata() >= info.versioneCode) return

        // Solo un percorso relativo al server del patto: l'update non segue mai
        // un URL assoluto arrivato nel metadata.
        if (!info.url.startsWith("/")) return

        val apk = File(context.cacheDir, NOME_APK)
        val scaricato = PostinoClient(configurazione).scaricaSuFile(info.url, apk)
        if (!scaricato) return // rete o server muto: si ritenta al giro dopo (niente "tentata")

        if (installa(apk)) impostazioni.registraVersioneTentata(info.versioneCode)
    }

    /** Apre una sessione, ci scrive l'APK e la conferma; l'esito arriva a InstallReceiver. */
    private fun installa(apk: File): Boolean {
        val installer = context.packageManager.packageInstaller
        return try {
            val parametri = PackageInstaller.SessionParams(
                PackageInstaller.SessionParams.MODE_FULL_INSTALL,
            ).apply { setAppPackageName(context.packageName) }
            val idSessione = installer.createSession(parametri)
            installer.openSession(idSessione).use { sessione ->
                apk.inputStream().use { ingresso ->
                    sessione.openWrite(NOME_APK, 0, apk.length()).use { uscita ->
                        ingresso.copyTo(uscita)
                        sessione.fsync(uscita)
                    }
                }
                // FLAG_MUTABLE: il sistema completa l'intent con l'esito e, nel
                // caso PENDING_USER_ACTION, con la propria Activity di conferma.
                val avviso = PendingIntent.getBroadcast(
                    context,
                    idSessione,
                    Intent(context, InstallReceiver::class.java).setAction(InstallReceiver.AZIONE),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
                )
                sessione.commit(avviso.intentSender)
            }
            true
        } catch (e: IOException) {
            false
        } finally {
            apk.delete() // i byte sono già nella sessione: il file di cache non serve più
        }
    }

    private companion object {
        const val NOME_APK = "aggiornamento.apk"
    }
}
