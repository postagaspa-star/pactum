package eu.stgm.pactum.figlio.valutatore

import eu.stgm.pactum.figlio.catalogo.CatalogoApp
import eu.stgm.pactum.figlio.dati.Regola
import eu.stgm.pactum.figlio.dati.TipiRegola
import kotlinx.serialization.json.JsonPrimitive

/**
 * (0.16, contratto v3.8) Alla regola [regolaId] il tempo di oggi è appena
 * finito: l'uso ha raggiunto [limiteEfficace] (limite + bonus di oggi), al
 * secondo, ma non lo ha ancora superato. [chiave] = l'app, la categoria o "totale".
 * [giaARegistro] = oggi lo sforamento di questa regola è già stato segnalato
 * (un tempo finito di nuovo dopo un bonus): andare oltre non aggiunge niente
 * al registro (uno sforamento per regola al giorno), e l'avviso non lo promette.
 */
data class TempoFinito(
    val regolaId: Long,
    val chiave: String,
    val limiteEfficace: Int,
    val giaARegistro: Boolean = false,
    /**
     * (0.16) Lo stesso tempo finito di nuovo: la sua notifica era stata tolta
     * (un bonus poi rifiutato dal server, la regola spenta e riaccesa, l'uso
     * contato che scende) e adesso vale di nuovo. Si ridà solo la notifica,
     * senza l'avviso a tutto schermo (quello per questo limite c'è già stato).
     */
    val ridato: Boolean = false,
)

/**
 * (0.16) La notifica del tempo finito della regola [regolaId] che non vale più
 * (un bonus ha alzato il limite, la regola è stata tolta): si toglie una volta
 * sola e si segna [segno] (TempiFiniti.chiaveTolta). Senza il segno la si
 * toglierebbe a ogni giro, e con lei il preavviso del limite nuovo, che sta
 * nello stesso posto della tendina.
 */
data class NotificaDaTogliere(val regolaId: Long, val segno: String)

/**
 * (0.16, contratto v3.8) "Il tempo è finito" (logica pura: la applica
 * SentinellaPatto sotto il suo mutex, la memoria su disco sta in Impostazioni).
 *
 * A 30 su 30 l'avviso, da 31 il fuori regola. Quando l'uso di una limite_tempo
 * di questo telefono arriva al limite efficace (al secondo, con le stesse app
 * dei limiti: niente Home, niente Pactum, niente tempo nelle app di una
 * Sessione in corso) arriva l'avviso "il tempo è finito". NON è uno
 * sforamento: per "al massimo 30 minuti" i 30 minuti sono permessi, non va al
 * server. Lo sforamento resta a uso > limite (minuti interi, Valutatore).
 *
 * Una volta per regola, per giorno, per limite: un bonus che alza il limite lo
 * riarma (come i preavvisi). Mai a limite già superato (lì c'è lo sforamento),
 * mai per un limite 0 a uso zero senza una sua app davanti.
 *
 * Puntualità: [prossimoMomento] dice fra quanto, con le app davanti, una
 * regola arriva al limite o lo supera; il servizio guarda proprio allora.
 */
object TempiFiniti {

    private const val MINUTO_MS = 60_000L

    /** La chiave del dedup su disco: "regolaId:giorno:limite". */
    fun chiave(regolaId: Long, giorno: String, limiteEfficace: Int): String = "$regolaId:$giorno:$limiteEfficace"

    /**
     * La chiave che dice che l'avviso a tutto schermo di quel tempo finito è
     * apparso: allo sforamento dello stesso limite niente secondo avviso a
     * tutto schermo, solo la notifica.
     */
    fun chiaveSchermo(regolaId: Long, giorno: String, limiteEfficace: Int): String =
        chiave(regolaId, giorno, limiteEfficace) + ":schermo"

    /**
     * (0.16) Il tempo finito di quel limite è ancora da dire oggi: mai detto, o
     * detto e poi tolto (allora, se torna valido, si ridà: TempoFinito.ridato).
     */
    private fun daDire(regolaId: Long, giorno: String, limite: Int, giaFatti: Set<String>): Boolean =
        chiave(regolaId, giorno, limite) !in giaFatti || chiaveTolta(regolaId, giorno, limite) in giaFatti

