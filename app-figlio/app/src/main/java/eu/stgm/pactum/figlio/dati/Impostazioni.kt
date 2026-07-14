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
}
