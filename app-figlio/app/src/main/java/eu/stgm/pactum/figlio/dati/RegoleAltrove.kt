package eu.stgm.pactum.figlio.dati

// Logica pura della v3 (più dispositivi per figlio): le regole del figlio sugli
// ALTRI suoi dispositivi, solo contate (qui non si mostrano: si cambiano da lì).
// Contano per "almeno una regola" e "l'ultima non si toglie", che valgono per il
// figlio (contratto v3, Regole). Niente Android: si prova con JUnit semplice.

/** L'ultimo conteggio salvato sul telefono e l'impronta del collegamento con cui è stato letto. */
data class RegoleAltroveSalvate(val conteggio: Int, val lettoCon: String)

object RegoleAltrove {

    /**
     * Quante regole attive del figlio (GET /api/regole) stanno su un altro suo
     * dispositivo: non fra quelle di questo telefono ([qui], che comprende la
     * vita reale) e non su un dispositivo scollegato dal genitore, perché da lì
     * non si cambiano più.
     */
    fun conta(delFiglio: List<Regola>, qui: List<Regola>, scollegati: Set<Long>): Int {
        val diQui = qui.mapTo(HashSet()) { it.id }
        return delFiglio.count { r -> r.attiva && r.id !in diQui && r.idDispositivo !in scollegati }
    }

    /**
     * Il numero da usare quando GET /api/regole non risponde (niente rete):
     * l'ultimo saputo, ma solo se letto con il collegamento di adesso
     * ([impronta]). Quello di un altro collegamento (telefono ricollegato a un
     * altro dispositivo) parla di un altro patto e non vale. Senza: zero.
     */
    fun ultimoNoto(salvato: RegoleAltroveSalvate?, impronta: String): Int =
        salvato?.takeIf { it.lettoCon == impronta }?.conteggio?.coerceAtLeast(0) ?: 0
}
