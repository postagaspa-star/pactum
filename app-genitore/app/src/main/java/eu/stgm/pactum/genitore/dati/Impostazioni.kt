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
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

// Delegato a livello di file: una sola istanza di DataStore per processo.
private val Context.dataStore by preferencesDataStore(name = "impostazioni")

data class ConfigurazionePostino(val serverUrl: String, val token: String) {
    val completa: Boolean get() = serverUrl.isNotBlank() && token.isNotBlank()
}

/**
 * L'ultimo stato di silenzio osservato dalla vedetta per UN dispositivo e il
 * battito su cui si basava. [silente] = "allarme" (un computer spento non lo è);
 * [spento] = computer spento (v3), per non riavvisare lo stesso spegnimento.
 */
@Serializable
data class SilenzioNoto(
    val silente: Boolean,
    val ultimoBattito: String?,
    val spento: Boolean = false,
)

/** Il digest giornaliero: se è attivo e a che ora (0-23) va mandato. */
data class ConfigDigest(val attivo: Boolean, val ora: Int)

class Impostazioni(private val context: Context) {

    private object Chiavi {
        val SERVER_URL = stringPreferencesKey("server_url")
        val TOKEN = stringPreferencesKey("token")
        val ULTIMA_VERIFICA_OK = longPreferencesKey("ultima_verifica_ok")
        val NOTIFICHE_AVVISATE = stringSetPreferencesKey("notifiche_avvisate")
        val RICHIESTA_NOTIFICHE_FATTA = booleanPreferencesKey("richiesta_notifiche_fatta")

        // Il silenzio della 0.7 (un dispositivo solo): non si legge più, si
        // cancella solo quando cambia il server. Il posto nuovo è SILENZI_NOTI.
        val SILENZIO_NOTO = booleanPreferencesKey("silenzio_noto")
        val SILENZIO_BATTITO = stringPreferencesKey("silenzio_battito")

        // L'ultimo versionCode per cui è già stato tentato l'auto-aggiornamento:
        // evita di riscaricare l'APK e ripresentare il dialogo a ogni giro.
        val VERSIONE_TENTATA = intPreferencesKey("versione_tentata")

        // Digest giornaliero: attivo (default sì) e ora scelta (default 21). Il
        // giorno già mandato è per figlio, in DIGEST_INVIATI.
        val DIGEST_ATTIVO = booleanPreferencesKey("digest_attivo")
        val DIGEST_ORA = intPreferencesKey("digest_ora")

        // "Ho capito" sulla scheda "Come funziona Pactum": chiusa una volta, non torna.
        val INTRO_CHIUSA = booleanPreferencesKey("intro_chiusa")

        // (v3) Il figlio scelto in cima a Panoramica, Tempo e "Proposte e
        // conferme": resta ricordato. E l'ultima famiglia letta, così i nomi e la
        // scelta ci sono subito, anche senza rete.
        val FIGLIO_SCELTO = longPreferencesKey("figlio_scelto")
        val FAMIGLIA_LETTA = stringPreferencesKey("famiglia_letta")

