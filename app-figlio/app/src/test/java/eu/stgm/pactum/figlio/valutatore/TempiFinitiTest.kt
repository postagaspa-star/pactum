package eu.stgm.pactum.figlio.valutatore

import eu.stgm.pactum.figlio.dati.Regola
import eu.stgm.pactum.figlio.dati.TipiRegola
import eu.stgm.pactum.figlio.servizio.CadenzaSentinella
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * (0.16, contratto v3.8) A 30 su 30 l'avviso "il tempo è finito", da 31 il
 * fuori regola: al secondo, una volta per regola, giorno e limite (un bonus
 * lo riarma), niente secondo avviso a tutto schermo allo sforamento, e il giro
 * della sentinella proprio al momento del limite e dello sforamento.
 */
class TempiFinitiTest {

    private val min = 60_000L
    private val sec = 1_000L
    private val giorno = "2026-10-05"

    private val instagram = "com.instagram.android"
    private val tiktok = "com.zhiliaoapp.musically"
    private val social = "categoria:social"

    private fun limite(id: Long, chiave: String, minuti: Int, attiva: Boolean = true) = Regola(
        id = id,
        tipo = TipiRegola.LIMITE_TEMPO,
        parametri = buildJsonObject {
            put("app_o_categoria", chiave)
            put("minuti_al_giorno", minuti)
        },
        attiva = attiva,
    )

    private fun categoria(pacchetto: String): String =
        if (pacchetto == instagram || pacchetto == tiktok) social else "categoria:altro"

    private fun indice(vararg uso: Pair<String, Long>, totaleMinimo: Long = 0L) =
        IndiceUso(uso.toList(), ::categoria, totaleMinimo)

    private fun daDare(regole: List<Regola>, indice: IndiceUso, fatti: Set<String> = emptySet(), bonus: Map<String, Int> = emptyMap()) =
        TempiFiniti.daDare(regole, bonus, indice, giorno, fatti)

    // --- la soglia del limite: al secondo ---------------------------------------

    @Test
    fun `un secondo prima del limite niente`() {
        val regola = limite(1, instagram, 30)
        assertTrue(daDare(listOf(regola), indice(instagram to 29 * min + 59 * sec)).isEmpty())
    }

    @Test
    fun `a 30 su 30 esatti il tempo e' finito`() {
        val regola = limite(1, instagram, 30)
        val tempo = daDare(listOf(regola), indice(instagram to 30 * min)).single()
        assertEquals(TempoFinito(1, instagram, 30), tempo)
    }

    @Test
    fun `a 30 minuti e 59 secondi e' ancora tempo finito, non sforamento`() {
        val regola = limite(1, instagram, 30)
        assertEquals(1, daDare(listOf(regola), indice(instagram to 30 * min + 59 * sec)).size)
        // Il valutatore, a minuti interi, non lo chiama ancora sforamento.
        val sforamenti = Valutatore.valuta(
            listOf(regola), emptyMap(), indice(instagram to 30 * min + 59 * sec)::minuti, { _, _ -> 0 }, 0L, java.time.ZoneId.of("UTC"),
        )
        assertTrue(sforamenti.isEmpty())
    }

    @Test
    fun `a 31 su 30 e' sforamento, non piu' tempo finito`() {
        val regola = limite(1, instagram, 30)
        assertTrue(daDare(listOf(regola), indice(instagram to 31 * min)).isEmpty())
        val sforamenti = Valutatore.valuta(
            listOf(regola), emptyMap(), indice(instagram to 31 * min)::minuti, { _, _ -> 0 }, 0L, java.time.ZoneId.of("UTC"),
        )
        assertEquals(1, sforamenti.size)
    }

    @Test
    fun `una volta sola per regola giorno e limite`() {
        val regola = limite(1, instagram, 30)
        val fatti = setOf(TempiFiniti.chiave(1, giorno, 30))
        assertTrue(daDare(listOf(regola), indice(instagram to 30 * min + 10 * sec), fatti).isEmpty())
        // Un altro giorno vale di nuovo.
        assertEquals(1, TempiFiniti.daDare(listOf(regola), emptyMap(), indice(instagram to 30 * min), "2026-10-06", fatti).size)
    }

    @Test
    fun `un bonus che alza il limite lo riarma`() {
        val regola = limite(1, instagram, 30)
        val fatti = setOf(TempiFiniti.chiave(1, giorno, 30))
        val bonus = mapOf("1" to 15)
        // Col bonus il limite è 45: a 40 minuti non è finito.
        assertTrue(daDare(listOf(regola), indice(instagram to 40 * min), fatti, bonus).isEmpty())
        // A 45 sì, di nuovo.
        assertEquals(TempoFinito(1, instagram, 45), daDare(listOf(regola), indice(instagram to 45 * min), fatti, bonus).single())
    }