    /** (0.16) La chiave che dice che la notifica di quel tempo finito è già stata tolta. */
    fun chiaveTolta(regolaId: Long, giorno: String, limiteEfficace: Int): String =
        chiave(regolaId, giorno, limiteEfficace) + ":tolta"

    /**
     * (0.16) Per [vicino]: entro quanto uso dal limite (o dallo sforamento) il
     * servizio guarda ogni pochi secondi, qualunque app sia davanti.
     */
    const val FINESTRA_VICINA_MS = 2 * 60_000L

    /**
     * L'uso di [chiave] è arrivato a [limite]? Contato COME IL VALUTATORE:
     * per una categoria la somma dei minuti interi delle sue app (così
     * l'avviso dice "30 su 30" quando anche Oggi e il genitore dicono 30, e
     * lo sforamento arriva un minuto dopo). Per un'app sola e per "totale" i
     * millisecondi veri: lì è lo stesso conto, al secondo.
     */
    private fun arrivato(indice: IndiceUso, chiave: String, limite: Int): Boolean =
        if (eCategoria(chiave)) indice.minuti(chiave) >= limite else indice.millis(chiave) >= limite * MINUTO_MS

    fun eCategoria(chiave: String): Boolean =
        chiave.trim().lowercase().startsWith(CatalogoApp.PREFISSO_CATEGORIA)

    /**
     * Quanto uso manca perché [chiave] arrivi a [obiettivoMinuti] contata
     * come il valutatore: per una categoria a minuti interi (i minuti che
     * mancano), per un'app o "totale" al millisecondo. Lo usano anche i
     * preavvisi (Preavvisi): "mancano 5 minuti" con gli stessi minuti di Oggi.
     */
    fun mancaUso(indice: IndiceUso, chiave: String, obiettivoMinuti: Long): Long =
        if (eCategoria(chiave)) {
            (obiettivoMinuti - indice.minuti(chiave)) * MINUTO_MS
        } else {
            obiettivoMinuti * MINUTO_MS - indice.millis(chiave)
        }

    /** Una limite_tempo attiva e leggibile: la sua chiave e il limite di oggi. */
    private class Limite(val regola: Regola, val chiave: String, val limite: Int)

    private fun limite(regola: Regola, bonusOggiPerRegola: Map<String, Int>): Limite? {
        if (!regola.attiva || regola.tipo != TipiRegola.LIMITE_TEMPO) return null
        val chiave = (regola.parametri["app_o_categoria"] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() }
            ?: return null
        val limite = Valutatore.limiteEfficace(regola, bonusOggiPerRegola) ?: return null
        return Limite(regola, chiave, limite)
    }

    /**
     * Le regole il cui tempo è finito adesso e non è ancora stato detto (per
     * questo limite, oggi). [sforamentiSegnalati] (chiavi di Segnalazioni):
     * se lo sforamento della regola è già a registro oggi, il tempo finito lo dice.
     */
    fun daDare(
        regole: List<Regola>,
        bonusOggiPerRegola: Map<String, Int>,
        indice: IndiceUso,
        giorno: String,
        giaFatti: Set<String>,
        davanti: List<AppDavanti> = emptyList(),
        sforamentiSegnalati: Set<String> = emptySet(),
    ): List<TempoFinito> = regole.mapNotNull { regola ->
        val l = limite(regola, bonusOggiPerRegola) ?: return@mapNotNull null
        // Già oltre (come lo conta il valutatore): c'è lo sforamento, non questo.
        if (indice.minuti(l.chiave) > l.limite) return@mapNotNull null
        if (!arrivato(indice, l.chiave, l.limite)) return@mapNotNull null
        // Un limite 0 a uso zero senza una sua app davanti (appena sveglio): niente.
        val inUso = indice.millis(l.chiave) > 0 || davanti.any { it.contaTra <= 0 && indice.cade(l.chiave, it.pacchetto) }
        if (!inUso) return@mapNotNull null
        // Già detto per questo limite, oggi: niente. Tranne se la sua notifica è
        // stata tolta e adesso vale di nuovo: allora la si ridà (v. TempoFinito.ridato).
        val detto = chiave(regola.id, giorno, l.limite) in giaFatti
        val tolto = chiaveTolta(regola.id, giorno, l.limite) in giaFatti
        if (detto && !tolto) return@mapNotNull null
        TempoFinito(
            regola.id,
            l.chiave,
            l.limite,
            giaARegistro = Segnalazioni.chiave(regola.id, giorno) in sforamentiSegnalati,
            ridato = detto,
        )
    }

