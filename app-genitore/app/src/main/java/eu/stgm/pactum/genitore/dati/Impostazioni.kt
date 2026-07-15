package eu.stgm.pactum.genitore.dati

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

// Delegato a livello di file: una sola istanza di DataStore per processo.
private val Context.dataStore by preferencesDataStore(name = "impostazioni")

data class ConfigurazionePostino(val serverUrl: String, val token: String) {
    val completa: Boolean get() = serverUrl.isNotBlank() && token.isNotBlank()
}

/** L'ultimo stato di silenzio osservato dalla vedetta e il battito su cui si basava. */
data class SilenzioNoto(val silente: Boolean, val ultimoBattito: String?)

/** Il digest giornaliero: se è attivo e a che ora (0-23) va mandato. */
data class ConfigDigest(val attivo: Boolean, val ora: Int)

class Impostazioni(private val context: Context) {

    private object Chiavi {
        val SERVER_URL = stringPreferencesKey("server_url")
        val TOKEN = stringPreferencesKey("token")
        val ULTIMA_VERIFICA_OK = longPreferencesKey("ultima_verifica_ok")
        val NOTIFICHE_AVVISATE = stringSetPreferencesKey("notifiche_avvisate")
        val RICHIESTA_NOTIFICHE_FATTA = booleanPreferencesKey("richiesta_notifiche_fatta")
        val SILENZIO_NOTO = booleanPreferencesKey("silenzio_noto")
        val SILENZIO_BATTITO = stringPreferencesKey("silenzio_battito")

        // L'ultimo versionCode per cui è già stato tentato l'auto-aggiornamento:
        // evita di riscaricare l'APK e ripresentare il dialogo a ogni giro.
        val VERSIONE_TENTATA = intPreferencesKey("versione_tentata")

        // Digest giornaliero: attivo (default sì), ora scelta (default 21) e
        // l'ultimo giorno locale in cui è stato mandato (dedup: uno al giorno).
        val DIGEST_ATTIVO = booleanPreferencesKey("digest_attivo")
        val DIGEST_ORA = intPreferencesKey("digest_ora")
        val DIGEST_ULTIMO_GIORNO = stringPreferencesKey("digest_ultimo_giorno")
    }

    val configurazione: Flow<ConfigurazionePostino> = context.dataStore.data.map { p ->
        ConfigurazionePostino(p[Chiavi.SERVER_URL] ?: "", p[Chiavi.TOKEN] ?: "")
    }

    suspend fun leggiConfigurazione(): ConfigurazionePostino = configurazione.first()

    suspend fun salvaConfigurazione(serverUrl: String, token: String) {
        context.dataStore.edit { p ->
            val urlNuovo = serverUrl.trim().trimEnd('/')
            val tokenNuovo = token.trim()
            // Server o token diversi = patto diverso: gli id già avvisati e lo
            // stato di silenzio osservato appartengono al server vecchio, e
            // tenerli soffocherebbe gli avvisi del server nuovo (id riciclati).
            if (p[Chiavi.SERVER_URL] != urlNuovo || p[Chiavi.TOKEN] != tokenNuovo) {
                p.remove(Chiavi.NOTIFICHE_AVVISATE)
                p.remove(Chiavi.SILENZIO_NOTO)
                p.remove(Chiavi.SILENZIO_BATTITO)
            }
            p[Chiavi.SERVER_URL] = urlNuovo
            p[Chiavi.TOKEN] = tokenNuovo
        }
    }

    /** Quando il server ha risposto l'ultima volta (epoch ms), null = mai. */
    val ultimaVerificaRiuscita: Flow<Long?> =
        context.dataStore.data.map { p -> p[Chiavi.ULTIMA_VERIFICA_OK] }

    /** Da chiamare a ogni risposta buona del server (vedetta, finestra, prova manuale). */
    suspend fun registraVerificaRiuscita(ts: Long = System.currentTimeMillis()) {
        context.dataStore.edit { p -> p[Chiavi.ULTIMA_VERIFICA_OK] = ts }
    }

    /** Id delle notifiche del patto già avvisate con una notifica di sistema. */
    suspend fun leggiIdAvvisati(): Set<Long> =
        context.dataStore.data.first()[Chiavi.NOTIFICHE_AVVISATE]
            .orEmpty()
            .mapNotNull { it.toLongOrNull() }
            .toSet()