    @Test
    fun `regole spente, fasce e limite 0 a uso zero non danno niente`() {
        assertTrue(daDare(listOf(limite(1, instagram, 30, attiva = false)), indice(instagram to 30 * min)).isEmpty())
        assertTrue(daDare(listOf(limite(2, tiktok, 0)), indice()).isEmpty())
        val fascia = Regola(id = 3, tipo = TipiRegola.FASCIA_ORARIA, parametri = buildJsonObject { put("dalle", "22:00") }, attiva = true)
        assertTrue(daDare(listOf(fascia), indice(instagram to 30 * min)).isEmpty())
    }

    @Test
    fun `un limite 0 con la sua app davanti e' finito subito`() {
        val regola = limite(2, tiktok, 0)
        val tempo = TempiFiniti.daDare(listOf(regola), emptyMap(), indice(), giorno, emptySet(), listOf(AppDavanti(tiktok)))
        assertEquals(1, tempo.size)
    }

    @Test
    fun `categoria e totale contano come i limiti`() {
        val categoria = limite(4, social, 30)
        assertEquals(1, daDare(listOf(categoria), indice(instagram to 20 * min, tiktok to 10 * min)).size)
        val totale = limite(5, "totale", 60)
        assertEquals(1, daDare(listOf(totale), indice(instagram to 40 * min, "com.whatsapp" to 20 * min)).size)
    }

    // --- niente doppio avviso allo sforamento ----------------------------------------

    private val sforamento = Sforamento(regolaId = 1, tipo = TipiRegola.LIMITE_TEMPO, limiteEfficace = 30, minutiOltre = 1)

    @Test
    fun `se l'avviso del tempo finito e' apparso, allo sforamento solo la notifica`() {
        val decisione = Segnalazioni.decidi(
            listOf(sforamento), giorno, emptySet(), mostraSopra = true,
            tempiFinitiFatti = setOf(TempiFiniti.chiave(1, giorno, 30), TempiFiniti.chiaveSchermo(1, giorno, 30)),
        )
        assertEquals(listOf(sforamento), decisione.nuovi)
        assertTrue(decisione.aTuttoSchermo.isEmpty())
    }

    @Test
    fun `se il tempo finito e' stato solo notificato, lo sforamento si apre a tutto schermo`() {
        val decisione = Segnalazioni.decidi(
            listOf(sforamento), giorno, emptySet(), mostraSopra = true,
            tempiFinitiFatti = setOf(TempiFiniti.chiave(1, giorno, 30)),
        )
        assertEquals(listOf(sforamento), decisione.aTuttoSchermo)
    }

    @Test
    fun `un avviso del tempo finito per un altro limite non toglie quello dello sforamento`() {
        // Apparso a 30, poi un bonus ha portato il limite a 45: lo sforamento a 46 si apre.
        val oltre45 = sforamento.copy(limiteEfficace = 45)
        val decisione = Segnalazioni.decidi(
            listOf(oltre45), giorno, emptySet(), mostraSopra = true,
            tempiFinitiFatti = setOf(TempiFiniti.chiaveSchermo(1, giorno, 30)),
        )
        assertEquals(listOf(oltre45), decisione.aTuttoSchermo)
    }

    @Test
    fun `le fasce non hanno tempo finito e restano come sempre`() {
        val fascia = Sforamento(regolaId = 7, tipo = TipiRegola.FASCIA_ORARIA, limiteEfficace = null, minutiOltre = 12, giornoAncora = giorno)
        val decisione = Segnalazioni.decidi(listOf(fascia), giorno, emptySet(), mostraSopra = true, tempiFinitiFatti = setOf("7:$giorno:0:schermo"))
        assertEquals(listOf(fascia), decisione.aTuttoSchermo)
    }

    // --- le notifiche da togliere ----------------------------------------------------

    @Test
    fun `un bonus che riporta sotto il limite toglie la notifica del tempo finito`() {
        val regola = limite(1, instagram, 30)
        val fatti = setOf(TempiFiniti.chiave(1, giorno, 30))
        val segno = TempiFiniti.chiaveTolta(1, giorno, 30)
        assertEquals(
            listOf(NotificaDaTogliere(1, segno)),
            TempiFiniti.daTogliere(listOf(regola), mapOf("1" to 15), indice(instagram to 31 * min), giorno, fatti),
        )
        // (0.16) Una volta sola: col segno non si toglie più (il posto serve al preavviso del limite nuovo).
        assertTrue(TempiFiniti.daTogliere(listOf(regola), mapOf("1" to 15), indice(instagram to 40 * min), giorno, fatti + segno).isEmpty())
        // Senza bonus resta (finito, o già oltre: lì arriva la notifica dello sforamento).
        assertTrue(TempiFiniti.daTogliere(listOf(regola), emptyMap(), indice(instagram to 31 * min), giorno, fatti).isEmpty())
        // Una regola tolta: via.
        assertEquals(listOf(1L), TempiFiniti.daTogliere(emptyList(), emptyMap(), indice(), giorno, fatti).map { it.regolaId })
    }

