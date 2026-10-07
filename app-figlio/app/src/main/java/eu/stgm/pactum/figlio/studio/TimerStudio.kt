package eu.stgm.pactum.figlio.studio

import eu.stgm.pactum.figlio.faccende.Istante
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * (0.18, contratto v4.0, «I tratti di attività») Un tratto del timer come lo
 * ricorda il telefono. La DURATA si misura con l'orologio che non si sposta
 * (elapsedRealtime: [monoInizio], [monoSalvato]); l'inizio e la fine sono
 * sull'ora del server agganciata a quell'orologio nella stessa accensione
 * (MemoriaBlocco.oraServer), oppure, dopo un riavvio senza rete,
 * sull'orologio del telefono più lo scarto misurato ([oraAgganciata] falso).
 *
 * Un riavvio azzera elapsedRealtime: il tratto in corso si chiude all'ultimo
 * punto salvato ([monoSalvato]) come `interrotto`, e conta fino a lì.
 */
@Serializable
data class TrattoLocale(
    /** Un uuid: la chiave di idempotenza del server. */
    val id: String,
    val tipo: String,
    val parola: String? = null,
    val faccendaId: Long? = null,
    /** L'accensione del telefono in cui è partito (Istante.avvio). */
    val avvio: Int? = null,
    val monoInizio: Long,
    /** L'ultimo punto salvato, sull'orologio che non si sposta. */
    val monoSalvato: Long,
    /** L'inizio, sull'ora del server (agganciata o con lo scarto). */
    val inizio: Long,
    val oraAgganciata: Boolean,
    /** La fine (ora del server), per un tratto finito o interrotto. */
    val fine: Long? = null,
    /** La durata misurata, per un tratto finito o interrotto. */
    val secondi: Long? = null,
    val esito: String = EsitiTratto.IN_CORSO,
    /** L'`in_corso` è arrivato al server. */
    val inCorsoMandato: Boolean = false,
    /** L'esito finale è arrivato al server, e quando (ora del server). */
    val consegnato: Boolean = false,
    val consegnatoIl: Long? = null,
    /** L'inizio dello Studio in cui gira (ora del server). */
    val studio: Long? = null,
) {
    val inCorso: Boolean get() = esito == EsitiTratto.IN_CORSO

    /** Lo stesso giro di accensione di [ora]: i due orologi che non si spostano si possono confrontare. */
    fun stessaAccensione(ora: Istante): Boolean {
        if (ora.monotono < monoSalvato) return false
        if (avvio == null || ora.avvio == null) return avvio == ora.avvio
        return avvio == ora.avvio
    }

    /** I secondi fin qui: per un tratto in corso, fino a adesso (o all'ultimo punto salvato dopo un riavvio). */
    fun secondiAdesso(ora: Istante): Long = when {
        !inCorso -> secondi ?: 0L
        stessaAccensione(ora) -> ((ora.monotono - monoInizio) / 1000).coerceAtLeast(0)
        else -> ((monoSalvato - monoInizio) / 1000).coerceAtLeast(0)
    }

    /** L'intervallo coperto sull'ora del server: `[fine − secondi, fine]`, o dall'inizio a adesso. */
    fun intervallo(ora: Istante): Pair<Long, Long> {
        if (!inCorso) {
            val f = fine ?: (inizio + (secondi ?: 0L) * 1000)
            return (f - (secondi ?: 0L) * 1000) to f
        }
        return inizio to inizio + secondiAdesso(ora) * 1000
    }

    /** Chiuso adesso ([fineServer] = l'ora del server adesso, [agganciata] = è quella vera). */
    fun chiuso(ora: Istante, fineServer: Long, agganciata: Boolean, esitoFinale: String = EsitiTratto.FINITO): TrattoLocale {
        if (!inCorso) return this
        val s = secondiAdesso(ora)
        return copy(esito = esitoFinale, secondi = s, fine = fineServer, oraAgganciata = agganciata, monoSalvato = maxOf(monoSalvato, ora.monotono))
    }

    /**
     * Chiuso da un riavvio: all'ultimo punto salvato, `interrotto`. La fine
     * è l'inizio più la durata misurata, con l'aggancio dell'inizio.
     */
    fun chiusoDalRiavvio(): TrattoLocale {
        if (!inCorso) return this
        val s = ((monoSalvato - monoInizio) / 1000).coerceAtLeast(0)
        return copy(esito = EsitiTratto.INTERROTTO, secondi = s, fine = inizio + s * 1000)
    }

    /** Chiuso dalla mezzanotte del patto ([mezzanotte], ora del server): `interrotto`, fino a lì. */
    fun chiusoAMezzanotte(ora: Istante, mezzanotte: Long): TrattoLocale {
        if (!inCorso) return this
        val s = minOf(secondiAdesso(ora), ((mezzanotte - inizio) / 1000).coerceAtLeast(0))
        return copy(esito = EsitiTratto.INTERROTTO, secondi = s, fine = inizio + s * 1000)
    }

    /** Il punto salvato adesso (solo nella stessa accensione). */
    fun salvato(ora: Istante): TrattoLocale =
        if (inCorso && stessaAccensione(ora)) copy(monoSalvato = ora.monotono) else this

    /** Da mandare: l'`in_corso` (se non è ancora partito) o l'esito finale. */
    val daMandare: Boolean get() = if (inCorso) !inCorsoMandato else !consegnato

    /**
     * Il tratto nella forma del contratto: `{ id, tipo, parola?,
     * faccenda_id?, inizio?, fine, ora_agganciata, secondi, esito }`. Per un
     * tratto in corso `fine` è null e c'è `inizio`; `secondi` da 1.
     */
    fun corpo(ora: Istante): JsonObject = buildJsonObject {
        put("id", id)
        put("tipo", tipo)
        parola?.let { put("parola", it) }
        if (tipo == TipiTratto.LAVORI_DI_CASA) faccendaId?.let { put("faccenda_id", it) }
        if (inCorso) {
            put("inizio", inizio)
            put("fine", JsonNull)
        } else {
            put("fine", fine ?: (inizio + (secondi ?: 0L) * 1000))
        }
        put("ora_agganciata", oraAgganciata)
        put("secondi", secondiAdesso(ora).coerceAtLeast(1))
        put("esito", esito)
    }

    /** Un'etichetta corta per il riepilogo: «compiti», «lavori di casa», o la parola. */
    val parolaOTipo: String get() = parola ?: tipo
}

/** (0.18) Il conto dei minuti: la durata dell'UNIONE degli intervalli, tagliata (logica pura). */
object ContoMinuti {

    /** Gli intervalli sovrapposti fusi in uno: un secondo non si conta due volte. */
    fun unione(intervalli: List<Pair<Long, Long>>): List<Pair<Long, Long>> {
        val ordinati = intervalli.filter { it.second > it.first }.sortedBy { it.first }
        val fusi = ArrayList<Pair<Long, Long>>(ordinati.size)
        for ((da, a) in ordinati) {
            val ultimo = fusi.lastOrNull()
            if (ultimo != null && da <= ultimo.second) {
                fusi[fusi.size - 1] = ultimo.first to maxOf(ultimo.second, a)
            } else {
                fusi += da to a
            }
        }
        return fusi
    }

    /** I millisecondi dell'unione di [intervalli] dentro `[da, a]`. */
    fun durata(intervalli: List<Pair<Long, Long>>, da: Long, a: Long): Long {
        if (a <= da) return 0
        return unione(intervalli.map { maxOf(it.first, da) to minOf(it.second, a) }).sumOf { it.second - it.first }
    }
}
