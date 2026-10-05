package eu.stgm.pactum.figlio.valutatore

import eu.stgm.pactum.figlio.avviso.Avviso
import eu.stgm.pactum.figlio.dati.Regola
import eu.stgm.pactum.figlio.dati.TipiRegola
import eu.stgm.pactum.figlio.servizio.CadenzaSentinella
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * (0.16, correzioni della revisione) Preavvisi e "tempo finito" nello stesso
 * giro, con la tendina vera di mezzo: le due notifiche di una regola stanno
 * nello stesso posto, quindi dopo un bonus il tempo finito vecchio si toglie
 * una volta, PRIMA dei preavvisi del limite nuovo. Più: la categoria contata
 * come il valutatore, il tempo finito "già a registro", e il giro ogni pochi
 * secondi vicino al limite.
 */
class AvvisiDelTempoTest {

    private val min = 60_000L
    private val sec = 1_000L
    private val giorno = "2026-10-05"

    private val instagram = "com.instagram.android"
    private val tiktok = "com.zhiliaoapp.musically"
    private val social = "categoria:social"

    private fun limite(id: Long, chiave: String, minuti: Int) = Regola(
        id = id,
        tipo = TipiRegola.LIMITE_TEMPO,
        parametri = buildJsonObject {
            put("app_o_categoria", chiave)
            put("minuti_al_giorno", minuti)
        },
        attiva = true,
    )

    private fun categoria(pacchetto: String): String =
        if (pacchetto == instagram || pacchetto == tiktok) social else "categoria:altro"

    private fun indice(vararg uso: Pair<String, Long>) = IndiceUso(uso.toList(), ::categoria)

    /**
     * Il telefono finto: la memoria su disco e la tendina (un posto per regola,
     * come AvvisiLocali.idPreavviso). [giro] fa quello che fa SentinellaPatto,
     * nello stesso ordine: chiavi, poi via il vecchio, poi il nuovo.
     */
    private inner class Telefono(var regole: List<Regola>) {
        var bonus: Map<String, Int> = emptyMap()

        /** Gli avvisi a tutto schermo chiesti (i ridati no). */
        val schermi = mutableListOf<String>()
        val preavvisiFatti = mutableSetOf<String>()
        val tempiFatti = mutableSetOf<String>()
        val segnalati = mutableSetOf<String>()

        /** regolaId → cosa c'è nella tendina in quel posto. */
        val tendina = mutableMapOf<Long, String>()

        fun giro(indice: IndiceUso, davanti: List<AppDavanti> = listOf(AppDavanti(instagram))): GiroDelTempo {
            val g = AvvisiDelTempo.giro(regole, bonus, indice, giorno, preavvisiFatti, tempiFatti, segnalati, davanti)
            preavvisiFatti += g.chiaviPreavvisi
            tempiFatti += g.chiaviTempiFiniti
            tempiFatti -= g.segniDaCancellare.toSet()
            for (id in g.daTogliere) tendina.remove(id)
            for (p in g.preavvisi) tendina[p.regolaId] = "mancano ${p.minutiMancanti} su ${p.limiteEfficace}"
            for (t in g.tempiFiniti) tendina[t.regolaId] = "finito ${t.limiteEfficace}"
            schermi += g.tempiFiniti.filter { !it.ridato }.map { "finito ${it.limiteEfficace}" }
            return g
        }
    }

    // --- 1. Dopo un bonus i preavvisi del limite nuovo restano --------------------------

