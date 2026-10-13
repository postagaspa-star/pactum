package eu.stgm.pactum.figlio.bilancio

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.util.Locale

/**
 * (0.24) La logica del bilancio della sera, senza Android: a che giorno appartiene un
 * bilancio, quali promesse chiedere e quando, com'è andata una promessa che il
 * telefono controlla da solo, quali promesse non riescono da tre volte. Provata in
 * LogicaBilancioTest. Gli orari sono quelli del telefono (LocalDateTime), come la
 * chiusura della sera.
 */
object LogicaBilancio {

    /** Prima di quest'ora il bilancio è ancora quello di ieri: chi risponde a mezzanotte e mezza. */
    val FINE_SERATA: LocalTime = LocalTime.of(4, 0)

    /** Quanto si ricorda: bilanci e promesse più vecchi si buttano. */
    const val GIORNI_DA_TENERE = 60L

    /** Una promessa senza risposta si chiede ancora per una settimana, poi si lascia stare. */
    const val GIORNI_PER_RISPONDERE = 7L

    /** Le volte di fila che una promessa non riesce prima di chiedere di cambiarla. */
    const val DI_FILA = 3

    const val MIN_CARATTERI = 3
    const val MAX_CARATTERI = 500

    /** Il giorno di cui si fa il bilancio adesso: prima delle 4 è ancora ieri sera. */
    fun giornoDelBilancio(adesso: LocalDateTime): LocalDate =
        if (adesso.toLocalTime().isBefore(FINE_SERATA)) adesso.toLocalDate().minusDays(1) else adesso.toLocalDate()

    fun bilancioDi(diario: Diario, giorno: LocalDate): Bilancio? =
        diario.bilanci.firstOrNull { it.giorno == giorno.toString() }

    /** Il momento della promessa; null se non ha orario (o è scritta male). */
    fun momento(promessa: Promessa): LocalDateTime? {
        val ora = promessa.ora ?: return null
        return runCatching { LocalDateTime.of(LocalDate.parse(promessa.giorno), LocalTime.parse(ora)) }.getOrNull()
    }

    private fun giorno(promessa: Promessa): LocalDate? = runCatching { LocalDate.parse(promessa.giorno) }.getOrNull()

    /**
     * Com'è andata una promessa. Quella detta dal figlio vale com'è. Per una
     * misurabile si legge il semaforo della sua regola di un giorno: rosso = non
     * fatta (anche oggi: lo sforamento c'è già); verde = fatta, ma solo a giorno
     * finito (oggi può ancora andare storta); grigio a giorno finito = senza dati.
     * null = ancora da sapere.
     */
    fun esito(promessa: Promessa, regole: List<RegolaDiUnGiorno>, oggi: LocalDate): String? {
        if (promessa.regolaId == null) return promessa.esito
        val giorno = giorno(promessa) ?: return null
        val passato = giorno.isBefore(oggi)
        val stato = regole.firstOrNull { it.id == promessa.regolaId }?.semaforo?.get(promessa.giorno)
        return when {
            stato == "rosso" -> EsitiPromessa.NON_FATTA
            stato == "verde" && passato -> EsitiPromessa.FATTA
            passato -> EsitiPromessa.SENZA_DATI
            else -> null
        }
    }

    /**
     * Le promesse a cui rispondere adesso ("Fatto?"): non misurabili, senza
     * risposta, degli ultimi [GIORNI_PER_RISPONDERE] giorni; di oggi solo quelle
     * senza orario o con l'orario passato. Dalla più vecchia.
     */
    fun daRispondere(diario: Diario, adesso: LocalDateTime): List<Promessa> {
        val oggi = adesso.toLocalDate()
        val limite = oggi.minusDays(GIORNI_PER_RISPONDERE)
        return diario.promesse
            .filter { p ->
                val g = giorno(p) ?: return@filter false
                p.regolaId == null && p.esito == null && !g.isBefore(limite) && when {
                    g.isBefore(oggi) -> true
                    g == oggi -> momento(p)?.let { !it.isAfter(adesso) } ?: true
                    else -> false
                }
            }
            .sortedWith(compareBy({ it.giorno }, { it.ora ?: "99:99" }))
    }

