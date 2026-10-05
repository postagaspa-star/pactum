package eu.stgm.pactum.figlio.valutatore

import eu.stgm.pactum.figlio.dati.Regola

/**
 * (0.16) Cosa fare in un giro, per gli avvisi del tempo di una regola: i
 * preavvisi "mancano 5 minuti / manca 1 minuto" e "il tempo è finito". Le due
 * notifiche di una regola stanno nello STESSO posto della tendina
 * (AvvisiLocali.idPreavviso), quindi l'ordine conta: SentinellaPatto prima
 * segna le chiavi, poi toglie [daTogliere], poi mette [preavvisi] e
 * [tempiFiniti]. Così, dopo un bonus, togliere il tempo finito del limite
 * vecchio non porta via il preavviso del limite nuovo dato nello stesso giro.
 */
data class GiroDelTempo(
    /** Notifiche dei tempi finiti che non valgono più (una volta sola: [chiaviTempiFiniti] ne porta il segno). */
    val tempiFinitiDaTogliere: List<NotificaDaTogliere>,
    /** Preavvisi che non valgono più (un bonus li ha riportati sopra i 5 minuti, regola tolta). */
    val preavvisiDaTogliere: List<Long>,
    val preavvisi: List<Preavviso>,
    val tempiFiniti: List<TempoFinito>,
    /** Da segnare come dette PRIMA di avvisare (mai un giro in tondo). */
    val chiaviPreavvisi: List<String>,
    val chiaviTempiFiniti: List<String>,
    /** (0.16) I segni "tolta" dei tempi finiti ridati: si cancellano (la notifica c'è di nuovo). */
    val segniDaCancellare: List<String> = emptyList(),
    /** Fra quanti ms l'app davanti porta una regola a una soglia, al limite o oltre; null = niente in vista. */
    val prossimo: Long?,
    /** Le chiavi delle regole a meno di due minuti di uso dal limite o dallo sforamento (TempiFiniti.vicine). */
    val vicine: List<String>,
) {
    val vicino: Boolean get() = vicine.isNotEmpty()

    /** Gli id di regola le cui notifiche vanno tolte, PRIMA di mettere quelle nuove. */
    val daTogliere: List<Long> get() = (tempiFinitiDaTogliere.map { it.regolaId } + preavvisiDaTogliere).distinct()
}

/**
 * (0.16) Quando il servizio guarda di nuovo, dopo un giro: [soglia] = fra
 * quanti ms l'app davanti porta una regola a una soglia, al limite o oltre
 * (null = niente in vista: il minuto). [vicine] = le chiavi delle regole a meno
 * di due minuti di uso dal limite o dallo sforamento: fino al giro dopo, il
 * controllo leggero (servizio.ControlloLeggero) guarda se arriva davanti una
 * loro app. [davanti] = i pacchetti davanti a questo giro (per loro vale già
 * [soglia]).
 */
data class ProssimoGiro(
    val soglia: Long?,
    val vicine: List<String> = emptyList(),
    val davanti: Set<String> = emptySet(),
) {
    val vicino: Boolean get() = vicine.isNotEmpty()

    companion object {
        val NIENTE = ProssimoGiro(null)
    }
}

object AvvisiDelTempo {

    /**
     * Il giro degli avvisi del tempo, contro [regole] (le limite_tempo di
     * questo telefono) e l'uso di adesso. [preavvisiFatti]/[tempiFinitiFatti]
     * = le chiavi già su disco; [sforamentiSegnalati] = gli sforamenti di oggi
     * già a registro (Segnalazioni).
     */
    fun giro(
        regole: List<Regola>,
        bonusOggiPerRegola: Map<String, Int>,
        indice: IndiceUso,
        giorno: String,
        preavvisiFatti: Set<String>,
        tempiFinitiFatti: Set<String>,
        sforamentiSegnalati: Set<String>,
        davanti: List<AppDavanti>,
    ): GiroDelTempo {
        val vecchi = TempiFiniti.daTogliere(regole, bonusOggiPerRegola, indice, giorno, tempiFinitiFatti)
        val preavvisi = Preavvisi.daDare(regole, bonusOggiPerRegola, indice, giorno, preavvisiFatti, davanti)
        val chiaviPreavvisi = Preavvisi.chiaviDette(preavvisi, giorno)
        val pf = preavvisiFatti + chiaviPreavvisi
        val preavvisiVecchi = Preavvisi.daTogliere(regole, bonusOggiPerRegola, indice, giorno, pf)
        val conSegni = tempiFinitiFatti + vecchi.map { it.segno }
        val tempi = TempiFiniti.daDare(regole, bonusOggiPerRegola, indice, giorno, conSegni, davanti, sforamentiSegnalati)
        val chiaviTempi = tempi.filter { !it.ridato }.map { TempiFiniti.chiave(it.regolaId, giorno, it.limiteEfficace) }
        val segniVia = tempi.filter { it.ridato }.map { TempiFiniti.chiaveTolta(it.regolaId, giorno, it.limiteEfficace) }
        val tf = conSegni + chiaviTempi - segniVia.toSet()
        val soglia = Preavvisi.prossimaSoglia(regole, bonusOggiPerRegola, indice, giorno, pf, davanti)
        val momento = TempiFiniti.prossimoMomento(regole, bonusOggiPerRegola, indice, giorno, tf, sforamentiSegnalati, davanti)
        return GiroDelTempo(
            tempiFinitiDaTogliere = vecchi,
            preavvisiDaTogliere = preavvisiVecchi,
            preavvisi = preavvisi,
            tempiFiniti = tempi,
            chiaviPreavvisi = chiaviPreavvisi,
            chiaviTempiFiniti = vecchi.map { it.segno } + chiaviTempi,
            segniDaCancellare = segniVia,
            prossimo = listOfNotNull(soglia, momento).minOrNull(),
            vicine = TempiFiniti.vicine(regole, bonusOggiPerRegola, indice, giorno, tf, sforamentiSegnalati),
        )
    }
}