    @Test
    fun `dopo un bonus i preavvisi del limite nuovo restano nella tendina`() {
        val t = Telefono(listOf(limite(5, instagram, 30)))
        t.giro(indice(instagram to 25 * min))
        assertEquals("mancano 5 su 30", t.tendina[5])
        t.giro(indice(instagram to 30 * min))
        assertEquals("finito 30", t.tendina[5])

        // +15 di bonus: il limite è 45. Il tempo finito di 30 se ne va (una volta).
        t.bonus = mapOf("5" to 15)
        t.giro(indice(instagram to 30 * min + 20 * sec))
        assertNull(t.tendina[5])
        assertTrue(TempiFiniti.chiaveTolta(5, giorno, 30) in t.tempiFatti)

        // A 40:00 "mancano 5 minuti" del limite nuovo, e RESTA (anche ai giri dopo).
        t.giro(indice(instagram to 40 * min))
        assertEquals("mancano 5 su 45", t.tendina[5])
        t.giro(indice(instagram to 40 * min + 5 * sec))
        assertEquals("mancano 5 su 45", t.tendina[5])
        // A 44:00 "manca 1 minuto", e resta.
        t.giro(indice(instagram to 44 * min))
        assertEquals("mancano 1 su 45", t.tendina[5])
        t.giro(indice(instagram to 44 * min + 5 * sec))
        assertEquals("mancano 1 su 45", t.tendina[5])
        // A 45:00 il tempo è finito di nuovo.
        t.giro(indice(instagram to 45 * min))
        assertEquals("finito 45", t.tendina[5])
    }

    @Test
    fun `bonus e preavviso nuovo nello stesso giro, il preavviso resta`() {
        // Lo schermo spento dopo il bonus: il primo giro dopo arriva già a 40:00.
        val t = Telefono(listOf(limite(5, instagram, 30)))
        t.giro(indice(instagram to 30 * min))
        assertEquals("finito 30", t.tendina[5])
        t.bonus = mapOf("5" to 15)
        val g = t.giro(indice(instagram to 40 * min))
        // Nello stesso giro: via il tempo finito di 30, dentro "mancano 5 minuti" di 45.
        assertEquals(listOf(5L), g.tempiFinitiDaTogliere.map { it.regolaId })
        assertEquals(1, g.preavvisi.size)
        assertEquals("mancano 5 su 45", t.tendina[5])
    }

    @Test
    fun `un bonus piccolo, preavviso subito nel giro del bonus`() {
        // A 30:30 un bonus di 3: limite 33, mancano 2 minuti e mezzo.
        val t = Telefono(listOf(limite(5, instagram, 30)))
        t.giro(indice(instagram to 30 * min))
        t.bonus = mapOf("5" to 3)
        t.giro(indice(instagram to 30 * min + 30 * sec))
        assertEquals("mancano 3 su 33", t.tendina[5])
    }

    @Test
    fun `senza bonus il tempo finito resta fino allo sforamento`() {
        val t = Telefono(listOf(limite(5, instagram, 30)))
        t.giro(indice(instagram to 30 * min))
        t.giro(indice(instagram to 30 * min + 40 * sec))
        assertEquals("finito 30", t.tendina[5])
    }

    // --- 2. La categoria contata come il valutatore ---------------------------------------

    @Test
    fun `categoria, il tempo finito arriva quando i minuti interi arrivano al limite`() {
        val regola = limite(4, social, 30)
        // Instagram 20:40 + TikTok 9:50 = 30:30 veri, ma 20 + 9 = 29 minuti interi:
        // Oggi e il genitore dicono 29. Niente "30 su 30".
        val prima = indice(instagram to 20 * min + 40 * sec, tiktok to 9 * min + 50 * sec)
        assertEquals(29L, prima.minuti(social))
        assertTrue(TempiFiniti.daDare(listOf(regola), emptyMap(), prima, giorno, emptySet()).isEmpty())
        // Instagram arriva a 21:00: 21 + 9 = 30. Adesso sì.
        val dopo = indice(instagram to 21 * min, tiktok to 9 * min + 50 * sec)
        assertEquals(TempoFinito(4, social, 30), TempiFiniti.daDare(listOf(regola), emptyMap(), dopo, giorno, emptySet()).single())
        // E il giro arriva proprio allora: 20 secondi al 21° minuto di Instagram.
        assertEquals(
            20 * sec,
            TempiFiniti.prossimoMomento(listOf(regola), emptyMap(), prima, giorno, emptySet(), emptySet(), listOf(AppDavanti(instagram))),
        )
    }