    /** I promemoria da mandare adesso: le promesse di oggi con l'ora passata, mai avvisate, senza risposta. */
    fun promemoriaDaDare(diario: Diario, adesso: LocalDateTime): List<Promessa> =
        diario.promesse.filter { p ->
            val quando = momento(p) ?: return@filter false
            p.regolaId == null && p.esito == null && !p.avvisoDato &&
                quando.toLocalDate() == adesso.toLocalDate() && !quando.isAfter(adesso)
        }

    /** Il prossimo promemoria da aspettare, dopo [adesso]; null = nessuno. */
    fun prossimoPromemoria(diario: Diario, adesso: LocalDateTime): LocalDateTime? =
        diario.promesse
            .filter { it.regolaId == null && it.esito == null && !it.avvisoDato }
            .mapNotNull { momento(it) }
            .filter { it.isAfter(adesso) }
            .minOrNull()

    fun segnaAvvisate(diario: Diario, ids: Set<String>): Diario =
        diario.copy(promesse = diario.promesse.map { if (it.id in ids) it.copy(avvisoDato = true) else it })

    fun rispondi(diario: Diario, id: String, esito: String): Diario =
        diario.copy(promesse = diario.promesse.map { if (it.id == id && it.regolaId == null) it.copy(esito = esito) else it })

    /** "Rimando": un altro giorno o un'altra ora; il promemoria riparte, il conto dei rimandi sale. */
    fun rimanda(diario: Diario, id: String, giorno: LocalDate, ora: LocalTime?): Diario =
        diario.copy(
            promesse = diario.promesse.map {
                if (it.id == id && it.regolaId == null) {
                    it.copy(
                        giorno = giorno.toString(),
                        ora = ora?.let(::testoOra),
                        esito = null,
                        avvisoDato = false,
                        rimandi = it.rimandi + 1,
                    )
                } else {
                    it
                }
            },
        )

    /**
     * "Rimando di un'ora" dalla notifica: fra un'ora, oggi. Se fra un'ora è già
     * domani si chiede nel bilancio di stasera (oggi, senza orario).
     */
    fun traUnOra(adesso: LocalDateTime): Pair<LocalDate, LocalTime?> {
        val dopo = adesso.plusHours(1)
        return if (dopo.toLocalDate() == adesso.toLocalDate()) adesso.toLocalDate() to dopo.toLocalTime() else adesso.toLocalDate() to null
    }

    /** Il testo di una promessa per confrontarla: senza maiuscole e spazi di troppo. */
    fun normale(testo: String): String = testo.trim().lowercase(Locale.ITALIAN).replace(Regex("\\s+"), " ")

    /**
     * Le promesse da cambiare: quelle (stesso testo) le cui ultime [DI_FILA] volte
     * con un esito sono tutte non fatte. Si torna a proporle uguali e non riescono:
     * forse sono troppo grandi, o l'orario non va. Il testo dell'ultima.
     */
    fun daCambiare(diario: Diario, regole: List<RegolaDiUnGiorno>, oggi: LocalDate): List<String> =
        diario.promesse
            .groupBy { normale(it.testo) }
            .values
            .mapNotNull { stesse ->
                val ultime = stesse
                    .sortedWith(compareBy({ it.giorno }, { it.ora ?: "" }))
                    .mapNotNull { p -> esito(p, regole, oggi)?.let { p to it } }
                    .takeLast(DI_FILA)
                ultime.takeIf { it.size == DI_FILA && it.all { (_, e) -> e == EsitiPromessa.NON_FATTA } }
                    ?.last()?.first?.testo
            }

    fun eDaCambiare(testo: String, diario: Diario, regole: List<RegolaDiUnGiorno>, oggi: LocalDate): Boolean =
        normale(testo) in daCambiare(diario, regole, oggi).map(::normale)

    /** Una promessa nuova, prima di salvarla. [regolaId] = la regola di un giorno già creata. */
    data class NuovaPromessa(
        val testo: String,
        val giorno: LocalDate,
        val ora: LocalTime? = null,
        val regolaId: Long? = null,
    )

    enum class Problema { COSA_MANCANTE, PROMESSA_MANCANTE, TROPPO_LUNGO, GIORNO_NON_VALIDO, ORA_PASSATA }