    // --- le attese: il giro proprio al limite e allo sforamento ------------------------

    private val davantiInstagram = listOf(AppDavanti(instagram))

    @Test
    fun `il prossimo giro arriva proprio quando finisce il tempo`() {
        val regola = limite(1, instagram, 30)
        val attesa = TempiFiniti.prossimoMomento(
            listOf(regola), emptyMap(), indice(instagram to 29 * min + 20 * sec), giorno, emptySet(), emptySet(), davantiInstagram,
        )
        assertEquals(40 * sec, attesa)
        // Col secondo di margine: si guarda a 30:01.
        assertEquals(41 * sec, CadenzaSentinella.attesa(attesa))
    }

    @Test
    fun `detto il tempo finito, il prossimo giro e' allo sforamento, al minuto intero dopo`() {
        val regola = limite(1, instagram, 30)
        val fatti = setOf(TempiFiniti.chiave(1, giorno, 30))
        val attesa = TempiFiniti.prossimoMomento(
            listOf(regola), emptyMap(), indice(instagram to 30 * min + 15 * sec), giorno, fatti, emptySet(), davantiInstagram,
        )
        // 31:00 meno 30:15.
        assertEquals(45 * sec, attesa)
    }

    @Test
    fun `segnalato lo sforamento, niente piu' da aspettare per quella regola`() {
        val regola = limite(1, instagram, 30)
        val fatti = setOf(TempiFiniti.chiave(1, giorno, 30))
        assertNull(
            TempiFiniti.prossimoMomento(
                listOf(regola), emptyMap(), indice(instagram to 31 * min), giorno, fatti, setOf(Segnalazioni.chiave(1, giorno)), davantiInstagram,
            ),
        )
    }

    @Test
    fun `nessuna app davanti che consuma la regola, nessun giro anticipato`() {
        val regola = limite(1, instagram, 30)
        assertNull(TempiFiniti.prossimoMomento(listOf(regola), emptyMap(), indice(instagram to 29 * min), giorno, emptySet(), emptySet(), emptyList()))
        assertNull(
            TempiFiniti.prossimoMomento(
                listOf(regola), emptyMap(), indice(instagram to 29 * min), giorno, emptySet(), emptySet(), listOf(AppDavanti("com.whatsapp")),
            ),
        )
    }

    @Test
    fun `una categoria va al minuto intero dell'app davanti`() {
        // Social 30: Instagram 20:40 (20 min interi) + TikTok 9:50 (9 min interi) = 29 min interi.
        val regola = limite(4, social, 30)
        val fatti = setOf(TempiFiniti.chiave(4, giorno, 30))
        val idx = indice(instagram to 20 * min + 40 * sec, tiktok to 9 * min + 50 * sec)
        assertEquals(29L, idx.minuti(social))
        // Per arrivare a 31 minuti interi con Instagram davanti: 20 s al suo 21° minuto, poi un altro minuto.
        val attesa = TempiFiniti.attesaMinuti(idx, social, 31, davantiInstagram)
        assertEquals(20 * sec + min, attesa)
        assertEquals(attesa, TempiFiniti.prossimoMomento(listOf(regola), emptyMap(), idx, giorno, fatti, emptySet(), davantiInstagram))
    }

    @Test
    fun `un'app di una sessione in corso conta solo dopo la fine della sessione`() {
        val regola = limite(1, instagram, 30)
        val attesa = TempiFiniti.prossimoMomento(
            listOf(regola), emptyMap(), indice(instagram to 29 * min), giorno, emptySet(), emptySet(),
            listOf(AppDavanti(instagram, contaTra = 10 * min)),
        )
        assertEquals(11 * min, attesa)
    }

    @Test
    fun `il totale con due app davanti finisce prima`() {
        val regola = limite(5, "totale", 60)
        val attesa = TempiFiniti.attesaMillis(
            indice(instagram to 58 * min), "totale", 60 * min, listOf(AppDavanti(instagram), AppDavanti("com.whatsapp")),
        )
        assertEquals(1 * min, attesa)
        assertEquals(
            1 * min,
            TempiFiniti.prossimoMomento(listOf(regola), emptyMap(), indice(instagram to 58 * min), giorno, emptySet(), emptySet(), listOf(AppDavanti(instagram), AppDavanti("com.whatsapp"))),
        )
    }

    @Test
    fun `mai meno di un secondo, mai oltre il minuto`() {
        assertEquals(CadenzaSentinella.ATTESA_MINIMA_MS + 0, CadenzaSentinella.attesa(-5_000))
        assertEquals(CadenzaSentinella.INTERVALLO_MS, CadenzaSentinella.attesa(10 * min))
        assertEquals(CadenzaSentinella.INTERVALLO_MS, CadenzaSentinella.attesa(null))
    }
}