    @Test
    fun `categoria, lo sforamento arriva un minuto intero dopo il tempo finito`() {
        val regola = limite(4, social, 30)
        val fatti = setOf(TempiFiniti.chiave(4, giorno, 30))
        val idx = indice(instagram to 21 * min, tiktok to 9 * min + 50 * sec)
        // Dal 21:00 di Instagram al 22:00: un minuto, come per un'app sola.
        assertEquals(
            1 * min,
            TempiFiniti.prossimoMomento(listOf(regola), emptyMap(), idx, giorno, fatti, emptySet(), listOf(AppDavanti(instagram))),
        )
    }

    @Test
    fun `un'app sola e il totale restano al secondo`() {
        val app = limite(1, instagram, 30)
        assertEquals(1, TempiFiniti.daDare(listOf(app), emptyMap(), indice(instagram to 30 * min), giorno, emptySet()).size)
        assertTrue(TempiFiniti.daDare(listOf(app), emptyMap(), indice(instagram to 30 * min - 1), giorno, emptySet()).isEmpty())
        val totale = limite(9, "totale", 60)
        assertEquals(1, TempiFiniti.daDare(listOf(totale), emptyMap(), indice(instagram to 30 * min + 30 * sec, "com.whatsapp" to 29 * min + 30 * sec), giorno, emptySet()).size)
    }

    // --- 3. Tempo finito di nuovo, con lo sforamento già a registro ----------------------

    @Test
    fun `dopo un bonus con lo sforamento gia' a registro il tempo finito non promette il registro`() {
        val regola = limite(1, instagram, 30)
        val segnalati = setOf(Segnalazioni.chiave(1, giorno))
        val fatti = setOf(TempiFiniti.chiave(1, giorno, 30))
        val tempo = TempiFiniti.daDare(listOf(regola), mapOf("1" to 15), indice(instagram to 45 * min), giorno, fatti, emptyList(), segnalati).single()
        assertTrue(tempo.giaARegistro)
        val avviso = Avviso.daTempoFinito(tempo, regola, "Instagram")
        assertTrue(avviso.finito && avviso.giaARegistro)
        // L'avviso viaggia nell'intent: il campo arriva dall'altra parte.
        assertTrue(Avviso.daJson(Avviso.inJson(listOf(avviso))).single().giaARegistro)
    }

    @Test
    fun `senza sforamento a registro il tempo finito dice che da adesso va a registro`() {
        val regola = limite(1, instagram, 30)
        val tempo = TempiFiniti.daDare(listOf(regola), emptyMap(), indice(instagram to 30 * min), giorno, emptySet(), emptyList(), emptySet()).single()
        assertFalse(tempo.giaARegistro)
        assertFalse(Avviso.daTempoFinito(tempo, regola, "Instagram").giaARegistro)
        // Uno sforamento di un'altra regola o di un altro giorno non conta.
        val altri = setOf(Segnalazioni.chiave(2, giorno), Segnalazioni.chiave(1, "2026-10-04"))
        assertFalse(TempiFiniti.daDare(listOf(regola), emptyMap(), indice(instagram to 30 * min), giorno, emptySet(), emptyList(), altri).single().giaARegistro)
    }

    @Test
    fun `nel giro il tempo finito sa dello sforamento gia' a registro`() {
        val t = Telefono(listOf(limite(1, instagram, 30)))
        t.segnalati += Segnalazioni.chiave(1, giorno)
        t.tempiFatti += TempiFiniti.chiave(1, giorno, 30)
        t.bonus = mapOf("1" to 15)
        val g = t.giro(indice(instagram to 45 * min))
        assertTrue(g.tempiFiniti.single().giaARegistro)
    }

    // --- 4. Vicino al limite: il giro ogni pochi secondi -----------------------------------

