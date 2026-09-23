package eu.stgm.pactum.figlio.dati

import android.content.Context
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import eu.stgm.pactum.design.GiornoPatto
import eu.stgm.pactum.figlio.giornata.Serie
import eu.stgm.pactum.figlio.giornata.SerieSalvata
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.security.MessageDigest
import java.time.LocalDate
import java.time.LocalTime

// Delegato a livello di file: una sola istanza di DataStore per processo.
private val Context.dataStore by preferencesDataStore(name = "impostazioni")

data class ConfigurazionePostino(val serverUrl: String, val token: String) {
    val completa: Boolean get() = serverUrl.isNotBlank() && token.isNotBlank()

    /**
     * Un'impronta del collegamento (indirizzo + codice), mai il codice in
     * chiaro: serve a riconoscere una copia del patto letta col collegamento
     * di prima (PattoLocale.salva).
     */
    val impronta: String
        get() = MessageDigest.getInstance("SHA-256")
            .digest("$serverUrl\n$token".toByteArray(Charsets.UTF_8))
            .take(8)
            .joinToString("") { "%02x".format(it) }
}

/**
 * (v3) Chi è questo telefono per il patto: il dispositivo e il figlio. Arriva
 * dall'abbinamento e poi da ogni GET /api/patto (il genitore può rinominare).
 * Vuota = collegato col vecchio codice lungo a un server che non lo dice.
 */
data class Identita(val dispositivo: Dispositivo? = null, val figlio: Figlio? = null)

/**
 * Ancora temporale: coppia (orologio a muro, elapsedRealtime) salvata a ogni
 * battito. OrologioReceiver la usa per distinguere una sincronizzazione
 * automatica (scarto piccolo) da un cambio d'ora manuale (scarto grande).
 */
data class AncoraTempo(val wallClock: Long, val elapsedRealtime: Long)

/** La chiusura della sera: accesa o no, e a che ora (minuti dalla mezzanotte). */
data class ConfigSerale(val attiva: Boolean, val minuti: Int) {
    val ora: LocalTime get() = LocalTime.of(minuti / 60, minuti % 60)

