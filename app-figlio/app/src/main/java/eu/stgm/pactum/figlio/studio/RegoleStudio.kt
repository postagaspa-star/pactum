package eu.stgm.pactum.figlio.studio

import java.text.Normalizer
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeParseException

/**
 * (0.18, contratto v4.0) Le regole dello Studio che il telefono controlla da
 * solo (logica pura): i campi di una proposta, la parola di un tratto
 * «altro», la dichiarazione di fine, le partenze calcolate dalla
 * configurazione (oltre i 14 giorni senza rete) e la mezzanotte del patto.
 * Il server ricontrolla tutto: qui si evita solo di mandare una cosa che
 * verrebbe rifiutata.
 */
object RegoleStudio {

    const val MINUTI_MINIMI_MIN = 10
    const val MINUTI_MINIMI_MAX = 600

    /** «Studio chiudibile in giornata»: max(chiusura_minima, inizio + minimo) ≤ 23:30. */
    const val ULTIMA_CHIUSURA = 23 * 60 + 30

    const val APP_MASSIME = 200
    const val PAROLA_MASSIMA = 30
    const val DICHIARAZIONE_MINIMA = 10
    const val DICHIARAZIONE_MASSIMA = 1000

    /** Cosa non va in una proposta (null = va bene). */
    enum class ErroreProposta { GIORNI, INIZIO, CHIUSURA, CHIUSURA_PRIMA, MINUTI, ORARI_IMPOSSIBILI, TROPPE_APP }

    fun controllaProposta(c: ContenutoStudio): ErroreProposta? {
        if (c.giorni.isEmpty() || c.giorni.any { it !in GIORNI_STUDIO }) return ErroreProposta.GIORNI
        val inizio = Orari.minuti(c.inizio) ?: return ErroreProposta.INIZIO
        val chiusura = Orari.minuti(c.chiusuraMinima) ?: return ErroreProposta.CHIUSURA
        if (chiusura < inizio) return ErroreProposta.CHIUSURA_PRIMA
        if (c.minutiMinimi !in MINUTI_MINIMI_MIN..MINUTI_MINIMI_MAX) return ErroreProposta.MINUTI
        if (maxOf(chiusura, inizio + c.minutiMinimi) > ULTIMA_CHIUSURA) return ErroreProposta.ORARI_IMPOSSIBILI
        if (c.app.size > APP_MASSIME) return ErroreProposta.TROPPE_APP
        return null
    }

    /**
     * La parola di un tratto «altro»: 1–30 caratteri dopo aver tolto gli
     * spazi ai bordi, in forma NFC, senza caratteri invisibili o di controllo
     * (le regole dei nomi). Null se non va bene.
     */
    fun parola(scritta: String?): String? {
        val t = Normalizer.normalize(scritta.orEmpty(), Normalizer.Form.NFC).trim()
        if (t.isEmpty()) return null
        if (t.codePointCount(0, t.length) > PAROLA_MASSIMA) return null
        if (t.codePoints().anyMatch { invisibile(it) }) return null
        return t
    }

    /** Cosa non va in una dichiarazione. */
    enum class ErroreDichiarazione { CORTA, LUNGA, CARATTERI }

    /**
     * La dichiarazione come la tratta il server (le regole della nota di un
     * lavoro, schemas._testo_lungo): gli a capo uniformati, gli spazi ai
     * bordi tolti, la forma NFC. È il testo che parte.
     */
    private fun normale(scritta: String?): String =
        Normalizer.normalize(scritta.orEmpty().replace("\r\n", "\n").replace('\r', '\n').trim(), Normalizer.Form.NFC)

    /**
     * Perché la dichiarazione non va (null = va bene), con le stesse regole
     * del server: nessun carattere invisibile o di controllo tranne l'a capo
     * (un tab, lo ZWJ U+200D che sta in molte emoji composte come 🏃‍♂️, i
     * separatori di riga), poi da 10 a 1000 caratteri. Senza questo il
     * telefono chiudeva lo Studio e il server lo rifiutava (422), ogni volta.
     */
    fun erroreDichiarazione(scritta: String?): ErroreDichiarazione? {
        val t = normale(scritta)
        if (t.codePoints().anyMatch { it != '\n'.code && invisibile(it) }) return ErroreDichiarazione.CARATTERI
        val n = t.codePointCount(0, t.length)
        return when {
            n < DICHIARAZIONE_MINIMA -> ErroreDichiarazione.CORTA
            n > DICHIARAZIONE_MASSIMA -> ErroreDichiarazione.LUNGA
            else -> null
        }
    }

    /** La dichiarazione di fine pronta da mandare (NFC, 10–1000 caratteri, niente invisibili). Null se non va bene. */
    fun dichiarazione(scritta: String?): String? = if (erroreDichiarazione(scritta) == null) normale(scritta) else null

