package eu.stgm.pactum.figlio.dati

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

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
    }

    val configurazione: Flow<ConfigurazionePostino> = context.dataStore.data.map { p ->
        ConfigurazionePostino(p[Chiavi.SERVER_URL] ?: "", p[Chiavi.TOKEN] ?: "")
    }

    suspend fun leggiConfigurazione(): ConfigurazionePostino = configurazione.first()

    suspend fun salvaConfigurazione(serverUrl: String, token: String) {
        context.dataStore.edit { p ->
            p[Chiavi.SERVER_URL] = serverUrl.trim().trimEnd('/')
            p[Chiavi.TOKEN] = token.trim()
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
}
