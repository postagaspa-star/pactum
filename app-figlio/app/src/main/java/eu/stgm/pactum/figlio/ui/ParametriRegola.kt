package eu.stgm.pactum.figlio.ui

import eu.stgm.pactum.figlio.dati.TipiRegola
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

// (0.10) Logica pura del modulo di una regola: dai campi scritti ai parametri
// del contratto (contratto-api.md, Regole). Lo stesso modulo serve a creare, a
// modificare e a proporre al genitore: una proposta manda proprio i parametri
// che la modifica salverebbe. Niente Android: si prova con JUnit semplice.

object ParametriRegola {

    /** "HH:MM", da 00:00 a 23:59: il formato che il server accetta. */
    val ORA_REGEX = Regex("^([01]\\d|2[0-3]):[0-5]\\d$")

    /** I giorni della settimana del contratto, nell'ordine in cui si scrivono. */
    val GIORNI = listOf("lun", "mar", "mer", "gio", "ven", "sab", "dom")

    /** Un limite al giorno non supera il giorno: lo stesso tetto del server (da 1 a 1440). */
    const val MINUTI_MASSIMI = 1440

    /**
     * I minuti scritti sono più di un giorno: il campo lo dice ("Al massimo 24
     * ore al giorno"), invece di lasciare solo il pulsante spento senza un perché.
     */
    fun minutiOltreIlGiorno(minuti: String): Boolean {
        val testo = minuti.trim()
        if (testo.isEmpty() || !testo.all { it in '0'..'9' }) return false
        // Tante cifre da non stare in un Long sono comunque più di un giorno.
        return (testo.toLongOrNull() ?: Long.MAX_VALUE) > MINUTI_MASSIMI
    }

    /**
     * I parametri pronti da mandare per una regola di [tipo], null se qualche
     * campo non va: il pulsante resta spento (un 422 evitabile è un errore in
     * meno da spiegare). Gli spazi ai bordi si tolgono; i giorni vanno
     * nell'ordine della settimana, qualunque sia l'ordine in cui si toccano.
     */
    fun daCampi(
        tipo: String,
        app: String = "",
        minuti: String = "",
        dalle: String = "",
        alle: String = "",
        giorni: Set<String> = emptySet(),
        descrizione: String = "",
        arbitro: String = "",
        frequenza: String = "",
    ): JsonObject? = when (tipo) {
        TipiRegola.LIMITE_TEMPO -> {
            val n = minuti.trim().toIntOrNull()
            if (app.isBlank() || n == null || n <= 0 || n > MINUTI_MASSIMI) {
                null
            } else {
                buildJsonObject {
                    put("app_o_categoria", app.trim())
                    put("minuti_al_giorno", n)
                }
            }
        }
        TipiRegola.FASCIA_ORARIA -> {
            val scelti = GIORNI.filter { it in giorni }
            if (!ORA_REGEX.matches(dalle.trim()) || !ORA_REGEX.matches(alle.trim()) || scelti.isEmpty()) {
                null
            } else {
                buildJsonObject {
                    put("dalle", dalle.trim())
                    put("alle", alle.trim())
                    putJsonArray("giorni") { scelti.forEach { add(it) } }
                }
            }
        }
        TipiRegola.VITA_REALE -> {
            if (descrizione.isBlank() || arbitro.isBlank() || frequenza.isBlank()) {
                null
            } else {
                buildJsonObject {
                    put("descrizione", descrizione.trim())
                    put("arbitro_nome", arbitro.trim())
                    put("frequenza", frequenza.trim())
                }
            }
        }
        else -> null
    }
}
