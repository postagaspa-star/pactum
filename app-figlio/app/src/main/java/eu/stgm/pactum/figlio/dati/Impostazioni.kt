package eu.stgm.pactum.figlio.dati

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.time.LocalDate

// Delegato a livello di file: una sola istanza di DataStore per processo.
private val Context.dataStore by preferencesDataStore(name = "impostazioni")

data class ConfigurazionePostino(val serverUrl: String, val token: String) {
    val completa: Boolean get() = serverUrl.isNotBlank() && token.isNotBlank()
}

/**
 * Ancora temporale: coppia (orologio a muro, elapsedRealtime) salvata a ogni
 * battito. OrologioReceiver la usa per distinguere una sincronizzazione
 * automatica (scarto piccolo) da un cambio d'ora manuale (scarto grande).
 */
data class AncoraTempo(val wallClock: Long, val elapsedRealtime: Long)

class Impostazioni(private val context: Context) {

    private object Chiavi {
        val SERVER_URL = stringPreferencesKey("server_url")
        val TOKEN = stringPreferencesKey("token")
        val ANCORA_WALL = longPreferencesKey("ancora_wall_clock")
        val ANCORA_ELAPSED = longPreferencesKey("ancora_elapsed_realtime")
        val ULTIMO_BATTITO_OK = longPreferencesKey("ultimo_battito_ok")
        val DRIFT_OROLOGIO = longPreferencesKey("drift_orologio_ms")

        // Tappa 5.
        val SFORAMENTI_SEGNALATI = stringSetPreferencesKey("sforamenti_segnalati")
        val NOTIFICHE_AVVISATE = stringSetPreferencesKey("notifiche_avvisate")

        // Tappa 6 — corazza.
        // Ultimo stato NOTO dei permessi (null = mai osservato): serve a
        // rilevare la REVOCA come transizione (concesso→revocato), non come
        // stato istantaneo, così l'evento manomissione nasce una volta sola.
        val ACCESSO_USO_NOTO = booleanPreferencesKey("accesso_uso_noto")
        val NOTIFICHE_NOTE = booleanPreferencesKey("notifiche_note")
        // L'ultimo versionCode per cui è già stato tentato l'auto-aggiornamento:
        // evita di riscaricare l'APK e ripresentare il dialogo a ogni giro.
        val VERSIONE_TENTATA = intPreferencesKey("versione_tentata")
    }

    val configurazione: Flow<ConfigurazionePostino> = context.dataStore.data.map { p ->
        ConfigurazionePostino(p[Chiavi.SERVER_URL] ?: "", p[Chiavi.TOKEN] ?: "")
    }

    suspend fun leggiConfigurazione(): ConfigurazionePostino = configurazione.first()

    suspend fun salvaConfigurazione(serverUrl: String, token: String) {
        context.dataStore.edit { p ->
            val urlNuovo = serverUrl.trim().trimEnd('/')
            val tokenNuovo = token.trim()
            // Server o token diversi = patto diverso: gli sforamenti già segnalati
            // e gli id delle notifiche già avvisate appartengono al patto vecchio e
            // soffocherebbero gli avvisi del nuovo (regole e id riciclati).
            if (p[Chiavi.SERVER_URL] != urlNuovo || p[Chiavi.TOKEN] != tokenNuovo) {
                p.remove(Chiavi.SFORAMENTI_SEGNALATI)
                p.remove(Chiavi.NOTIFICHE_AVVISATE)
                // Nuovo patto = nuova base dei permessi: senza azzerare, una revoca
                // già in corso verrebbe rimandata come manomissione al server nuovo.
                p.remove(Chiavi.ACCESSO_USO_NOTO)
                p.remove(Chiavi.NOTIFICHE_NOTE)
            }
            p[Chiavi.SERVER_URL] = urlNuovo
            p[Chiavi.TOKEN] = tokenNuovo
        }
    }

    suspend fun leggiAncoraTempo(): AncoraTempo? {
        val p = context.dataStore.data.first()
        val wall = p[Chiavi.ANCORA_WALL] ?: return null
        val elapsed = p[Chiavi.ANCORA_ELAPSED] ?: return null
        return AncoraTempo(wall, elapsed)
    }

    suspend fun salvaAncoraTempo(ancora: AncoraTempo) {
        context.dataStore.edit { p ->
            p[Chiavi.ANCORA_WALL] = ancora.wallClock
            p[Chiavi.ANCORA_ELAPSED] = ancora.elapsedRealtime
        }
    }

    /** Quando è stato consegnato l'ultimo battito (epoch ms), null = mai. */
    val ultimoBattitoConsegnato: Flow<Long?> =
        context.dataStore.data.map { p -> p[Chiavi.ULTIMO_BATTITO_OK] }