    /**
     * Aggiunge gli id appena avvisati. L'insieme è limitato ai più RECENTI
     * (gli id del server crescono sempre): senza tetto crescerebbe per sempre,
     * e i vecchi id non servono più — le notifiche lette spariscono comunque
     * da GET /api/notifiche.
     */
    suspend fun registraIdAvvisati(nuovi: Collection<Long>) {
        if (nuovi.isEmpty()) return
        context.dataStore.edit { p ->
            val unione = p[Chiavi.NOTIFICHE_AVVISATE].orEmpty()
                .mapNotNull { it.toLongOrNull() }
                .toSet() + nuovi
            p[Chiavi.NOTIFICHE_AVVISATE] = unione
                .sortedDescending()
                .take(TETTO_ID_AVVISATI)
                .map { it.toString() }
                .toSet()
        }
    }

    /** L'ultimo stato di silenzio osservato dalla vedetta, null = mai osservato. */
    suspend fun leggiSilenzioNoto(): SilenzioNoto? {
        val p = context.dataStore.data.first()
        val silente = p[Chiavi.SILENZIO_NOTO] ?: return null
        return SilenzioNoto(silente, p[Chiavi.SILENZIO_BATTITO])
    }

    /** Registra lo stato di silenzio appena osservato (dedup degli avvisi). */
    suspend fun registraSilenzioNoto(silente: Boolean, ultimoBattito: String?) {
        context.dataStore.edit { p ->
            p[Chiavi.SILENZIO_NOTO] = silente
            if (ultimoBattito == null) {
                p.remove(Chiavi.SILENZIO_BATTITO)
            } else {
                p[Chiavi.SILENZIO_BATTITO] = ultimoBattito
            }
        }
    }

    // --- Auto-aggiornamento (tappa 6) ----------------------------------------

    /** L'ultimo versionCode per cui l'installazione è già stata tentata (0 = nessuno). */
    suspend fun leggiVersioneTentata(): Int =
        context.dataStore.data.first()[Chiavi.VERSIONE_TENTATA] ?: 0

    suspend fun registraVersioneTentata(versioneCode: Int) {
        context.dataStore.edit { p -> p[Chiavi.VERSIONE_TENTATA] = versioneCode }
    }

    // --- Digest giornaliero ---------------------------------------------------

    val configDigest: Flow<ConfigDigest> = context.dataStore.data.map { p ->
        ConfigDigest(
            attivo = p[Chiavi.DIGEST_ATTIVO] ?: true,
            ora = p[Chiavi.DIGEST_ORA] ?: ORA_DIGEST_DEFAULT,
        )
    }

    suspend fun leggiConfigDigest(): ConfigDigest = configDigest.first()

    suspend fun salvaConfigDigest(attivo: Boolean, ora: Int) {
        context.dataStore.edit { p ->
            p[Chiavi.DIGEST_ATTIVO] = attivo
            p[Chiavi.DIGEST_ORA] = ora.coerceIn(0, 23)
        }
    }

    /** L'ultimo giorno locale (ISO yyyy-MM-dd) col digest già mandato, null = mai. */
    suspend fun leggiDigestUltimoGiorno(): String? =
        context.dataStore.data.first()[Chiavi.DIGEST_ULTIMO_GIORNO]

    suspend fun registraDigestInviato(giorno: String) {
        context.dataStore.edit { p -> p[Chiavi.DIGEST_ULTIMO_GIORNO] = giorno }
    }

    /** true = la richiesta del permesso notifiche è già stata mostrata una volta. */
    val richiestaNotificheFatta: Flow<Boolean> =
        context.dataStore.data.map { p -> p[Chiavi.RICHIESTA_NOTIFICHE_FATTA] ?: false }

    suspend fun registraRichiestaNotificheFatta() {
        context.dataStore.edit { p -> p[Chiavi.RICHIESTA_NOTIFICHE_FATTA] = true }
    }

    private companion object {
        const val TETTO_ID_AVVISATI = 500
        const val ORA_DIGEST_DEFAULT = 21
    }
}