    private fun vicino(regola: Regola, idx: IndiceUso, fatti: Set<String> = emptySet(), segnalati: Set<String> = emptySet()) =
        TempiFiniti.vicino(listOf(regola), emptyMap(), idx, giorno, fatti, segnalati)

    @Test
    fun `a meno di due minuti dal limite e' vicino, qualunque app sia davanti`() {
        val regola = limite(1, instagram, 30)
        assertTrue(vicino(regola, indice(instagram to 28 * min + 30 * sec)))
        assertTrue(vicino(regola, indice(instagram to 28 * min)))
        assertFalse(vicino(regola, indice(instagram to 27 * min + 59 * sec)))
        // Nel giro, senza nessuna app di Instagram davanti: vicino lo stesso.
        val g = Telefono(listOf(regola)).giro(indice(instagram to 29 * min), davanti = listOf(AppDavanti("com.whatsapp")))
        assertTrue(g.vicino)
        assertNull(g.prossimo)
        assertEquals(listOf(instagram), g.vicine)
        // Il giro completo dopo resta al minuto: nel frattempo il controllo leggero.
        assertEquals(CadenzaSentinella.INTERVALLO_MS, CadenzaSentinella.attesa(ProssimoGiro(g.prossimo, g.vicine)))
    }

    @Test
    fun `detto il tempo finito resta vicino fino allo sforamento, poi no`() {
        val regola = limite(1, instagram, 30)
        val fatti = setOf(TempiFiniti.chiave(1, giorno, 30))
        assertTrue(vicino(regola, indice(instagram to 30 * min + 10 * sec), fatti))
        // Sforamento segnalato: la soglia è passata, si torna al minuto.
        val segnalati = setOf(Segnalazioni.chiave(1, giorno))
        assertFalse(vicino(regola, indice(instagram to 31 * min), fatti, segnalati))
        assertEquals(CadenzaSentinella.INTERVALLO_MS, CadenzaSentinella.attesa(ProssimoGiro.NIENTE))
    }

    @Test
    fun `un limite 0 mai usato oggi e' vicino, cosi' la prima apertura accende subito il giro`() {
        // (0.16) Il controllo leggero costa poco: anche le regole mai usate oggi.
        assertTrue(vicino(limite(3, tiktok, 0), indice()))
        assertTrue(vicino(limite(2, tiktok, 1), indice()))
        // Detto il tempo finito (alla prima apertura) resta vicino fino allo sforamento…
        val fatti = setOf(TempiFiniti.chiave(3, giorno, 0))
        assertTrue(vicino(limite(3, tiktok, 0), indice(tiktok to 20 * sec), fatti))
        // …poi, segnalato lo sforamento, non più.
        assertFalse(vicino(limite(3, tiktok, 0), indice(tiktok to 70 * sec), fatti, setOf(Segnalazioni.chiave(3, giorno))))
        // Un limite lontano mai usato: no.
        assertFalse(vicino(limite(4, tiktok, 30), indice()))
    }

    @Test
    fun `limite 0, la prima apertura dell'app da' subito il tempo finito`() {
        val t = Telefono(listOf(limite(3, tiktok, 0)))
        // Prima: niente davanti, niente avviso; ma è vicino (controllo leggero).
        val prima = t.giro(indice(), davanti = emptyList())
        assertTrue(prima.vicino)
        assertTrue(prima.tempiFiniti.isEmpty())
        // Il controllo leggero vede TikTok arrivare: il giro completo lo ha davanti.
        assertEquals(1, t.giro(indice(), davanti = listOf(AppDavanti(tiktok))).tempiFiniti.size)
        assertEquals("finito 0", t.tendina[3])
    }

    // --- Tempo finito tolto e poi tornato valido ---------------------------------------------