    /**
     * I "tempo finito" detti oggi che non valgono più: la regola è tolta o
     * spenta, o un bonus ha riportato il limite sopra l'uso. La loro notifica
     * va tolta, UNA volta sola (poi c'è il segno [chiaveTolta]): lo stesso
     * posto nella tendina servirà ai preavvisi del limite nuovo. Superati no:
     * lì la notifica dello sforamento prende il posto. Il servizio toglie
     * queste PRIMA di mettere i preavvisi del giro (AvvisiDelTempo).
     */
    fun daTogliere(
        regole: List<Regola>,
        bonusOggiPerRegola: Map<String, Int>,
        indice: IndiceUso,
        giorno: String,
        giaFatti: Set<String>,
    ): List<NotificaDaTogliere> {
        val perId = regole.associateBy { it.id }
        return giaFatti.mapNotNull { chiave ->
            val parti = chiave.split(':')
            // Solo le chiavi dei tempi finiti di oggi ("id:giorno:limite"), non i loro segni.
            if (parti.size != 3 || parti[1] != giorno) return@mapNotNull null
            val id = parti[0].toLongOrNull() ?: return@mapNotNull null
            val limiteDetto = parti[2].toIntOrNull() ?: return@mapNotNull null
            val segno = chiaveTolta(id, giorno, limiteDetto)
            if (segno in giaFatti) return@mapNotNull null
            val regola = perId[id]
            val l = regola?.let { limite(it, bonusOggiPerRegola) }
            val valeAncora = l != null && arrivato(indice, l.chiave, l.limite)
            if (valeAncora) null else NotificaDaTogliere(id, segno)
        }.sortedBy { it.segno }
    }

    /**
     * Fra quanti ms le app in primo piano ([davanti]), se restano lì, portano
     * una regola al suo limite (tempo finito non ancora detto per quel limite)
     * o oltre (sforamento non ancora segnalato oggi: chiave di Segnalazioni in
     * [sforamentiSegnalati]). Null se niente è in vista: vale il solito giro.
     */
    fun prossimoMomento(
        regole: List<Regola>,
        bonusOggiPerRegola: Map<String, Int>,
        indice: IndiceUso,
        giorno: String,
        giaFatti: Set<String>,
        sforamentiSegnalati: Set<String>,
        davanti: List<AppDavanti>,
    ): Long? {
        if (davanti.isEmpty()) return null
        var prima: Long? = null
        for (regola in regole) {
            val l = limite(regola, bonusOggiPerRegola) ?: continue
            if (daDire(regola.id, giorno, l.limite, giaFatti) && !arrivato(indice, l.chiave, l.limite)) {
                // Contato come in [daDare]: una categoria al minuto intero delle sue app.
                val attesa = if (eCategoria(l.chiave)) {
                    attesaMinuti(indice, l.chiave, l.limite.toLong(), davanti)
                } else {
                    attesaMillis(indice, l.chiave, l.limite * MINUTO_MS, davanti)
                }
                attesa?.let { prima = minOf(prima ?: it, it) }
            }
            if (Segnalazioni.chiave(regola.id, giorno) !in sforamentiSegnalati && indice.minuti(l.chiave) <= l.limite) {
                attesaMinuti(indice, l.chiave, l.limite + 1L, davanti)?.let { prima = minOf(prima ?: it, it) }
            }
        }
        return prima
    }

    /**
     * (0.16) Una regola di tempo di questo telefono è VICINA al suo limite
     * (tempo finito non ancora detto per il limite di oggi) o allo sforamento
     * (non ancora segnalato oggi): mancano più di zero e al massimo
     * [FINESTRA_VICINA_MS] di uso, contato come il valutatore. Allora, a
     * schermo acceso, il servizio guarda ogni pochi secondi QUALUNQUE app sia
     * davanti: un'app aperta dopo il giro (un cambio di app non sveglia il
     * servizio) arriva al limite al più pochi secondi prima che lo si veda.
     * (0.16) Anche le regole mai usate oggi (un limite 0, o di pochi minuti):
     * fra un giro e l'altro c'è solo il controllo leggero, che costa poco.
     */
    fun vicino(
        regole: List<Regola>,
        bonusOggiPerRegola: Map<String, Int>,
        indice: IndiceUso,
        giorno: String,
        giaFatti: Set<String>,
        sforamentiSegnalati: Set<String>,
    ): Boolean = vicine(regole, bonusOggiPerRegola, indice, giorno, giaFatti, sforamentiSegnalati).isNotEmpty()

