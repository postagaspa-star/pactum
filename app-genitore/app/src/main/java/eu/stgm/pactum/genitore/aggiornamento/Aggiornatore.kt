package eu.stgm.pactum.genitore.aggiornamento

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import eu.stgm.pactum.genitore.BuildConfig
import eu.stgm.pactum.genitore.dati.Impostazioni
import eu.stgm.pactum.genitore.rete.PostinoClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/** L'esito di un controllo aggiornamenti, per riferirlo in italiano nella UI. */
sealed interface EsitoAggiornamento {
    /** Già all'ultima versione (o il server non pubblica una build del genitore). */
    data object GiaAggiornato : EsitoAggiornamento

    /** Trovata una versione più nuova: scaricata, installazione avviata. */
    data class Avviato(val versioneNome: String) : EsitoAggiornamento

    /** Manca indirizzo del server o token: non c'è da dove controllare. */
    data object ConfigMancante : EsitoAggiornamento

    /** Server non raggiungibile o risposta inattesa. */
    data object Irraggiungibile : EsitoAggiornamento

    /** Download o avvio dell'installazione fallito. */
    data object Fallito : EsitoAggiornamento
}

/**
 * L'auto-aggiornamento del binocolo (tappa 6): chiede a GET /api/versione
 * l'ultima versione del genitore, la confronta col proprio versionCode e, se il
 * server è più avanti, scarica l'APK e lancia PackageInstaller. Il primo
 * aggiornamento passa dal dialogo di sistema (conferma + "installa app
 * sconosciute"); i successivi sono più silenziosi dove Android lo consente.
 *
 * Idempotente e a prova di offline: ogni fallimento torna un esito esplicito,
 * mai un'eccezione. Va bene richiamarlo a ogni giro della vedetta.
 */
class Aggiornatore(private val context: Context) {

    suspend fun controlla(): EsitoAggiornamento {
        val configurazione = Impostazioni(context).leggiConfigurazione()
        if (!configurazione.completa) return EsitoAggiornamento.ConfigMancante

        val postino = PostinoClient(configurazione)
        val info = postino.leggiVersione() ?: return EsitoAggiornamento.Irraggiungibile
        // Il server ha risposto ma non conosce una build del genitore: niente da
        // fare, non è un errore di rete.
        val ultima = info.genitore ?: return EsitoAggiornamento.GiaAggiornato

        if (ultima.versioneCode <= BuildConfig.VERSION_CODE) return EsitoAggiornamento.GiaAggiornato
        if (ultima.url.isBlank()) return EsitoAggiornamento.Fallito

        val apk = preparaFile() ?: return EsitoAggiornamento.Fallito
        if (!postino.scaricaApk(ultima.url, apk)) return EsitoAggiornamento.Fallito

        return if (installa(apk)) {
            EsitoAggiornamento.Avviato(ultima.versioneNome)
        } else {
            EsitoAggiornamento.Fallito
        }
    }

    /** File pulito nella cache dove scaricare l'APK; null se non creabile. */
    private fun preparaFile(): File? = try {
        val cartella = File(context.cacheDir, CARTELLA).apply { mkdirs() }
        File(cartella, NOME_APK).apply { delete() }
    } catch (e: IOException) {
        null
    } catch (e: SecurityException) {
        null
    }

    /**
     * Apre una sessione PackageInstaller, ci scrive l'APK e la conferma. L'esito
     * (incluso il dialogo "conferma installazione") arriva ad [AggiornamentoReceiver]
     * via PendingIntent. true = sessione consegnata al sistema, non "installato":
     * la conferma finale è nelle mani dell'utente.
     */
    private suspend fun installa(apk: File): Boolean = withContext(Dispatchers.IO) {
        val installer = context.packageManager.packageInstaller
        val parametri = PackageInstaller.SessionParams(
            PackageInstaller.SessionParams.MODE_FULL_INSTALL,
        )
        var sessionId = -1
        try {
            sessionId = installer.createSession(parametri)
            installer.openSession(sessionId).use { sessione ->
                apk.inputStream().use { ingresso ->
                    sessione.openWrite(NOME_APK, 0, apk.length()).use { uscita ->
                        ingresso.copyTo(uscita)
                        sessione.fsync(uscita)
                    }
                }
                sessione.commit(intentEsito(sessionId).intentSender)
            }
            true
        } catch (e: IOException) {
            if (sessionId >= 0) runCatching { installer.abandonSession(sessionId) }
            false
        } catch (e: SecurityException) {
            if (sessionId >= 0) runCatching { installer.abandonSession(sessionId) }
            false
        }
    }

    private fun intentEsito(sessionId: Int): PendingIntent {
        val intent = Intent(context, AggiornamentoReceiver::class.java)
            .setAction(AggiornamentoReceiver.AZIONE)
            .setPackage(context.packageName)
        // MUTABLE: il sistema riempie il PendingIntent con l'esito (EXTRA_STATUS,
        // l'intent di conferma). requestCode = sessionId per non collidere tra
        // sessioni diverse.
        val flag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        return PendingIntent.getBroadcast(context, sessionId, intent, flag)
    }

    private companion object {
        const val CARTELLA = "aggiornamenti"
        const val NOME_APK = "pactum-genitore.apk"
    }
}
