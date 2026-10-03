package eu.stgm.pactum.figlio.faccende

import android.app.ActivityManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import eu.stgm.pactum.figlio.dati.CodaEventi
import eu.stgm.pactum.figlio.dati.Evento
import eu.stgm.pactum.figlio.dati.TipiEvento
import eu.stgm.pactum.figlio.sync.ConsegnaEventi
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.math.abs

/**
 * (0.13) "Forza arresto" durante il blocco. Le Impostazioni restano libere
 * durante il blocco (decisione di Andrea), e da lì si può fermare Pactum: la
 * barriera sparisce e il server vede solo silenzio. Quando Pactum riparte, se
 * era stato fermato a mano (Android 11 e dopo lo dice: ApplicationExitInfo,
 * REASON_USER_REQUESTED o REASON_USER_STOPPED) mentre il telefono era
 * bloccato, manda un evento `manomissione` con `sotto_tipo:
 * "fermato_durante_blocco"`, da quando (`dal`) a quando (`al`) e i minuti.
 *
 * Mai dopo un riavvio del telefono (solo le uscite di questa accensione) e
 * mai per un aggiornamento di Pactum (un'uscita vicina all'ora
 * dell'aggiornamento). Una volta sola per uscita.
 */
object FermatoDuranteBlocco {

    /** ApplicationExitInfo.REASON_USER_REQUESTED: "Forza arresto". */
    const val MOTIVO_FORZA_ARRESTO = 10

    /** ApplicationExitInfo.REASON_USER_STOPPED. */
    const val MOTIVO_FERMATA = 11

    /** Un'uscita così vicina all'aggiornamento di Pactum è l'aggiornamento. */
    const val MARGINE_AGGIORNAMENTO_MS = 2L * 60 * 1000

    private val mutex = Mutex()

    data class Uscita(val quando: Long, val motivo: Int)

    /**
     * L'uscita da segnalare (logica pura): fermata a mano, dopo
     * l'accensione ([accensioneIl]), dopo l'ultima già guardata ([giaVista]),
     * non per un aggiornamento ([aggiornataIl]), con il telefono bloccato in
     * quel momento ([bloccatoIl]). La più recente.
     */
    fun daSegnalare(
        uscite: List<Uscita>,
        accensioneIl: Long,
        aggiornataIl: Long?,
        giaVista: Long?,
        bloccatoIl: (Long) -> Boolean,
    ): Uscita? = uscite
        .filter { it.motivo == MOTIVO_FORZA_ARRESTO || it.motivo == MOTIVO_FERMATA }
        .filter { it.quando >= accensioneIl }
        .filter { giaVista == null || it.quando > giaVista }
        .filter { aggiornataIl == null || abs(it.quando - aggiornataIl) > MARGINE_AGGIORNAMENTO_MS }
        .filter { bloccatoIl(it.quando) }
        .maxByOrNull { it.quando }

    /** I dettagli dell'evento (logica pura). */
    fun dettagli(uscita: Uscita, adesso: Long) = buildJsonObject {
        put("sotto_tipo", "fermato_durante_blocco")
        put("dal", uscita.quando)
        put("al", adesso)
        put("minuti", ((adesso - uscita.quando).coerceAtLeast(0L) + 59_999) / 60_000)
    }

    /** All'avvio del processo (PactumApp): guarda le uscite di prima. Solo da Android 11. */
    suspend fun controlla(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        val app = context.applicationContext
        mutex.withLock {
            val am = app.getSystemService(ActivityManager::class.java) ?: return
            val uscite = try {
                am.getHistoricalProcessExitReasons(app.packageName, 0, 10).map { Uscita(it.timestamp, it.reason) }
            } catch (e: Exception) {
                return
            }
            if (uscite.isEmpty()) return
            val adesso = System.currentTimeMillis()
            val accensioneIl = adesso - SystemClock.elapsedRealtime()
            val aggiornataIl = try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    app.packageManager.getPackageInfo(app.packageName, PackageManager.PackageInfoFlags.of(0)).lastUpdateTime
                } else {
                    @Suppress("DEPRECATION")
                    app.packageManager.getPackageInfo(app.packageName, 0).lastUpdateTime
                }
            } catch (e: Exception) {
                null
            }
            val memoria = ArchivioBlocco.leggi(app)
            val trovata = daSegnalare(uscite, accensioneIl, aggiornataIl, memoria.uscitaVista) { memoria.attivoAlMuro(it) }
            val ultima = uscite.maxOf { it.quando }
            ArchivioBlocco.modifica(app) { it.copy(uscitaVista = maxOf(ultima, it.uscitaVista ?: Long.MIN_VALUE)) }
            if (trovata != null) {
                CodaEventi(app).accoda(Evento(tipo = TipiEvento.MANOMISSIONE, tsDevice = adesso, dettagli = dettagli(trovata, adesso)))
                try {
                    ConsegnaEventi.subito(app)
                } catch (e: Exception) {
                    // resta in coda: la porta il worker
                }
            }
        }
    }
}