        // (v3) Il silenzio osservato PER DISPOSITIVO (JSON id → SilenzioNoto) e
        // il digest già mandato PER FIGLIO ("figlio|giorno"). Sul server 0.7
        // valgono la chiave 0 e il figlio 0.
        val SILENZI_NOTI = stringPreferencesKey("silenzi_noti")
        val DIGEST_INVIATI = stringSetPreferencesKey("digest_inviati")
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
            // Lo stesso vale per la famiglia letta e il figlio scelto: su un
            // altro server gli id sono di altri figli.
            if (p[Chiavi.SERVER_URL] != urlNuovo || p[Chiavi.TOKEN] != tokenNuovo) {
                p.remove(Chiavi.NOTIFICHE_AVVISATE)
                p.remove(Chiavi.SILENZIO_NOTO)
                p.remove(Chiavi.SILENZIO_BATTITO)
                p.remove(Chiavi.SILENZI_NOTI)
                p.remove(Chiavi.FIGLIO_SCELTO)
                p.remove(Chiavi.FAMIGLIA_LETTA)
            }
            p[Chiavi.SERVER_URL] = urlNuovo
            p[Chiavi.TOKEN] = tokenNuovo
        }
    }

    // --- Famiglia (v3) -----------------------------------------------------------

    /** Il figlio scelto l'ultima volta, null = mai scelto (vale il primo). */
    val figlioScelto: Flow<Long?> = context.dataStore.data.map { p -> p[Chiavi.FIGLIO_SCELTO] }

    suspend fun salvaFiglioScelto(figlioId: Long) {
        context.dataStore.edit { p -> p[Chiavi.FIGLIO_SCELTO] = figlioId }
    }

    /** L'ultima famiglia letta dal server (JSON grezzo), null = mai letta. */
    suspend fun leggiFamigliaLetta(): String? = context.dataStore.data.first()[Chiavi.FAMIGLIA_LETTA]

    /** null = server 0.7: la famiglia non esiste, e non va ricordata. */
    suspend fun salvaFamigliaLetta(json: String?) {
        context.dataStore.edit { p ->
            if (json == null) p.remove(Chiavi.FAMIGLIA_LETTA) else p[Chiavi.FAMIGLIA_LETTA] = json
        }
    }

    /** Il silenzio osservato per ciascun dispositivo (chiave: id, 0 = server 0.7). */
    suspend fun leggiSilenziNoti(): Map<Long, SilenzioNoto> {
        val grezzo = context.dataStore.data.first()[Chiavi.SILENZI_NOTI] ?: return emptyMap()
        return try {
            jsonSilenzi.decodeFromString(MappaSilenzi.serializer(), grezzo)
                .perDispositivo
                .mapNotNull { (id, noto) -> id.toLongOrNull()?.let { it to noto } }
                .toMap()
        } catch (e: SerializationException) {
            emptyMap() // un JSON rovinato vale "mai osservato": si riparte dalla base, senza allarmi
        } catch (e: IllegalArgumentException) {
            emptyMap()
        }
    }

    suspend fun salvaSilenziNoti(silenzi: Map<Long, SilenzioNoto>) {
        val grezzo = jsonSilenzi.encodeToString(
            MappaSilenzi.serializer(),
            MappaSilenzi(silenzi.mapKeys { it.key.toString() }),
        )
        context.dataStore.edit { p -> p[Chiavi.SILENZI_NOTI] = grezzo }
    }

    /** true = il digest di [giorno] per [figlioId] è già partito (0 = server 0.7). */
    suspend fun digestGiaInviato(figlioId: Long, giorno: String): Boolean =
        "$figlioId|$giorno" in context.dataStore.data.first()[Chiavi.DIGEST_INVIATI].orEmpty()

    /** Registra il digest di oggi per il figlio, e dimentica i suoi giorni vecchi. */
    suspend fun registraDigestInviato(figlioId: Long, giorno: String) {
        context.dataStore.edit { p ->
            val altri = p[Chiavi.DIGEST_INVIATI].orEmpty()
                .filterNot { it.substringBefore('|') == figlioId.toString() }
            p[Chiavi.DIGEST_INVIATI] = (altri + "$figlioId|$giorno").toSet()
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

    /** true = la richiesta del permesso notifiche è già stata mostrata una volta. */
    val richiestaNotificheFatta: Flow<Boolean> =
        context.dataStore.data.map { p -> p[Chiavi.RICHIESTA_NOTIFICHE_FATTA] ?: false }

    suspend fun registraRichiestaNotificheFatta() {
        context.dataStore.edit { p -> p[Chiavi.RICHIESTA_NOTIFICHE_FATTA] = true }
    }

    /** true = il genitore ha già chiuso la scheda "Come funziona Pactum" con "Ho capito". */
    val introChiusa: Flow<Boolean> =
        context.dataStore.data.map { p -> p[Chiavi.INTRO_CHIUSA] ?: false }

    suspend fun registraIntroChiusa() {
        context.dataStore.edit { p -> p[Chiavi.INTRO_CHIUSA] = true }
    }

    private companion object {
        const val TETTO_ID_AVVISATI = 500
        const val ORA_DIGEST_DEFAULT = 21
        val jsonSilenzi = Json { ignoreUnknownKeys = true }
    }
}

/** Il contenitore JSON dei silenzi per dispositivo (chiavi stringa: JSON non ha chiavi numeriche). */
@Serializable
private data class MappaSilenzi(val perDispositivo: Map<String, SilenzioNoto> = emptyMap())