    @Test
    fun `bonus rifiutato dal server, il tempo finito torna con la sola notifica`() {
        val t = Telefono(listOf(limite(5, instagram, 30)))
        t.giro(indice(instagram to 30 * min))
        assertEquals(listOf("finito 30"), t.schermi)
        // Il bonus nella copia locale: il tempo finito di 30 se ne va.
        t.bonus = mapOf("5" to 15)
        t.giro(indice(instagram to 30 * min + 10 * sec))
        assertNull(t.tendina[5])
        // Il server lo rifiuta: di nuovo 30. La notifica torna, l'avviso a tutto schermo no.
        t.bonus = emptyMap()
        val g = t.giro(indice(instagram to 30 * min + 20 * sec))
        assertTrue(g.tempiFiniti.single().ridato)
        assertEquals("finito 30", t.tendina[5])
        assertEquals(listOf("finito 30"), t.schermi)
        // Il segno "tolta" è cancellato: al giro dopo niente di nuovo.
        assertFalse(TempiFiniti.chiaveTolta(5, giorno, 30) in t.tempiFatti)
        assertTrue(t.giro(indice(instagram to 30 * min + 25 * sec)).tempiFiniti.isEmpty())
        assertEquals("finito 30", t.tendina[5])
    }

    @Test
    fun `regola spenta e riaccesa, il tempo finito torna quando l'uso ci arriva`() {
        val regola = limite(5, instagram, 30)
        val t = Telefono(listOf(regola))
        t.giro(indice(instagram to 30 * min))
        // Spenta: la notifica se ne va.
        t.regole = emptyList()
        t.giro(indice(instagram to 30 * min + 10 * sec))
        assertNull(t.tendina[5])
        // Riaccesa con l'uso ancora sotto (l'uso contato è sceso): niente, ma il giro
        // anticipato al limite c'è di nuovo.
        t.regole = listOf(regola)
        val sotto = t.giro(indice(instagram to 29 * min + 30 * sec))
        assertTrue(sotto.tempiFiniti.isEmpty())
        assertEquals(30 * sec, sotto.prossimo)
        // Al limite: la notifica torna, senza tutto schermo.
        val g = t.giro(indice(instagram to 30 * min))
        assertTrue(g.tempiFiniti.single().ridato)
        assertEquals("finito 30", t.tendina[5])
        assertEquals(1, t.schermi.size)
    }

    @Test
    fun `categoria vicina a minuti interi`() {
        val regola = limite(4, social, 30)
        assertTrue(vicino(regola, indice(instagram to 19 * min + 50 * sec, tiktok to 9 * min)))
        assertFalse(vicino(regola, indice(instagram to 18 * min + 50 * sec, tiktok to 9 * min)))
    }

    @Test
    fun `il giro completo dopo resta il momento esatto o il minuto, anche vicino`() {
        // Vicino o no, il giro completo va alla soglia dell'app davanti (col secondo
        // di margine) o al minuto: in mezzo c'è solo il controllo leggero.
        assertEquals(41_000L, CadenzaSentinella.attesa(ProssimoGiro(40_000L, listOf(instagram))))
        assertEquals(41_000L, CadenzaSentinella.attesa(ProssimoGiro(40_000L)))
        assertEquals(60_000L, CadenzaSentinella.attesa(ProssimoGiro(null, listOf(instagram))))
        assertEquals(1_000L, CadenzaSentinella.attesa(ProssimoGiro(-3_000L, listOf(instagram))))
        assertEquals(60_000L, CadenzaSentinella.attesa(ProssimoGiro.NIENTE))
    }

    @Test
    fun `le chiavi vicine una volta sola, solo quelle vicine`() {
        val regole = listOf(limite(1, instagram, 30), limite(2, instagram, 60), limite(4, social, 30), limite(9, "totale", 300))
        // Instagram 29:00 (vicina a 30, non a 60), social 29 minuti interi, totale lontano.
        val vicine = TempiFiniti.vicine(regole, emptyMap(), indice(instagram to 29 * min), giorno, emptySet(), emptySet())
        assertEquals(listOf(instagram, social), vicine)
    }
}