    /**
     * (0.16) Le chiavi (app, categoria, "totale") delle regole vicine (v.
     * [vicino]), una volta ciascuna: il controllo leggero del servizio guarda
     * se arriva davanti un'app che cade in una di queste.
     */
    fun vicine(
        regole: List<Regola>,
        bonusOggiPerRegola: Map<String, Int>,
        indice: IndiceUso,
        giorno: String,
        giaFatti: Set<String>,
        sforamentiSegnalati: Set<String>,
    ): List<String> = regole.mapNotNull { regola ->
        val l = limite(regola, bonusOggiPerRegola) ?: return@mapNotNull null
        val manca = mancaUso(indice, l.chiave, l.limite.toLong())
        // (0.16) Anche una regola mai usata oggi: il controllo leggero costa poco, e
        // così la prima apertura di un'app a limite 0 (o di pochi minuti) accende
        // subito il giro. Un limite 0 mai usato: il tempo finisce alla prima apertura.
        val maiUsata = indice.millis(l.chiave) <= 0
        val alLimite = daDire(regola.id, giorno, l.limite, giaFatti) &&
            (manca in 1..FINESTRA_VICINA_MS || (maiUsata && manca <= 0))
        val alloSforamento = Segnalazioni.chiave(regola.id, giorno) !in sforamentiSegnalati &&
            mancaUso(indice, l.chiave, l.limite + 1L) in 1..FINESTRA_VICINA_MS
        l.chiave.takeIf { alLimite || alloSforamento }
    }.distinct()

    /**
     * Fra quanti ms i millisecondi di [chiave] arrivano a [obiettivoMs] con le
     * app [davanti] che la consumano (insieme, come per i preavvisi). Un'app
     * di una Sessione in corso conta solo dalla fine della sessione. Null se
     * nessuna app davanti la consuma.
     */
    fun attesaMillis(indice: IndiceUso, chiave: String, obiettivoMs: Long, davanti: List<AppDavanti>): Long? {
        val manca = obiettivoMs - indice.millis(chiave)
        if (manca <= 0) return 0
        var prima: Long? = null
        val subito = davanti.count { it.contaTra <= 0 && indice.cade(chiave, it.pacchetto) }
        if (subito > 0) prima = manca / subito
        for (app in davanti) {
            if (app.contaTra > 0 && indice.cade(chiave, app.pacchetto)) {
                val t = app.contaTra + manca
                prima = minOf(prima ?: t, t)
            }
        }
        return prima
    }

    /**
     * Fra quanti ms i minuti INTERI di [chiave], come li conta il valutatore
     * (IndiceUso.minuti), arrivano a [obiettivoMinuti]. Per un'app o una
     * categoria i minuti sono la somma dei minuti interi di ogni app: un'app
     * davanti ne aggiunge uno ogni volta che il SUO tempo passa un minuto
     * intero. Per "totale" contano i millisecondi veri. Null se nessuna app
     * davanti la consuma.
     */
    fun attesaMinuti(indice: IndiceUso, chiave: String, obiettivoMinuti: Long, davanti: List<AppDavanti>): Long? {
        val mancano = obiettivoMinuti - indice.minuti(chiave)
        if (mancano <= 0) return 0
        if (chiave == CatalogoApp.CHIAVE_TOTALE) return attesaMillis(indice, chiave, obiettivoMinuti * MINUTO_MS, davanti)
        var prima: Long? = null
        for (app in davanti) {
            if (!indice.cade(chiave, app.pacchetto)) continue
            val suoi = indice.millis(app.pacchetto)
            // Al prossimo minuto intero della sua app, poi un minuto per ogni minuto che manca ancora.
            val t = (MINUTO_MS - suoi % MINUTO_MS) + (mancano - 1) * MINUTO_MS + app.contaTra.coerceAtLeast(0)
            prima = minOf(prima ?: t, t)
        }
        return prima
    }
}