    companion object {
        /** 21:30: dopo cena, prima che la serata finisca. */
        const val MINUTI_PREDEFINITI = 21 * 60 + 30
    }
}

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

        // v2.3 — osservazione dei siti visitati.
        // RICHIESTA: il figlio l'ha accesa nell'app (la sua decisione, che
        // sopravvive ai riavvii). NOTA: l'ultimo stato osservato di
        // "richiesta + consenso VPN vivo", per rilevare l'interruzione come
        // transizione e non come stato istantaneo.
        val SITI_RICHIESTA = booleanPreferencesKey("siti_osservazione_richiesta")
        val SITI_NOTA = booleanPreferencesKey("siti_osservazione_nota")

        // v2.4 — redesign. Serie e record vivono SOLO qui: non partono mai.
        val SERIE_FINE = stringPreferencesKey("serie_fine")
        val SERIE_LUNGHEZZA = intPreferencesKey("serie_lunghezza")
        val RECORD_SERIE = intPreferencesKey("record_serie")

        // La chiusura della sera: attiva, a che ora (minuti dalla mezzanotte) e
        // l'ultimo giorno in cui è partita (una volta sola, anche dopo un riavvio).
        val SERALE_ATTIVA = booleanPreferencesKey("serale_attiva")
        val SERALE_MINUTI = intPreferencesKey("serale_minuti")
        val SERALE_ULTIMO_GIORNO = stringPreferencesKey("serale_ultimo_giorno")

        // "Cosa vede tuo padre" mostrato almeno una volta dopo i permessi. La
        // chiave è per versione del testo: quando cambia quello che il genitore
        // vede (v3: i dispositivi, il computer), lo si rilegge una volta.
        val COSA_VEDE_VISTA = booleanPreferencesKey("cosa_vede_vista_v3")

        // v3 — chi è questo telefono per il patto (Identita).
        val DISPOSITIVO_ID = longPreferencesKey("dispositivo_id")
        val DISPOSITIVO_NOME = stringPreferencesKey("dispositivo_nome")
        val DISPOSITIVO_TIPO = stringPreferencesKey("dispositivo_tipo")
        val FIGLIO_ID = longPreferencesKey("figlio_id")
        val FIGLIO_NOME = stringPreferencesKey("figlio_nome")
    }

    val configurazione: Flow<ConfigurazionePostino> = context.dataStore.data.map { p ->
        ConfigurazionePostino(p[Chiavi.SERVER_URL] ?: "", p[Chiavi.TOKEN] ?: "")
    }

    suspend fun leggiConfigurazione(): ConfigurazionePostino = configurazione.first()

    /**
     * Il collegamento al patto: indirizzo, token e (v3) chi è questo telefono.
     * Lo chiama solo Collegamento, dentro PattoLocale.cambiaCollegamento.
     *
     * [stessoDispositivo] = la storia di questo dispositivo continua (codice
     * nuovo per lo stesso dispositivo): cambia solo il token. Altrimenti è un
     * patto diverso e se ne va tutto quello che apparteneva al vecchio; la
     * serie di giorni di fila resta solo se il figlio è lo stesso
     * ([stessoFiglio]), perché è calcolata dalla SUA striscia.
     */
    suspend fun salvaCollegamento(
        serverUrl: String,
        token: String,
        identita: Identita?,
        stessoDispositivo: Boolean,
        stessoFiglio: Boolean,
    ) {
        context.dataStore.edit { p ->
            if (!stessoDispositivo) {
                // Gli sforamenti già segnalati e gli id delle notifiche già avvisate
                // appartengono al patto vecchio e soffocherebbero gli avvisi del
                // nuovo (regole e id riciclati).
                p.remove(Chiavi.SFORAMENTI_SEGNALATI)
                p.remove(Chiavi.NOTIFICHE_AVVISATE)
                // Nuovo patto = nuova base dei permessi: senza azzerare, una revoca
                // già in corso verrebbe rimandata come manomissione al server nuovo.
                p.remove(Chiavi.ACCESSO_USO_NOTO)
                p.remove(Chiavi.NOTIFICHE_NOTE)
                // Idem per l'osservazione dei siti (la SCELTA del figlio resta:
                // è un consenso dato al telefono, non al server).
                p.remove(Chiavi.SITI_NOTA)
                if (!stessoFiglio) {
                    // La serie in corso è di un altro figlio. Il record no: non scende mai.
                    p.remove(Chiavi.SERIE_FINE)
                    p.remove(Chiavi.SERIE_LUNGHEZZA)
                }
                // Chi era questo telefono non vale più: lo dice il nuovo collegamento
                // o, col codice lungo, il prossimo patto letto.
                rimuoviIdentita(p)
            }
            identita?.let { scriviIdentita(p, it.dispositivo, it.figlio) }
            p[Chiavi.SERVER_URL] = serverUrl.trim().trimEnd('/')
            p[Chiavi.TOKEN] = token.trim()
        }
    }

    // --- Chi è questo telefono (v3) -----------------------------------------

    val identita: Flow<Identita> = context.dataStore.data.map { p ->
        val dispositivo = p[Chiavi.DISPOSITIVO_ID]?.let { id ->
            Dispositivo(
                id = id,
                nome = p[Chiavi.DISPOSITIVO_NOME] ?: "",
                tipo = p[Chiavi.DISPOSITIVO_TIPO] ?: "",
            )
        }
        val figlio = p[Chiavi.FIGLIO_ID]?.let { id -> Figlio(id = id, nome = p[Chiavi.FIGLIO_NOME] ?: "") }
        Identita(dispositivo, figlio)
    }.distinctUntilChanged()

    suspend fun leggiIdentita(): Identita = identita.first()

    /**
     * Dal patto appena letto (il genitore può aver rinominato figlio o
     * dispositivo). Scrive solo se cambia qualcosa: gira a ogni sincronizzazione.
     */
    suspend fun aggiornaIdentita(dispositivo: Dispositivo?, figlio: Figlio?) {
        if (dispositivo == null && figlio == null) return
        val attuale = leggiIdentita()
        // Solo id, nome e tipo: la striscia non fa parte di "chi è".
        val nuovo = dispositivo?.takeIf { it.id > 0 }
            ?.let { Dispositivo(id = it.id, nome = it.nome, tipo = it.tipo) }
        val nuovoFiglio = figlio?.takeIf { it.id > 0 }
        val dispositivoUguale = nuovo == null || attuale.dispositivo == nuovo
        val figlioUguale = nuovoFiglio == null || attuale.figlio == nuovoFiglio
        if (dispositivoUguale && figlioUguale) return
        context.dataStore.edit { p -> scriviIdentita(p, nuovo, nuovoFiglio) }
    }

    private fun scriviIdentita(p: MutablePreferences, dispositivo: Dispositivo?, figlio: Figlio?) {
        dispositivo?.takeIf { it.id > 0 }?.let {
            p[Chiavi.DISPOSITIVO_ID] = it.id
            p[Chiavi.DISPOSITIVO_NOME] = it.nome
            p[Chiavi.DISPOSITIVO_TIPO] = it.tipo
        }
        figlio?.takeIf { it.id > 0 }?.let {
            p[Chiavi.FIGLIO_ID] = it.id
            p[Chiavi.FIGLIO_NOME] = it.nome
        }
    }

    private fun rimuoviIdentita(p: MutablePreferences) {
        p.remove(Chiavi.DISPOSITIVO_ID)
        p.remove(Chiavi.DISPOSITIVO_NOME)
        p.remove(Chiavi.DISPOSITIVO_TIPO)
        p.remove(Chiavi.FIGLIO_ID)
        p.remove(Chiavi.FIGLIO_NOME)
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

    // --- Osservazione dei siti visitati (v2.3) ------------------------------
    // La scelta del figlio (l'ha accesa lui) e l'ultimo stato noto
    // dell'osservazione viva, per rilevarne l'interruzione come transizione.

    val osservazioneSitiRichiesta: Flow<Boolean> =
        context.dataStore.data.map { p -> p[Chiavi.SITI_RICHIESTA] ?: false }

    suspend fun leggiOsservazioneSitiRichiesta(): Boolean =
        context.dataStore.data.first()[Chiavi.SITI_RICHIESTA] ?: false

    suspend fun registraOsservazioneSitiRichiesta(richiesta: Boolean) {
        context.dataStore.edit { p -> p[Chiavi.SITI_RICHIESTA] = richiesta }
    }

    suspend fun leggiOsservazioneSitiNota(): Boolean? =
        context.dataStore.data.first()[Chiavi.SITI_NOTA]

    suspend fun registraOsservazioneSitiNota(attiva: Boolean) {
        context.dataStore.edit { p -> p[Chiavi.SITI_NOTA] = attiva }
    }

    // --- Serie e record (redesign C2/C6) ------------------------------------
    // Calcolati dalla striscia del server, ricordati qui. Mai al server.

    suspend fun leggiSerieSalvata(): SerieSalvata? {
        val p = context.dataStore.data.first()
        val fine = p[Chiavi.SERIE_FINE] ?: return null
        val lunghezza = p[Chiavi.SERIE_LUNGHEZZA] ?: return null
        return SerieSalvata(fine, lunghezza)
    }

    /**
     * Aggiorna serie e record con una striscia appena arrivata e li restituisce
     * (serie, record). Una sola scrittura: il record non può restare indietro
     * rispetto alla serie, e non scende mai.
     */
    suspend fun aggiornaSerie(giorni: List<GiornoPatto>): Pair<Int, Int> {
        var esito = 0 to 0
        context.dataStore.edit { p ->
            val salvata = p[Chiavi.SERIE_FINE]?.let { fine ->
                p[Chiavi.SERIE_LUNGHEZZA]?.let { SerieSalvata(fine, it) }
            }
            // Due cose diverse: la serie da mostrare adesso e la memoria da
            // tenere. Un giorno grigio dopo la fine ricordata azzera la prima
            // ma non la seconda (Serie.memoria): quando diventa verde, la
            // serie lunga torna com'era.
            val mostrata = if (giorni.isEmpty()) salvata else Serie.calcola(giorni, salvata)
            val memoria = if (giorni.isEmpty()) salvata else Serie.memoria(giorni, salvata)
            if (memoria == null) {
                p.remove(Chiavi.SERIE_FINE)
                p.remove(Chiavi.SERIE_LUNGHEZZA)
            } else {
                p[Chiavi.SERIE_FINE] = memoria.fine
                p[Chiavi.SERIE_LUNGHEZZA] = memoria.lunghezza
            }
            val serie = mostrata?.lunghezza ?: 0
            val record = Serie.record(p[Chiavi.RECORD_SERIE] ?: 0, serie)
            p[Chiavi.RECORD_SERIE] = record
            esito = serie to record
        }
        return esito
    }

    // --- Chiusura della sera (redesign C5) ----------------------------------

    // distinctUntilChanged: il DataStore riemette a ogni scrittura (anche a ogni
    // battito); chi ascolta questa configurazione deve svegliarsi solo se cambia.
    val chiusuraSerale: Flow<ConfigSerale> = context.dataStore.data.map { p ->
        ConfigSerale(
            attiva = p[Chiavi.SERALE_ATTIVA] ?: true,
            minuti = p[Chiavi.SERALE_MINUTI] ?: ConfigSerale.MINUTI_PREDEFINITI,
        )
    }.distinctUntilChanged()

    suspend fun leggiChiusuraSerale(): ConfigSerale = chiusuraSerale.first()

    suspend fun salvaChiusuraSerale(attiva: Boolean, minuti: Int) {
        context.dataStore.edit { p ->
            p[Chiavi.SERALE_ATTIVA] = attiva
            p[Chiavi.SERALE_MINUTI] = minuti.coerceIn(0, 24 * 60 - 1)
        }
    }

    suspend fun leggiUltimaChiusura(): String? =
        context.dataStore.data.first()[Chiavi.SERALE_ULTIMO_GIORNO]

    suspend fun registraUltimaChiusura(giorno: String) {
        context.dataStore.edit { p -> p[Chiavi.SERALE_ULTIMO_GIORNO] = giorno }
    }

    // --- "Cosa vede tuo padre" (redesign C6) --------------------------------

    val cosaVedeVista: Flow<Boolean> =
        context.dataStore.data.map { p -> p[Chiavi.COSA_VEDE_VISTA] ?: false }.distinctUntilChanged()

    suspend fun registraCosaVedeVista() {
        context.dataStore.edit { p -> p[Chiavi.COSA_VEDE_VISTA] = true }
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