    /**
     * Cosa manca per salvare. Con "bene" niente. Con "così così" o "male": cosa
     * non ha funzionato e una promessa per un giorno dopo quello del bilancio, con
     * un'ora non già passata.
     */
    fun problema(voto: String, cosa: String?, nuova: NuovaPromessa?, adesso: LocalDateTime): Problema? {
        if (voto == Voti.BENE) return null
        val testoCosa = cosa?.trim().orEmpty()
        if (testoCosa.length < MIN_CARATTERI) return Problema.COSA_MANCANTE
        if (testoCosa.length > MAX_CARATTERI) return Problema.TROPPO_LUNGO
        if (nuova == null || nuova.testo.trim().length < MIN_CARATTERI) return Problema.PROMESSA_MANCANTE
        if (nuova.testo.trim().length > MAX_CARATTERI) return Problema.TROPPO_LUNGO
        if (!nuova.giorno.isAfter(giornoDelBilancio(adesso))) return Problema.GIORNO_NON_VALIDO
        if (nuova.ora != null && !LocalDateTime.of(nuova.giorno, nuova.ora).isAfter(adesso)) return Problema.ORA_PASSATA
        return null
    }

    /**
     * Il bilancio di [giorno], salvato: sostituisce quello di prima dello stesso
     * giorno. Una promessa di prima senza risposta e non misurabile se ne va con lui
     * (è stata riscritta); una misurabile resta, la sua regola c'è già.
     */
    fun salva(
        diario: Diario,
        giorno: LocalDate,
        voto: String,
        cosa: String?,
        nuova: NuovaPromessa?,
        idPromessa: String,
        scrittoIl: Long,
    ): Diario {
        val prima = bilancioDi(diario, giorno)
        val promesse = diario.promesse.filterNot { p ->
            p.id == prima?.promessaId && p.regolaId == null && p.esito == null
        }
        val promessa = nuova?.takeIf { voto != Voti.BENE }?.let {
            Promessa(
                id = idPromessa,
                testo = it.testo.trim(),
                giorno = it.giorno.toString(),
                ora = it.ora?.let(::testoOra),
                nataIl = giorno.toString(),
                regolaId = it.regolaId,
            )
        }
        val bilancio = Bilancio(
            giorno = giorno.toString(),
            voto = voto,
            cosaNonHaFunzionato = cosa?.trim()?.takeIf { voto != Voti.BENE && it.isNotEmpty() },
            promessaId = promessa?.id ?: prima?.promessaId?.takeIf { id -> promesse.any { it.id == id } },
            scrittoIl = scrittoIl,
        )
        return diario.copy(
            bilanci = diario.bilanci.filterNot { it.giorno == bilancio.giorno } + bilancio,
            promesse = promesse + listOfNotNull(promessa),
        )
    }

    /** Si butta quello più vecchio di [GIORNI_DA_TENERE] giorni. */
    fun pulisci(diario: Diario, oggi: LocalDate): Diario {
        val limite = oggi.minusDays(GIORNI_DA_TENERE).toString()
        return diario.copy(
            bilanci = diario.bilanci.filter { it.giorno >= limite },
            promesse = diario.promesse.filter { it.giorno >= limite || it.nataIl >= limite },
        )
    }

    /** Un giorno della storia in fondo alla pagina: il voto (se c'è) e la promessa di quella sera. */
    data class GiornoDellaStoria(
        val giorno: LocalDate,
        val voto: String?,
        val promessa: Promessa?,
        val esito: String?,
    )

    /** Gli ultimi [quanti] giorni prima di [oggi] (escluso), dal più recente. */
    fun storia(diario: Diario, regole: List<RegolaDiUnGiorno>, oggi: LocalDate, quanti: Int = 7): List<GiornoDellaStoria> =
        (1..quanti).map { indietro ->
            val giorno = oggi.minusDays(indietro.toLong())
            val bilancio = bilancioDi(diario, giorno)
            val promessa = bilancio?.promessaId?.let { id -> diario.promesse.firstOrNull { it.id == id } }
            GiornoDellaStoria(giorno, bilancio?.voto, promessa, promessa?.let { esito(it, regole, oggi) })
        }

    /**
     * Il bilancio va chiesto in Oggi: quello di stasera non c'è ancora e l'ora della
     * sera è passata (o è già notte, prima delle 4).
     */
    fun bilancioDaChiedere(diario: Diario, adesso: LocalDateTime, oraSerale: LocalTime): Boolean {
        val giorno = giornoDelBilancio(adesso)
        if (bilancioDi(diario, giorno) != null) return false
        return giorno != adesso.toLocalDate() || !adesso.toLocalTime().isBefore(oraSerale)
    }

    fun testoOra(ora: LocalTime): String = "%02d:%02d".format(ora.hour, ora.minute)
}