    /** Quanti caratteri conta la dichiarazione (per il contatore), come li conta il server. */
    fun caratteri(scritta: String): Int {
        val t = normale(scritta)
        return t.codePointCount(0, t.length)
    }

    private fun invisibile(cp: Int): Boolean = when (Character.getType(cp).toByte()) {
        Character.CONTROL, Character.FORMAT, Character.LINE_SEPARATOR, Character.PARAGRAPH_SEPARATOR -> true
        else -> false
    }

    // --- Il calendario dello Studio (fuso del patto) ------------------------

    /** Il giorno locale (fuso del patto) di un istante. */
    fun giorno(istante: Long, zona: ZoneId): LocalDate = Instant.ofEpochMilli(istante).atZone(zona).toLocalDate()

    /** La mezzanotte alla fine del giorno [giorno] (le 00:00 del giorno dopo), nel fuso del patto. */
    fun mezzanotteDopo(giorno: LocalDate, zona: ZoneId): Long = giorno.plusDays(1).atStartOfDay(zona).toInstant().toEpochMilli()

    fun mezzanotteDopo(giorno: String, zona: ZoneId): Long? = data(giorno)?.let { mezzanotteDopo(it, zona) }

    fun data(giorno: String?): LocalDate? = try {
        giorno?.trim()?.takeIf { it.isNotEmpty() }?.let { LocalDate.parse(it) }
    } catch (e: DateTimeParseException) {
        null
    }

    /**
     * L'istante in cui nel giorno [giorno] sono le [orario], nel fuso del
     * patto. Se l'ora non esiste (cambio dell'ora) vale la prima ora valida
     * dopo; se esiste due volte, la prima (contratto v4.0).
     */
    fun istante(giorno: LocalDate, orario: String, zona: ZoneId): Long? {
        val minuti = Orari.minuti(orario) ?: return null
        val locale = LocalDateTime.of(giorno, LocalTime.of(minuti / 60, minuti % 60))
        val regole = zona.rules
        val validi = regole.getValidOffsets(locale)
        return when {
            validi.isEmpty() -> regole.getTransition(locale)?.instant?.toEpochMilli()
                ?: locale.atZone(zona).toInstant().toEpochMilli()
            else -> locale.atOffset(validi.first()).toInstant().toEpochMilli()
        }
    }

    private val SETTIMANA = mapOf(
        DayOfWeek.MONDAY to "lun", DayOfWeek.TUESDAY to "mar", DayOfWeek.WEDNESDAY to "mer",
        DayOfWeek.THURSDAY to "gio", DayOfWeek.FRIDAY to "ven", DayOfWeek.SATURDAY to "sab", DayOfWeek.SUNDAY to "dom",
    )

    fun sigla(giorno: LocalDate): String = SETTIMANA.getValue(giorno.dayOfWeek)

    /**
     * La partenza del giorno [giorno] con gli orari [orari] (null se quel
     * giorno lo Studio non parte, o gli orari non valgono ancora: `orari_dal`).
     */
    fun partenza(giorno: LocalDate, orari: ContenutoStudio, zona: ZoneId): PartenzaStudio? {
        if (sigla(giorno) !in orari.giorni) return null
        val dal = data(orari.orariDal)
        if (dal != null && giorno.isBefore(dal)) return null
        val inizio = istante(giorno, orari.inizio, zona) ?: return null
        return PartenzaStudio(
            giorno = giorno.toString(),
            inizio = inizio,
            chiudibileDal = istante(giorno, orari.chiusuraMinima, zona),
            minutiMinimi = orari.minutiMinimi,
        )
    }

    /**
     * (0.18, contratto v4.0) «Oltre i 14 giorni senza rete il telefono
     * calcola le partenze dalla configurazione, nel fuso del patto»: le
     * partenze dal giorno dopo l'ultima dell'elenco del server fino a
     * [finoA] compreso. Solo se l'ultimo elenco ricevuto NON è vuoto: un
     * elenco vuoto vuol dire «nessuna partenza» (nessun telefono 0.18), e
     * allora il telefono non parte da solo nemmeno coi suoi conti.
     */
    fun partenzeOltre(elenco: List<PartenzaStudio>, orari: ContenutoStudio?, finoA: LocalDate, zona: ZoneId): List<PartenzaStudio> {
        if (elenco.isEmpty() || orari == null) return emptyList()
        val ultimo = elenco.mapNotNull { data(it.giorno) }.maxOrNull() ?: return emptyList()
        val risultato = mutableListOf<PartenzaStudio>()
        var d = ultimo.plusDays(1)
        var giri = 0
        while (!d.isAfter(finoA) && giri < 400) {
            partenza(d, orari, zona)?.let { risultato += it }
            d = d.plusDays(1)
            giri++
        }
        return risultato
    }
}