    /**
     * Da chiamare a ogni battito CONSEGNATO (worker, loop del servizio, prova
     * manuale): memorizza il quando e azzera il drift accumulato dell'orologio
     * — dal battito in poi è il server, con il suo orologio, a fare fede.
     */
    suspend fun registraBattitoConsegnato(ts: Long = System.currentTimeMillis()) {
        context.dataStore.edit { p ->
            p[Chiavi.ULTIMO_BATTITO_OK] = ts
            p.remove(Chiavi.DRIFT_OROLOGIO)
        }
    }

    /**
     * Drift dell'orologio accumulato dai cambi d'ora sotto soglia (ms, con
     * segno). Serve a OrologioReceiver contro i cambi "a fette di salame":
     * tanti passi da poco che singolarmente non farebbero mai scattare nulla.
     */
    suspend fun leggiDriftOrologio(): Long =
        context.dataStore.data.first()[Chiavi.DRIFT_OROLOGIO] ?: 0L

    suspend fun salvaDriftOrologio(driftMs: Long) {
        context.dataStore.edit { p -> p[Chiavi.DRIFT_OROLOGIO] = driftMs }
    }

    suspend fun azzeraDriftOrologio() {
        context.dataStore.edit { p -> p.remove(Chiavi.DRIFT_OROLOGIO) }
    }

    // --- Dedup degli sforamenti (tappa 5) -----------------------------------
    // Chiave = "regolaId:giorno" (giorno = data locale del telefono): il
    // contratto vuole al massimo UNO sforamento per regola per giorno. Le voci
    // più vecchie di una settimana si potano a ogni scrittura: la memoria serve
    // solo per il giorno corrente e i confini di mezzanotte, non per sempre.

    private fun chiaveSforamento(regolaId: Long, giorno: String) = "$regolaId:$giorno"

    suspend fun sforamentoGiaSegnalato(regolaId: Long, giorno: String): Boolean =
        chiaveSforamento(regolaId, giorno) in
            (context.dataStore.data.first()[Chiavi.SFORAMENTI_SEGNALATI].orEmpty())

    suspend fun registraSforamentoSegnalato(regolaId: Long, giorno: String) {
        context.dataStore.edit { p ->
            val soglia = LocalDate.now().minusDays(GIORNI_MEMORIA_SFORAMENTI)
            val recenti = p[Chiavi.SFORAMENTI_SEGNALATI].orEmpty().filter { chiave ->
                val g = chiave.substringAfter(':', "")
                runCatching { LocalDate.parse(g) }.getOrNull()?.isBefore(soglia) != true
            }
            p[Chiavi.SFORAMENTI_SEGNALATI] = (recenti + chiaveSforamento(regolaId, giorno)).toSet()
        }
    }

    // --- Dedup delle notifiche del figlio già avvisate (proposte, verdetti) ---

    suspend fun leggiIdAvvisati(): Set<Long> =
        context.dataStore.data.first()[Chiavi.NOTIFICHE_AVVISATE]
            .orEmpty()
            .mapNotNull { it.toLongOrNull() }
            .toSet()

    suspend fun registraIdAvvisati(nuovi: Collection<Long>) {
        if (nuovi.isEmpty()) return
        context.dataStore.edit { p ->
            val unione = p[Chiavi.NOTIFICHE_AVVISATE].orEmpty()
                .mapNotNull { it.toLongOrNull() }
                .toSet() + nuovi
            // Gli id del server crescono sempre: si tiene solo la coda più recente.
            p[Chiavi.NOTIFICHE_AVVISATE] = unione
                .sortedDescending()
                .take(TETTO_ID_AVVISATI)
                .map { it.toString() }
                .toSet()
        }
    }

    // --- Stato noto dei permessi (tappa 6, rilevamento revoca) --------------
    // null = mai osservato: la prima osservazione fissa solo la base, senza
    // emettere manomissioni (un permesso già assente all'inizio non è una revoca).

    suspend fun leggiAccessoUsoNoto(): Boolean? =
        context.dataStore.data.first()[Chiavi.ACCESSO_USO_NOTO]

    suspend fun registraAccessoUsoNoto(concesso: Boolean) {
        context.dataStore.edit { p -> p[Chiavi.ACCESSO_USO_NOTO] = concesso }
    }

    suspend fun leggiNotificheNote(): Boolean? =
        context.dataStore.data.first()[Chiavi.NOTIFICHE_NOTE]

    suspend fun registraNotificheNote(attive: Boolean) {
        context.dataStore.edit { p -> p[Chiavi.NOTIFICHE_NOTE] = attive }
    }

    // --- Auto-aggiornamento (tappa 6) ---------------------------------------

    /** L'ultimo versionCode per cui l'installazione è già stata tentata (0 = nessuno). */
    suspend fun leggiVersioneTentata(): Int =
        context.dataStore.data.first()[Chiavi.VERSIONE_TENTATA] ?: 0

    suspend fun registraVersioneTentata(versioneCode: Int) {
        context.dataStore.edit { p -> p[Chiavi.VERSIONE_TENTATA] = versioneCode }
    }

    private companion object {
        const val GIORNI_MEMORIA_SFORAMENTI = 7L
        const val TETTO_ID_AVVISATI = 500
    }
}
